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

package android.healthconnect.cts.lib;

import static androidx.test.InstrumentationRegistry.getContext;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;

import static com.google.common.truth.Truth.assertThat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.InsertRecordsResponse;
import android.health.connect.ReadRecordsRequest;
import android.health.connect.ReadRecordsResponse;
import android.health.connect.RecordIdFilter;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.changelog.ChangeLogTokenResponse;
import android.health.connect.changelog.ChangeLogsRequest;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.datatypes.BasalMetabolicRateRecord;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Device;
import android.health.connect.datatypes.ExerciseRoute;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.ExerciseSessionType;
import android.health.connect.datatypes.HeartRateRecord;
import android.health.connect.datatypes.Metadata;
import android.health.connect.datatypes.Record;
import android.health.connect.datatypes.StepsRecord;
import android.health.connect.datatypes.units.Power;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;

import com.android.cts.install.lib.TestApp;

import java.io.Serializable;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

public class TestUtils {
    static final String TAG = "HealthConnectTest";
    public static final String QUERY_TYPE = "android.healthconnect.cts.queryType";
    public static final String INTENT_EXTRA_CALLING_PKG = "android.healthconnect.cts.calling_pkg";
    public static final String APP_PKG_NAME_USED_IN_DATA_ORIGIN =
            "android.healthconnect.cts.pkg.usedInDataOrigin";
    public static final String INSERT_RECORD_QUERY = "android.healthconnect.cts.insertRecord";
    public static final String READ_RECORDS_QUERY = "android.healthconnect.cts.readRecords";
    public static final String READ_RECORDS_SIZE = "android.healthconnect.cts.readRecordsNumber";
    public static final String READ_USING_DATA_ORIGIN_FILTERS =
            "android.healthconnect.cts.readUsingDataOriginFilters";
    public static final String READ_RECORD_CLASS_NAME =
            "android.healthconnect.cts.readRecordsClass";
    public static final String READ_CHANGE_LOGS_QUERY = "android.healthconnect.cts.readChangeLogs";
    public static final String CHANGE_LOGS_RESPONSE =
            "android.healthconnect.cts.changeLogsResponse";
    public static final String CHANGE_LOG_TOKEN = "android.healthconnect.cts.changeLogToken";
    public static final String SUCCESS = "android.healthconnect.cts.success";
    public static final String CLIENT_ID = "android.healthconnect.cts.clientId";
    public static final String RECORD_IDS = "android.healthconnect.cts.records";
    public static final String DELETE_RECORDS_QUERY = "android.healthconnect.cts.deleteRecords";
    public static final String UPDATE_RECORDS_QUERY = "android.healthconnect.cts.updateRecords";
    public static final String UPDATE_EXERCISE_ROUTE = "android.healthconnect.cts.updateRoute";

    public static final String UPSERT_EXERCISE_ROUTE = "android.healthconnect.cts.upsertRoute";
    public static final String GET_CHANGE_LOG_TOKEN_QUERY =
            "android.healthconnect.cts.getChangeLogToken";
    public static final String INTENT_EXCEPTION = "android.healthconnect.cts.exception";
    private static final long POLLING_TIMEOUT_MILLIS = TimeUnit.SECONDS.toMillis(20);

    public static class RecordTypeAndRecordIds implements Serializable {
        private String mRecordType;
        private List<String> mRecordIds;

        public RecordTypeAndRecordIds(String recordType, List<String> ids) {
            mRecordType = recordType;
            mRecordIds = ids;
        }

        public String getRecordType() {
            return mRecordType;
        }

        public List<String> getRecordIds() {
            return mRecordIds;
        }
    }

