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

import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_JOIN_CUSTOM_AUDIENCE;
import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_LEAVE_CUSTOM_AUDIENCE;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.any;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyBoolean;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.times;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;

import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.CallingAppUidSupplierFailureImpl;
import android.adservices.common.CallingAppUidSupplierProcessImpl;
import android.adservices.common.CommonFixture;
import android.adservices.common.FledgeErrorResponse;
import android.adservices.customaudience.CustomAudience;
import android.adservices.customaudience.CustomAudienceFixture;
import android.adservices.customaudience.CustomAudienceOverrideCallback;
import android.adservices.customaudience.ICustomAudienceCallback;
import android.content.Context;
import android.os.IBinder;
import android.os.LimitExceededException;
import android.os.RemoteException;

import androidx.room.Room;
import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.customaudience.DBCustomAudienceFixture;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.CustomAudienceDatabase;
import com.android.adservices.data.customaudience.DBCustomAudience;
import com.android.adservices.data.customaudience.DBCustomAudienceOverride;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.adselection.JsVersionRegister;
import com.android.adservices.service.common.AppImportanceFilter;
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
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

public class CustomAudienceServiceEndToEndTest {
    protected static final Context CONTEXT = ApplicationProvider.getApplicationContext();

    private static final CustomAudience CUSTOM_AUDIENCE_PK1_1 =
            CustomAudienceFixture.getValidBuilderForBuyerFilters(CommonFixture.VALID_BUYER_1)
                    .build();

    private static final CustomAudience CUSTOM_AUDIENCE_PK1_2 =
            CustomAudienceFixture.getValidBuilderForBuyerFilters(CommonFixture.VALID_BUYER_1)
                    .setActivationTime(CustomAudienceFixture.VALID_DELAYED_ACTIVATION_TIME)
                    .setExpirationTime(CustomAudienceFixture.VALID_DELAYED_EXPIRATION_TIME)
                    .build();

    private static final CustomAudience CUSTOM_AUDIENCE_PK1_BEYOND_MAX_EXPIRATION_TIME =
            CustomAudienceFixture.getValidBuilderForBuyerFilters(CommonFixture.VALID_BUYER_1)
                    .setExpirationTime(CustomAudienceFixture.INVALID_BEYOND_MAX_EXPIRATION_TIME)
                    .build();

    private static final DBCustomAudience DB_CUSTOM_AUDIENCE_PK1_1 =
            DBCustomAudienceFixture.getValidBuilderByBuyer(CommonFixture.VALID_BUYER_1).build();

    private static final DBCustomAudience DB_CUSTOM_AUDIENCE_PK1_2 =
            DBCustomAudienceFixture.getValidBuilderByBuyer(CommonFixture.VALID_BUYER_1)
                    .setActivationTime(CustomAudienceFixture.VALID_DELAYED_ACTIVATION_TIME)
                    .setExpirationTime(CustomAudienceFixture.VALID_DELAYED_EXPIRATION_TIME)
                    .build();

    private static final String MY_APP_PACKAGE_NAME = "com.google.ppapi.test";
    private static final AdTechIdentifier BUYER_1 = AdTechIdentifier.fromString("BUYER_1");
    private static final AdTechIdentifier BUYER_2 = AdTechIdentifier.fromString("BUYER_2");
    private static final String NAME_1 = "NAME_1";
    private static final String NAME_2 = "NAME_2";
    private static final String BIDDING_LOGIC_JS = "function test() { return \"hello world\"; }";
    private static final AdSelectionSignals TRUSTED_BIDDING_DATA =
            AdSelectionSignals.fromString("{\"trusted_bidding_data\":1}");

    private CustomAudienceDao mCustomAudienceDao;

    private CustomAudienceServiceImpl mService;

    private MockitoSession mStaticMockSession = null;

    @Mock private ConsentManager mConsentManagerMock;

    // This object access some system APIs
    @Mock private DevContextFilter mDevContextFilter;
    @Mock private Throttler mMockThrottler;
    private Supplier<Throttler> mThrottlerSupplier = () -> mMockThrottler;
    @Mock private AppImportanceFilter mAppImportanceFilter;
    private final AdServicesLogger mAdServicesLogger = AdServicesLoggerImpl.getInstance();

