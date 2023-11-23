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

package com.android.adservices.service.common;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doThrow;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.adservices.common.AdTechIdentifier;
import android.adservices.common.CommonFixture;
import android.content.Context;
import android.os.LimitExceededException;
import android.os.Process;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.DbTestUtil;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.exception.FilterException;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.quality.Strictness;

import java.util.function.Supplier;

public class AdSelectionServiceFilterTest {
    private static final String CALLER_PACKAGE_NAME = CommonFixture.TEST_PACKAGE_NAME;
    @Spy private Context mContext = ApplicationProvider.getApplicationContext();
    private static final Flags TEST_FLAGS = FlagsFactory.getFlagsForTest();
    private static final Flags FLAGS_WITH_ENROLLMENT_CHECK =
            new Flags() {
                @Override
                public boolean getDisableFledgeEnrollmentCheck() {
                    return false;
                }
            };
    private static final Flags FLAGS_WITH_GA_UX_ENABLED =
            new Flags() {
                @Override
                public boolean getGaUxFeatureEnabled() {
                    return true;
                }

                @Override
                public boolean getDisableFledgeEnrollmentCheck() {
                    return true;
                }
            };
    @Mock AppImportanceFilter mAppImportanceFilter;

    private final AdServicesLogger mAdServicesLoggerMock =
            ExtendedMockito.mock(AdServicesLoggerImpl.class);

    @Spy
    FledgeAllowListsFilter mFledgeAllowListsFilterSpy =
            new FledgeAllowListsFilter(TEST_FLAGS, mAdServicesLoggerMock);

    @Mock private ConsentManager mConsentManagerMock;

    @Spy
    FledgeAuthorizationFilter mFledgeAuthorizationFilterSpy =
            new FledgeAuthorizationFilter(
                    mContext.getPackageManager(),
                    new EnrollmentDao(mContext, DbTestUtil.getSharedDbHelperForTest(), TEST_FLAGS),
                    mAdServicesLoggerMock);

    @Mock private Throttler mMockThrottler;

    private final Supplier<Throttler> mThrottlerSupplier = () -> mMockThrottler;

    private MockitoSession mStaticMockSession = null;

    private AdSelectionServiceFilter mAdSelectionServiceFilter;

    private static final AdTechIdentifier SELLER_VALID =
            AdTechIdentifier.fromString("developer.android.com");

    private static final int API_NAME = 0;

    private static final int MY_UID = Process.myUid();

    @Before
    public void setUp() throws Exception {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .initMocks(this)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        mAdSelectionServiceFilter =
                new AdSelectionServiceFilter(
                        mContext,
                        mConsentManagerMock,
                        TEST_FLAGS,
                        mAppImportanceFilter,
                        mFledgeAuthorizationFilterSpy,
                        mFledgeAllowListsFilterSpy,
                        mThrottlerSupplier);
        when(mMockThrottler.tryAcquire(eq(Throttler.ApiKey.UNKNOWN), anyString())).thenReturn(true);
    }

    @After
    public void tearDown() {
        if (mStaticMockSession != null) {
            mStaticMockSession.finishMocking();
        }
    }

    @Test
    public void testFilterRequestSucceedsGaUxDisabled() {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        mAdSelectionServiceFilter.filterRequest(
                SELLER_VALID,
                CALLER_PACKAGE_NAME,
                false,
                true,
                MY_UID,
                API_NAME,
                Throttler.ApiKey.UNKNOWN);
    }

    @Test
    public void testFilterRequestSucceedsGaUxEnabled() {
        mAdSelectionServiceFilter =
                new AdSelectionServiceFilter(
                        mContext,
                        mConsentManagerMock,
                        FLAGS_WITH_GA_UX_ENABLED,
                        mAppImportanceFilter,
                        mFledgeAuthorizationFilterSpy,
                        mFledgeAllowListsFilterSpy,
                        mThrottlerSupplier);
        doReturn(AdServicesApiConsent.GIVEN)
                .when(mConsentManagerMock)
                .getConsent(AdServicesApiType.FLEDGE);
        mAdSelectionServiceFilter.filterRequest(
                SELLER_VALID,
                CALLER_PACKAGE_NAME,
                false,
                true,
                MY_UID,
                API_NAME,
                Throttler.ApiKey.UNKNOWN);
    }

