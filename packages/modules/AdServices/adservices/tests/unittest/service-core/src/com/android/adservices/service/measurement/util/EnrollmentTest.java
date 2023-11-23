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
package com.android.adservices.service.measurement.util;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.Uri;

import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.AppManifestConfigHelper;
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

@SmallTest
public final class EnrollmentTest {

    private static final Context sContext =
            InstrumentationRegistry.getInstrumentation().getContext();
    private static final Uri REGISTRATION_URI = Uri.parse("https://ad-tech.test/register");
    private static final String ENROLLMENT_ID = "enrollment-id";
    private static final String PACKAGE_NAME = "com.package.name";
    private static final EnrollmentData ENROLLMENT =
            new EnrollmentData.Builder()
                    .setEnrollmentId("enrollment-id")
                    .setAttributionReportingUrl(
                            List.of("https://origin1.test", "https://origin2.test"))
                    .build();
    @Mock private EnrollmentDao mEnrollmentDao;

    @Mock private Flags mFlags;

    private StaticMockitoSession mStaticMockitoSession;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mStaticMockitoSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(AppManifestConfigHelper.class)
                        .strictness(Strictness.WARN)
                        .startMocking();
    }

    @After
    public void cleanup() {
        mStaticMockitoSession.finishMocking();
    }

    @Test
    public void testMaybeGetEnrollmentId_success() {
        when(mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(eq(REGISTRATION_URI)))
                .thenReturn(ENROLLMENT);
        when(mFlags.isEnrollmentBlocklisted(any())).thenReturn(false);
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                AppManifestConfigHelper.isAllowedAttributionAccess(
                                        any(), eq(PACKAGE_NAME), eq(ENROLLMENT_ID)));

        assertEquals(
                Optional.of(ENROLLMENT.getEnrollmentId()),
                Enrollment.getValidEnrollmentId(
                        REGISTRATION_URI, PACKAGE_NAME, mEnrollmentDao, sContext, mFlags));
    }

    @Test
    public void testMaybeGetEnrollmentId_enrollmentDataNull() {
        // Simulating failure
        when(mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(eq(REGISTRATION_URI)))
                .thenReturn(null);
        when(mFlags.isEnrollmentBlocklisted(any())).thenReturn(false);
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                AppManifestConfigHelper.isAllowedAttributionAccess(
                                        any(), eq(PACKAGE_NAME), eq(ENROLLMENT_ID)));

        assertEquals(
                Optional.empty(),
                Enrollment.getValidEnrollmentId(
                        REGISTRATION_URI, PACKAGE_NAME, mEnrollmentDao, sContext, mFlags));
    }

    @Test
    public void testMaybeGetEnrollmentId_blockedByEnrollmentBlockList() {
        when(mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(eq(REGISTRATION_URI)))
                .thenReturn(ENROLLMENT);
        // Simulating failure
        when(mFlags.isEnrollmentBlocklisted(any())).thenReturn(true);
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                AppManifestConfigHelper.isAllowedAttributionAccess(
                                        any(), eq(PACKAGE_NAME), eq(ENROLLMENT_ID)));
        assertEquals(
                Optional.empty(),
                Enrollment.getValidEnrollmentId(
                        REGISTRATION_URI, PACKAGE_NAME, mEnrollmentDao, sContext, mFlags));
    }

    @Test
    public void testMaybeGetEnrollmentId_packageManifestCheckFailure() {
        when(mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(eq(REGISTRATION_URI)))
                .thenReturn(ENROLLMENT);
        when(mFlags.isEnrollmentBlocklisted(any())).thenReturn(false);
        // Simulating failure
        ExtendedMockito.doReturn(false)
                .when(
                        () ->
                                AppManifestConfigHelper.isAllowedAttributionAccess(
                                        any(), eq(PACKAGE_NAME), eq(ENROLLMENT_ID)));

        assertEquals(
                Optional.empty(),
                Enrollment.getValidEnrollmentId(
                        REGISTRATION_URI, PACKAGE_NAME, mEnrollmentDao, sContext, mFlags));
    }

    @Test
    public void testMaybeGetReportingOrigin_success() {
        when(mEnrollmentDao.getEnrollmentData(eq(ENROLLMENT_ID))).thenReturn(ENROLLMENT);

        assertEquals(
                Optional.of(Uri.parse("https://origin1.test")),
                Enrollment.maybeGetReportingOrigin(ENROLLMENT_ID, mEnrollmentDao));
    }

    @Test
    public void testMaybeGetReportingOrigin_enrollmentDataNull() {
        when(mEnrollmentDao.getEnrollmentData(eq(ENROLLMENT_ID))).thenReturn(null);

        assertEquals(
                Optional.empty(),
                Enrollment.maybeGetReportingOrigin(ENROLLMENT_ID, mEnrollmentDao));
    }

    @Test
    public void testMaybeGetReportingOrigin_attributionReportingUrlEmpty() {
        EnrollmentData enrollment = new EnrollmentData.Builder().build();
        when(mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(eq(Uri.parse(ENROLLMENT_ID))))
                .thenReturn(enrollment);

        assertEquals(
                Optional.empty(),
                Enrollment.maybeGetReportingOrigin(ENROLLMENT_ID, mEnrollmentDao));
    }
}
