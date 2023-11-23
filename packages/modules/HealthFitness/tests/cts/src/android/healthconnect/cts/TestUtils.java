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

import static android.health.connect.HealthDataCategory.ACTIVITY;
import static android.health.connect.HealthDataCategory.BODY_MEASUREMENTS;
import static android.health.connect.HealthDataCategory.CYCLE_TRACKING;
import static android.health.connect.HealthDataCategory.NUTRITION;
import static android.health.connect.HealthDataCategory.SLEEP;
import static android.health.connect.HealthDataCategory.VITALS;
import static android.health.connect.HealthPermissionCategory.BASAL_METABOLIC_RATE;
import static android.health.connect.HealthPermissionCategory.EXERCISE;
import static android.health.connect.HealthPermissionCategory.HEART_RATE;
import static android.health.connect.HealthPermissionCategory.STEPS;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_BASAL_METABOLIC_RATE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_HEART_RATE;
import static android.health.connect.datatypes.RecordTypeIdentifier.RECORD_TYPE_STEPS;

import static com.android.compatibility.common.util.FeatureUtil.AUTOMOTIVE_FEATURE;
import static com.android.compatibility.common.util.FeatureUtil.hasSystemFeature;
import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static com.google.common.truth.Truth.assertThat;

import android.app.UiAutomation;
import android.content.Context;
import android.health.connect.AggregateRecordsGroupedByDurationResponse;
import android.health.connect.AggregateRecordsGroupedByPeriodResponse;
import android.health.connect.AggregateRecordsRequest;
import android.health.connect.AggregateRecordsResponse;
import android.health.connect.ApplicationInfoResponse;
import android.health.connect.DeleteUsingFiltersRequest;
import android.health.connect.HealthConnectDataState;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthPermissionCategory;
import android.health.connect.HealthPermissions;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.ReadRecordsRequest;
import android.health.connect.ReadRecordsRequestUsingIds;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.RecordIdFilter;
import android.health.connect.RecordTypeInfoResponse;
import android.health.connect.TimeInstantRangeFilter;
import android.health.connect.accesslog.AccessLog;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.changelog.ChangeLogTokenResponse;
import android.health.connect.changelog.ChangeLogsRequest;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.datatypes.ActiveCaloriesBurnedRecord;
import android.health.connect.datatypes.AppInfo;
import android.health.connect.datatypes.BasalBodyTemperatureRecord;
import android.health.connect.datatypes.BasalMetabolicRateRecord;
import android.health.connect.datatypes.BloodGlucoseRecord;
import android.health.connect.datatypes.BloodPressureRecord;
import android.health.connect.datatypes.BodyFatRecord;
import android.health.connect.datatypes.BodyTemperatureRecord;
import android.health.connect.datatypes.BodyWaterMassRecord;
import android.health.connect.datatypes.BoneMassRecord;
import android.health.connect.datatypes.CervicalMucusRecord;
import android.health.connect.datatypes.CyclingPedalingCadenceRecord;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.DistanceRecord;
import android.health.connect.datatypes.ElevationGainedRecord;
import android.health.connect.datatypes.ExerciseLap;
import android.health.connect.datatypes.ExerciseRoute;
import android.health.connect.datatypes.ExerciseSegment;
import android.health.connect.datatypes.ExerciseSegmentType;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.ExerciseSessionType;
import android.health.connect.datatypes.FloorsClimbedRecord;
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.HeartRateVariabilityRmssdRecord;
import android.health.connect.datatypes.HeightRecord;
import android.health.connect.datatypes.HydrationRecord;
import android.health.connect.datatypes.IntermenstrualBleedingRecord;
import android.health.connect.datatypes.LeanBodyMassRecord;
import android.health.connect.datatypes.MenstruationFlowRecord;
import android.health.connect.datatypes.MenstruationPeriodRecord;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.NutritionRecord;
import android.health.connect.datatypes.OvulationTestRecord;
import android.health.connect.datatypes.OxygenSaturationRecord;
import android.health.connect.datatypes.PowerRecord;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.RespiratoryRateRecord;
import android.health.connect.datatypes.RestingHeartRateRecord;
import android.health.connect.datatypes.SexualActivityRecord;
import android.health.connect.datatypes.SleepSessionRecord;
import android.health.connect.datatypes.SpeedRecord;
import android.health.connect.datatypes.StepsCadenceRecord;
import android.health.connect.datatypes.StepsRecord;
import android.health.connect.datatypes.TotalCaloriesBurnedRecord;
import android.health.connect.datatypes.Vo2MaxRecord;
import android.health.connect.datatypes.WeightRecord;
import android.health.connect.datatypes.WheelchairPushesRecord;
import android.health.connect.datatypes.units.Length;
import android.health.connect.datatypes.units.Power;
import android.health.connect.migration.MigrationException;
import android.os.OutcomeReceiver;
import android.util.Log;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

public class TestUtils {
    public static final String MANAGE_HEALTH_DATA = HealthPermissions.MANAGE_HEALTH_DATA_PERMISSION;
    public static final Instant SESSION_START_TIME = Instant.now().minus(10, ChronoUnit.DAYS);
    public static final Instant SESSION_END_TIME =
            Instant.now().minus(10, ChronoUnit.DAYS).plus(1, ChronoUnit.HOURS);
    private static final String TAG = "HCTestUtils";