    @Test
    public void testFilterRequestThrowsCallerMismatchExceptionWithInvalidPackageName() {
        FilterException exception =
                assertThrows(
                        FilterException.class,
                        () ->
                                mAdSelectionServiceFilter.filterRequest(
                                        SELLER_VALID,
                                        "invalidPackageName",
                                        false,
                                        true,
                                        MY_UID,
                                        API_NAME,
                                        Throttler.ApiKey.UNKNOWN));
        assertThat(exception.getCause())
                .isInstanceOf(FledgeAuthorizationFilter.CallerMismatchException.class);
    }

    @Test
    public void testFilterRequestThrowsLimitExceededExceptionIfThrottled() {
        when(mMockThrottler.tryAcquire(eq(Throttler.ApiKey.UNKNOWN), anyString()))
                .thenReturn(false);
        FilterException exception =
                assertThrows(
                        FilterException.class,
                        () ->
                                mAdSelectionServiceFilter.filterRequest(
                                        SELLER_VALID,
                                        CALLER_PACKAGE_NAME,
                                        false,
                                        true,
                                        MY_UID,
                                        API_NAME,
                                        Throttler.ApiKey.UNKNOWN));
        assertThat(exception.getCause()).isInstanceOf(LimitExceededException.class);
    }

    @Test
    public void
            testFilterRequestThrowsWrongCallingApplicationStateExceptionIfForegroundCheckFails() {
        doThrow(new AppImportanceFilter.WrongCallingApplicationStateException())
                .when(mAppImportanceFilter)
                .assertCallerIsInForeground(Process.myUid(), API_NAME, null);
        FilterException exception =
                assertThrows(
                        FilterException.class,
                        () ->
                                mAdSelectionServiceFilter.filterRequest(
                                        SELLER_VALID,
                                        CALLER_PACKAGE_NAME,
                                        true,
                                        true,
                                        MY_UID,
                                        API_NAME,
                                        Throttler.ApiKey.UNKNOWN));
        assertThat(exception.getCause())
                .isInstanceOf(AppImportanceFilter.WrongCallingApplicationStateException.class);
    }

    @Test
    public void testFilterRequestSucceedsForBackgroundAppsWhenEnforceForegroundFalse() {
        doThrow(new AppImportanceFilter.WrongCallingApplicationStateException())
                .when(mAppImportanceFilter)
                .assertCallerIsInForeground(Process.myUid(), API_NAME, null);
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();
        mAdSelectionServiceFilter.filterRequest(
                SELLER_VALID,
                CALLER_PACKAGE_NAME,
                false,
                true,
                MY_UID,
                API_NAME,
                Throttler.ApiKey.UNKNOWN);
    }

    @Test
    public void testFilterRequestThrowsAdTechNotAllowedExceptionWhenAdTechNotAuthorized() {

        // Create new AdSelectionServiceFilter with new flags
        mAdSelectionServiceFilter =
                new AdSelectionServiceFilter(
                        mContext,
                        mConsentManagerMock,
                        FLAGS_WITH_ENROLLMENT_CHECK,
                        mAppImportanceFilter,
                        mFledgeAuthorizationFilterSpy,
                        mFledgeAllowListsFilterSpy,
                        mThrottlerSupplier);

        doThrow(new FledgeAuthorizationFilter.AdTechNotAllowedException())
                .when(mFledgeAuthorizationFilterSpy)
                .assertAdTechAllowed(mContext, CALLER_PACKAGE_NAME, SELLER_VALID, API_NAME);
        FilterException exception =
                assertThrows(
                        FilterException.class,
                        () ->
                                mAdSelectionServiceFilter.filterRequest(
                                        SELLER_VALID,
                                        CALLER_PACKAGE_NAME,
                                        false,
                                        true,
                                        MY_UID,
                                        API_NAME,
                                        Throttler.ApiKey.UNKNOWN));
        assertThat(exception.getCause())
                .isInstanceOf(FledgeAuthorizationFilter.AdTechNotAllowedException.class);
    }

