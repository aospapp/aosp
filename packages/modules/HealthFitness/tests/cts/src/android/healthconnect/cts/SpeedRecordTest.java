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
import android.health.connect.DeleteUsingFiltersRequest;
import android.health.connect.HealthConnectException;
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
import android.health.connect.datatypes.SpeedRecord;
import android.health.connect.datatypes.units.Velocity;
import android.platform.test.annotations.AppModeFull;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

@AppModeFull(reason = "HealthConnectManager is not accessible to instant apps")
@RunWith(AndroidJUnit4.class)
public class SpeedRecordTest {

    private static final String TAG = "SpeedRecordTest";

    @Before
    public void setUp() {
        // TODO(b/283737434): Update the HC code to use user aware context on permission change.
        // Temporary fix to set firstGrantTime for the correct user in HSUM.
        TestUtils.deleteAllStagedRemoteData();
    }

    @After
    public void tearDown() throws InterruptedException {
        TestUtils.verifyDeleteRecords(
                SpeedRecord.class,
                new TimeInstantRangeFilter.Builder()
                        .setStartTime(Instant.EPOCH)
                        .setEndTime(Instant.now())
                        .build());
        TestUtils.deleteAllStagedRemoteData();
    }

    @Test
    public void testInsertSpeedRecord() throws InterruptedException {
        TestUtils.insertRecords(Arrays.asList(getBaseSpeedRecord(), getCompleteSpeedRecord()));
    }

    @Test
    public void testReadSpeedRecord_usingIds() throws InterruptedException {
        testReadSpeedRecordIds();
    }

