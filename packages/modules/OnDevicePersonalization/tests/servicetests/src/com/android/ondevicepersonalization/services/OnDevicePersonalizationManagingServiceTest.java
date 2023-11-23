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

package com.android.ondevicepersonalization.services;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.content.Intent;
import android.ondevicepersonalization.aidl.IExecuteCallback;
import android.ondevicepersonalization.aidl.IRequestSurfacePackageCallback;
import android.os.Binder;
import android.os.IBinder;
import android.os.PersistableBundle;
import android.view.SurfaceControlViewHost;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.rule.ServiceTestRule;

import com.android.ondevicepersonalization.services.request.AppRequestFlow;
import com.android.ondevicepersonalization.services.request.RenderFlow;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeoutException;

@RunWith(JUnit4.class)
public class OnDevicePersonalizationManagingServiceTest {
    @Rule
    public final ServiceTestRule serviceRule = new ServiceTestRule();
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private OnDevicePersonalizationManagingServiceDelegate mService;
    private boolean mAppRequestFlowStarted = false;
    private boolean mRenderFlowStarted = false;

    @Before
    public void setup() throws Exception {
        mService = new OnDevicePersonalizationManagingServiceDelegate(
                mContext, new TestInjector());
    }
    @Test
    public void testVersion() throws Exception {
        assertEquals(mService.getVersion(), "1.0");
    }

    @Test
    public void testExecuteInvokesAppRequestFlow() throws Exception {
        var callback = new ExecuteCallback();
        mService.execute(
                        mContext.getPackageName(),
                        mContext.getPackageName(),
                        PersistableBundle.EMPTY,
                        callback);
        assertTrue(mAppRequestFlowStarted);
    }

    @Test
    public void testExecuteThrowsIfAppPackageNameIncorrect() throws Exception {
        var callback = new ExecuteCallback();
        assertThrows(
                SecurityException.class,
                () ->
                    mService.execute(
                        "abc",
                        mContext.getPackageName(),
                        PersistableBundle.EMPTY,
                        callback));
    }

    @Test
    public void testExecuteThrowsIfAppPackageNameMissing() throws Exception {
        var callback = new ExecuteCallback();
        assertThrows(
                NullPointerException.class,
                () ->
                    mService.execute(
                        null,
                        mContext.getPackageName(),
                        PersistableBundle.EMPTY,
                        callback));
    }

    @Test
    public void testExecuteThrowsIfServicePackageMissing() throws Exception {
        var callback = new ExecuteCallback();
        assertThrows(
                NullPointerException.class,
                () ->
                    mService.execute(
                        mContext.getPackageName(),
                        null,
                        PersistableBundle.EMPTY,
                        callback));
    }

    @Test
    public void testExecuteThrowsIfCallbackMissing() throws Exception {
        assertThrows(
                NullPointerException.class,
                () ->
                    mService.execute(
                        mContext.getPackageName(),
                        mContext.getPackageName(),
                        PersistableBundle.EMPTY,
                        null));
    }

    @Test
    public void testRequestSurfacePackageInvokesRenderFlow() throws Exception {
        var callback = new RequestSurfacePackageCallback();
        mService.requestSurfacePackage(
                "resultToken",
                new Binder(),
                0,
                100,
                50,
                callback);
        assertTrue(mRenderFlowStarted);
    }

    @Test
    public void testRequestSurfacePackageThrowsIfSlotResultTokenMissing() throws Exception {
        var callback = new RequestSurfacePackageCallback();
        assertThrows(
                NullPointerException.class,
                () ->
                    mService.requestSurfacePackage(
                        null,
                        new Binder(),
                        0,
                        100,
                        50,
                        callback));
    }

    @Test
    public void testRequestSurfacePackageThrowsIfHostTokenMissing() throws Exception {
        var callback = new RequestSurfacePackageCallback();
        assertThrows(
                NullPointerException.class,
                () ->
                    mService.requestSurfacePackage(
                        "resultToken",
                        null,
                        0,
                        100,
                        50,
                        callback));
    }

