package com.alibaba.druid.pool.ha.balance;

import java.sql.SQLException;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;

import com.alibaba.druid.pool.ha.DataSourceHolder;
import com.alibaba.druid.pool.ha.MultiConnectionHolder;
import com.alibaba.druid.pool.ha.MultiDataSource;
import com.alibaba.druid.pool.ha.MultiDataSourceConnection;
import com.alibaba.druid.util.ThreadLocalRandom;

public class WeightBalancer implements Balancer {

    private MultiDataSource multiDataSource;
    private int             totalWeight = 0;

    @Override
    public void init(MultiDataSource multiDataSource) {
        this.multiDataSource = multiDataSource;
    }

    public void afterDataSourceChanged(Object event) {
        computeTotalWeight();
    }

    public int produceRandomNumber() {
        if (totalWeight == 0) {
            return 0;
        }

        return ThreadLocalRandom.current().nextInt(totalWeight);
    }
    
    public void computeTotalWeight() {
        int totalWeight = 0;
        for (DataSourceHolder holder : multiDataSource.getDataSources().values()) {
            if (!holder.isEnable()) {
                holder.setWeightRegionBegin(-1);
                holder.setWeightRegionEnd(-1);
                continue;
            }
            holder.setWeightRegionBegin(totalWeight);
            totalWeight += holder.getWeight();
            holder.setWeightRegionEnd(totalWeight);
        }
        this.totalWeight = totalWeight;

        multiDataSource.notFailSignal();
    }

    @Override
    public MultiConnectionHolder getConnection(MultiDataSourceConnection connectionProxy, String sql)
                                                                                                     throws SQLException {
        MultiDataSource multiDataSource = connectionProxy.getMultiDataSource();

        long maxWaitMillis = multiDataSource.getMaxWaitMillis();

        long startNano = -1;
        if (maxWaitMillis > 0) {
            startNano = System.nanoTime();
        }

        DataSourceHolder dataSource = null;

        final int MAX_RETRY = 10;
        for (int i = 0; i < MAX_RETRY; ++i) {
            int randomNumber = produceRandomNumber();
            DataSourceHolder first = null;

            boolean needRetry = false;
            for (DataSourceHolder item : multiDataSource.getDataSources().values()) {
                if (first == null) {
                    first = item;
                }
                if (randomNumber >= item.getWeightRegionBegin() && randomNumber < item.getWeightRegionEnd()) {
                    if (!item.isEnable()) {
                        needRetry = true;
                        break;
                    }

                    if (item.getDataSource().isBusy()) {
                        multiDataSource.incrementBusySkipCount();
                        needRetry = true;
                        break;
                    }

                    dataSource = item;
                }
            }

            if (needRetry) {
                multiDataSource.incrementRetryGetConnectionCount();
                continue;
            }

            if (dataSource == null) {
                dataSource = first;
            }

            if (dataSource == null && i != MAX_RETRY - 1) {
                Lock lock = multiDataSource.getLock();
                Condition notFail = multiDataSource.getNotFail();
                lock.lock();
                try {
                    if (multiDataSource.getEnabledDataSourceCount() == 0) {
                        try {
                            if (maxWaitMillis > 0) {
                                long nano = System.nanoTime() - startNano;
                                long restNano = maxWaitMillis * 1000 * 1000 - nano;
                                if (restNano > 0) {
                                    notFail.awaitNanos(restNano);
                                } else {
                                    break;
                                }
                            } else {
                                notFail.await();
                            }
                            continue;
                        } catch (InterruptedException e) {
                            throw new SQLException("interrupted", e);
                        }
                    }
                } finally {
                    lock.unlock();
                }
            }
            break;
        }

        if (dataSource == null) {
            throw new SQLException("cannot get connection. enabledDataSourceCount "
                                   + multiDataSource.getEnabledDataSourceCount());
        }

        return dataSource.getConnection();
    }

}
