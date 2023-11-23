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

package com.android.adservices.service.customaudience;

import static android.adservices.common.AdServicesStatusUtils.ILLEGAL_STATE_BACKGROUND_CALLER_ERROR_MESSAGE;
import static android.adservices.common.AdServicesStatusUtils.RATE_LIMIT_REACHED_ERROR_MESSAGE;
import static android.adservices.common.AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE;
import static android.adservices.common.AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ON_BEHALF_ERROR_MESSAGE;
import static android.adservices.common.AdServicesStatusUtils.STATUS_BACKGROUND_CALLER;
import static android.adservices.common.AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
import static android.adservices.common.AdServicesStatusUtils.STATUS_INVALID_ARGUMENT;
import static android.adservices.common.AdServicesStatusUtils.STATUS_RATE_LIMIT_REACHED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;
import static android.adservices.common.AdServicesStatusUtils.STATUS_UNAUTHORIZED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_USER_CONSENT_REVOKED;

import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_JOIN_CUSTOM_AUDIENCE;
import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_LEAVE_CUSTOM_AUDIENCE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__REMOVE_CUSTOM_AUDIENCE_REMOTE_INFO_OVERRIDE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__RESET_ALL_CUSTOM_AUDIENCE_OVERRIDES;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.staticMockMarker;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.CallingAppUidSupplierFailureImpl;
import android.adservices.common.CallingAppUidSupplierProcessImpl;
import android.adservices.common.CommonFixture;
import android.adservices.common.FledgeErrorResponse;
import android.adservices.customaudience.CustomAudience;
import android.adservices.customaudience.CustomAudienceFixture;
import android.adservices.customaudience.CustomAudienceOverrideCallback;
import android.adservices.customaudience.ICustomAudienceCallback;
import android.content.Context;
import android.os.Process;
import android.os.RemoteException;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.DBCustomAudienceOverride;
import com.android.adservices.service.Flags;
import com.android.adservices.service.adselection.JsVersionRegister;
import com.android.adservices.service.common.AppImportanceFilter;
import com.android.adservices.service.common.AppImportanceFilter.WrongCallingApplicationStateException;
import com.android.adservices.service.common.CustomAudienceServiceFilter;
import com.android.adservices.service.common.FledgeAllowListsFilter;
import com.android.adservices.service.common.FledgeAuthorizationFilter;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.devapi.DevContext;
import com.android.adservices.service.devapi.DevContextFilter;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;

import java.util.concurrent.ExecutorService;
import java.util.function.Supplier;

public class CustomAudienceServiceImplTest {
    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    private static final ExecutorService DIRECT_EXECUTOR = MoreExecutors.newDirectExecutorService();

