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

package com.android.server.healthconnect;

import static android.Manifest.permission.MIGRATE_HEALTH_CONNECT_DATA;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.health.connect.Constants.DEFAULT_LONG;
import static android.health.connect.Constants.READ;
import static android.health.connect.HealthConnectException.ERROR_INTERNAL;
import static android.health.connect.HealthConnectException.ERROR_SECURITY;
import static android.health.connect.HealthPermissions.MANAGE_HEALTH_DATA_PERMISSION;

import static com.android.server.healthconnect.logging.HealthConnectServiceLogger.ApiMethods.DELETE_DATA;
import static com.android.server.healthconnect.logging.HealthConnectServiceLogger.ApiMethods.GET_CHANGES;
import static com.android.server.healthconnect.logging.HealthConnectServiceLogger.ApiMethods.GET_CHANGES_TOKEN;
import static com.android.server.healthconnect.logging.HealthConnectServiceLogger.ApiMethods.INSERT_DATA;
import static com.android.server.healthconnect.logging.HealthConnectServiceLogger.ApiMethods.READ_AGGREGATED_DATA;
import static com.android.server.healthconnect.logging.HealthConnectServiceLogger.ApiMethods.READ_DATA;
import static com.android.server.healthconnect.logging.HealthConnectServiceLogger.ApiMethods.UPDATE_DATA;

import android.Manifest;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.AttributionSource;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.database.sqlite.SQLiteException;
import android.health.connect.Constants;
import android.health.connect.FetchDataOriginsPriorityOrderResponse;
import android.health.connect.HealthConnectDataState;
import android.health.connect.HealthConnectException;
import android.health.connect.HealthConnectManager;
import android.health.connect.HealthConnectManager.DataDownloadState;
import android.health.connect.HealthDataCategory;
import android.health.connect.HealthPermissions;
import android.health.connect.RecordTypeInfoResponse;
import android.health.connect.accesslog.AccessLog;
import android.health.connect.accesslog.AccessLogsResponseParcel;
import android.health.connect.aidl.ActivityDatesRequestParcel;
import android.health.connect.aidl.ActivityDatesResponseParcel;
import android.health.connect.aidl.AggregateDataRequestParcel;
import android.health.connect.aidl.ApplicationInfoResponseParcel;
import android.health.connect.aidl.DeleteUsingFiltersRequestParcel;
import android.health.connect.aidl.GetPriorityResponseParcel;
import android.health.connect.aidl.HealthConnectExceptionParcel;
import android.health.connect.aidl.IAccessLogsResponseCallback;
import android.health.connect.aidl.IActivityDatesResponseCallback;
import android.health.connect.aidl.IAggregateRecordsResponseCallback;
import android.health.connect.aidl.IApplicationInfoResponseCallback;
import android.health.connect.aidl.IChangeLogsResponseCallback;
import android.health.connect.aidl.IDataStagingFinishedCallback;
import android.health.connect.aidl.IEmptyResponseCallback;
import android.health.connect.aidl.IGetChangeLogTokenCallback;
import android.health.connect.aidl.IGetHealthConnectDataStateCallback;
import android.health.connect.aidl.IGetHealthConnectMigrationUiStateCallback;
import android.health.connect.aidl.IGetPriorityResponseCallback;
import android.health.connect.aidl.IHealthConnectService;
import android.health.connect.aidl.IInsertRecordsResponseCallback;
import android.health.connect.aidl.IMigrationCallback;
import android.health.connect.aidl.IReadRecordsResponseCallback;
import android.health.connect.aidl.IRecordTypeInfoResponseCallback;
import android.health.connect.aidl.InsertRecordsResponseParcel;
import android.health.connect.aidl.ReadRecordsRequestParcel;
import android.health.connect.aidl.ReadRecordsResponseParcel;
import android.health.connect.aidl.RecordIdFiltersParcel;
import android.health.connect.aidl.RecordTypeInfoResponseParcel;
import android.health.connect.aidl.RecordsParcel;
import android.health.connect.aidl.UpdatePriorityRequestParcel;
import android.health.connect.changelog.ChangeLogTokenRequest;
import android.health.connect.changelog.ChangeLogTokenResponse;
import android.health.connect.changelog.ChangeLogsRequest;
import android.health.connect.changelog.ChangeLogsResponse;
import android.health.connect.changelog.ChangeLogsResponse.DeletedLog;
import android.health.connect.datatypes.AppInfo;
import android.health.connect.datatypes.DataOrigin;
import android.health.connect.datatypes.Record;
import android.health.connect.internal.datatypes.RecordInternal;
import android.health.connect.internal.datatypes.utils.AggregationTypeIdMapper;
import android.health.connect.internal.datatypes.utils.RecordMapper;
import android.health.connect.internal.datatypes.utils.RecordTypePermissionCategoryMapper;
import android.health.connect.migration.HealthConnectMigrationUiState;
import android.health.connect.migration.MigrationEntityParcel;
import android.health.connect.migration.MigrationException;
import android.health.connect.ratelimiter.RateLimiter;
import android.health.connect.ratelimiter.RateLimiter.QuotaCategory;
import android.health.connect.ratelimiter.RateLimiterException;
import android.health.connect.restore.BackupFileNamesSet;
import android.health.connect.restore.StageRemoteDataException;
import android.health.connect.restore.StageRemoteDataRequest;
import android.os.Binder;
import android.os.ParcelFileDescriptor;
import android.os.Process;
import android.os.RemoteException;
import android.os.Trace;
import android.os.UserHandle;
import android.permission.PermissionManager;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.Log;
import android.util.Pair;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.LocalManagerRegistry;
import com.android.server.appop.AppOpsManagerLocal;
import com.android.server.healthconnect.backuprestore.BackupRestore;
import com.android.server.healthconnect.logging.HealthConnectServiceLogger;
import com.android.server.healthconnect.migration.DataMigrationManager;
import com.android.server.healthconnect.migration.MigrationCleaner;
import com.android.server.healthconnect.migration.MigrationStateManager;
import com.android.server.healthconnect.migration.MigrationUiStateManager;
import com.android.server.healthconnect.migration.PriorityMigrationHelper;
import com.android.server.healthconnect.permission.DataPermissionEnforcer;
import com.android.server.healthconnect.permission.FirstGrantTimeManager;
import com.android.server.healthconnect.permission.HealthConnectPermissionHelper;
import com.android.server.healthconnect.storage.AutoDeleteService;
import com.android.server.healthconnect.storage.TransactionManager;
import com.android.server.healthconnect.storage.datatypehelpers.AccessLogsHelper;
import com.android.server.healthconnect.storage.datatypehelpers.ActivityDateHelper;
import com.android.server.healthconnect.storage.datatypehelpers.AppInfoHelper;
import com.android.server.healthconnect.storage.datatypehelpers.ChangeLogsHelper;
import com.android.server.healthconnect.storage.datatypehelpers.ChangeLogsRequestHelper;
import com.android.server.healthconnect.storage.datatypehelpers.DeviceInfoHelper;
import com.android.server.healthconnect.storage.datatypehelpers.HealthDataCategoryPriorityHelper;
import com.android.server.healthconnect.storage.datatypehelpers.MigrationEntityHelper;
import com.android.server.healthconnect.storage.datatypehelpers.RecordHelper;
import com.android.server.healthconnect.storage.request.AggregateTransactionRequest;
import com.android.server.healthconnect.storage.request.DeleteTransactionRequest;
import com.android.server.healthconnect.storage.request.ReadTransactionRequest;
import com.android.server.healthconnect.storage.request.UpsertTransactionRequest;
import com.android.server.healthconnect.storage.utils.RecordHelperProvider;

import java.io.IOException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

/**
 * IHealthConnectService's implementation
 *
 * @hide
 */
final class HealthConnectServiceImpl extends IHealthConnectService.Stub {
    private static final String TAG = "HealthConnectService";
    // Permission for test api for deleting staged data
    private static final String DELETE_STAGED_HEALTH_CONNECT_REMOTE_DATA_PERMISSION =
            "android.permission.DELETE_STAGED_HEALTH_CONNECT_REMOTE_DATA";
    // Allows an application to act as a backup inter-agent to send and receive HealthConnect data
    private static final String HEALTH_CONNECT_BACKUP_INTER_AGENT_PERMISSION =
            "android.permission.HEALTH_CONNECT_BACKUP_INTER_AGENT";

    private static final String TAG_INSERT = "HealthConnectInsert";
    private static final String TAG_READ = "HealthConnectRead";
    private static final String TAG_GRANT_PERMISSION = "HealthConnectGrantReadPermissions";
    private static final String TAG_READ_PERMISSION = "HealthConnectReadPermission";
    private static final String TAG_INSERT_SUBTASKS = "HealthConnectInsertSubtasks";

    private static final String TAG_DELETE_SUBTASKS = "HealthConnectDeleteSubtasks";
    private static final String TAG_READ_SUBTASKS = "HealthConnectReadSubtasks";
    private static final int TRACE_TAG_INSERT = TAG_INSERT.hashCode();
    private static final int TRACE_TAG_READ = TAG_READ.hashCode();
    private static final int TRACE_TAG_GRANT_PERMISSION = TAG_GRANT_PERMISSION.hashCode();
    private static final int TRACE_TAG_READ_PERMISSION = TAG_READ_PERMISSION.hashCode();
    private static final int TRACE_TAG_INSERT_SUBTASKS = TAG_INSERT_SUBTASKS.hashCode();
    private static final int TRACE_TAG_DELETE_SUBTASKS = TAG_DELETE_SUBTASKS.hashCode();
    private static final int TRACE_TAG_READ_SUBTASKS = TAG_READ_SUBTASKS.hashCode();

