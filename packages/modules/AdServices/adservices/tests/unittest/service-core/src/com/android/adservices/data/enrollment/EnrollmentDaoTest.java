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

package com.android.adservices.data.enrollment;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.adservices.common.AdTechIdentifier;
import android.content.Context;
import android.database.DatabaseUtils;
import android.net.Uri;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.DbTestUtil;
import com.android.adservices.data.shared.SharedDbHelper;
import com.android.adservices.service.Flags;
import com.android.adservices.service.enrollment.EnrollmentData;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Set;

public class EnrollmentDaoTest {

    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    private SharedDbHelper mDbHelper;
    private EnrollmentDao mEnrollmentDao;

    @Mock private Flags mMockFlags;

    private static final EnrollmentData ENROLLMENT_DATA1 =
            new EnrollmentData.Builder()
                    .setEnrollmentId("1")
                    .setCompanyId("1001")
                    .setSdkNames("1sdk")
                    .setAttributionSourceRegistrationUrl(Arrays.asList("https://1test.com/source"))
                    .setAttributionTriggerRegistrationUrl(
                            Arrays.asList("https://1test.com/trigger"))
                    .setAttributionReportingUrl(Arrays.asList("https://1test.com"))
                    .setRemarketingResponseBasedRegistrationUrl(Arrays.asList("https://1test.com"))
                    .setEncryptionKeyUrl(Arrays.asList("https://1test.com/keys"))
                    .build();

    private static final EnrollmentData ENROLLMENT_DATA2 =
            new EnrollmentData.Builder()
                    .setEnrollmentId("2")
                    .setCompanyId("1002")
                    .setSdkNames(Arrays.asList("2sdk", "anotherSdk"))
                    .setAttributionSourceRegistrationUrl(
                            Arrays.asList(
                                    "https://2test.com/source",
                                    "https://2test-middle.com/source",
                                    "https://2test2.com/source"))
                    .setAttributionTriggerRegistrationUrl(
                            Arrays.asList(
                                    "https://2test.com/trigger",
                                    "https://2test.com/trigger/extra/path"))
                    .setAttributionReportingUrl(Arrays.asList("https://2test.com"))
                    .setRemarketingResponseBasedRegistrationUrl(Arrays.asList("https://2test.com"))
                    .setEncryptionKeyUrl(Arrays.asList("https://2test.com/keys"))
                    .build();

    private static final EnrollmentData ENROLLMENT_DATA3 =
            new EnrollmentData.Builder()
                    .setEnrollmentId("3")
                    .setCompanyId("1003")
                    .setSdkNames("3sdk 31sdk")
                    .setAttributionSourceRegistrationUrl(
                            Arrays.asList("https://2test.com/source", "https://2test2.com/source"))
                    .setAttributionTriggerRegistrationUrl(
                            Arrays.asList("https://2test.com/trigger"))
                    .setAttributionReportingUrl(Arrays.asList("https://2test.com"))
                    .setRemarketingResponseBasedRegistrationUrl(Arrays.asList("https://2test.com"))
                    .setEncryptionKeyUrl(Arrays.asList("https://2test.com/keys"))
                    .build();

    private static final EnrollmentData ENROLLMENT_DATA4 =
            new EnrollmentData.Builder()
                    .setEnrollmentId("4")
                    .setCompanyId("1004")
                    .setSdkNames("4sdk 41sdk")
                    .setAttributionSourceRegistrationUrl(
                            Arrays.asList("https://4test.com", "https://prefix.test-prefix.com"))
                    .setAttributionTriggerRegistrationUrl(Arrays.asList("https://4test.com"))
                    .setAttributionReportingUrl(Arrays.asList("https://4test.com"))
                    .setRemarketingResponseBasedRegistrationUrl(Arrays.asList("https://4test.com"))
                    .setEncryptionKeyUrl(Arrays.asList("https://4test.com/keys"))
                    .build();

