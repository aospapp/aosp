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

import static android.health.connect.HealthConnectException.ERROR_INVALID_ARGUMENT;
import static android.health.connect.datatypes.Metadata.RECORDING_METHOD_ACTIVELY_RECORDED;
import static android.health.connect.datatypes.StepsRecord.STEPS_COUNT_TOTAL;
import static android.healthconnect.cts.TestUtils.isHardwareAutomotive;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.health.connect.AggregateRecordsGroupedByDurationResponse;
import android.health.connect.AggregateRecordsGroupedByPeriodResponse;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.DeleteUsingFiltersRequest;
import android.health.connect.HealthConnectException;
import android.health.connect.LocalTimeRangeFilter;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.ReadRecordsRequestUsingIds;
import android.health.connect.RecordIdFilter;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.changelog.ChangeLogTokenResponse;
import android.health.connect.changelog.ChangeLogsRequest;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.StepsRecord;
import android.platform.test.annotations.AppModeFull;
import android.util.Pair;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@AppModeFull(reason = "HealthConnectManager is not accessible to instant apps")
@RunWith(AndroidJUnit4.class)
public class StepsRecordTest {
    private static final String TAG = "StepsRecordTest";

    @Before
    public void setUp() {
        // TODO(b/283737434): Update the HC code to use user aware context on permission change.
        // Temporary fix to set firstGrantTime for the correct user in HSUM.
        TestUtils.deleteAllStagedRemoteData();
    }

    @After
    public void tearDown() throws InterruptedException {
        TestUtils.verifyDeleteRecords(
                StepsRecord.class,
                new TimeInstantRangeFilter.Builder()
                        .setStartTime(Instant.EPOCH)
                        .setEndTime(Instant.now().plus(1, ChronoUnit.DAYS))
                        .build());
        TestUtils.deleteAllStagedRemoteData();
    }

    @Test
    public void testInsertStepsRecord() throws InterruptedException {
        List<Record> records = List.of(getBaseStepsRecord(), getCompleteStepsRecord());
        TestUtils.insertRecords(records);
    }

    @Test
    public void testUpdateStepsRecordToDuplicate() throws InterruptedException {
        List<Record> records = List.of(getBaseStepsRecord(), getStepsRecord_minusDays(1));
        records = TestUtils.insertRecords(records);

        try {
            TestUtils.updateRecords(
                    Collections.singletonList(
                            getStepsRecordDuplicateEntry(
                                    (StepsRecord) records.get(1), (StepsRecord) records.get(0))));
            Assert.fail();
        } catch (HealthConnectException healthConnectException) {
            assertThat(healthConnectException.getErrorCode()).isEqualTo(ERROR_INVALID_ARGUMENT);
            assertThat(healthConnectException.getMessage())
                    .contains(records.get(0).getMetadata().getId());
            assertThat(healthConnectException.getMessage())
                    .contains(records.get(1).getMetadata().getId());
        }
    }

    @Test
    public void testReadStepsRecord_usingIds() throws InterruptedException {
        List<Record> recordList = Arrays.asList(getCompleteStepsRecord(), getCompleteStepsRecord());
        List<Record> insertedRecords = TestUtils.insertRecords(recordList);
        readStepsRecordUsingIds(insertedRecords);
    }

    @Test
    public void testReadStepsRecord_invalidIds() throws InterruptedException {
        ReadRecordsRequestUsingIds<StepsRecord> request =
                new ReadRecordsRequestUsingIds.Builder<>(StepsRecord.class)
                        .addId(UUID.randomUUID().toString())
                        .build();
        assertThat(request.getRecordType()).isEqualTo(StepsRecord.class);
        assertThat(request.getRecordIdFilters()).isNotNull();
        List<StepsRecord> result = TestUtils.readRecords(request);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    public void testReadStepsRecord_usingClientRecordIds() throws InterruptedException {
        List<Record> recordList = Arrays.asList(getCompleteStepsRecord(), getCompleteStepsRecord());
        List<Record> insertedRecords = TestUtils.insertRecords(recordList);
        readStepsRecordUsingClientId(insertedRecords);
    }

    @Test
    public void testReadStepsRecord_invalidClientRecordIds() throws InterruptedException {
        ReadRecordsRequestUsingIds<StepsRecord> request =
                new ReadRecordsRequestUsingIds.Builder<>(StepsRecord.class)
                        .addClientRecordId("abc")
                        .build();
        List<StepsRecord> result = TestUtils.readRecords(request);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    public void testReadStepsRecordUsingFilters_default() throws InterruptedException {
        List<StepsRecord> oldStepsRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setAscending(true)
                                .build());
        StepsRecord testRecord = getCompleteStepsRecord();
        TestUtils.insertRecords(Collections.singletonList(testRecord));
        List<StepsRecord> newStepsRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setAscending(true)
                                .build());
        assertThat(newStepsRecords.size()).isEqualTo(oldStepsRecords.size() + 1);
        assertThat(newStepsRecords.get(newStepsRecords.size() - 1).equals(testRecord)).isTrue();
    }

