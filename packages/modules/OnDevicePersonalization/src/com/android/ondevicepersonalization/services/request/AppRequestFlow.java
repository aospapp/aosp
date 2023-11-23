/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.ondevicepersonalization.services.request;

import android.annotation.NonNull;
import android.content.Context;
import android.ondevicepersonalization.Constants;
import android.ondevicepersonalization.ExecuteInput;
import android.ondevicepersonalization.ExecuteOutput;
import android.ondevicepersonalization.SlotResult;
import android.ondevicepersonalization.aidl.IExecuteCallback;
import android.os.Bundle;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.data.DataAccessServiceImpl;
import com.android.ondevicepersonalization.services.data.events.EventsDao;
import com.android.ondevicepersonalization.services.data.events.Query;
import com.android.ondevicepersonalization.services.manifest.AppManifestConfigHelper;
import com.android.ondevicepersonalization.services.process.IsolatedServiceInfo;
import com.android.ondevicepersonalization.services.process.ProcessUtils;
import com.android.ondevicepersonalization.services.util.CryptUtils;
import com.android.ondevicepersonalization.services.util.OnDevicePersonalizationFlatbufferUtils;

import com.google.common.util.concurrent.AsyncCallable;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Handles a surface package request from an app or SDK.
 */
public class AppRequestFlow {
    private static final String TAG = "AppRequestFlow";
    private static final String TASK_NAME = "AppRequest";
    @NonNull
    private final String mCallingPackageName;
    @NonNull
    private final String mServicePackageName;
    @NonNull
    private final PersistableBundle mParams;
    @NonNull
    private final IExecuteCallback mCallback;
    @NonNull
    private final Context mContext;
    @NonNull
    private String mServiceClassName;

    @NonNull
    private final ListeningExecutorService mExecutorService;

    public AppRequestFlow(
            @NonNull String callingPackageName,
            @NonNull String servicePackageName,
            @NonNull PersistableBundle params,
            @NonNull IExecuteCallback callback,
            @NonNull Context context) {
        this(callingPackageName, servicePackageName, params,
                callback, context, OnDevicePersonalizationExecutors.getBackgroundExecutor());
    }

    @VisibleForTesting
    AppRequestFlow(
            @NonNull String callingPackageName,
            @NonNull String servicePackageName,
            @NonNull PersistableBundle params,
            @NonNull IExecuteCallback callback,
            @NonNull Context context,
            @NonNull ListeningExecutorService executorService) {
        Log.d(TAG, "AppRequestFlow created.");
        mCallingPackageName = Objects.requireNonNull(callingPackageName);
        mServicePackageName = Objects.requireNonNull(servicePackageName);
        mParams = Objects.requireNonNull(params);
        mCallback = Objects.requireNonNull(callback);
        mContext = Objects.requireNonNull(context);
        mExecutorService = Objects.requireNonNull(executorService);
    }

    /** Runs the request processing flow. */
    public void run() {
        var unused = Futures.submit(() -> this.processRequest(), mExecutorService);
    }

    private void processRequest() {
        try {
            mServiceClassName = Objects.requireNonNull(
                    AppManifestConfigHelper.getServiceNameFromOdpSettings(
                            mContext, mServicePackageName));
            ListenableFuture<ExecuteOutput> resultFuture = FluentFuture.from(
                            ProcessUtils.loadIsolatedService(
                                    TASK_NAME, mServicePackageName, mContext))
                    .transformAsync(
                            result -> executeAppRequest(result),
                            mExecutorService
                    )
                    .transform(
                            result -> {
                                return result.getParcelable(
                                        Constants.EXTRA_RESULT, ExecuteOutput.class);
                            },
                            mExecutorService
                    );

            ListenableFuture<Long> queryIdFuture = FluentFuture.from(resultFuture)
                    .transformAsync(input -> logQuery(input), mExecutorService);

            ListenableFuture<List<String>> slotResultTokensFuture =
                    Futures.whenAllSucceed(resultFuture, queryIdFuture)
                            .callAsync(new AsyncCallable<List<String>>() {
                                @Override
                                public ListenableFuture<List<String>> call() {
                                    return createTokens(resultFuture, queryIdFuture);
                                }
                            }, mExecutorService);

            Futures.addCallback(
                    slotResultTokensFuture,
                    new FutureCallback<List<String>>() {
                        @Override
                        public void onSuccess(List<String> slotResultTokens) {
                            sendResult(slotResultTokens);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            Log.w(TAG, "Request failed.", t);
                            sendErrorResult(Constants.STATUS_INTERNAL_ERROR);
                        }
                    },
                    mExecutorService);
        } catch (Exception e) {
            Log.e(TAG, "Could not process request.", e);
            sendErrorResult(Constants.STATUS_INTERNAL_ERROR);
        }
    }