    private static final EnrollmentData ENROLLMENT_DATA5 =
            new EnrollmentData.Builder()
                    .setEnrollmentId("5")
                    .setCompanyId("1005")
                    .setSdkNames("5sdk 51sdk")
                    .setAttributionSourceRegistrationUrl(
                            Arrays.asList(
                                    "https://us.5test.com/source",
                                    "https://us.5test2.com/source",
                                    "https://port-test.5test3.com:443/source"))
                    .setAttributionTriggerRegistrationUrl(
                            Arrays.asList(
                                    "https://us.5test.com/trigger",
                                    "https://port-test.5test3.com:443/trigger"))
                    .setAttributionReportingUrl(Arrays.asList("https://us.5test.com"))
                    .setRemarketingResponseBasedRegistrationUrl(
                            Arrays.asList("https://us.5test.com"))
                    .setEncryptionKeyUrl(Arrays.asList("https://us.5test.com/keys"))
                    .build();

    private static final EnrollmentData DUPLICATE_ID_ENROLLMENT_DATA =
            new EnrollmentData.Builder()
                    .setEnrollmentId("1")
                    .setCompanyId("1004")
                    .setSdkNames("4sdk")
                    .setAttributionSourceRegistrationUrl(Arrays.asList("https://4test.com/source"))
                    .setAttributionTriggerRegistrationUrl(
                            Arrays.asList("https://4test.com/trigger"))
                    .setAttributionReportingUrl(Arrays.asList("https://4test.com"))
                    .setRemarketingResponseBasedRegistrationUrl(Arrays.asList("https://4test.com"))
                    .setEncryptionKeyUrl(Arrays.asList("https://4test.com/keys"))
                    .build();

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mDbHelper = DbTestUtil.getSharedDbHelperForTest();
        when(mMockFlags.isEnableEnrollmentTestSeed()).thenReturn(false);
        mEnrollmentDao = new EnrollmentDao(sContext, mDbHelper, mMockFlags);
    }

    @After
    public void cleanup() {
        clearAllTables();
    }

    private void clearAllTables() {
        for (String table : EnrollmentTables.ENROLLMENT_TABLES) {
            mDbHelper.safeGetWritableDatabase().delete(table, null, null);
        }
    }

    @Test
    public void testInitialization() {
        // Check seeded
        EnrollmentDao spyEnrollmentDao =
                Mockito.spy(new EnrollmentDao(sContext, mDbHelper, mMockFlags));
        Mockito.doReturn(false).when(spyEnrollmentDao).isSeeded();

        spyEnrollmentDao.seed();
        long count =
                DatabaseUtils.queryNumEntries(
                        mDbHelper.getReadableDatabase(),
                        EnrollmentTables.EnrollmentDataContract.TABLE,
                        null);
        assertNotEquals(count, 0);

        // Check that seeded enrollments are in the table.
        EnrollmentData e = spyEnrollmentDao.getEnrollmentData("E1");
        assertNotNull(e);
        assertEquals(e.getSdkNames().get(0), "sdk1");
        EnrollmentData e2 = spyEnrollmentDao.getEnrollmentData("E2");
        assertNotNull(e2);
        assertEquals(e2.getSdkNames().get(0), "sdk2");
        EnrollmentData e3 = spyEnrollmentDao.getEnrollmentData("E3");
        assertNotNull(e3);
        assertEquals(e3.getSdkNames().get(0), "sdk3");
    }

    @Test
    public void testDelete() {
        mEnrollmentDao.insert(ENROLLMENT_DATA1);
        EnrollmentData e = mEnrollmentDao.getEnrollmentData("1");
        assertEquals(e, ENROLLMENT_DATA1);

        mEnrollmentDao.delete("1");
        EnrollmentData e2 = mEnrollmentDao.getEnrollmentData("1");
        assertNull(e2);
    }

    @Test
    public void testDeleteAll() {
        // Insert a record
        mEnrollmentDao.insert(ENROLLMENT_DATA1);
        long count =
                DatabaseUtils.queryNumEntries(
                        mDbHelper.getReadableDatabase(),
                        EnrollmentTables.EnrollmentDataContract.TABLE,
                        null);
        assertNotEquals(count, 0);

        // Delete the whole table
        assertTrue(mEnrollmentDao.deleteAll());

        // Check seeded enrollments are deleted.
        count =
                DatabaseUtils.queryNumEntries(
                        mDbHelper.getReadableDatabase(),
                        EnrollmentTables.EnrollmentDataContract.TABLE,
                        null);
        assertEquals(count, 0);

        // Check unseeded.
        assertFalse(mEnrollmentDao.isSeeded());
    }

    @Test
    public void testGetEnrollmentData() {
        mEnrollmentDao.insert(ENROLLMENT_DATA1);
        EnrollmentData e = mEnrollmentDao.getEnrollmentData("1");
        assertEquals(e, ENROLLMENT_DATA1);
    }

    @Test
    public void initEnrollmentDao_ForEnrollmentSeedFlagOn_PerformsSeed() {
        when(mMockFlags.isEnableEnrollmentTestSeed()).thenReturn(true);
        EnrollmentDao enrollmentDao = new EnrollmentDao(sContext, mDbHelper, mMockFlags);

        for (EnrollmentData enrollmentData : PreEnrolledAdTechForTest.getList()) {
            EnrollmentData e = enrollmentDao.getEnrollmentData(enrollmentData.getEnrollmentId());
            assertEquals(enrollmentData, e);
        }
    }

    @Test
    public void initEnrollmentDao_ForEnrollmentSeedFlagOff_SkipsSeed() {
        when(mMockFlags.isEnableEnrollmentTestSeed()).thenReturn(false);
        for (EnrollmentData enrollmentData : PreEnrolledAdTechForTest.getList()) {
            EnrollmentData e = mEnrollmentDao.getEnrollmentData(enrollmentData.getEnrollmentId());
            assertNull(e);
        }
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForOriginMatchAndSameOrigin_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(true);
        mEnrollmentDao.insert(ENROLLMENT_DATA5);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://us.5test.com/source"));

        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://us.5test.com"));

        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://us.5test.com/anotherPath"));

        EnrollmentData e4 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://us.5test2.com/source"));

        EnrollmentData e5 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://us.5test.com/trigger"));

        assertEquals(e1, ENROLLMENT_DATA5);
        assertEquals(e2, ENROLLMENT_DATA5);
        assertEquals(e3, ENROLLMENT_DATA5);
        assertEquals(e4, ENROLLMENT_DATA5);
        assertEquals(e5, ENROLLMENT_DATA5);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForOriginMatchAndSamePort_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(true);
        mEnrollmentDao.insert(ENROLLMENT_DATA5);

        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://port-test.5test3.com:443/source"));
        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://port-test.5test3.com:443/trigger"));

        assertEquals(e1, ENROLLMENT_DATA5);
        assertEquals(e2, ENROLLMENT_DATA5);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForOriginMatchAndDifferentPort_isNotMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(true);
        mEnrollmentDao.insert(ENROLLMENT_DATA5);

        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://port-test.5test3.com:8080/source"));
        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://port-test.5test3.com:8080/trigger"));

        assertNull(e1);
        assertNull(e2);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndAnyPort_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA5);

        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://port-test.5test3.com:443/source"));
        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://port-test.5test3.com:8080/source"));
        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://port-test.5test3.com/source"));
        EnrollmentData e4 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://5test3.com/source"));

        assertEquals(e1, ENROLLMENT_DATA5);
        assertEquals(e2, ENROLLMENT_DATA5);
        assertEquals(e3, ENROLLMENT_DATA5);
        assertEquals(e4, ENROLLMENT_DATA5);
    }

    @Test
    public void
            getEnrollmentDataFromMeasurementUrl_ForOriginMatchAndDifferentOriginUri_isNotMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(true);
        mEnrollmentDao.insert(ENROLLMENT_DATA5);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://eu.5test.com/source"));

        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(Uri.parse("https://5test.com"));

        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://eu.5test2.com"));

        EnrollmentData e4 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://eu.5test.com/trigger"));

        assertNull(e1);
        assertNull(e2);
        assertNull(e3);
        assertNull(e4);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndSameSiteUri_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/source"));
        assertEquals(e1, ENROLLMENT_DATA2);

        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test2.com/source"));
        assertEquals(e2, ENROLLMENT_DATA2);

        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/trigger"));
        assertEquals(e3, ENROLLMENT_DATA2);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndSameETLD_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://prefix.2test.com/source"));
        assertEquals(e1, ENROLLMENT_DATA2);

        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://prefix.2test2.com/source"));
        assertEquals(e2, ENROLLMENT_DATA2);

        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://prefix.2test.com/trigger"));
        assertEquals(e3, ENROLLMENT_DATA2);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndSamePath_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA2);

        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/source"));

        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/trigger"));

        assertEquals(e1, ENROLLMENT_DATA2);
        assertEquals(e2, ENROLLMENT_DATA2);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndIncompletePath_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        EnrollmentData e =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/so"));
        assertEquals(e, ENROLLMENT_DATA2);
        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test2.com/so"));
        assertEquals(e2, ENROLLMENT_DATA2);
        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/tri"));
        assertEquals(e3, ENROLLMENT_DATA2);
        EnrollmentData e4 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/trigger/extra"));
        assertEquals(e4, ENROLLMENT_DATA2);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndExtraPath_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/source/viewId/123"));
        assertEquals(e1, ENROLLMENT_DATA2);

        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test2.com/source/viewId/123"));
        assertEquals(e2, ENROLLMENT_DATA2);

        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/trigger/clickId/123"));
        assertEquals(e3, ENROLLMENT_DATA2);

        EnrollmentData e4 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.com/trigger/extra/path/clickId/123"));
        assertEquals(e4, ENROLLMENT_DATA2);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndDifferentUri_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA4);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://4test.com/path"));
        assertEquals(e1, ENROLLMENT_DATA4);

        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://prefix.test-prefix.com/path"));
        assertEquals(e2, ENROLLMENT_DATA4);

        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://test-prefix.com/path"));
        assertEquals(e3, ENROLLMENT_DATA4);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndOneUrlInEnrollment_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        EnrollmentData data =
                new EnrollmentData.Builder()
                        .setEnrollmentId("5")
                        .setCompanyId("1005")
                        .setSdkNames("5sdk 51sdk")
                        .setAttributionSourceRegistrationUrl(
                                Arrays.asList("https://prefix.test-prefix.com"))
                        .setAttributionTriggerRegistrationUrl(Arrays.asList("https://5test.com"))
                        .setAttributionReportingUrl(Arrays.asList("https://5test.com"))
                        .setRemarketingResponseBasedRegistrationUrl(
                                Arrays.asList("https://5test.com"))
                        .setEncryptionKeyUrl(Arrays.asList("https://5test.com/keys"))
                        .build();
        mEnrollmentDao.insert(data);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://prefix.test-prefix.com"));
        assertEquals(e1, data);

        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://another-prefix.prefix.test-prefix.com"));
        assertEquals(e2, data);

        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://prefix.test-prefix.com/path"));
        assertEquals(e3, data);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndDiffSchemeUrl_matchesScheme() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        EnrollmentData data =
                new EnrollmentData.Builder()
                        .setEnrollmentId("4")
                        .setCompanyId("1004")
                        .setSdkNames("4sdk 41sdk")
                        .setAttributionSourceRegistrationUrl(
                                Arrays.asList("http://4test.com", "https://prefix.test-prefix.com"))
                        .setAttributionTriggerRegistrationUrl(Arrays.asList("https://4test.com"))
                        .setAttributionReportingUrl(Arrays.asList("https://4test.com"))
                        .setRemarketingResponseBasedRegistrationUrl(
                                Arrays.asList("https://4test.com"))
                        .setEncryptionKeyUrl(Arrays.asList("https://4test.com/keys"))
                        .build();
        mEnrollmentDao.insert(data);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://prefix.test-prefix.com"));
        assertEquals(e1, data);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndSameSubdomainChild_isMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA4);
        EnrollmentData e =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://test-prefix.com"));
        assertEquals(e, ENROLLMENT_DATA4);

        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://other-prefix.test-prefix.com"));
        assertEquals(e1, ENROLLMENT_DATA4);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndDifferentDomain_doesNotMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        EnrollmentData e =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://abc2test.com/source"));
        assertNull(e);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://abc2test.com/trigger"));
        assertNull(e1);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndDifferentScheme_doesNotMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        EnrollmentData e =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("http://2test.com/source"));
        assertNull(e);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("http://2test.com/trigger"));
        assertNull(e1);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndDifferentETld_doesNotMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        EnrollmentData e =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(Uri.parse("https://2test.co"));

        assertNull(e);
        EnrollmentData e2 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.co/source"));
        assertNull(e2);
        EnrollmentData e3 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://2test.co/trigger"));
        assertNull(e3);
    }

    @Test
    public void getEnrollmentDataFromMeasurementUrl_ForSiteMatchAndInvalidPublicSuffix_isNoMatch() {
        when(mMockFlags.getEnforceEnrollmentOriginMatch()).thenReturn(false);
        mEnrollmentDao.insert(ENROLLMENT_DATA4);
        EnrollmentData e =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://4test.invalid"));
        assertNull(e);

        EnrollmentData enrollmentData =
                new EnrollmentData.Builder()
                        .setEnrollmentId("4")
                        .setCompanyId("1004")
                        .setSdkNames("4sdk 41sdk")
                        .setAttributionSourceRegistrationUrl(Arrays.asList("https://4test.invalid"))
                        .setAttributionTriggerRegistrationUrl(
                                Arrays.asList("https://4test.invalid"))
                        .setAttributionReportingUrl(Arrays.asList("https://4test.invalid"))
                        .setRemarketingResponseBasedRegistrationUrl(
                                Arrays.asList("https://4test.invalid"))
                        .setEncryptionKeyUrl(Arrays.asList("https://4test.invalid/keys"))
                        .build();
        mEnrollmentDao.insert(enrollmentData);
        EnrollmentData e1 =
                mEnrollmentDao.getEnrollmentDataFromMeasurementUrl(
                        Uri.parse("https://4test.invalid"));
        assertNull(e1);
    }

    @Test
    public void testGetEnrollmentDataForFledgeByAdTechIdentifier() {
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        AdTechIdentifier adtechIdentifier = AdTechIdentifier.fromString("2test.com", false);
        EnrollmentData e =
                mEnrollmentDao.getEnrollmentDataForFledgeByAdTechIdentifier(adtechIdentifier);
        assertEquals(e, ENROLLMENT_DATA2);
    }

    @Test
    public void testGetAllFledgeEnrolledAdTechs_noEntries() {
        // Delete any entries in the database
        clearAllTables();

        assertThat(mEnrollmentDao.getAllFledgeEnrolledAdTechs()).isEmpty();
    }

    @Test
    public void testGetAllFledgeEnrolledAdTechs_multipleEntries() {
        mEnrollmentDao.insert(ENROLLMENT_DATA1);
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        mEnrollmentDao.insert(ENROLLMENT_DATA3);

        Set<AdTechIdentifier> enrolledFledgeAdTechIdentifiers =
                mEnrollmentDao.getAllFledgeEnrolledAdTechs();

        assertThat(enrolledFledgeAdTechIdentifiers).hasSize(2);
        assertThat(enrolledFledgeAdTechIdentifiers)
                .containsExactly(
                        AdTechIdentifier.fromString("1test.com"),
                        AdTechIdentifier.fromString("2test.com"));
    }

    @Test
    public void testGetEnrollmentDataFromSdkName() {
        mEnrollmentDao.insert(ENROLLMENT_DATA2);
        EnrollmentData e = mEnrollmentDao.getEnrollmentDataFromSdkName("2sdk");
        assertEquals(e, ENROLLMENT_DATA2);

        EnrollmentData e2 = mEnrollmentDao.getEnrollmentDataFromSdkName("anotherSdk");
        assertEquals(e2, ENROLLMENT_DATA2);

        mEnrollmentDao.insert(ENROLLMENT_DATA3);
        EnrollmentData e3 = mEnrollmentDao.getEnrollmentDataFromSdkName("31sdk");
        assertEquals(e3, ENROLLMENT_DATA3);
    }

    @Test
    public void testDuplicateEnrollmentData() {
        mEnrollmentDao.insert(ENROLLMENT_DATA1);
        EnrollmentData e = mEnrollmentDao.getEnrollmentData("1");
        assertEquals(ENROLLMENT_DATA1, e);

        mEnrollmentDao.insert(DUPLICATE_ID_ENROLLMENT_DATA);
        e = mEnrollmentDao.getEnrollmentData("1");
        assertEquals(DUPLICATE_ID_ENROLLMENT_DATA, e);
    }
}
