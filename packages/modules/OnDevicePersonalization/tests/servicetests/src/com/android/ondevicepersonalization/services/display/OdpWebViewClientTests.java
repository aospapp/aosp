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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.ondevicepersonalization.Bid;
import android.ondevicepersonalization.Metrics;
import android.ondevicepersonalization.SlotResult;
import android.os.PersistableBundle;
import android.webkit.WebResourceRequest;
import android.webkit.WebView;
import android.webkit.WebViewClient;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;
import com.android.ondevicepersonalization.services.data.events.Event;
import com.android.ondevicepersonalization.services.data.events.EventType;
import com.android.ondevicepersonalization.services.data.events.EventUrlHelper;
import com.android.ondevicepersonalization.services.data.events.EventUrlPayload;
import com.android.ondevicepersonalization.services.data.events.EventsContract;
import com.android.ondevicepersonalization.services.data.events.EventsDao;
import com.android.ondevicepersonalization.services.data.events.Query;
import com.android.ondevicepersonalization.services.fbs.EventFields;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

@RunWith(JUnit4.class)
public class OdpWebViewClientTests {
    private final Context mContext = ApplicationProvider.getApplicationContext();

    private final SlotResult mSlotResult = new SlotResult.Builder()
            .addRenderedBidKeys("bidId")
            .addLoggedBids(
                new Bid.Builder()
                    .setKey("bidId")
                    .build())
            .build();
    private final Event mTestEvent = new Event.Builder()
            .setType(EventType.B2D.getValue())
            .setEventData("event".getBytes(StandardCharsets.UTF_8))
            .setBidId("bidId")
            .setServicePackageName("servicePackageName")
            .setSlotId("slotId")
            .setSlotPosition(1)
            .setQueryId(1L)
            .setTimeMillis(1L)
            .setSlotIndex(0)
            .build();
    private final EventUrlPayload mTestEventPayload = new EventUrlPayload.Builder()
            .setEvent(mTestEvent).build();
    private final Query mTestQuery = new Query.Builder()
            .setTimeMillis(1L)
            .setServicePackageName("servicePackageName")
            .setQueryData("query".getBytes(StandardCharsets.UTF_8))
            .build();
    private EventsDao mDao;
    private OnDevicePersonalizationDbHelper mDbHelper;
    private OdpWebView mWebView;
    private String mOpenedUrl;

    @Before
    public void setup() throws Exception {
        mDbHelper = OnDevicePersonalizationDbHelper.getInstanceForTest(mContext);
        mDao = EventsDao.getInstanceForTest(mContext);
        // Insert query for FK constraint
        mDao.insertQuery(mTestQuery);

        CountDownLatch latch = new CountDownLatch(1);
        OnDevicePersonalizationExecutors.getHandler().postAtFrontOfQueue(() -> {
            mWebView = new OdpWebView(mContext);
            latch.countDown();
        });
        latch.await();
    }

    @After
    public void cleanup() {
        OnDevicePersonalizationDbHelper dbHelper =
                OnDevicePersonalizationDbHelper.getInstanceForTest(mContext);
        dbHelper.getWritableDatabase().close();
        dbHelper.getReadableDatabase().close();
        dbHelper.close();
    }

    @Test
    public void testValidUrlOverride() throws Exception {
        WebViewClient webViewClient = getWebViewClient();
        String odpUrl = EventUrlHelper.getEncryptedOdpEventUrl(mTestEventPayload);
        WebResourceRequest webResourceRequest = new OdpWebResourceRequest(Uri.parse(odpUrl));
        assertTrue(webViewClient.shouldOverrideUrlLoading(mWebView, webResourceRequest));
        assertEquals(1,
                mDbHelper.getReadableDatabase().query(EventsContract.EventsEntry.TABLE_NAME, null,
                        null, null, null, null, null).getCount());
    }

    @Test
    public void testValidUrlIntercept() throws Exception {
        WebViewClient webViewClient = getWebViewClient();
        String odpUrl = EventUrlHelper.getEncryptedOdpEventUrl(mTestEventPayload);
        WebResourceRequest webResourceRequest = new OdpWebResourceRequest(Uri.parse(odpUrl));
        assertEquals(null, webViewClient.shouldInterceptRequest(mWebView, webResourceRequest));
        assertEquals(1,
                mDbHelper.getReadableDatabase().query(EventsContract.EventsEntry.TABLE_NAME, null,
                        null, null, null, null, null).getCount());
    }

    @Test
    public void testValidUrlWithRedirect() throws Exception {
        String landingPage = "https://www.google.com";
        String odpUrl = EventUrlHelper.getEncryptedClickTrackingUrl(mTestEventPayload, landingPage);
        WebResourceRequest webResourceRequest = new OdpWebResourceRequest(Uri.parse(odpUrl));

        CountDownLatch latch = new CountDownLatch(1);
        AtomicBoolean result = new AtomicBoolean(false);
        OnDevicePersonalizationExecutors.getHandler().postAtFrontOfQueue(() -> {
            WebViewClient webViewClient = getWebViewClient();
            result.set(webViewClient.shouldOverrideUrlLoading(mWebView, webResourceRequest));
            latch.countDown();
        });
        latch.await();

        assertTrue(result.get());
        assertEquals(landingPage, mOpenedUrl);
        assertEquals(1,
                mDbHelper.getReadableDatabase().query(EventsContract.EventsEntry.TABLE_NAME, null,
                        null, null, null, null, null).getCount());
    }