    @Before
    public void setup() {
        // Test applications don't have the required permissions to read config P/H flags, and
        // injecting mocked flags everywhere is annoying and non-trivial for static methods
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .mockStatic(BackgroundFetchJobService.class)
                        .strictness(Strictness.WARN)
                        .initMocks(this)
                        .startMocking();

        ExtendedMockito.doReturn(FlagsFactory.getFlagsForTest()).when(FlagsFactory::getFlags);

        mCustomAudienceDao =
                Room.inMemoryDatabaseBuilder(CONTEXT, CustomAudienceDatabase.class)
                        .addTypeConverter(new DBCustomAudience.Converters(true))
                        .build()
                        .customAudienceDao();

        CustomAudienceQuantityChecker customAudienceQuantityChecker =
                new CustomAudienceQuantityChecker(mCustomAudienceDao, CommonFixture.FLAGS_FOR_TEST);

        CustomAudienceValidator customAudienceValidator =
                new CustomAudienceValidator(
                        CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI, CommonFixture.FLAGS_FOR_TEST);

        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        new CustomAudienceImpl(
                                mCustomAudienceDao,
                                customAudienceQuantityChecker,
                                customAudienceValidator,
                                CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI,
                                CommonFixture.FLAGS_FOR_TEST),
                        new FledgeAuthorizationFilter(
                                CONTEXT.getPackageManager(),
                                EnrollmentDao.getInstance(CONTEXT),
                                mAdServicesLogger),
                        mConsentManagerMock,
                        mDevContextFilter,
                        MoreExecutors.newDirectExecutorService(),
                        mAdServicesLogger,
                        mAppImportanceFilter,
                        CommonFixture.FLAGS_FOR_TEST,
                        CallingAppUidSupplierProcessImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                CommonFixture.FLAGS_FOR_TEST,
                                mAppImportanceFilter,
                                new FledgeAuthorizationFilter(
                                        CONTEXT.getPackageManager(),
                                        EnrollmentDao.getInstance(CONTEXT),
                                        mAdServicesLogger),
                                new FledgeAllowListsFilter(
                                        CommonFixture.FLAGS_FOR_TEST, mAdServicesLogger),
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
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testJoinCustomAudience_notInBinderThread_fail() {
        CustomAudienceQuantityChecker customAudienceQuantityChecker =
                new CustomAudienceQuantityChecker(mCustomAudienceDao, CommonFixture.FLAGS_FOR_TEST);

        CustomAudienceValidator customAudienceValidator =
                new CustomAudienceValidator(
                        CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI, CommonFixture.FLAGS_FOR_TEST);

        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        new CustomAudienceImpl(
                                mCustomAudienceDao,
                                customAudienceQuantityChecker,
                                customAudienceValidator,
                                CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI,
                                CommonFixture.FLAGS_FOR_TEST),
                        new FledgeAuthorizationFilter(
                                CONTEXT.getPackageManager(),
                                EnrollmentDao.getInstance(CONTEXT),
                                mAdServicesLogger),
                        mConsentManagerMock,
                        mDevContextFilter,
                        MoreExecutors.newDirectExecutorService(),
                        mAdServicesLogger,
                        mAppImportanceFilter,
                        CommonFixture.FLAGS_FOR_TEST,
                        CallingAppUidSupplierFailureImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                CommonFixture.FLAGS_FOR_TEST,
                                mAppImportanceFilter,
                                new FledgeAuthorizationFilter(
                                        CONTEXT.getPackageManager(),
                                        EnrollmentDao.getInstance(CONTEXT),
                                        mAdServicesLogger),
                                new FledgeAllowListsFilter(
                                        CommonFixture.FLAGS_FOR_TEST, mAdServicesLogger),
                                mThrottlerSupplier));