    @Test
    public void testRequestSurfacePackageThrowsIfDisplayIdInvalid() throws Exception {
        var callback = new RequestSurfacePackageCallback();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    mService.requestSurfacePackage(
                        "resultToken",
                        new Binder(),
                        -1,
                        100,
                        50,
                        callback));
    }

    @Test
    public void testRequestSurfacePackageThrowsIfWidthInvalid() throws Exception {
        var callback = new RequestSurfacePackageCallback();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    mService.requestSurfacePackage(
                        "resultToken",
                        new Binder(),
                        0,
                        0,
                        50,
                        callback));
    }

    @Test
    public void testRequestSurfacePackageThrowsIfHeightInvalid() throws Exception {
        var callback = new RequestSurfacePackageCallback();
        assertThrows(
                IllegalArgumentException.class,
                () ->
                    mService.requestSurfacePackage(
                        "resultToken",
                        new Binder(),
                        0,
                        100,
                        0,
                        callback));
    }

    @Test
    public void testRequestSurfacePackageThrowsIfCallbackMissing() throws Exception {
        assertThrows(
                NullPointerException.class,
                () ->
                    mService.requestSurfacePackage(
                        "resultToken",
                        new Binder(),
                        0,
                        100,
                        50,
                        null));
    }

    @Test
    public void testDefaultInjector() {
        var executeCallback = new ExecuteCallback();
        var renderCallback = new RequestSurfacePackageCallback();
        OnDevicePersonalizationManagingServiceDelegate.Injector injector =
                new OnDevicePersonalizationManagingServiceDelegate.Injector();

        assertNotNull(injector.getAppRequestFlow(
                mContext.getPackageName(),
                mContext.getPackageName(),
                PersistableBundle.EMPTY,
                executeCallback,
                mContext));

        assertNotNull(injector.getRenderFlow(
                "resultToken",
                new Binder(),
                0,
                100,
                50,
                renderCallback,
                mContext
        ));
    }

    @Test
    public void testWithBoundService() throws TimeoutException {
        Intent serviceIntent = new Intent(mContext,
                OnDevicePersonalizationManagingServiceImpl.class);
        IBinder binder = serviceRule.bindService(serviceIntent);
        assertTrue(binder instanceof OnDevicePersonalizationManagingServiceDelegate);
    }

    class TestInjector extends OnDevicePersonalizationManagingServiceDelegate.Injector {
        AppRequestFlow getAppRequestFlow(
                String callingPackageName,
                String servicePackageName,
                PersistableBundle params,
                IExecuteCallback callback,
                Context context) {
            return new AppRequestFlow(
                    callingPackageName, servicePackageName, params, callback, context) {
                @Override public void run() {
                    mAppRequestFlowStarted = true;
                }
            };
        }

        RenderFlow getRenderFlow(
                String slotResultToken,
                IBinder hostToken,
                int displayId,
                int width,
                int height,
                IRequestSurfacePackageCallback callback,
                Context context) {
            return new RenderFlow(
                    slotResultToken, hostToken, displayId, width, height, callback, context) {
                @Override public void run() {
                    mRenderFlowStarted = true;
                }
            };
        }
    }

    static class ExecuteCallback extends IExecuteCallback.Stub {
        public boolean mError = false;
        public int mErrorCode = 0;
        public List<String> mTokens = null;
        private CountDownLatch mLatch = new CountDownLatch(1);

        @Override
        public void onSuccess(List<String> tokens) {
            mTokens = tokens;
            mLatch.countDown();
        }

        @Override
        public void onError(int errorCode) {
            mError = true;
            mErrorCode = errorCode;
            mLatch.countDown();
        }

        public void await() throws Exception {
            mLatch.await();
        }
    }

    static class RequestSurfacePackageCallback extends IRequestSurfacePackageCallback.Stub {
        public boolean mError = false;
        public int mErrorCode = 0;
        private CountDownLatch mLatch = new CountDownLatch(1);

        @Override
        public void onSuccess(SurfaceControlViewHost.SurfacePackage s) {
            mLatch.countDown();
        }

        @Override
        public void onError(int errorCode) {
            mError = true;
            mErrorCode = errorCode;
            mLatch.countDown();
        }

        public void await() throws Exception {
            mLatch.await();
        }
    }
}
