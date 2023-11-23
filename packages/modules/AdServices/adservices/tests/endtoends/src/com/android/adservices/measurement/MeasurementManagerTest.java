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
package com.android.adservices.measurement;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.after;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.adservices.adid.AdId;
import android.adservices.adid.AdIdManager;
import android.adservices.measurement.DeletionParam;
import android.adservices.measurement.DeletionRequest;
import android.adservices.measurement.IMeasurementService;
import android.adservices.measurement.MeasurementManager;
import android.adservices.measurement.RegistrationRequest;
import android.adservices.measurement.WebSourceParams;
import android.adservices.measurement.WebSourceRegistrationRequest;
import android.adservices.measurement.WebSourceRegistrationRequestInternal;
import android.adservices.measurement.WebTriggerParams;
import android.adservices.measurement.WebTriggerRegistrationRequest;
import android.adservices.measurement.WebTriggerRegistrationRequestInternal;
import android.app.sdksandbox.SandboxedSdkContext;
import android.content.Context;
import android.net.Uri;
import android.os.OutcomeReceiver;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.LogUtil;
import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.compatibility.common.util.ShellUtils;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;

import java.util.Collections;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public class MeasurementManagerTest {
    private static final String CLIENT_PACKAGE_NAME = "com.android.adservices.endtoendtest";
    private static final long TIMEOUT = 5000L;
    private static final long CALLBACK_TIMEOUT = 100L;

    private static final long AD_ID_TIMEOUT = 500;

    public static final String AD_ID = "35a4ac90-e4dc-4fe7-bbc6-95e804aa7dbc";

    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final Executor CALLBACK_EXECUTOR = Executors.newCachedThreadPool();
    private static final SandboxedSdkContext sSandboxedSdkContext =
            new SandboxedSdkContext(
                    sContext,
                    sContext.getClassLoader(),
                    CLIENT_PACKAGE_NAME,
                    sContext.getApplicationInfo(),
                    "sdkName",
                    /* sdkCeDataDir = */ null,
                    /* sdkDeDataDir = */ null,
                    /* isCustomizedSdkContext = */ false);

    private String mPreviousAppAllowList;

    private String getPackageName() {
        return SdkLevel.isAtLeastT()
                ? "com.android.adservices.endtoendtest"
                : "com.android.adextservices.endtoendtest";
    }

    private MeasurementManager getMeasurementManager() {
        return spy(MeasurementManager.get(sContext));
    }

    @Before
    public void setUp() throws TimeoutException {
        if (!SdkLevel.isAtLeastT()) {
            mPreviousAppAllowList =
                    CompatAdServicesTestUtils.getAndOverridePpapiAppAllowList(
                            sContext.getPackageName());
            CompatAdServicesTestUtils.setFlags();
        }
    }

    @After
    public void tearDown() {
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.setPpapiAppAllowList(mPreviousAppAllowList);
            CompatAdServicesTestUtils.resetFlagsToDefault();
        }

        resetOverrideConsentManagerDebugMode();
    }

    @Test
    public void testRegisterSource_callingApp_expectedAttributionSource() throws Exception {
        MeasurementManager mm = getMeasurementManager();
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        mm.registerSource(
                Uri.parse("https://example.com"),
                /* inputEvent = */ null,
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterSource_BindServiceFailure_propagateErrorCallback() {
        MeasurementManager measurementManager = getMeasurementManager();
        doThrow(new IllegalStateException()).when(measurementManager).getService();
        OutcomeReceiver callback = mock(OutcomeReceiver.class);
        measurementManager.registerSource(
                Uri.parse("https://example.com"),
                /* inputEvent = */ null,
                /* executor = */ CALLBACK_EXECUTOR,
                /* callback = */ callback);

        verify(callback, after(CALLBACK_TIMEOUT)).onError(any());
    }

    @Test
    public void testRegisterSource_adIdEnabled_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onResult(new AdId(AD_ID, true));
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerSource(
                Uri.parse("https://example.com"),
                /* inputEvent = */ null,
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertTrue(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
        verify(mock(LogUtil.class), never()).w(anyString());
    }

    @Test
    public void testRegisterSource_adIdZeroOut_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onResult(new AdId(AdId.ZERO_OUT, true));
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerSource(
                Uri.parse("https://example.com"),
                /* inputEvent = */ null,
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterSource_adIdDisabled_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onError(new SecurityException());
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerSource(
                Uri.parse("https://example.com"),
                /* inputEvent = */ null,
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
        verify(mock(LogUtil.class)).w(anyString());
    }

    @Test
    public void testRegisterSource_adIdTimeOut_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    Thread.sleep(AD_ID_TIMEOUT);
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerSource(
                Uri.parse("https://example.com"),
                /* inputEvent = */ null,
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterTrigger_BindServiceFailure_propagateErrorCallback() {
        MeasurementManager measurementManager = getMeasurementManager();
        doThrow(new IllegalStateException()).when(measurementManager).getService();
        OutcomeReceiver callback = mock(OutcomeReceiver.class);

        measurementManager.registerTrigger(
                Uri.parse("https://example.com"),
                /* executor = */ CALLBACK_EXECUTOR,
                /* callback = */ callback);

        verify(callback, after(CALLBACK_TIMEOUT)).onError(any());
    }

    @Test
    public void testRegisterWebSource_BindServiceFailure_propagateErrorCallback() {
        MeasurementManager measurementManager = getMeasurementManager();
        doThrow(new IllegalStateException()).when(measurementManager).getService();
        OutcomeReceiver callback = mock(OutcomeReceiver.class);

        measurementManager.registerWebSource(
                buildDefaultWebSourceRegistrationRequest(),
                /* executor = */ CALLBACK_EXECUTOR,
                /* callback = */ callback);

        verify(callback, after(CALLBACK_TIMEOUT)).onError(any());
    }

    @Test
    public void testRegisterWebTrigger_BindServiceFailure_propagateErrorCallback() {
        MeasurementManager measurementManager = getMeasurementManager();
        doThrow(new IllegalStateException()).when(measurementManager).getService();
        OutcomeReceiver callback = mock(OutcomeReceiver.class);

        measurementManager.registerWebTrigger(
                buildDefaultWebTriggerRegistrationRequest(),
                /* executor = */ CALLBACK_EXECUTOR,
                /* callback = */ callback);

        verify(callback, after(CALLBACK_TIMEOUT)).onError(any());
    }

    @Test
    public void testRegisterSource_callingSdk_expectedAttributionSource() throws Exception {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        MeasurementManager mm =
                spy(sSandboxedSdkContext.getSystemService(MeasurementManager.class));
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        mm.registerSource(
                Uri.parse("https://example.com"),
                /* inputEvent = */ null,
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterSource_executorAndCallbackCalled() throws Exception {
        final MeasurementManager mm = getMeasurementManager();
        final CountDownLatch anyCountDownLatch = new CountDownLatch(1);

        mm.registerSource(
                Uri.parse("https://registration-source"),
                /* inputEvent = */ null,
                CALLBACK_EXECUTOR,
                new OutcomeReceiver<Object, Exception>() {
                    @Override
                    public void onResult(@NonNull Object result) {
                        anyCountDownLatch.countDown();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        anyCountDownLatch.countDown();
                    }
                });

        assertTrue(anyCountDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
    }

    private WebSourceRegistrationRequest buildDefaultWebSourceRegistrationRequest() {
        WebSourceParams webSourceParams =
                new WebSourceParams.Builder(Uri.parse("https://example.com"))
                        .setDebugKeyAllowed(false)
                        .build();

        return new WebSourceRegistrationRequest.Builder(
                Collections.singletonList(webSourceParams),
                Uri.parse("https://example.com"))
                .setInputEvent(null)
                .setAppDestination(Uri.parse("android-app://com.example"))
                .setWebDestination(Uri.parse("https://example.com"))
                .setVerifiedDestination(null)
                .build();
    }

    @Test
    public void testRegisterWebSource_callingApp_expectedAttributionSource() throws Exception {
        MeasurementManager mm = getMeasurementManager();
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<WebSourceRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebSourceRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebSource(captor.capture(), any(), any());

        mm.registerWebSource(
                buildDefaultWebSourceRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterWebSource_callingSdk_expectedAttributionSource() throws Exception {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        MeasurementManager mm =
                spy(sSandboxedSdkContext.getSystemService(MeasurementManager.class));
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<WebSourceRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebSourceRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebSource(captor.capture(), any(), any());

        mm.registerWebSource(
                buildDefaultWebSourceRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterWebSource_executorAndCallbackCalled() throws Exception {
        final MeasurementManager mm = getMeasurementManager();
        final CountDownLatch anyCountDownLatch = new CountDownLatch(1);

        mm.registerWebSource(
                buildDefaultWebSourceRegistrationRequest(),
                CALLBACK_EXECUTOR,
                new OutcomeReceiver<Object, Exception>() {
                    @Override
                    public void onResult(@NonNull Object result) {
                        anyCountDownLatch.countDown();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        anyCountDownLatch.countDown();
                    }
                });

        assertTrue(anyCountDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRegisterWebSource_adIdEnabled_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<WebSourceRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebSourceRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebSource(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onResult(new AdId(AD_ID, true));
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerWebSource(
                buildDefaultWebSourceRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertTrue(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
        verify(mock(LogUtil.class), never()).w(anyString());
    }

    @Test
    public void testRegisterWebSource_adIdZeroOut_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<WebSourceRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebSourceRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebSource(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onResult(new AdId(AdId.ZERO_OUT, true));
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerWebSource(
                buildDefaultWebSourceRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterWebSource_adIdDisabled_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<WebSourceRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebSourceRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebSource(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onError(new SecurityException());
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerWebSource(
                buildDefaultWebSourceRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
        verify(mock(LogUtil.class)).w(anyString());
    }

    @Test
    public void testRegisterWebSource_adIdTimeOut_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<WebSourceRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebSourceRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebSource(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    Thread.sleep(AD_ID_TIMEOUT);
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerWebSource(
                buildDefaultWebSourceRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    private WebTriggerRegistrationRequest buildDefaultWebTriggerRegistrationRequest() {
        WebTriggerParams webTriggerParams =
                new WebTriggerParams.Builder(Uri.parse("https://example.com"))
                        .setDebugKeyAllowed(false)
                        .build();
        return new WebTriggerRegistrationRequest.Builder(
                        Collections.singletonList(webTriggerParams),
                        Uri.parse("https://example.com"))
                .build();
    }

    @Test
    public void testRegisterWebTrigger_callingApp_expectedAttributionSource() throws Exception {
        MeasurementManager mm = getMeasurementManager();
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<WebTriggerRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebTriggerRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebTrigger(captor.capture(), any(), any());

        mm.registerWebTrigger(
                buildDefaultWebTriggerRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterWebTrigger_callingSdk_expectedAttributionSource() throws Exception {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        MeasurementManager mm =
                spy(sSandboxedSdkContext.getSystemService(MeasurementManager.class));
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<WebTriggerRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebTriggerRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebTrigger(captor.capture(), any(), any());

        mm.registerWebTrigger(
                buildDefaultWebTriggerRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterWebTrigger_executorAndCallbackCalled() throws Exception {
        final MeasurementManager mm = getMeasurementManager();
        final CountDownLatch anyCountDownLatch = new CountDownLatch(1);

        mm.registerWebTrigger(
                buildDefaultWebTriggerRegistrationRequest(),
                CALLBACK_EXECUTOR,
                new OutcomeReceiver<Object, Exception>() {
                    @Override
                    public void onResult(@NonNull Object result) {
                        anyCountDownLatch.countDown();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        anyCountDownLatch.countDown();
                    }
                });

        assertTrue(anyCountDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRegisterWebTrigger_adIdEnabled_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<WebTriggerRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebTriggerRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebTrigger(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onResult(new AdId(AD_ID, true));
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerWebTrigger(
                buildDefaultWebTriggerRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertTrue(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
        verify(mock(LogUtil.class), never()).w(anyString());
    }

    @Test
    public void testRegisterWebTrigger_adIdZeroOut_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<WebTriggerRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebTriggerRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebTrigger(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onResult(new AdId(AdId.ZERO_OUT, true));
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerWebTrigger(
                buildDefaultWebTriggerRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterWebTrigger_adIdDisabled_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<WebTriggerRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebTriggerRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebTrigger(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onError(new SecurityException());
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerWebTrigger(
                buildDefaultWebTriggerRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
        verify(mock(LogUtil.class)).w(anyString());
    }

    @Test
    public void testRegisterWebTrigger_adIdTimeOut_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<WebTriggerRegistrationRequestInternal> captor =
                ArgumentCaptor.forClass(WebTriggerRegistrationRequestInternal.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).registerWebTrigger(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    Thread.sleep(AD_ID_TIMEOUT);
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerWebTrigger(
                buildDefaultWebTriggerRegistrationRequest(),
                /* executor = */ null,
                /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterTrigger_callingApp_expectedAttributionSource() throws Exception {
        MeasurementManager mm = getMeasurementManager();
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        mm.registerTrigger(
                Uri.parse("https://example.com"), /* executor = */ null, /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterTrigger_callingSdk_expectedAttributionSource() throws Exception {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        MeasurementManager mm =
                spy(sSandboxedSdkContext.getSystemService(MeasurementManager.class));
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        mm.registerTrigger(
                Uri.parse("https://example.com"), /* executor = */ null, /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterTrigger_executorAndCallbackCalled() throws Exception {
        final MeasurementManager mm = getMeasurementManager();
        final CountDownLatch anyCountDownLatch = new CountDownLatch(1);

        mm.registerTrigger(
                Uri.parse("https://registration-trigger"),
                CALLBACK_EXECUTOR,
                new OutcomeReceiver<Object, Exception>() {
                    @Override
                    public void onResult(@NonNull Object result) {
                        anyCountDownLatch.countDown();
                    }

                    @Override
                    public void onError(@NonNull Exception error) {
                        anyCountDownLatch.countDown();
                    }
                });

        assertTrue(anyCountDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
    }

    @Test
    public void testRegisterTrigger_adIdEnabled_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onResult(new AdId(AD_ID, true));
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerTrigger(
                Uri.parse("https://example.com"), /* executor = */ null, /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertTrue(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
        verify(mock(LogUtil.class), never()).w(anyString());
    }

    @Test
    public void testRegisterTrigger_adIdZeroOut_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onResult(new AdId(AdId.ZERO_OUT, true));
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerTrigger(
                Uri.parse("https://example.com"), /* executor = */ null, /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testRegisterTrigger_adIdDisabled_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    ((OutcomeReceiver) invocation.getArgument(1))
                            .onError(new SecurityException());
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerTrigger(
                Uri.parse("https://example.com"), /* executor = */ null, /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
        verify(mock(LogUtil.class)).w(anyString());
    }

    @Test
    public void testRegisterTrigger_adIdTimeOut_register() throws Exception {
        AdIdManager adIdManager = mock(AdIdManager.class);
        MeasurementManager measurementManager = spy(MeasurementManager.get(sContext, adIdManager));

        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(measurementManager).getService();
        ArgumentCaptor<RegistrationRequest> captor =
                ArgumentCaptor.forClass(RegistrationRequest.class);
        CountDownLatch countDownLatch = new CountDownLatch(1);
        Answer<Void> answer =
                (invocation) -> {
                    countDownLatch.countDown();
                    return null;
                };
        doAnswer(answer).when(mockService).register(captor.capture(), any(), any());

        doAnswer(
                (invocation) -> {
                    Thread.sleep(AD_ID_TIMEOUT);
                    return null;
                })
                .when(adIdManager)
                .getAdId(any(), any());

        measurementManager.registerTrigger(
                Uri.parse("https://example.com"), /* executor = */ null, /* callback = */ null);

        assertTrue(countDownLatch.await(TIMEOUT, TimeUnit.MILLISECONDS));
        assertFalse(captor.getValue().isAdIdPermissionGranted());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testDeleteRegistrations_callingApp_expectedAttributionSource() throws Exception {
        MeasurementManager mm = getMeasurementManager();
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<DeletionParam> captor = ArgumentCaptor.forClass(DeletionParam.class);
        doNothing().when(mockService).deleteRegistrations(captor.capture(), any(), any());

        mm.deleteRegistrations(
                new DeletionRequest.Builder().build(),
                CALLBACK_EXECUTOR,
                i -> new CompletableFuture<>().complete(i));

        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testDeleteRegistrations_callingSdk_expectedAttributionSource() throws Exception {
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        MeasurementManager mm =
                spy(sSandboxedSdkContext.getSystemService(MeasurementManager.class));
        IMeasurementService mockService = mock(IMeasurementService.class);
        doReturn(mockService).when(mm).getService();
        ArgumentCaptor<DeletionParam> captor = ArgumentCaptor.forClass(DeletionParam.class);
        doNothing().when(mockService).deleteRegistrations(captor.capture(), any(), any());

        mm.deleteRegistrations(
                new DeletionRequest.Builder().build(),
                CALLBACK_EXECUTOR,
                i -> new CompletableFuture<>().complete(i));

        Assert.assertNotNull(captor.getValue().getAppPackageName());
        Assert.assertEquals(getPackageName(), captor.getValue().getAppPackageName());
    }

    @Test
    public void testDeleteRegistrations_nullExecutor_throwNullPointerException() {
        MeasurementManager mm = getMeasurementManager();

        assertThrows(
                NullPointerException.class,
                () ->
                        mm.deleteRegistrations(
                                new DeletionRequest.Builder().build(),
                                /* executor */ null,
                                i -> new CompletableFuture<>().complete(i)));
    }

    @Test
    public void testDeleteRegistrations_nullCallback_throwNullPointerException() {
        MeasurementManager mm = getMeasurementManager();

        assertThrows(
                NullPointerException.class,
                () ->
                        mm.deleteRegistrations(
                                new DeletionRequest.Builder().build(),
                                CALLBACK_EXECUTOR,
                                /* callback */ null));
    }

    @Test
    public void testGetMeasurementApiStatus() throws Exception {
        final MeasurementManager mm = getMeasurementManager();
        final int response = callMeasurementApiStatus(mm);
        Assert.assertEquals(MeasurementManager.MEASUREMENT_API_STATE_ENABLED, response);
    }

    @Test
    public void testGetMeasurementApiStatus_nullExecutor_throwNullPointerException() {
        MeasurementManager mm = getMeasurementManager();
        overrideConsentManagerDebugMode();

        assertThrows(
                NullPointerException.class,
                () -> mm.getMeasurementApiStatus(/* executor */ null, result -> {}));
    }

    @Test
    public void testGetMeasurementApiStatus_nullCallback_throwNullPointerException() {
        MeasurementManager mm = getMeasurementManager();
        overrideConsentManagerDebugMode();

        assertThrows(
                NullPointerException.class,
                () -> mm.getMeasurementApiStatus(CALLBACK_EXECUTOR, /* callback */ null));
    }

    @Test
    public void testGetMeasurementApiStatus_getServiceThrowsIllegalState_returnDisabled()
            throws Exception {
        final MeasurementManager mm = getMeasurementManager();
        doThrow(new IllegalStateException()).when(mm).getService();
        final int response = callMeasurementApiStatus(mm);
        Assert.assertEquals(MeasurementManager.MEASUREMENT_API_STATE_DISABLED, response);
    }

    @Test
    public void testGetMeasurementApiStatus_getServiceThrowsRuntimeException_propagateOnError()
            throws Exception {
        final MeasurementManager mm = getMeasurementManager();
        doThrow(new RuntimeException()).when(mm).getService();
        CompletableFuture<Exception> future = new CompletableFuture<>();

        mm.getMeasurementApiStatus(
                CALLBACK_EXECUTOR,
                new OutcomeReceiver<Integer, Exception>() {
                    @Override
                    public void onResult(Integer result) {
                        Assert.fail();
                    }

                    @Override
                    public void onError(Exception error) {
                        future.complete(error);
                    }
                });

        Exception exception = future.get();
        Assert.assertTrue(exception instanceof RuntimeException);
    }

    @Test
    public void testGetMeasurementApiStatus_remoteException_returnDisabled() throws Exception {
        final MeasurementManager mm = getMeasurementManager();
        IMeasurementService mockMeasurementService = mock(IMeasurementService.class);
        doReturn(mockMeasurementService).when(mm).getService();
        doThrow(new RemoteException())
                .when(mockMeasurementService)
                .getMeasurementApiStatus(any(), any(), any());
        final int response = callMeasurementApiStatus(mm);
        Assert.assertEquals(MeasurementManager.MEASUREMENT_API_STATE_DISABLED, response);
    }

    @Test
    public void testGetMeasurementApiStatus_RuntimeException_propagateOnError() throws Exception {
        final MeasurementManager mm = getMeasurementManager();
        IMeasurementService mockMeasurementService = mock(IMeasurementService.class);
        doReturn(mockMeasurementService).when(mm).getService();
        doThrow(new RuntimeException())
                .when(mockMeasurementService)
                .getMeasurementApiStatus(any(), any(), any());

        CompletableFuture<Exception> future = new CompletableFuture<>();
        mm.getMeasurementApiStatus(
                CALLBACK_EXECUTOR,
                new OutcomeReceiver<Integer, Exception>() {
                    @Override
                    public void onResult(Integer result) {
                        Assert.fail();
                    }

                    @Override
                    public void onError(Exception error) {
                        future.complete(error);
                    }
                });

        Exception exception = future.get();
        Assert.assertTrue(exception instanceof RuntimeException);
    }

    private int callMeasurementApiStatus(MeasurementManager mm) throws Exception {
        overrideConsentManagerDebugMode();
        CompletableFuture<Integer> future = new CompletableFuture<>();
        OutcomeReceiver<Integer, Exception> callback =
                new OutcomeReceiver<Integer, Exception>() {
                    @Override
                    public void onResult(Integer result) {
                        future.complete(result);
                    }

                    @Override
                    public void onError(Exception error) {
                        Assert.fail();
                    }
                };

        mm.getMeasurementApiStatus(CALLBACK_EXECUTOR, callback);
        return future.get();
    }

    // Override the Consent Manager behaviour - Consent Given
    private void overrideConsentManagerDebugMode() {
        ShellUtils.runShellCommand("setprop debug.adservices.consent_manager_debug_mode true");
    }

    private void resetOverrideConsentManagerDebugMode() {
        ShellUtils.runShellCommand("setprop debug.adservices.consent_manager_debug_mode null");
    }
}
