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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import com.android.federatedcompute.services.data.FederatedTraningTaskContract.FederatedTrainingTaskColumns;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.collect.Iterables;

import java.util.List;

/** DAO for accessing training task table. */
public class FederatedTrainingTaskDao {

    private static final String TAG = "FederatedTrainingTaskDao";

    private final SQLiteOpenHelper mDbHelper;
    private static FederatedTrainingTaskDao sSingletonInstance;

    private FederatedTrainingTaskDao(SQLiteOpenHelper dbHelper) {
        this.mDbHelper = dbHelper;
    }

    /** Returns an instance of the FederatedTrainingTaskDao given a context. */
    @NonNull
    public static FederatedTrainingTaskDao getInstance(Context context) {
        synchronized (FederatedTrainingTaskDao.class) {
            if (sSingletonInstance == null) {
                sSingletonInstance =
                        new FederatedTrainingTaskDao(
                                FederatedTrainingTaskDbHelper.getInstance(context));
            }
            return sSingletonInstance;
        }
    }

    /** It's only public to unit test. */
    @VisibleForTesting
    public static FederatedTrainingTaskDao getInstanceForTest(Context context) {
        synchronized (FederatedTrainingTaskDao.class) {
            if (sSingletonInstance == null) {
                FederatedTrainingTaskDbHelper dbHelper =
                        FederatedTrainingTaskDbHelper.getInstanceForTest(context);
                sSingletonInstance = new FederatedTrainingTaskDao(dbHelper);
            }
            return sSingletonInstance;
        }
    }

    /** Deletes a training task in FederatedTrainingTask table. */
    private void deleteFederatedTrainingTask(String selection, String[] selectionArgs) {
        SQLiteDatabase db = getWritableDatabase();
        if (db == null) {
            return;
        }
        db.delete(FEDERATED_TRAINING_TASKS_TABLE, selection, selectionArgs);
    }

    /** Insert a training task or update it if task already exists. */
    public boolean updateOrInsertFederatedTrainingTask(FederatedTrainingTask trainingTask) {
        SQLiteDatabase db = getWritableDatabase();
        if (db == null) {
            throw new SQLiteException("Failed to open database.");
        }
        return trainingTask.addToDatabase(db);
    }

    /** Get the list of tasks that match select conditions. */
    @Nullable
    public List<FederatedTrainingTask> getFederatedTrainingTask(
            String selection, String[] selectionArgs) {
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        if (db == null) {
            return null;
        }
        return FederatedTrainingTask.readFederatedTrainingTasksFromDatabase(
                db, selection, selectionArgs);
    }

    /** Delete a task from table based on job scheduler id. */
    public FederatedTrainingTask findAndRemoveTaskByJobId(int jobId) {
        String selection = FederatedTrainingTaskColumns.JOB_SCHEDULER_JOB_ID + " = ?";
        String[] selectionArgs = selectionArgs(jobId);
        FederatedTrainingTask task =
                Iterables.getOnlyElement(getFederatedTrainingTask(selection, selectionArgs), null);
        if (task != null) {
            deleteFederatedTrainingTask(selection, selectionArgs);
        }
        return task;
    }

    /** Delete a task from table based on population name. */
    public FederatedTrainingTask findAndRemoveTaskByPopulationName(String populationName) {
        String selection = FederatedTrainingTaskColumns.POPULATION_NAME + " = ?";
        String[] selectionArgs = {populationName};
        FederatedTrainingTask task =
                Iterables.getOnlyElement(getFederatedTrainingTask(selection, selectionArgs), null);
        if (task != null) {
            deleteFederatedTrainingTask(selection, selectionArgs);
        }
        return task;
    }

    /** Delete a task from table based on population name and job scheduler id. */
    public FederatedTrainingTask findAndRemoveTaskByPopulationAndJobId(
            String populationName, int jobId) {
        String selection =
                FederatedTrainingTaskColumns.POPULATION_NAME
                        + " = ? AND "
                        + FederatedTrainingTaskColumns.JOB_SCHEDULER_JOB_ID
                        + " = ?";
        String[] selectionArgs = {populationName, String.valueOf(jobId)};
        FederatedTrainingTask task =
                Iterables.getOnlyElement(getFederatedTrainingTask(selection, selectionArgs), null);
        if (task != null) {
            deleteFederatedTrainingTask(selection, selectionArgs);
        }
        return task;
    }

    private String[] selectionArgs(Number... args) {
        String[] values = new String[args.length];
        for (int i = 0; i < args.length; i++) {
            values[i] = String.valueOf(args[i]);
        }
        return values;
    }

    /** It's only public to unit test. Clears all records in task table. */
    @VisibleForTesting
    public boolean clearDatabase() {
        SQLiteDatabase db = getWritableDatabase();
        if (db == null) {
            return false;
        }
        db.delete(FEDERATED_TRAINING_TASKS_TABLE, null, null);
        return true;
    }

    /* Returns a writable database object or null if error occurs. */
    @Nullable
    private SQLiteDatabase getWritableDatabase() {
        try {
            return mDbHelper.getWritableDatabase();
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to open the database.", e);
        }
        return null;
    }
}