    private ListenableFuture<Bundle> executeAppRequest(IsolatedServiceInfo isolatedServiceInfo) {
        Log.d(TAG, "executeAppRequest() started.");
        Bundle serviceParams = new Bundle();
        ExecuteInput input =
                new ExecuteInput.Builder()
                        .setAppPackageName(mCallingPackageName)
                        .setAppParams(mParams)
                        .build();
        serviceParams.putParcelable(Constants.EXTRA_INPUT, input);
        DataAccessServiceImpl binder = new DataAccessServiceImpl(
                mServicePackageName, mContext, true, null);
        serviceParams.putBinder(Constants.EXTRA_DATA_ACCESS_SERVICE_BINDER, binder);
        return ProcessUtils.runIsolatedService(
                isolatedServiceInfo, mServiceClassName, Constants.OP_SELECT_CONTENT, serviceParams);
    }

    private ListenableFuture<Long> logQuery(ExecuteOutput result) {
        Log.d(TAG, "logQuery() started.");
        // TODO(b/228200518): Validate that slotIds and bidIds are present in REMOTE_DATA.
        // TODO(b/259950173): Add certDigest to queryData.
        byte[] queryData = OnDevicePersonalizationFlatbufferUtils.createQueryData(
                mServicePackageName, null, result);
        Query query = new Query.Builder()
                .setServicePackageName(mServicePackageName)
                .setQueryData(queryData)
                .setTimeMillis(System.currentTimeMillis())
                .build();
        long queryId = EventsDao.getInstance(mContext).insertQuery(query);
        if (queryId == -1) {
            return Futures.immediateFailedFuture(new RuntimeException("Failed to log query."));
        }
        return Futures.immediateFuture(queryId);
    }

    private ListenableFuture<List<String>> createTokens(
            ListenableFuture<ExecuteOutput> selectContentResultFuture,
            ListenableFuture<Long> queryIdFuture) {
        try {
            Log.d(TAG, "createTokens() started.");
            ExecuteOutput selectContentResult = Futures.getDone(selectContentResultFuture);
            long queryId = Futures.getDone(queryIdFuture);
            List<SlotResult> slotResults = selectContentResult.getSlotResults();
            Objects.requireNonNull(slotResults);

            List<String> slotResultTokens = new ArrayList<String>();
            for (SlotResult slotResult : slotResults) {
                if (slotResult == null) {
                    slotResultTokens.add(null);
                } else {
                    SlotRenderingData wrapper = new SlotRenderingData(
                            slotResult, mServicePackageName, queryId);
                    slotResultTokens.add(CryptUtils.encrypt(wrapper));
                }
            }

            return Futures.immediateFuture(slotResultTokens);
        } catch (Exception e) {
            return Futures.immediateFailedFuture(e);
        }
    }

    private void sendResult(List<String> slotResultTokens) {
        try {
            if (slotResultTokens != null && slotResultTokens.size() > 0) {
                mCallback.onSuccess(slotResultTokens);
            } else {
                Log.w(TAG, "slotResultTokens is null or empty");
                sendErrorResult(Constants.STATUS_INTERNAL_ERROR);
            }
        } catch (RemoteException e) {
            Log.w(TAG, "Callback error", e);
        }
    }

    private void sendErrorResult(int errorCode) {
        try {
            mCallback.onError(errorCode);
        } catch (RemoteException e) {
            Log.w(TAG, "Callback error", e);
        }
    }
}
