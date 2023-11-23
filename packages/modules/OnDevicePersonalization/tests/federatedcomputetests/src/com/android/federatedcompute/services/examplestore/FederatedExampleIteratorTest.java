/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.federatedcompute.services.examplestore;

import static android.federatedcompute.common.ClientConstants.EXTRA_COLLECTION_NAME;
import static android.federatedcompute.common.ClientConstants.EXTRA_EXAMPLE_ITERATOR_RESULT;
import static android.federatedcompute.common.ClientConstants.STATUS_INTERNAL_ERROR;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.when;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Intent;
import android.federatedcompute.aidl.IExampleStoreCallback;
import android.federatedcompute.aidl.IExampleStoreIterator;
import android.federatedcompute.aidl.IExampleStoreIteratorCallback;
import android.federatedcompute.aidl.IExampleStoreService;
import android.os.Bundle;
import android.os.RemoteException;

import androidx.annotation.Nullable;

import com.android.federatedcompute.services.common.Flags;
import com.android.federatedcompute.services.examplestore.ExampleConsumptionRecorder.SingleQueryRecorder;

import com.google.common.base.Throwables;
import com.google.common.collect.ImmutableList;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import junit.framework.AssertionFailedError;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.nio.charset.Charset;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

@RunWith(JUnit4.class)
public final class FederatedExampleIteratorTest {
    private static final String APP_ID = "com.foo.bar";
    private static final String FAKE_COLLECTION = "/collection1";
    private static final byte[] FAKE_CRITERIA = new byte[] {10, 0, 1};
    private static final byte[] RESUMPTION_TOKEN = "token1".getBytes(Charset.defaultCharset());
    private static final byte[] EXAMPLE_1 = "example1".getBytes(Charset.defaultCharset());
    private static final byte[] EXAMPLE_2 = "example2".getBytes(Charset.defaultCharset());
    private static final long TIMEOUT_SECS = 5L;

    private static final ListeningExecutorService EXECUTOR =
            MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
    private final SingleQueryRecorder mRecorder =
            new ExampleConsumptionRecorder()
                    .createRecorderForTracking(FAKE_COLLECTION, FAKE_CRITERIA);

    private final ExampleStoreServiceProvider mExampleStoreServiceProvider =
            new ExampleStoreServiceProvider() {
                @Override
                @Nullable
                public IExampleStoreService getExampleStoreService() {
                    return new FakeExampleStore();
                }

                @Override
                public boolean bindService(Intent intent) {
                    return true;
                }

                @Override
                public void unbindService() {}
            };

