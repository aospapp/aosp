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

package com.android.adservices.service.common;

import static android.adservices.common.AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_PERMISSION_NOT_REQUESTED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_UNAUTHORIZED;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.anyInt;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.eq;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyNoMoreInteractions;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verifyZeroInteractions;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.CommonFixture;
import android.content.Context;
import android.content.pm.PackageManager;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.PhFlags;
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.adservices.service.stats.AdServicesStatsLog;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoSession;

public class FledgeAuthorizationFilterTest {
    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    private static final int UID = 111;
    private static final int API_NAME_LOGGING_ID =
            AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE;
    private static final String PACKAGE_NAME = "pkg_name";
    private static final String PACKAGE_NAME_OTHER = "other_pkg_name";
    private static final String ENROLLMENT_ID = "enroll_id";
    private static final EnrollmentData ENROLLMENT_DATA =
            new EnrollmentData.Builder().setEnrollmentId(ENROLLMENT_ID).build();

    @Mock private PackageManager mPackageManager;
    @Mock private EnrollmentDao mEnrollmentDao;
    @Mock private PhFlags mPhFlags;
    private final AdServicesLogger mAdServicesLoggerMock =
            ExtendedMockito.mock(AdServicesLoggerImpl.class);

    public MockitoSession mMockitoSession;

    private FledgeAuthorizationFilter mChecker;

    @Before
    public void setup() {
        mMockitoSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(PermissionHelper.class)
                        .mockStatic(AppManifestConfigHelper.class)
                        .mockStatic(PhFlags.class)
                        .initMocks(this)
                        .startMocking();
        mChecker =
                new FledgeAuthorizationFilter(
                        mPackageManager, mEnrollmentDao, mAdServicesLoggerMock);
    }

    @After
    public void teardown() {
        mMockitoSession.finishMocking();
    }

    @Test
    public void testAssertCallingPackageName_isCallingPackageName() {
        when(mPackageManager.getPackagesForUid(UID))
                .thenReturn(new String[] {PACKAGE_NAME, PACKAGE_NAME_OTHER});

        mChecker.assertCallingPackageName(PACKAGE_NAME, UID, API_NAME_LOGGING_ID);

        verify(mPackageManager).getPackagesForUid(UID);
        verifyNoMoreInteractions(mPackageManager);
        verifyZeroInteractions(mAdServicesLoggerMock, mEnrollmentDao);
    }

    @Test
    public void testAssertCallingPackageName_nullPackageName_throwNpe() {
        assertThrows(
                NullPointerException.class,
                () -> mChecker.assertCallingPackageName(null, UID, API_NAME_LOGGING_ID));

        verifyZeroInteractions(mPackageManager, mAdServicesLoggerMock, mEnrollmentDao);
    }

    @Test
    public void testAssertCallingPackageName_isNotCallingPackageName_throwSecurityException() {
        when(mPackageManager.getPackagesForUid(UID)).thenReturn(new String[] {PACKAGE_NAME_OTHER});

        SecurityException exception =
                assertThrows(
                        SecurityException.class,
                        () ->
                                mChecker.assertCallingPackageName(
                                        PACKAGE_NAME, UID, API_NAME_LOGGING_ID));

        assertEquals(
                AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ON_BEHALF_ERROR_MESSAGE,
                exception.getMessage());
        verify(mPackageManager).getPackagesForUid(UID);
        verify(mAdServicesLoggerMock)
                .logFledgeApiCallStats(eq(API_NAME_LOGGING_ID), eq(STATUS_UNAUTHORIZED), anyInt());
        verifyNoMoreInteractions(mPackageManager, mAdServicesLoggerMock, mEnrollmentDao);
    }

    @Test
    public void testAssertCallingPackageName_packageNotExist_throwSecurityException() {
        when(mPackageManager.getPackagesForUid(UID)).thenReturn(new String[] {});

        SecurityException exception =
                assertThrows(
                        SecurityException.class,
                        () ->
                                mChecker.assertCallingPackageName(
                                        PACKAGE_NAME, UID, API_NAME_LOGGING_ID));

        assertEquals(
                AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ON_BEHALF_ERROR_MESSAGE,
                exception.getMessage());
        verify(mPackageManager).getPackagesForUid(UID);
        verify(mAdServicesLoggerMock)
                .logFledgeApiCallStats(eq(API_NAME_LOGGING_ID), eq(STATUS_UNAUTHORIZED), anyInt());
        verifyNoMoreInteractions(mPackageManager, mEnrollmentDao, mAdServicesLoggerMock);
    }

