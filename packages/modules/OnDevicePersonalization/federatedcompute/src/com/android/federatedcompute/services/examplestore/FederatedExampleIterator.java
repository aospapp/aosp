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
import static android.federatedcompute.common.ClientConstants.EXTRA_EXAMPLE_ITERATOR_CRITERIA;
import static android.federatedcompute.common.ClientConstants.EXTRA_EXAMPLE_ITERATOR_RESULT;
import static android.federatedcompute.common.ClientConstants.EXTRA_EXAMPLE_ITERATOR_RESUMPTION_TOKEN;

import static java.util.concurrent.TimeUnit.SECONDS;

import android.content.Intent;
import android.federatedcompute.aidl.IExampleStoreCallback;
import android.federatedcompute.aidl.IExampleStoreIterator;
import android.federatedcompute.aidl.IExampleStoreIteratorCallback;
import android.federatedcompute.aidl.IExampleStoreService;
import android.federatedcompute.common.ClientConstants;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.RemoteException;
import android.util.Log;
import android.util.Pair;

import com.android.federatedcompute.services.common.Flags;
import com.android.federatedcompute.services.examplestore.ExampleConsumptionRecorder.SingleQueryRecorder;
import com.android.internal.util.Preconditions;

import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.io.Closeable;
import java.util.NoSuchElementException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import javax.annotation.Nullable;

/**
 * Implementation of an iterator that reads data from an app-hosted example store. This class's
 * methods generally do blocking IO, the class is not thread safe, and it should not be used on the
 * main thread.
 */
public final class FederatedExampleIterator implements ExampleIterator {
    private static final String TAG = "FederatedExampleIterator";
    // TODO: replace with PH flag.
    private static final long TIMEOUT_SECS = 2L;

    private boolean mClosed;
    private final String mPackageName;
    private final String mCollection;
    private final byte[] mCriteria;
    @Nullable private ProxyIteratorWrapper mIteratorWrapper;
    @Nullable private IteratorResult mCurrentResult;
    private byte[] mResumptionToken;
    @Nullable private final SingleQueryRecorder mRecorder;
    private final ExampleStoreServiceProvider mExampleStoreServiceProvider;

    private enum NextResultState {
        /**
         * It is unknown whether there is a next result or not. To know, we need to try and fetch
         * one first.
         */
        UNKNOWN,
        /**
         * It is known that the end of the iterator has been reached, and no more results will
         * become available.
         */
        END_OF_ITERATOR,
        /** It is known that another result is available. */
        RESULT_AVAILABLE,
    }

    private NextResultState mNextResultState;
    private Flags mFlags;

    public FederatedExampleIterator(
            String packageName,
            String collectionName,
            byte[] criteria,
            byte[] resumptionToken,
            SingleQueryRecorder recorder,
            ExampleStoreServiceProvider exampleStoreServiceProvider,
            Flags flags) {
        this.mPackageName = packageName;
        this.mCollection = collectionName;
        this.mCriteria = criteria;
        this.mResumptionToken = resumptionToken;
        this.mNextResultState = NextResultState.UNKNOWN;
        this.mCurrentResult = null;
        this.mClosed = false;
        this.mRecorder = recorder;
        this.mExampleStoreServiceProvider = exampleStoreServiceProvider;
        this.mFlags = flags;
    }

    @Override
    public boolean hasNext() throws InterruptedException {
        Preconditions.checkState(!mClosed, "hasNext() called after close()");
        Preconditions.checkState(!isMainThread(), "hasNext() called on main thread");
        if (mNextResultState != NextResultState.UNKNOWN) {
            return mNextResultState == NextResultState.RESULT_AVAILABLE;
        }
        getNextResult();
        return mNextResultState == NextResultState.RESULT_AVAILABLE;
    }

    @Override
    public byte[] next() throws InterruptedException {
        Preconditions.checkState(!mClosed, "next() called after close()");
        Preconditions.checkState(!isMainThread(), "next() called on main thread");
        if (mNextResultState == NextResultState.UNKNOWN) {
            getNextResult();
        }
        if (mNextResultState == NextResultState.END_OF_ITERATOR) {
            throw new NoSuchElementException("next() called but end of iterator reached");
        }
        byte[] result = mCurrentResult.mResultBytes;
        this.mResumptionToken = mCurrentResult.mResumptionToken;
        if (mRecorder != null) {
            mRecorder.incrementAndUpdateResumptionToken(mCurrentResult.mResumptionToken);
        }
        mCurrentResult = null;
        mNextResultState = NextResultState.UNKNOWN;
        return result;
    }

