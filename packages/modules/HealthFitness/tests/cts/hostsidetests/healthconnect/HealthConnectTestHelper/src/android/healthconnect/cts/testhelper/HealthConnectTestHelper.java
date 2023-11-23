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

package android.healthconnect.cts.testhelper;

import static android.healthconnect.cts.lib.TestUtils.APP_PKG_NAME_USED_IN_DATA_ORIGIN;
import static android.healthconnect.cts.lib.TestUtils.CHANGE_LOGS_RESPONSE;
import static android.healthconnect.cts.lib.TestUtils.CHANGE_LOG_TOKEN;
import static android.healthconnect.cts.lib.TestUtils.CLIENT_ID;
import static android.healthconnect.cts.lib.TestUtils.DELETE_RECORDS_QUERY;
import static android.healthconnect.cts.lib.TestUtils.GET_CHANGE_LOG_TOKEN_QUERY;
import static android.healthconnect.cts.lib.TestUtils.INSERT_RECORD_QUERY;
import static android.healthconnect.cts.lib.TestUtils.INTENT_EXCEPTION;
import static android.healthconnect.cts.lib.TestUtils.QUERY_TYPE;
import static android.healthconnect.cts.lib.TestUtils.READ_CHANGE_LOGS_QUERY;
import static android.healthconnect.cts.lib.TestUtils.READ_RECORDS_QUERY;
import static android.healthconnect.cts.lib.TestUtils.READ_RECORDS_SIZE;
import static android.healthconnect.cts.lib.TestUtils.READ_RECORD_CLASS_NAME;
import static android.healthconnect.cts.lib.TestUtils.READ_USING_DATA_ORIGIN_FILTERS;
import static android.healthconnect.cts.lib.TestUtils.RECORD_IDS;
import static android.healthconnect.cts.lib.TestUtils.SUCCESS;
import static android.healthconnect.cts.lib.TestUtils.UPDATE_EXERCISE_ROUTE;
import static android.healthconnect.cts.lib.TestUtils.UPDATE_RECORDS_QUERY;
import static android.healthconnect.cts.lib.TestUtils.UPSERT_EXERCISE_ROUTE;
import static android.healthconnect.cts.lib.TestUtils.getChangeLogs;
import static android.healthconnect.cts.lib.TestUtils.getExerciseSessionRecord;
import static android.healthconnect.cts.lib.TestUtils.getTestRecords;
import static android.healthconnect.cts.lib.TestUtils.insertRecords;
import static android.healthconnect.cts.lib.TestUtils.insertRecordsAndGetIds;
import static android.healthconnect.cts.lib.TestUtils.readRecords;
import static android.healthconnect.cts.lib.TestUtils.updateRecords;
import static android.healthconnect.cts.lib.TestUtils.verifyDeleteRecords;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.health.connect.ReadRecordsRequestUsingFilters;
import android.health.connect.RecordIdFilter;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.changelog.ChangeLogTokenResponse;
import android.health.connect.changelog.ChangeLogsRequest;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.ExerciseSessionRecord;
import android.health.connect.datatypes.Record;
import android.healthconnect.cts.lib.TestUtils;
import android.os.Bundle;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

