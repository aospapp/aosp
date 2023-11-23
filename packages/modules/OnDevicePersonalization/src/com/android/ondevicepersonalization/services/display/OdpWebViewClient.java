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

package com.android.ondevicepersonalization.services.display;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.ondevicepersonalization.Bid;
import android.ondevicepersonalization.Constants;
import android.ondevicepersonalization.EventInput;
import android.ondevicepersonalization.EventOutput;
import android.ondevicepersonalization.Metrics;
import android.ondevicepersonalization.SlotResult;
import android.os.Bundle;
import android.util.Log;
import android.webkit.WebResourceRequest;
import android.webkit.WebResourceResponse;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.data.DataAccessServiceImpl;
import com.android.ondevicepersonalization.services.data.events.Event;
import com.android.ondevicepersonalization.services.data.events.EventUrlHelper;
import com.android.ondevicepersonalization.services.data.events.EventUrlPayload;
import com.android.ondevicepersonalization.services.data.events.EventsDao;
import com.android.ondevicepersonalization.services.manifest.AppManifestConfigHelper;
import com.android.ondevicepersonalization.services.process.IsolatedServiceInfo;
import com.android.ondevicepersonalization.services.process.ProcessUtils;
import com.android.ondevicepersonalization.services.util.OnDevicePersonalizationFlatbufferUtils;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.HashMap;
import java.util.concurrent.Executor;

class OdpWebViewClient extends WebViewClient {
    private static final String TAG = "OdpWebViewClient";
    public static final String TASK_NAME = "ComputeEventMetrics";

    @VisibleForTesting
    static class Injector {
        Executor getExecutor() {
            return OnDevicePersonalizationExecutors.getBackgroundExecutor();
        }

