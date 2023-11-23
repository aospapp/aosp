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

package com.android.ondevicepersonalization.services.request;

import android.annotation.NonNull;
import android.content.Context;
import android.ondevicepersonalization.Constants;
import android.ondevicepersonalization.RenderInput;
import android.ondevicepersonalization.RenderOutput;
import android.ondevicepersonalization.SlotInfo;
import android.ondevicepersonalization.SlotResult;
import android.ondevicepersonalization.aidl.IRequestSurfacePackageCallback;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.SurfaceControlViewHost.SurfacePackage;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.data.DataAccessServiceImpl;
import com.android.ondevicepersonalization.services.display.DisplayHelper;
import com.android.ondevicepersonalization.services.manifest.AppManifestConfigHelper;
import com.android.ondevicepersonalization.services.process.IsolatedServiceInfo;
import com.android.ondevicepersonalization.services.process.ProcessUtils;
import com.android.ondevicepersonalization.services.util.CryptUtils;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;

import java.util.List;
import java.util.Objects;

/**
 * Handles a surface package request from an app or SDK.
 */
public class RenderFlow {
    private static final String TAG = "RenderFlow";
    private static final String TASK_NAME = "Render";

    @VisibleForTesting
    static class Injector {
        ListeningExecutorService getExecutor() {
            return OnDevicePersonalizationExecutors.getBackgroundExecutor();
        }

        SlotRenderingData decryptToken(String slotResultToken) throws Exception {
            return (SlotRenderingData) CryptUtils.decrypt(slotResultToken);
        }
    }

    @NonNull
    private final String mSlotResultToken;
    @NonNull
    private final IBinder mHostToken;
    @NonNull private final int mDisplayId;
    @NonNull private final int mWidth;
    @NonNull private final int mHeight;
    @NonNull
    private final IRequestSurfacePackageCallback mCallback;
    @NonNull
    private final Context mContext;
    @NonNull
    private final Injector mInjector;
    @NonNull
    private final DisplayHelper mDisplayHelper;
    @NonNull
    private String mServicePackageName;
    @NonNull
    private String mServiceClassName;

    public RenderFlow(
            @NonNull String slotResultToken,
            @NonNull IBinder hostToken,
            int displayId,
            int width,
            int height,
            @NonNull IRequestSurfacePackageCallback callback,
            @NonNull Context context) {
        this(slotResultToken, hostToken, displayId, width, height,
                callback, context,
                new Injector(),
                new DisplayHelper(context));
    }

    @VisibleForTesting
    RenderFlow(
            @NonNull String slotResultToken,
            @NonNull IBinder hostToken,
            int displayId,
            int width,
            int height,
            @NonNull IRequestSurfacePackageCallback callback,
            @NonNull Context context,
            @NonNull Injector injector,
            @NonNull DisplayHelper displayHelper) {
        Log.d(TAG, "RenderFlow created.");
        mSlotResultToken = Objects.requireNonNull(slotResultToken);
        mHostToken = Objects.requireNonNull(hostToken);
        mDisplayId = displayId;
        mWidth = width;
        mHeight = height;
        mCallback = Objects.requireNonNull(callback);
        mInjector = Objects.requireNonNull(injector);
        mContext = Objects.requireNonNull(context);
        mDisplayHelper = Objects.requireNonNull(displayHelper);
    }

    /** Runs the request processing flow. */
    public void run() {
        var unused = Futures.submit(() -> this.processRequest(), mInjector.getExecutor());
    }

    private void processRequest() {
        try {
            SlotRenderingData slotRenderingData = mInjector.decryptToken(mSlotResultToken);
            mServicePackageName = Objects.requireNonNull(
                    slotRenderingData.getServicePackageName());
            mServiceClassName = Objects.requireNonNull(
                    AppManifestConfigHelper.getServiceNameFromOdpSettings(
                        mContext, mServicePackageName));

            ListenableFuture<SurfacePackage> surfacePackageFuture =
                    renderContentForSlot(slotRenderingData);

            Futures.addCallback(
                    surfacePackageFuture,
                    new FutureCallback<SurfacePackage>() {
                        @Override
                        public void onSuccess(SurfacePackage surfacePackage) {
                            sendDisplayResult(surfacePackage);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            Log.w(TAG, "Request failed.", t);
                            sendErrorResult(Constants.STATUS_INTERNAL_ERROR);
                        }
                    },
                    mInjector.getExecutor());
        } catch (Exception e) {
            Log.e(TAG, "Could not process request.", e);
            sendErrorResult(Constants.STATUS_INTERNAL_ERROR);
        }
    }

    private ListenableFuture<SurfacePackage> renderContentForSlot(
            SlotRenderingData slotRenderingData
    ) {
        try {
            Log.d(TAG, "renderContentForSlot() started.");
            Objects.requireNonNull(slotRenderingData);
            SlotResult slotResult = slotRenderingData.getSlotResult();
            Objects.requireNonNull(slotResult);
            long queryId = slotRenderingData.getQueryId();
            SlotInfo slotInfo =
                    new SlotInfo.Builder()
                            .setHeight(mHeight)
                            .setWidth(mWidth).build();
            List<String> bidKeys = slotResult.getRenderedBidKeys();
            if (bidKeys == null || bidKeys.isEmpty()) {
                return Futures.immediateFailedFuture(new IllegalArgumentException("No bids"));
            }

            return FluentFuture.from(ProcessUtils.loadIsolatedService(
                            TASK_NAME, mServicePackageName, mContext))
                    .transformAsync(
                            loadResult -> executeRenderContentRequest(
                                    loadResult, slotInfo, slotResult, queryId, bidKeys),
                            mInjector.getExecutor())
                    .transform(result -> {
                        return result.getParcelable(
                                Constants.EXTRA_RESULT, RenderOutput.class);
                    }, mInjector.getExecutor())
                    .transform(
                            result -> mDisplayHelper.generateHtml(result, mServicePackageName),
                            mInjector.getExecutor())
                    .transformAsync(
                            result -> mDisplayHelper.displayHtml(
                                    result,
                                    slotResult,
                                    mServicePackageName,
                                    mHostToken,
                                    mDisplayId,
                                    mWidth,
                                    mHeight),
                            mInjector.getExecutor());
        } catch (Exception e) {
            return Futures.immediateFailedFuture(e);
        }
    }

    private ListenableFuture<Bundle> executeRenderContentRequest(
            IsolatedServiceInfo isolatedServiceInfo, SlotInfo slotInfo, SlotResult slotResult,
            long queryId, List<String> bidKeys) {
        Log.d(TAG, "executeRenderContentRequest() started.");
        Bundle serviceParams = new Bundle();
        RenderInput input =
                new RenderInput.Builder().setSlotInfo(slotInfo).setBidKeys(bidKeys).build();
        serviceParams.putParcelable(Constants.EXTRA_INPUT, input);
        DataAccessServiceImpl binder = new DataAccessServiceImpl(
                mServicePackageName, mContext, false,
                new DataAccessServiceImpl.EventUrlQueryData(queryId, slotResult));
        serviceParams.putBinder(Constants.EXTRA_DATA_ACCESS_SERVICE_BINDER, binder);
        return ProcessUtils.runIsolatedService(
                isolatedServiceInfo, mServiceClassName, Constants.OP_RENDER_CONTENT,
                serviceParams);
    }

    private void sendDisplayResult(SurfacePackage surfacePackage) {
        try {
            if (surfacePackage != null) {
                mCallback.onSuccess(surfacePackage);
            } else {
                Log.w(TAG, "surfacePackages is null or empty");
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
