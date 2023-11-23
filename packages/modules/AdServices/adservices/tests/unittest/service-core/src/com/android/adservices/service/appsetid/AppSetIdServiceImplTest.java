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

package com.android.adservices.service.appsetid;

import static android.adservices.common.AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_RATE_LIMIT_REACHED;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_CLASS__APPSETID;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__GET_APPSETID;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.adservices.appsetid.GetAppSetIdParam;
import android.adservices.appsetid.GetAppSetIdResult;
import android.adservices.appsetid.IGetAppSetIdCallback;
import android.adservices.common.CallerMetadata;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.IBinder;
import android.os.Process;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.service.Flags;
import com.android.adservices.service.common.AppImportanceFilter;
import com.android.adservices.service.common.AppImportanceFilter.WrongCallingApplicationStateException;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.adservices.service.stats.ApiCallStats;
import com.android.adservices.service.stats.Clock;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.invocation.InvocationOnMock;
import org.mockito.stubbing.Answer;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Unit test for {@link com.android.adservices.service.appsetid.AppSetIdServiceImpl}. */
public class AppSetIdServiceImplTest {
    private static final String TEST_APP_PACKAGE_NAME = "com.android.adservices.servicecoretest";
    private static final String INVALID_PACKAGE_NAME = "com.do_not_exists";
    private static final String SOME_SDK_NAME = "SomeSdkName";
    private static final int BINDER_CONNECTION_TIMEOUT_MS = 5_000;
    private static final String SDK_PACKAGE_NAME = "test_package_name";
    private static final String ALLOWED_SDK_ID = "1234567";
    // This is not allowed per the ad_services_config.xml manifest config.
    private static final String DISALLOWED_SDK_ID = "123";
    private static final String APPSETID_API_ALLOW_LIST = "com.android.adservices.servicecoretest";
    private static final int SANDBOX_UID = 25000;

    private final Context mContext = ApplicationProvider.getApplicationContext();
    private final AdServicesLogger mAdServicesLogger =
            Mockito.spy(AdServicesLoggerImpl.getInstance());

    private CountDownLatch mGetAppSetIdCallbackLatch;
    private CallerMetadata mCallerMetadata;
    private AppSetIdWorker mAppSetIdWorker;
    private GetAppSetIdParam mRequest;
    private MockitoSession mStaticMockitoSession;

    @Mock private PackageManager mPackageManager;
    @Mock private Flags mMockFlags;
    @Mock private Clock mClock;
    @Mock private Context mMockSdkContext;
    @Mock private Context mMockAppContext;
    @Mock private Throttler mMockThrottler;
    @Mock private AppSetIdServiceImpl mAppSetIdServiceImpl;
    @Mock private AppImportanceFilter mMockAppImportanceFilter;

    @Before
    public void setup() throws Exception {
        MockitoAnnotations.initMocks(this);

        mAppSetIdWorker =
                Mockito.spy(
                        AppSetIdWorker.getInstance(ApplicationProvider.getApplicationContext()));
        Mockito.doReturn(null).when(mAppSetIdWorker).getService();

        when(mClock.elapsedRealtime()).thenReturn(150L, 200L);
        mCallerMetadata = new CallerMetadata.Builder().setBinderElapsedTimestamp(100L).build();
        mRequest =
                new GetAppSetIdParam.Builder()
                        .setAppPackageName(TEST_APP_PACKAGE_NAME)
                        .setSdkPackageName(SDK_PACKAGE_NAME)
                        .build();

        when(mMockSdkContext.getPackageManager()).thenReturn(mPackageManager);
        when(mPackageManager.getPackageUid(TEST_APP_PACKAGE_NAME, 0)).thenReturn(Process.myUid());

        // Put this test app into bypass list to bypass Allow-list check.
        when(mMockFlags.getPpapiAppAllowList()).thenReturn(APPSETID_API_ALLOW_LIST);

        // Rate Limit is not reached.
        when(mMockThrottler.tryAcquire(
                        eq(Throttler.ApiKey.APPSETID_API_APP_PACKAGE_NAME), anyString()))
                .thenReturn(true);

        // Initialize mock static.
        mStaticMockitoSession =
                ExtendedMockito.mockitoSession().mockStatic(Binder.class).startMocking();
    }

    @After
    public void tearDown() {
        mStaticMockitoSession.finishMocking();
    }

