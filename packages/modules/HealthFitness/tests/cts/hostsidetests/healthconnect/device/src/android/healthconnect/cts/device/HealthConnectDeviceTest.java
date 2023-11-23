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

package android.healthconnect.cts.device;

import static android.healthconnect.cts.lib.TestUtils.CHANGE_LOGS_RESPONSE;
import static android.healthconnect.cts.lib.TestUtils.CHANGE_LOG_TOKEN;
import static android.healthconnect.cts.lib.TestUtils.READ_RECORDS_SIZE;
import static android.healthconnect.cts.lib.TestUtils.RECORD_IDS;
import static android.healthconnect.cts.lib.TestUtils.SUCCESS;
import static android.healthconnect.cts.lib.TestUtils.deleteAllStagedRemoteData;
import static android.healthconnect.cts.lib.TestUtils.deleteRecordsAs;
import static android.healthconnect.cts.lib.TestUtils.getChangeLogTokenAs;
import static android.healthconnect.cts.lib.TestUtils.insertRecordAs;
import static android.healthconnect.cts.lib.TestUtils.insertRecordWithAnotherAppPackageName;
import static android.healthconnect.cts.lib.TestUtils.insertRecordWithGivenClientId;
import static android.healthconnect.cts.lib.TestUtils.readChangeLogsUsingDataOriginFiltersAs;
import static android.healthconnect.cts.lib.TestUtils.readRecords;
import static android.healthconnect.cts.lib.TestUtils.readRecordsAs;
import static android.healthconnect.cts.lib.TestUtils.readRecordsUsingDataOriginFiltersAs;
import static android.healthconnect.cts.lib.TestUtils.updateRecordsAs;

import static com.android.compatibility.common.util.FeatureUtil.AUTOMOTIVE_FEATURE;
import static com.android.compatibility.common.util.FeatureUtil.hasSystemFeature;

import static com.google.common.truth.Truth.assertThat;

import android.health.connect.HealthConnectException;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.healthconnect.cts.lib.TestUtils;
import android.os.Bundle;

import androidx.test.runner.AndroidJUnit4;

import com.android.cts.install.lib.TestApp;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

@RunWith(AndroidJUnit4.class)
public class HealthConnectDeviceTest {
    static final String TAG = "HealthConnectDeviceTest";
    static final long VERSION_CODE = 1;

    static final TestApp APP_A_WITH_READ_WRITE_PERMS =
            new TestApp(
                    "TestAppA",
                    "android.healthconnect.cts.testapp.readWritePerms.A",
                    VERSION_CODE,
                    false,
                    "CtsHealthConnectTestAppA.apk");

    private static final TestApp APP_B_WITH_READ_WRITE_PERMS =
            new TestApp(
                    "TestAppB",
                    "android.healthconnect.cts.testapp.readWritePerms.B",
                    VERSION_CODE,
                    false,
                    "CtsHealthConnectTestAppB.apk");

    private static final TestApp APP_WITH_WRITE_PERMS_ONLY =
            new TestApp(
                    "TestAppC",
                    "android.healthconnect.cts.testapp.writePermsOnly",
                    VERSION_CODE,
                    false,
                    "CtsHealthConnectTestAppWithWritePermissionsOnly.apk");

    private static final TestApp APP_WITH_DATA_MANAGE_PERMS_ONLY =
            new TestApp(
                    "TestAppD",
                    "android.healthconnect.cts.testapp.data.manage.permissions",
                    VERSION_CODE,
                    false,
                    "CtsHealthConnectTestAppWithDataManagePermission.apk");

    @Before
    public void setUp() {
        Assume.assumeFalse(hasSystemFeature(AUTOMOTIVE_FEATURE));
    }

    @After
    public void tearDown() {
        deleteAllStagedRemoteData();
    }

    @Test
    public void testAppWithNormalReadWritePermCanInsertRecord() throws Exception {
        Bundle bundle = insertRecordAs(APP_A_WITH_READ_WRITE_PERMS);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();
    }

    @Test
    public void testAnAppCantDeleteAnotherAppEntry() throws Exception {
        Bundle bundle = insertRecordAs(APP_A_WITH_READ_WRITE_PERMS);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        try {
            deleteRecordsAs(APP_B_WITH_READ_WRITE_PERMS, listOfRecordIdsAndClass);
            Assert.fail("Should have thrown an Invalid Argument Exception!");
        } catch (HealthConnectException e) {

            assertThat(e.getErrorCode()).isEqualTo(HealthConnectException.ERROR_INVALID_ARGUMENT);
        }
    }