    @Test
    public void testReadSpeedRecord_invalidIds() throws InterruptedException {
        ReadRecordsRequestUsingIds<SpeedRecord> request =
                new ReadRecordsRequestUsingIds.Builder<>(SpeedRecord.class)
                        .addId(UUID.randomUUID().toString())
                        .build();
        List<SpeedRecord> result = TestUtils.readRecords(request);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    public void testReadSpeedRecord_usingClientRecordIds() throws InterruptedException {
        List<Record> recordList = Arrays.asList(getCompleteSpeedRecord(), getCompleteSpeedRecord());
        List<Record> insertedRecords = TestUtils.insertRecords(recordList);
        readSpeedRecordUsingClientId(insertedRecords);
    }

    @Test
    public void testReadSpeedRecord_invalidClientRecordIds() throws InterruptedException {
        ReadRecordsRequestUsingIds<SpeedRecord> request =
                new ReadRecordsRequestUsingIds.Builder<>(SpeedRecord.class)
                        .addClientRecordId("abc")
                        .build();
        List<SpeedRecord> result = TestUtils.readRecords(request);
        assertThat(result.size()).isEqualTo(0);
    }

    @Test
    public void testReadSpeedRecordUsingFilters_default() throws InterruptedException {
        List<SpeedRecord> oldSpeedRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(SpeedRecord.class).build());
        SpeedRecord testRecord = getCompleteSpeedRecord();
        TestUtils.insertRecords(Collections.singletonList(testRecord));
        List<SpeedRecord> newSpeedRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(SpeedRecord.class).build());
        assertThat(newSpeedRecords.size()).isEqualTo(oldSpeedRecords.size() + 1);
        assertThat(newSpeedRecords.get(newSpeedRecords.size() - 1).equals(testRecord)).isTrue();
    }

    @Test
    public void testReadSpeedRecordUsingFilters_timeFilter() throws InterruptedException {
        TimeInstantRangeFilter filter =
                new TimeInstantRangeFilter.Builder()
                        .setStartTime(Instant.now())
                        .setEndTime(Instant.now().plusMillis(3000))
                        .build();
        SpeedRecord testRecord = getCompleteSpeedRecord();
        TestUtils.insertRecords(Collections.singletonList(testRecord));
        List<SpeedRecord> newSpeedRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(SpeedRecord.class)
                                .setTimeRangeFilter(filter)
                                .build());
        assertThat(newSpeedRecords.size()).isEqualTo(1);
        assertThat(newSpeedRecords.get(newSpeedRecords.size() - 1).equals(testRecord)).isTrue();
    }

    @Test
    public void testReadSpeedRecordUsingFilters_dataFilter_correct() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        List<SpeedRecord> oldSpeedRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(SpeedRecord.class)
                                .addDataOrigins(
                                        new DataOrigin.Builder()
                                                .setPackageName(context.getPackageName())
                                                .build())
                                .build());
        SpeedRecord testRecord = getCompleteSpeedRecord();
        TestUtils.insertRecords(Collections.singletonList(testRecord));
        List<SpeedRecord> newSpeedRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(SpeedRecord.class)
                                .addDataOrigins(
                                        new DataOrigin.Builder()
                                                .setPackageName(context.getPackageName())
                                                .build())
                                .build());
        assertThat(newSpeedRecords.size() - oldSpeedRecords.size()).isEqualTo(1);
        assertThat(newSpeedRecords.get(newSpeedRecords.size() - 1).equals(testRecord)).isTrue();
        SpeedRecord newRecord = newSpeedRecords.get(newSpeedRecords.size() - 1);
        assertThat(newRecord.equals(testRecord)).isTrue();
        for (int idx = 0; idx < newRecord.getSamples().size(); idx++) {
            assertThat(newRecord.getSamples().get(idx).getTime().toEpochMilli())
                    .isEqualTo(testRecord.getSamples().get(idx).getTime().toEpochMilli());
            assertThat(newRecord.getSamples().get(idx).getSpeed())
                    .isEqualTo(testRecord.getSamples().get(idx).getSpeed());
        }
    }

    @Test
    public void testReadSpeedRecordUsingFilters_dataFilter_incorrect() throws InterruptedException {
        TestUtils.insertRecords(Collections.singletonList(getCompleteSpeedRecord()));
        List<SpeedRecord> newSpeedRecords =
                TestUtils.readRecords(
                        new ReadRecordsRequestUsingFilters.Builder<>(SpeedRecord.class)
                                .addDataOrigins(
                                        new DataOrigin.Builder().setPackageName("abc").build())
                                .build());
        assertThat(newSpeedRecords.size()).isEqualTo(0);
    }

    @Test
    public void testDeleteSpeedRecord_no_filters() throws InterruptedException {
        String id = TestUtils.insertRecordAndGetId(getCompleteSpeedRecord());
        TestUtils.verifyDeleteRecords(new DeleteUsingFiltersRequest.Builder().build());
        TestUtils.assertRecordNotFound(id, SpeedRecord.class);
    }

    @Test
    public void testDeleteSpeedRecord_time_filters() throws InterruptedException {
        TimeInstantRangeFilter timeRangeFilter =
                new TimeInstantRangeFilter.Builder()
                        .setStartTime(Instant.now())
                        .setEndTime(Instant.now().plusMillis(1000))
                        .build();
        String id = TestUtils.insertRecordAndGetId(getCompleteSpeedRecord());
        TestUtils.verifyDeleteRecords(
                new DeleteUsingFiltersRequest.Builder()
                        .addRecordType(SpeedRecord.class)
                        .setTimeRangeFilter(timeRangeFilter)
                        .build());
        TestUtils.assertRecordNotFound(id, SpeedRecord.class);
    }

    @Test
    public void testDeleteSpeedRecord_recordId_filters() throws InterruptedException {
        List<Record> records = List.of(getBaseSpeedRecord(), getCompleteSpeedRecord());
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
    public void testDeleteSpeedRecord_dataOrigin_filters() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        String id = TestUtils.insertRecordAndGetId(getCompleteSpeedRecord());
        TestUtils.verifyDeleteRecords(
                new DeleteUsingFiltersRequest.Builder()
                        .addDataOrigin(
                                new DataOrigin.Builder()
                                        .setPackageName(context.getPackageName())
                                        .build())
                        .build());
        TestUtils.assertRecordNotFound(id, SpeedRecord.class);
    }

    @Test
    public void testDeleteSpeedRecord_dataOrigin_filter_incorrect() throws InterruptedException {
        String id = TestUtils.insertRecordAndGetId(getCompleteSpeedRecord());
        TestUtils.verifyDeleteRecords(
                new DeleteUsingFiltersRequest.Builder()
                        .addDataOrigin(new DataOrigin.Builder().setPackageName("abc").build())
                        .build());
        TestUtils.assertRecordFound(id, SpeedRecord.class);
    }

    @Test
    public void testDeleteSpeedRecord_usingIds() throws InterruptedException {
        List<Record> records = List.of(getBaseSpeedRecord(), getCompleteSpeedRecord());
        List<Record> insertedRecord = TestUtils.insertRecords(records);
        List<RecordIdFilter> recordIds = new ArrayList<>(records.size());
        for (Record record : insertedRecord) {
            recordIds.add(RecordIdFilter.fromId(record.getClass(), record.getMetadata().getId()));
        }

        TestUtils.verifyDeleteRecords(recordIds);
        for (Record record : records) {
            TestUtils.assertRecordNotFound(record.getMetadata().getId(), record.getClass());
        }
    }

    @Test
    public void testDeleteSpeedRecord_time_range() throws InterruptedException {
        TimeInstantRangeFilter timeRangeFilter =
                new TimeInstantRangeFilter.Builder()
                        .setStartTime(Instant.now())
                        .setEndTime(Instant.now().plusMillis(1000))
                        .build();
        String id = TestUtils.insertRecordAndGetId(getCompleteSpeedRecord());
        TestUtils.verifyDeleteRecords(SpeedRecord.class, timeRangeFilter);
        TestUtils.assertRecordNotFound(id, SpeedRecord.class);
    }

    @Test
    public void testZoneOffsets() {
        final ZoneOffset defaultZoneOffset =
                ZoneOffset.systemDefault().getRules().getOffset(Instant.now());
        final ZoneOffset startZoneOffset = ZoneOffset.UTC;
        final ZoneOffset endZoneOffset = ZoneOffset.MAX;
        SpeedRecord.Builder builder =
                new SpeedRecord.Builder(
                        new Metadata.Builder().build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        Collections.emptyList());

        assertThat(builder.setStartZoneOffset(startZoneOffset).build().getStartZoneOffset())
                .isEqualTo(startZoneOffset);
        assertThat(builder.setEndZoneOffset(endZoneOffset).build().getEndZoneOffset())
                .isEqualTo(endZoneOffset);
        assertThat(builder.clearStartZoneOffset().build().getStartZoneOffset())
                .isEqualTo(defaultZoneOffset);
        assertThat(builder.clearEndZoneOffset().build().getEndZoneOffset())
                .isEqualTo(defaultZoneOffset);
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
                                .addRecordType(SpeedRecord.class)
                                .build());
        ChangeLogsRequest changeLogsRequest =
                new ChangeLogsRequest.Builder(tokenResponse.getToken()).build();
        ChangeLogsResponse response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getUpsertedRecords().size()).isEqualTo(0);
        assertThat(response.getDeletedLogs().size()).isEqualTo(0);

        List<Record> testRecord = Collections.singletonList(getCompleteSpeedRecord());
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
                new DeleteUsingFiltersRequest.Builder().addRecordType(SpeedRecord.class).build());
        response = TestUtils.getChangeLogs(changeLogsRequest);
        assertThat(response.getDeletedLogs()).isEmpty();
    }

    private void testReadSpeedRecordIds() throws InterruptedException {
        List<Record> recordList = Arrays.asList(getCompleteSpeedRecord(), getCompleteSpeedRecord());
        readSpeedRecordUsingIds(recordList);
    }

    private void readSpeedRecordUsingClientId(List<Record> insertedRecord)
            throws InterruptedException {
        ReadRecordsRequestUsingIds.Builder<SpeedRecord> request =
                new ReadRecordsRequestUsingIds.Builder<>(SpeedRecord.class);
        for (Record record : insertedRecord) {
            request.addClientRecordId(record.getMetadata().getClientRecordId());
        }
        List<SpeedRecord> result = TestUtils.readRecords(request.build());
        assertThat(result.size()).isEqualTo(insertedRecord.size());
        assertThat(result).containsExactlyElementsIn(insertedRecord);
    }

    private void readSpeedRecordUsingIds(List<Record> recordList) throws InterruptedException {
        List<Record> insertedRecords = TestUtils.insertRecords(recordList);
        ReadRecordsRequestUsingIds.Builder<SpeedRecord> request =
                new ReadRecordsRequestUsingIds.Builder<>(SpeedRecord.class);
        for (Record record : insertedRecords) {
            request.addId(record.getMetadata().getId());
        }
        ReadRecordsRequestUsingIds requestUsingIds = request.build();
        assertThat(requestUsingIds.getRecordType()).isEqualTo(SpeedRecord.class);
        assertThat(requestUsingIds.getRecordIdFilters()).isNotNull();
        List<SpeedRecord> result = TestUtils.readRecords(requestUsingIds);
        assertThat(result).hasSize(insertedRecords.size());
        assertThat(result.containsAll(insertedRecords)).isTrue();
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateSpeedRecord_invalidValue() {
        new SpeedRecord.SpeedRecordSample(
                Velocity.fromMetersPerSecond(1000001), Instant.now().plusMillis(100));
    }

    @Test(expected = IllegalArgumentException.class)
    public void testCreateSpeedRecord_invalidSampleTime() {
        SpeedRecord.SpeedRecordSample speedRecord =
                new SpeedRecord.SpeedRecordSample(
                        Velocity.fromMetersPerSecond(10.0), Instant.now().plusMillis(100));
        ArrayList<SpeedRecord.SpeedRecordSample> speedRecords = new ArrayList<>();
        speedRecords.add(speedRecord);
        new SpeedRecord.Builder(
                        new Metadata.Builder().build(),
                        Instant.now(),
                        Instant.now().plusMillis(99),
                        speedRecords)
                .build();
    }

    @Test
    public void testUpdateRecords_validInput_dataBaseUpdatedSuccessfully()
            throws InterruptedException {

        List<Record> insertedRecords =
                TestUtils.insertRecords(
                        Arrays.asList(getCompleteSpeedRecord(), getCompleteSpeedRecord()));

        // read inserted records and verify that the data is same as inserted.
        readSpeedRecordUsingIds(insertedRecords);

        // Generate a new set of records that will be used to perform the update operation.
        List<Record> updateRecords =
                Arrays.asList(getCompleteSpeedRecord(), getCompleteSpeedRecord());

        // Modify the uid of the updateRecords to the uuid that was present in the insert records.
        for (int itr = 0; itr < updateRecords.size(); itr++) {
            updateRecords.set(
                    itr,
                    getSpeedRecord_update(
                            updateRecords.get(itr),
                            insertedRecords.get(itr).getMetadata().getId(),
                            insertedRecords.get(itr).getMetadata().getClientRecordId()));
        }

        TestUtils.updateRecords(updateRecords);

        // assert the inserted data has been modified by reading the data.
        readSpeedRecordUsingIds(updateRecords);
    }

    @Test
    public void testUpdateRecords_invalidInputRecords_noChangeInDataBase()
            throws InterruptedException {
        List<Record> insertedRecords =
                TestUtils.insertRecords(
                        Arrays.asList(getCompleteSpeedRecord(), getCompleteSpeedRecord()));

        // read inserted records and verify that the data is same as inserted.
        readSpeedRecordUsingIds(insertedRecords);

        // Generate a second set of records that will be used to perform the update operation.
        List<Record> updateRecords =
                Arrays.asList(getCompleteSpeedRecord(), getCompleteSpeedRecord());

        // Modify the Uid of the updateRecords to the UUID that was present in the insert records,
        // leaving out alternate records so that they have a new UUID which is not present in the
        // dataBase.
        for (int itr = 0; itr < updateRecords.size(); itr++) {
            updateRecords.set(
                    itr,
                    getSpeedRecord_update(
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
            assertThat(exception.getErrorCode())
                    .isEqualTo(HealthConnectException.ERROR_INVALID_ARGUMENT);
        }

        // assert the inserted data has not been modified by reading the data.
        readSpeedRecordUsingIds(insertedRecords);
    }

    @Test
    public void testUpdateRecords_recordWithInvalidPackageName_noChangeInDataBase()
            throws InterruptedException {
        List<Record> insertedRecords =
                TestUtils.insertRecords(
                        Arrays.asList(getCompleteSpeedRecord(), getCompleteSpeedRecord()));

        // read inserted records and verify that the data is same as inserted.
        readSpeedRecordUsingIds(insertedRecords);

        // Generate a second set of records that will be used to perform the update operation.
        List<Record> updateRecords =
                Arrays.asList(getCompleteSpeedRecord(), getCompleteSpeedRecord());

        // Modify the Uuid of the updateRecords to the uuid that was present in the insert records.
        for (int itr = 0; itr < updateRecords.size(); itr++) {
            updateRecords.set(
                    itr,
                    getSpeedRecord_update(
                            updateRecords.get(itr),
                            insertedRecords.get(itr).getMetadata().getId(),
                            insertedRecords.get(itr).getMetadata().getClientRecordId()));
            //             adding an entry with invalid packageName.
            updateRecords.set(itr, getCompleteSpeedRecord());
        }

        try {
            TestUtils.updateRecords(updateRecords);
            Assert.fail("Expected to fail due to invalid package.");
        } catch (Exception exception) {
            // verify that the testcase failed due to invalid argument exception.
            assertThat(exception).isNotNull();
        }

        // assert the inserted data has not been modified by reading the data.
        readSpeedRecordUsingIds(insertedRecords);
    }

    SpeedRecord getSpeedRecord_update(Record record, String id, String clientRecordId) {
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

        SpeedRecord.SpeedRecordSample speedRecordSample =
                new SpeedRecord.SpeedRecordSample(
                        Velocity.fromMetersPerSecond(8.0), Instant.now().plusMillis(100));

        return new SpeedRecord.Builder(
                        metadataWithId,
                        Instant.now(),
                        Instant.now().plusMillis(2000),
                        List.of(speedRecordSample, speedRecordSample))
                .setStartZoneOffset(ZoneOffset.systemDefault().getRules().getOffset(Instant.now()))
                .setEndZoneOffset(ZoneOffset.systemDefault().getRules().getOffset(Instant.now()))
                .build();
    }

    private static SpeedRecord getBaseSpeedRecord() {
        SpeedRecord.SpeedRecordSample speedRecord =
                new SpeedRecord.SpeedRecordSample(
                        Velocity.fromMetersPerSecond(10.0), Instant.now().plusMillis(100));
        ArrayList<SpeedRecord.SpeedRecordSample> speedRecords = new ArrayList<>();
        speedRecords.add(speedRecord);
        speedRecords.add(speedRecord);

        return new SpeedRecord.Builder(
                        new Metadata.Builder().build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        speedRecords)
                .build();
    }

    private static SpeedRecord getCompleteSpeedRecord() {

        Device device =
                new Device.Builder()
                        .setManufacturer("google")
                        .setModel("Pixel4a")
                        .setType(2)
                        .build();
        DataOrigin dataOrigin =
                new DataOrigin.Builder().setPackageName("android.healthconnect.cts").build();
        Metadata.Builder testMetadataBuilder = new Metadata.Builder();
        testMetadataBuilder.setDevice(device).setDataOrigin(dataOrigin);
        testMetadataBuilder.setClientRecordId("SPR" + Math.random());
        testMetadataBuilder.setRecordingMethod(Metadata.RECORDING_METHOD_ACTIVELY_RECORDED);

        SpeedRecord.SpeedRecordSample speedRecord =
                new SpeedRecord.SpeedRecordSample(
                        Velocity.fromMetersPerSecond(10.0), Instant.now().plusMillis(100));

        ArrayList<SpeedRecord.SpeedRecordSample> speedRecords = new ArrayList<>();
        speedRecords.add(speedRecord);
        speedRecords.add(speedRecord);

        return new SpeedRecord.Builder(
                        testMetadataBuilder.build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        speedRecords)
                .build();
    }
}