    public static boolean isHardwareAutomotive() {
        return hasSystemFeature(AUTOMOTIVE_FEATURE);
    }

    public static ChangeLogTokenResponse getChangeLogToken(ChangeLogTokenRequest request)
            throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChangeLogTokenResponse> response = new AtomicReference<>();
        AtomicReference<HealthConnectException> exceptionAtomicReference = new AtomicReference<>();
        service.getChangeLogToken(
                request,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(ChangeLogTokenResponse result) {
                        response.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException exception) {
                        Log.e(TAG, exception.getMessage());
                        exceptionAtomicReference.set(exception);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (exceptionAtomicReference.get() != null) {
            throw exceptionAtomicReference.get();
        }
        return response.get();
    }

    public static String insertRecordAndGetId(Record record) throws InterruptedException {
        return insertRecords(Collections.singletonList(record)).get(0).getMetadata().getId();
    }

    /**
     * Inserts records to the database.
     *
     * @param records records to insert
     * @return inserted records
     */
    public static List<Record> insertRecords(List<Record> records) throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        AtomicReference<List<Record>> response = new AtomicReference<>();
        AtomicReference<HealthConnectException> exceptionAtomicReference = new AtomicReference<>();
        service.insertRecords(
                records,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(InsertRecordsResponse result) {
                        response.set(result.getRecords());
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException exception) {
                        exceptionAtomicReference.set(exception);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (exceptionAtomicReference.get() != null) {
            throw exceptionAtomicReference.get();
        }
        assertThat(response.get()).hasSize(records.size());

        return response.get();
    }

    public static void updateRecords(List<Record> records) throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        AtomicReference<HealthConnectException> exceptionAtomicReference = new AtomicReference<>();
        service.updateRecords(
                records,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Void result) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException exception) {
                        exceptionAtomicReference.set(exception);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (exceptionAtomicReference.get() != null) {
            throw exceptionAtomicReference.get();
        }
    }

    public static ChangeLogsResponse getChangeLogs(ChangeLogsRequest changeLogsRequest)
            throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();

        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<ChangeLogsResponse> response = new AtomicReference<>();
        AtomicReference<HealthConnectException> healthConnectExceptionAtomicReference =
                new AtomicReference<>();
        service.getChangeLogs(
                changeLogsRequest,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(ChangeLogsResponse result) {
                        response.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException exception) {
                        healthConnectExceptionAtomicReference.set(exception);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
        if (healthConnectExceptionAtomicReference.get() != null) {
            throw healthConnectExceptionAtomicReference.get();
        }

        return response.get();
    }

    public static Device buildDevice() {
        return new Device.Builder()
                .setManufacturer("google")
                .setModel("Pixel4a")
                .setType(2)
                .build();
    }

    public static List<Record> getTestRecords() {
        return Arrays.asList(
                getStepsRecord(),
                getHeartRateRecord(),
                getBasalMetabolicRateRecord(),
                buildExerciseSession());
    }

    public static List<RecordAndIdentifier> getRecordsAndIdentifiers() {
        return Arrays.asList(
                new RecordAndIdentifier(RECORD_TYPE_STEPS, getStepsRecord()),
                new RecordAndIdentifier(RECORD_TYPE_HEART_RATE, getHeartRateRecord()),
                new RecordAndIdentifier(
                        RECORD_TYPE_BASAL_METABOLIC_RATE, getBasalMetabolicRateRecord()));
    }

    public static ExerciseRoute.Location buildLocationTimePoint(Instant startTime) {
        return new ExerciseRoute.Location.Builder(
                        Instant.ofEpochMilli(
                                (long) (startTime.toEpochMilli() + 10 + Math.random() * 50)),
                        Math.random() * 50,
                        Math.random() * 50)
                .build();
    }

    public static ExerciseRoute buildExerciseRoute() {
        return new ExerciseRoute(
                List.of(
                        buildLocationTimePoint(SESSION_START_TIME),
                        buildLocationTimePoint(SESSION_START_TIME),
                        buildLocationTimePoint(SESSION_START_TIME)));
    }

    public static StepsRecord getStepsRecord() {
        Context context = ApplicationProvider.getApplicationContext();
        Device device =
                new Device.Builder().setManufacturer("google").setModel("Pixel").setType(1).build();
        DataOrigin dataOrigin =
                new DataOrigin.Builder().setPackageName(context.getPackageName()).build();
        return new StepsRecord.Builder(
                        new Metadata.Builder()
                                .setDevice(device)
                                .setDataOrigin(dataOrigin)
                                .setClientRecordId("SR" + Math.random())
                                .build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        10)
                .build();
    }

    public static StepsRecord getStepsRecord(String id) {
        Context context = ApplicationProvider.getApplicationContext();
        Device device =
                new Device.Builder().setManufacturer("google").setModel("Pixel").setType(1).build();
        DataOrigin dataOrigin =
                new DataOrigin.Builder().setPackageName(context.getPackageName()).build();
        return new StepsRecord.Builder(
                        new Metadata.Builder()
                                .setDevice(device)
                                .setId(id)
                                .setDataOrigin(dataOrigin)
                                .build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        10)
                .build();
    }

    public static HeartRateRecord getHeartRateRecord() {
        Context context = ApplicationProvider.getApplicationContext();

        HeartRateRecord.HeartRateSample heartRateSample =
                new HeartRateRecord.HeartRateSample(72, Instant.now().plusMillis(100));
        ArrayList<HeartRateRecord.HeartRateSample> heartRateSamples = new ArrayList<>();
        heartRateSamples.add(heartRateSample);
        heartRateSamples.add(heartRateSample);
        Device device =
                new Device.Builder().setManufacturer("google").setModel("Pixel").setType(1).build();
        DataOrigin dataOrigin =
                new DataOrigin.Builder().setPackageName(context.getPackageName()).build();

        return new HeartRateRecord.Builder(
                        new Metadata.Builder()
                                .setDevice(device)
                                .setDataOrigin(dataOrigin)
                                .setClientRecordId("HR" + Math.random())
                                .build(),
                        Instant.now(),
                        Instant.now().plusMillis(500),
                        heartRateSamples)
                .build();
    }

    public static HeartRateRecord getHeartRateRecord(int heartRate) {
        HeartRateRecord.HeartRateSample heartRateSample =
                new HeartRateRecord.HeartRateSample(heartRate, Instant.now().plusMillis(100));
        ArrayList<HeartRateRecord.HeartRateSample> heartRateSamples = new ArrayList<>();
        heartRateSamples.add(heartRateSample);
        heartRateSamples.add(heartRateSample);

        return new HeartRateRecord.Builder(
                        new Metadata.Builder().build(),
                        Instant.now(),
                        Instant.now().plusMillis(500),
                        heartRateSamples)
                .build();
    }

    public static HeartRateRecord getHeartRateRecord(int heartRate, Instant instant) {
        HeartRateRecord.HeartRateSample heartRateSample =
                new HeartRateRecord.HeartRateSample(heartRate, instant);
        ArrayList<HeartRateRecord.HeartRateSample> heartRateSamples = new ArrayList<>();
        heartRateSamples.add(heartRateSample);
        heartRateSamples.add(heartRateSample);

        return new HeartRateRecord.Builder(
                        new Metadata.Builder().build(),
                        instant,
                        instant.plusMillis(1000),
                        heartRateSamples)
                .build();
    }

    public static BasalMetabolicRateRecord getBasalMetabolicRateRecord() {
        Context context = ApplicationProvider.getApplicationContext();
        Device device =
                new Device.Builder()
                        .setManufacturer("google")
                        .setModel("Pixel4a")
                        .setType(2)
                        .build();
        DataOrigin dataOrigin =
                new DataOrigin.Builder().setPackageName(context.getPackageName()).build();
        return new BasalMetabolicRateRecord.Builder(
                        new Metadata.Builder()
                                .setDevice(device)
                                .setDataOrigin(dataOrigin)
                                .setClientRecordId("BMR" + Math.random())
                                .build(),
                        Instant.now(),
                        Power.fromWatts(100.0))
                .build();
    }

    public static <T> AggregateRecordsResponse<T> getAggregateResponse(
            AggregateRecordsRequest<T> request, List<Record> recordsToInsert)
            throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        if (recordsToInsert != null) {
            insertRecords(recordsToInsert);
        }
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<AggregateRecordsResponse<T>> response = new AtomicReference<>();
        AtomicReference<HealthConnectException> healthConnectExceptionAtomicReference =
                new AtomicReference<>();
        service.aggregate(
                request,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(AggregateRecordsResponse<T> result) {
                        response.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException healthConnectException) {
                        healthConnectExceptionAtomicReference.set(healthConnectException);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (healthConnectExceptionAtomicReference.get() != null) {
            throw healthConnectExceptionAtomicReference.get();
        }

        return response.get();
    }

    public static <T>
            List<AggregateRecordsGroupedByDurationResponse<T>> getAggregateResponseGroupByDuration(
                    AggregateRecordsRequest<T> request, Duration duration)
                    throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<AggregateRecordsGroupedByDurationResponse<T>>> response =
                new AtomicReference<>();
        AtomicReference<HealthConnectException> healthConnectExceptionAtomicReference =
                new AtomicReference<>();
        service.aggregateGroupByDuration(
                request,
                duration,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(
                            List<AggregateRecordsGroupedByDurationResponse<T>> result) {
                        response.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException healthConnectException) {
                        healthConnectExceptionAtomicReference.set(healthConnectException);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (healthConnectExceptionAtomicReference.get() != null) {
            throw healthConnectExceptionAtomicReference.get();
        }
        return response.get();
    }

    public static <T>
            List<AggregateRecordsGroupedByPeriodResponse<T>> getAggregateResponseGroupByPeriod(
                    AggregateRecordsRequest<T> request, Period period) throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<List<AggregateRecordsGroupedByPeriodResponse<T>>> response =
                new AtomicReference<>();
        AtomicReference<HealthConnectException> healthConnectExceptionAtomicReference =
                new AtomicReference<>();
        service.aggregateGroupByPeriod(
                request,
                period,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(List<AggregateRecordsGroupedByPeriodResponse<T>> result) {
                        response.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException healthConnectException) {
                        healthConnectExceptionAtomicReference.set(healthConnectException);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (healthConnectExceptionAtomicReference.get() != null) {
            throw healthConnectExceptionAtomicReference.get();
        }

        return response.get();
    }

    public static <T extends Record> List<T> readRecords(ReadRecordsRequest<T> request)
            throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        CountDownLatch latch = new CountDownLatch(1);
        assertThat(service).isNotNull();
        assertThat(request.getRecordType()).isNotNull();
        AtomicReference<List<T>> response = new AtomicReference<>();
        AtomicReference<HealthConnectException> healthConnectExceptionAtomicReference =
                new AtomicReference<>();
        service.readRecords(
                request,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(ReadRecordsResponse<T> result) {
                        response.set(result.getRecords());
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException exception) {
                        Log.e(TAG, exception.getMessage());
                        healthConnectExceptionAtomicReference.set(exception);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
        if (healthConnectExceptionAtomicReference.get() != null) {
            throw healthConnectExceptionAtomicReference.get();
        }
        return response.get();
    }

    public static <T extends Record> void assertRecordNotFound(String uuid, Class<T> recordType)
            throws InterruptedException {
        assertThat(
                        readRecords(
                                new ReadRecordsRequestUsingIds.Builder<>(recordType)
                                        .addId(uuid)
                                        .build()))
                .isEmpty();
    }

    public static <T extends Record> void assertRecordFound(String uuid, Class<T> recordType)
            throws InterruptedException {
        assertThat(
                        readRecords(
                                new ReadRecordsRequestUsingIds.Builder<>(recordType)
                                        .addId(uuid)
                                        .build()))
                .isNotEmpty();
    }

    public static <T extends Record> Pair<List<T>, Long> readRecordsWithPagination(
            ReadRecordsRequest<T> request) throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        CountDownLatch latch = new CountDownLatch(1);
        assertThat(service).isNotNull();
        AtomicReference<List<T>> response = new AtomicReference<>();
        AtomicReference<HealthConnectException> healthConnectExceptionAtomicReference =
                new AtomicReference<>();
        AtomicReference<Long> pageToken = new AtomicReference<>();
        service.readRecords(
                request,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(ReadRecordsResponse<T> result) {
                        response.set(result.getRecords());
                        pageToken.set(result.getNextPageToken());
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException exception) {
                        healthConnectExceptionAtomicReference.set(exception);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
        if (healthConnectExceptionAtomicReference.get() != null) {
            throw healthConnectExceptionAtomicReference.get();
        }
        return Pair.create(response.get(), pageToken.get());
    }

    public static void setAutoDeletePeriod(int period) throws InterruptedException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(MANAGE_HEALTH_DATA);
        try {
            Context context = ApplicationProvider.getApplicationContext();
            HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
            CountDownLatch latch = new CountDownLatch(1);
            assertThat(service).isNotNull();
            AtomicReference<HealthConnectException> exceptionAtomicReference =
                    new AtomicReference<>();
            service.setRecordRetentionPeriodInDays(
                    period,
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<>() {
                        @Override
                        public void onResult(Void result) {
                            latch.countDown();
                        }

                        @Override
                        public void onError(HealthConnectException healthConnectException) {
                            exceptionAtomicReference.set(healthConnectException);
                            latch.countDown();
                        }
                    });
            assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
            if (exceptionAtomicReference.get() != null) {
                throw exceptionAtomicReference.get();
            }
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    public static void verifyDeleteRecords(DeleteUsingFiltersRequest request)
            throws InterruptedException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(MANAGE_HEALTH_DATA);
        try {
            Context context = ApplicationProvider.getApplicationContext();
            HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<HealthConnectException> exceptionAtomicReference =
                    new AtomicReference<>();
            assertThat(service).isNotNull();
            service.deleteRecords(
                    request,
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<>() {
                        @Override
                        public void onResult(Void result) {
                            latch.countDown();
                        }

                        @Override
                        public void onError(HealthConnectException healthConnectException) {
                            exceptionAtomicReference.set(healthConnectException);
                            latch.countDown();
                        }
                    });
            assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
            if (exceptionAtomicReference.get() != null) {
                throw exceptionAtomicReference.get();
            }
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    public static void verifyDeleteRecords(List<RecordIdFilter> request)
            throws InterruptedException {

        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<HealthConnectException> exceptionAtomicReference = new AtomicReference<>();
        assertThat(service).isNotNull();
        service.deleteRecords(
                request,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Void result) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException healthConnectException) {
                        exceptionAtomicReference.set(healthConnectException);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
        if (exceptionAtomicReference.get() != null) {
            throw exceptionAtomicReference.get();
        }
    }

    public static void verifyDeleteRecords(
            Class<? extends Record> recordType, TimeInstantRangeFilter timeRangeFilter)
            throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        CountDownLatch latch = new CountDownLatch(1);
        assertThat(service).isNotNull();
        AtomicReference<HealthConnectException> exceptionAtomicReference = new AtomicReference<>();

        service.deleteRecords(
                recordType,
                timeRangeFilter,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Void result) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException healthConnectException) {
                        exceptionAtomicReference.set(healthConnectException);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
        if (exceptionAtomicReference.get() != null) {
            throw exceptionAtomicReference.get();
        }
    }

    public static void deleteRecords(List<Record> records) throws InterruptedException {
        List<RecordIdFilter> recordIdFilters =
                records.stream()
                        .map(
                                (record ->
                                        RecordIdFilter.fromId(
                                                record.getClass(), record.getMetadata().getId())))
                        .collect(Collectors.toList());
        verifyDeleteRecords(recordIdFilters);
    }

    public static List<AccessLog> queryAccessLogs() throws InterruptedException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(MANAGE_HEALTH_DATA);
        try {
            Context context = ApplicationProvider.getApplicationContext();
            HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
            assertThat(service).isNotNull();

            CountDownLatch latch = new CountDownLatch(1);
            AtomicReference<List<AccessLog>> response = new AtomicReference<>();
            AtomicReference<HealthConnectException> exceptionAtomicReference =
                    new AtomicReference<>();
            service.queryAccessLogs(
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<List<AccessLog>, HealthConnectException>() {

                        @Override
                        public void onResult(List<AccessLog> result) {
                            response.set(result);
                            latch.countDown();
                        }

                        @Override
                        public void onError(@NonNull HealthConnectException exception) {
                            exceptionAtomicReference.set(exception);
                            latch.countDown();
                        }
                    });
            assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
            if (exceptionAtomicReference.get() != null) {
                throw exceptionAtomicReference.get();
            }
            return response.get();
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    public static Map<Class<? extends Record>, RecordTypeInfoResponse> queryAllRecordTypesInfo()
            throws InterruptedException, NullPointerException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(MANAGE_HEALTH_DATA);
        AtomicReference<Map<Class<? extends Record>, RecordTypeInfoResponse>> response =
                new AtomicReference<>();
        AtomicReference<HealthConnectException> responseException = new AtomicReference<>();
        try {
            Context context = ApplicationProvider.getApplicationContext();
            CountDownLatch latch = new CountDownLatch(1);
            HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
            assertThat(service).isNotNull();
            service.queryAllRecordTypesInfo(
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<
                            Map<Class<? extends Record>, RecordTypeInfoResponse>,
                            HealthConnectException>() {
                        @Override
                        public void onResult(
                                Map<Class<? extends Record>, RecordTypeInfoResponse> result) {
                            response.set(result);
                            latch.countDown();
                        }

                        @Override
                        public void onError(HealthConnectException exception) {
                            responseException.set(exception);
                            latch.countDown();
                        }
                    });
            assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
            assertThat(responseException.get()).isNull();
            assertThat(response).isNotNull();
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
        return response.get();
    }

    public static List<LocalDate> getActivityDates(List<Class<? extends Record>> recordTypes)
            throws InterruptedException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        uiAutomation.adoptShellPermissionIdentity(MANAGE_HEALTH_DATA);
        try {
            Context context = ApplicationProvider.getApplicationContext();
            HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
            CountDownLatch latch = new CountDownLatch(1);
            assertThat(service).isNotNull();
            AtomicReference<List<LocalDate>> response = new AtomicReference<>();
            AtomicReference<HealthConnectException> exceptionAtomicReference =
                    new AtomicReference<>();
            service.queryActivityDates(
                    recordTypes,
                    Executors.newSingleThreadExecutor(),
                    new OutcomeReceiver<>() {
                        @Override
                        public void onResult(List<LocalDate> result) {
                            response.set(result);
                            latch.countDown();
                        }

                        @Override
                        public void onError(HealthConnectException exception) {
                            exceptionAtomicReference.set(exception);
                            latch.countDown();
                        }
                    });

            assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
            if (exceptionAtomicReference.get() != null) {
                throw exceptionAtomicReference.get();
            }

            return response.get();
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }
    }

    public static ExerciseSessionRecord buildExerciseSession() {
        return buildExerciseSession(buildExerciseRoute(), "Morning training", "rain");
    }

    public static SleepSessionRecord buildSleepSession() {
        return new SleepSessionRecord.Builder(
                        generateMetadata(), SESSION_START_TIME, SESSION_END_TIME)
                .setNotes("warm")
                .setTitle("Afternoon nap")
                .setStages(
                        List.of(
                                new SleepSessionRecord.Stage(
                                        SESSION_START_TIME,
                                        SESSION_START_TIME.plusSeconds(300),
                                        SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_LIGHT),
                                new SleepSessionRecord.Stage(
                                        SESSION_START_TIME.plusSeconds(300),
                                        SESSION_START_TIME.plusSeconds(600),
                                        SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_REM),
                                new SleepSessionRecord.Stage(
                                        SESSION_START_TIME.plusSeconds(900),
                                        SESSION_START_TIME.plusSeconds(1200),
                                        SleepSessionRecord.StageType.STAGE_TYPE_SLEEPING_DEEP)))
                .build();
    }

    public static void startMigration() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);

        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        service.startMigration(
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<Void, MigrationException>() {
                    @Override
                    public void onResult(Void result) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(MigrationException exception) {
                        Log.e(TAG, exception.getMessage());
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    public static void finishMigration() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);

        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        service.finishMigration(
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<Void, MigrationException>() {

                    @Override
                    public void onResult(Void result) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(MigrationException exception) {
                        Log.e(TAG, exception.getMessage());
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
    }

    public static void insertMinDataMigrationSdkExtensionVersion(int version)
            throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        CountDownLatch latch = new CountDownLatch(1);
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        AtomicReference<MigrationException> migrationExceptionAtomicReference =
                new AtomicReference<>();
        service.insertMinDataMigrationSdkExtensionVersion(
                version,
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {

                    @Override
                    public void onResult(Void result) {
                        latch.countDown();
                    }

                    @Override
                    public void onError(MigrationException exception) {
                        migrationExceptionAtomicReference.set(exception);
                        Log.e(TAG, exception.getMessage());
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isTrue();
        if (migrationExceptionAtomicReference.get() != null) {
            throw migrationExceptionAtomicReference.get();
        }
    }

    public static void deleteAllStagedRemoteData() {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        runWithShellPermissionIdentity(
                () ->
                        // TODO(b/241542162): Avoid reflection once TestApi can be called from CTS
                        service.getClass().getMethod("deleteAllStagedRemoteData").invoke(service),
                "android.permission.DELETE_STAGED_HEALTH_CONNECT_REMOTE_DATA");
    }

    public static int getHealthConnectDataMigrationState() throws InterruptedException {
        CountDownLatch latch = new CountDownLatch(1);
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        assertThat(service).isNotNull();
        AtomicReference<HealthConnectDataState> returnedHealthConnectDataState =
                new AtomicReference<>();
        AtomicReference<HealthConnectException> responseException = new AtomicReference<>();
        service.getHealthConnectDataState(
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(HealthConnectDataState healthConnectDataState) {
                        returnedHealthConnectDataState.set(healthConnectDataState);
                        latch.countDown();
                    }

                    @Override
                    public void onError(@NonNull HealthConnectException exception) {
                        responseException.set(exception);
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
        if (responseException.get() != null) {
            throw responseException.get();
        }
        return returnedHealthConnectDataState.get().getDataMigrationState();
    }

    public static List<AppInfo> getApplicationInfo() throws InterruptedException {
        Context context = ApplicationProvider.getApplicationContext();
        HealthConnectManager service = context.getSystemService(HealthConnectManager.class);
        CountDownLatch latch = new CountDownLatch(1);
        assertThat(service).isNotNull();
        AtomicReference<List<AppInfo>> response = new AtomicReference<>();
        AtomicReference<HealthConnectException> exceptionAtomicReference = new AtomicReference<>();
        service.getContributorApplicationsInfo(
                Executors.newSingleThreadExecutor(),
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(ApplicationInfoResponse result) {
                        response.set(result.getApplicationInfoList());
                        latch.countDown();
                    }

                    @Override
                    public void onError(HealthConnectException exception) {
                        exceptionAtomicReference.set(exception);
                        Log.e(TAG, exception.getMessage());
                        latch.countDown();
                    }
                });
        assertThat(latch.await(3, TimeUnit.SECONDS)).isEqualTo(true);
        if (exceptionAtomicReference.get() != null) {
            throw exceptionAtomicReference.get();
        }
        return response.get();
    }

    public static <T extends Record> T getRecordById(List<T> list, String id) {
        for (T record : list) {
            if (record.getMetadata().getId().equals(id)) {
                return record;
            }
        }

        throw new AssertionError("Record not found with id: " + id);
    }

    static Metadata generateMetadata() {
        Context context = ApplicationProvider.getApplicationContext();
        return new Metadata.Builder()
                .setDevice(buildDevice())
                .setId(UUID.randomUUID().toString())
                .setClientRecordId("clientRecordId" + Math.random())
                .setDataOrigin(
                        new DataOrigin.Builder().setPackageName(context.getPackageName()).build())
                .setDevice(buildDevice())
                .setRecordingMethod(Metadata.RECORDING_METHOD_UNKNOWN)
                .build();
    }

    private static ExerciseSessionRecord buildExerciseSession(
            ExerciseRoute route, String title, String notes) {
        return new ExerciseSessionRecord.Builder(
                        generateMetadata(),
                        SESSION_START_TIME,
                        SESSION_END_TIME,
                        ExerciseSessionType.EXERCISE_SESSION_TYPE_OTHER_WORKOUT)
                .setRoute(route)
                .setLaps(
                        List.of(
                                new ExerciseLap.Builder(
                                                SESSION_START_TIME,
                                                SESSION_START_TIME.plusSeconds(20))
                                        .setLength(Length.fromMeters(10))
                                        .build(),
                                new ExerciseLap.Builder(
                                                SESSION_END_TIME.minusSeconds(20), SESSION_END_TIME)
                                        .build()))
                .setSegments(
                        List.of(
                                new ExerciseSegment.Builder(
                                                SESSION_START_TIME.plusSeconds(1),
                                                SESSION_START_TIME.plusSeconds(10),
                                                ExerciseSegmentType
                                                        .EXERCISE_SEGMENT_TYPE_BENCH_PRESS)
                                        .build(),
                                new ExerciseSegment.Builder(
                                                SESSION_START_TIME.plusSeconds(21),
                                                SESSION_START_TIME.plusSeconds(124),
                                                ExerciseSegmentType.EXERCISE_SEGMENT_TYPE_BURPEE)
                                        .setRepetitionsCount(15)
                                        .build()))
                .setEndZoneOffset(ZoneOffset.MAX)
                .setStartZoneOffset(ZoneOffset.MIN)
                .setNotes(notes)
                .setTitle(title)
                .build();
    }

    static void populateAndResetExpectedResponseMap(
            HashMap<Class<? extends Record>, RecordTypeInfoTestResponse> expectedResponseMap) {
        expectedResponseMap.put(
                ElevationGainedRecord.class,
                new RecordTypeInfoTestResponse(
                        ACTIVITY, HealthPermissionCategory.ELEVATION_GAINED, new ArrayList<>()));
        expectedResponseMap.put(
                OvulationTestRecord.class,
                new RecordTypeInfoTestResponse(
                        CYCLE_TRACKING,
                        HealthPermissionCategory.OVULATION_TEST,
                        new ArrayList<>()));
        expectedResponseMap.put(
                DistanceRecord.class,
                new RecordTypeInfoTestResponse(
                        ACTIVITY, HealthPermissionCategory.DISTANCE, new ArrayList<>()));
        expectedResponseMap.put(
                SpeedRecord.class,
                new RecordTypeInfoTestResponse(
                        ACTIVITY, HealthPermissionCategory.SPEED, new ArrayList<>()));

        expectedResponseMap.put(
                Vo2MaxRecord.class,
                new RecordTypeInfoTestResponse(
                        ACTIVITY, HealthPermissionCategory.VO2_MAX, new ArrayList<>()));
        expectedResponseMap.put(
                OxygenSaturationRecord.class,
                new RecordTypeInfoTestResponse(
                        VITALS, HealthPermissionCategory.OXYGEN_SATURATION, new ArrayList<>()));
        expectedResponseMap.put(
                TotalCaloriesBurnedRecord.class,
                new RecordTypeInfoTestResponse(
                        ACTIVITY,
                        HealthPermissionCategory.TOTAL_CALORIES_BURNED,
                        new ArrayList<>()));
        expectedResponseMap.put(
                HydrationRecord.class,
                new RecordTypeInfoTestResponse(
                        NUTRITION, HealthPermissionCategory.HYDRATION, new ArrayList<>()));
        expectedResponseMap.put(
                StepsRecord.class,
                new RecordTypeInfoTestResponse(ACTIVITY, STEPS, new ArrayList<>()));
        expectedResponseMap.put(
                CervicalMucusRecord.class,
                new RecordTypeInfoTestResponse(
                        CYCLE_TRACKING,
                        HealthPermissionCategory.CERVICAL_MUCUS,
                        new ArrayList<>()));
        expectedResponseMap.put(
                ExerciseSessionRecord.class,
                new RecordTypeInfoTestResponse(ACTIVITY, EXERCISE, new ArrayList<>()));
        expectedResponseMap.put(
                HeartRateRecord.class,
                new RecordTypeInfoTestResponse(VITALS, HEART_RATE, new ArrayList<>()));
        expectedResponseMap.put(
                RespiratoryRateRecord.class,
                new RecordTypeInfoTestResponse(
                        VITALS, HealthPermissionCategory.RESPIRATORY_RATE, new ArrayList<>()));
        expectedResponseMap.put(
                BasalBodyTemperatureRecord.class,
                new RecordTypeInfoTestResponse(
                        VITALS,
                        HealthPermissionCategory.BASAL_BODY_TEMPERATURE,
                        new ArrayList<>()));
        expectedResponseMap.put(
                WheelchairPushesRecord.class,
                new RecordTypeInfoTestResponse(
                        ACTIVITY, HealthPermissionCategory.WHEELCHAIR_PUSHES, new ArrayList<>()));
        expectedResponseMap.put(
                PowerRecord.class,
                new RecordTypeInfoTestResponse(
                        ACTIVITY, HealthPermissionCategory.POWER, new ArrayList<>()));
        expectedResponseMap.put(
                BodyWaterMassRecord.class,
                new RecordTypeInfoTestResponse(
                        BODY_MEASUREMENTS,
                        HealthPermissionCategory.BODY_WATER_MASS,
                        new ArrayList<>()));
        expectedResponseMap.put(
                WeightRecord.class,
                new RecordTypeInfoTestResponse(
                        BODY_MEASUREMENTS, HealthPermissionCategory.WEIGHT, new ArrayList<>()));
        expectedResponseMap.put(
                BoneMassRecord.class,
                new RecordTypeInfoTestResponse(
                        BODY_MEASUREMENTS, HealthPermissionCategory.BONE_MASS, new ArrayList<>()));
        expectedResponseMap.put(
                RestingHeartRateRecord.class,
                new RecordTypeInfoTestResponse(
                        VITALS, HealthPermissionCategory.RESTING_HEART_RATE, new ArrayList<>()));
        expectedResponseMap.put(
                ActiveCaloriesBurnedRecord.class,
                new RecordTypeInfoTestResponse(
                        ACTIVITY,
                        HealthPermissionCategory.ACTIVE_CALORIES_BURNED,
                        new ArrayList<>()));
        expectedResponseMap.put(
                BodyFatRecord.class,
                new RecordTypeInfoTestResponse(
                        BODY_MEASUREMENTS, HealthPermissionCategory.BODY_FAT, new ArrayList<>()));
        expectedResponseMap.put(
                BodyTemperatureRecord.class,
                new RecordTypeInfoTestResponse(
                        VITALS, HealthPermissionCategory.BODY_TEMPERATURE, new ArrayList<>()));
        expectedResponseMap.put(
                NutritionRecord.class,
                new RecordTypeInfoTestResponse(
                        NUTRITION, HealthPermissionCategory.NUTRITION, new ArrayList<>()));
        expectedResponseMap.put(
                LeanBodyMassRecord.class,
                new RecordTypeInfoTestResponse(
                        BODY_MEASUREMENTS,
                        HealthPermissionCategory.LEAN_BODY_MASS,
                        new ArrayList<>()));
        expectedResponseMap.put(
                HeartRateVariabilityRmssdRecord.class,
                new RecordTypeInfoTestResponse(
                        VITALS,
                        HealthPermissionCategory.HEART_RATE_VARIABILITY,
                        new ArrayList<>()));
        expectedResponseMap.put(
                MenstruationFlowRecord.class,
                new RecordTypeInfoTestResponse(
                        CYCLE_TRACKING, HealthPermissionCategory.MENSTRUATION, new ArrayList<>()));
        expectedResponseMap.put(
                BloodGlucoseRecord.class,
                new RecordTypeInfoTestResponse(
                        VITALS, HealthPermissionCategory.BLOOD_GLUCOSE, new ArrayList<>()));
        expectedResponseMap.put(
                BloodPressureRecord.class,
                new RecordTypeInfoTestResponse(
                        VITALS, HealthPermissionCategory.BLOOD_PRESSURE, new ArrayList<>()));
        expectedResponseMap.put(
                CyclingPedalingCadenceRecord.class,
                new RecordTypeInfoTestResponse(ACTIVITY, EXERCISE, new ArrayList<>()));
        expectedResponseMap.put(
                IntermenstrualBleedingRecord.class,
                new RecordTypeInfoTestResponse(
                        CYCLE_TRACKING,
                        HealthPermissionCategory.INTERMENSTRUAL_BLEEDING,
                        new ArrayList<>()));
        expectedResponseMap.put(
                FloorsClimbedRecord.class,
                new RecordTypeInfoTestResponse(
                        ACTIVITY, HealthPermissionCategory.FLOORS_CLIMBED, new ArrayList<>()));
        expectedResponseMap.put(
                StepsCadenceRecord.class,
                new RecordTypeInfoTestResponse(ACTIVITY, STEPS, new ArrayList<>()));
        expectedResponseMap.put(
                HeightRecord.class,
                new RecordTypeInfoTestResponse(
                        BODY_MEASUREMENTS, HealthPermissionCategory.HEIGHT, new ArrayList<>()));
        expectedResponseMap.put(
                SexualActivityRecord.class,
                new RecordTypeInfoTestResponse(
                        CYCLE_TRACKING,
                        HealthPermissionCategory.SEXUAL_ACTIVITY,
                        new ArrayList<>()));
        expectedResponseMap.put(
                MenstruationPeriodRecord.class,
                new RecordTypeInfoTestResponse(
                        CYCLE_TRACKING, HealthPermissionCategory.MENSTRUATION, new ArrayList<>()));
        expectedResponseMap.put(
                SleepSessionRecord.class,
                new RecordTypeInfoTestResponse(
                        SLEEP, HealthPermissionCategory.SLEEP, new ArrayList<>()));
        expectedResponseMap.put(
                BasalMetabolicRateRecord.class,
                new RecordTypeInfoTestResponse(
                        BODY_MEASUREMENTS, BASAL_METABOLIC_RATE, new ArrayList<>()));
    }

    static final class RecordAndIdentifier {
        private final int id;
        private final Record recordClass;

        public RecordAndIdentifier(int id, Record recordClass) {
            this.id = id;
            this.recordClass = recordClass;
        }

        public int getId() {
            return id;
        }

        public Record getRecordClass() {
            return recordClass;
        }
    }

    static class RecordTypeInfoTestResponse {
        private final int mRecordTypePermission;
        private final ArrayList<String> mContributingPackages;
        private final int mRecordTypeCategory;

        RecordTypeInfoTestResponse(
                int recordTypeCategory,
                int recordTypePermission,
                ArrayList<String> contributingPackages) {
            mRecordTypeCategory = recordTypeCategory;
            mRecordTypePermission = recordTypePermission;
            mContributingPackages = contributingPackages;
        }

        public int getRecordTypeCategory() {
            return mRecordTypeCategory;
        }

        public int getRecordTypePermission() {
            return mRecordTypePermission;
        }

        public ArrayList<String> getContributingPackages() {
            return mContributingPackages;
        }
    }
}
