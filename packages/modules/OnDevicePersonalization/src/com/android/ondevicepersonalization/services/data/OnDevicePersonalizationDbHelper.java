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

package com.android.ondevicepersonalization.services.data;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.data.events.EventsContract;
import com.android.ondevicepersonalization.services.data.events.QueriesContract;
import com.android.ondevicepersonalization.services.data.user.UserDataTables;
import com.android.ondevicepersonalization.services.data.vendor.VendorSettingsContract;

/**
 * Helper to manage the OnDevicePersonalization database.
 */
public class OnDevicePersonalizationDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "OnDevicePersonalizationDbHelper";

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "ondevicepersonalization.db";

    private static OnDevicePersonalizationDbHelper sSingleton = null;

    private OnDevicePersonalizationDbHelper(Context context, String dbName) {
        super(context, dbName, null, DATABASE_VERSION);
    }

    /** Returns an instance of the OnDevicePersonalizationDbHelper given a context. */
    public static OnDevicePersonalizationDbHelper getInstance(Context context) {
        synchronized (OnDevicePersonalizationDbHelper.class) {
            if (sSingleton == null) {
                sSingleton = new OnDevicePersonalizationDbHelper(context, DATABASE_NAME);
            }
            return sSingleton;
        }
    }

    /**
     * Returns an instance of the OnDevicePersonalizationDbHelper given a context. This is used
     * for testing only.
     */
    @VisibleForTesting
    public static OnDevicePersonalizationDbHelper getInstanceForTest(Context context) {
        synchronized (OnDevicePersonalizationDbHelper.class) {
            if (sSingleton == null) {
                // Use null database name to make it in-memory
                sSingleton = new OnDevicePersonalizationDbHelper(context, null);
            }
            return sSingleton;
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(VendorSettingsContract.VendorSettingsEntry.CREATE_TABLE_STATEMENT);

        // Queries and events tables.
        db.execSQL(QueriesContract.QueriesEntry.CREATE_TABLE_STATEMENT);
        db.execSQL(EventsContract.EventsEntry.CREATE_TABLE_STATEMENT);

        // User data tables and indexes.
        db.execSQL(UserDataTables.LocationHistory.CREATE_TABLE_STATEMENT);
        db.execSQL(UserDataTables.LocationHistory.CREATE_INDEXES_STATEMENT);
        db.execSQL(UserDataTables.AppUsageHistory.CREATE_TABLE_STATEMENT);
        db.execSQL(UserDataTables.AppUsageHistory.CREATE_STARTING_TIME_SEC_INDEX_STATEMENT);
        db.execSQL(UserDataTables.AppUsageHistory.CREATE_ENDING_TIME_SEC_INDEX_STATEMENT);
        db.execSQL(UserDataTables.AppUsageHistory.CREATE_TOTAL_TIME_USED_SEC_INDEX_STATEMENT);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // TODO: handle upgrade when the db schema is changed.
        Log.d(TAG, "DB upgrade from " + oldVersion + " to " + newVersion);
        throw new UnsupportedOperationException(
                "Database upgrade for OnDevicePersonalization is unsupported");
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.setForeignKeyConstraintsEnabled(true);
        db.enableWriteAheadLogging();
    }
}