    @Test
    public void checkAllowList_emptyAllowList() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        // Empty allow list.
        when(mMockFlags.getPpapiAppAllowList()).thenReturn("");
        invokeGetAppSetIdAndVerifyError(
                mContext, STATUS_CALLER_NOT_ALLOWED, /* checkLoggingStatus */ true);
    }

    @Test
    public void checkThrottler_rateLimitReached_forAppPackageName() throws InterruptedException {
        // App calls AppSetId API directly, not via an SDK.
        GetAppSetIdParam request =
                new GetAppSetIdParam.Builder()
                        .setAppPackageName(TEST_APP_PACKAGE_NAME)
                        .setSdkPackageName(SDK_PACKAGE_NAME)
                        .build();

        // Rate Limit Reached.
        when(mMockThrottler.tryAcquire(
                        eq(Throttler.ApiKey.APPSETID_API_APP_PACKAGE_NAME), anyString()))
                .thenReturn(false);
        // We don't log STATUS_RATE_LIMIT_REACHED for getAppSetId API.
        invokeGetAppSetIdAndVerifyError(
                mContext, STATUS_RATE_LIMIT_REACHED, request, /* checkLoggingStatus */ false);
    }

    @Test
    public void testEnforceForeground_sandboxCaller() throws Exception {
        Assume.assumeTrue(SdkLevel.isAtLeastT()); // Sandbox caller is only applicable on T+

        // Mock AppImportanceFilter to throw Exception when invoked. This is to verify getAppSetId()
        // doesn't throw if caller is via Sandbox.
        doThrow(new WrongCallingApplicationStateException())
                .when(mMockAppImportanceFilter)
                .assertCallerIsInForeground(
                        SANDBOX_UID, AD_SERVICES_API_CALLED__API_NAME__GET_APPSETID, SOME_SDK_NAME);

        // Mock UID with SDK UID
        when(Binder.getCallingUidOrThrow()).thenReturn(SANDBOX_UID);

        // Mock Flags with true to enable enforcing foreground check.
        doReturn(true).when(mMockFlags).getEnforceForegroundStatusForAppSetId();

        // Mock to grant required permissions
        // Copied UID calculation from Process.getAppUidForSdkSandboxUid().
        final int appCallingUid = SANDBOX_UID - 10000;
        when(mPackageManager.getPackageUid(TEST_APP_PACKAGE_NAME, 0)).thenReturn(appCallingUid);

        // Verify getAppSetId() doesn't throw.
        mAppSetIdServiceImpl = createAppSetIdServiceImplInstance_SandboxContext();
        runGetAppSetId(mAppSetIdServiceImpl);

        verify(mMockAppImportanceFilter, never())
                .assertCallerIsInForeground(
                        SANDBOX_UID, AD_SERVICES_API_CALLED__API_NAME__GET_APPSETID, SOME_SDK_NAME);
    }

    @Test
    public void testEnforceForeground_disableEnforcing() throws Exception {
        final int uid = Process.myUid();
        // Mock AppImportanceFilter to throw Exception when invoked. This is to verify getAppSetId()
        // doesn't throw if enforcing foreground is disabled
        doThrow(new WrongCallingApplicationStateException())
                .when(mMockAppImportanceFilter)
                .assertCallerIsInForeground(
                        uid, AD_SERVICES_API_CALLED__API_NAME__GET_APPSETID, SOME_SDK_NAME);

        // Mock UID with Non-SDK UI
        when(Binder.getCallingUidOrThrow()).thenReturn(uid);

        // Mock Flags with false to disable enforcing foreground check.
        doReturn(false).when(mMockFlags).getEnforceForegroundStatusForAppSetId();

        // Mock to grant required permissions
        // TODO
        when(mPackageManager.getPackageUid(TEST_APP_PACKAGE_NAME, 0)).thenReturn(uid);

        // Verify getAppSetId() doesn't throw.
        mAppSetIdServiceImpl = createTestAppSetIdServiceImplInstance();
        runGetAppSetId(mAppSetIdServiceImpl);

        verify(mMockAppImportanceFilter, never())
                .assertCallerIsInForeground(
                        uid, AD_SERVICES_API_CALLED__API_NAME__GET_APPSETID, SOME_SDK_NAME);
    }

    @Test
    public void getAppSetId() throws Exception {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());
        runGetAppSetId(createTestAppSetIdServiceImplInstance());
    }

    @Test
    public void testGetAppSetId_enforceCallingPackage_invalidPackage() throws InterruptedException {
        when(Binder.getCallingUidOrThrow()).thenReturn(Process.myUid());

        AppSetIdServiceImpl appSetIdService = createTestAppSetIdServiceImplInstance();

        // A request with an invalid package name.
        mRequest =
                new GetAppSetIdParam.Builder()
                        .setAppPackageName(INVALID_PACKAGE_NAME)
                        .setSdkPackageName(SOME_SDK_NAME)
                        .build();

        invokeGetAppSetIdAndVerifyError(
                mContext, STATUS_CALLER_NOT_ALLOWED, mRequest, /* checkLoggingStatus */ true);
    }

    private void invokeGetAppSetIdAndVerifyError(
            Context context, int expectedResultCode, boolean checkLoggingStatus)
            throws InterruptedException {
        invokeGetAppSetIdAndVerifyError(context, expectedResultCode, mRequest, checkLoggingStatus);
    }

    private void invokeGetAppSetIdAndVerifyError(
            Context context,
            int expectedResultCode,
            GetAppSetIdParam request,
            boolean checkLoggingStatus)
            throws InterruptedException {
        CountDownLatch jobFinishedCountDown = new CountDownLatch(1);

        CountDownLatch logOperationCalledLatch = new CountDownLatch(1);
        Mockito.doAnswer(
                        new Answer<Object>() {
                            @Override
                            public Object answer(InvocationOnMock invocation) throws Throwable {
                                // The method logAPiCallStats is called.
                                invocation.callRealMethod();
                                logOperationCalledLatch.countDown();
                                return null;
                            }
                        })
                .when(mAdServicesLogger)
                .logApiCallStats(ArgumentMatchers.any(ApiCallStats.class));

        mAppSetIdServiceImpl =
                new AppSetIdServiceImpl(
                        context,
                        mAppSetIdWorker,
                        mAdServicesLogger,
                        mClock,
                        mMockFlags,
                        mMockThrottler,
                        mMockAppImportanceFilter);
        mAppSetIdServiceImpl.getAppSetId(
                request,
                mCallerMetadata,
                new IGetAppSetIdCallback() {
                    @Override
                    public void onResult(GetAppSetIdResult responseParcel) {
                        Assert.fail();
                        jobFinishedCountDown.countDown();
                    }

                    @Override
                    public void onError(int resultCode) {
                        assertThat(resultCode).isEqualTo(expectedResultCode);
                        jobFinishedCountDown.countDown();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });
        jobFinishedCountDown.await();

        if (checkLoggingStatus) {
            // getAppSetId method finished executing.
            logOperationCalledLatch.await();

            ArgumentCaptor<ApiCallStats> argument = ArgumentCaptor.forClass(ApiCallStats.class);

            verify(mAdServicesLogger).logApiCallStats(argument.capture());
            assertThat(argument.getValue().getCode()).isEqualTo(AD_SERVICES_API_CALLED);
            assertThat(argument.getValue().getApiClass())
                    .isEqualTo(AD_SERVICES_API_CALLED__API_CLASS__APPSETID);
            assertThat(argument.getValue().getApiName())
                    .isEqualTo(AD_SERVICES_API_CALLED__API_NAME__GET_APPSETID);
            assertThat(argument.getValue().getResultCode()).isEqualTo(expectedResultCode);
            assertThat(argument.getValue().getAppPackageName())
                    .isEqualTo(request.getAppPackageName());
            assertThat(argument.getValue().getSdkPackageName())
                    .isEqualTo(request.getSdkPackageName());
        }
    }

    private void runGetAppSetId(AppSetIdServiceImpl appSetIdServiceImpl) throws Exception {

        GetAppSetIdResult expectedGetAppSetIdResult =
                new GetAppSetIdResult.Builder()
                        .setAppSetId("00000000-0000-0000-0000-000000000000")
                        .setAppSetIdScope(0)
                        .build();

        final GetAppSetIdResult[] capturedResponseParcel = getAppSetIdResults(appSetIdServiceImpl);

        assertThat(
                        mGetAppSetIdCallbackLatch.await(
                                BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isTrue();

        GetAppSetIdResult getAppSetIdResult = capturedResponseParcel[0];
        assertThat(getAppSetIdResult.getAppSetId())
                .isEqualTo(expectedGetAppSetIdResult.getAppSetId());
    }

    @NonNull
    private GetAppSetIdResult[] getAppSetIdResults(AppSetIdServiceImpl appSetIdServiceImpl) {
        // To capture result in inner class, we have to declare final.
        final GetAppSetIdResult[] capturedResponseParcel = new GetAppSetIdResult[1];
        mGetAppSetIdCallbackLatch = new CountDownLatch(1);
        appSetIdServiceImpl.getAppSetId(
                mRequest,
                mCallerMetadata,
                new IGetAppSetIdCallback() {
                    @Override
                    public void onResult(GetAppSetIdResult responseParcel) {
                        capturedResponseParcel[0] = responseParcel;
                        mGetAppSetIdCallbackLatch.countDown();
                    }

                    @Override
                    public void onError(int resultCode) {
                        Assert.fail();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });

        return capturedResponseParcel;
    }

    @NonNull
    private AppSetIdServiceImpl createTestAppSetIdServiceImplInstance() {
        return new AppSetIdServiceImpl(
                mContext,
                mAppSetIdWorker,
                mAdServicesLogger,
                mClock,
                mMockFlags,
                mMockThrottler,
                mMockAppImportanceFilter);
    }

    @NonNull
    private AppSetIdServiceImpl createAppSetIdServiceImplInstance_SandboxContext() {
        return new AppSetIdServiceImpl(
                mMockSdkContext,
                mAppSetIdWorker,
                mAdServicesLogger,
                mClock,
                mMockFlags,
                mMockThrottler,
                mMockAppImportanceFilter);
    }
}