    @Test
    public void testAssertAppHasPermission_appHasPermission() {
        when(PermissionHelper.hasCustomAudiencesPermission(CONTEXT)).thenReturn(true);

        mChecker.assertAppDeclaredPermission(CONTEXT, API_NAME_LOGGING_ID);

        verifyZeroInteractions(mPackageManager, mEnrollmentDao, mAdServicesLoggerMock);
    }

    @Test
    public void testAssertAppHasPermission_appDoesNotHavePermission_throwSecurityException() {
        when(PermissionHelper.hasCustomAudiencesPermission(CONTEXT)).thenReturn(false);

        SecurityException exception =
                assertThrows(
                        SecurityException.class,
                        () -> mChecker.assertAppDeclaredPermission(CONTEXT, API_NAME_LOGGING_ID));

        assertEquals(
                AdServicesStatusUtils.SECURITY_EXCEPTION_PERMISSION_NOT_REQUESTED_ERROR_MESSAGE,
                exception.getMessage());
        verify(mAdServicesLoggerMock)
                .logFledgeApiCallStats(
                        eq(API_NAME_LOGGING_ID), eq(STATUS_PERMISSION_NOT_REQUESTED), anyInt());
        verifyNoMoreInteractions(mAdServicesLoggerMock);
        verifyZeroInteractions(mPackageManager, mEnrollmentDao);
    }

    @Test
    public void testAssertAppHasPermission_nullContext_throwNpe() {
        assertThrows(
                NullPointerException.class,
                () -> mChecker.assertAppDeclaredPermission(null, API_NAME_LOGGING_ID));

        verifyZeroInteractions(mPackageManager, mEnrollmentDao, mAdServicesLoggerMock);
    }

    @Test
    public void testAssertAdTechHasPermission_hasPermission() {
        when(mEnrollmentDao.getEnrollmentDataForFledgeByAdTechIdentifier(
                        CommonFixture.VALID_BUYER_1))
                .thenReturn(ENROLLMENT_DATA);
        when(AppManifestConfigHelper.isAllowedCustomAudiencesAccess(
                        CONTEXT, PACKAGE_NAME, ENROLLMENT_ID))
                .thenReturn(true);
        when(PhFlags.getInstance()).thenReturn(mPhFlags);
        when(mPhFlags.isEnrollmentBlocklisted(ENROLLMENT_ID)).thenReturn(false);

        mChecker.assertAdTechAllowed(
                CONTEXT, PACKAGE_NAME, CommonFixture.VALID_BUYER_1, API_NAME_LOGGING_ID);
        verify(mEnrollmentDao)
                .getEnrollmentDataForFledgeByAdTechIdentifier(CommonFixture.VALID_BUYER_1);
        verifyNoMoreInteractions(mEnrollmentDao);
        verifyZeroInteractions(mPackageManager, mAdServicesLoggerMock);
    }

    @Test
    public void testAssertAdTechHasPermission_noEnrollmentForAdTech_throwSecurityException() {
        when(mEnrollmentDao.getEnrollmentDataForFledgeByAdTechIdentifier(
                        CommonFixture.VALID_BUYER_1))
                .thenReturn(null);

        SecurityException exception =
                assertThrows(
                        SecurityException.class,
                        () ->
                                mChecker.assertAdTechAllowed(
                                        CONTEXT,
                                        PACKAGE_NAME,
                                        CommonFixture.VALID_BUYER_1,
                                        API_NAME_LOGGING_ID));

        assertEquals(
                AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE,
                exception.getMessage());
        verify(mEnrollmentDao)
                .getEnrollmentDataForFledgeByAdTechIdentifier(CommonFixture.VALID_BUYER_1);
        verify(mAdServicesLoggerMock)
                .logFledgeApiCallStats(
                        eq(API_NAME_LOGGING_ID), eq(STATUS_CALLER_NOT_ALLOWED), anyInt());
        verifyNoMoreInteractions(mEnrollmentDao, mAdServicesLoggerMock);
        verifyZeroInteractions(mPackageManager);
    }

