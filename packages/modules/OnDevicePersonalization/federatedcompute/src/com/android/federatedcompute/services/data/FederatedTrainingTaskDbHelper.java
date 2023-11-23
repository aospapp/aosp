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

package com.android.federatedcompute.services.data;

import static com.android.federatedcompute.services.data.FederatedTraningTaskContract.FEDERATED_TRAINING_TASKS_TABLE;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.android.federatedcompute.services.data.FederatedTraningTaskContract.FederatedTrainingTaskColumns;
import com.android.internal.annotations.VisibleForTesting;

/** Helper to manage FederatedTrainingTask database. */
public class FederatedTrainingTaskDbHelper extends SQLiteOpenHelper {

    private static final String TAG = "FederatedTrainingTaskDbHelper";

    private static final int DATABASE_VERSION = 1;
    private static final String DATABASE_NAME = "trainingtasks.db";
    private static final String CREATE_TRAINING_TASK_TABLE =
            "CREATE TABLE "
                    + FEDERATED_TRAINING_TASKS_TABLE
                    + " ( "
                    + FederatedTrainingTaskColumns._ID
                    + " INTEGER PRIMARY KEY, "
                    + FederatedTrainingTaskColumns.APP_PACKAGE_NAME
                    + " TEXT NOT NULL, "
                    + FederatedTrainingTaskColumns.JOB_SCHEDULER_JOB_ID
                    + " INTEGER, "
                    + FederatedTrainingTaskColumns.POPULATION_NAME
                    + " TEXT NOT NULL,"
                    + FederatedTrainingTaskColumns.INTERVAL_OPTIONS
                    + " BLOB, "
                    + FederatedTrainingTaskColumns.CREATION_TIME
                    + " INTEGER, "
                    + FederatedTrainingTaskColumns.LAST_SCHEDULED_TIME
                    + " INTEGER, "
                    + FederatedTrainingTaskColumns.LAST_RUN_START_TIME
                    + " INTEGER, "
                    + FederatedTrainingTaskColumns.LAST_RUN_END_TIME
                    + " INTEGER, "
                    + FederatedTrainingTaskColumns.EARLIEST_NEXT_RUN_TIME
                    + " INTEGER, "
                    + FederatedTrainingTaskColumns.CONSTRAINTS
                    + " BLOB, "
                    + FederatedTrainingTaskColumns.SCHEDULING_REASON
                    + " INTEGER )";

    private static FederatedTrainingTaskDbHelper sInstance = null;

    private FederatedTrainingTaskDbHelper(Context context, String dbName) {
        super(context, dbName, null, DATABASE_VERSION);
    }

    /** Returns an instance of the FederatedTrainingTaskDbHelper given a context. */
    public static FederatedTrainingTaskDbHelper getInstance(Context context) {
        synchronized (FederatedTrainingTaskDbHelper.class) {
            if (sInstance == null) {
                sInstance = new FederatedTrainingTaskDbHelper(context, DATABASE_NAME);
            }
            return sInstance;
        }
    }

    /**
     * Returns an instance of the FederatedTrainingTaskDbHelper given a context. This is used for
     * testing only.
     */
    @VisibleForTesting
    public static FederatedTrainingTaskDbHelper getInstanceForTest(Context context) {
        synchronized (FederatedTrainingTaskDbHelper.class) {
            if (sInstance == null) {
                // Use null database name to make it in-memory
                sInstance = new FederatedTrainingTaskDbHelper(context, null);
            }
            return sInstance;
        }
    }

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TRAINING_TASK_TABLE);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        // TODO: handle upgrade when the db schema is changed.
        Log.d(TAG, "DB upgrade from " + oldVersion + " to " + newVersion);
    }

    @VisibleForTesting
    void resetDatabase(SQLiteDatabase db) {
        // Delete and recreate the database.
        // These tables must be dropped in order because of database constraints.
        db.execSQL("DROP TABLE IF EXISTS " + FEDERATED_TRAINING_TASKS_TABLE);
        onCreate(db);
    }

    @Override
    public void onConfigure(SQLiteDatabase db) {
        db.enableWriteAheadLogging();
    }

    /** It's only public to testing. */
    @VisibleForTesting
    public static void resetInstance() {
        synchronized (FederatedTrainingTaskDbHelper.class) {
            if (sInstance != null) {
                sInstance.close();
                sInstance = null;
            }
        }
    }
}