    @Test
    public void testValidUrlWithEventMetrics() throws Exception {
        SlotResult slotResult = new SlotResult.Builder()
                .addRenderedBidKeys("bidId")
                .addLoggedBids(
                    new Bid.Builder()
                        .setKey("bidId")
                        .setMetrics(new Metrics.Builder()
                            .setLongValues(10)
                            .setDoubleValues(5.0)
                            .build())
                        .build())
                .build();
        WebViewClient webViewClient = getWebViewClient(slotResult);
        EventUrlPayload payload = new EventUrlPayload.Builder()
                .setEvent(mTestEvent)
                .build();
        String odpUrl = EventUrlHelper.getEncryptedOdpEventUrl(payload);
        WebResourceRequest webResourceRequest = new OdpWebResourceRequest(Uri.parse(odpUrl));
        assertTrue(webViewClient.shouldOverrideUrlLoading(mWebView, webResourceRequest));
        Cursor result =
                mDbHelper.getReadableDatabase().query(
                    EventsContract.EventsEntry.TABLE_NAME, null,
                    null, null, null, null, null);
        assertEquals(1, result.getCount());
        result.moveToFirst();
        int dataColumn = result.getColumnIndex("eventData");
        byte[] data = result.getBlob(dataColumn);
        EventFields eventFields = EventFields.getRootAsEventFields(ByteBuffer.wrap(data));
        assertEquals(1, eventFields.metrics().longValuesLength());
        assertEquals(10, eventFields.metrics().longValues(0));
        assertEquals(1, eventFields.metrics().doubleValuesLength());
        assertEquals(5.0, eventFields.metrics().doubleValues(0), 0.001);
    }

    @Test
    public void testInvalidUrl() {
        WebViewClient webViewClient = getWebViewClient();
        WebResourceRequest webResourceRequest = new OdpWebResourceRequest(
                Uri.parse("https://www.google.com"));
        assertTrue(webViewClient.shouldOverrideUrlLoading(mWebView, webResourceRequest));
        assertEquals(0,
                mDbHelper.getReadableDatabase().query(EventsContract.EventsEntry.TABLE_NAME, null,
                        null, null, null, null, null).getCount());
    }

    @Test
    public void testDefaultInjector() {
        // Assert constructor using default injector succeeds.
        new OdpWebViewClient(mContext, mContext.getPackageName(), mSlotResult);

        // Mock context for default injector tests.
        MockitoSession session = ExtendedMockito.mockitoSession().strictness(
                Strictness.LENIENT).startMocking();
        try {
            Context mockContext = mock(Context.class);
            OdpWebViewClient.Injector injector = new OdpWebViewClient.Injector();
            injector.openUrl("https://google.com", mockContext);
            assertEquals(injector.getExecutor(),
                    OnDevicePersonalizationExecutors.getBackgroundExecutor());
            verify(mockContext, times(1)).startActivity(any());
        } finally {
            session.finishMocking();
        }
    }

    class TestInjector extends OdpWebViewClient.Injector {
        Executor getExecutor() {
            return MoreExecutors.directExecutor();
        }

        void openUrl(String url, Context context) {
            mOpenedUrl = url;
        }
    }

    private WebViewClient getWebViewClient() {
        return getWebViewClient(mSlotResult);
    }

    private WebViewClient getWebViewClient(SlotResult slotResult) {
        return new OdpWebViewClient(mContext, mContext.getPackageName(), slotResult,
                new TestInjector());
    }

    private PersistableBundle createEventMetricsParameters() {
        PersistableBundle data = new PersistableBundle();
        data.putInt("a", 10);
        data.putDouble("b", 5.0);
        return data;
    }

    static class OdpWebView extends WebView {
        private String mLastLoadedUrl;

        OdpWebView(@NonNull Context context) {
            super(context);
        }

        @Override
        public void loadUrl(String url) {
            mLastLoadedUrl = url;
        }

        public String getLastLoadedUrl() {
            return mLastLoadedUrl;
        }

    }

    static class OdpWebResourceRequest implements WebResourceRequest {
        Uri mUri;

        OdpWebResourceRequest(Uri uri) {
            this.mUri = uri;
        }

        @Override
        public Uri getUrl() {
            return mUri;
        }

        @Override
        public boolean isForMainFrame() {
            return false;
        }

        @Override
        public boolean isRedirect() {
            return false;
        }

        @Override
        public boolean hasGesture() {
            return false;
        }

        @Override
        public String getMethod() {
            return null;
        }

        @Override
        public Map<String, String> getRequestHeaders() {
            return null;
        }
    }
}