    @Test
    public void testAnAppCantUpdateAnotherAppEntry() throws Exception {
        Bundle bundle = insertRecordAs(APP_A_WITH_READ_WRITE_PERMS);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        try {
            updateRecordsAs(APP_B_WITH_READ_WRITE_PERMS, listOfRecordIdsAndClass);
            Assert.fail("Should have thrown an Invalid Argument Exception!");
        } catch (HealthConnectException e) {
            assertThat(e.getErrorCode()).isEqualTo(HealthConnectException.ERROR_INVALID_ARGUMENT);
        }
    }

    @Test
    public void testDataOriginGetsOverriddenBySelfPackageName() throws Exception {
        Bundle bundle =
                insertRecordWithAnotherAppPackageName(
                        APP_A_WITH_READ_WRITE_PERMS, APP_B_WITH_READ_WRITE_PERMS);

        List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        for (TestUtils.RecordTypeAndRecordIds recordTypeAndRecordIds : listOfRecordIdsAndClass) {
            List<Record> records =
                    (List<Record>)
                            readRecords(
                                    new ReadRecordsRequestUsingFilters.Builder<>(
                                                    (Class<? extends Record>)
                                                            Class.forName(
                                                                    recordTypeAndRecordIds
                                                                            .getRecordType()))
                                            .build());

            for (Record record : records) {
                assertThat(record.getMetadata().getDataOrigin().getPackageName())
                        .isEqualTo(APP_A_WITH_READ_WRITE_PERMS.getPackageName());
            }
        }
    }

    @Test
    public void testAppWithWritePermsOnlyCanReadItsOwnEntry() throws Exception {
        Bundle bundle = insertRecordAs(APP_WITH_WRITE_PERMS_ONLY);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        ArrayList<String> recordClassesToRead = new ArrayList<>();
        for (TestUtils.RecordTypeAndRecordIds recordTypeAndRecordIds : listOfRecordIdsAndClass) {
            recordClassesToRead.add(recordTypeAndRecordIds.getRecordType());
        }

        bundle = readRecordsAs(APP_WITH_WRITE_PERMS_ONLY, recordClassesToRead);
        assertThat(bundle.getInt(READ_RECORDS_SIZE)).isNotEqualTo(0);
    }

    @Test
    public void testAppWithWritePermsOnlyCantReadAnotherAppEntry() throws Exception {
        Bundle bundle = insertRecordAs(APP_A_WITH_READ_WRITE_PERMS);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        ArrayList<String> recordClassesToRead = new ArrayList<>();
        for (TestUtils.RecordTypeAndRecordIds recordTypeAndRecordIds : listOfRecordIdsAndClass) {
            recordClassesToRead.add(recordTypeAndRecordIds.getRecordType());
        }

        bundle = readRecordsAs(APP_WITH_WRITE_PERMS_ONLY, recordClassesToRead);
        assertThat(bundle.getInt(READ_RECORDS_SIZE)).isEqualTo(0);
    }

    @Test
    public void testAppWithManageHealthDataPermsOnlyCantInsertRecords() throws Exception {
        try {
            insertRecordAs(APP_WITH_DATA_MANAGE_PERMS_ONLY);
            Assert.fail("Should have thrown Exception while inserting records!");
        } catch (HealthConnectException e) {
            assertThat(e.getErrorCode()).isEqualTo(HealthConnectException.ERROR_SECURITY);
        }
    }

    @Test
    public void testAppWithManageHealthDataPermsOnlyCantUpdateRecords() throws Exception {
        Bundle bundle = insertRecordAs(APP_A_WITH_READ_WRITE_PERMS);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        try {
            updateRecordsAs(APP_WITH_DATA_MANAGE_PERMS_ONLY, listOfRecordIdsAndClass);
            Assert.fail("Should have thrown Health Connect Exception!");
        } catch (HealthConnectException e) {
            assertThat(e.getErrorCode()).isEqualTo(HealthConnectException.ERROR_SECURITY);
        }
    }

    @Test
    public void testTwoAppsCanUseSameClientRecordIdsToInsert() throws Exception {
        final double clientId = Math.random();
        Bundle bundle = insertRecordWithGivenClientId(APP_A_WITH_READ_WRITE_PERMS, clientId);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        bundle = insertRecordWithGivenClientId(APP_B_WITH_READ_WRITE_PERMS, clientId);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();
    }

