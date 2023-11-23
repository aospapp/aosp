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

package android.healthconnect.cts;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.changelog.ChangeLogTokenResponse;
import android.health.connect.changelog.ChangeLogsRequest;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.StepsRecord;
import android.platform.test.annotations.AppModeFull;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/** CTS test for API provided by HealthConnectManager. */
@AppModeFull(reason = "HealthConnectManager is not accessible to instant apps")
@RunWith(AndroidJUnit4.class)
public class HealthConnectChangeLogsTests {
    @Before
    public void setUp() {
        // TODO(b/283737434): Update the HC code to use user aware context on permission change.
        // Temporary fix to set firstGrantTime for the correct user in HSUM.
        TestUtils.deleteAllStagedRemoteData();
    }

    @Test
    public void testGetChangeLogToken() throws InterruptedException {
        ChangeLogTokenRequest changeLogTokenRequest = new ChangeLogTokenRequest.Builder().build();
        assertThat(TestUtils.getChangeLogToken(changeLogTokenRequest)).isNotNull();
        assertThat(changeLogTokenRequest.getRecordTypes()).isNotNull();
        assertThat(changeLogTokenRequest.getDataOriginFilters()).isNotNull();
    }

    @Test
    public void testChangeLogs_insert_default() throws InterruptedException {
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(new ChangeLogTokenRequest.Builder().build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        assertThat(changeLogsRequest.getToken()).isNotNull();
        assertThat(changeLogsRequest.getPageSize()).isNotNull();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);

        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(testRecord.size());
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);
    }

    @Test
    public void testChangeLogs_insert_dataOrigin_filter_incorrect() throws InterruptedException {
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(
                        new ChangeLogTokenRequest.Builder()
                                .addDataOriginFilter(
                                        new DataOrigin.Builder().setPackageName("random").build())
                                .build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        assertThat(changeLogsRequest.getPageSize()).isNotNull();
        assertThat(changeLogsRequest.getToken()).isNotNull();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);

        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);
    }

    @Test
    public void testChangeLogs_insert_dataOrigin_filter_correct() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(
                        new ChangeLogTokenRequest.Builder()
                                .addDataOriginFilter(
                                        new DataOrigin.Builder()
                                                .setPackageName(context.getPackageName())
                                                .build())
                                .build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        assertThat(changeLogsRequest.getPageSize()).isNotNull();
        assertThat(changeLogsRequest.getToken()).isNotNull();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);

        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(testRecord.size());
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);
    }

    @Test
    public void testChangeLogs_insert_record_filter() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(
                        new ChangeLogTokenRequest.Builder()
                                .addDataOriginFilter(
                                        new DataOrigin.Builder()
                                                .setPackageName(context.getPackageName())
                                                .build())
                                .addRecordType(StepsRecord.class)
                                .build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);

        List<Record> testRecord = Collections.singletonList(TestUtils.getStepsRecord());
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(1);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);
        testRecord = Collections.singletonList(TestUtils.getHeartRateRecord());
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(1);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);
    }

    @Test
    public void testChangeLogs_insertAndDelete_default() throws InterruptedException {
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(new ChangeLogTokenRequest.Builder().build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);

        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        TestUtils.deleteRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(testRecord.size());
        List<ChangeLogsResponse.DeletedLog> deletedLogs = response.getDeletedLogs();
        ChangeLogsResponse.DeletedLog deletedLog =
                new ChangeLogsResponse.DeletedLog("abc", Instant.now().toEpochMilli());
        assertThat(deletedLogs).doesNotContain(deletedLog);
        for (ChangeLogsResponse.DeletedLog log : deletedLogs) {
            assertThat(log.getDeletedRecordId()).isNotNull();
            assertThat(log.getDeletedTime()).isNotNull();
            assertThat(log.getDeletedRecordId()).isNotEqualTo(deletedLog.getDeletedRecordId());
        }
    }

    @Test
    public void testChangeLogs_insertAndDelete_beforePermission() throws InterruptedException {
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(new ChangeLogTokenRequest.Builder().build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);

        List<Record> testRecord =
                Arrays.asList(
                        StepsRecordTest.getStepsRecord_minusDays(45),
                        StepsRecordTest.getStepsRecord_minusDays(20),
                        StepsRecordTest.getStepsRecord_minusDays(5));
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(2);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);
        TestUtils.deleteRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(3);
    }

    @Test
    public void testChangeLogs_insertAndDelete_dataOrigin_filter_incorrect()
            throws InterruptedException {
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(
                        new ChangeLogTokenRequest.Builder()
                                .addDataOriginFilter(
                                        new DataOrigin.Builder().setPackageName("random").build())
                                .build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        assertThat(changeLogsRequest.getPageSize()).isNotNull();
        assertThat(changeLogsRequest.getToken()).isNotNull();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);

        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        TestUtils.deleteRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);
    }

    @Test
    public void testChangeLogs_insertAndDelete_dataOrigin_filter_correct()
            throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(
                        new ChangeLogTokenRequest.Builder()
                                .addDataOriginFilter(
                                        new DataOrigin.Builder()
                                                .setPackageName(context.getPackageName())
                                                .build())
                                .build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        assertThat(changeLogsRequest.getPageSize()).isNotNull();
        assertThat(changeLogsRequest.getToken()).isNotNull();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);

        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        TestUtils.deleteRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(testRecord.size());
    }

    @Test
    public void testChangeLogs_insertAndDelete_record_filter() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(
                        new ChangeLogTokenRequest.Builder()
                                .addDataOriginFilter(
                                        new DataOrigin.Builder()
                                                .setPackageName(context.getPackageName())
                                                .build())
                                .addRecordType(StepsRecord.class)
                                .build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        assertThat(changeLogsRequest.getPageSize()).isNotNull();
        assertThat(changeLogsRequest.getToken()).isNotNull();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);

        List<Record> testRecord = Collections.singletonList(TestUtils.getStepsRecord());
        TestUtils.insertRecords(testRecord);
        TestUtils.deleteRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(1);
        testRecord = Collections.singletonList(TestUtils.getHeartRateRecord());
        TestUtils.insertRecords(testRecord);
        TestUtils.deleteRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(1);
    }

    @Test
    public void testChangeLogs_insert_default_withPageSize() throws InterruptedException {
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(new ChangeLogTokenRequest.Builder().build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).setPageSize(1).build();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);

        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(1);
    }

    @Test
    public void testChangeLogs_insert_default_withNextPageToken() throws InterruptedException {
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(new ChangeLogTokenRequest.Builder().build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).setPageSize(1).build();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.hasMorePages()).isFalse();

        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.hasMorePages()).isTrue();
        assertThat(response.getUpsertedRecords().size()).isEqualTo(1);
        ChangeLogsRequest nextChangeLogsRequest =
                new ChangeLogsRequest.Builder(response.getNextChangesToken())
                        .setPageSize(1)
                        .build();
        ChangeLogsResponse nextResponse = TestUtils.getChangeLogs(nextChangeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(1);
        assertThat(nextResponse.hasMorePages()).isTrue();
        assertThat(nextResponse.getNextChangesToken())
                .isGreaterThan(response.getNextChangesToken());
        nextChangeLogsRequest =
                new ChangeLogsRequest.Builder(nextResponse.getNextChangesToken()).build();
        nextResponse = TestUtils.getChangeLogs(nextChangeLogsRequest);
        assertThat(nextResponse.hasMorePages()).isFalse();
    }

    @Test
    public void testChangeLogs_insert_default_withSamePageToken() throws InterruptedException {
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(new ChangeLogTokenRequest.Builder().build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.hasMorePages()).isFalse();
        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        ChangeLogsResponse newResponse = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(newResponse.getUpsertedRecords().size()).isEqualTo(testRecord.size());
    }

    // Test added for b/271607816 to make sure that getChangeLogs() method returns the requested
    // changelog token as nextPageToken in the response when it is the end of page.
    // ( i.e. hasMoreRecords is false)
    @Test
    public void testChangeLogs_checkToken_hasMorePages_False() throws InterruptedException {
        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(new ChangeLogTokenRequest.Builder().build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.hasMorePages()).isFalse();
        List<Record> testRecord = TestUtils.getTestRecords();
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(testRecord.size());
        assertThat(response.hasMorePages()).isFalse();
        ChangeLogsRequest changeLogsRequestNew =
                new ChangeLogsRequest.Builder(response.getNextChangesToken())
                        .setPageSize(2)
                        .build();
        ChangeLogsResponse newResponse = TestUtils.getChangeLogs(changeLogsRequestNew);
        assertThat(newResponse.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(newResponse.hasMorePages()).isFalse();
        assertThat(newResponse.getNextChangesToken()).isEqualTo(changeLogsRequestNew.getToken());
    }
}
