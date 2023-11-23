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

package com.android.federatedcompute.services.training;

import android.annotation.Nullable;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.federatedcompute.aidl.IResultHandlingService;
import android.os.IBinder;
import android.util.Log;

import com.android.federatedcompute.services.common.Flags;

import com.google.common.util.concurrent.SettableFuture;
import com.google.common.util.concurrent.UncheckedExecutionException;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Implementation of the ResultHandlingServiceProvider interface. */
public final class ResultHandlingServiceProviderImpl implements ResultHandlingServiceProvider {
    private static final String TAG = "ResultHandlingServiceProviderImpl";
    private static final Executor SINGLE_THREAD_EXECUTOR = Executors.newSingleThreadExecutor();
    private final Context mContext;
    private IResultHandlingService mResultHandlingService;
    private SettableFuture<IResultHandlingService> mResultHandlingServiceFuture =
            SettableFuture.create();
    private boolean mBound;
    private Flags mFlags;

    public ResultHandlingServiceProviderImpl(Context context, Flags flags) {
        this.mContext = context;
        this.mFlags = flags;
    }

    ServiceConnection mServiceConnection =
            new ServiceConnection() {
                @Override
                public void onServiceConnected(ComponentName name, IBinder service) {
                    if (service == null) {
                        Log.e(TAG, "onServiceConnected() received null binder");
                        return;
                    }
                    mResultHandlingServiceFuture.set(
                            IResultHandlingService.Stub.asInterface(service));
                    mBound = true;
                }

                @Override
                public void onServiceDisconnected(ComponentName name) {
                    reset();
                    Log.d(TAG, "Connection unexpectedly disconnected");
                }
            };

    @Override
    @Nullable
    public IResultHandlingService getResultHandlingService() {
        return mResultHandlingService;
    }

    @Override
    public boolean bindService(Intent intent) {
        if (!mContext.bindService(
                intent, Context.BIND_AUTO_CREATE, SINGLE_THREAD_EXECUTOR, mServiceConnection)) {
            Log.e(TAG, "Unable to bind to ResultHandlingService intent: " + intent);
            return false;
        }
        try {
            mResultHandlingService =
                    mResultHandlingServiceFuture.get(
                            mFlags.getResultHandlingBindServiceTimeoutSecs(), TimeUnit.SECONDS);
        } catch (TimeoutException e) {
            throw new IllegalStateException(
                    String.format(
                            "Service connection time out (%ss) for app hosted"
                                    + " ResultHandlingService.",
                            mFlags.getResultHandlingBindServiceTimeoutSecs()),
                    e);
        } catch (ExecutionException e) {
            throw new UncheckedExecutionException(e);
        } catch (InterruptedException e) {
            Log.e(TAG, "ResultHandlingService interrupted", e);
            unbindService();
            return false;
        }
        return true;
    }

    @Override
    public void unbindService() {
        if (mBound) {
            mContext.unbindService(mServiceConnection);
            reset();
        }
    }

    private void reset() {
        mResultHandlingServiceFuture = SettableFuture.create();
        mResultHandlingService = null;
        mBound = false;
    }
}