public class HealthConnectTestHelper extends Activity {
    private static final String TAG = "HealthConnectTestHelper";

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        final Context context = getApplicationContext();
        Bundle bundle = getIntent().getExtras();
        String queryType = bundle.getString(QUERY_TYPE);
        Intent returnIntent;
        try {
            switch (queryType) {
                case INSERT_RECORD_QUERY:
                    if (bundle.containsKey(APP_PKG_NAME_USED_IN_DATA_ORIGIN)) {
                        returnIntent =
                                insertRecordsWithDifferentPkgName(
                                        queryType,
                                        bundle.getString(APP_PKG_NAME_USED_IN_DATA_ORIGIN),
                                        context);
                        break;
                    }
                    if (bundle.containsKey(CLIENT_ID)) {
                        returnIntent =
                                insertRecordsWithGivenClientId(
                                        queryType, bundle.getDouble(CLIENT_ID), context);
                        break;
                    }
                    returnIntent = insertRecord(queryType, context);
                    break;
                case DELETE_RECORDS_QUERY:
                    returnIntent =
                            deleteRecords(
                                    queryType,
                                    (List<TestUtils.RecordTypeAndRecordIds>)
                                            bundle.getSerializable(RECORD_IDS),
                                    context);
                    break;
                case UPDATE_EXERCISE_ROUTE:
                    returnIntent = updateRouteAs(queryType, context);
                    break;
                case UPSERT_EXERCISE_ROUTE:
                    returnIntent = upsertRouteAs(queryType, context);
                    break;
                case UPDATE_RECORDS_QUERY:
                    returnIntent =
                            updateRecordsAs(
                                    queryType,
                                    (List<TestUtils.RecordTypeAndRecordIds>)
                                            bundle.getSerializable(RECORD_IDS),
                                    context);
                    break;
                case READ_RECORDS_QUERY:
                    if (bundle.containsKey(READ_USING_DATA_ORIGIN_FILTERS)) {
                        returnIntent =
                                readRecordsUsingDataOriginFilters(
                                        queryType,
                                        bundle.getStringArrayList(READ_RECORD_CLASS_NAME),
                                        context);
                        break;
                    }
                    returnIntent =
                            readRecordsAs(
                                    queryType,
                                    bundle.getStringArrayList(READ_RECORD_CLASS_NAME),
                                    context);
                    break;
                case READ_CHANGE_LOGS_QUERY:
                    returnIntent =
                            readChangeLogsUsingDataOriginFilters(
                                    queryType, bundle.getString(CHANGE_LOG_TOKEN), context);
                    break;
                case GET_CHANGE_LOG_TOKEN_QUERY:
                    returnIntent =
                            getChangeLogToken(
                                    queryType,
                                    bundle.getString(APP_PKG_NAME_USED_IN_DATA_ORIGIN),
                                    context);
                    break;
                default:
                    throw new IllegalStateException(
                            "Unknown query received from launcher app: " + queryType);
            }
        } catch (Exception e) {
            returnIntent = new Intent(queryType);
            returnIntent.putExtra(INTENT_EXCEPTION, e);
        }