    @Test
    public void testAssertAdTechHasPermission_appManifestNoPermission_throwSecurityException() {
        when(mEnrollmentDao.getEnrollmentDataForFledgeByAdTechIdentifier(
                        CommonFixture.VALID_BUYER_1))
                .thenReturn(ENROLLMENT_DATA);
        when(AppManifestConfigHelper.isAllowedCustomAudiencesAccess(
                        CONTEXT, PACKAGE_NAME, ENROLLMENT_ID))
                .thenReturn(false);

        SecurityException exception =
                assertThrows(
                        SecurityException.class,
                        () ->
                                mChecker.assertAdTechAllowed(
                                        CONTEXT,
                                        PACKAGE_NAME,
                                        CommonFixture.VALID_BUYER_1,
                                        API_NAME_LOGGING_ID));

        assertEquals(
                AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE,
                exception.getMessage());
        verify(mEnrollmentDao)
                .getEnrollmentDataForFledgeByAdTechIdentifier(CommonFixture.VALID_BUYER_1);
        verify(mAdServicesLoggerMock)
                .logFledgeApiCallStats(
                        eq(API_NAME_LOGGING_ID), eq(STATUS_CALLER_NOT_ALLOWED), anyInt());
        verifyNoMoreInteractions(mEnrollmentDao, mAdServicesLoggerMock);
        verifyZeroInteractions(mPackageManager);
    }

    @Test
    public void testAdTechInBlocklist_throwSecurityException() {
        when(mEnrollmentDao.getEnrollmentDataForFledgeByAdTechIdentifier(
                        CommonFixture.VALID_BUYER_1))
                .thenReturn(ENROLLMENT_DATA);
        when(AppManifestConfigHelper.isAllowedCustomAudiencesAccess(
                        CONTEXT, PACKAGE_NAME, ENROLLMENT_ID))
                .thenReturn(true);
        // Add ENROLLMENT_ID to blocklist.
        when(PhFlags.getInstance()).thenReturn(mPhFlags);
        when(mPhFlags.isEnrollmentBlocklisted(ENROLLMENT_ID)).thenReturn(true);

        assertThrows(
                SecurityException.class,
                () ->
                        mChecker.assertAdTechAllowed(
                                CONTEXT,
                                PACKAGE_NAME,
                                CommonFixture.VALID_BUYER_1,
                                API_NAME_LOGGING_ID));
    }

    @Test
    public void testAssertAdTechHasPermission_nullContext_throwNpe() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mChecker.assertAdTechAllowed(
                                null,
                                PACKAGE_NAME,
                                CommonFixture.VALID_BUYER_1,
                                API_NAME_LOGGING_ID));

        verifyZeroInteractions(mPackageManager, mEnrollmentDao, mAdServicesLoggerMock);
    }

    @Test
    public void testAssertAdTechHasPermission_nullPackageName_throwNpe() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mChecker.assertAdTechAllowed(
                                CONTEXT, null, CommonFixture.VALID_BUYER_1, API_NAME_LOGGING_ID));

        verifyZeroInteractions(mPackageManager, mEnrollmentDao, mAdServicesLoggerMock);
    }

    @Test
    public void testAssertAdTechHasPermission_nullAdTechIdentifier_throwNpe() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mChecker.assertAdTechAllowed(
                                CONTEXT, PACKAGE_NAME, null, API_NAME_LOGGING_ID));

        verifyZeroInteractions(mPackageManager, mEnrollmentDao, mAdServicesLoggerMock);
    }

    @Test
    public void testAdTechNotEnrolled_throwSecurityException() {
        when(mEnrollmentDao.getEnrollmentDataForFledgeByAdTechIdentifier(
                        CommonFixture.VALID_BUYER_1))
                .thenReturn(null);

        assertThrows(
                SecurityException.class,
                () ->
                        mChecker.assertAdTechEnrolled(
                                CommonFixture.VALID_BUYER_1, API_NAME_LOGGING_ID));
    }

    @Test
    public void testAdTechEnrolled_isEnrolled() {
        when(mEnrollmentDao.getEnrollmentDataForFledgeByAdTechIdentifier(
                        CommonFixture.VALID_BUYER_1))
                .thenReturn(ENROLLMENT_DATA);

        mChecker.assertAdTechEnrolled(CommonFixture.VALID_BUYER_1, API_NAME_LOGGING_ID);

        verify(mEnrollmentDao)
                .getEnrollmentDataForFledgeByAdTechIdentifier(CommonFixture.VALID_BUYER_1);
        verifyNoMoreInteractions(mEnrollmentDao);
    }
}
