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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.ondevicepersonalization.Bid;
import android.ondevicepersonalization.RenderOutput;
import android.ondevicepersonalization.SlotResult;
import android.ondevicepersonalization.aidl.IRequestSurfacePackageCallback;
import android.os.Binder;
import android.os.IBinder;
import android.view.SurfaceControlViewHost.SurfacePackage;

import androidx.test.core.app.ApplicationProvider;

import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.display.DisplayHelper;
import com.android.ondevicepersonalization.services.util.CryptUtils;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.concurrent.CountDownLatch;

@RunWith(JUnit4.class)
public class RenderFlowTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final CountDownLatch mLatch = new CountDownLatch(1);

    private String mRenderedContent;
    private boolean mGenerateHtmlCalled;
    private String mGeneratedHtml;
    private boolean mDisplayHtmlCalled;
    private boolean mCallbackSuccess;
    private boolean mCallbackError;

    @Test
    public void testRunRenderFlow() throws Exception {
        RenderFlow flow = new RenderFlow(
                "token",
                new Binder(),
                0,
                100,
                50,
                new TestCallback(),
                mContext,
                new TestInjector(),
                new TestDisplayHelper());
        flow.run();
        mLatch.await();
        assertTrue(mGenerateHtmlCalled);
        assertTrue(mDisplayHtmlCalled);
        assertTrue(mRenderedContent.contains("bid1"));
        assertTrue(mGeneratedHtml.contains("bid1"));
    }

    @Test
    public void testDefaultInjector() throws Exception {
        RenderFlow.Injector injector = new RenderFlow.Injector();
        assertEquals(OnDevicePersonalizationExecutors.getBackgroundExecutor(),
                injector.getExecutor());
        SlotResult slotResult =
                new SlotResult.Builder()
                        .addRenderedBidKeys("bid1")
                        .addLoggedBids(new Bid.Builder().setKey("bid1").build())
                        .build();
        SlotRenderingData data = new SlotRenderingData(
                slotResult, mContext.getPackageName(), 0);
        String encrypted = CryptUtils.encrypt(data);
        SlotRenderingData decrypted = injector.decryptToken(encrypted);
        assertEquals(data.getQueryId(), decrypted.getQueryId());
        assertEquals(data.getServicePackageName(), decrypted.getServicePackageName());
        assertEquals(data.getSlotResult(), decrypted.getSlotResult());
    }

    class TestInjector extends RenderFlow.Injector {
        ListeningExecutorService getExecutor() {
            return MoreExecutors.newDirectExecutorService();
        }

        SlotRenderingData decryptToken(String token) {
            if (token.equals("token")) {
                SlotResult slotResult =
                        new SlotResult.Builder()
                        .addRenderedBidKeys("bid1")
                        .addLoggedBids(new Bid.Builder().setKey("bid1").build())
                        .addLoggedBids(new Bid.Builder().setKey("bid2").build())
                        .build();
                SlotRenderingData data = new SlotRenderingData(
                        slotResult, mContext.getPackageName(), 0);
                return data;
            } else {
                return null;
            }
        }
    }

    class TestDisplayHelper extends DisplayHelper {
        TestDisplayHelper() {
            super(mContext);
        }

        @Override public String generateHtml(RenderOutput renderContentResult, String packageName) {
            mRenderedContent = renderContentResult.getContent();
            mGenerateHtmlCalled = true;
            return mRenderedContent;
        }

        @Override public ListenableFuture<SurfacePackage> displayHtml(
                String html, SlotResult slotResult, String servicePackageName,
                IBinder hostToken, int displayId, int width, int height) {
            mGeneratedHtml = html;
            mDisplayHtmlCalled = true;
            // TODO(b/228200518): Create and return surfacePackage and check for callback success.
            return Futures.immediateFuture(null);
        }
    }

    class TestCallback extends IRequestSurfacePackageCallback.Stub {
        @Override public void onSuccess(SurfacePackage surfacePackage) {
            mCallbackSuccess = true;
            mLatch.countDown();
        }
        @Override public void onError(int errorCode) {
            mCallbackError = true;
            mLatch.countDown();
        }
    }
}