    @Test
    public void testAppCanReadRecordsUsingDataOriginFilters() throws Exception {
        Bundle bundle = insertRecordAs(APP_A_WITH_READ_WRITE_PERMS);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        int noOfRecordsInsertedByAppA = 0;
        Set<String> recordClassesToReadSet = new HashSet<>();
        for (TestUtils.RecordTypeAndRecordIds recordTypeAndRecordIds : listOfRecordIdsAndClass) {
            noOfRecordsInsertedByAppA += recordTypeAndRecordIds.getRecordIds().size();
            recordClassesToReadSet.add(recordTypeAndRecordIds.getRecordType());
        }

        bundle = insertRecordAs(APP_B_WITH_READ_WRITE_PERMS);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        for (TestUtils.RecordTypeAndRecordIds recordTypeAndRecordIds : listOfRecordIdsAndClass) {
            recordClassesToReadSet.add(recordTypeAndRecordIds.getRecordType());
        }

        ArrayList<String> recordClassesToRead = new ArrayList<>();
        for (String recordClass : recordClassesToReadSet) {
            recordClassesToRead.add(recordClass);
        }
        bundle =
                readRecordsUsingDataOriginFiltersAs(
                        APP_A_WITH_READ_WRITE_PERMS, recordClassesToRead);
        assertThat(bundle.getInt(READ_RECORDS_SIZE)).isEqualTo(noOfRecordsInsertedByAppA);
    }

    @Test
    public void testAppCanReadChangeLogsUsingDataOriginFilters() throws Exception {
        Bundle bundle =
                getChangeLogTokenAs(
                        APP_B_WITH_READ_WRITE_PERMS, APP_A_WITH_READ_WRITE_PERMS.getPackageName());
        String changeLogTokenForAppB = bundle.getString(CHANGE_LOG_TOKEN);

        bundle =
                getChangeLogTokenAs(
                        APP_A_WITH_READ_WRITE_PERMS, APP_B_WITH_READ_WRITE_PERMS.getPackageName());
        String changeLogTokenForAppA = bundle.getString(CHANGE_LOG_TOKEN);

        bundle = insertRecordAs(APP_A_WITH_READ_WRITE_PERMS);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        List<String> listOfRecordIdsInsertedByAppA = new ArrayList<>();
        int noOfRecordsInsertedByAppA = 0;
        for (TestUtils.RecordTypeAndRecordIds recordTypeAndRecordIds : listOfRecordIdsAndClass) {
            noOfRecordsInsertedByAppA += recordTypeAndRecordIds.getRecordIds().size();
            listOfRecordIdsInsertedByAppA.addAll(recordTypeAndRecordIds.getRecordIds());
        }

        updateRecordsAs(APP_A_WITH_READ_WRITE_PERMS, listOfRecordIdsAndClass);

        bundle = insertRecordAs(APP_B_WITH_READ_WRITE_PERMS);
        assertThat(bundle.getBoolean(SUCCESS)).isTrue();

        listOfRecordIdsAndClass =
                (List<TestUtils.RecordTypeAndRecordIds>) bundle.getSerializable(RECORD_IDS);

        int noOfRecordsInsertedByAppB = 0;
        for (TestUtils.RecordTypeAndRecordIds recordTypeAndRecordIds : listOfRecordIdsAndClass) {
            noOfRecordsInsertedByAppB += recordTypeAndRecordIds.getRecordIds().size();
        }

        deleteRecordsAs(APP_B_WITH_READ_WRITE_PERMS, listOfRecordIdsAndClass);

        bundle =
                readChangeLogsUsingDataOriginFiltersAs(
                        APP_B_WITH_READ_WRITE_PERMS, changeLogTokenForAppB);

        ChangeLogsResponse response = bundle.getParcelable(CHANGE_LOGS_RESPONSE);

        assertThat(response.getUpsertedRecords().size()).isEqualTo(noOfRecordsInsertedByAppA);
        assertThat(
                        response.getUpsertedRecords().stream()
                                .map(Record::getMetadata)
                                .map(Metadata::getId)
                                .toList())
                .containsExactlyElementsIn(listOfRecordIdsInsertedByAppA);

        assertThat(response.getDeletedLogs().size()).isEqualTo(0);

        bundle =
                readChangeLogsUsingDataOriginFiltersAs(
                        APP_A_WITH_READ_WRITE_PERMS, changeLogTokenForAppA);

        response = bundle.getParcelable(CHANGE_LOGS_RESPONSE);

        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(noOfRecordsInsertedByAppB);
    }
}