    private final TransactionManager mTransactionManager;
    private final HealthConnectPermissionHelper mPermissionHelper;
    private final FirstGrantTimeManager mFirstGrantTimeManager;
    private final Context mContext;
    private final PermissionManager mPermissionManager;

    private final BackupRestore mBackupRestore;
    private final MigrationStateManager mMigrationStateManager;

    private final DataPermissionEnforcer mDataPermissionEnforcer;

    private final AppOpsManagerLocal mAppOpsManagerLocal;
    private final MigrationUiStateManager mMigrationUiStateManager;

    private volatile UserHandle mCurrentForegroundUser;

    HealthConnectServiceImpl(
            TransactionManager transactionManager,
            HealthConnectPermissionHelper permissionHelper,
            MigrationCleaner migrationCleaner,
            FirstGrantTimeManager firstGrantTimeManager,
            MigrationStateManager migrationStateManager,
            MigrationUiStateManager migrationUiStateManager,
            Context context) {
        mTransactionManager = transactionManager;
        mPermissionHelper = permissionHelper;
        mFirstGrantTimeManager = firstGrantTimeManager;
        mContext = context;
        mCurrentForegroundUser = context.getUser();
        mPermissionManager = mContext.getSystemService(PermissionManager.class);
        mMigrationStateManager = migrationStateManager;
        mDataPermissionEnforcer = new DataPermissionEnforcer(mPermissionManager, mContext);
        mAppOpsManagerLocal = LocalManagerRegistry.getManager(AppOpsManagerLocal.class);
        mBackupRestore =
                new BackupRestore(mFirstGrantTimeManager, mMigrationStateManager, mContext);
        mMigrationUiStateManager = migrationUiStateManager;
        migrationCleaner.attachTo(migrationStateManager);
        mMigrationUiStateManager.attachTo(migrationStateManager);
    }

    public void onUserSwitching(UserHandle currentForegroundUser) {
        mCurrentForegroundUser = currentForegroundUser;
        mBackupRestore.setupForUser(currentForegroundUser);
    }

    @Override
    public void grantHealthPermission(
            @NonNull String packageName, @NonNull String permissionName, @NonNull UserHandle user) {
        throwIllegalStateExceptionIfDataSyncInProgress();
        Trace.traceBegin(TRACE_TAG_GRANT_PERMISSION, TAG_GRANT_PERMISSION);
        mPermissionHelper.grantHealthPermission(packageName, permissionName, user);
        Trace.traceEnd(TRACE_TAG_GRANT_PERMISSION);
    }

    @Override
    public void revokeHealthPermission(
            @NonNull String packageName,
            @NonNull String permissionName,
            @Nullable String reason,
            @NonNull UserHandle user) {
        throwIllegalStateExceptionIfDataSyncInProgress();
        mPermissionHelper.revokeHealthPermission(packageName, permissionName, reason, user);
    }

    @Override
    public void revokeAllHealthPermissions(
            @NonNull String packageName, @Nullable String reason, @NonNull UserHandle user) {
        throwIllegalStateExceptionIfDataSyncInProgress();
        mPermissionHelper.revokeAllHealthPermissions(packageName, reason, user);
    }

    @Override
    public List<String> getGrantedHealthPermissions(
            @NonNull String packageName, @NonNull UserHandle user) {
        throwIllegalStateExceptionIfDataSyncInProgress();
        Trace.traceBegin(TRACE_TAG_READ_PERMISSION, TAG_READ_PERMISSION);
        List<String> grantedPermissions =
                mPermissionHelper.getGrantedHealthPermissions(packageName, user);
        Trace.traceEnd(TRACE_TAG_READ_PERMISSION);
        return grantedPermissions;
    }

    @Override
    public long getHistoricalAccessStartDateInMilliseconds(
            @NonNull String packageName, @NonNull UserHandle userHandle) {
        throwIllegalStateExceptionIfDataSyncInProgress();
        Instant date = mPermissionHelper.getHealthDataStartDateAccess(packageName, userHandle);
        if (date == null) {
            return Constants.DEFAULT_LONG;
        } else {
            return date.toEpochMilli();
        }
    }

