/*
 * Copyright (C) 2013 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package android.hardware.cts.helpers.sensoroperations;

import junit.framework.Assert;

import android.hardware.cts.helpers.SensorStats;
import android.os.SystemClock;

import java.util.ArrayList;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A {@link ISensorOperation} that executes a set of children {@link ISensorOperation}s in parallel.
 * The children are run in parallel but are given an index label in the order they are added. This
 * class can be combined to compose complex {@link ISensorOperation}s.
 */
public class ParallelSensorOperation extends AbstractSensorOperation {
    public static final String STATS_TAG = "parallel";

    private final List<ISensorOperation> mOperations = new LinkedList<ISensorOperation>();
    private final Long mTimeout;
    private final TimeUnit mTimeUnit;

    /**
     * Constructor for the {@link ParallelSensorOperation} without a timeout.
     */
    // TODO: sensor tests must always provide a timeout to prevent tests from running forever
    public ParallelSensorOperation() {
        mTimeout = null;
        mTimeUnit = null;
    }

    /**
     * Constructor for the {@link ParallelSensorOperation} with a timeout.
     */
    public ParallelSensorOperation(long timeout, TimeUnit timeUnit) {
        if (timeUnit == null) {
            throw new IllegalArgumentException("Arguments cannot be null");
        }
        mTimeout = timeout;
        mTimeUnit = timeUnit;
    }

    /**
     * Add a set of {@link ISensorOperation}s.
     */
    public void add(ISensorOperation ... operations) {
        for (ISensorOperation operation : operations) {
            if (operation == null) {
                throw new IllegalArgumentException("Arguments cannot be null");
            }
            mOperations.add(operation);
        }
    }

    /**
     * Executes the {@link ISensorOperation}s in parallel. If an exception occurs one or more
     * operations, the first exception will be thrown once all operations are completed.
     */
    @Override
    public void execute() throws InterruptedException {
        int operationsCount = mOperations.size();
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                operationsCount,
                operationsCount,
                1 /* keepAliveTime */,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<Runnable>());
        executor.allowCoreThreadTimeOut(true);
        executor.prestartAllCoreThreads();

        ArrayList<Future<ISensorOperation>> futures = new ArrayList<Future<ISensorOperation>>();
        for (final ISensorOperation operation : mOperations) {
            Future<ISensorOperation> future = executor.submit(new Callable<ISensorOperation>() {
                @Override
                public ISensorOperation call() throws Exception {
                    operation.execute();
                    return operation;
                }
            });
            futures.add(future);
        }

        Long executionTimeNs = null;
        if (mTimeout != null) {
            executionTimeNs = SystemClock.elapsedRealtimeNanos()
                    + TimeUnit.NANOSECONDS.convert(mTimeout, mTimeUnit);
        }

        boolean hasAssertionErrors = false;
        ArrayList<Integer> timeoutIndices = new ArrayList<Integer>();
        ArrayList<Throwable> exceptions = new ArrayList<Throwable>();
        for (int i = 0; i < operationsCount; ++i) {
            Future<ISensorOperation> future = futures.get(i);
            try {
                ISensorOperation operation = getFutureResult(future, executionTimeNs);
                addSensorStats(STATS_TAG, i, operation.getStats());
            } catch (ExecutionException e) {
                // extract the exception thrown by the worker thread
                Throwable cause = e.getCause();
                hasAssertionErrors |= (cause instanceof AssertionError);
                exceptions.add(e.getCause());
                addSensorStats(STATS_TAG, i, mOperations.get(i).getStats());
            } catch (TimeoutException e) {
                // we log, but we also need to interrupt the operation to terminate cleanly
                timeoutIndices.add(i);
                future.cancel(true /* mayInterruptIfRunning */);
            } catch (InterruptedException e) {
                // clean-up after ourselves by interrupting all the worker threads, and propagate
                // the interruption status, so we stop the outer loop as well
                executor.shutdownNow();
                throw e;
            }
        }

        String summary = getSummaryMessage(exceptions, timeoutIndices);
        if (hasAssertionErrors) {
            getStats().addValue(SensorStats.ERROR, summary);
        }
        if (!exceptions.isEmpty() || !timeoutIndices.isEmpty()) {
            Assert.fail(summary);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public ParallelSensorOperation clone() {
        ParallelSensorOperation operation = new ParallelSensorOperation();
        for (ISensorOperation subOperation : mOperations) {
            operation.add(subOperation.clone());
        }
        return operation;
    }

    /**
     * Helper method that waits for a {@link Future} to complete, and returns its result.
     */
    private ISensorOperation getFutureResult(Future<ISensorOperation> future, Long timeoutNs)
            throws ExecutionException, TimeoutException, InterruptedException {
        if (timeoutNs == null) {
            return future.get();
        }
        // cap timeout to 1ns so that join doesn't block indefinitely
        long waitTimeNs = Math.max(timeoutNs - SystemClock.elapsedRealtimeNanos(), 1);
        return future.get(waitTimeNs, TimeUnit.NANOSECONDS);
    }

    /**
     * Helper method for joining the exception and timeout messages used in assertions.
     */
    private String getSummaryMessage(List<Throwable> exceptions, List<Integer> timeoutIndices) {
        StringBuilder sb = new StringBuilder();
        for (Throwable exception : exceptions) {
            sb.append(exception.toString()).append(", ");
        }

        if (!timeoutIndices.isEmpty()) {
            sb.append("Operation");
            if (timeoutIndices.size() != 1) {
                sb.append("s");
            }
            sb.append(" [");
            for (Integer index : timeoutIndices) {
                sb.append(index).append(", ");
            }
            sb.append("] timed out");
        }

        return sb.toString();
    }
}
