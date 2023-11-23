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

package com.android.server.healthconnect.storage;

import android.annotation.NonNull;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.android.server.healthconnect.migration.PriorityMigrationHelper;
import com.android.server.healthconnect.storage.datatypehelpers.AccessLogsHelper;
import com.android.server.healthconnect.storage.datatypehelpers.ActivityDateHelper;
import com.android.server.healthconnect.storage.datatypehelpers.AppInfoHelper;
import com.android.server.healthconnect.storage.datatypehelpers.ChangeLogsHelper;
import com.android.server.healthconnect.storage.datatypehelpers.ChangeLogsRequestHelper;
import com.android.server.healthconnect.storage.datatypehelpers.DeviceInfoHelper;
import com.android.server.healthconnect.storage.datatypehelpers.HealthDataCategoryPriorityHelper;
import com.android.server.healthconnect.storage.datatypehelpers.MigrationEntityHelper;
import com.android.server.healthconnect.storage.datatypehelpers.PreferenceHelper;
import com.android.server.healthconnect.storage.datatypehelpers.RecordHelper;
import com.android.server.healthconnect.storage.request.CreateTableRequest;
import com.android.server.healthconnect.storage.utils.DropTableRequest;
import com.android.server.healthconnect.storage.utils.RecordHelperProvider;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Class to maintain the health connect DB. Actual operations are performed by {@link
 * TransactionManager}
 *
 * @hide
 */
public class HealthConnectDatabase extends SQLiteOpenHelper {
    public static final int DB_VERSION_UUID_BLOB = 9;

    public static final int DB_VERSION_GENERATED_LOCAL_TIME = 10;
    private static final String TAG = "HealthConnectDatabase";
    private static final int DATABASE_VERSION = 10;
    private static final String DATABASE_NAME = "healthconnect.db";
    @NonNull private final Collection<RecordHelper<?>> mRecordHelpers;
    private final Context mContext;

    public HealthConnectDatabase(@NonNull Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
        mRecordHelpers = RecordHelperProvider.getInstance().getRecordHelpers().values();
        mContext = context;
    }

    @Override
    public void onCreate(@NonNull SQLiteDatabase db) {
        for (CreateTableRequest createTableRequest : getCreateTableRequests()) {
            createTable(db, createTableRequest);
        }
    }

    @Override
    public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        if (oldVersion < DB_VERSION_UUID_BLOB) {
            dropAllTables(db);
            onCreate(db);
            return;
        }

        mRecordHelpers.forEach(recordHelper -> recordHelper.onUpgrade(db, oldVersion, newVersion));
        DeviceInfoHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
        AppInfoHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
        ChangeLogsHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
        ChangeLogsRequestHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
        HealthDataCategoryPriorityHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
        ActivityDateHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
        MigrationEntityHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
        PriorityMigrationHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
        PreferenceHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
        AccessLogsHelper.getInstance().onUpgrade(oldVersion, newVersion, db);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        // Enforce FK constraints for DB writes as we want to enforce FK constraints on DB write.
        // This is also required for when we delete entries, for cascade to work
        db.setForeignKeyConstraintsEnabled(true);
    }

    @Override
    public void onDowngrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        Log.i(TAG, "onDowngrade oldVersion = " + oldVersion + " newVersion = " + newVersion);
    }

    public File getDatabasePath() {
        return mContext.getDatabasePath(DATABASE_NAME);
    }

    private void dropAllTables(SQLiteDatabase db) {
        List<String> allTables =
                getCreateTableRequests().stream().map(CreateTableRequest::getTableName).toList();
        for (String table : allTables) {
            db.execSQL(new DropTableRequest(table).getCommand());
        }
    }

    private List<CreateTableRequest> getCreateTableRequests() {
        List<CreateTableRequest> requests = new ArrayList<>();
        mRecordHelpers.forEach(
                (recordHelper) ->
                        addCreateRequestsFor(recordHelper.getCreateTableRequest(), requests));
        addCreateRequestsFor(DeviceInfoHelper.getInstance().getCreateTableRequest(), requests);
        addCreateRequestsFor(AppInfoHelper.getInstance().getCreateTableRequest(), requests);
        addCreateRequestsFor(ActivityDateHelper.getInstance().getCreateTableRequest(), requests);
        addCreateRequestsFor(ChangeLogsHelper.getInstance().getCreateTableRequest(), requests);
        addCreateRequestsFor(
                ChangeLogsRequestHelper.getInstance().getCreateTableRequest(), requests);
        addCreateRequestsFor(
                HealthDataCategoryPriorityHelper.getInstance().getCreateTableRequest(), requests);
        addCreateRequestsFor(PreferenceHelper.getInstance().getCreateTableRequest(), requests);
        addCreateRequestsFor(AccessLogsHelper.getInstance().getCreateTableRequest(), requests);
        addCreateRequestsFor(MigrationEntityHelper.getInstance().getCreateTableRequest(), requests);
        addCreateRequestsFor(
                PriorityMigrationHelper.getInstance().getCreateTableRequest(), requests);

        return requests;
    }

    private void addCreateRequestsFor(
            CreateTableRequest createTableRequest, List<CreateTableRequest> tableRequests) {
        tableRequests.add(createTableRequest);
        createTableRequest
                .getChildTableRequests()
                .forEach(
                        (childTableRequest) ->
                                addCreateRequestsFor(childTableRequest, tableRequests));
    }

    /** Runs create table request on database. */
    public static void createTable(SQLiteDatabase db, CreateTableRequest createTableRequest) {
        db.execSQL(createTableRequest.getCreateCommand());
        createTableRequest.getCreateIndexStatements().forEach(db::execSQL);
    }

    public static String getName() {
        return DATABASE_NAME;
    }
}