        sendBroadcast(returnIntent);
        this.finish();
    }

    /**
     * Method to get test records, insert them, and put the list of recordId and recordClass in the
     * intent
     *
     * @param queryType - specifies the action, here it should be INSERT_RECORDS_QUERY
     * @param context - application context
     * @return Intent to send back to the main app which is running the tests
     */
    private Intent insertRecord(String queryType, Context context) {
        List<Record> records = getTestRecords(context.getPackageName());
        final Intent intent = new Intent(queryType);
        try {
            List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                    insertRecordsAndGetIds(records, context);
            intent.putExtra(RECORD_IDS, (Serializable) listOfRecordIdsAndClass);
            intent.putExtra(SUCCESS, true);
        } catch (Exception e) {
            intent.putExtra(SUCCESS, false);
            intent.putExtra(INTENT_EXCEPTION, e);
        }

        return intent;
    }

    /**
     * Method to delete the records and put the Exception in the intent if deleting records throws
     * an exception
     *
     * @param queryType - specifies the action, here it should be DELETE_RECORDS_QUERY
     * @param listOfRecordIdsAndClassName - list of recordId and recordClass of records to be
     *     deleted
     * @param context - application context
     * @return Intent to send back to the main app which is running the tests
     * @throws ClassNotFoundException if a record category class is not found for any class name
     *     present in the list @listOfRecordIdsAndClassName
     */
    private Intent deleteRecords(
            String queryType,
            List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClassName,
            Context context)
            throws ClassNotFoundException {
        final Intent intent = new Intent(queryType);

        List<RecordIdFilter> recordIdFilters = new ArrayList<>();
        for (TestUtils.RecordTypeAndRecordIds recordTypeAndRecordIds :
                listOfRecordIdsAndClassName) {
            for (String recordId : recordTypeAndRecordIds.getRecordIds()) {
                recordIdFilters.add(
                        RecordIdFilter.fromId(
                                (Class<? extends Record>)
                                        Class.forName(recordTypeAndRecordIds.getRecordType()),
                                recordId));
            }
        }
        try {
            verifyDeleteRecords(recordIdFilters, context);
            intent.putExtra(SUCCESS, true);
        } catch (Exception e) {
            intent.putExtra(INTENT_EXCEPTION, e);
        }
        return intent;
    }

    /**
     * Method to update the records and put the exception in the intent if updating the records
     * throws an exception
     *
     * @param queryType - specifies the action, here it should be UPDATE_RECORDS_QUERY
     * @param listOfRecordIdsAndClassName - list of recordId and recordClass of records to be
     *     updated
     * @param context - application context
     * @return Intent to send back to the main app which is running the tests
     */
    private Intent updateRecordsAs(
            String queryType,
            List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClassName,
            Context context) {
        final Intent intent = new Intent(queryType);

        try {
            for (TestUtils.RecordTypeAndRecordIds recordTypeAndRecordIds :
                    listOfRecordIdsAndClassName) {
                List<? extends Record> recordsToBeUpdated =
                        readRecords(
                                new ReadRecordsRequestUsingFilters.Builder<>(
                                                (Class<? extends Record>)
                                                        Class.forName(
                                                                recordTypeAndRecordIds
                                                                        .getRecordType()))
                                        .build(),
                                context);
                updateRecords((List<Record>) recordsToBeUpdated, context);
            }
            intent.putExtra(SUCCESS, true);
        } catch (Exception e) {
            intent.putExtra(INTENT_EXCEPTION, e);
            intent.putExtra(SUCCESS, false);
        }

        return intent;
    }

    /**
     * Method to update the session record to the session without route and put the exception in the
     * intent if updating the record throws an exception
     *
     * @param queryType - specifies the action, here it should be UPDATE_RECORDS_QUERY
     * @param context - application context
     * @return Intent to send back to the main app which is running the tests
     */
    private Intent updateRouteAs(String queryType, Context context) {
        final Intent intent = new Intent(queryType);
        try {
            ExerciseSessionRecord existingSession =
                    readRecords(
                                    new ReadRecordsRequestUsingFilters.Builder<>(
                                                    ExerciseSessionRecord.class)
                                            .build(),
                                    context)
                            .get(0);
            updateRecords(
                    List.of(
                            getExerciseSessionRecord(
                                    context.getPackageName(),
                                    Double.parseDouble(
                                            existingSession.getMetadata().getClientRecordId()),
                                    false)),
                    context);
            intent.putExtra(SUCCESS, true);
        } catch (Exception e) {
            intent.putExtra(INTENT_EXCEPTION, e);
            intent.putExtra(SUCCESS, false);
        }

        return intent;
    }

    /**
     * Method to upsert the session record to the session without route and put the exception in the
     * intent if updating the record throws an exception
     *
     * @param queryType - specifies the action, here it should be UPDATE_RECORDS_QUERY
     * @param context - application context
     * @return Intent to send back to the main app which is running the tests
     */
    private Intent upsertRouteAs(String queryType, Context context) {
        final Intent intent = new Intent(queryType);
        try {
            ExerciseSessionRecord existingSession =
                    readRecords(
                                    new ReadRecordsRequestUsingFilters.Builder<>(
                                                    ExerciseSessionRecord.class)
                                            .build(),
                                    context)
                            .get(0);
            insertRecords(
                    List.of(
                            getExerciseSessionRecord(
                                    context.getPackageName(),
                                    Double.parseDouble(
                                            existingSession.getMetadata().getClientRecordId()),
                                    false)),
                    context);
            intent.putExtra(SUCCESS, true);
        } catch (Exception e) {
            intent.putExtra(INTENT_EXCEPTION, e);
            intent.putExtra(SUCCESS, false);
        }

        return intent;
    }

    /**
     * Method to insert records with different package name in dataOrigin of the record and add the
     * details in the intent
     *
     * @param queryType - specifies the action, here it should be INSERT_RECORDS_QUERY
     * @param pkgNameUsedInDataOrigin - package name to be added in the dataOrigin of the records
     * @param context - application context
     * @return Intent to send back to the main app which is running the tests
     * @throws InterruptedException
     */
    private Intent insertRecordsWithDifferentPkgName(
            String queryType, String pkgNameUsedInDataOrigin, Context context)
            throws InterruptedException {
        final Intent intent = new Intent(queryType);

        List<Record> recordsToBeInserted = getTestRecords(pkgNameUsedInDataOrigin);
        List<TestUtils.RecordTypeAndRecordIds> listOfRecordIdsAndClass =
                insertRecordsAndGetIds(recordsToBeInserted, context);

        intent.putExtra(RECORD_IDS, (Serializable) listOfRecordIdsAndClass);
        return intent;
    }

    /**
     * Method to read records and put the number of records read in the intent or put the exception
     * in the intent in case reading records throws exception
     *
     * @param queryType - specifies the action, here it should be READ_RECORDS_QUERY
     * @param recordClassesToRead - List of Record Class names for the records to be read
     * @param context - application context
     * @return Intent to send back to the main app which is running the tests
     */
    private Intent readRecordsAs(
            String queryType, ArrayList<String> recordClassesToRead, Context context) {
        final Intent intent = new Intent(queryType);
        int recordsSize = 0;
        try {
            for (String recordClass : recordClassesToRead) {
                List<? extends Record> recordsRead =
                        readRecords(
                                new ReadRecordsRequestUsingFilters.Builder<>(
                                                (Class<? extends Record>)
                                                        Class.forName(recordClass))
                                        .build(),
                                context);

                recordsSize += recordsRead.size();
            }
            intent.putExtra(SUCCESS, true);
        } catch (Exception e) {
            intent.putExtra(INTENT_EXCEPTION, e);
            intent.putExtra(SUCCESS, false);
        }

        intent.putExtra(READ_RECORDS_SIZE, recordsSize);

        return intent;
    }

    /**
     * Method to insert records with given clientId in their dataOrigin and put SUCCESS as true if
     * insertion is successfule or SUCCESS as false if insertion throws an exception
     *
     * @param queryType - specifies the action, here it should be INSERT_RECORDS_QUERY
     * @param clientId - clientId to be specified in the dataOrigin of the records to be inserted
     * @param context - application context
     * @return Intent to send back to the main app which is running the tests
     */
    private Intent insertRecordsWithGivenClientId(
            String queryType, double clientId, Context context) {
        final Intent intent = new Intent(queryType);

        List<Record> records = getTestRecords(context.getPackageName(), clientId);

        try {
            insertRecords(records, context);
            intent.putExtra(SUCCESS, true);
        } catch (Exception e) {
            intent.putExtra(SUCCESS, false);
        }

        return intent;
    }

    /**
     * Method to read records using data origin filters and add number of records read to the intent
     *
     * @param queryType - specifies the action, here it should be READ_RECORDS_QUERY
     * @param recordClassesToRead - List of Record Class names for the records to be read
     * @param context - application context
     * @return Intent to send back to the main app which is running the tests
     */
    private Intent readRecordsUsingDataOriginFilters(
            String queryType, ArrayList<String> recordClassesToRead, Context context) {
        final Intent intent = new Intent(queryType);

        int recordsSize = 0;
        try {
            for (String recordClass : recordClassesToRead) {
                List<? extends Record> recordsRead =
                        readRecords(
                                new ReadRecordsRequestUsingFilters.Builder<>(
                                                (Class<? extends Record>)
                                                        Class.forName(recordClass))
                                        .addDataOrigins(
                                                new DataOrigin.Builder()
                                                        .setPackageName(context.getPackageName())
                                                        .build())
                                        .build(),
                                context);
                recordsSize += recordsRead.size();
            }
        } catch (Exception e) {
            intent.putExtra(READ_RECORDS_SIZE, 0);
            intent.putExtra(INTENT_EXCEPTION, e);
        }

        intent.putExtra(READ_RECORDS_SIZE, recordsSize);

        return intent;
    }

    /**
     * Method to read changeLogs using dataOriginFilters and add the changeLogToken
     *
     * @param queryType - specifies the action, here it should be
     *     READ_CHANGE_LOGS_USING_DATA_ORIGIN_FILTERS_QUERY
     * @param context - application context
     * @param changeLogToken - Token corresponding to which changeLogs have to be read
     * @return Intent to send back to the main app which is running the tests
     */
    private Intent readChangeLogsUsingDataOriginFilters(
            String queryType, String changeLogToken, Context context) {
        final Intent intent = new Intent(queryType);

        ChangeLogsRequest changeLogsRequest = new ChangeLogsRequest.Builder(changeLogToken).build();

        try {
            ChangeLogsResponse response = getChangeLogs(changeLogsRequest, context);
            intent.putExtra(CHANGE_LOGS_RESPONSE, response);
        } catch (Exception e) {
            intent.putExtra(INTENT_EXCEPTION, e);
        }

        return intent;
    }

    /**
     * Method to get changeLogToken for an app
     *
     * @param queryType - specifies the action, here it should be GET_CHANGE_LOG_TOKEN_QUERY
     * @param pkgName - pkgName of the app whose changeLogs we have to read using the returned token
     * @param context - application context
     * @return - Intent to send back to the main app which is running the tests
     */
    private Intent getChangeLogToken(String queryType, String pkgName, Context context)
            throws Exception {
        final Intent intent = new Intent(queryType);

        ChangeLogTokenResponse tokenResponse =
                TestUtils.getChangeLogToken(
                        new ChangeLogTokenRequest.Builder()
                                .addDataOriginFilter(
                                        new DataOrigin.Builder().setPackageName(pkgName).build())
                                .build(),
                        context);

        intent.putExtra(CHANGE_LOG_TOKEN, tokenResponse.getToken());
        return intent;
    }
}