        ResultCapturingCallback callback = new ResultCapturingCallback();
        assertThrows(
                IllegalStateException.class,
                () ->
                        mService.joinCustomAudience(
                                CUSTOM_AUDIENCE_PK1_1,
                                CustomAudienceFixture.VALID_OWNER,
                                callback));
        assertNull(
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));
    }

    @Test
    public void testJoinCustomAudience_callerPackageNameMismatch_fail() {
        ResultCapturingCallback callback = new ResultCapturingCallback();
        mService.joinCustomAudience(
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1).build(),
                "other_owner",
                callback);

        assertFalse(callback.isSuccess());
        assertTrue(callback.getException() instanceof SecurityException);
        assertEquals(
                AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ON_BEHALF_ERROR_MESSAGE,
                callback.getException().getMessage());
        assertNull(
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));
    }

    @Test
    public void testJoinCustomAudience_joinTwice_secondJoinOverrideValues() {
        doReturn(CommonFixture.FLAGS_FOR_TEST).when(FlagsFactory::getFlags);
        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        ResultCapturingCallback callback = new ResultCapturingCallback();
        mService.joinCustomAudience(
                CUSTOM_AUDIENCE_PK1_1, CustomAudienceFixture.VALID_OWNER, callback);
        assertTrue(callback.isSuccess());
        assertEquals(
                DB_CUSTOM_AUDIENCE_PK1_1,
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));

        callback = new ResultCapturingCallback();
        mService.joinCustomAudience(
                CUSTOM_AUDIENCE_PK1_2, CustomAudienceFixture.VALID_OWNER, callback);
        assertTrue(callback.isSuccess());
        assertEquals(
                DB_CUSTOM_AUDIENCE_PK1_2,
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)), times(2));
    }

    @Test
    public void testJoinCustomAudienceWithRevokedUserConsentForAppSuccess() {
        doReturn(true)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        ResultCapturingCallback callback = new ResultCapturingCallback();
        mService.joinCustomAudience(
                CUSTOM_AUDIENCE_PK1_1, CustomAudienceFixture.VALID_OWNER, callback);
        assertTrue(callback.isSuccess());
        assertNull(
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CUSTOM_AUDIENCE_PK1_1.getBuyer(),
                        CUSTOM_AUDIENCE_PK1_1.getName()));
    }

    @Test
    public void testJoinCustomAudience_beyondMaxExpirationTime_fail() {
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        ResultCapturingCallback callback = new ResultCapturingCallback();
        mService.joinCustomAudience(
                CUSTOM_AUDIENCE_PK1_BEYOND_MAX_EXPIRATION_TIME,
                CustomAudienceFixture.VALID_OWNER,
                callback);
        assertFalse(callback.isSuccess());
        assertTrue(callback.getException() instanceof IllegalArgumentException);
        assertNull(
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));
    }

    @Test
    public void testLeaveCustomAudience_notInBinderThread_fail() {
        CustomAudienceQuantityChecker customAudienceQuantityChecker =
                new CustomAudienceQuantityChecker(mCustomAudienceDao, CommonFixture.FLAGS_FOR_TEST);

        CustomAudienceValidator customAudienceValidator =
                new CustomAudienceValidator(
                        CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI, CommonFixture.FLAGS_FOR_TEST);

        mService =
                new CustomAudienceServiceImpl(
                        CONTEXT,
                        new CustomAudienceImpl(
                                mCustomAudienceDao,
                                customAudienceQuantityChecker,
                                customAudienceValidator,
                                CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI,
                                CommonFixture.FLAGS_FOR_TEST),
                        new FledgeAuthorizationFilter(
                                CONTEXT.getPackageManager(),
                                EnrollmentDao.getInstance(CONTEXT),
                                mAdServicesLogger),
                        mConsentManagerMock,
                        mDevContextFilter,
                        MoreExecutors.newDirectExecutorService(),
                        mAdServicesLogger,
                        mAppImportanceFilter,
                        CommonFixture.FLAGS_FOR_TEST,
                        CallingAppUidSupplierFailureImpl.create(),
                        new CustomAudienceServiceFilter(
                                CONTEXT,
                                mConsentManagerMock,
                                CommonFixture.FLAGS_FOR_TEST,
                                mAppImportanceFilter,
                                new FledgeAuthorizationFilter(
                                        CONTEXT.getPackageManager(),
                                        EnrollmentDao.getInstance(CONTEXT),
                                        mAdServicesLogger),
                                new FledgeAllowListsFilter(
                                        CommonFixture.FLAGS_FOR_TEST, mAdServicesLogger),
                                mThrottlerSupplier));

        ResultCapturingCallback callback = new ResultCapturingCallback();
        assertThrows(
                IllegalStateException.class,
                () ->
                        mService.leaveCustomAudience(
                                CustomAudienceFixture.VALID_OWNER,
                                CommonFixture.VALID_BUYER_1,
                                CustomAudienceFixture.VALID_NAME,
                                callback));
    }

    @Test
    public void testLeaveCustomAudience_callerPackageNameMismatch_fail() {
        ResultCapturingCallback callback = new ResultCapturingCallback();
        mService.leaveCustomAudience(
                "other_owner",
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                callback);

        assertFalse(callback.isSuccess());
        assertTrue(callback.getException() instanceof SecurityException);
        assertEquals(
                AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ON_BEHALF_ERROR_MESSAGE,
                callback.getException().getMessage());
    }

    @Test
    public void testLeaveCustomAudience_leaveJoinedCustomAudience() {
        doReturn(CommonFixture.FLAGS_FOR_TEST).when(FlagsFactory::getFlags);
        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        ResultCapturingCallback callback = new ResultCapturingCallback();
        mService.joinCustomAudience(
                CUSTOM_AUDIENCE_PK1_1, CustomAudienceFixture.VALID_OWNER, callback);
        assertTrue(callback.isSuccess());
        assertEquals(
                DB_CUSTOM_AUDIENCE_PK1_1,
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));

        callback = new ResultCapturingCallback();
        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                callback);
        assertTrue(callback.isSuccess());
        assertNull(
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)));
    }

    @Test
    public void testLeaveCustomAudience_leaveJoinedCustomAudienceFilersDisabled() {
        doReturn(
                        // CHECKSTYLE:OFF IndentationCheck
                        new Flags() {
                            @Override
                            public boolean getFledgeAdSelectionFilteringEnabled() {
                                return false;
                            }
                        })
                .when(FlagsFactory::getFlags);
        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        ResultCapturingCallback callback = new ResultCapturingCallback();
        mService.joinCustomAudience(
                CUSTOM_AUDIENCE_PK1_1, CustomAudienceFixture.VALID_OWNER, callback);
        assertTrue(callback.isSuccess());
        assertEquals(
                DB_CUSTOM_AUDIENCE_PK1_1,
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));

        callback = new ResultCapturingCallback();
        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                CustomAudienceFixture.VALID_NAME,
                callback);
        assertTrue(callback.isSuccess());
        assertNull(
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));

        verify(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), eq(false)));
    }

    @Test
    public void testLeaveCustomAudienceWithRevokedUserConsentForAppSuccess() {
        doReturn(CommonFixture.FLAGS_FOR_TEST).when(FlagsFactory::getFlags);
        doReturn(true).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        ResultCapturingCallback callback = new ResultCapturingCallback();
        mService.joinCustomAudience(
                CUSTOM_AUDIENCE_PK1_1, CustomAudienceFixture.VALID_OWNER, callback);
        assertTrue(callback.isSuccess());
        assertEquals(
                DB_CUSTOM_AUDIENCE_PK1_1,
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CUSTOM_AUDIENCE_PK1_1.getBuyer(),
                        CUSTOM_AUDIENCE_PK1_1.getName()));

        callback = new ResultCapturingCallback();
        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CUSTOM_AUDIENCE_PK1_1.getBuyer(),
                CUSTOM_AUDIENCE_PK1_1.getName(),
                callback);
        assertTrue(callback.isSuccess());
        assertEquals(
                DB_CUSTOM_AUDIENCE_PK1_1,
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CUSTOM_AUDIENCE_PK1_1.getBuyer(),
                        CUSTOM_AUDIENCE_PK1_1.getName()));
    }

    @Test
    public void testLeaveCustomAudience_leaveNotJoinedCustomAudience_doesNotFail() {
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        ResultCapturingCallback callback = new ResultCapturingCallback();
        mService.leaveCustomAudience(
                CustomAudienceFixture.VALID_OWNER,
                CommonFixture.VALID_BUYER_1,
                "Not exist name",
                callback);
        assertTrue(callback.isSuccess());
        assertNull(
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));
    }

    @Test
    public void testOverrideCustomAudienceRemoteInfoSuccess() throws Exception {
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(MY_APP_PACKAGE_NAME)
                                .build());
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        CustomAudienceOverrideTestCallback callback =
                callAddOverride(
                        MY_APP_PACKAGE_NAME,
                        BUYER_1,
                        NAME_1,
                        BIDDING_LOGIC_JS,
                        JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3,
                        TRUSTED_BIDDING_DATA,
                        mService);

        assertTrue(callback.mIsSuccess);
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
    }

    @Test
    public void testOverrideCustomAudienceRemoteInfoWithRevokedUserConsentSuccess()
            throws Exception {
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(MY_APP_PACKAGE_NAME)
                                .build());
        doReturn(true).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        CustomAudienceOverrideTestCallback callback =
                callAddOverride(
                        MY_APP_PACKAGE_NAME,
                        BUYER_1,
                        NAME_1,
                        BIDDING_LOGIC_JS,
                        JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3,
                        TRUSTED_BIDDING_DATA,
                        mService);

        assertTrue(callback.mIsSuccess);
        assertFalse(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
    }

    @Test
    public void testOverrideCustomAudienceRemoteInfoDoesNotAddOverrideWithPackageNameNotMatchOwner()
            throws Exception {
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(MY_APP_PACKAGE_NAME)
                                .build());
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        String otherOwner = "otherOwner";

        CustomAudienceOverrideTestCallback callback =
                callAddOverride(
                        otherOwner,
                        BUYER_1,
                        NAME_1,
                        BIDDING_LOGIC_JS,
                        JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3,
                        TRUSTED_BIDDING_DATA,
                        mService);

        assertTrue(callback.mIsSuccess);
        assertFalse(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(otherOwner, BUYER_1, NAME_1));
    }

    @Test
    public void testOverrideCustomAudienceRemoteInfoFailsWithDevOptionsDisabled() throws Exception {
        when(mDevContextFilter.createDevContext())
                .thenReturn(DevContext.createForDevOptionsDisabled());

        assertThrows(
                SecurityException.class,
                () ->
                        callAddOverride(
                                MY_APP_PACKAGE_NAME,
                                BUYER_1,
                                NAME_1,
                                BIDDING_LOGIC_JS,
                                JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3,
                                TRUSTED_BIDDING_DATA,
                                mService));

        assertFalse(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
    }

    @Test
    public void testRemoveCustomAudienceRemoteInfoOverrideSuccess() throws Exception {
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(MY_APP_PACKAGE_NAME)
                                .build());
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        DBCustomAudienceOverride dbCustomAudienceOverride =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_1)
                        .setName(NAME_1)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride);
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));

        CustomAudienceOverrideTestCallback callback =
                callRemoveOverride(MY_APP_PACKAGE_NAME, BUYER_1, NAME_1, mService);

        assertTrue(callback.mIsSuccess);
        assertFalse(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
    }

    @Test
    public void testRemoveCustomAudienceRemoteInfoOverrideWithRevokedUserConsentSuccess()
            throws Exception {
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(MY_APP_PACKAGE_NAME)
                                .build());
        doReturn(true).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        DBCustomAudienceOverride dbCustomAudienceOverride =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_1)
                        .setName(NAME_1)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride);
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));

        CustomAudienceOverrideTestCallback callback =
                callRemoveOverride(MY_APP_PACKAGE_NAME, BUYER_1, NAME_1, mService);

        assertTrue(callback.mIsSuccess);
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
    }

    @Test
    public void testRemoveCustomAudienceRemoteInfoOverrideDoesNotDeleteWithIncorrectPackageName()
            throws Exception {
        String incorrectPackageName = "incorrectPackageName";

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(incorrectPackageName)
                                .build());
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        DBCustomAudienceOverride dbCustomAudienceOverride =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_1)
                        .setName(NAME_1)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride);
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));

        CustomAudienceOverrideTestCallback callback =
                callRemoveOverride(MY_APP_PACKAGE_NAME, BUYER_1, NAME_1, mService);

        assertTrue(callback.mIsSuccess);
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
    }

    @Test
    public void testRemoveCustomAudienceRemoteInfoOverrideFailsWithDevOptionsDisabled()
            throws Exception {
        when(mDevContextFilter.createDevContext())
                .thenReturn(DevContext.createForDevOptionsDisabled());

        DBCustomAudienceOverride dbCustomAudienceOverride =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_1)
                        .setName(NAME_1)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride);
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));

        assertThrows(
                SecurityException.class,
                () -> callRemoveOverride(MY_APP_PACKAGE_NAME, BUYER_1, NAME_1, mService));

        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
    }

    @Test
    public void testResetAllCustomAudienceRemoteOverridesSuccess() throws Exception {
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(MY_APP_PACKAGE_NAME)
                                .build());
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        DBCustomAudienceOverride dbCustomAudienceOverride1 =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_1)
                        .setName(NAME_1)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        DBCustomAudienceOverride dbCustomAudienceOverride2 =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_2)
                        .setName(NAME_2)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride1);
        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride2);

        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));

        CustomAudienceOverrideTestCallback callback = callResetAllOverrides(mService);

        assertTrue(callback.mIsSuccess);
        assertFalse(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
        assertFalse(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));
    }

    @Test
    public void testResetAllCustomAudienceRemoteOverridesWithRevokedUserConsentSuccess()
            throws Exception {
        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(MY_APP_PACKAGE_NAME)
                                .build());
        doReturn(true).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        DBCustomAudienceOverride dbCustomAudienceOverride1 =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_1)
                        .setName(NAME_1)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        DBCustomAudienceOverride dbCustomAudienceOverride2 =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_2)
                        .setName(NAME_2)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride1);
        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride2);

        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));

        CustomAudienceOverrideTestCallback callback = callResetAllOverrides(mService);

        assertTrue(callback.mIsSuccess);
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));
    }

    @Test
    public void testResetAllCustomAudienceRemoteOverridesDoesNotDeleteWithIncorrectPackageName()
            throws Exception {
        String incorrectPackageName = "incorrectPackageName";

        when(mDevContextFilter.createDevContext())
                .thenReturn(
                        DevContext.builder()
                                .setDevOptionsEnabled(true)
                                .setCallingAppPackageName(incorrectPackageName)
                                .build());
        doReturn(false).when(mConsentManagerMock).isFledgeConsentRevokedForApp(any());

        DBCustomAudienceOverride dbCustomAudienceOverride1 =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_1)
                        .setName(NAME_1)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        DBCustomAudienceOverride dbCustomAudienceOverride2 =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_2)
                        .setName(NAME_2)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride1);
        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride2);

        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));

        CustomAudienceOverrideTestCallback callback = callResetAllOverrides(mService);

        assertTrue(callback.mIsSuccess);
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));
    }

    @Test
    public void testResetAllCustomAudienceRemoteOverridesFailsWithDevOptionsDisabled() {
        when(mDevContextFilter.createDevContext())
                .thenReturn(DevContext.createForDevOptionsDisabled());

        DBCustomAudienceOverride dbCustomAudienceOverride1 =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_1)
                        .setName(NAME_1)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        DBCustomAudienceOverride dbCustomAudienceOverride2 =
                DBCustomAudienceOverride.builder()
                        .setOwner(MY_APP_PACKAGE_NAME)
                        .setBuyer(BUYER_2)
                        .setName(NAME_2)
                        .setBiddingLogicJS(BIDDING_LOGIC_JS)
                        .setTrustedBiddingData(TRUSTED_BIDDING_DATA.toString())
                        .setAppPackageName(MY_APP_PACKAGE_NAME)
                        .build();

        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride1);
        mCustomAudienceDao.persistCustomAudienceOverride(dbCustomAudienceOverride2);

        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));

        assertThrows(SecurityException.class, () -> callResetAllOverrides(mService));

        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_1, NAME_1));
        assertTrue(
                mCustomAudienceDao.doesCustomAudienceOverrideExist(
                        MY_APP_PACKAGE_NAME, BUYER_2, NAME_2));
    }

    @Test
    public void testCustomAudience_throttledSubsequentCallFails() {
        doReturn(CommonFixture.FLAGS_FOR_TEST).when(FlagsFactory::getFlags);
        doNothing()
                .when(() -> BackgroundFetchJobService.scheduleIfNeeded(any(), any(), anyBoolean()));
        doReturn(false)
                .when(mConsentManagerMock)
                .isFledgeConsentRevokedForAppAfterSettingFledgeUse(any());

        CustomAudienceQuantityChecker customAudienceQuantityChecker =
                new CustomAudienceQuantityChecker(mCustomAudienceDao, CommonFixture.FLAGS_FOR_TEST);

        CustomAudienceValidator customAudienceValidator =
                new CustomAudienceValidator(
                        CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI, CommonFixture.FLAGS_FOR_TEST);
        Throttler.destroyExistingThrottler();
        CustomAudienceServiceImpl customAudienceService =
                mService =
                        new CustomAudienceServiceImpl(
                                CONTEXT,
                                new CustomAudienceImpl(
                                        mCustomAudienceDao,
                                        customAudienceQuantityChecker,
                                        customAudienceValidator,
                                        CommonFixture.FIXED_CLOCK_TRUNCATED_TO_MILLI,
                                        CommonFixture.FLAGS_FOR_TEST),
                                new FledgeAuthorizationFilter(
                                        CONTEXT.getPackageManager(),
                                        EnrollmentDao.getInstance(CONTEXT),
                                        mAdServicesLogger),
                                mConsentManagerMock,
                                mDevContextFilter,
                                MoreExecutors.newDirectExecutorService(),
                                mAdServicesLogger,
                                mAppImportanceFilter,
                                CommonFixture.FLAGS_FOR_TEST,
                                CallingAppUidSupplierProcessImpl.create(),
                                new CustomAudienceServiceFilter(
                                        CONTEXT,
                                        mConsentManagerMock,
                                        CommonFixture.FLAGS_FOR_TEST,
                                        mAppImportanceFilter,
                                        new FledgeAuthorizationFilter(
                                                CONTEXT.getPackageManager(),
                                                EnrollmentDao.getInstance(CONTEXT),
                                                mAdServicesLogger),
                                        new FledgeAllowListsFilter(
                                                CommonFixture.FLAGS_FOR_TEST, mAdServicesLogger),
                                        () -> Throttler.getInstance(CommonFixture.FLAGS_FOR_TEST)));

        // The first call should succeed
        ResultCapturingCallback callbackFirstCall = new ResultCapturingCallback();
        customAudienceService.joinCustomAudience(
                CUSTOM_AUDIENCE_PK1_1, CustomAudienceFixture.VALID_OWNER, callbackFirstCall);

        // The immediate subsequent call should be throttled
        ResultCapturingCallback callbackSubsequentCall = new ResultCapturingCallback();
        customAudienceService.joinCustomAudience(
                CUSTOM_AUDIENCE_PK1_1, CustomAudienceFixture.VALID_OWNER, callbackSubsequentCall);

        assertTrue(callbackFirstCall.isSuccess());
        assertEquals(
                DB_CUSTOM_AUDIENCE_PK1_1,
                mCustomAudienceDao.getCustomAudienceByPrimaryKey(
                        CustomAudienceFixture.VALID_OWNER,
                        CommonFixture.VALID_BUYER_1,
                        CustomAudienceFixture.VALID_NAME));

        assertFalse(callbackSubsequentCall.isSuccess());
        assertTrue(callbackSubsequentCall.getException() instanceof LimitExceededException);
        assertEquals(
                AdServicesStatusUtils.RATE_LIMIT_REACHED_ERROR_MESSAGE,
                callbackSubsequentCall.getException().getMessage());
        resetThrottlerToNoRateLimits();
    }

    /**
     * Given Throttler is singleton, & shared across tests, this method should be invoked after
     * tests that impose restrictive rate limits.
     */
    private void resetThrottlerToNoRateLimits() {
        Throttler.destroyExistingThrottler();
        final float noRateLimit = -1;
        Flags mockNoRateLimitFlags = mock(Flags.class);
        doReturn(noRateLimit).when(mockNoRateLimitFlags).getSdkRequestPermitsPerSecond();
        Throttler.getInstance(mockNoRateLimitFlags);
    }

    private CustomAudienceOverrideTestCallback callAddOverride(
            String owner,
            AdTechIdentifier buyer,
            String name,
            String biddingLogicJs,
            Long biddingLogicJsVersion,
            AdSelectionSignals trustedBiddingData,
            CustomAudienceServiceImpl customAudienceService)
            throws Exception {
        CountDownLatch resultLatch = new CountDownLatch(1);
        CustomAudienceOverrideTestCallback callback =
                new CustomAudienceOverrideTestCallback(resultLatch);

        customAudienceService.overrideCustomAudienceRemoteInfo(
                owner,
                buyer,
                name,
                biddingLogicJs,
                biddingLogicJsVersion,
                trustedBiddingData,
                callback);
        resultLatch.await();
        return callback;
    }

    private CustomAudienceOverrideTestCallback callRemoveOverride(
            String owner,
            AdTechIdentifier buyer,
            String name,
            CustomAudienceServiceImpl customAudienceService)
            throws Exception {
        CountDownLatch resultLatch = new CountDownLatch(1);
        CustomAudienceOverrideTestCallback callback =
                new CustomAudienceOverrideTestCallback(resultLatch);

        customAudienceService.removeCustomAudienceRemoteInfoOverride(owner, buyer, name, callback);

        resultLatch.await();
        return callback;
    }

    private CustomAudienceOverrideTestCallback callResetAllOverrides(
            CustomAudienceServiceImpl customAudienceService) throws Exception {
        CountDownLatch resultLatch = new CountDownLatch(1);
        CustomAudienceOverrideTestCallback callback =
                new CustomAudienceOverrideTestCallback(resultLatch);

        customAudienceService.resetAllCustomAudienceOverrides(callback);
        resultLatch.await();
        return callback;
    }

    public static class CustomAudienceOverrideTestCallback
            extends CustomAudienceOverrideCallback.Stub {
        boolean mIsSuccess = false;
        FledgeErrorResponse mFledgeErrorResponse;
        private final CountDownLatch mCountDownLatch;

        public CustomAudienceOverrideTestCallback(CountDownLatch countDownLatch) {
            mCountDownLatch = countDownLatch;
        }

        @Override
        public void onSuccess() throws RemoteException {
            mIsSuccess = true;
            mCountDownLatch.countDown();
        }

        @Override
        public void onFailure(FledgeErrorResponse fledgeErrorResponse) throws RemoteException {
            mFledgeErrorResponse = fledgeErrorResponse;
            mCountDownLatch.countDown();
        }
    }

    private static class ResultCapturingCallback implements ICustomAudienceCallback {
        private boolean mIsSuccess;
        private Exception mException;

        public boolean isSuccess() {
            return mIsSuccess;
        }

        public Exception getException() {
            return mException;
        }

        @Override
        public void onSuccess() throws RemoteException {
            mIsSuccess = true;
        }

        @Override
        public void onFailure(FledgeErrorResponse responseParcel) throws RemoteException {
            mIsSuccess = false;
            mException = AdServicesStatusUtils.asException(responseParcel);
        }

        @Override
        public IBinder asBinder() {
            throw new RuntimeException("Should not be called.");
        }
    }
}