    public static Bundle insertRecordAs(TestApp testApp) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, INSERT_RECORD_QUERY);

        return getFromTestApp(testApp, bundle);
    }

    public static Bundle deleteRecordsAs(
            TestApp testApp, List<RecordTypeAndRecordIds> listOfRecordIdsAndClass)
            throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, DELETE_RECORDS_QUERY);
        bundle.putSerializable(RECORD_IDS, (Serializable) listOfRecordIdsAndClass);

        return getFromTestApp(testApp, bundle);
    }

    public static Bundle updateRecordsAs(
            TestApp testAppToUpdateData, List<RecordTypeAndRecordIds> listOfRecordIdsAndClass)
            throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, UPDATE_RECORDS_QUERY);
        bundle.putSerializable(RECORD_IDS, (Serializable) listOfRecordIdsAndClass);

        return getFromTestApp(testAppToUpdateData, bundle);
    }

    public static Bundle updateRouteAs(TestApp testAppToUpdateData) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, UPDATE_EXERCISE_ROUTE);
        return getFromTestApp(testAppToUpdateData, bundle);
    }

    public static Bundle insertSessionNoRouteAs(TestApp testAppToUpdateData) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, UPSERT_EXERCISE_ROUTE);
        return getFromTestApp(testAppToUpdateData, bundle);
    }

    public static Bundle insertRecordWithAnotherAppPackageName(
            TestApp testAppToInsertData, TestApp testAppPkgNameUsed) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, INSERT_RECORD_QUERY);
        bundle.putString(APP_PKG_NAME_USED_IN_DATA_ORIGIN, testAppPkgNameUsed.getPackageName());

        return getFromTestApp(testAppToInsertData, bundle);
    }

    public static Bundle readRecordsAs(TestApp testApp, ArrayList<String> recordClassesToRead)
            throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, READ_RECORDS_QUERY);
        bundle.putStringArrayList(READ_RECORD_CLASS_NAME, recordClassesToRead);

        return getFromTestApp(testApp, bundle);
    }

    public static Bundle insertRecordWithGivenClientId(TestApp testApp, double clientId)
            throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, INSERT_RECORD_QUERY);
        bundle.putDouble(CLIENT_ID, clientId);

        return getFromTestApp(testApp, bundle);
    }

    public static Bundle readRecordsUsingDataOriginFiltersAs(
            TestApp testApp, ArrayList<String> recordClassesToRead) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, READ_RECORDS_QUERY);
        bundle.putStringArrayList(READ_RECORD_CLASS_NAME, recordClassesToRead);
        bundle.putBoolean(READ_USING_DATA_ORIGIN_FILTERS, true);

        return getFromTestApp(testApp, bundle);
    }

    public static Bundle readChangeLogsUsingDataOriginFiltersAs(
            TestApp testApp, String changeLogToken) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, READ_CHANGE_LOGS_QUERY);
        bundle.putString(CHANGE_LOG_TOKEN, changeLogToken);
        bundle.putBoolean(READ_USING_DATA_ORIGIN_FILTERS, true);

        return getFromTestApp(testApp, bundle);
    }

    public static Bundle getChangeLogTokenAs(TestApp testApp, String pkgName) throws Exception {
        Bundle bundle = new Bundle();
        bundle.putString(QUERY_TYPE, GET_CHANGE_LOG_TOKEN_QUERY);
        bundle.putString(APP_PKG_NAME_USED_IN_DATA_ORIGIN, pkgName);

        return getFromTestApp(testApp, bundle);
    }

    private static Bundle getFromTestApp(TestApp testApp, Bundle bundleToCreateIntent)
            throws Exception {
        final CountDownLatch latch = new CountDownLatch(1);
        AtomicReference<Bundle> response = new AtomicReference<>();
        AtomicReference<Exception> exceptionAtomicReference = new AtomicReference<>();
        BroadcastReceiver broadcastReceiver =
                new BroadcastReceiver() {
                    @Override
                    public void onReceive(Context context, Intent intent) {
                        if (intent.hasExtra(INTENT_EXCEPTION)) {
                            exceptionAtomicReference.set(
                                    (Exception) (intent.getSerializableExtra(INTENT_EXCEPTION)));
                        } else {
                            response.set(intent.getExtras());
                        }
                        latch.countDown();
                    }
                };

        launchTestApp(testApp, bundleToCreateIntent, broadcastReceiver, latch);
        if (exceptionAtomicReference.get() != null) {
            throw exceptionAtomicReference.get();
        }
        return response.get();
    }

    private static void launchTestApp(
            TestApp testApp,
            Bundle bundleToCreateIntent,
            BroadcastReceiver broadcastReceiver,
            CountDownLatch latch)
            throws Exception {

        // Register broadcast receiver
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(bundleToCreateIntent.getString(QUERY_TYPE));
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        getContext().registerReceiver(broadcastReceiver, intentFilter, Context.RECEIVER_EXPORTED);

        // Launch the test app.
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(testApp.getPackageName());
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.putExtra(INTENT_EXTRA_CALLING_PKG, getContext().getPackageName());
        intent.putExtras(bundleToCreateIntent);
        intent.addCategory(Intent.CATEGORY_LAUNCHER);
        intent.putExtras(bundleToCreateIntent);

        Thread.sleep(500);
        getContext().startActivity(intent);
        if (!latch.await(POLLING_TIMEOUT_MILLIS, TimeUnit.MILLISECONDS)) {
            final String errorMessage =
                    "Timed out while waiting to receive "
                            + bundleToCreateIntent.getString(QUERY_TYPE)
                            + " intent from "
                            + testApp.getPackageName();
            throw new TimeoutException(errorMessage);
        }
        getContext().unregisterReceiver(broadcastReceiver);
    }

    public static String insertRecordAndGetId(Record record, Context context)
            throws InterruptedException {
        return insertRecords(Collections.singletonList(record), context)
                .get(0)
                .getMetadata()
                .getId();
    }

    public static List<RecordTypeAndRecordIds> insertRecordsAndGetIds(
            List<Record> records, Context context) throws InterruptedException {
        List<Record> insertedRecords = insertRecords(records, context);

        Map<String, List<String>> recordTypeToRecordIdsMap = new HashMap<>();
        for (Record record : insertedRecords) {
            recordTypeToRecordIdsMap.putIfAbsent(record.getClass().getName(), new ArrayList<>());
            recordTypeToRecordIdsMap
                    .get(record.getClass().getName())
                    .add(record.getMetadata().getId());
        }

        List<RecordTypeAndRecordIds> recordTypeAndRecordIdsList = new ArrayList<>();
        for (String recordType : recordTypeToRecordIdsMap.keySet()) {
            recordTypeAndRecordIdsList.add(
                    new RecordTypeAndRecordIds(
                            recordType, recordTypeToRecordIdsMap.get(recordType)));
        }

        return recordTypeAndRecordIdsList;
    }

    public static List<Record> insertRecords(List<Record> records, Context context)
            throws InterruptedException {
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
                        Log.e(TAG, exception.getMessage());
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

    public static List<Record> getTestRecords(String packageName) {
        double clientId = Math.random();
        return getTestRecords(packageName, clientId);
    }

    public static List<Record> getTestRecords(String packageName, Double clientId) {
        return Arrays.asList(
                getExerciseSessionRecord(packageName, clientId, /* withRoute= */ true),
                getStepsRecord(packageName, clientId),
                getHeartRateRecord(packageName, clientId),
                getBasalMetabolicRateRecord(packageName, clientId));
    }

    public static ExerciseSessionRecord getExerciseSessionRecord(
            String packageName, double clientId, boolean withRoute) {
        Instant startTime = Instant.now().minusSeconds(3000);
        Instant endTime = Instant.now();
        ExerciseSessionRecord.Builder builder =
                new ExerciseSessionRecord.Builder(
                                buildSessionMetadata(packageName, clientId),
                                startTime,
                                endTime,
                                ExerciseSessionType.EXERCISE_SESSION_TYPE_OTHER_WORKOUT)
                        .setEndZoneOffset(ZoneOffset.MAX)
                        .setStartZoneOffset(ZoneOffset.MIN)
                        .setNotes("notes")
                        .setTitle("title");

        if (withRoute) {
            builder.setRoute(
                    new ExerciseRoute(
                            List.of(
                                    new ExerciseRoute.Location.Builder(startTime, 50., 50.).build(),
                                    new ExerciseRoute.Location.Builder(
                                                    startTime.plusSeconds(2), 51., 51.)
                                            .build())));
        }
        return builder.build();
    }

    public static StepsRecord getStepsRecord(String packageName, double clientId) {
        Device device =
                new Device.Builder().setManufacturer("google").setModel("Pixel").setType(1).build();
        DataOrigin dataOrigin = new DataOrigin.Builder().setPackageName(packageName).build();
        return new StepsRecord.Builder(
                        new Metadata.Builder()
                                .setDevice(device)
                                .setDataOrigin(dataOrigin)
                                .setClientRecordId("SR" + clientId)
                                .build(),
                        Instant.now(),
                        Instant.now().plusMillis(1000),
                        10)
                .build();
    }

    public static HeartRateRecord getHeartRateRecord(String packageName, double clientId) {
        HeartRateRecord.HeartRateSample heartRateSample =
                new HeartRateRecord.HeartRateSample(72, Instant.now().plusMillis(100));
        ArrayList<HeartRateRecord.HeartRateSample> heartRateSamples = new ArrayList<>();
        heartRateSamples.add(heartRateSample);
        heartRateSamples.add(heartRateSample);
        Device device =
                new Device.Builder().setManufacturer("google").setModel("Pixel").setType(1).build();
        DataOrigin dataOrigin = new DataOrigin.Builder().setPackageName(packageName).build();

        return new HeartRateRecord.Builder(
                        new Metadata.Builder()
                                .setDevice(device)
                                .setDataOrigin(dataOrigin)
                                .setClientRecordId("HR" + clientId)
                                .build(),
                        Instant.now(),
                        Instant.now().plusMillis(500),
                        heartRateSamples)
                .build();
    }

    public static BasalMetabolicRateRecord getBasalMetabolicRateRecord(
            String packageName, double clientId) {
        Device device =
                new Device.Builder()
                        .setManufacturer("google")
                        .setModel("Pixel4a")
                        .setType(2)
                        .build();
        DataOrigin dataOrigin = new DataOrigin.Builder().setPackageName(packageName).build();
        return new BasalMetabolicRateRecord.Builder(
                        new Metadata.Builder()
                                .setDevice(device)
                                .setDataOrigin(dataOrigin)
                                .setClientRecordId("BMR" + clientId)
                                .build(),
                        Instant.now(),
                        Power.fromWatts(100.0))
                .build();
    }

    public static void verifyDeleteRecords(List<RecordIdFilter> request, Context context)
            throws InterruptedException {
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

    public static <T extends Record> List<T> readRecords(
            ReadRecordsRequest<T> request, Context context) throws InterruptedException {
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

    public static void updateRecords(List<Record> records, Context context)
            throws InterruptedException {
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

    public static ChangeLogTokenResponse getChangeLogToken(
            ChangeLogTokenRequest request, Context context) throws InterruptedException {
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

    public static ChangeLogsResponse getChangeLogs(
            ChangeLogsRequest changeLogsRequest, Context context) throws InterruptedException {
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

    private static Metadata buildSessionMetadata(String packageName, double clientId) {
        Device device =
                new Device.Builder().setManufacturer("google").setModel("Pixel").setType(1).build();
        DataOrigin dataOrigin = new DataOrigin.Builder().setPackageName(packageName).build();
        return new Metadata.Builder()
                .setDevice(device)
                .setDataOrigin(dataOrigin)
                .setClientRecordId(String.valueOf(clientId))
                .build();
    }
}