    private FederatedExampleIterator mIterator;
    private AtomicReference<IExampleStoreIterator> mExampleStoreIteratorStub;
    private AtomicReference<Integer> mExampleStoreIteratorError;
    @Mock private Flags mMockFlags;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mIterator =
                new FederatedExampleIterator(
                        APP_ID,
                        FAKE_COLLECTION,
                        FAKE_CRITERIA,
                        RESUMPTION_TOKEN,
                        mRecorder,
                        mExampleStoreServiceProvider,
                        mMockFlags);
        mExampleStoreIteratorStub = new AtomicReference<>(null);
        mExampleStoreIteratorError = new AtomicReference<>(null);
        when(mMockFlags.getAppHostedExampleStoreTimeoutSecs()).thenReturn(TIMEOUT_SECS);
    }

    @Test
    public void testNormalHasGetNextResultSuccess() throws Exception {
        ImmutableList<byte[]> fakeResults = ImmutableList.of(EXAMPLE_1, EXAMPLE_2);
        FakeExampleStoreIterator fakeIterator = new FakeExampleStoreIterator(fakeResults);
        mExampleStoreIteratorStub.set(fakeIterator);

        // Verify the mIterator works in a typical hasNext/Next/hasNext/next/hasNext.
        assertThat(runInBackgroundAndWait(mIterator::hasNext)).isTrue();
        assertThat(runInBackgroundAndWait(mIterator::next)).isEqualTo(EXAMPLE_1);
        assertThat(runInBackgroundAndWait(mIterator::hasNext)).isTrue();
        assertThat(runInBackgroundAndWait(mIterator::next)).isEqualTo(EXAMPLE_2);
        assertThat(runInBackgroundAndWait(mIterator::hasNext)).isFalse();
        // Verify IExampleStoreIterator is mClosed once reach the end of results.
        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
        runInBackgroundAndWait(mIterator::close);
        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
    }

    @Test
    public void testGetNextResultSuccess() throws Exception {
        ImmutableList<byte[]> fakeResults = ImmutableList.of(EXAMPLE_1, EXAMPLE_2);
        FakeExampleStoreIterator fakeIterator = new FakeExampleStoreIterator(fakeResults);
        mExampleStoreIteratorStub.set(fakeIterator);

        // Verify the mIterator works in a typical hasNext/Next/hasNext/next.
        assertThat(runInBackgroundAndWait(mIterator::hasNext)).isTrue();
        assertThat(runInBackgroundAndWait(mIterator::next)).isEqualTo(EXAMPLE_1);
        assertThat(runInBackgroundAndWait(mIterator::hasNext)).isTrue();
        assertThat(runInBackgroundAndWait(mIterator::next)).isEqualTo(EXAMPLE_2);
        ExecutionException exception =
                assertThrows(
                        ExecutionException.class, () -> runInBackgroundAndWait(mIterator::next));
        assertThat(exception).hasCauseThat().isInstanceOf(NoSuchElementException.class);
        // Verify IExampleStoreIterator is mClosed once reach the end of results.
        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
        runInBackgroundAndWait(mIterator::close);
        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
    }

    @Test
    public void testNextResultSuccess() throws Exception {
        ImmutableList<byte[]> fakeResults = ImmutableList.of(EXAMPLE_1, EXAMPLE_2);
        FakeExampleStoreIterator fakeIterator = new FakeExampleStoreIterator(fakeResults);
        mExampleStoreIteratorStub.set(fakeIterator);

        // Verify the mIterator works if only next() is called and hasNext() never called.
        assertThat(runInBackgroundAndWait(mIterator::next)).isEqualTo(EXAMPLE_1);
        assertThat(runInBackgroundAndWait(mIterator::next)).isEqualTo(EXAMPLE_2);
        ExecutionException exception =
                assertThrows(
                        ExecutionException.class, () -> runInBackgroundAndWait(mIterator::next));
        assertThat(exception).hasCauseThat().isInstanceOf(NoSuchElementException.class);
    }

    @Test
    public void testCloseWithoutUse() throws Exception {
        runInBackgroundAndWait(mIterator::close);
    }

    @Test
    public void testCloseAfterHashNext() throws Exception {
        ImmutableList<byte[]> fakeResults = ImmutableList.of(EXAMPLE_1, EXAMPLE_2);
        FakeExampleStoreIterator fakeIterator = new FakeExampleStoreIterator(fakeResults);
        mExampleStoreIteratorStub.set(fakeIterator);

        assertThat(runInBackgroundAndWait(mIterator::hasNext)).isTrue();
        runInBackgroundAndWait(mIterator::close);

        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
    }

    @Test
    public void testCloseAfterNext() throws Exception {
        ImmutableList<byte[]> fakeResults = ImmutableList.of(EXAMPLE_1, EXAMPLE_2);
        FakeExampleStoreIterator fakeIterator = new FakeExampleStoreIterator(fakeResults);
        mExampleStoreIteratorStub.set(fakeIterator);

        assertThat(runInBackgroundAndWait(mIterator::next)).isEqualTo(EXAMPLE_1);
        assertThat(runInBackgroundAndWait(mIterator::hasNext)).isTrue();
        runInBackgroundAndWait(mIterator::close);

        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
    }

    @Test
    public void testStartQueryReturnsErrorWhenCallHasNext() throws Exception {
        mExampleStoreIteratorError.set(STATUS_INTERNAL_ERROR);
        ExecutionException exception =
                assertThrows(
                        ExecutionException.class, () -> runInBackgroundAndWait(mIterator::hasNext));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
        assertThat(exception)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("onStartQueryFailure collection /collection1 error code 1");
    }

    @Test
    public void testStartQueryReturnsErrorWhenCallNext() throws Exception {
        mExampleStoreIteratorError.set(STATUS_INTERNAL_ERROR);
        ExecutionException exception =
                assertThrows(
                        ExecutionException.class, () -> runInBackgroundAndWait(mIterator::next));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
        assertThat(exception)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("onStartQueryFailure collection /collection1 error code 1");
    }

    @Test
    public void testExampleStoreIteratorReturnsErrorWhenCallNext() throws Exception {
        ImmutableList<byte[]> fakeResults = ImmutableList.of(EXAMPLE_1);
        FakeExampleStoreIterator fakeIterator =
                new FakeExampleStoreIterator(fakeResults, STATUS_INTERNAL_ERROR);
        mExampleStoreIteratorStub.set(fakeIterator);

        runInBackgroundAndWait(mIterator::next);

        ExecutionException exception =
                assertThrows(
                        ExecutionException.class, () -> runInBackgroundAndWait(mIterator::next));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
        assertThat(exception)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("OnIteratorNextFailure: /collection1: 1");
        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
        runInBackgroundAndWait(mIterator::close);
        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
    }

    @Test
    public void testExampleStoreIteratorReturnsErrorWhenCallHasNext() throws Exception {
        ImmutableList<byte[]> fakeResults = ImmutableList.of(EXAMPLE_1);
        FakeExampleStoreIterator fakeIterator =
                new FakeExampleStoreIterator(fakeResults, STATUS_INTERNAL_ERROR);
        mExampleStoreIteratorStub.set(fakeIterator);

        runInBackgroundAndWait(mIterator::next);

        ExecutionException exception =
                assertThrows(
                        ExecutionException.class, () -> runInBackgroundAndWait(mIterator::hasNext));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
        assertThat(exception)
                .hasCauseThat()
                .hasMessageThat()
                .isEqualTo("OnIteratorNextFailure: /collection1: 1");
        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
        runInBackgroundAndWait(mIterator::close);
        assertThat(fakeIterator.mClosed.get()).isEqualTo(1);
    }

    @Test
    public void testCallsAfterClose() throws Exception {
        runInBackgroundAndWait(mIterator::close);

        ExecutionException exception =
                assertThrows(
                        ExecutionException.class, () -> runInBackgroundAndWait(mIterator::hasNext));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
        exception =
                assertThrows(
                        ExecutionException.class, () -> runInBackgroundAndWait(mIterator::next));
        assertThat(exception).hasCauseThat().isInstanceOf(IllegalStateException.class);
    }

    private <T> T runInBackgroundAndWait(Callable<T> callable) throws Exception {
        return waitOnFuture(runInBackground(callable));
    }

    private void runInBackgroundAndWait(Runnable runnable) throws Exception {
        waitOnFuture(
                runInBackground(
                        () -> {
                            runnable.run();
                            return null;
                        }));
    }

    private static <T> T waitOnFuture(Future<T> future) throws Exception {
        long maxWaitTimeMs = SECONDS.toMillis(10);
        long sleepPerIterMs = 50;
        for (int i = 0; i < maxWaitTimeMs / sleepPerIterMs && !future.isDone(); i++) {
            Thread.sleep(sleepPerIterMs);
        }
        // Run any final UI tasks one more time.
        if (!future.isDone()) {
            throw new AssertionFailedError(
                    "Future " + future + " expected to complete, but never did");
        }
        return future.get();
    }

    private static <T> Future<T> runInBackground(Callable<T> callable) throws Exception {
        CountDownLatch started = new CountDownLatch(1);
        ListenableFuture<T> result =
                EXECUTOR.submit(
                        () -> {
                            started.countDown();
                            System.err.println("Running on thread " + Thread.currentThread());
                            try {
                                return callable.call();
                            } catch (Throwable e) {
                                System.err.println(
                                        "Error in runInBackground callable: "
                                                + Throwables.getStackTraceAsString(e));
                                throw e;
                            }
                        });
        // Block until the task has at least been started on the background thread (but not
        // necessarily
        // completed).
        started.await(10, SECONDS);
        return result;
    }

    private final class FakeExampleStore extends IExampleStoreService.Stub {
        @Override
        public void startQuery(Bundle params, IExampleStoreCallback callback)
                throws RemoteException {
            String collection = params.getString(EXTRA_COLLECTION_NAME);
            if (!collection.equals(FAKE_COLLECTION)) {
                throw new AssertionFailedError(
                        String.format(
                                "startQuery called with wrong arguments: collection=%s",
                                collection));
            }
            IExampleStoreIterator mIterator = mExampleStoreIteratorStub.get();
            Integer errorStatus = mExampleStoreIteratorError.get();
            if (mIterator != null) {
                callback.onStartQuerySuccess(mIterator);
            } else if (errorStatus != null) {
                callback.onStartQueryFailure(errorStatus);
            } else {
                throw new RuntimeException(
                        "Must provide either an IExampleStoreIterator or an error Status");
            }
        }
    }

    private static final class FakeExampleStoreIterator extends IExampleStoreIterator.Stub {
        private final AtomicInteger mClosed = new AtomicInteger(0);
        private final Iterator<byte[]> mExampleResults;
        private final Integer mFinalError;

        private FakeExampleStoreIterator(List<byte[]> exampleResults) {
            this(exampleResults, 0);
        }

        private FakeExampleStoreIterator(Iterable<byte[]> exampleResults, int finalError) {
            this.mExampleResults = exampleResults.iterator();
            this.mFinalError = finalError;
        }

        @Override
        public void next(IExampleStoreIteratorCallback callback) throws RemoteException {
            assertThat(mClosed.get()).isEqualTo(0);
            synchronized (this) {
                if (mExampleResults.hasNext()) {
                    byte[] nextProtoResult = mExampleResults.next();
                    Bundle bundle = new Bundle();
                    bundle.putByteArray(EXTRA_EXAMPLE_ITERATOR_RESULT, nextProtoResult);
                    callback.onIteratorNextSuccess(bundle);
                } else if (mFinalError == 0) {
                    callback.onIteratorNextSuccess(null);
                } else {
                    callback.onIteratorNextFailure(mFinalError);
                }
            }
        }

        @Override
        public void close() {
            mClosed.getAndIncrement();
        }
    }
}