    @Override
    public void close() {
        Preconditions.checkState(!isMainThread(), "close() called on main thread");
        if (mClosed) {
            return;
        }
        mClosed = true;
        if (mIteratorWrapper != null) {
            mIteratorWrapper.close();
        }
    }

    private void getNextResult() throws InterruptedException {
        if (mIteratorWrapper == null) {
            Log.i(TAG, "iterator wrapper is null");
            connectToClientExampleStoreService();
        }
        mCurrentResult = mIteratorWrapper.next();
        if (mCurrentResult == null) {
            mNextResultState = NextResultState.END_OF_ITERATOR;
            Log.d(TAG, "App example store returns null, end of iterator.");
        } else {
            mNextResultState = NextResultState.RESULT_AVAILABLE;
        }
    }

    private void connectToClientExampleStoreService() throws InterruptedException {
        Intent intent = new Intent();
        intent.setAction(ClientConstants.EXAMPLE_STORE_ACTION).setPackage(mPackageName);
        intent.setData(
                new Uri.Builder().scheme("app").authority(mPackageName).path(mCollection).build());
        Log.d(TAG, "Attempting to bind to example store service: " + intent);
        if (!mExampleStoreServiceProvider.bindService(intent)) {
            Log.w(TAG, "bindService failed for example store service: " + intent);
            mExampleStoreServiceProvider.unbindService();
            return;
        }
        IExampleStoreService exampleStoreService =
                mExampleStoreServiceProvider.getExampleStoreService();
        Bundle bundle = new Bundle();
        bundle.putString(EXTRA_COLLECTION_NAME, mCollection);
        bundle.putByteArray(EXTRA_EXAMPLE_ITERATOR_RESUMPTION_TOKEN, this.mResumptionToken);
        bundle.putByteArray(EXTRA_EXAMPLE_ITERATOR_CRITERIA, mCriteria);

        SettableFuture<Pair<IExampleStoreIterator, Integer>> iteratorOrFailureFuture =
                SettableFuture.create();
        try {
            try {
                exampleStoreService.startQuery(
                        bundle,
                        new IExampleStoreCallback.Stub() {
                            @Override
                            public void onStartQuerySuccess(IExampleStoreIterator iterator) {
                                Log.d(TAG, "Acquire iterator");
                                iteratorOrFailureFuture.set(Pair.create(iterator, null));
                            }

                            @Override
                            public void onStartQueryFailure(int errorCode) {
                                Log.e(TAG, "Could not acquire iterator: " + errorCode);
                                iteratorOrFailureFuture.set(Pair.create(null, errorCode));
                            }
                        });
            } catch (RemoteException e) {
                Log.e(TAG, "StartQuery failure: " + e.getMessage());
                throw new IllegalStateException(e);
            }

            Pair<IExampleStoreIterator, Integer> iteratorOrFailure;
            try {
                iteratorOrFailure =
                        iteratorOrFailureFuture.get(
                                mFlags.getAppHostedExampleStoreTimeoutSecs(), TimeUnit.SECONDS);
            } catch (ExecutionException e) {
                // Should not happen.
                throw new UncheckedExecutionException(e);
            } catch (TimeoutException e) {
                Log.e(TAG, "startQuery timed out: ", e);
                throw new IllegalStateException(
                        String.format(
                                "startQuery timed out (%ss): %s",
                                mFlags.getAppHostedExampleStoreTimeoutSecs(), this.mCollection),
                        e);
            }
            if (iteratorOrFailure.second != null) {
                throw new IllegalStateException(
                        String.format(
                                "onStartQueryFailure collection %s error code %d",
                                this.mCollection, iteratorOrFailure.second));
            }
            Log.d(TAG, "Wrapping IExampleStoreIterator");
            mIteratorWrapper = new ProxyIteratorWrapper(iteratorOrFailure.first);
        } catch (Exception e) {
            // If any exception is thrown in try block, we first call unbindService to avoid service
            // connection hanging.
            Log.d(TAG, "Unbinding from service due to exception", e);
            mExampleStoreServiceProvider.unbindService();
            throw e;
        }
    }