    private static final CustomAudience VALID_CUSTOM_AUDIENCE =
            CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1).build();

    @Mock private CustomAudienceImpl mCustomAudienceImpl;
    @Mock private FledgeAuthorizationFilter mFledgeAuthorizationFilter;
    @Mock private FledgeAllowListsFilter mFledgeAllowListsFilter;
    @Mock private ConsentManager mConsentManagerMock;
    @Mock private ICustomAudienceCallback mICustomAudienceCallback;
    @Mock private CustomAudienceOverrideCallback mCustomAudienceOverrideCallback;
    @Mock private AppImportanceFilter mAppImportanceFilter;
    @Mock private CustomAudienceDao mCustomAudienceDao;
    @Mock DevContextFilter mDevContextFilter;
    private final AdServicesLogger mAdServicesLoggerMock =
            ExtendedMockito.mock(AdServicesLoggerImpl.class);
    @Mock private Throttler mMockThrottler;
    private Supplier<Throttler> mThrottlerSupplier = () -> mMockThrottler;

    private static final int MY_UID = Process.myUid();

    private final Flags mFlagsWithAllCheckEnabled = new FlagsWithCheckEnabledSwitch(true, true);
    private final Flags mFlagsWithForegroundCheckDisabled =
            new FlagsWithCheckEnabledSwitch(false, true);
    private final Flags mFlagsWithEnrollmentCheckDisabled =
            new FlagsWithCheckEnabledSwitch(true, false);

    private CustomAudienceServiceImpl mService;

    private MockitoSession mStaticMockitoSession;

    @Before
    public void setup() throws Exception {
        mStaticMockitoSession =
                mockitoSession()
                        .initMocks(this)
                        .mockStatic(BackgroundFetchJobService.class)
                        .startMocking();

        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithAllCheckEnabled,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithAllCheckEnabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        Mockito.lenient()
                .when(mMockThrottler.tryAcquire(eq(FLEDGE_API_JOIN_CUSTOM_AUDIENCE), anyString()))
                .thenReturn(true);
        Mockito.lenient()
                .when(mMockThrottler.tryAcquire(eq(FLEDGE_API_LEAVE_CUSTOM_AUDIENCE), anyString()))
                .thenReturn(true);
    }

    @After
    public void teardown() {
        verifyNoMoreInteractions(
                mCustomAudienceImpl,
                mFledgeAuthorizationFilter,
                staticMockMarker(BackgroundFetchJobService.class),
                mFledgeAllowListsFilter,
                mICustomAudienceCallback,
                mCustomAudienceOverrideCallback,
                mCustomAudienceDao,
                mDevContextFilter,
                mAppImportanceFilter,
                mConsentManagerMock,
                mAdServicesLoggerMock);
        mStaticMockitoSession.finishMocking();
    }

    @Test
    public void testJoinCustomAudience_runNormally() throws RemoteException {

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);
        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mCustomAudienceImpl)
                .joinCustomAudience(VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER);
        verify(
                () ->
                        BackgroundFetchJobService.scheduleIfNeeded(
                                CONTEXT, mFlagsWithAllCheckEnabled, false));
        verify(mICustomAudienceCallback).onSuccess();
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, null);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock).isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);

        verifyLoggerMock(AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_SUCCESS);
    }

    @Test
    public void testJoinCustomAudienceWithRevokedUserConsentSuccess() throws RemoteException {
        doReturn(true)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);
        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        any(), eq(AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE));
        verify(mICustomAudienceCallback).onSuccess();
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock).isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, null);

        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE,
                STATUS_USER_CONSENT_REVOKED);
    }

    @Test
    public void testJoinCustomAudience_notInBinderThread() {
        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithAllCheckEnabled,
                        CallingAppUidSupplierFailureImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithAllCheckEnabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        assertThrows(
                IllegalStateException.class,
                () ->
                        mService.joinCustomAudience(
                                VALID_CUSTOM_AUDIENCE,
                                CustomAudienceFixture.VALID_OWNER,
                                mICustomAudienceCallback));

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_INTERNAL_ERROR);
    }

    @Test
    public void testJoinCustomAudience_ownerAssertFailed() throws RemoteException {
        doThrow(new FledgeAuthorizationFilter.CallerMismatchException())
                .when(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verifyErrorResponseICustomAudienceCallback(
                STATUS_UNAUTHORIZED, SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ON_BEHALF_ERROR_MESSAGE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
    }

    @Test
    public void testJoinCustomAudience_nullInput() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mService.joinCustomAudience(
                                null, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback));

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_INVALID_ARGUMENT);
    }

    @Test
    public void testJoinCustomAudience_nullCallerPackageName() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mService.joinCustomAudience(
                                VALID_CUSTOM_AUDIENCE, null, mICustomAudienceCallback));

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_INVALID_ARGUMENT);
    }

    @Test
    public void testJoinCustomAudience_nullCallback() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mService.joinCustomAudience(
                                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, null));

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_INVALID_ARGUMENT);
    }

    @Test
    public void testJoinCustomAudience_errorCreateCustomAudience() throws RemoteException {
        String errorMessage = "Simulating Error creating Custom Audience";
        doThrow(new RuntimeException(errorMessage))
                .when(mCustomAudienceImpl)
                .joinCustomAudience(VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER);

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mCustomAudienceImpl)
                .joinCustomAudience(VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER);
        verifyErrorResponseICustomAudienceCallback(STATUS_INTERNAL_ERROR, errorMessage);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_INTERNAL_ERROR);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, null);
        verify(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(
                        CustomAudienceFixture.VALID_OWNER);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);

        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_INTERNAL_ERROR);
    }

    @Test
    public void testJoinCustomAudience_errorReturnCallback() throws RemoteException {
        doThrow(RemoteException.class).when(mICustomAudienceCallback).onSuccess();

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mCustomAudienceImpl)
                .joinCustomAudience(VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER);
        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)));
        verify(mICustomAudienceCallback).onSuccess();
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, null);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(
                        CustomAudienceFixture.VALID_OWNER);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_INTERNAL_ERROR);
    }

    @Test
    public void testLeaveCustomAudience_runNormally() throws RemoteException {
        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        any(), eq(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE));
        verify(mCustomAudienceImpl)
                .leaveCustomAudience(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME);
        verify(mICustomAudienceCallback).onSuccess();
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, null);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock).isFledgeConsentRevokedForApp(CustomAudienceFixture.VALID_OWNER);

        verifyLoggerMock(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_SUCCESS);
    }

    @Test
    public void testLeaveCustomAudienceWithRevokedUserConsent() throws RemoteException {
        doReturn(true).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        any(), eq(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE));
        verify(mICustomAudienceCallback).onSuccess();
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock).isFledgeConsentRevokedForApp(CustomAudienceFixture.VALID_OWNER);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, null);

        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE,
                STATUS_USER_CONSENT_REVOKED);
    }

    @Test
    public void testLeaveCustomAudience_notInBinderThread() {
        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithAllCheckEnabled,
                        CallingAppUidSupplierFailureImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithAllCheckEnabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        assertThrows(
                IllegalStateException.class,
                () ->
                        mService.leaveCustomAudience(
                                CustomAudienceFixture.VALID_OWNER,
                                CommonFixture.VALID_BUYER_1,
                                CustomAudienceFixture.VALID_NAME,
                                mICustomAudienceCallback));

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        any(), eq(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE));
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_INTERNAL_ERROR);
    }

    @Test
    public void testLeaveCustomAudience_ownerAssertFailed() throws RemoteException {
        doThrow(new FledgeAuthorizationFilter.CallerMismatchException())
                .when(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        any(), eq(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE));
        verifyErrorResponseICustomAudienceCallback(
                STATUS_UNAUTHORIZED, SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ON_BEHALF_ERROR_MESSAGE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
    }

    @Test
    public void testLeaveCustomAudience_nullOwner() {
        doNothing().when(mFledgeAuthorizationFilter).assertAppDeclaredPermission(any(), anyInt());
        assertThrows(
                NullPointerException.class,
                () ->
                        mService.leaveCustomAudience(
                                null,
                                CommonFixture.VALID_BUYER_1,
                                CustomAudienceFixture.VALID_NAME,
                                mICustomAudienceCallback));

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_INVALID_ARGUMENT);
    }

    @Test
    public void testLeaveCustomAudience_nullBuyer() {
        doNothing().when(mFledgeAuthorizationFilter).assertAppDeclaredPermission(any(), anyInt());
        assertThrows(
                NullPointerException.class,
                () ->
                        mService.leaveCustomAudience(
                                CustomAudienceFixture.VALID_OWNER,
                                null,
                                CustomAudienceFixture.VALID_NAME,
                                mICustomAudienceCallback));

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_INVALID_ARGUMENT);
    }

    @Test
    public void testLeaveCustomAudience_nullName() {
        doNothing().when(mFledgeAuthorizationFilter).assertAppDeclaredPermission(any(), anyInt());
        assertThrows(
                NullPointerException.class,
                () ->
                        mService.leaveCustomAudience(
                                CustomAudienceFixture.VALID_OWNER,
                                CommonFixture.VALID_BUYER_1,
                                null,
                                mICustomAudienceCallback));

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_INVALID_ARGUMENT);
    }

    @Test
    public void testLeaveCustomAudience_nullCallback() {
        doNothing().when(mFledgeAuthorizationFilter).assertAppDeclaredPermission(any(), anyInt());
        assertThrows(
                NullPointerException.class,
                () ->
                        mService.leaveCustomAudience(
                                CustomAudienceFixture.VALID_OWNER,
                                CommonFixture.VALID_BUYER_1,
                                CustomAudienceFixture.VALID_NAME,
                                null));

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_INVALID_ARGUMENT);
    }

    @Test
    public void testLeaveCustomAudience_errorCallCustomAudienceImpl() throws RemoteException {
        doThrow(new RuntimeException("Simulating Error calling CustomAudienceImpl"))
                .when(mCustomAudienceImpl)
                .leaveCustomAudience(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME);

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        any(), eq(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE));
        verify(mCustomAudienceImpl)
                .leaveCustomAudience(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME);
        verify(mICustomAudienceCallback).onSuccess();
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, null);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock).isFledgeConsentRevokedForApp(CustomAudienceFixture.VALID_OWNER);

        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_INTERNAL_ERROR);
    }

    @Test
    public void testLeaveCustomAudience_errorReturnCallback() throws RemoteException {
        doThrow(RemoteException.class).when(mICustomAudienceCallback).onSuccess();

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, null);
        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        any(), eq(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE));
        verify(mCustomAudienceImpl)
                .leaveCustomAudience(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME);
        verify(mICustomAudienceCallback).onSuccess();
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock).isFledgeConsentRevokedForApp(CustomAudienceFixture.VALID_OWNER);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_INTERNAL_ERROR);
    }

    @Test
    public void testAppImportanceTestFails_joinCustomAudienceThrowsException()
            throws RemoteException {
        doThrow(new WrongCallingApplicationStateException())
                .when(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, null);

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, null);
        verifyErrorResponseICustomAudienceCallback(
                STATUS_BACKGROUND_CALLER, ILLEGAL_STATE_BACKGROUND_CALLER_ERROR_MESSAGE);
    }

    @Test
    public void testAppImportanceDisabledCallerInBackground_joinCustomAudienceSucceeds()
            throws RemoteException {
        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithForegroundCheckDisabled,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithForegroundCheckDisabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);
        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(
                        CustomAudienceFixture.VALID_OWNER);
        verify(mCustomAudienceImpl)
                .joinCustomAudience(VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER);
        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)));
        verify(mICustomAudienceCallback).onSuccess();
        verifyLoggerMock(AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_SUCCESS);
    }

    @Test
    public void testAppImportanceTestFails_leaveCustomAudienceThrowsException()
            throws RemoteException {
        doThrow(new WrongCallingApplicationStateException())
                .when(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, null);

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, null);
        verifyErrorResponseICustomAudienceCallback(
                AdServicesStatusUtils.STATUS_BACKGROUND_CALLER,
                ILLEGAL_STATE_BACKGROUND_CALLER_ERROR_MESSAGE);
    }

    @Test
    public void testAppImportanceDisabledCallerInBackground_leaveCustomAudienceSucceeds()
            throws RemoteException {
        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithForegroundCheckDisabled,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithForegroundCheckDisabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock).isFledgeConsentRevokedForApp(CustomAudienceFixture.VALID_OWNER);
        verify(mCustomAudienceImpl)
                .leaveCustomAudience(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME);
        verify(mICustomAudienceCallback).onSuccess();
        verifyLoggerMock(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_SUCCESS);
    }

    @Test
    public void testAppImportanceTestFails_overrideCustomAudienceThrowsException()
            throws RemoteException {
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setCallingAppPackageName("")
                                .setDevOptionsEnabled(true)
                                .build());
        when(mCustomAudienceImpl.getCustomAudienceDao()).thenReturn(mCustomAudienceDao);
        doThrow(new WrongCallingApplicationStateException())
                .when(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO,
                        null);

        mService.overrideCustomAudienceRemoteInfo(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                "",
                JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3,
                AdSelectionSignals.EMPTY,
                mCustomAudienceOverrideCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT,
                        AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO);
        verify(mDevContextFilter).createDevContext();
        verify(mCustomAudienceImpl).getCustomAudienceDao();
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO,
                        null);
        verifyErrorResponseCustomAudienceOverrideCallback(
                AdServicesStatusUtils.STATUS_BACKGROUND_CALLER,
                ILLEGAL_STATE_BACKGROUND_CALLER_ERROR_MESSAGE);
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO,
                STATUS_BACKGROUND_CALLER);
    }

    @Test
    public void testAppImportanceDisabledCallerInBackground_overrideCustomAudienceSucceeds()
            throws RemoteException {
        when(mCustomAudienceImpl.getCustomAudienceDao()).thenReturn(mCustomAudienceDao);
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setCallingAppPackageName(CustomAudienceFixture.VALID_OWNER)
                                .setDevOptionsEnabled(true)
                                .build());
        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithForegroundCheckDisabled,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithForegroundCheckDisabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        mService.overrideCustomAudienceRemoteInfo(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                "",
                JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3,
                AdSelectionSignals.EMPTY,
                mCustomAudienceOverrideCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT,
                        AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO);
        verify(mDevContextFilter).createDevContext();
        verify(mCustomAudienceImpl).getCustomAudienceDao();
        verify(mConsentManagerMock).isFledgeConsentRevokedForApp(CustomAudienceFixture.VALID_OWNER);
        verify(mCustomAudienceDao)
                .persistCustomAudienceOverride(
                        DBCustomAudienceOverride.builder()
                                .setOwner(CustomAudienceFixture.VALID_OWNER)
                                .setBuyer(CommonFixture.VALID_BUYER_1)
                                .setName(CustomAudienceFixture.VALID_NAME)
                                .setBiddingLogicJS("")
                                .setBiddingLogicJsVersion(
                                        JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3)
                                .setTrustedBiddingData(AdSelectionSignals.EMPTY.toString())
                                .setAppPackageName(CustomAudienceFixture.VALID_OWNER)
                                .build());
        verify(mCustomAudienceOverrideCallback).onSuccess();
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO,
                STATUS_SUCCESS);
    }

    @Test
    public void testAppImportanceTestFails_removeCustomAudienceOverrideThrowsException()
            throws RemoteException {
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setCallingAppPackageName("")
                                .setDevOptionsEnabled(true)
                                .build());
        when(mCustomAudienceImpl.getCustomAudienceDao()).thenReturn(mCustomAudienceDao);
        int apiName = AD_SERVICES_API_CALLED__API_NAME__REMOVE_CUSTOM_AUDIENCE_REMOTE_INFO_OVERRIDE;
        doThrow(new WrongCallingApplicationStateException())
                .when(mAppImportanceFilter)
                .assertCallerIsInForeground(CustomAudienceFixture.VALID_OWNER, apiName, null);

        mService.removeCustomAudienceRemoteInfoOverride(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mCustomAudienceOverrideCallback);

        verify(mFledgeAuthorizationFilter).assertAppDeclaredPermission(CONTEXT, apiName);
        verify(mDevContextFilter).createDevContext();
        verify(mCustomAudienceImpl).getCustomAudienceDao();
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(CustomAudienceFixture.VALID_OWNER, apiName, null);
        verifyErrorResponseCustomAudienceOverrideCallback(
                STATUS_BACKGROUND_CALLER, ILLEGAL_STATE_BACKGROUND_CALLER_ERROR_MESSAGE);
        verifyLoggerMock(apiName, STATUS_BACKGROUND_CALLER);
    }

    @Test
    public void testAppImportanceDisabledCallerInBackground_removeCustomAudienceOverrideSucceeds()
            throws RemoteException {
        when(mCustomAudienceImpl.getCustomAudienceDao()).thenReturn(mCustomAudienceDao);
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setCallingAppPackageName(CustomAudienceFixture.VALID_OWNER)
                                .setDevOptionsEnabled(true)
                                .build());
        int apiName = AD_SERVICES_API_CALLED__API_NAME__REMOVE_CUSTOM_AUDIENCE_REMOTE_INFO_OVERRIDE;
        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithForegroundCheckDisabled,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithForegroundCheckDisabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        mService.removeCustomAudienceRemoteInfoOverride(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mCustomAudienceOverrideCallback);

        verify(mFledgeAuthorizationFilter).assertAppDeclaredPermission(CONTEXT, apiName);
        verify(mDevContextFilter).createDevContext();
        verify(mCustomAudienceImpl).getCustomAudienceDao();
        verify(mConsentManagerMock).isFledgeConsentRevokedForApp(CustomAudienceFixture.VALID_OWNER);
        verify(mCustomAudienceDao)
                .removeCustomAudienceOverrideByPrimaryKeyAndPackageName(
                        CustomAudienceFixture.VALID_OWNER, CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME, CustomAudienceFixture.VALID_OWNER);
        verify(mCustomAudienceOverrideCallback).onSuccess();
        verifyLoggerMock(apiName, STATUS_SUCCESS);
    }

    @Test
    public void testAppImportanceTestFails_resetOverridesThrowsException() throws RemoteException {
        when(mCustomAudienceImpl.getCustomAudienceDao()).thenReturn(mCustomAudienceDao);
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setCallingAppPackageName(CustomAudienceFixture.VALID_OWNER)
                                .setDevOptionsEnabled(true)
                                .build());
        int apiName = AD_SERVICES_API_CALLED__API_NAME__RESET_ALL_CUSTOM_AUDIENCE_OVERRIDES;
        doThrow(new WrongCallingApplicationStateException())
                .when(mAppImportanceFilter)
                .assertCallerIsInForeground(Process.myUid(), apiName, null);

        mService.resetAllCustomAudienceOverrides(mCustomAudienceOverrideCallback);

        verify(mFledgeAuthorizationFilter).assertAppDeclaredPermission(CONTEXT, apiName);
        verify(mDevContextFilter).createDevContext();
        verify(mCustomAudienceImpl).getCustomAudienceDao();
        verify(mAppImportanceFilter).assertCallerIsInForeground(Process.myUid(), apiName, null);
        verifyLoggerMock(apiName, STATUS_BACKGROUND_CALLER);
        verifyErrorResponseCustomAudienceOverrideCallback(
                STATUS_BACKGROUND_CALLER, ILLEGAL_STATE_BACKGROUND_CALLER_ERROR_MESSAGE);
    }

    @Test
    public void testAppImportanceDisabledCallerInBackground_resetOverridesSucceeds()
            throws RemoteException {
        when(mCustomAudienceImpl.getCustomAudienceDao()).thenReturn(mCustomAudienceDao);
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setCallingAppPackageName(CustomAudienceFixture.VALID_OWNER)
                                .setDevOptionsEnabled(true)
                                .build());
        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithForegroundCheckDisabled,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithForegroundCheckDisabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        mService.resetAllCustomAudienceOverrides(mCustomAudienceOverrideCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT,
                        AD_SERVICES_API_CALLED__API_NAME__RESET_ALL_CUSTOM_AUDIENCE_OVERRIDES);
        verify(mDevContextFilter).createDevContext();
        verify(mCustomAudienceImpl).getCustomAudienceDao();
        verify(mConsentManagerMock).isFledgeConsentRevokedForApp(CustomAudienceFixture.VALID_OWNER);
        verify(mCustomAudienceDao)
                .removeCustomAudienceOverridesByPackageName(CustomAudienceFixture.VALID_OWNER);
        verify(mCustomAudienceOverrideCallback).onSuccess();
        verifyLoggerMock(
                AD_SERVICES_API_CALLED__API_NAME__RESET_ALL_CUSTOM_AUDIENCE_OVERRIDES,
                STATUS_SUCCESS);
    }

    @Test
    public void testAppManifestPermissionNotRequested_joinCustomAudience_fails() {
        doThrow(SecurityException.class)
                .when(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);

        assertThrows(
                SecurityException.class,
                () ->
                        mService.joinCustomAudience(
                                VALID_CUSTOM_AUDIENCE,
                                CustomAudienceFixture.VALID_OWNER,
                                mICustomAudienceCallback));
    }

    @Test
    public void testAppManifestPermissionNotRequested_leaveCustomAudience_fails() {
        doThrow(SecurityException.class)
                .when(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);

        assertThrows(
                SecurityException.class,
                () ->
                        mService.leaveCustomAudience(
                                CustomAudienceFixture.VALID_OWNER,
                                CommonFixture.VALID_BUYER_1,
                                CustomAudienceFixture.VALID_NAME,
                                mICustomAudienceCallback));
    }

    @Test
    public void testAppManifestPermissionNotRequested_overrideCustomAudienceRemoteInfo_fails() {
        doThrow(SecurityException.class)
                .when(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT,
                        AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO);

        assertThrows(
                SecurityException.class,
                () ->
                        mService.overrideCustomAudienceRemoteInfo(
                                CustomAudienceFixture.VALID_OWNER,
                                CommonFixture.VALID_BUYER_1,
                                CustomAudienceFixture.VALID_NAME,
                                "",
                                JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3,
                                AdSelectionSignals.EMPTY,
                                mCustomAudienceOverrideCallback));
    }

    @Test
    public void
            testAppManifestPermissionNotRequested_removeCustomAudienceRemoteInfoOverride_fails() {
        int apiName = AD_SERVICES_API_CALLED__API_NAME__REMOVE_CUSTOM_AUDIENCE_REMOTE_INFO_OVERRIDE;
        doThrow(SecurityException.class)
                .when(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(CONTEXT, apiName);

        assertThrows(
                SecurityException.class,
                () ->
                        mService.removeCustomAudienceRemoteInfoOverride(
                                CustomAudienceFixture.VALID_OWNER,
                                CommonFixture.VALID_BUYER_1,
                                CustomAudienceFixture.VALID_NAME,
                                mCustomAudienceOverrideCallback));
    }

    @Test
    public void testAppManifestPermissionNotRequested_resetAllCustomAudienceOverrides_fails() {
        doThrow(SecurityException.class)
                .when(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT,
                        AD_SERVICES_API_CALLED__API_NAME__RESET_ALL_CUSTOM_AUDIENCE_OVERRIDES);

        assertThrows(
                SecurityException.class,
                () -> mService.resetAllCustomAudienceOverrides(mCustomAudienceOverrideCallback));
    }

    @Test
    public void testEnrollmentCheckEnabledWithNoEnrollment_joinCustomAudience_fails()
            throws RemoteException {
        doThrow(new FledgeAuthorizationFilter.AdTechNotAllowedException())
                .when(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, null);

        verifyErrorResponseICustomAudienceCallback(
                STATUS_CALLER_NOT_ALLOWED, SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE);
    }

    @Test
    public void testEnrollmentCheckDisabled_joinCustomAudience_runNormally()
            throws RemoteException {
        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithEnrollmentCheckDisabled,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithEnrollmentCheckDisabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);
        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mCustomAudienceImpl)
                .joinCustomAudience(VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER);
        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)));
        verify(mICustomAudienceCallback).onSuccess();
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, null);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock).isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        verifyLoggerMock(AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, STATUS_SUCCESS);
    }

    @Test
    public void testEnrollmentCheckEnabledWithNoEnrollment_leaveCustomAudience_fails()
            throws RemoteException {
        doThrow(new FledgeAuthorizationFilter.AdTechNotAllowedException())
                .when(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, null);

        verifyErrorResponseICustomAudienceCallback(
                STATUS_CALLER_NOT_ALLOWED, SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE);
    }

    @Test
    public void testEnrollmentCheckDisabled_leaveCustomAudience_runNormally()
            throws RemoteException {
        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        mCustomAudienceImpl,
                        mFledgeAuthorizationFilter,
                        mConsentManagerMock,
                        mDevContextFilter,
                        DIRECT_EXECUTOR,
                        mAdServicesLoggerMock,
                        mAppImportanceFilter,
                        mFlagsWithEnrollmentCheckDisabled,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                mFlagsWithEnrollmentCheckDisabled,
                                mAppImportanceFilter,
                                mFledgeAuthorizationFilter,
                                mFledgeAllowListsFilter,
                                mThrottlerSupplier));

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        any(), eq(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE));
        verify(mCustomAudienceImpl)
                .leaveCustomAudience(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME);
        verify(mICustomAudienceCallback).onSuccess();
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, null);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mConsentManagerMock).isFledgeConsentRevokedForApp(CustomAudienceFixture.VALID_OWNER);

        verifyLoggerMock(AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, STATUS_SUCCESS);
    }

    @Test
    public void testNotInAllowList_joinCustomAudience_fail() throws RemoteException {
        doThrow(new FledgeAllowListsFilter.AppNotAllowedException())
                .when(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE, null);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verifyErrorResponseICustomAudienceCallback(
                STATUS_CALLER_NOT_ALLOWED, SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE);
    }

    @Test
    public void testNotInAllowList_leaveCustomAudience_fail() throws RemoteException {
        doThrow(new FledgeAllowListsFilter.AppNotAllowedException())
                .when(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);
        verify(mFledgeAuthorizationFilter)
                .assertAdTechAllowed(
                        CONTEXT,
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mAppImportanceFilter)
                .assertCallerIsInForeground(
                        MY_UID, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE, null);
        verify(mFledgeAllowListsFilter)
                .assertAppCanUsePpapi(
                        CustomAudienceFixture.VALID_OWNER,
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verifyErrorResponseICustomAudienceCallback(
                STATUS_CALLER_NOT_ALLOWED, SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE);
    }

    @Test
    public void testJoinCustomAudience_throttledFailure() throws RemoteException {
        // Throttle Join Custom Audience
        when(mMockThrottler.tryAcquire(eq(FLEDGE_API_JOIN_CUSTOM_AUDIENCE), anyString()))
                .thenReturn(false);

        mService.joinCustomAudience(
                VALID_CUSTOM_AUDIENCE, CustomAudienceFixture.VALID_OWNER, mICustomAudienceCallback);

        ArgumentCaptor<FledgeErrorResponse> actualResponseCaptor =
                ArgumentCaptor.forClass(FledgeErrorResponse.class);
        verify(mICustomAudienceCallback).onFailure(actualResponseCaptor.capture());
        assertEquals(STATUS_RATE_LIMIT_REACHED, actualResponseCaptor.getValue().getStatusCode());
        assertEquals(
                RATE_LIMIT_REACHED_ERROR_MESSAGE,
                actualResponseCaptor.getValue().getErrorMessage());
        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE);
    }

    @Test
    public void testLeaveCustomAudience_throttledFailure() throws RemoteException {
        // Throttle Leave Custom Audience
        when(mMockThrottler.tryAcquire(eq(FLEDGE_API_LEAVE_CUSTOM_AUDIENCE), anyString()))
                .thenReturn(false);

        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                mICustomAudienceCallback);

        ArgumentCaptor<FledgeErrorResponse> actualResponseCaptor =
                ArgumentCaptor.forClass(FledgeErrorResponse.class);
        verify(mICustomAudienceCallback).onFailure(actualResponseCaptor.capture());
        assertEquals(STATUS_RATE_LIMIT_REACHED, actualResponseCaptor.getValue().getStatusCode());
        assertEquals(
                RATE_LIMIT_REACHED_ERROR_MESSAGE,
                actualResponseCaptor.getValue().getErrorMessage());
        verify(mFledgeAuthorizationFilter)
                .assertAppDeclaredPermission(
                        CONTEXT, AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
        verify(mFledgeAuthorizationFilter)
                .assertCallingPackageName(
                        CustomAudienceFixture.VALID_OWNER,
                        Process.myUid(),
                        AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE);
    }

    private void verifyErrorResponseICustomAudienceCallback(int statusCode, String errorMessage)
            throws RemoteException {
        ArgumentCaptor<FledgeErrorResponse> errorCaptor =
                ArgumentCaptor.forClass(FledgeErrorResponse.class);
        verify(mICustomAudienceCallback).onFailure(errorCaptor.capture());
        assertEquals(statusCode, errorCaptor.getValue().getStatusCode());
        assertEquals(errorMessage, errorCaptor.getValue().getErrorMessage());
    }

    private void verifyErrorResponseCustomAudienceOverrideCallback(
            int statusCode, String errorMessage) throws RemoteException {
        ArgumentCaptor<FledgeErrorResponse> errorCaptor =
                ArgumentCaptor.forClass(FledgeErrorResponse.class);
        verify(mCustomAudienceOverrideCallback).onFailure(errorCaptor.capture());
        assertEquals(statusCode, errorCaptor.getValue().getStatusCode());
        assertEquals(errorMessage, errorCaptor.getValue().getErrorMessage());
    }

    private void verifyLoggerMock(int apiName, int statusCode) {
        verify(mAdServicesLoggerMock).logFledgeApiCallStats(eq(apiName), eq(statusCode), anyInt());
    }

    private static class FlagsWithCheckEnabledSwitch implements Flags {
        private final boolean mForegroundCheckEnabled;
        private final boolean mEnrollmentCheckEnabled;

        FlagsWithCheckEnabledSwitch(
                boolean foregroundCheckEnabled, boolean enrollmentCheckEnabled) {
            mForegroundCheckEnabled = foregroundCheckEnabled;
            mEnrollmentCheckEnabled = enrollmentCheckEnabled;
        }

        @Override
        public boolean getEnforceForegroundStatusForFledgeCustomAudience() {
            return mForegroundCheckEnabled;
        }

        @Override
        public boolean getEnforceForegroundStatusForFledgeOverrides() {
            return mForegroundCheckEnabled;
        }

        @Override
        public boolean getDisableFledgeEnrollmentCheck() {
            return !mEnrollmentCheckEnabled;
        }
    }
}