        void openUrl(String landingPage, Context context) {
            if (landingPage != null) {
                Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(landingPage));
                intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                context.startActivity(intent);
            }
        }
    }

    @NonNull private final Context mContext;
    @NonNull private final String mServicePackageName;
    @NonNull private final HashMap<String, Bid> mBidsMap = new HashMap<>();
    @NonNull private final Injector mInjector;

    OdpWebViewClient(Context context, String servicePackageName, SlotResult slotResult) {
        this(context, servicePackageName, slotResult, new Injector());
    }

    @VisibleForTesting
    OdpWebViewClient(Context context, String servicePackageName, SlotResult slotResult,
            Injector injector) {
        mContext = context;
        mServicePackageName = servicePackageName;
        for (Bid bid: slotResult.getLoggedBids()) {
            mBidsMap.put(bid.getKey(), bid);
        }
        mInjector = injector;
    }

    @Override public WebResourceResponse shouldInterceptRequest(
        @NonNull WebView webView, @NonNull WebResourceRequest request) {
        if (webView == null || request == null || request.getUrl() == null) {
            Log.e(TAG, "Received null webView or Request or Url");
            return null;
        }
        String url = request.getUrl().toString();
        if (EventUrlHelper.isOdpUrl(url)) {
            mInjector.getExecutor().execute(() -> handleEvent(url));
            // TODO(b/242753206): Return an empty response.
        }
        return null;
    }

    @Override
    public boolean shouldOverrideUrlLoading(
            @NonNull WebView webView, @NonNull WebResourceRequest request) {
        if (webView == null || request == null) {
            Log.e(TAG, "Received null webView or Request");
            return true;
        }
        //Decode odp://localhost/ URIs and call Events table API to write an event.
        String url = request.getUrl().toString();
        if (EventUrlHelper.isOdpUrl(url)) {
            mInjector.getExecutor().execute(() -> handleEvent(url));
            String landingPage = request.getUrl().getQueryParameter(
                    EventUrlHelper.URL_LANDING_PAGE_EVENT_KEY);
            mInjector.openUrl(landingPage, webView.getContext());
        } else {
            // TODO(b/263180569): Handle any non-odp URLs
            Log.d(TAG, "Non-odp URL encountered: " + url);
        }
        // Cancel the current load
        return true;
    }

    private ListenableFuture<EventOutput> executeEventHandler(
            IsolatedServiceInfo isolatedServiceInfo, EventUrlPayload payload) {
        try {
            Log.d(TAG, "executeEventHandler() called");
            Bundle serviceParams = new Bundle();
            DataAccessServiceImpl binder = new DataAccessServiceImpl(
                    mServicePackageName, mContext, true, null);
            serviceParams.putBinder(Constants.EXTRA_DATA_ACCESS_SERVICE_BINDER, binder);
            Bid bid = mBidsMap.get(payload.getEvent().getBidId());
            // TODO(b/259950177): Add Query row to input.
            EventInput input = new EventInput.Builder()
                    .setEventType(payload.getEvent().getType())
                    .setBid(bid)
                    .build();
            serviceParams.putParcelable(Constants.EXTRA_INPUT, input);
            return FluentFuture.from(
                    ProcessUtils.runIsolatedService(
                        isolatedServiceInfo,
                        AppManifestConfigHelper.getServiceNameFromOdpSettings(
                                mContext, mServicePackageName),
                        Constants.OP_COMPUTE_EVENT_METRICS,
                        serviceParams))
                    .transform(
                            result -> result.getParcelable(
                                Constants.EXTRA_RESULT, EventOutput.class),
                            mInjector.getExecutor());
        } catch (Exception e) {
            Log.e(TAG, "executeEventHandler() failed", e);
            return Futures.immediateFailedFuture(e);
        }

    }

    ListenableFuture<EventOutput> getEventMetrics(EventUrlPayload payload) {
        try {
            Log.d(TAG, "getEventMetrics(): Starting isolated process.");
            return FluentFuture.from(ProcessUtils.loadIsolatedService(
                    TASK_NAME, mServicePackageName, mContext))
                .transformAsync(
                        result -> executeEventHandler(result, payload),
                        mInjector.getExecutor());

        } catch (Exception e) {
            Log.e(TAG, "getEventMetrics() failed", e);
            return Futures.immediateFailedFuture(e);
        }
    }

    private ListenableFuture<Void> writeEvent(Event event, EventOutput result) {
        try {
            Log.d(TAG, "writeEvent() called. event: " + event.toString() + " metrics: "
                     + result.toString());
            Metrics metrics = null;
            if (result != null) {
                metrics = result.getMetrics();
            }
            if (metrics == null) {
                // Metrics required because eventData column is non-null.
                metrics = new Metrics.Builder().build();
            }
            byte[] eventData = OnDevicePersonalizationFlatbufferUtils.createEventData(metrics);
            event = new Event.Builder()
                    .setType(event.getType())
                    .setQueryId(event.getQueryId())
                    .setServicePackageName(event.getServicePackageName())
                    .setTimeMillis(event.getTimeMillis())
                    .setSlotId(event.getSlotId())
                    .setSlotPosition(event.getSlotPosition())
                    .setSlotIndex(event.getSlotIndex())
                    .setBidId(event.getBidId())
                    .setEventData(eventData)
                    .build();
            if (-1 == EventsDao.getInstance(mContext).insertEvent(event)) {
                Log.e(TAG, "Failed to insert event: " + event);
            }
            return Futures.immediateFuture(null);
        } catch (Exception e) {
            Log.e(TAG, "writeEvent() failed", e);
            return Futures.immediateFailedFuture(e);
        }
    }

    private void handleEvent(String url) {
        try {
            Log.d(TAG, "handleEvent() called");
            EventUrlPayload eventUrlPayload = EventUrlHelper.getEventFromOdpEventUrl(url);
            Event event = eventUrlPayload.getEvent();

            var unused = FluentFuture.from(getEventMetrics(eventUrlPayload))
                    .transformAsync(
                        result -> writeEvent(event, result),
                        mInjector.getExecutor());

        } catch (Exception e) {
            Log.e(TAG, "Failed to handle Event", e);
        }
    }
}