    private static final class IteratorResult {
        private final byte[] mResultBytes;
        private final byte[] mResumptionToken;

        private IteratorResult(byte[] resultBytes, byte[] resumptionToken) {
            this.mResultBytes = resultBytes;
            this.mResumptionToken = resumptionToken;
        }
    }

    private final class ProxyIteratorWrapper implements Closeable {
        private final IExampleStoreIterator mExampleStoreIterator;
        private boolean mIteratorClosed = false;
        private final FederatedExampleStoreIteratorCallback mIteratorCallback =
                new FederatedExampleStoreIteratorCallback();

        private ProxyIteratorWrapper(IExampleStoreIterator iterator) {
            this.mExampleStoreIterator = iterator;
        }

        private IteratorResult next() {
            Preconditions.checkState(
                    !mIteratorClosed, "next() called after ProxyIteratorWrapper close()");
            SettableFuture<Pair<IteratorResult, Integer>> resultOrErrorCodeFuture =
                    SettableFuture.create();
            mIteratorCallback.initializeForNextResult(resultOrErrorCodeFuture);
            try {
                mExampleStoreIterator.next(mIteratorCallback);
            } catch (RemoteException e) {
                close();
                throw new IllegalStateException("Failed to call next()", e);
            }

            Pair<IteratorResult, Integer> resultOrFailure;
            try {
                resultOrFailure = resultOrErrorCodeFuture.get(TIMEOUT_SECS, SECONDS);
            } catch (InterruptedException e) {
                close();
                throw new IllegalStateException(e);
            } catch (ExecutionException e) {
                close();
                throw new IllegalStateException("Failed to get iterator result", e);
            } catch (TimeoutException e) {
                close();
                throw new IllegalStateException("call iterator next() timeout", e);
            }

            if (resultOrFailure.second != null) {
                close();
                throw new IllegalStateException(
                        "OnIteratorNextFailure: " + mCollection + ": " + resultOrFailure.second);
            }
            if (resultOrFailure.first == null) {
                close();
            }
            return resultOrFailure.first;
        }

        @Override
        public void close() {
            if (mIteratorClosed) {
                return;
            }
            mIteratorClosed = true;
            if (mExampleStoreIterator != null) {
                try {
                    mExampleStoreIterator.close();
                } catch (RemoteException e) {
                    Log.w(TAG, "Exception during call to IExampleStoreIterator.close", e);
                }
            }
            Log.d(TAG, "Unbinding from service due to iterator being closed");
            mExampleStoreServiceProvider.unbindService();
        }
    }

    private static final class FederatedExampleStoreIteratorCallback
            extends IExampleStoreIteratorCallback.Stub {
        private SettableFuture<Pair<IteratorResult, Integer>> mResultOrErrorCodeFuture;

        void initializeForNextResult(
                SettableFuture<Pair<IteratorResult, Integer>> resultOrErrorCodeFuture) {
            this.mResultOrErrorCodeFuture = resultOrErrorCodeFuture;
        }

        @Override
        public void onIteratorNextSuccess(Bundle result) {
            if (result == null) {
                // Reach the end of data collection.
                mResultOrErrorCodeFuture.set(Pair.create(null, null));
                return;
            }
            byte[] example = result.getByteArray(EXTRA_EXAMPLE_ITERATOR_RESULT);
            if (example == null) {
                // Reaches the end of data collection.
                mResultOrErrorCodeFuture.set(Pair.create(null, null));
                return;
            }

            byte[] resumptionToken = result.getByteArray(EXTRA_EXAMPLE_ITERATOR_RESUMPTION_TOKEN);
            if (resumptionToken == null) {
                resumptionToken = new byte[] {};
            }
            mResultOrErrorCodeFuture.set(
                    Pair.create(new IteratorResult(example, resumptionToken), null));
        }

        @Override
        public void onIteratorNextFailure(int errorCode) {
            mResultOrErrorCodeFuture.set(Pair.create(null, errorCode));
        }
    }

    private static boolean isMainThread() {
        return Looper.myLooper() == Looper.getMainLooper();
    }
}