    @Test
    public void testReadStepsRecordUsingFilters_timeFilter() throws InterruptedException {
        TimeInstantRangeFilter filter =
                new TimeInstantRangeFilter.Builder()
                        .setStartTime(Instant.now())
                        .setEndTime(Instant.now().plusMillis(3000))
                        .build();
        StepsRecord testRecord = getCompleteStepsRecord();
        TestUtils.insertRecords(Collections.singletonList(testRecord));
        List<StepsRecord> newStepsRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setTimeRangeFilter(filter)
                                .build());
        assertThat(newStepsRecords.size()).isEqualTo(1);
        assertThat(newStepsRecords.get(newStepsRecords.size() - 1).equals(testRecord)).isTrue();
    }

    static StepsRecord getBaseStepsRecord(Instant time, ZoneOffset zoneOffset, int value) {
        return new StepsRecord.Builder(
                        new Metadata.Builder().build(),
                        time,
                        time.plus(1, ChronoUnit.SECONDS),
                        value)
                .setStartZoneOffset(zoneOffset)
                .setEndZoneOffset(zoneOffset)
                .build();
    }

    @Test
    public void testReadStepsRecordUsingFilters_dataFilter_correct() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        List<StepsRecord> oldStepsRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .addDataOrigins(
                                        new DataOrigin.Builder()
                                                .setPackageName(context.getPackageName())
                                                .build())
                                .build());
        StepsRecord testRecord = getCompleteStepsRecord();
        TestUtils.insertRecords(Collections.singletonList(testRecord));
        List<StepsRecord> newStepsRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .addDataOrigins(
                                        new DataOrigin.Builder()
                                                .setPackageName(context.getPackageName())
                                                .build())
                                .build());
        assertThat(newStepsRecords.size() - oldStepsRecords.size()).isEqualTo(1);
        StepsRecord newRecord = newStepsRecords.get(newStepsRecords.size() - 1);
        assertThat(newRecord.equals(testRecord)).isTrue();
    }

    @Test
    public void testReadStepsRecordUsingFilters_dataFilter_incorrect() throws InterruptedException {
        TestUtils.insertRecords(Collections.singletonList(getCompleteStepsRecord()));
        ReadRecordsRequestUsingFilters<StepsRecord> requestUsingFilters =
                new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                        .addDataOrigins(new DataOrigin.Builder().setPackageName("abc").build())
                        .setAscending(false)
                        .build();
        assertThat(requestUsingFilters.getDataOrigins()).isNotNull();
        assertThat(requestUsingFilters.isAscending()).isFalse();
        List<StepsRecord> newStepsRecords = TestUtils.readRecords(requestUsingFilters);
        assertThat(newStepsRecords.size()).isEqualTo(0);
    }

    @Test
    public void testReadStepsRecordUsingFilters_withPageSize() throws InterruptedException {
        List<Record> recordList =
                Arrays.asList(getStepsRecord_minusDays(1), getStepsRecord_minusDays(2));
        TestUtils.insertRecords(recordList);
        Pair<List<StepsRecord>, Long> newStepsRecords =
                TestUtils.readRecordsWithPagination(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setPageSize(1)
                                .build());
        assertThat(newStepsRecords.first.size()).isEqualTo(1);
    }

    @Test
    public void testReadStepsRecordUsingFilters_timeFilterLocal() throws InterruptedException {
        LocalDateTime recordTime = LocalDateTime.now(ZoneOffset.MIN);
        LocalTimeRangeFilter filter =
                new LocalTimeRangeFilter.Builder()
                        .setStartTime(recordTime.minus(1, ChronoUnit.SECONDS))
                        .setEndTime(recordTime.plus(1, ChronoUnit.SECONDS))
                        .build();
        StepsRecord testRecord =
                getBaseStepsRecord(recordTime.toInstant(ZoneOffset.MIN), ZoneOffset.MIN, 50);
        TestUtils.insertRecords(Collections.singletonList(testRecord));
        List<StepsRecord> newStepsRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setTimeRangeFilter(filter)
                                .build());
        assertThat(newStepsRecords.size()).isEqualTo(1);
        StepsRecord stepsRecord = newStepsRecords.get(newStepsRecords.size() - 1);
        assertThat(stepsRecord.getCount()).isEqualTo(50);
        assertThat(stepsRecord.getStartZoneOffset()).isEqualTo(ZoneOffset.MIN);
        assertThat(stepsRecord.getEndZoneOffset()).isEqualTo(ZoneOffset.MIN);

        TimeInstantRangeFilter timeInstantRangeFilter =
                new TimeInstantRangeFilter.Builder()
                        .setStartTime(
                                recordTime.minus(1, ChronoUnit.SECONDS).toInstant(ZoneOffset.MIN))
                        .setEndTime(
                                recordTime.plus(1, ChronoUnit.SECONDS).toInstant(ZoneOffset.MIN))
                        .build();

        newStepsRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setTimeRangeFilter(timeInstantRangeFilter)
                                .build());
        stepsRecord = newStepsRecords.get(newStepsRecords.size() - 1);
        assertThat(newStepsRecords.size()).isEqualTo(1);
        assertThat(stepsRecord.getCount()).isEqualTo(50);
        assertThat(stepsRecord.getStartZoneOffset()).isEqualTo(ZoneOffset.MIN);
        assertThat(stepsRecord.getEndZoneOffset()).isEqualTo(ZoneOffset.MIN);
    }

    @Test
    public void testReadStepsRecordUsingFilters_withPageToken() throws InterruptedException {
        List<Record> recordList =
                Arrays.asList(
                        getStepsRecord_minusDays(1),
                        getStepsRecord_minusDays(2),
                        getStepsRecord_minusDays(3),
                        getStepsRecord_minusDays(4));
        TestUtils.insertRecords(recordList);
        ReadRecordsRequestUsingFilters<StepsRecord> requestUsingFilters =
                new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                        .setPageSize(1)
                        .setAscending(true)
                        .build();
        assertThat(requestUsingFilters.isAscending()).isTrue();
        assertThat(requestUsingFilters.getPageSize()).isEqualTo(1);
        assertThat(requestUsingFilters.getTimeRangeFilter()).isNull();
        Pair<List<StepsRecord>, Long> oldStepsRecord =
                TestUtils.readRecordsWithPagination(requestUsingFilters);
        assertThat(oldStepsRecord.first.size()).isEqualTo(1);
        ReadRecordsRequestUsingFilters<StepsRecord> requestUsingFiltersNew =
                new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                        .setPageSize(1)
                        .setPageToken(oldStepsRecord.second)
                        .build();
        assertThat(requestUsingFiltersNew.getPageSize()).isEqualTo(1);
        assertThat(requestUsingFiltersNew.getPageToken()).isEqualTo(oldStepsRecord.second);
        assertThat(requestUsingFiltersNew.getTimeRangeFilter()).isNull();
        Pair<List<StepsRecord>, Long> newStepsRecords =
                TestUtils.readRecordsWithPagination(requestUsingFiltersNew);
        assertThat(newStepsRecords.first.size()).isEqualTo(1);
    }

    @Test
    public void testReadStepsRecordUsingFilters_withPageTokenReverse() throws InterruptedException {
        List<Record> recordList =
                Arrays.asList(
                        getStepsRecord_minusDays(1),
                        getStepsRecord_minusDays(2),
                        getStepsRecord_minusDays(3),
                        getStepsRecord_minusDays(4));
        TestUtils.insertRecords(recordList);
        Pair<List<StepsRecord>, Long> oldStepsRecord =
                TestUtils.readRecordsWithPagination(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setPageSize(1)
                                .setAscending(false)
                                .build());
        assertThat(oldStepsRecord.first.size()).isEqualTo(1);
        Pair<List<StepsRecord>, Long> newStepsRecords =
                TestUtils.readRecordsWithPagination(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setPageSize(1)
                                .setPageToken(oldStepsRecord.second)
                                .build());
        assertThat(newStepsRecords.first.size()).isEqualTo(1);
        assertThat(newStepsRecords.second).isNotEqualTo(oldStepsRecord.second);
        assertThat(newStepsRecords.second).isLessThan(oldStepsRecord.second);
    }

    @Test
    public void testStepsRecordUsingFilters_nextPageTokenEnd() throws InterruptedException {
        List<Record> recordList =
                Arrays.asList(TestUtils.getStepsRecord(), TestUtils.getStepsRecord());
        TestUtils.insertRecords(recordList);
        Pair<List<StepsRecord>, Long> oldStepsRecord =
                TestUtils.readRecordsWithPagination(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class).build());
        Pair<List<StepsRecord>, Long> newStepsRecord;
        while (oldStepsRecord.second != -1) {
            newStepsRecord =
                    TestUtils.readRecordsWithPagination(
                            new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                    .setPageToken(oldStepsRecord.second)
                                    .build());
            if (newStepsRecord.second != -1) {
                assertThat(newStepsRecord.second).isGreaterThan(oldStepsRecord.second);
            }
            oldStepsRecord = newStepsRecord;
        }
        assertThat(oldStepsRecord.second).isEqualTo(-1);
    }

    @Test
    public void testReadStepsRecordUsingFilters_withPageToken_NewOrder()
            throws InterruptedException {
        List<Record> recordList =
                Arrays.asList(
                        getStepsRecord_minusDays(1),
                        getStepsRecord_minusDays(2),
                        getStepsRecord_minusDays(3),
                        getStepsRecord_minusDays(4));
        TestUtils.insertRecords(recordList);
        Pair<List<StepsRecord>, Long> oldStepsRecord =
                TestUtils.readRecordsWithPagination(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setPageSize(1)
                                .setAscending(false)
                                .build());
        assertThat(oldStepsRecord.first.size()).isEqualTo(1);
        try {
            ReadRecordsRequestUsingFilters<StepsRecord> requestUsingFilters =
                    new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                            .setPageSize(1)
                            .setPageToken(oldStepsRecord.second)
                            .setAscending(true)
                            .build();
            TestUtils.readRecordsWithPagination(requestUsingFilters);
            Assert.fail(
                    "IllegalStateException  expected when both page token and page order is set");
        } catch (Exception exception) {
            assertThat(exception).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    public void testReadStepsRecordUsingFilters_withPageToken_SameOrder()
            throws InterruptedException {
        List<Record> recordList =
                Arrays.asList(
                        getStepsRecord_minusDays(1),
                        getStepsRecord_minusDays(2),
                        getStepsRecord_minusDays(3),
                        getStepsRecord_minusDays(4));
        TestUtils.insertRecords(recordList);
        Pair<List<StepsRecord>, Long> oldStepsRecord =
                TestUtils.readRecordsWithPagination(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setPageSize(1)
                                .setAscending(false)
                                .build());
        assertThat(oldStepsRecord.first.size()).isEqualTo(1);
        try {
            ReadRecordsRequestUsingFilters<StepsRecord> requestUsingFilters =
                    new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                            .setPageSize(1)
                            .setPageToken(oldStepsRecord.second)
                            .setAscending(false)
                            .build();
            TestUtils.readRecordsWithPagination(requestUsingFilters);
            Assert.fail(
                    "IllegalStateException  expected when both page token and page order is set");
        } catch (Exception exception) {
            assertThat(exception).isInstanceOf(IllegalStateException.class);
        }
    }

    @Test
    public void testReadStepsRecord_beforePermissionGrant() throws InterruptedException {
        Assume.assumeFalse(isHardwareAutomotive());
        List<Record> recordList =
                Arrays.asList(
                        getStepsRecord_minusDays(45),
                        getStepsRecord_minusDays(20),
                        getStepsRecord(10));
        TestUtils.insertRecords(recordList);
        List<StepsRecord> newStepsRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class).build());
        assertThat(newStepsRecords.size()).isEqualTo(2);
    }

    @Test
    public void testDeleteStepsRecord_no_filters() throws InterruptedException {
        String id = TestUtils.insertRecordAndGetId(getCompleteStepsRecord());
        TestUtils.verifyDeleteRecords(new DeleteUsingFiltersRequest.Builder().build());
        TestUtils.assertRecordNotFound(id, StepsRecord.class);
    }

    @Test
    public void testDeleteStepsRecord_time_filters() throws InterruptedException {
        TimeInstantRangeFilter timeRangeFilter =
                new TimeInstantRangeFilter.Builder()
                        .setStartTime(Instant.now())
                        .setEndTime(Instant.now().plusMillis(1000))
                        .build();
        String id = TestUtils.insertRecordAndGetId(getCompleteStepsRecord());
        TestUtils.verifyDeleteRecords(
                new DeleteUsingFiltersRequest.Builder()
                        .addRecordType(StepsRecord.class)
                        .setTimeRangeFilter(timeRangeFilter)
                        .build());
        TestUtils.assertRecordNotFound(id, StepsRecord.class);
    }

    @Test
    public void testReadStepsRecordUsingFiltersLocal_withPageSize() throws InterruptedException {
        LocalDateTime recordTime = LocalDateTime.now(ZoneOffset.MIN).minus(10, ChronoUnit.SECONDS);
        LocalTimeRangeFilter filter =
                new LocalTimeRangeFilter.Builder()
                        .setStartTime(recordTime.minus(2, ChronoUnit.SECONDS))
                        .setEndTime(recordTime.plus(2, ChronoUnit.SECONDS))
                        .build();
        List<Record> testRecord =
                List.of(
                        getBaseStepsRecord(
                                recordTime.plus(1, ChronoUnit.SECONDS).toInstant(ZoneOffset.MIN),
                                ZoneOffset.MIN,
                                20),
                        getBaseStepsRecord(
                                recordTime.toInstant(ZoneOffset.MIN), ZoneOffset.MIN, 50),
                        getBaseStepsRecord(
                                recordTime.minus(1, ChronoUnit.SECONDS).toInstant(ZoneOffset.MIN),
                                ZoneOffset.MIN,
                                70));
        TestUtils.insertRecords(testRecord);
        Pair<List<StepsRecord>, Long> newStepsRecords =
                TestUtils.readRecordsWithPagination(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setTimeRangeFilter(filter)
                                .setPageSize(1)
                                .build());
        assertThat(newStepsRecords.first.size()).isEqualTo(1);
        assertThat(newStepsRecords.first.get(0).getCount()).isEqualTo(70);
        newStepsRecords =
                TestUtils.readRecordsWithPagination(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setTimeRangeFilter(filter)
                                .setPageSize(1)
                                .setPageToken(newStepsRecords.second)
                                .build());
        assertThat(newStepsRecords.first.size()).isEqualTo(1);
        assertThat(newStepsRecords.first.get(0).getCount()).isEqualTo(50);
        newStepsRecords =
                TestUtils.readRecordsWithPagination(
                        new ReadRecordsRequestUsingFilters.Builder<>(StepsRecord.class)
                                .setTimeRangeFilter(filter)
                                .setPageSize(1)
                                .setPageToken(newStepsRecords.second)
                                .build());
        assertThat(newStepsRecords.first.size()).isEqualTo(1);
        assertThat(newStepsRecords.first.get(0).getCount()).isEqualTo(20);
        assertThat(newStepsRecords.second).isEqualTo(-1);
    }

    @Test
    public void testDeleteStepsRecord_time_filters_local() throws InterruptedException {
        LocalDateTime recordTime = LocalDateTime.now(ZoneOffset.MIN);
        LocalTimeRangeFilter timeRangeFilter =
                new LocalTimeRangeFilter.Builder()
                        .setStartTime(recordTime.minus(1, ChronoUnit.SECONDS))
                        .setEndTime(recordTime.plus(2, ChronoUnit.SECONDS))
                        .build();
        String id1 =
                TestUtils.insertRecordAndGetId(
                        getBaseStepsRecord(
                                recordTime.toInstant(ZoneOffset.MIN), ZoneOffset.MIN, 50));
        String id2 =
                TestUtils.insertRecordAndGetId(
                        getBaseStepsRecord(
                                recordTime.toInstant(ZoneOffset.MAX), ZoneOffset.MAX, 50));
        TestUtils.assertRecordFound(id1, StepsRecord.class);
        TestUtils.assertRecordFound(id2, StepsRecord.class);
        TestUtils.verifyDeleteRecords(
                new DeleteUsingFiltersRequest.Builder()
                        .addRecordType(StepsRecord.class)
                        .setTimeRangeFilter(timeRangeFilter)
                        .build());
        TestUtils.assertRecordNotFound(id1, StepsRecord.class);
        TestUtils.assertRecordNotFound(id2, StepsRecord.class);
    }

    @Test
    public void testDeleteStepsRecord_recordId_filters() throws InterruptedException {
        List<Record> records = List.of(getBaseStepsRecord(), getCompleteStepsRecord());
        TestUtils.insertRecords(records);

        for (Record record : records) {
            TestUtils.verifyDeleteRecords(
                    new DeleteUsingFiltersRequest.Builder()
                            .addRecordType(record.getClass())
                            .build());
            TestUtils.assertRecordNotFound(record.getMetadata().getId(), record.getClass());
        }
    }

    @Test
    public void testDeleteStepsRecord_dataOrigin_filters() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        String id = TestUtils.insertRecordAndGetId(getCompleteStepsRecord());
        TestUtils.verifyDeleteRecords(
                new DeleteUsingFiltersRequest.Builder()
                        .addDataOrigin(
                                new DataOrigin.Builder()
                                        .setPackageName(context.getPackageName())
                                        .build())
                        .build());
        TestUtils.assertRecordNotFound(id, StepsRecord.class);
    }

    @Test
    public void testDeleteStepsRecord_dataOrigin_filter_incorrect() throws InterruptedException {
        String id = TestUtils.insertRecordAndGetId(getCompleteStepsRecord());
        TestUtils.verifyDeleteRecords(
                new DeleteUsingFiltersRequest.Builder()
                        .addDataOrigin(new DataOrigin.Builder().setPackageName("abc").build())
                        .build());
        TestUtils.assertRecordFound(id, StepsRecord.class);
    }

    @Test
    public void testDeleteStepsRecord_usingIds() throws InterruptedException {
        List<Record> records = List.of(getBaseStepsRecord(), getCompleteStepsRecord());
        List<Record> insertedRecord = TestUtils.insertRecords(records);
        List<RecordIdFilter> recordIds = new ArrayList<>(records.size());
        for (Record record : insertedRecord) {
            recordIds.add(RecordIdFilter.fromId(record.getClass(), record.getMetadata().getId()));
        }
        for (RecordIdFilter recordIdFilter : recordIds) {
            assertThat(recordIdFilter.getClientRecordId()).isNull();
            assertThat(recordIdFilter.getId()).isNotNull();
            assertThat(recordIdFilter.getRecordType()).isEqualTo(StepsRecord.class);
        }

        TestUtils.verifyDeleteRecords(recordIds);
        for (Record record : records) {
            TestUtils.assertRecordNotFound(record.getMetadata().getId(), record.getClass());
        }
    }

    @Test
    public void testDeleteStepsRecord_usingInvalidClientIds() throws InterruptedException {
        List<Record> records = List.of(getBaseStepsRecord(), getCompleteStepsRecord());
        List<Record> insertedRecord = TestUtils.insertRecords(records);
        List<RecordIdFilter> recordIds = new ArrayList<>(records.size());
        for (Record record : insertedRecord) {
            recordIds.add(
                    RecordIdFilter.fromClientRecordId(
                            record.getClass(), record.getMetadata().getId()));
        }
        for (RecordIdFilter recordIdFilter : recordIds) {
            assertThat(recordIdFilter.getClientRecordId()).isNotNull();
            assertThat(recordIdFilter.getId()).isNull();
            assertThat(recordIdFilter.getRecordType()).isEqualTo(StepsRecord.class);
        }

        TestUtils.verifyDeleteRecords(recordIds);
        for (Record record : records) {
            TestUtils.assertRecordFound(record.getMetadata().getId(), record.getClass());
        }
    }

    @Test
    public void testDeleteStepsRecord_time_range() throws InterruptedException {
        TimeInstantRangeFilter timeRangeFilter =
                new TimeInstantRangeFilter.Builder()
                        .setStartTime(Instant.now())
                        .setEndTime(Instant.now().plusMillis(1000))
                        .build();
        String id = TestUtils.insertRecordAndGetId(getCompleteStepsRecord());
        TestUtils.verifyDeleteRecords(StepsRecord.class, timeRangeFilter);
        TestUtils.assertRecordNotFound(id, StepsRecord.class);
    }

    @Test
    public void testZoneOffsets() {
        final ZoneOffset defaultZoneOffset =
                ZoneOffset.systemDefault().getRules().getOffset(Instant.now());
        final ZoneOffset startZoneOffset = ZoneOffset.UTC;
        final ZoneOffset endZoneOffset = ZoneOffset.MAX;
        StepsRecord.Builder builder =
                new StepsRecord.Builder(
                        new Metadata.Builder().build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        10);

        assertThat(builder.setStartZoneOffset(startZoneOffset).build().getStartZoneOffset())
                .isEqualTo(startZoneOffset);
        assertThat(builder.setEndZoneOffset(endZoneOffset).build().getEndZoneOffset())
                .isEqualTo(endZoneOffset);
        assertThat(builder.clearStartZoneOffset().build().getStartZoneOffset())
                .isEqualTo(defaultZoneOffset);
        assertThat(builder.clearEndZoneOffset().build().getEndZoneOffset())
                .isEqualTo(defaultZoneOffset);
    }

    private void readStepsRecordUsingClientId(List<Record> insertedRecord)
            throws InterruptedException {
        ReadRecordsRequestUsingIds.Builder<StepsRecord> request =
                new ReadRecordsRequestUsingIds.Builder<>(StepsRecord.class);
        for (Record record : insertedRecord) {
            request.addClientRecordId(record.getMetadata().getClientRecordId());
        }
        ReadRecordsRequestUsingIds<StepsRecord> readRequest = request.build();
        assertThat(readRequest.getRecordType()).isNotNull();
        assertThat(readRequest.getRecordType()).isEqualTo(StepsRecord.class);
        List<StepsRecord> result = TestUtils.readRecords(readRequest);
        assertThat(result.size()).isEqualTo(insertedRecord.size());
        assertThat(result).containsExactlyElementsIn(insertedRecord);
    }

    @Test
    public void testAggregation_StepsCountTotal() throws Exception {
        List<Record> records =
                Arrays.asList(getStepsRecord(1000, 1, 1), getStepsRecord(1000, 2, 1));
        AggregateRecordsRequest<Long> aggregateRecordsRequest =
                new AggregateRecordsRequest.Builder<Long>(
                                new TimeInstantRangeFilter.Builder()
                                        .setStartTime(Instant.ofEpochMilli(0))
                                        .setEndTime(Instant.now().plus(1, ChronoUnit.DAYS))
                                        .build())
                        .addAggregationType(STEPS_COUNT_TOTAL)
                        .build();
        assertThat(aggregateRecordsRequest.getAggregationTypes()).isNotNull();
        assertThat(aggregateRecordsRequest.getTimeRangeFilter()).isNotNull();
        assertThat(aggregateRecordsRequest.getDataOriginsFilters()).isNotNull();
        AggregateRecordsResponse<Long> oldResponse =
                TestUtils.getAggregateResponse(aggregateRecordsRequest, records);
        List<Record> recordNew =
                Arrays.asList(getStepsRecord(1000, 3, 1), getStepsRecord(1000, 4, 1));
        AggregateRecordsResponse<Long> newResponse =
                TestUtils.getAggregateResponse(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new TimeInstantRangeFilter.Builder()
                                                .setStartTime(Instant.ofEpochMilli(0))
                                                .setEndTime(Instant.now().plus(1, ChronoUnit.DAYS))
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        recordNew);
        assertThat(newResponse.get(STEPS_COUNT_TOTAL)).isNotNull();
        assertThat(newResponse.get(STEPS_COUNT_TOTAL))
                .isEqualTo(oldResponse.get(STEPS_COUNT_TOTAL) + 2000);
        Set<DataOrigin> newDataOrigin = newResponse.getDataOrigins(STEPS_COUNT_TOTAL);
        for (DataOrigin itr : newDataOrigin) {
            assertThat(itr.getPackageName()).isEqualTo("android.healthconnect.cts");
        }
        Set<DataOrigin> oldDataOrigin = oldResponse.getDataOrigins(STEPS_COUNT_TOTAL);
        for (DataOrigin itr : oldDataOrigin) {
            assertThat(itr.getPackageName()).isEqualTo("android.healthconnect.cts");
        }
        StepsRecord record = getStepsRecord(1000, 5, 1);
        List<Record> recordNew2 = Arrays.asList(record, record);
        AggregateRecordsResponse<Long> newResponse2 =
                TestUtils.getAggregateResponse(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new TimeInstantRangeFilter.Builder()
                                                .setStartTime(Instant.ofEpochMilli(0))
                                                .setEndTime(Instant.now().plus(1, ChronoUnit.DAYS))
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        recordNew2);
        assertThat(newResponse2.get(STEPS_COUNT_TOTAL)).isNotNull();
        assertThat(newResponse2.get(STEPS_COUNT_TOTAL))
                .isEqualTo(newResponse.get(STEPS_COUNT_TOTAL) + 1000);
    }

    @Test
    public void testInsertWithClientVersion() throws InterruptedException {
        List<Record> records = List.of(getStepsRecordWithClientVersion(10, 1, "testId"));
        final String id = TestUtils.insertRecords(records).get(0).getMetadata().getId();
        ReadRecordsRequestUsingIds<StepsRecord> request =
                new ReadRecordsRequestUsingIds.Builder<>(StepsRecord.class)
                        .addClientRecordId("testId")
                        .build();
        StepsRecord stepsRecord = TestUtils.readRecords(request).get(0);
        assertThat(stepsRecord.getCount()).isEqualTo(10);
        records = List.of(getStepsRecordWithClientVersion(20, 2, "testId"));
        TestUtils.insertRecords(records);

        stepsRecord = TestUtils.readRecords(request).get(0);
        assertThat(stepsRecord.getMetadata().getId()).isEqualTo(id);
        assertThat(stepsRecord.getCount()).isEqualTo(20);
        records = List.of(getStepsRecordWithClientVersion(30, 1, "testId"));
        TestUtils.insertRecords(records);
        stepsRecord = TestUtils.readRecords(request).get(0);
        assertThat(stepsRecord.getMetadata().getId()).isEqualTo(id);
        assertThat(stepsRecord.getCount()).isEqualTo(20);
    }

    @Test
    public void testAggregation_recordStartsBeforeAggWindow_returnsRescaledStepsCountInResult()
            throws Exception {
        Instant start = Instant.now().minus(1, ChronoUnit.DAYS);
        List<Record> record =
                Arrays.asList(
                        new StepsRecord.Builder(
                                        new Metadata.Builder().build(),
                                        start,
                                        start.plus(1, ChronoUnit.HOURS),
                                        600)
                                .build());
        AggregateRecordsRequest<Long> request =
                new AggregateRecordsRequest.Builder<Long>(
                                new TimeInstantRangeFilter.Builder()
                                        .setStartTime(start.plus(10, ChronoUnit.MINUTES))
                                        .setEndTime(start.plus(1, ChronoUnit.DAYS))
                                        .build())
                        .addAggregationType(STEPS_COUNT_TOTAL)
                        .build();

        AggregateRecordsResponse<Long> response = TestUtils.getAggregateResponse(request, record);
        assertThat(response.get(STEPS_COUNT_TOTAL)).isEqualTo(500);
        assertThat(response.getZoneOffset(STEPS_COUNT_TOTAL))
                .isEqualTo(ZoneOffset.systemDefault().getRules().getOffset(Instant.now()));
    }

    @Test
    public void testStepsCountAggregation_groupByDurationWithInstantFilter() throws Exception {
        Instant end = Instant.now();
        Instant start = end.minus(5, ChronoUnit.DAYS);
        List<Record> records =
                Arrays.asList(
                        getStepsRecord(1000, 1, 1),
                        getStepsRecord(1000, 2, 1),
                        getStepsRecord(1000, 3, 1),
                        getStepsRecord(1000, 4, 1),
                        getStepsRecord(1000, 5, 1));
        TestUtils.insertRecords(records);
        List<AggregateRecordsGroupedByDurationResponse<Long>> responses =
                TestUtils.getAggregateResponseGroupByDuration(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new TimeInstantRangeFilter.Builder()
                                                .setStartTime(start)
                                                .setEndTime(end)
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        Duration.ofDays(1));
        assertThat(responses.size()).isEqualTo(5);
    }

    @Test
    public void testStepsCountAggregation_groupByDuration() throws Exception {
        Instant end = Instant.now();
        Instant start = end.minus(3, ChronoUnit.DAYS);
        insertStepsRecordWithDelay(1000, 3);

        List<AggregateRecordsGroupedByDurationResponse<Long>> responses =
                TestUtils.getAggregateResponseGroupByDuration(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new TimeInstantRangeFilter.Builder()
                                                .setStartTime(start)
                                                .setEndTime(end)
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        Duration.ofDays(1));
        assertThat(responses.size()).isEqualTo(3);
        for (AggregateRecordsGroupedByDurationResponse<Long> response : responses) {
            assertThat(response.get(STEPS_COUNT_TOTAL)).isNotNull();
            assertThat(response.get(STEPS_COUNT_TOTAL)).isEqualTo(1000);
            assertThat(response.getZoneOffset(STEPS_COUNT_TOTAL))
                    .isEqualTo(ZoneOffset.systemDefault().getRules().getOffset(Instant.now()));
        }
    }

    @Test
    public void testAggregation_insertForEveryHour_returnsAggregateForHourAndHalfHours()
            throws Exception {
        Instant start = Instant.now().minus(1, ChronoUnit.DAYS);
        Instant end = Instant.now();
        for (int i = 0; i < 10; i++) {
            Instant st = start.plus(i, ChronoUnit.HOURS);
            List<Record> records =
                    Arrays.asList(
                            new StepsRecord.Builder(
                                            new Metadata.Builder().build(),
                                            st,
                                            st.plus(1, ChronoUnit.HOURS),
                                            1000)
                                    .build());
            TestUtils.insertRecords(records);
            Thread.sleep(100);
        }

        start = start.plus(30, ChronoUnit.MINUTES);
        List<AggregateRecordsGroupedByDurationResponse<Long>> responses =
                TestUtils.getAggregateResponseGroupByDuration(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new TimeInstantRangeFilter.Builder()
                                                .setStartTime(start)
                                                .setEndTime(end)
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        Duration.ofHours(1));
        assertThat(responses.size()).isEqualTo(24);
        for (int i = 0; i < responses.size(); i++) {
            AggregateRecordsGroupedByDurationResponse<Long> response = responses.get(i);
            if (i > 9) {
                assertThat(response.get(STEPS_COUNT_TOTAL)).isNull();
            } else if (i == 9) {
                assertThat(response.get(STEPS_COUNT_TOTAL)).isEqualTo(500);
            } else {
                assertThat(response.get(STEPS_COUNT_TOTAL)).isEqualTo(1000);
            }
        }
    }

    @Test
    public void testAggregation_groupByDurationInstant_halfSizeGroupResultIsCorrect()
            throws Exception {
        Instant end = Instant.now();
        TestUtils.insertRecords(List.of(getStepsRecord(end, 100, 1, 2)));

        List<AggregateRecordsGroupedByDurationResponse<Long>> responses =
                TestUtils.getAggregateResponseGroupByDuration(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new TimeInstantRangeFilter.Builder()
                                                .setStartTime(end.minus(24, ChronoUnit.HOURS))
                                                .setEndTime(
                                                        end.minus(22, ChronoUnit.HOURS)
                                                                .minus(30, ChronoUnit.MINUTES))
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        Duration.ofHours(1));
        assertThat(responses.size()).isEqualTo(2);
        assertThat(responses.get(0).get(STEPS_COUNT_TOTAL)).isEqualTo(50);
        assertThat(responses.get(1).get(STEPS_COUNT_TOTAL)).isEqualTo(25);
    }

    @Test
    public void testAggregation_StepsCountTotal_withDuplicateEntry() throws Exception {
        List<Record> records =
                Arrays.asList(getStepsRecord(1000, 1, 1), getStepsRecord(1000, 2, 1));
        AggregateRecordsResponse<Long> oldResponse =
                TestUtils.getAggregateResponse(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new TimeInstantRangeFilter.Builder()
                                                .setStartTime(Instant.ofEpochMilli(0))
                                                .setEndTime(Instant.now().plus(1, ChronoUnit.DAYS))
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        records);
        List<Record> recordNew =
                Arrays.asList(getStepsRecord(1000, 3, 1), getStepsRecord(1000, 3, 1));
        AggregateRecordsResponse<Long> newResponse =
                TestUtils.getAggregateResponse(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new TimeInstantRangeFilter.Builder()
                                                .setStartTime(Instant.ofEpochMilli(0))
                                                .setEndTime(Instant.now().plus(1, ChronoUnit.DAYS))
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        recordNew);
        assertThat(newResponse.get(STEPS_COUNT_TOTAL)).isNotNull();
        assertThat(newResponse.get(STEPS_COUNT_TOTAL))
                .isEqualTo(oldResponse.get(STEPS_COUNT_TOTAL) + 1000);
        Set<DataOrigin> newDataOrigin = newResponse.getDataOrigins(STEPS_COUNT_TOTAL);
        for (DataOrigin itr : newDataOrigin) {
            assertThat(itr.getPackageName()).isEqualTo("android.healthconnect.cts");
        }
        Set<DataOrigin> oldDataOrigin = oldResponse.getDataOrigins(STEPS_COUNT_TOTAL);
        for (DataOrigin itr : oldDataOrigin) {
            assertThat(itr.getPackageName()).isEqualTo("android.healthconnect.cts");
        }
    }

    @Test
    public void testUpdateRecords_validInput_dataBaseUpdatedSuccessfully()
            throws InterruptedException {

        List<Record> insertedRecords =
                TestUtils.insertRecords(
                        Arrays.asList(getCompleteStepsRecord(), getCompleteStepsRecord()));

        // read inserted records and verify that the data is same as inserted.
        readStepsRecordUsingIds(insertedRecords);

        // Generate a new set of records that will be used to perform the update operation.
        List<Record> updateRecords =
                Arrays.asList(getCompleteStepsRecord(), getCompleteStepsRecord());

        // Modify the uid of the updateRecords to the uuid that was present in the insert records.
        for (int itr = 0; itr < updateRecords.size(); itr++) {
            updateRecords.set(
                    itr,
                    getStepsRecord_update(
                            updateRecords.get(itr),
                            insertedRecords.get(itr).getMetadata().getId(),
                            insertedRecords.get(itr).getMetadata().getClientRecordId()));
        }

        TestUtils.updateRecords(updateRecords);

        // assert the inserted data has been modified by reading the data.
        readStepsRecordUsingIds(updateRecords);
    }

    @Test
    public void testUpdateRecords_invalidInputRecords_noChangeInDataBase()
            throws InterruptedException {
        List<Record> insertedRecords =
                TestUtils.insertRecords(
                        Arrays.asList(getCompleteStepsRecord(), getCompleteStepsRecord()));

        // read inserted records and verify that the data is same as inserted.
        readStepsRecordUsingIds(insertedRecords);

        // Generate a second set of records that will be used to perform the update operation.
        List<Record> updateRecords =
                Arrays.asList(getCompleteStepsRecord(), getCompleteStepsRecord());

        // Modify the Uid of the updateRecords to the UUID that was present in the insert records,
        // leaving out alternate records so that they have a new UUID which is not present in the
        // dataBase.
        for (int itr = 0; itr < updateRecords.size(); itr++) {
            updateRecords.set(
                    itr,
                    getStepsRecord_update(
                            updateRecords.get(itr),
                            itr % 2 == 0
                                    ? insertedRecords.get(itr).getMetadata().getId()
                                    : UUID.randomUUID().toString(),
                            itr % 2 == 0
                                    ? insertedRecords.get(itr).getMetadata().getId()
                                    : UUID.randomUUID().toString()));
        }

        try {
            TestUtils.updateRecords(updateRecords);
            Assert.fail("Expected to fail due to invalid records ids.");
        } catch (HealthConnectException exception) {
            assertThat(exception.getErrorCode()).isEqualTo(ERROR_INVALID_ARGUMENT);
        }

        // assert the inserted data has not been modified by reading the data.
        readStepsRecordUsingIds(insertedRecords);
    }

    @Test
    public void testUpdateRecords_recordWithInvalidPackageName_noChangeInDataBase()
            throws InterruptedException {
        List<Record> insertedRecords =
                TestUtils.insertRecords(
                        Arrays.asList(getCompleteStepsRecord(), getCompleteStepsRecord()));

        // read inserted records and verify that the data is same as inserted.
        readStepsRecordUsingIds(insertedRecords);

        // Generate a second set of records that will be used to perform the update operation.
        List<Record> updateRecords =
                Arrays.asList(getCompleteStepsRecord(), getCompleteStepsRecord());

        // Modify the Uuid of the updateRecords to the uuid that was present in the insert records.
        for (int itr = 0; itr < updateRecords.size(); itr++) {
            updateRecords.set(
                    itr,
                    getStepsRecord_update(
                            updateRecords.get(itr),
                            insertedRecords.get(itr).getMetadata().getId(),
                            insertedRecords.get(itr).getMetadata().getClientRecordId()));
            //             adding an entry with invalid packageName.
            updateRecords.set(itr, getCompleteStepsRecord());
        }

        try {
            TestUtils.updateRecords(updateRecords);
            Assert.fail("Expected to fail due to invalid package.");
        } catch (Exception exception) {
            // verify that the testcase failed due to invalid argument exception.
            assertThat(exception).isNotNull();
        }

        // assert the inserted data has not been modified by reading the data.
        readStepsRecordUsingIds(insertedRecords);
    }

    @Test
    public void testInsertAndDeleteRecord_changelogs() throws InterruptedException {
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

        List<Record> testRecord = Collections.singletonList(getCompleteStepsRecord());
        TestUtils.insertRecords(testRecord);
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(1);
        assertThat(
                        response.getUpsertedRecords().stream()
                                .map(Record::getMetadata)
                                .map(Metadata::getId)
                                .toList())
                .containsExactlyElementsIn(
                        testRecord.stream().map(Record::getMetadata).map(Metadata::getId).toList());
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);

        TestUtils.verifyDeleteRecords(
                new DeleteUsingFiltersRequest.Builder().addRecordType(StepsRecord.class).build());
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getDeletedLogs()).isEmpty();
    }

    private void insertStepsRecordWithDelay(long delayInMillis, int times)
            throws InterruptedException {
        for (int i = 0; i < times; i++) {
            List<Record> records =
                    Arrays.asList(
                            getStepsRecord(1000, 1, 1),
                            getStepsRecord(1000, 2, 1),
                            getStepsRecord(1000, 3, 1));
            TestUtils.insertRecords(records);
            Thread.sleep(delayInMillis);
        }
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateStepsRecord_invalidValue() {
        new StepsRecord.Builder(
                        new Metadata.Builder().build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        1000001)
                .build();
    }

    @Test
    public void testAggregatePeriod_withLocalDateTime() throws Exception {
        testAggregationLocalTimeOffset(ZoneOffset.ofHours(-4));
        testAggregationLocalTimeOffset(ZoneOffset.MIN);
        testAggregationLocalTimeOffset(ZoneOffset.MAX);
        testAggregationLocalTimeOffset(ZoneOffset.UTC);
        testAggregationLocalTimeOffset(ZoneOffset.ofHours(4));
    }

    private void testAggregationLocalTimeOffset(ZoneOffset offset) throws InterruptedException {
        LocalDateTime endTimeLocal = LocalDateTime.now(offset);
        LocalDateTime startTimeLocal = endTimeLocal.minusDays(4);
        Instant endTimeInstant = endTimeLocal.toInstant(offset);
        insertFourStepsRecordsWithZoneOffset(endTimeInstant, offset);

        List<AggregateRecordsGroupedByPeriodResponse<Long>> responses =
                TestUtils.getAggregateResponseGroupByPeriod(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new LocalTimeRangeFilter.Builder()
                                                .setStartTime(startTimeLocal)
                                                .setEndTime(endTimeLocal)
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        Period.ofDays(1));

        assertThat(responses).hasSize(4);
        LocalDateTime groupBoundary = startTimeLocal;
        for (int i = 0; i < 4; i++) {
            assertThat(responses.get(i).get(STEPS_COUNT_TOTAL)).isEqualTo(10);
            assertThat(responses.get(i).getZoneOffset(STEPS_COUNT_TOTAL)).isEqualTo(offset);
            assertThat(responses.get(i).getStartTime().getDayOfYear())
                    .isEqualTo(groupBoundary.getDayOfYear());
            groupBoundary = groupBoundary.plusDays(1);
            assertThat(responses.get(i).getEndTime().getDayOfYear())
                    .isEqualTo(groupBoundary.getDayOfYear());
        }

        tearDown();
    }

    @Test
    public void testAggregatePeriod_withLocalDateTime_halfSizeGroupResultIsCorrect()
            throws Exception {
        Instant end = Instant.now();
        // Insert steps from -48 hours to -12 hours, 36 hours session
        TestUtils.insertRecords(List.of(getStepsRecord(end, 2160, 2, 36, ZoneOffset.UTC)));

        LocalDateTime endTimeLocal = LocalDateTime.ofInstant(end, ZoneOffset.UTC);
        List<AggregateRecordsGroupedByPeriodResponse<Long>> responses =
                TestUtils.getAggregateResponseGroupByPeriod(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new LocalTimeRangeFilter.Builder()
                                                .setStartTime(endTimeLocal.minusHours(60))
                                                .setEndTime(endTimeLocal.minusHours(24))
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        Period.ofDays(1));

        assertThat(responses).hasSize(2);
        // -60 hours to -36 hours, 12 hours intersection with the group
        assertThat(responses.get(0).get(STEPS_COUNT_TOTAL)).isEqualTo(720);
        // -36 hours to -24 hours, 12 hours intersection with the group
        assertThat(responses.get(1).get(STEPS_COUNT_TOTAL)).isEqualTo(720);
    }

    @Test
    public void testAggregateLocalFilter_minOffsetRecord() throws Exception {
        LocalDateTime endTimeLocal = LocalDateTime.now(ZoneOffset.UTC);
        Instant endTimeInstant = Instant.now();

        AggregateRecordsResponse<Long> response =
                TestUtils.getAggregateResponse(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new LocalTimeRangeFilter.Builder()
                                                .setStartTime(endTimeLocal.minusHours(25))
                                                .setEndTime(endTimeLocal.minusHours(15))
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        List.of(
                                new StepsRecord.Builder(
                                                TestUtils.generateMetadata(),
                                                endTimeInstant.minusSeconds(500),
                                                endTimeInstant.minusSeconds(100),
                                                100)
                                        .setStartZoneOffset(ZoneOffset.MIN)
                                        .setEndZoneOffset(ZoneOffset.MIN)
                                        .build(),
                                new StepsRecord.Builder(
                                                TestUtils.generateMetadata(),
                                                endTimeInstant.minusSeconds(1000),
                                                endTimeInstant.minusSeconds(800),
                                                100)
                                        .setStartZoneOffset(ZoneOffset.MIN)
                                        .setEndZoneOffset(ZoneOffset.MIN)
                                        .build()));

        assertThat(response.get(STEPS_COUNT_TOTAL)).isEqualTo(200);
    }

    @Test
    public void testAggregateDuration_withLocalDateTime() throws Exception {
        testAggregateDurationWithLocalTimeForZoneOffset(ZoneOffset.MIN);
        testAggregateDurationWithLocalTimeForZoneOffset(ZoneOffset.ofHours(-4));
        testAggregateDurationWithLocalTimeForZoneOffset(ZoneOffset.UTC);
        testAggregateDurationWithLocalTimeForZoneOffset(ZoneOffset.ofHours(4));
        testAggregateDurationWithLocalTimeForZoneOffset(ZoneOffset.MAX);
    }

    private void testAggregateDurationWithLocalTimeForZoneOffset(ZoneOffset offset)
            throws Exception {
        Instant endTime = Instant.now();
        LocalDateTime endTimeLocal = LocalDateTime.ofInstant(endTime, offset);
        LocalDateTime startTimeLocal = endTimeLocal.minusDays(4);
        insertFourStepsRecordsWithZoneOffset(endTime, offset);

        List<AggregateRecordsGroupedByDurationResponse<Long>> responses =
                TestUtils.getAggregateResponseGroupByDuration(
                        new AggregateRecordsRequest.Builder<Long>(
                                        new LocalTimeRangeFilter.Builder()
                                                .setStartTime(startTimeLocal)
                                                .setEndTime(endTimeLocal)
                                                .build())
                                .addAggregationType(STEPS_COUNT_TOTAL)
                                .build(),
                        Duration.ofDays(1));

        assertThat(responses).hasSize(4);
        Instant groupBoundary = startTimeLocal.toInstant(ZoneOffset.UTC);
        for (int i = 0; i < 4; i++) {
            assertThat(responses.get(i).get(STEPS_COUNT_TOTAL)).isEqualTo(10);
            assertThat(responses.get(i).getZoneOffset(STEPS_COUNT_TOTAL)).isEqualTo(offset);
            assertThat(responses.get(i).getStartTime().getEpochSecond())
                    .isEqualTo(groupBoundary.getEpochSecond());
            groupBoundary = groupBoundary.plus(1, ChronoUnit.DAYS);
            assertThat(responses.get(i).getEndTime().getEpochSecond())
                    .isEqualTo(groupBoundary.getEpochSecond());
        }

        tearDown();
    }

    private void insertFourStepsRecordsWithZoneOffset(Instant endTime, ZoneOffset offset)
            throws InterruptedException {
        TestUtils.insertRecords(
                List.of(
                        getStepsRecord(endTime, 10, 1, 1, offset),
                        getStepsRecord(endTime, 10, 2, 1, offset),
                        getStepsRecord(endTime, 10, 3, 1, offset),
                        getStepsRecord(endTime, 10, 4, 1, offset)));
    }

    StepsRecord getStepsRecordDuplicateEntry(
            StepsRecord recordToUpdate, StepsRecord duplicateRecord) {
        Metadata metadata = recordToUpdate.getMetadata();
        Metadata metadataWithId =
                new Metadata.Builder()
                        .setId(metadata.getId())
                        .setClientRecordVersion(metadata.getClientRecordVersion())
                        .setDataOrigin(metadata.getDataOrigin())
                        .setDevice(metadata.getDevice())
                        .setLastModifiedTime(metadata.getLastModifiedTime())
                        .build();
        return new StepsRecord.Builder(
                        metadataWithId,
                        duplicateRecord.getStartTime(),
                        duplicateRecord.getEndTime(),
                        20)
                .setStartZoneOffset(ZoneOffset.systemDefault().getRules().getOffset(Instant.now()))
                .setEndZoneOffset(ZoneOffset.systemDefault().getRules().getOffset(Instant.now()))
                .build();
    }

    static void readStepsRecordUsingIds(List<Record> recordList) throws InterruptedException {
        ReadRecordsRequestUsingIds.Builder<StepsRecord> request =
                new ReadRecordsRequestUsingIds.Builder<>(StepsRecord.class);
        for (Record record : recordList) {
            request.addId(record.getMetadata().getId());
        }
        ReadRecordsRequestUsingIds requestUsingIds = request.build();
        assertThat(requestUsingIds.getRecordType()).isEqualTo(StepsRecord.class);
        assertThat(requestUsingIds.getRecordIdFilters()).isNotNull();
        List<StepsRecord> result = TestUtils.readRecords(requestUsingIds);
        assertThat(result).hasSize(recordList.size());
        assertThat(result).containsExactlyElementsIn(recordList);
    }

    static StepsRecord getStepsRecord_update(Record record, String id, String clientRecordId) {
        Metadata metadata = record.getMetadata();
        Metadata metadataWithId =
                new Metadata.Builder()
                        .setId(id)
                        .setClientRecordId(clientRecordId)
                        .setClientRecordVersion(metadata.getClientRecordVersion())
                        .setDataOrigin(metadata.getDataOrigin())
                        .setDevice(metadata.getDevice())
                        .setLastModifiedTime(metadata.getLastModifiedTime())
                        .build();
        return new StepsRecord.Builder(
                        metadataWithId, Instant.now(), Instant.now().plusMillis(2000), 20)
                .setStartZoneOffset(ZoneOffset.systemDefault().getRules().getOffset(Instant.now()))
                .setEndZoneOffset(ZoneOffset.systemDefault().getRules().getOffset(Instant.now()))
                .build();
    }

    static StepsRecord getBaseStepsRecord() {
        return new StepsRecord.Builder(
                        new Metadata.Builder().build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        10)
                .build();
    }

    static StepsRecord getStepsRecord(int count) {
        return new StepsRecord.Builder(
                        new Metadata.Builder().build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        count)
                .build();
    }

    static StepsRecord getStepsRecord(int count, int daysPast, int durationInHours) {
        return getStepsRecord(Instant.now(), count, daysPast, durationInHours);
    }

    static StepsRecord getStepsRecord(Instant time, int count, int daysPast, int durationInHours) {
        return getStepsRecord(time, count, daysPast, durationInHours, null);
    }

    static StepsRecord getStepsRecord(
            Instant time, int count, int daysPast, int durationInHours, ZoneOffset offset) {
        StepsRecord.Builder builder =
                new StepsRecord.Builder(
                        new Metadata.Builder().build(),
                        time.minus(daysPast, ChronoUnit.DAYS),
                        time.minus(daysPast, ChronoUnit.DAYS)
                                .plus(durationInHours, ChronoUnit.HOURS),
                        count);
        if (offset != null) {
            builder.setStartZoneOffset(offset).setEndZoneOffset(offset);
        }
        return builder.build();
    }

    static StepsRecord getCompleteStepsRecord() {
        Device device =
                new Device.Builder().setManufacturer("google").setModel("Pixel").setType(1).build();
        DataOrigin dataOrigin =
                new DataOrigin.Builder().setPackageName("android.healthconnect.cts").build();

        Metadata.Builder testMetadataBuilder = new Metadata.Builder();
        testMetadataBuilder.setDevice(device).setDataOrigin(dataOrigin);
        testMetadataBuilder.setClientRecordId("SR" + Math.random());
        testMetadataBuilder.setRecordingMethod(RECORDING_METHOD_ACTIVELY_RECORDED);
        Metadata testMetaData = testMetadataBuilder.build();
        assertThat(testMetaData.getRecordingMethod()).isEqualTo(RECORDING_METHOD_ACTIVELY_RECORDED);
        return new StepsRecord.Builder(
                        testMetaData, Instant.now(), Instant.now().plusMillis(1000), 10)
                .build();
    }

    static StepsRecord getStepsRecordWithClientVersion(
            int steps, int version, String clientRecordId) {
        Metadata.Builder testMetadataBuilder = new Metadata.Builder();
        testMetadataBuilder.setClientRecordId(clientRecordId);
        testMetadataBuilder.setClientRecordVersion(version);
        Metadata testMetaData = testMetadataBuilder.build();
        return new StepsRecord.Builder(
                        testMetaData, Instant.now(), Instant.now().plusMillis(1000), steps)
                .build();
    }

    static StepsRecord getStepsRecord_minusDays(int days) {
        return new StepsRecord.Builder(
                        new Metadata.Builder().build(),
                        Instant.now().minus(days, ChronoUnit.DAYS),
                        Instant.now().minus(days, ChronoUnit.DAYS).plusMillis(1000),
                        10)
                .build();
    }
}