    @Test
    public void testFilterRequestThrowsAppNotAllowedExceptionWhenAppNotInAllowlist() {
        doThrow(new FledgeAllowListsFilter.AppNotAllowedException())
                .when(mFledgeAllowListsFilterSpy)
                .assertAppCanUsePpapi(CALLER_PACKAGE_NAME, API_NAME);
        FilterException exception =
                assertThrows(
                        FilterException.class,
                        () ->
                                mAdSelectionServiceFilter.filterRequest(
                                        SELLER_VALID,
                                        CALLER_PACKAGE_NAME,
                                        false,
                                        true,
                                        MY_UID,
                                        API_NAME,
                                        Throttler.ApiKey.UNKNOWN));
        assertThat(exception.getCause())
                .isInstanceOf(FledgeAllowListsFilter.AppNotAllowedException.class);
    }

    @Test
    public void testFilterRequestThrowsRevokedConsentExceptionAppDoesNotHaveConsentGaUxDisabled() {
        doReturn(AdServicesApiConsent.REVOKED).when(mConsentManagerMock).getConsent();
        FilterException exception =
                assertThrows(
                        FilterException.class,
                        () ->
                                mAdSelectionServiceFilter.filterRequest(
                                        SELLER_VALID,
                                        CALLER_PACKAGE_NAME,
                                        false,
                                        true,
                                        MY_UID,
                                        API_NAME,
                                        Throttler.ApiKey.UNKNOWN));
        assertThat(exception.getCause()).isInstanceOf(ConsentManager.RevokedConsentException.class);
    }

    @Test
    public void testFilterRequestSucceedsConsentRevokedEnforceConsentFalse() {
        doReturn(AdServicesApiConsent.REVOKED).when(mConsentManagerMock).getConsent();
        mAdSelectionServiceFilter.filterRequest(
                SELLER_VALID,
                CALLER_PACKAGE_NAME,
                false,
                false,
                MY_UID,
                API_NAME,
                Throttler.ApiKey.UNKNOWN);
        verifyNoMoreInteractions(mConsentManagerMock);
    }

    @Test
    public void testFilterRequestThrowsRevokedConsentExceptionAppDoesNotHaveConsentGaUxEnabled() {
        // Create new AdSelectionServiceFilter with new flags
        mAdSelectionServiceFilter =
                new AdSelectionServiceFilter(
                        mContext,
                        mConsentManagerMock,
                        FLAGS_WITH_GA_UX_ENABLED,
                        mAppImportanceFilter,
                        mFledgeAuthorizationFilterSpy,
                        mFledgeAllowListsFilterSpy,
                        mThrottlerSupplier);
        doReturn(AdServicesApiConsent.REVOKED)
                .when(mConsentManagerMock)
                .getConsent(AdServicesApiType.FLEDGE);
        FilterException exception =
                assertThrows(
                        FilterException.class,
                        () ->
                                mAdSelectionServiceFilter.filterRequest(
                                        SELLER_VALID,
                                        CALLER_PACKAGE_NAME,
                                        false,
                                        true,
                                        MY_UID,
                                        API_NAME,
                                        Throttler.ApiKey.UNKNOWN));
        assertThat(exception.getCause()).isInstanceOf(ConsentManager.RevokedConsentException.class);
    }

    @Test
    public void testFilterRequestDoesNotDoEnrollmentCheckWhenAdTechParamIsNull() {
        doReturn(AdServicesApiConsent.GIVEN).when(mConsentManagerMock).getConsent();

        mAdSelectionServiceFilter.filterRequest(
                null, CALLER_PACKAGE_NAME, false, true, MY_UID, API_NAME, Throttler.ApiKey.UNKNOWN);

        verify(mFledgeAuthorizationFilterSpy, never())
                .assertAdTechAllowed(any(), anyString(), any(), anyInt());
    }
}
