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

package com.android.server.healthconnect.storage.datatypehelpers;

import static android.health.connect.Constants.DEFAULT_INT;
import static android.health.connect.Constants.DEFAULT_LONG;
import static android.health.connect.Constants.MAXIMUM_PAGE_SIZE;

import static com.android.server.healthconnect.storage.datatypehelpers.IntervalRecordHelper.END_TIME_COLUMN_NAME;
import static com.android.server.healthconnect.storage.request.ReadTransactionRequest.TYPE_NOT_PRESENT_PACKAGE_NAME;
import static com.android.server.healthconnect.storage.utils.StorageUtils.BLOB_UNIQUE_NON_NULL;
import static com.android.server.healthconnect.storage.utils.StorageUtils.BLOB_UNIQUE_NULL;
import static com.android.server.healthconnect.storage.utils.StorageUtils.INTEGER;
import static com.android.server.healthconnect.storage.utils.StorageUtils.PRIMARY_AUTOINCREMENT;
import static com.android.server.healthconnect.storage.utils.StorageUtils.TEXT_NULL;
import static com.android.server.healthconnect.storage.utils.StorageUtils.getCursorInt;
import static com.android.server.healthconnect.storage.utils.StorageUtils.getCursorLong;
import static com.android.server.healthconnect.storage.utils.StorageUtils.getCursorString;
import static com.android.server.healthconnect.storage.utils.StorageUtils.getCursorUUID;
import static com.android.server.healthconnect.storage.utils.StorageUtils.getDedupeByteBuffer;
import static com.android.server.healthconnect.storage.utils.StorageUtils.supportsPriority;

import android.annotation.NonNull;
import android.content.ContentValues;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.health.connect.AggregateResult;
import android.health.connect.aidl.ReadRecordsRequestParcel;
import android.health.connect.datatypes.AggregationType;
import android.health.connect.datatypes.RecordTypeIdentifier;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.RecordMapper;
import android.os.Trace;
import android.util.ArrayMap;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.android.server.healthconnect.storage.request.AggregateParams;
import com.android.server.healthconnect.storage.request.AggregateTableRequest;
import com.android.server.healthconnect.storage.request.CreateTableRequest;
import com.android.server.healthconnect.storage.request.DeleteTableRequest;
import com.android.server.healthconnect.storage.request.ReadTableRequest;
import com.android.server.healthconnect.storage.request.UpsertTableRequest;
import com.android.server.healthconnect.storage.utils.OrderByClause;
import com.android.server.healthconnect.storage.utils.SqlJoin;
import com.android.server.healthconnect.storage.utils.StorageUtils;
import com.android.server.healthconnect.storage.utils.WhereClauses;

import java.lang.reflect.InvocationTargetException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Parent class for all the helper classes for all the records
 *
 * @hide
 */
public abstract class RecordHelper<T extends RecordInternal<?>> {
    public static final String PRIMARY_COLUMN_NAME = "row_id";
    public static final String UUID_COLUMN_NAME = "uuid";
    public static final String CLIENT_RECORD_ID_COLUMN_NAME = "client_record_id";
    public static final String APP_INFO_ID_COLUMN_NAME = "app_info_id";
    public static final String LAST_MODIFIED_TIME_COLUMN_NAME = "last_modified_time";
    private static final String CLIENT_RECORD_VERSION_COLUMN_NAME = "client_record_version";
    private static final String DEVICE_INFO_ID_COLUMN_NAME = "device_info_id";
    private static final String RECORDING_METHOD_COLUMN_NAME = "recording_method";
    private static final String DEDUPE_HASH_COLUMN_NAME = "dedupe_hash";
    private static final List<Pair<String, Integer>> UNIQUE_COLUMNS_INFO =
            List.of(
                    new Pair<>(DEDUPE_HASH_COLUMN_NAME, UpsertTableRequest.TYPE_BLOB),
                    new Pair<>(UUID_COLUMN_NAME, UpsertTableRequest.TYPE_BLOB));
    private static final String TAG_RECORD_HELPER = "HealthConnectRecordHelper";
    private static final int TRACE_TAG_RECORD_HELPER = TAG_RECORD_HELPER.hashCode();
    @RecordTypeIdentifier.RecordType private final int mRecordIdentifier;