    /**
     * Inserts {@code recordsParcel} into the HealthConnect database.
     *
     * @param recordsParcel parcel for list of records to be inserted.
     * @param callback Callback to receive result of performing this operation. The keys returned in
     *     {@link InsertRecordsResponseParcel} are the unique IDs of the input records. The values
     *     are in same order as {@code record}. In case of an error or a permission failure the
     *     HealthConnect service, {@link IInsertRecordsResponseCallback#onError} will be invoked
     *     with a {@link HealthConnectExceptionParcel}.
     */
    @Override
    public void insertRecords(
            @NonNull AttributionSource attributionSource,
            @NonNull RecordsParcel recordsParcel,
            @NonNull IInsertRecordsResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        final HealthConnectServiceLogger.Builder builder =
                new HealthConnectServiceLogger.Builder(false, INSERT_DATA)
                        .setPackageName(attributionSource.getPackageName());

        HealthConnectThreadScheduler.schedule(
                mContext,
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        verifyPackageNameFromUid(uid, attributionSource);
                        if (hasDataManagementPermission(uid, pid)) {
                            throw new SecurityException(
                                    "Apps with android.permission.MANAGE_HEALTH_DATA permission are"
                                            + " not allowed to insert records");
                        }
                        enforceMemoryRateLimit(
                                recordsParcel.getRecordsSize(),
                                recordsParcel.getRecordsChunkSize());
                        final List<RecordInternal<?>> recordInternals = recordsParcel.getRecords();
                        builder.setNumberOfRecords(recordInternals.size());
                        throwExceptionIfDataSyncInProgress();
                        mDataPermissionEnforcer.enforceRecordsWritePermissions(
                                recordInternals, attributionSource);
                        boolean isInForeground = mAppOpsManagerLocal.isUidInForeground(uid);
                        tryAcquireApiCallQuota(
                                uid, QuotaCategory.QUOTA_CATEGORY_WRITE, isInForeground, builder);
                        Trace.traceBegin(TRACE_TAG_INSERT, TAG_INSERT);
                        UpsertTransactionRequest insertRequest =
                                new UpsertTransactionRequest(
                                        attributionSource.getPackageName(),
                                        recordInternals,
                                        mContext,
                                        /* isInsertRequest */ true,
                                        mDataPermissionEnforcer
                                                .collectExtraWritePermissionStateMapping(
                                                        recordInternals, attributionSource));
                        List<String> uuids = mTransactionManager.insertAll(insertRequest);
                        tryAndReturnResult(callback, uuids, builder);

                        HealthConnectThreadScheduler.scheduleInternalTask(
                                () -> postInsertTasks(attributionSource, recordsParcel));

                        finishDataDeliveryWriteRecords(recordInternals, attributionSource);
                        logRecordTypeSpecificUpsertMetrics(
                                recordInternals, attributionSource.getPackageName());
                        builder.setDataTypesFromRecordInternals(recordInternals);
                    } catch (SQLiteException sqLiteException) {
                        builder.setHealthDataServiceApiStatusError(HealthConnectException.ERROR_IO);
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_SECURITY);
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        builder.setHealthDataServiceApiStatusError(
                                healthConnectException.getErrorCode());
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception e) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, ERROR_INTERNAL);
                    } finally {
                        Trace.traceEnd(TRACE_TAG_INSERT);
                        builder.build().log();
                    }
                },
                uid,
                false);
    }

    private void postInsertTasks(
            @NonNull AttributionSource attributionSource, @NonNull RecordsParcel recordsParcel) {
        Trace.traceBegin(TRACE_TAG_INSERT_SUBTASKS, TAG_INSERT.concat("PostInsertTasks"));

        ActivityDateHelper.getInstance().insertRecordDate(recordsParcel.getRecords());
        Set<Integer> recordsTypesInsertedSet =
                recordsParcel.getRecords().stream()
                        .map(RecordInternal::getRecordType)
                        .collect(Collectors.toSet());
        // Update AppInfo table with the record types of records inserted in the request for the
        // current package.
        AppInfoHelper.getInstance()
                .updateAppInfoRecordTypesUsedOnInsert(
                        recordsTypesInsertedSet, attributionSource.getPackageName());

        Trace.traceEnd(TRACE_TAG_INSERT_SUBTASKS);
    }

    /**
     * Returns aggregation results based on the {@code request} into the HealthConnect database.
     *
     * @param request represents the request using which the aggregation is to be performed.
     * @param callback Callback to receive result of performing this operation.
     */
    public void aggregateRecords(
            @NonNull AttributionSource attributionSource,
            AggregateDataRequestParcel request,
            IAggregateRecordsResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        final boolean holdsDataManagementPermission = hasDataManagementPermission(uid, pid);
        final HealthConnectServiceLogger.Builder builder =
                new HealthConnectServiceLogger.Builder(
                                holdsDataManagementPermission, READ_AGGREGATED_DATA)
                        .setPackageName(attributionSource.getPackageName());

        HealthConnectThreadScheduler.schedule(
                mContext,
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        verifyPackageNameFromUid(uid, attributionSource);
                        builder.setNumberOfRecords(request.getAggregateIds().length);
                        throwExceptionIfDataSyncInProgress();
                        List<Integer> recordTypesToTest = new ArrayList<>();
                        for (int aggregateId : request.getAggregateIds()) {
                            recordTypesToTest.addAll(
                                    AggregationTypeIdMapper.getInstance()
                                            .getAggregationTypeFor(aggregateId)
                                            .getApplicableRecordTypeIds());
                        }

                        if (!holdsDataManagementPermission) {
                            boolean isInForeground = mAppOpsManagerLocal.isUidInForeground(uid);
                            if (!isInForeground) {
                                throwSecurityException(
                                        attributionSource.getPackageName()
                                                + "must be in foreground to call aggregate method");
                            }
                            mDataPermissionEnforcer.enforceRecordIdsReadPermissions(
                                    recordTypesToTest, attributionSource);
                            tryAcquireApiCallQuota(
                                    uid,
                                    RateLimiter.QuotaCategory.QUOTA_CATEGORY_READ,
                                    isInForeground,
                                    builder);
                        }
                        callback.onResult(
                                new AggregateTransactionRequest(
                                                attributionSource.getPackageName(), request)
                                        .getAggregateDataResponseParcel());
                        finishDataDeliveryRead(recordTypesToTest, attributionSource);
                        builder.setDataTypesFromRecordTypes(recordTypesToTest)
                                .setHealthDataServiceApiStatusSuccess();
                    } catch (SQLiteException sqLiteException) {
                        builder.setHealthDataServiceApiStatusError(HealthConnectException.ERROR_IO);
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_SECURITY);
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        builder.setHealthDataServiceApiStatusError(
                                healthConnectException.getErrorCode());
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception e) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, ERROR_INTERNAL);
                    } finally {
                        builder.build().log();
                    }
                },
                uid,
                holdsDataManagementPermission);
    }

    /**
     * Read records {@code recordsParcel} from HealthConnect database.
     *
     * @param request ReadRecordsRequestParcel is parcel for the request object containing {@link
     *     RecordIdFiltersParcel}.
     * @param callback Callback to receive result of performing this operation. The records are
     *     returned in {@link RecordsParcel} . In case of an error or a permission failure the
     *     HealthConnect service, {@link IReadRecordsResponseCallback#onError} will be invoked with
     *     a {@link HealthConnectExceptionParcel}.
     */
    @Override
    public void readRecords(
            @NonNull AttributionSource attributionSource,
            @NonNull ReadRecordsRequestParcel request,
            @NonNull IReadRecordsResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        final boolean holdsDataManagementPermission = hasDataManagementPermission(uid, pid);
        final HealthConnectServiceLogger.Builder builder =
                new HealthConnectServiceLogger.Builder(holdsDataManagementPermission, READ_DATA)
                        .setPackageName(attributionSource.getPackageName());

        HealthConnectThreadScheduler.schedule(
                mContext,
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        verifyPackageNameFromUid(uid, attributionSource);
                        throwExceptionIfDataSyncInProgress();
                        AtomicBoolean enforceSelfRead = new AtomicBoolean();
                        if (!holdsDataManagementPermission) {
                            boolean isInForeground = mAppOpsManagerLocal.isUidInForeground(uid);
                            // If requesting app has only write permission allowed but no read
                            // permission for the record type or if app is not in foreground then
                            // allow to read its own records.
                            enforceSelfRead.set(
                                    mDataPermissionEnforcer.enforceReadAccessAndGetEnforceSelfRead(
                                                    request.getRecordType(), attributionSource)
                                            || !isInForeground);
                            if (Constants.DEBUG) {
                                Slog.d(
                                        TAG,
                                        "Enforce self read for package "
                                                + attributionSource.getPackageName()
                                                + ":"
                                                + enforceSelfRead.get());
                            }
                            tryAcquireApiCallQuota(
                                    uid,
                                    QuotaCategory.QUOTA_CATEGORY_READ,
                                    isInForeground,
                                    builder);
                        }
                        final Map<String, Boolean> extraReadPermsToGrantState =
                                Collections.unmodifiableMap(
                                        mDataPermissionEnforcer
                                                .collectExtraReadPermissionToStateMapping(
                                                        request.getRecordType(),
                                                        attributionSource));

                        Trace.traceBegin(TRACE_TAG_READ, TAG_READ);
                        try {
                            long startDateAccess = request.getStartTime();
                            if (!holdsDataManagementPermission) {
                                Instant startInstant =
                                        mPermissionHelper.getHealthDataStartDateAccess(
                                                attributionSource.getPackageName(), userHandle);
                                if (startInstant == null) {
                                    throwExceptionIncorrectPermissionState();
                                }

                                // Always set the startDateAccess for local time filter, as for
                                // local date time we use it in conjunction with the time filter
                                // start-time
                                if (request.usesLocalTimeFilter()
                                        || startInstant.toEpochMilli() > startDateAccess) {
                                    startDateAccess = startInstant.toEpochMilli();
                                }
                            }
                            Pair<List<RecordInternal<?>>, Long> readRecordsResponse =
                                    mTransactionManager.readRecordsAndGetNextToken(
                                            new ReadTransactionRequest(
                                                    attributionSource.getPackageName(),
                                                    request,
                                                    startDateAccess,
                                                    enforceSelfRead.get(),
                                                    extraReadPermsToGrantState));
                            builder.setNumberOfRecords(readRecordsResponse.first.size());
                            long pageToken =
                                    request.getRecordIdFiltersParcel() == null
                                            ? readRecordsResponse.second
                                            : DEFAULT_LONG;
                            if (pageToken != DEFAULT_LONG) {
                                // pagetoken is used here to store sorting order of the result.
                                // An even pagetoken indicate ascending and Odd page token indicate
                                // descending sort order. This detail from page token will be used
                                // in next read request to have same sort order.
                                pageToken =
                                        request.isAscending() ? pageToken * 2 : pageToken * 2 + 1;
                            }

                            if (Constants.DEBUG) {
                                Slog.d(TAG, "pageToken: " + pageToken);
                            }

                            final String packageName = attributionSource.getPackageName();
                            final List<Integer> recordTypes =
                                    Collections.singletonList(request.getRecordType());
                            // Calls from controller APK should not be recorded in access logs
                            // If an app is reading only its own data then it is not recorded in
                            // access logs.
                            boolean requiresLogging =
                                    !holdsDataManagementPermission && !enforceSelfRead.get();
                            if (requiresLogging) {
                                Trace.traceBegin(
                                        TRACE_TAG_READ_SUBTASKS, TAG_READ.concat("AddAccessLog"));
                                AccessLogsHelper.getInstance()
                                        .addAccessLog(packageName, recordTypes, READ);
                                Trace.traceEnd(TRACE_TAG_READ_SUBTASKS);
                            }
                            callback.onResult(
                                    new ReadRecordsResponseParcel(
                                            new RecordsParcel(readRecordsResponse.first),
                                            pageToken));
                            finishDataDeliveryRead(request.getRecordType(), attributionSource);
                            if (requiresLogging) {
                                logRecordTypeSpecificReadMetrics(
                                        readRecordsResponse.first, packageName);
                            }
                            builder.setDataTypesFromRecordInternals(readRecordsResponse.first)
                                    .setHealthDataServiceApiStatusSuccess();
                        } catch (TypeNotPresentException exception) {
                            // All the requested package names are not present, so simply
                            // return an empty list
                            if (ReadTransactionRequest.TYPE_NOT_PRESENT_PACKAGE_NAME.equals(
                                    exception.typeName())) {
                                if (Constants.DEBUG) {
                                    Slog.d(
                                            TAG,
                                            "No app info recorded for "
                                                    + attributionSource.getPackageName());
                                }
                                callback.onResult(
                                        new ReadRecordsResponseParcel(
                                                new RecordsParcel(new ArrayList<>()),
                                                DEFAULT_LONG));
                                builder.setHealthDataServiceApiStatusSuccess();
                            } else {
                                builder.setHealthDataServiceApiStatusError(
                                        HealthConnectException.ERROR_UNKNOWN);
                                throw exception;
                            }
                        }
                    } catch (SQLiteException sqLiteException) {
                        builder.setHealthDataServiceApiStatusError(HealthConnectException.ERROR_IO);
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_SECURITY);
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (IllegalStateException illegalStateException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
                        Slog.e(TAG, "IllegalStateException: ", illegalStateException);
                        tryAndThrowException(callback, illegalStateException, ERROR_INTERNAL);
                    } catch (HealthConnectException healthConnectException) {
                        builder.setHealthDataServiceApiStatusError(
                                healthConnectException.getErrorCode());
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception e) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, ERROR_INTERNAL);
                    } finally {
                        Trace.traceEnd(TRACE_TAG_READ);
                        builder.build().log();
                    }
                },
                uid,
                holdsDataManagementPermission);
    }

    /**
     * Updates {@code recordsParcel} into the HealthConnect database.
     *
     * @param recordsParcel parcel for list of records to be updated.
     * @param callback Callback to receive result of performing this operation. In case of an error
     *     or a permission failure the HealthConnect service, {@link IEmptyResponseCallback#onError}
     *     will be invoked with a {@link HealthConnectException}.
     */
    @Override
    public void updateRecords(
            @NonNull AttributionSource attributionSource,
            @NonNull RecordsParcel recordsParcel,
            @NonNull IEmptyResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        final HealthConnectServiceLogger.Builder builder =
                new HealthConnectServiceLogger.Builder(false, UPDATE_DATA)
                        .setPackageName(attributionSource.getPackageName());
        HealthConnectThreadScheduler.schedule(
                mContext,
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        verifyPackageNameFromUid(uid, attributionSource);
                        if (hasDataManagementPermission(uid, pid)) {
                            throw new SecurityException(
                                    "Apps with android.permission.MANAGE_HEALTH_DATA permission are"
                                            + " not allowed to insert records");
                        }
                        enforceMemoryRateLimit(
                                recordsParcel.getRecordsSize(),
                                recordsParcel.getRecordsChunkSize());
                        final List<RecordInternal<?>> recordInternals = recordsParcel.getRecords();
                        builder.setNumberOfRecords(recordInternals.size());
                        throwExceptionIfDataSyncInProgress();
                        mDataPermissionEnforcer.enforceRecordsWritePermissions(
                                recordInternals, attributionSource);
                        boolean isInForeground = mAppOpsManagerLocal.isUidInForeground(uid);
                        tryAcquireApiCallQuota(
                                uid, QuotaCategory.QUOTA_CATEGORY_WRITE, isInForeground, builder);
                        UpsertTransactionRequest request =
                                new UpsertTransactionRequest(
                                        attributionSource.getPackageName(),
                                        recordInternals,
                                        mContext,
                                        /* isInsertRequest */ false,
                                        mDataPermissionEnforcer
                                                .collectExtraWritePermissionStateMapping(
                                                        recordInternals, attributionSource));
                        mTransactionManager.updateAll(request);
                        tryAndReturnResult(callback, builder);
                        finishDataDeliveryWriteRecords(recordInternals, attributionSource);
                        logRecordTypeSpecificUpsertMetrics(
                                recordInternals, attributionSource.getPackageName());
                        builder.setDataTypesFromRecordInternals(recordInternals);
                        // Update activity dates table
                        HealthConnectThreadScheduler.scheduleInternalTask(
                                () ->
                                        ActivityDateHelper.getInstance()
                                                .reSyncByRecordTypeIds(
                                                        recordInternals.stream()
                                                                .map(RecordInternal::getRecordType)
                                                                .toList()));
                    } catch (SecurityException securityException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_SECURITY);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (SQLiteException sqLiteException) {
                        builder.setHealthDataServiceApiStatusError(HealthConnectException.ERROR_IO);
                        Slog.e(TAG, "SqlException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (IllegalArgumentException illegalArgumentException) {
                        builder.setHealthDataServiceApiStatusError(
                                HealthConnectException.ERROR_INVALID_ARGUMENT);

                        Slog.e(TAG, "IllegalArgumentException: ", illegalArgumentException);
                        tryAndThrowException(
                                callback,
                                illegalArgumentException,
                                HealthConnectException.ERROR_INVALID_ARGUMENT);
                    } catch (HealthConnectException healthConnectException) {
                        builder.setHealthDataServiceApiStatusError(
                                healthConnectException.getErrorCode());
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception e) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);

                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, ERROR_INTERNAL);
                    } finally {
                        builder.build().log();
                    }
                },
                uid,
                false);
    }

    /**
     * @see HealthConnectManager#getChangeLogToken
     */
    @Override
    public void getChangeLogToken(
            @NonNull AttributionSource attributionSource,
            @NonNull ChangeLogTokenRequest request,
            @NonNull IGetChangeLogTokenCallback callback) {
        final int uid = Binder.getCallingUid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        final HealthConnectServiceLogger.Builder builder =
                new HealthConnectServiceLogger.Builder(false, GET_CHANGES_TOKEN)
                        .setPackageName(attributionSource.getPackageName());
        HealthConnectThreadScheduler.schedule(
                mContext,
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        verifyPackageNameFromUid(uid, attributionSource);
                        tryAcquireApiCallQuota(
                                uid,
                                QuotaCategory.QUOTA_CATEGORY_READ,
                                mAppOpsManagerLocal.isUidInForeground(uid),
                                builder);
                        throwExceptionIfDataSyncInProgress();
                        mDataPermissionEnforcer.enforceRecordIdsReadPermissions(
                                request.getRecordTypesList(), attributionSource);
                        callback.onResult(
                                new ChangeLogTokenResponse(
                                        ChangeLogsRequestHelper.getInstance()
                                                .getToken(
                                                        attributionSource.getPackageName(),
                                                        request)));
                        builder.setHealthDataServiceApiStatusSuccess();
                    } catch (SQLiteException sqLiteException) {
                        builder.setHealthDataServiceApiStatusError(HealthConnectException.ERROR_IO);
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_SECURITY);
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        builder.setHealthDataServiceApiStatusError(
                                healthConnectException.getErrorCode());
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception e) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
                        tryAndThrowException(callback, e, ERROR_INTERNAL);
                    }
                    {
                        builder.build().log();
                    }
                },
                uid,
                false);
    }

    /**
     * @hide
     * @see HealthConnectManager#getChangeLogs
     */
    @Override
    public void getChangeLogs(
            @NonNull AttributionSource attributionSource,
            @NonNull ChangeLogsRequest token,
            IChangeLogsResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        final HealthConnectServiceLogger.Builder builder =
                new HealthConnectServiceLogger.Builder(false, GET_CHANGES)
                        .setPackageName(attributionSource.getPackageName());

        HealthConnectThreadScheduler.schedule(
                mContext,
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        verifyPackageNameFromUid(uid, attributionSource);
                        throwExceptionIfDataSyncInProgress();
                        ChangeLogsRequestHelper.TokenRequest changeLogsTokenRequest =
                                ChangeLogsRequestHelper.getRequest(
                                        attributionSource.getPackageName(), token.getToken());
                        mDataPermissionEnforcer.enforceRecordIdsReadPermissions(
                                changeLogsTokenRequest.getRecordTypes(), attributionSource);
                        boolean isInForeground = mAppOpsManagerLocal.isUidInForeground(uid);
                        if (!isInForeground) {
                            throwSecurityException(
                                    attributionSource.getPackageName()
                                            + " must be in foreground to read the change logs");
                        }
                        tryAcquireApiCallQuota(
                                uid, QuotaCategory.QUOTA_CATEGORY_READ, isInForeground, builder);
                        Instant startDateInstant =
                                mPermissionHelper.getHealthDataStartDateAccess(
                                        attributionSource.getPackageName(), userHandle);
                        if (startDateInstant == null) {
                            throwExceptionIncorrectPermissionState();
                        }
                        long startDateAccess = startDateInstant.toEpochMilli();
                        final ChangeLogsHelper.ChangeLogsResponse changeLogsResponse =
                                ChangeLogsHelper.getInstance()
                                        .getChangeLogs(changeLogsTokenRequest, token);

                        List<RecordInternal<?>> recordInternals =
                                mTransactionManager.readRecords(
                                        new ReadTransactionRequest(
                                                ChangeLogsHelper.getRecordTypeToInsertedUuids(
                                                        changeLogsResponse.getChangeLogsMap()),
                                                startDateAccess));
                        List<DeletedLog> deletedLogs =
                                ChangeLogsHelper.getDeletedLogs(
                                        changeLogsResponse.getChangeLogsMap());

                        callback.onResult(
                                new ChangeLogsResponse(
                                        new RecordsParcel(recordInternals),
                                        deletedLogs,
                                        changeLogsResponse.getNextPageToken(),
                                        changeLogsResponse.hasMorePages()));
                        finishDataDeliveryRead(
                                changeLogsTokenRequest.getRecordTypes(), attributionSource);
                        builder.setHealthDataServiceApiStatusSuccess()
                                .setNumberOfRecords(recordInternals.size() + deletedLogs.size())
                                .setDataTypesFromRecordInternals(recordInternals);
                    } catch (IllegalArgumentException illegalArgumentException) {
                        builder.setHealthDataServiceApiStatusError(
                                HealthConnectException.ERROR_INVALID_ARGUMENT);
                        Slog.e(TAG, "IllegalArgumentException: ", illegalArgumentException);
                        tryAndThrowException(
                                callback,
                                illegalArgumentException,
                                HealthConnectException.ERROR_INVALID_ARGUMENT);
                    } catch (SQLiteException sqLiteException) {
                        builder.setHealthDataServiceApiStatusError(HealthConnectException.ERROR_IO);
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_SECURITY);
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (IllegalStateException illegalStateException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
                        Slog.e(TAG, "IllegalStateException: ", illegalStateException);
                        tryAndThrowException(callback, illegalStateException, ERROR_INTERNAL);
                    } catch (HealthConnectException healthConnectException) {
                        builder.setHealthDataServiceApiStatusError(
                                healthConnectException.getErrorCode());
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception exception) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(callback, exception, ERROR_INTERNAL);
                    } finally {
                        builder.build().log();
                    }
                },
                uid,
                false);
    }

    /**
     * API to delete records based on {@code request}
     *
     * <p>NOTE: Though internally we only need a single API to handle deletes as SDK code transform
     * all its delete requests to {@link DeleteUsingFiltersRequestParcel}, we have this separation
     * to make sure no non-controller APIs can use {@link
     * HealthConnectServiceImpl#deleteUsingFilters} API
     */
    @Override
    public void deleteUsingFiltersForSelf(
            @NonNull AttributionSource attributionSource,
            @NonNull DeleteUsingFiltersRequestParcel request,
            @NonNull IEmptyResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        final boolean holdsDataManagementPermission = hasDataManagementPermission(uid, pid);
        final HealthConnectServiceLogger.Builder builder =
                new HealthConnectServiceLogger.Builder(holdsDataManagementPermission, DELETE_DATA)
                        .setPackageName(attributionSource.getPackageName());

        HealthConnectThreadScheduler.schedule(
                mContext,
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        verifyPackageNameFromUid(uid, attributionSource);
                        throwExceptionIfDataSyncInProgress();
                        List<Integer> recordTypeIdsToDelete =
                                (!request.getRecordTypeFilters().isEmpty())
                                        ? request.getRecordTypeFilters()
                                        : new ArrayList<>(
                                                RecordMapper.getInstance()
                                                        .getRecordIdToExternalRecordClassMap()
                                                        .keySet());
                        // Requests from non controller apps are not allowed to use non-id
                        // filters
                        request.setPackageNameFilters(
                                Collections.singletonList(attributionSource.getPackageName()));

                        if (!holdsDataManagementPermission) {
                            mDataPermissionEnforcer.enforceRecordIdsWritePermissions(
                                    recordTypeIdsToDelete, attributionSource);
                            tryAcquireApiCallQuota(
                                    uid,
                                    QuotaCategory.QUOTA_CATEGORY_WRITE,
                                    mAppOpsManagerLocal.isUidInForeground(uid),
                                    builder);
                        }

                        deleteUsingFiltersInternal(
                                attributionSource,
                                request,
                                callback,
                                builder,
                                recordTypeIdsToDelete,
                                uid,
                                pid);
                    } catch (SQLiteException sqLiteException) {
                        builder.setHealthDataServiceApiStatusError(HealthConnectException.ERROR_IO);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (IllegalArgumentException illegalArgumentException) {
                        builder.setHealthDataServiceApiStatusError(
                                HealthConnectException.ERROR_INVALID_ARGUMENT);
                        Slog.e(TAG, "IllegalArgumentException: ", illegalArgumentException);
                        tryAndThrowException(
                                callback,
                                illegalArgumentException,
                                HealthConnectException.ERROR_INVALID_ARGUMENT);
                    } catch (SecurityException securityException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_SECURITY);
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        builder.setHealthDataServiceApiStatusError(
                                healthConnectException.getErrorCode());
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception exception) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(callback, exception, ERROR_INTERNAL);
                    } finally {
                        builder.build().log();
                    }
                },
                uid,
                holdsDataManagementPermission);
    }

    /**
     * API to delete records based on {@code request}
     *
     * <p>NOTE: Though internally we only need a single API to handle deletes as SDK code transform
     * all its delete requests to {@link DeleteUsingFiltersRequestParcel}, we have this separation
     * to make sure no non-controller APIs can use this API
     */
    @Override
    public void deleteUsingFilters(
            @NonNull AttributionSource attributionSource,
            @NonNull DeleteUsingFiltersRequestParcel request,
            @NonNull IEmptyResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        final boolean holdsDataManagementPermission = hasDataManagementPermission(uid, pid);
        final HealthConnectServiceLogger.Builder builder =
                new HealthConnectServiceLogger.Builder(holdsDataManagementPermission, DELETE_DATA)
                        .setPackageName(attributionSource.getPackageName());

        HealthConnectThreadScheduler.schedule(
                mContext,
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        verifyPackageNameFromUid(uid, attributionSource);
                        throwExceptionIfDataSyncInProgress();
                        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
                        List<Integer> recordTypeIdsToDelete =
                                (!request.getRecordTypeFilters().isEmpty())
                                        ? request.getRecordTypeFilters()
                                        : new ArrayList<>(
                                                RecordMapper.getInstance()
                                                        .getRecordIdToExternalRecordClassMap()
                                                        .keySet());

                        deleteUsingFiltersInternal(
                                attributionSource,
                                request,
                                callback,
                                builder,
                                recordTypeIdsToDelete,
                                uid,
                                pid);
                    } catch (SQLiteException sqLiteException) {
                        builder.setHealthDataServiceApiStatusError(HealthConnectException.ERROR_IO);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (IllegalArgumentException illegalArgumentException) {
                        builder.setHealthDataServiceApiStatusError(
                                HealthConnectException.ERROR_INVALID_ARGUMENT);
                        Slog.e(TAG, "IllegalArgumentException: ", illegalArgumentException);
                        tryAndThrowException(
                                callback,
                                illegalArgumentException,
                                HealthConnectException.ERROR_INVALID_ARGUMENT);
                    } catch (SecurityException securityException) {
                        builder.setHealthDataServiceApiStatusError(ERROR_SECURITY);
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        builder.setHealthDataServiceApiStatusError(
                                healthConnectException.getErrorCode());
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception exception) {
                        builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(callback, exception, ERROR_INTERNAL);
                    } finally {
                        builder.build().log();
                    }
                },
                uid,
                holdsDataManagementPermission);
    }

    private void deleteUsingFiltersInternal(
            @NonNull AttributionSource attributionSource,
            @NonNull DeleteUsingFiltersRequestParcel request,
            @NonNull IEmptyResponseCallback callback,
            @NonNull HealthConnectServiceLogger.Builder builder,
            List<Integer> recordTypeIdsToDelete,
            int uid,
            int pid) {
        if (request.usesIdFilters() && request.usesNonIdFilters()) {
            throw new IllegalArgumentException(
                    "Requests with both id and non-id filters are not" + " supported");
        }
        int numberOfRecordsDeleted =
                mTransactionManager.deleteAll(
                        new DeleteTransactionRequest(attributionSource.getPackageName(), request)
                                .setHasManageHealthDataPermission(
                                        hasDataManagementPermission(uid, pid)));
        tryAndReturnResult(callback, builder);
        finishDataDeliveryWrite(recordTypeIdsToDelete, attributionSource);
        HealthConnectThreadScheduler.scheduleInternalTask(
                () -> postDeleteTasks(recordTypeIdsToDelete));

        builder.setNumberOfRecords(numberOfRecordsDeleted)
                .setDataTypesFromRecordTypes(recordTypeIdsToDelete);
    }

    /** API to get Priority for {@code dataCategory} */
    @Override
    public void getCurrentPriority(
            @NonNull String packageName,
            @HealthDataCategory.Type int dataCategory,
            @NonNull IGetPriorityResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        HealthConnectThreadScheduler.scheduleControllerTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
                        throwExceptionIfDataSyncInProgress();
                        List<DataOrigin> dataOriginInPriorityOrder =
                                HealthDataCategoryPriorityHelper.getInstance()
                                        .getPriorityOrder(dataCategory)
                                        .stream()
                                        .map(
                                                (name) ->
                                                        new DataOrigin.Builder()
                                                                .setPackageName(name)
                                                                .build())
                                        .collect(Collectors.toList());
                        callback.onResult(
                                new GetPriorityResponseParcel(
                                        new FetchDataOriginsPriorityOrderResponse(
                                                dataOriginInPriorityOrder)));
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(callback, exception, ERROR_INTERNAL);
                    }
                });
    }

    /** API to update priority for permission category(ies) */
    @Override
    public void updatePriority(
            @NonNull String packageName,
            @NonNull UpdatePriorityRequestParcel updatePriorityRequest,
            @NonNull IEmptyResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        HealthConnectThreadScheduler.scheduleControllerTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
                        throwExceptionIfDataSyncInProgress();
                        HealthDataCategoryPriorityHelper.getInstance()
                                .setPriorityOrder(
                                        updatePriorityRequest.getDataCategory(),
                                        updatePriorityRequest.getPackagePriorityOrder());
                        callback.onResult();
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(callback, exception, ERROR_INTERNAL);
                    }
                });
    }

    @Override
    public void setRecordRetentionPeriodInDays(
            int days, @NonNull UserHandle user, IEmptyResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        HealthConnectThreadScheduler.scheduleControllerTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
                        throwExceptionIfDataSyncInProgress();
                        AutoDeleteService.setRecordRetentionPeriodInDays(days);
                        callback.onResult();
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SQLiteException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(callback, exception, ERROR_INTERNAL);
                    }
                });
    }

    @Override
    public int getRecordRetentionPeriodInDays(@NonNull UserHandle user) {
        enforceIsForegroundUser(getCallingUserHandle());
        throwExceptionIfDataSyncInProgress();
        try {
            mContext.enforceCallingPermission(MANAGE_HEALTH_DATA_PERMISSION, null);
            return AutoDeleteService.getRecordRetentionPeriodInDays();
        } catch (Exception e) {
            if (e instanceof SecurityException) {
                throw e;
            }
            Slog.e(TAG, "Unable to get record retention period for " + user);
        }

        throw new RuntimeException();
    }

    /**
     * Returns information, represented by {@code ApplicationInfoResponse}, for all the packages
     * that have contributed to the health connect DB.
     *
     * @param callback Callback to receive result of performing this operation. In case of an error
     *     or a permission failure the HealthConnect service, {@link IEmptyResponseCallback#onError}
     *     will be invoked with a {@link HealthConnectException}.
     */
    @Override
    public void getContributorApplicationsInfo(@NonNull IApplicationInfoResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        HealthConnectThreadScheduler.scheduleControllerTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
                        throwExceptionIfDataSyncInProgress();
                        List<AppInfo> applicationInfos =
                                AppInfoHelper.getInstance().getApplicationInfosWithRecordTypes();

                        callback.onResult(new ApplicationInfoResponseParcel(applicationInfos));
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SqlException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, ERROR_INTERNAL);
                    }
                });
    }

    /** Retrieves {@link RecordTypeInfoResponse} for each RecordType. */
    @Override
    public void queryAllRecordTypesInfo(@NonNull IRecordTypeInfoResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        HealthConnectThreadScheduler.scheduleControllerTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
                        throwExceptionIfDataSyncInProgress();
                        callback.onResult(
                                new RecordTypeInfoResponseParcel(
                                        getPopulatedRecordTypeInfoResponses()));
                    } catch (SQLiteException sqLiteException) {
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception exception) {
                        tryAndThrowException(callback, exception, ERROR_INTERNAL);
                    }
                });
    }

    /**
     * @see HealthConnectManager#queryAccessLogs
     */
    @Override
    public void queryAccessLogs(@NonNull String packageName, IAccessLogsResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();

        HealthConnectThreadScheduler.scheduleControllerTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
                        throwExceptionIfDataSyncInProgress();
                        final List<AccessLog> accessLogsList =
                                AccessLogsHelper.getInstance().queryAccessLogs();
                        callback.onResult(new AccessLogsResponseParcel(accessLogsList));
                    } catch (SecurityException securityException) {
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception exception) {
                        Slog.e(TAG, "Exception: ", exception);
                        tryAndThrowException(callback, exception, ERROR_INTERNAL);
                    }
                });
    }

    /**
     * Returns a list of unique dates for which the database has at least one entry
     *
     * @param activityDatesRequestParcel Parcel request containing records classes
     * @param callback Callback to receive result of performing this operation. The results are
     *     returned in {@link List<LocalDate>} . In case of an error or a permission failure the
     *     HealthConnect service, {@link IActivityDatesResponseCallback#onError} will be invoked
     *     with a {@link HealthConnectExceptionParcel}.
     */
    @Override
    public void getActivityDates(
            @NonNull ActivityDatesRequestParcel activityDatesRequestParcel,
            IActivityDatesResponseCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();

        HealthConnectThreadScheduler.scheduleControllerTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);
                        throwExceptionIfDataSyncInProgress();
                        List<LocalDate> localDates =
                                ActivityDateHelper.getInstance()
                                        .getActivityDates(
                                                activityDatesRequestParcel.getRecordTypes());

                        callback.onResult(new ActivityDatesResponseParcel(localDates));
                    } catch (SQLiteException sqLiteException) {
                        Slog.e(TAG, "SqlException: ", sqLiteException);
                        tryAndThrowException(
                                callback, sqLiteException, HealthConnectException.ERROR_IO);
                    } catch (SecurityException securityException) {
                        Slog.e(TAG, "SecurityException: ", securityException);
                        tryAndThrowException(callback, securityException, ERROR_SECURITY);
                    } catch (HealthConnectException healthConnectException) {
                        Slog.e(TAG, "HealthConnectException: ", healthConnectException);
                        tryAndThrowException(
                                callback,
                                healthConnectException,
                                healthConnectException.getErrorCode());
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, ERROR_INTERNAL);
                    }
                });
    }

    // TODO(b/265780725): Update javadocs and ensure that the caller handles SHOW_MIGRATION_INFO
    // intent.
    @Override
    public void startMigration(@NonNull String packageName, IMigrationCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();

        HealthConnectThreadScheduler.scheduleInternalTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(
                                MIGRATE_HEALTH_CONNECT_DATA,
                                pid,
                                uid,
                                "Caller does not have " + MIGRATE_HEALTH_CONNECT_DATA);
                        enforceShowMigrationInfoIntent(packageName, uid);
                        mBackupRestore.runWithStatesReadLock(
                                () -> {
                                    if (mBackupRestore.isRestoreMergingInProgress()) {
                                        throw new MigrationException(
                                                "Cannot start data migration. Backup and restore in"
                                                        + " progress.",
                                                MigrationException.ERROR_INTERNAL,
                                                null);
                                    }
                                    mMigrationStateManager.startMigration(mContext);
                                });
                        PriorityMigrationHelper.getInstance().populatePreMigrationPriority();
                        callback.onSuccess();
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, MigrationException.ERROR_INTERNAL, null);
                    }
                });
    }

    // TODO(b/265780725): Update javadocs and ensure that the caller handles SHOW_MIGRATION_INFO
    // intent.
    @Override
    public void finishMigration(@NonNull String packageName, IMigrationCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();

        HealthConnectThreadScheduler.scheduleInternalTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(
                                MIGRATE_HEALTH_CONNECT_DATA,
                                pid,
                                uid,
                                "Caller does not have " + MIGRATE_HEALTH_CONNECT_DATA);
                        enforceShowMigrationInfoIntent(packageName, uid);
                        mMigrationStateManager.finishMigration(mContext);
                        AppInfoHelper.getInstance().syncAppInfoRecordTypesUsed();
                        callback.onSuccess();
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        // TODO(b/263897830): Verify migration state and send errors properly
                        tryAndThrowException(callback, e, MigrationException.ERROR_INTERNAL, null);
                    }
                });
    }

    // TODO(b/265780725): Update javadocs and ensure that the caller handles SHOW_MIGRATION_INFO
    // intent.
    @Override
    public void writeMigrationData(
            @NonNull String packageName,
            MigrationEntityParcel parcel,
            IMigrationCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        UserHandle callingUserHandle = getCallingUserHandle();

        HealthConnectThreadScheduler.scheduleInternalTask(
                () -> {
                    try {
                        enforceIsForegroundUser(callingUserHandle);
                        mContext.enforcePermission(
                                MIGRATE_HEALTH_CONNECT_DATA,
                                pid,
                                uid,
                                "Caller does not have " + MIGRATE_HEALTH_CONNECT_DATA);
                        enforceShowMigrationInfoIntent(packageName, uid);
                        mMigrationStateManager.validateWriteMigrationData();
                        getDataMigrationManager(callingUserHandle)
                                .apply(parcel.getMigrationEntities());
                        callback.onSuccess();
                    } catch (DataMigrationManager.EntityWriteException e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(
                                callback,
                                e,
                                MigrationException.ERROR_MIGRATE_ENTITY,
                                e.getEntityId());
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, MigrationException.ERROR_INTERNAL, null);
                    }
                });
    }

    public void insertMinDataMigrationSdkExtensionVersion(
            @NonNull String packageName, int requiredSdkExtension, IMigrationCallback callback) {
        int uid = Binder.getCallingUid();
        int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();

        HealthConnectThreadScheduler.scheduleInternalTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(
                                MIGRATE_HEALTH_CONNECT_DATA,
                                pid,
                                uid,
                                "Caller does not have " + MIGRATE_HEALTH_CONNECT_DATA);
                        enforceShowMigrationInfoIntent(packageName, uid);
                        mMigrationStateManager.validateSetMinSdkVersion();
                        mMigrationStateManager.setMinDataMigrationSdkExtensionVersion(
                                mContext, requiredSdkExtension);

                        callback.onSuccess();
                    } catch (Exception e) {
                        Slog.e(TAG, "Exception: ", e);
                        tryAndThrowException(callback, e, MigrationException.ERROR_INTERNAL, null);
                    }
                });
    }

    /**
     * @see HealthConnectManager#stageAllHealthConnectRemoteData
     */
    @Override
    public void stageAllHealthConnectRemoteData(
            @NonNull StageRemoteDataRequest stageRemoteDataRequest,
            @NonNull UserHandle userHandle,
            @NonNull IDataStagingFinishedCallback callback) {
        Map<String, ParcelFileDescriptor> origPfdsByFileName =
                stageRemoteDataRequest.getPfdsByFileName();
        Map<String, HealthConnectException> exceptionsByFileName =
                new ArrayMap<>(origPfdsByFileName.size());
        Map<String, ParcelFileDescriptor> pfdsByFileName =
                new ArrayMap<>(origPfdsByFileName.size());

        try {
            mDataPermissionEnforcer.enforceAnyOfPermissions(
                    Manifest.permission.STAGE_HEALTH_CONNECT_REMOTE_DATA,
                    HEALTH_CONNECT_BACKUP_INTER_AGENT_PERMISSION);

            enforceIsForegroundUser(Binder.getCallingUserHandle());

            for (Entry<String, ParcelFileDescriptor> entry : origPfdsByFileName.entrySet()) {
                try {
                    pfdsByFileName.put(entry.getKey(), entry.getValue().dup());
                } catch (IOException e) {
                    Slog.e(TAG, "IOException: ", e);
                    exceptionsByFileName.put(
                            entry.getKey(),
                            new HealthConnectException(
                                    HealthConnectException.ERROR_IO, e.getMessage()));
                }
            }

            HealthConnectThreadScheduler.scheduleInternalTask(
                    () -> {
                        if (!mBackupRestore.prepForStagingIfNotAlreadyDone()) {
                            try {
                                callback.onResult();
                            } catch (RemoteException e) {
                                Log.e(TAG, "Restore response could not be sent to the caller.", e);
                            }
                            return;
                        }
                        mBackupRestore.stageAllHealthConnectRemoteData(
                                pfdsByFileName,
                                exceptionsByFileName,
                                userHandle.getIdentifier(),
                                callback);
                    });
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "Exception encountered while staging", e);
            try {
                @HealthConnectException.ErrorCode int errorCode =
                        (e instanceof SecurityException) ? ERROR_SECURITY : ERROR_INTERNAL;
                exceptionsByFileName.put("", new HealthConnectException(
                        errorCode,
                        e.getMessage()));

                callback.onError(
                        new StageRemoteDataException(exceptionsByFileName));
            } catch (RemoteException remoteException) {
                Log.e(TAG, "Restore permission response could not be sent to the caller.", e);
            }
        }
    }

    /**
     * @see HealthConnectManager#getAllDataForBackup
     */
    @Override
    public void getAllDataForBackup(
            @NonNull StageRemoteDataRequest stageRemoteDataRequest,
            @NonNull UserHandle userHandle) {
        mContext.enforceCallingPermission(HEALTH_CONNECT_BACKUP_INTER_AGENT_PERMISSION, null);
        mBackupRestore.getAllDataForBackup(stageRemoteDataRequest, userHandle);
    }

    /**
     * @see HealthConnectManager#getAllBackupFileNames
     */
    @Override
    public BackupFileNamesSet getAllBackupFileNames(boolean forDeviceToDevice) {
        mContext.enforceCallingPermission(HEALTH_CONNECT_BACKUP_INTER_AGENT_PERMISSION, null);
        return mBackupRestore.getAllBackupFileNames(forDeviceToDevice);
    }

    /**
     * @see HealthConnectManager#deleteAllStagedRemoteData
     */
    @Override
    public void deleteAllStagedRemoteData(@NonNull UserHandle userHandle) {
        mContext.enforceCallingPermission(
                DELETE_STAGED_HEALTH_CONNECT_REMOTE_DATA_PERMISSION, null);
        mBackupRestore.deleteAndResetEverything(userHandle);
        mMigrationStateManager.clearCaches(mContext);
        AppInfoHelper.getInstance().clearData(mTransactionManager);
        ActivityDateHelper.getInstance().clearData(mTransactionManager);
        MigrationEntityHelper.getInstance().clearData(mTransactionManager);
        HealthDataCategoryPriorityHelper.getInstance().clearData(mTransactionManager);
        PriorityMigrationHelper.getInstance().clearData(mTransactionManager);
        RateLimiter.clearCache();
        String[] packageNames = mContext.getPackageManager().getPackagesForUid(getCallingUid());
        for (String packageName : packageNames) {
            mFirstGrantTimeManager.setFirstGrantTime(packageName, Instant.now(), userHandle);
        }
    }

    /**
     * @see HealthConnectManager#updateDataDownloadState
     */
    @Override
    public void updateDataDownloadState(@DataDownloadState int downloadState) {
        mContext.enforceCallingPermission(
                Manifest.permission.STAGE_HEALTH_CONNECT_REMOTE_DATA, null);
        enforceIsForegroundUser(getCallingUserHandle());
        mBackupRestore.updateDataDownloadState(downloadState);
    }

    /**
     * @see HealthConnectManager#getHealthConnectDataState
     */
    @Override
    public void getHealthConnectDataState(@NonNull IGetHealthConnectDataStateCallback callback) {
        try {
            mDataPermissionEnforcer.enforceAnyOfPermissions(
                    MANAGE_HEALTH_DATA_PERMISSION, Manifest.permission.MIGRATE_HEALTH_CONNECT_DATA);
            final UserHandle userHandle = Binder.getCallingUserHandle();
            enforceIsForegroundUser(userHandle);
            HealthConnectThreadScheduler.scheduleInternalTask(
                    () -> {
                        try {
                            @HealthConnectDataState.DataRestoreError
                            int dataRestoreError = mBackupRestore.getDataRestoreError();
                            @HealthConnectDataState.DataRestoreState
                            int dataRestoreState = mBackupRestore.getDataRestoreState();

                            try {
                                callback.onResult(
                                        new HealthConnectDataState(
                                                dataRestoreState,
                                                dataRestoreError,
                                                mMigrationStateManager.getMigrationState()));
                            } catch (RemoteException remoteException) {
                                Log.e(
                                        TAG,
                                        "HealthConnectDataState could not be sent to the caller.",
                                        remoteException);
                            }
                        } catch (RuntimeException e) {
                            // exception getting the state from the disk
                            try {
                                callback.onError(
                                        new HealthConnectExceptionParcel(
                                                new HealthConnectException(
                                                        HealthConnectException.ERROR_IO,
                                                        e.getMessage())));
                            } catch (RemoteException remoteException) {
                                Log.e(
                                        TAG,
                                        "Exception for getHealthConnectDataState could not be sent"
                                                + " to the caller.",
                                        remoteException);
                            }
                        }
                    });
        } catch (SecurityException | IllegalStateException e) {
            Log.e(TAG, "getHealthConnectDataState: Exception encountered", e);
            @HealthConnectException.ErrorCode int errorCode =
                    (e instanceof SecurityException) ? ERROR_SECURITY
                            : ERROR_INTERNAL;
            try {
                callback.onError(
                        new HealthConnectExceptionParcel(
                                new HealthConnectException(
                                        errorCode,
                                        e.getMessage())));
            } catch (RemoteException remoteException) {
                Log.e(TAG, "getHealthConnectDataState error could not be sent", e);
            }
        }
    }

    /**
     * @see HealthConnectManager#getHealthConnectMigrationUiState
     */
    @Override
    public void getHealthConnectMigrationUiState(
            @NonNull IGetHealthConnectMigrationUiStateCallback callback) {
        final int uid = Binder.getCallingUid();
        final int pid = Binder.getCallingPid();
        final UserHandle userHandle = Binder.getCallingUserHandle();
        HealthConnectThreadScheduler.scheduleInternalTask(
                () -> {
                    try {
                        enforceIsForegroundUser(userHandle);
                        mContext.enforcePermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid, null);

                        try {
                            callback.onResult(
                                    new HealthConnectMigrationUiState(
                                            mMigrationUiStateManager
                                                    .getHealthConnectMigrationUiState()));
                        } catch (RemoteException remoteException) {
                            Log.e(
                                    TAG,
                                    "HealthConnectMigrationUiState could not be sent to the"
                                            + " caller.",
                                    remoteException);
                        }
                    } catch (SecurityException securityException) {
                        try {
                            callback.onError(
                                    new HealthConnectExceptionParcel(
                                            new HealthConnectException(
                                                    ERROR_SECURITY,
                                                    securityException.getMessage())));
                        } catch (RemoteException remoteException) {
                            Log.e(
                                    TAG,
                                    "Exception for HealthConnectMigrationUiState could not be sent"
                                            + " to the caller.",
                                    remoteException);
                        }
                    } catch (RuntimeException e) {
                        // exception getting the state from the disk
                        try {
                            callback.onError(
                                    new HealthConnectExceptionParcel(
                                            new HealthConnectException(
                                                    HealthConnectException.ERROR_IO,
                                                    e.getMessage())));
                        } catch (RemoteException remoteException) {
                            Log.e(
                                    TAG,
                                    "Exception for HealthConnectMigrationUiState could not be sent"
                                            + " to the caller.",
                                    remoteException);
                        }
                    }
                });
    }

    // Cancel BR timeouts - this might be needed when a user is going into background.
    void cancelBackupRestoreTimeouts() {
        mBackupRestore.cancelAllJobs();
    }

    private void tryAcquireApiCallQuota(
            int uid,
            @QuotaCategory.Type int quotaCategory,
            boolean isInForeground,
            HealthConnectServiceLogger.Builder builder) {
        try {
            RateLimiter.tryAcquireApiCallQuota(uid, quotaCategory, isInForeground);
        } catch (RateLimiterException rateLimiterException) {
            builder.setRateLimit(
                    rateLimiterException.getRateLimiterQuotaBucket(),
                    rateLimiterException.getRateLimiterQuotaLimit());
            throw new HealthConnectException(
                    rateLimiterException.getErrorCode(), rateLimiterException.getMessage());
        }
    }

    private void enforceMemoryRateLimit(List<Long> recordsSize, long recordsChunkSize) {
        recordsSize.forEach(RateLimiter::checkMaxRecordMemoryUsage);
        RateLimiter.checkMaxChunkMemoryUsage(recordsChunkSize);
    }

    private void enforceIsForegroundUser(UserHandle callingUserHandle) {
        if (!callingUserHandle.equals(mCurrentForegroundUser)) {
            throw new IllegalStateException(
                    "Calling user: "
                            + callingUserHandle.getIdentifier()
                            + "is not the current foreground user: "
                            + mCurrentForegroundUser.getIdentifier()
                            + ". HC request must be called"
                            + " from the current foreground user.");
        }
    }

    private boolean isDataSyncInProgress() {
        return mMigrationStateManager.isMigrationInProgress()
                || mBackupRestore.isRestoreMergingInProgress();
    }

    @VisibleForTesting
    Set<String> getStagedRemoteFileNames(int userId) {
        return mBackupRestore.getStagedRemoteFileNames(userId);
    }

    @NonNull
    private DataMigrationManager getDataMigrationManager(@NonNull UserHandle userHandle) {
        final Context userContext = mContext.createContextAsUser(userHandle, 0);

        return new DataMigrationManager(
                userContext,
                mTransactionManager,
                mPermissionHelper,
                mFirstGrantTimeManager,
                DeviceInfoHelper.getInstance(),
                AppInfoHelper.getInstance(),
                MigrationEntityHelper.getInstance(),
                RecordHelperProvider.getInstance(),
                HealthDataCategoryPriorityHelper.getInstance(),
                PriorityMigrationHelper.getInstance(),
                ActivityDateHelper.getInstance());
    }

    private void enforceCallingPackageBelongsToUid(String packageName, int callingUid) {
        int packageUid;
        try {
            packageUid =
                    mContext.getPackageManager()
                            .getPackageUid(
                                    packageName, /* flags */ PackageManager.PackageInfoFlags.of(0));
        } catch (PackageManager.NameNotFoundException e) {
            throw new IllegalStateException(packageName + " not found");
        }
        if (UserHandle.getAppId(packageUid) != UserHandle.getAppId(callingUid)) {
            throwSecurityException(packageName + " does not belong to uid " + callingUid);
        }
    }

    /**
     * Verify various aspects of the calling user.
     *
     * @param callingUid Uid of the caller, usually retrieved from Binder for authenticity.
     * @param callerAttributionSource The permission identity of the caller
     */
    private void verifyPackageNameFromUid(
            int callingUid, @NonNull AttributionSource callerAttributionSource) {
        // Check does the attribution source is one for the calling app.
        callerAttributionSource.enforceCallingUid();
        // Obtain the user where the client is running in.
        UserHandle callingUserHandle = UserHandle.getUserHandleForUid(callingUid);
        Context callingUserContext = mContext.createContextAsUser(callingUserHandle, 0);
        String callingPackageName =
                Objects.requireNonNull(callerAttributionSource.getPackageName());
        verifyCallingPackage(callingUserContext, callingUid, callingPackageName);
    }

    /**
     * Check that the caller's supposed package name matches the uid making the call.
     *
     * @throws SecurityException if the package name and uid don't match.
     */
    private void verifyCallingPackage(
            @NonNull Context actualCallingUserContext,
            int actualCallingUid,
            @NonNull String claimedCallingPackage) {
        int claimedCallingUid = getPackageUid(actualCallingUserContext, claimedCallingPackage);
        if (claimedCallingUid != actualCallingUid) {
            throwSecurityException(
                    claimedCallingPackage + " does not belong to uid " + actualCallingUid);
        }
    }

    /** Finds the UID of the {@code packageName} in the given {@code context}. */
    private int getPackageUid(@NonNull Context context, @NonNull String packageName) {
        try {
            return context.getPackageManager().getPackageUid(packageName, /* flags= */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            return Process.INVALID_UID;
        }
    }

    private void enforceShowMigrationInfoIntent(String packageName, int callingUid) {
        enforceCallingPackageBelongsToUid(packageName, callingUid);

        Intent intentToCheck =
                new Intent(HealthConnectManager.ACTION_SHOW_MIGRATION_INFO).setPackage(packageName);

        ResolveInfo resolveResult =
                mContext.getPackageManager()
                        .resolveActivity(
                                intentToCheck,
                                PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL));

        if (Objects.isNull(resolveResult)) {
            throw new IllegalArgumentException(
                    packageName
                            + " does not handle intent "
                            + HealthConnectManager.ACTION_SHOW_MIGRATION_INFO);
        }
    }

    private Map<Integer, List<DataOrigin>> getPopulatedRecordTypeInfoResponses() {
        Map<Integer, Class<? extends Record>> recordIdToExternalRecordClassMap =
                RecordMapper.getInstance().getRecordIdToExternalRecordClassMap();
        AppInfoHelper appInfoHelper = AppInfoHelper.getInstance();
        Map<Integer, List<DataOrigin>> recordTypeInfoResponses =
                new ArrayMap<>(recordIdToExternalRecordClassMap.size());
        Map<Integer, Set<String>> recordTypeToContributingPackagesMap =
                appInfoHelper.getRecordTypesToContributingPackagesMap();
        recordIdToExternalRecordClassMap
                .keySet()
                .forEach(
                        (recordType) -> {
                            if (recordTypeToContributingPackagesMap.containsKey(recordType)) {
                                List<DataOrigin> packages =
                                        recordTypeToContributingPackagesMap.get(recordType).stream()
                                                .map(
                                                        (packageName) ->
                                                                new DataOrigin.Builder()
                                                                        .setPackageName(packageName)
                                                                        .build())
                                                .toList();
                                recordTypeInfoResponses.put(recordType, packages);
                            } else {
                                recordTypeInfoResponses.put(recordType, Collections.emptyList());
                            }
                        });
        return recordTypeInfoResponses;
    }

    private boolean hasDataManagementPermission(int uid, int pid) {
        return mContext.checkPermission(MANAGE_HEALTH_DATA_PERMISSION, pid, uid)
                == PERMISSION_GRANTED;
    }

    private void finishDataDeliveryRead(int recordTypeId, AttributionSource attributionSource) {
        finishDataDeliveryRead(Collections.singletonList(recordTypeId), attributionSource);
    }

    private void finishDataDeliveryRead(
            List<Integer> recordTypeIds, AttributionSource attributionSource) {
        Trace.traceBegin(TRACE_TAG_READ_SUBTASKS, TAG_READ.concat("FinishDataDeliveryRead"));

        try {
            for (Integer recordTypeId : recordTypeIds) {
                String permissionName =
                        HealthPermissions.getHealthReadPermission(
                                RecordTypePermissionCategoryMapper
                                        .getHealthPermissionCategoryForRecordType(recordTypeId));
                mPermissionManager.finishDataDelivery(permissionName, attributionSource);
            }
        } catch (Exception exception) {
            // Ignore: HC API has already fulfilled the result, ignore any exception we hit here
        }
        Trace.traceEnd(TRACE_TAG_READ_SUBTASKS);
    }

    private void finishDataDeliveryWriteRecords(
            List<RecordInternal<?>> recordInternals, AttributionSource attributionSource) {
        Trace.traceBegin(TRACE_TAG_READ_SUBTASKS, TAG_READ.concat(".FinishDataDeliveryWrite"));
        Set<Integer> recordTypeIdsToEnforce = new ArraySet<>();
        for (RecordInternal<?> recordInternal : recordInternals) {
            recordTypeIdsToEnforce.add(recordInternal.getRecordType());
        }

        finishDataDeliveryWrite(recordTypeIdsToEnforce.stream().toList(), attributionSource);
        Trace.traceEnd(TRACE_TAG_READ_SUBTASKS);
    }

    private void finishDataDeliveryWrite(
            List<Integer> recordTypeIds, AttributionSource attributionSource) {
        try {
            for (Integer recordTypeId : recordTypeIds) {
                String permissionName =
                        HealthPermissions.getHealthWritePermission(
                                RecordTypePermissionCategoryMapper
                                        .getHealthPermissionCategoryForRecordType(recordTypeId));
                mPermissionManager.finishDataDelivery(permissionName, attributionSource);
            }
        } catch (Exception exception) {
            // Ignore: HC API has already fulfilled the result, ignore any exception we hit here
        }
    }

    private void enforceBinderUidIsSameAsAttributionSourceUid(
            int binderUid, int attributionSourceUid) {
        if (binderUid != attributionSourceUid) {
            throw new SecurityException("Binder uid must be equal to attribution source uid.");
        }
    }

    private void throwExceptionIncorrectPermissionState() {
        throw new IllegalStateException(
                "Incorrect health permission state, likely"
                        + " because the calling application's manifest does not specify handling "
                        + Intent.ACTION_VIEW_PERMISSION_USAGE
                        + " with "
                        + HealthConnectManager.CATEGORY_HEALTH_PERMISSIONS);
    }

    private void logRecordTypeSpecificUpsertMetrics(
            @NonNull List<RecordInternal<?>> recordInternals, @NonNull String packageName) {
        Objects.requireNonNull(recordInternals);
        Objects.requireNonNull(packageName);

        Map<Integer, List<RecordInternal<?>>> recordTypeToRecordInternals =
                getRecordTypeToListOfRecords(recordInternals);
        for (Entry<Integer, List<RecordInternal<?>>> recordTypeToRecordInternalsEntry :
                recordTypeToRecordInternals.entrySet()) {
            RecordHelper<?> recordHelper =
                    RecordHelperProvider.getInstance()
                            .getRecordHelper(recordTypeToRecordInternalsEntry.getKey());
            recordHelper.logUpsertMetrics(recordTypeToRecordInternalsEntry.getValue(), packageName);
        }
    }

    private void logRecordTypeSpecificReadMetrics(
            @NonNull List<RecordInternal<?>> recordInternals, @NonNull String packageName) {
        Objects.requireNonNull(recordInternals);
        Objects.requireNonNull(packageName);

        Map<Integer, List<RecordInternal<?>>> recordTypeToRecordInternals =
                getRecordTypeToListOfRecords(recordInternals);
        for (Entry<Integer, List<RecordInternal<?>>> recordTypeToRecordInternalsEntry :
                recordTypeToRecordInternals.entrySet()) {
            RecordHelper<?> recordHelper =
                    RecordHelperProvider.getInstance()
                            .getRecordHelper(recordTypeToRecordInternalsEntry.getKey());
            recordHelper.logReadMetrics(recordTypeToRecordInternalsEntry.getValue(), packageName);
        }
    }

    private Map<Integer, List<RecordInternal<?>>> getRecordTypeToListOfRecords(
            List<RecordInternal<?>> recordInternals) {

        return recordInternals.stream()
                .collect(Collectors.groupingBy(RecordInternal::getRecordType));
    }

    private void throwSecurityException(String message) {
        throw new SecurityException(message);
    }

    private void throwExceptionIfDataSyncInProgress() {
        if (isDataSyncInProgress()) {
            throw new HealthConnectException(
                    HealthConnectException.ERROR_DATA_SYNC_IN_PROGRESS,
                    "Storage data sync in progress. API calls are blocked");
        }
    }

    /**
     * Throws an IllegalState Exception if data migration or restore is in process. This is only
     * used by HealthConnect synchronous APIs as {@link HealthConnectException} is lost between
     * processes on synchronous APIs and can only be returned to the caller for the APIs with a
     * callback.
     */
    private void throwIllegalStateExceptionIfDataSyncInProgress() {
        if (isDataSyncInProgress()) {
            throw new IllegalStateException("Storage data sync in progress. API calls are blocked");
        }
    }

    private static void postDeleteTasks(List<Integer> recordTypeIdsToDelete) {
        Trace.traceBegin(TRACE_TAG_DELETE_SUBTASKS, TAG_INSERT.concat("PostDeleteTasks"));
        if (recordTypeIdsToDelete != null && !recordTypeIdsToDelete.isEmpty()) {
            AppInfoHelper.getInstance()
                    .syncAppInfoRecordTypesUsed(new HashSet<>(recordTypeIdsToDelete));
            ActivityDateHelper.getInstance().reSyncByRecordTypeIds(recordTypeIdsToDelete);
        }
        Trace.traceEnd(TRACE_TAG_DELETE_SUBTASKS);
    }

    private static void tryAndReturnResult(
            IEmptyResponseCallback callback, HealthConnectServiceLogger.Builder builder) {
        try {
            callback.onResult();
            builder.setHealthDataServiceApiStatusSuccess();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote call failed", e);
            builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
        }
    }

    private static void tryAndReturnResult(
            IInsertRecordsResponseCallback callback,
            List<String> uuids,
            HealthConnectServiceLogger.Builder builder) {
        try {
            callback.onResult(new InsertRecordsResponseParcel(uuids));
            builder.setHealthDataServiceApiStatusSuccess();
        } catch (RemoteException e) {
            Slog.e(TAG, "Remote call failed", e);
            builder.setHealthDataServiceApiStatusError(ERROR_INTERNAL);
        }
    }

    private static void tryAndThrowException(
            @NonNull IInsertRecordsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IAggregateRecordsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IReadRecordsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IActivityDatesResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IGetChangeLogTokenCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IAccessLogsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IEmptyResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IApplicationInfoResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IChangeLogsResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IRecordTypeInfoResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IGetPriorityResponseCallback callback,
            @NonNull Exception exception,
            @HealthConnectException.ErrorCode int errorCode) {
        try {
            callback.onError(
                    new HealthConnectExceptionParcel(
                            new HealthConnectException(errorCode, exception.toString())));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private static void tryAndThrowException(
            @NonNull IMigrationCallback callback,
            @NonNull Exception exception,
            @MigrationException.ErrorCode int errorCode,
            @Nullable String failedEntityId) {
        try {
            callback.onError(
                    new MigrationException(exception.toString(), errorCode, failedEntityId));
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send result to the callback", e);
        }
    }
}