    RecordHelper(@RecordTypeIdentifier.RecordType int recordIdentifier) {
        mRecordIdentifier = recordIdentifier;
    }

    public DeleteTableRequest getDeleteRequestForAutoDelete(int recordAutoDeletePeriodInDays) {
        return new DeleteTableRequest(getMainTableName())
                .setTimeFilter(
                        getStartTimeColumnName(),
                        Instant.EPOCH.toEpochMilli(),
                        Instant.now()
                                .minus(recordAutoDeletePeriodInDays, ChronoUnit.DAYS)
                                .toEpochMilli());
    }

    @RecordTypeIdentifier.RecordType
    public int getRecordIdentifier() {
        return mRecordIdentifier;
    }

    /**
     * Called on DB update. Inheriting classes should implement this if they need to add new columns
     * or tables.
     */
    public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        // empty
    }

    /**
     * @return {@link AggregateTableRequest} corresponding to {@code aggregationType}
     */
    public final AggregateTableRequest getAggregateTableRequest(
            AggregationType<?> aggregationType,
            List<String> packageFilter,
            long startTime,
            long endTime,
            boolean useLocalTime) {
        AggregateParams params = getAggregateParams(aggregationType);
        params.setTimeColumnName(
                useLocalTime ? getLocalStartTimeColumnName() : getStartTimeColumnName());
        params.setExtraTimeColumn(
                useLocalTime ? getLocalEndTimeColumnName() : getEndTimeColumnName());
        params.setOffsetColumnToFetch(getZoneOffsetColumnName());

        if (supportsPriority(mRecordIdentifier, aggregationType.getAggregateOperationType())) {
            List<String> columns =
                    Arrays.asList(
                            getStartTimeColumnName(),
                            END_TIME_COLUMN_NAME,
                            APP_INFO_ID_COLUMN_NAME,
                            LAST_MODIFIED_TIME_COLUMN_NAME);
            params.appendAdditionalColumns(columns);
        }
        if (StorageUtils.isDerivedType(mRecordIdentifier)) {
            params.appendAdditionalColumns(Collections.singletonList(getStartTimeColumnName()));
        }

        return new AggregateTableRequest(params, aggregationType, this, useLocalTime)
                .setPackageFilter(
                        AppInfoHelper.getInstance().getAppInfoIds(packageFilter),
                        APP_INFO_ID_COLUMN_NAME)
                .setTimeFilter(startTime, endTime);
    }

    /**
     * Used to get the Aggregate result for aggregate types
     *
     * @return {@link AggregateResult} for {@link AggregationType}
     */
    public AggregateResult<?> getAggregateResult(
            Cursor cursor, AggregationType<?> aggregationType) {
        return null;
    }

    /**
     * Used to get the Aggregate result for aggregate types where the priority of apps is to be
     * considered for overlapping data for sleep and activity interval records
     *
     * @return {@link AggregateResult} for {@link AggregationType}
     */
    public AggregateResult<?> getAggregateResult(
            Cursor results, AggregationType<?> aggregationType, double total) {
        return null;
    }

    /**
     * Used to calculate and get aggregate results for data types that support derived aggregates
     */
    public double[] deriveAggregate(Cursor cursor, AggregateTableRequest request) {
        return null;
    }

    /**
     * Returns a requests representing the tables that should be created corresponding to this
     * helper
     */
    @NonNull
    public final CreateTableRequest getCreateTableRequest() {
        return new CreateTableRequest(getMainTableName(), getColumnInfo())
                .addForeignKey(
                        DeviceInfoHelper.getInstance().getTableName(),
                        Collections.singletonList(DEVICE_INFO_ID_COLUMN_NAME),
                        Collections.singletonList(PRIMARY_COLUMN_NAME))
                .addForeignKey(
                        AppInfoHelper.TABLE_NAME,
                        Collections.singletonList(APP_INFO_ID_COLUMN_NAME),
                        Collections.singletonList(PRIMARY_COLUMN_NAME))
                .setChildTableRequests(getChildTableCreateRequests())
                .setGeneratedColumnInfo(getGeneratedColumnInfo());
    }

    public UpsertTableRequest getUpsertTableRequest(RecordInternal<?> recordInternal) {
        return getUpsertTableRequest(recordInternal, null);
    }

    @NonNull
    @SuppressWarnings("unchecked")
    public UpsertTableRequest getUpsertTableRequest(
            RecordInternal<?> recordInternal,
            ArrayMap<String, Boolean> extraWritePermissionToStateMap) {
        Trace.traceBegin(
                TRACE_TAG_RECORD_HELPER, TAG_RECORD_HELPER.concat("GetUpsertTableRequest"));
        ContentValues upsertValues = getContentValues((T) recordInternal);
        updateUpsertValuesIfRequired(upsertValues, extraWritePermissionToStateMap);
        UpsertTableRequest upsertTableRequest =
                new UpsertTableRequest(getMainTableName(), upsertValues, UNIQUE_COLUMNS_INFO)
                        .setRequiresUpdateClause(
                                new UpsertTableRequest.IRequiresUpdate() {
                                    @Override
                                    public boolean requiresUpdate(
                                            Cursor cursor,
                                            ContentValues contentValues,
                                            UpsertTableRequest request) {
                                        final UUID newUUID =
                                                StorageUtils.convertBytesToUUID(
                                                        contentValues.getAsByteArray(
                                                                UUID_COLUMN_NAME));
                                        final UUID oldUUID =
                                                StorageUtils.getCursorUUID(
                                                        cursor, UUID_COLUMN_NAME);

                                        if (!Objects.equals(newUUID, oldUUID)) {
                                            // Use old UUID in case of conflicts on de-dupe.
                                            contentValues.put(
                                                    UUID_COLUMN_NAME,
                                                    StorageUtils.convertUUIDToBytes(oldUUID));
                                            request.getRecordInternal().setUuid(oldUUID);
                                            // This means there was a duplication conflict, we want
                                            // to update in this case.
                                            return true;
                                        }

                                        long clientRecordVersion =
                                                StorageUtils.getCursorLong(
                                                        cursor, CLIENT_RECORD_VERSION_COLUMN_NAME);
                                        long newClientRecordVersion =
                                                contentValues.getAsLong(
                                                        CLIENT_RECORD_VERSION_COLUMN_NAME);

                                        return newClientRecordVersion >= clientRecordVersion;
                                    }
                                })
                        .setChildTableRequests(getChildTableUpsertRequests((T) recordInternal))
                        .setHelper(this)
                        .setExtraWritePermissionsStateMapping(extraWritePermissionToStateMap);
        Trace.traceEnd(TRACE_TAG_RECORD_HELPER);
        return upsertTableRequest;
    }

    /* Updates upsert content values based on extra permissions state. */
    protected void updateUpsertValuesIfRequired(
            @NonNull ContentValues values,
            @Nullable ArrayMap<String, Boolean> extraWritePermissionToStateMap) {}

    public List<String> getChildTablesToDeleteOnRecordUpsert(
            ArrayMap<String, Boolean> extraWritePermissionToState) {
        return getAllChildTables();
    }

    @NonNull
    public List<String> getAllChildTables() {
        List<String> childTables = new ArrayList<>();
        for (CreateTableRequest childTableCreateRequest : getChildTableCreateRequests()) {
            populateWithTablesNames(childTableCreateRequest, childTables);
        }

        return childTables;
    }

    @NonNull
    protected List<CreateTableRequest.GeneratedColumnInfo> getGeneratedColumnInfo() {
        return Collections.emptyList();
    }

    private void populateWithTablesNames(
            CreateTableRequest childTableCreateRequest, List<String> childTables) {
        childTables.add(childTableCreateRequest.getTableName());
        for (CreateTableRequest childTableRequest :
                childTableCreateRequest.getChildTableRequests()) {
            populateWithTablesNames(childTableRequest, childTables);
        }
    }

    /**
     * Returns ReadSingleTableRequest for {@code request} and package name {@code packageName}
     *
     */
    public ReadTableRequest getReadTableRequest(
            ReadRecordsRequestParcel request,
            String packageName,
            boolean enforceSelfRead,
            long startDateAccess,
            Map<String, Boolean> extraPermsState) {
        return new ReadTableRequest(getMainTableName())
                .setJoinClause(getJoinForReadRequest())
                .setWhereClause(
                        getReadTableWhereClause(
                                request, packageName, enforceSelfRead, startDateAccess))
                .setOrderBy(getOrderByClause(request))
                .setLimit(getLimitSize(request))
                .setRecordHelper(this)
                .setExtraReadRequests(
                        getExtraDataReadRequests(
                                request, packageName, startDateAccess, extraPermsState));
    }

    /**
     * Logs metrics specific to a record type's insertion/update.
     *
     * @param recordInternals List of records being inserted/updated
     * @param packageName Caller package name
     */
    public void logUpsertMetrics(
            @NonNull List<RecordInternal<?>> recordInternals, @NonNull String packageName) {
        // Do nothing, implement in record specific helpers
    }

    /**
     * Logs metrics specific to a record type's read.
     *
     * @param recordInternals List of records being read
     * @param packageName Caller package name
     */
    public void logReadMetrics(
            @NonNull List<RecordInternal<?>> recordInternals, @NonNull String packageName) {
        // Do nothing, implement in record specific helpers
    }

    /** Returns ReadTableRequest for {@code uuids} */
    public ReadTableRequest getReadTableRequest(List<UUID> uuids, long startDateAccess) {
        return new ReadTableRequest(getMainTableName())
                .setJoinClause(getJoinForReadRequest())
                .setWhereClause(
                        new WhereClauses()
                                .addWhereInClauseWithoutQuotes(
                                        UUID_COLUMN_NAME, StorageUtils.getListOfHexString(uuids))
                                .addWhereLaterThanTimeClause(
                                        getStartTimeColumnName(), startDateAccess))
                .setRecordHelper(this)
                .setExtraReadRequests(getExtraDataReadRequests(uuids, startDateAccess));
    }

    /**
     * Returns a list of ReadSingleTableRequest for {@code request} and package name {@code
     * packageName} to populate extra data. Called in database read requests.
     */
    List<ReadTableRequest> getExtraDataReadRequests(
            ReadRecordsRequestParcel request,
            String packageName,
            long startDateAccess,
            Map<String, Boolean> extraPermsState) {
        return Collections.emptyList();
    }

    /**
     * Returns list if ReadSingleTableRequest for {@code uuids} to populate extra data. Called in
     * change logs read requests.
     */
    List<ReadTableRequest> getExtraDataReadRequests(List<UUID> uuids, long startDateAccess) {
        return Collections.emptyList();
    }

    /**
     * Returns ReadTableRequest for the record corresponding to this helper with a distinct clause
     * on the input column names.
     */
    public ReadTableRequest getReadTableRequestWithDistinctAppInfoIds() {
        return new ReadTableRequest(getMainTableName())
                .setColumnNames(new ArrayList<>(List.of(APP_INFO_ID_COLUMN_NAME)))
                .setDistinctClause(true);
    }

    /** Returns List of Internal records from the cursor */
    @SuppressWarnings("unchecked")
    public List<RecordInternal<?>> getInternalRecords(Cursor cursor, int requestSize) {
        return getInternalRecords(cursor, requestSize, null);
    }

    /** Returns List of Internal records from the cursor */
    @SuppressWarnings("unchecked")
    public List<RecordInternal<?>> getInternalRecords(
            Cursor cursor, int requestSize, Map<Long, String> packageNamesByAppIds) {
        Trace.traceBegin(TRACE_TAG_RECORD_HELPER, TAG_RECORD_HELPER.concat("GetInternalRecords"));
        List<RecordInternal<?>> recordInternalList = new ArrayList<>();

        int count = 0;
        long prevStartTime = DEFAULT_LONG;
        long currentStartTime = DEFAULT_LONG;
        int tempCount = 0;
        List<RecordInternal<?>> tempList = new ArrayList<>();
        while (cursor.moveToNext()) {
            try {
                T record =
                        (T)
                                RecordMapper.getInstance()
                                        .getRecordIdToInternalRecordClassMap()
                                        .get(getRecordIdentifier())
                                        .getConstructor()
                                        .newInstance();
                record.setUuid(getCursorUUID(cursor, UUID_COLUMN_NAME));
                record.setLastModifiedTime(getCursorLong(cursor, LAST_MODIFIED_TIME_COLUMN_NAME));
                record.setClientRecordId(getCursorString(cursor, CLIENT_RECORD_ID_COLUMN_NAME));
                record.setClientRecordVersion(
                        getCursorLong(cursor, CLIENT_RECORD_VERSION_COLUMN_NAME));
                record.setRecordingMethod(getCursorInt(cursor, RECORDING_METHOD_COLUMN_NAME));
                record.setRowId(getCursorInt(cursor, PRIMARY_COLUMN_NAME));
                long deviceInfoId = getCursorLong(cursor, DEVICE_INFO_ID_COLUMN_NAME);
                DeviceInfoHelper.getInstance().populateRecordWithValue(deviceInfoId, record);
                long appInfoId = getCursorLong(cursor, APP_INFO_ID_COLUMN_NAME);
                AppInfoHelper.getInstance()
                        .populateRecordWithValue(appInfoId, record, packageNamesByAppIds);
                populateRecordValue(cursor, record);

                prevStartTime = currentStartTime;
                currentStartTime = getCursorLong(cursor, getStartTimeColumnName());
                if (prevStartTime == DEFAULT_LONG || prevStartTime == currentStartTime) {
                    // Fetch and add records with same startTime to tempList
                    tempList.add(record);
                    tempCount++;
                } else {
                    if (count == 0) {
                        // items in tempList having startTime same as the first record from cursor
                        // is added to final list.
                        // This makes sure that we return at least 1 record if the count of
                        // records with startTime same as second record exceeds requestSize.
                        recordInternalList.addAll(tempList);
                        count = tempCount;
                        tempList.clear();
                        tempCount = 0;
                        if (count >= requestSize) {
                            // startTime of current record should be fetched for pageToken
                            cursor.moveToPrevious();
                            break;
                        }
                        tempList.add(record);
                        tempCount = 1;
                    } else if (tempCount + count <= requestSize) {
                        // Makes sure after adding records in tempList with same starTime
                        // the count does not exceed requestSize
                        recordInternalList.addAll(tempList);
                        count += tempCount;
                        tempList.clear();
                        tempCount = 0;
                        if (count >= requestSize) {
                            // After adding records if count is equal to requestSize then startTime
                            // of current fetched record should be the next page token.
                            cursor.moveToPrevious();
                            break;
                        }
                        tempList.add(record);
                        tempCount = 1;
                    } else {
                        // If adding records in tempList makes count > requestSize, then ignore temp
                        // list and startTime of records in temp list should be the next page token.
                        tempList.clear();
                        int lastposition = cursor.getPosition();
                        cursor.moveToPosition(lastposition - 2);
                        break;
                    }
                }
            } catch (InstantiationException
                    | IllegalAccessException
                    | NoSuchMethodException
                    | InvocationTargetException exception) {
                throw new IllegalArgumentException(exception);
            }
        }
        if (!tempList.isEmpty()) {
            if (tempCount + count <= requestSize) {
                // If reached end of cursor while fetching records then add it to final list
                recordInternalList.addAll(tempList);
            } else {
                // If reached end of cursor while fetching and adding it will exceed requestSize
                // then ignore them,startTime of the last record will be pageToken for next read.
                cursor.moveToPosition(cursor.getCount() - 2);
            }
        }
        Trace.traceEnd(TRACE_TAG_RECORD_HELPER);
        return recordInternalList;
    }

    /** Returns is the read of this record type is enabled */
    public boolean isRecordOperationsEnabled() {
        return true;
    }

    /** Populate internalRecords fields using extraDataCursor */
    @SuppressWarnings("unchecked")
    public void updateInternalRecordsWithExtraFields(
            List<RecordInternal<?>> internalRecords, Cursor cursorExtraData, String tableName) {
        readExtraData((List<T>) internalRecords, cursorExtraData, tableName);
    }

    public DeleteTableRequest getDeleteTableRequest(
            List<String> packageFilters,
            long startTime,
            long endTime,
            boolean usesLocalTimeFilter) {
        final String timeColumnName =
                usesLocalTimeFilter ? getLocalStartTimeColumnName() : getStartTimeColumnName();
        return new DeleteTableRequest(getMainTableName(), getRecordIdentifier())
                .setTimeFilter(timeColumnName, startTime, endTime)
                .setPackageFilter(
                        APP_INFO_ID_COLUMN_NAME,
                        AppInfoHelper.getInstance().getAppInfoIds(packageFilters))
                .setRequiresUuId(UUID_COLUMN_NAME);
    }

    public DeleteTableRequest getDeleteTableRequest(List<UUID> ids) {
        return new DeleteTableRequest(getMainTableName(), getRecordIdentifier())
                .setIds(UUID_COLUMN_NAME, StorageUtils.getListOfHexString(ids))
                .setRequiresUuId(UUID_COLUMN_NAME)
                .setEnforcePackageCheck(APP_INFO_ID_COLUMN_NAME, UUID_COLUMN_NAME);
    }

    public abstract String getDurationGroupByColumnName();

    public abstract String getPeriodGroupByColumnName();

    public abstract String getStartTimeColumnName();

    public abstract String getLocalStartTimeColumnName();

    public String getLocalEndTimeColumnName() {
        return null;
    }

    public String getEndTimeColumnName() {
        return null;
    }

    /** Populate internalRecords with extra data. */
    void readExtraData(List<T> internalRecords, Cursor cursorExtraData, String tableName) {}

    /**
     * Child classes should implement this if it wants to create additional tables, apart from the
     * main table.
     */
    @NonNull
    List<CreateTableRequest> getChildTableCreateRequests() {
        return Collections.emptyList();
    }

    /** Returns the table name to be created corresponding to this helper */
    @NonNull
    abstract String getMainTableName();

    /** Returns the information required to perform aggregate operation. */
    AggregateParams getAggregateParams(AggregationType<?> aggregateRequest) {
        return null;
    }

    /**
     * This implementation should return the column names with which the table should be created.
     *
     * <p>NOTE: New columns can only be added via onUpgrade. Why? Consider what happens if a table
     * already exists on the device
     *
     * <p>PLEASE DON'T USE THIS METHOD TO ADD NEW COLUMNS
     */
    @NonNull
    abstract List<Pair<String, String>> getSpecificColumnInfo();

    /**
     * Child classes implementation should add the values of {@code recordInternal} that needs to be
     * populated in the DB to {@code contentValues}.
     */
    abstract void populateContentValues(
            @NonNull ContentValues contentValues, @NonNull T recordInternal);

    /**
     * Child classes implementation should populate the values to the {@code record} using the
     * cursor {@code cursor} queried from the DB .
     */
    abstract void populateRecordValue(@NonNull Cursor cursor, @NonNull T recordInternal);

    List<UpsertTableRequest> getChildTableUpsertRequests(T record) {
        return Collections.emptyList();
    }

    SqlJoin getJoinForReadRequest() {
        return null;
    }

    private int getLimitSize(ReadRecordsRequestParcel request) {
        if (request.getRecordIdFiltersParcel() == null) {
            return request.getPageSize();
        } else {
            return MAXIMUM_PAGE_SIZE;
        }
    }

    WhereClauses getReadTableWhereClause(
            ReadRecordsRequestParcel request,
            String packageName,
            boolean enforceSelfRead,
            long startDateAccess) {
        if (request.getRecordIdFiltersParcel() == null) {
            List<Long> appIds =
                    AppInfoHelper.getInstance().getAppInfoIds(request.getPackageFilters()).stream()
                            .distinct()
                            .collect(Collectors.toList());
            if (enforceSelfRead) {
                appIds =
                        AppInfoHelper.getInstance()
                                .getAppInfoIds(Collections.singletonList(packageName));
            }
            if (appIds.size() == 1 && appIds.get(0) == DEFAULT_INT) {
                throw new TypeNotPresentException(TYPE_NOT_PRESENT_PACKAGE_NAME, new Throwable());
            }

            WhereClauses clauses =
                    new WhereClauses().addWhereInLongsClause(APP_INFO_ID_COLUMN_NAME, appIds);

            if (request.getPageToken() != DEFAULT_LONG) {
                // Since pageToken passed contains detail of sort order. Actual token value for read
                // is calculated back from the requested pageToken based on sort order.
                if (request.isAscending()) {
                    clauses.addWhereGreaterThanOrEqualClause(
                            getStartTimeColumnName(), request.getPageToken() / 2);
                } else {
                    clauses.addWhereLessThanOrEqualClause(
                            getStartTimeColumnName(), (request.getPageToken() - 1) / 2);
                }
            }

            if (request.usesLocalTimeFilter()) {
                clauses.addWhereGreaterThanOrEqualClause(getStartTimeColumnName(), startDateAccess);
                clauses.addWhereBetweenClause(
                        getLocalStartTimeColumnName(),
                        request.getStartTime(),
                        request.getEndTime());
            } else {
                clauses.addWhereBetweenTimeClause(
                        getStartTimeColumnName(), startDateAccess, request.getEndTime());
            }

            return clauses;
        }

        // Since for now we don't support mixing IDs and filters, we need to look for IDs now
        List<UUID> ids =
                request.getRecordIdFiltersParcel().getRecordIdFilters().stream()
                        .map(
                                (recordIdFilter) ->
                                        StorageUtils.getUUIDFor(recordIdFilter, packageName))
                        .collect(Collectors.toList());
        WhereClauses whereClauses =
                new WhereClauses()
                        .addWhereInClauseWithoutQuotes(
                                UUID_COLUMN_NAME, StorageUtils.getListOfHexString(ids));

        if (enforceSelfRead) {
            long id = AppInfoHelper.getInstance().getAppInfoId(packageName);
            if (id == DEFAULT_LONG) {
                throw new TypeNotPresentException(TYPE_NOT_PRESENT_PACKAGE_NAME, new Throwable());
            }
            whereClauses.addWhereInLongsClause(
                    APP_INFO_ID_COLUMN_NAME, Collections.singletonList(id));
            return whereClauses.addWhereLaterThanTimeClause(
                    getStartTimeColumnName(), startDateAccess);
        }
        return whereClauses;
    }

    abstract String getZoneOffsetColumnName();

    private OrderByClause getOrderByClause(ReadRecordsRequestParcel request) {
        OrderByClause orderByClause = new OrderByClause();
        if (request.getRecordIdFiltersParcel() == null) {
            orderByClause.addOrderByClause(getStartTimeColumnName(), request.isAscending());
        }
        return orderByClause;
    }

    @NonNull
    private ContentValues getContentValues(@NonNull T recordInternal) {
        ContentValues recordContentValues = new ContentValues();

        recordContentValues.put(
                UUID_COLUMN_NAME, StorageUtils.convertUUIDToBytes(recordInternal.getUuid()));
        recordContentValues.put(
                LAST_MODIFIED_TIME_COLUMN_NAME, recordInternal.getLastModifiedTime());
        recordContentValues.put(CLIENT_RECORD_ID_COLUMN_NAME, recordInternal.getClientRecordId());
        recordContentValues.put(
                CLIENT_RECORD_VERSION_COLUMN_NAME, recordInternal.getClientRecordVersion());
        recordContentValues.put(RECORDING_METHOD_COLUMN_NAME, recordInternal.getRecordingMethod());
        recordContentValues.put(DEVICE_INFO_ID_COLUMN_NAME, recordInternal.getDeviceInfoId());
        recordContentValues.put(APP_INFO_ID_COLUMN_NAME, recordInternal.getAppInfoId());
        recordContentValues.put(DEDUPE_HASH_COLUMN_NAME, getDedupeByteBuffer(recordInternal));

        populateContentValues(recordContentValues, recordInternal);

        return recordContentValues;
    }

    /**
     * This implementation should return the column names with which the table should be created.
     *
     * <p>NOTE: New columns can only be added via onUpgrade. Why? Consider what happens if a table
     * already exists on the device
     *
     * <p>PLEASE DON'T USE THIS METHOD TO ADD NEW COLUMNS
     */
    @NonNull
    private List<Pair<String, String>> getColumnInfo() {
        ArrayList<Pair<String, String>> columnInfo = new ArrayList<>();
        columnInfo.add(new Pair<>(PRIMARY_COLUMN_NAME, PRIMARY_AUTOINCREMENT));
        columnInfo.add(new Pair<>(UUID_COLUMN_NAME, BLOB_UNIQUE_NON_NULL));
        columnInfo.add(new Pair<>(LAST_MODIFIED_TIME_COLUMN_NAME, INTEGER));
        columnInfo.add(new Pair<>(CLIENT_RECORD_ID_COLUMN_NAME, TEXT_NULL));
        columnInfo.add(new Pair<>(CLIENT_RECORD_VERSION_COLUMN_NAME, TEXT_NULL));
        columnInfo.add(new Pair<>(DEVICE_INFO_ID_COLUMN_NAME, INTEGER));
        columnInfo.add(new Pair<>(APP_INFO_ID_COLUMN_NAME, INTEGER));
        columnInfo.add(new Pair<>(RECORDING_METHOD_COLUMN_NAME, INTEGER));
        columnInfo.add(new Pair<>(DEDUPE_HASH_COLUMN_NAME, BLOB_UNIQUE_NULL));

        columnInfo.addAll(getSpecificColumnInfo());

        return columnInfo;
    }

    /** Checks that operation with current record type are supported. */
    public void checkRecordOperationsAreEnabled(RecordInternal<?> recordInternal) {}

    /** Returns permissions required to read extra record data. */
    public List<String> getExtraReadPermissions() {
        return Collections.emptyList();
    }

    /** Returns all extra permissions associated with current record type. */
    public List<String> getExtraWritePermissions() {
        return Collections.emptyList();
    }

    /** Returns extra permissions required to write given record. */
    public List<String> getRequiredExtraWritePermissions(RecordInternal<?> recordInternal) {
        return Collections.emptyList();
    }
}
