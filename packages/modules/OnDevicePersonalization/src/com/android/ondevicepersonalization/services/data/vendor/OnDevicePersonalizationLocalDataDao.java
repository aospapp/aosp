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

package com.android.ondevicepersonalization.services.data.vendor;

import android.annotation.NonNull;
import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * Dao used to manage access to local data tables
 */
public class OnDevicePersonalizationLocalDataDao {
    private static final String TAG = "OnDevicePersonalizationLocalDataDao";
    private static final String LOCAL_DATA_TABLE_NAME_PREFIX = "localdata_";

    private static final Map<String, OnDevicePersonalizationLocalDataDao> sLocalDataDaos =
            new HashMap<>();
    private final OnDevicePersonalizationDbHelper mDbHelper;
    private final String mOwner;
    private final String mCertDigest;
    private final String mTableName;

    private OnDevicePersonalizationLocalDataDao(OnDevicePersonalizationDbHelper dbHelper,
            String owner, String certDigest) {
        this.mDbHelper = dbHelper;
        this.mOwner = owner;
        this.mCertDigest = certDigest;
        this.mTableName = getTableName(owner, certDigest);
    }

    /**
     * Returns an instance of the OnDevicePersonalizationLocalDataDao given a context.
     *
     * @param context    The context of the application
     * @param owner      Name of package that owns the table
     * @param certDigest Hash of the certificate used to sign the package
     * @return Instance of OnDevicePersonalizationLocalDataDao for accessing the requested
     * package's table
     */
    public static OnDevicePersonalizationLocalDataDao getInstance(Context context, String owner,
            String certDigest) {
        synchronized (OnDevicePersonalizationLocalDataDao.class) {
            // TODO: Validate the owner and certDigest
            String tableName = getTableName(owner, certDigest);
            OnDevicePersonalizationLocalDataDao instance = sLocalDataDaos.get(tableName);
            if (instance == null) {
                OnDevicePersonalizationDbHelper dbHelper =
                        OnDevicePersonalizationDbHelper.getInstance(context);
                instance = new OnDevicePersonalizationLocalDataDao(
                        dbHelper, owner, certDigest);
                sLocalDataDaos.put(tableName, instance);
            }
            return instance;
        }
    }

    /**
     * Returns an instance of the OnDevicePersonalizationLocalDataDao given a context. This is used
     * for testing only
     */
    @VisibleForTesting
    public static OnDevicePersonalizationLocalDataDao getInstanceForTest(Context context,
            String owner, String certDigest) {
        synchronized (OnDevicePersonalizationLocalDataDao.class) {
            String tableName = getTableName(owner, certDigest);
            OnDevicePersonalizationLocalDataDao instance = sLocalDataDaos.get(tableName);
            if (instance == null) {
                OnDevicePersonalizationDbHelper dbHelper =
                        OnDevicePersonalizationDbHelper.getInstanceForTest(context);
                createTableIfNotExists(tableName, dbHelper);
                instance = new OnDevicePersonalizationLocalDataDao(
                        dbHelper, owner, certDigest);
                sLocalDataDaos.put(tableName, instance);
            }
            return instance;
        }
    }


    /**
     * Attempts to create the LocalData table
     *
     * @return true if it already exists or was created, false otherwise.
     */
    public static boolean createTableIfNotExists(String tableName,
            OnDevicePersonalizationDbHelper dbHelper) {
        try {
            SQLiteDatabase db = dbHelper.getWritableDatabase();
            db.execSQL(LocalDataContract.LocalDataEntry.getCreateTableIfNotExistsStatement(
                    tableName));
        } catch (SQLException e) {
            Log.e(TAG, "Failed to create table: " + tableName, e);
            return false;
        }
        return true;
    }

    /**
     * Creates the LocalData table name for the given owner
     */
    public static String getTableName(String owner, String certDigest) {
        owner = owner.replace(".", "_");
        return LOCAL_DATA_TABLE_NAME_PREFIX + owner + "_" + certDigest;
    }

    /**
     * Reads single row in the local data table
     *
     * @return Local data for the single row requested
     */
    public byte[] readSingleLocalDataRow(String key) {
        try {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            String[] projection = {LocalDataContract.LocalDataEntry.DATA};
            String selection = LocalDataContract.LocalDataEntry.KEY + " = ?";
            String[] selectionArgs = { key };
            try (Cursor cursor = db.query(
                    mTableName,
                    projection,
                    selection,
                    selectionArgs,
                    /* groupBy= */ null,
                    /* having= */ null,
                    /* orderBy= */ null
            )) {
                if (cursor.getCount() < 1) {
                    Log.d(TAG, "Failed to find requested key: " + key);
                    return null;
                }
                cursor.moveToNext();
                return cursor.getBlob(0);
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to read local data row", e);
        }
        return null;
    }

    /**
     * Updates the given local data row, adds it if it doesn't already exist.
     *
     * @return true if the update/insert succeeded, false otherwise
     */
    public boolean updateOrInsertLocalData(LocalData localData) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(LocalDataContract.LocalDataEntry.KEY, localData.getKey());
            values.put(LocalDataContract.LocalDataEntry.DATA, localData.getData());
            return db.insertWithOnConflict(mTableName, null,
                    values, SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to update or insert local data", e);
        }
        return false;
    }

    /**
     * Deletes the row with the specified key from the local data table
     *
     * @param key the key specifying the row to delete
     * @return true if the row was deleted, false otherwise.
     */
    public boolean deleteLocalDataRow(@NonNull String key) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            String whereClause = LocalDataContract.LocalDataEntry.KEY + " = ?";
            String[] selectionArgs = { key };
            return db.delete(mTableName, whereClause, selectionArgs) == 1;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to delete row from local data", e);
        }
        return false;
    }

    /**
     * Reads all keys in the local data table
     *
     * @return Set of keys in the local data table.
     */
    public Set<String> readAllLocalDataKeys() {
        Set<String> keyset = new HashSet<>();
        try {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            String[] projection = {VendorDataContract.VendorDataEntry.KEY};
            try (Cursor cursor = db.query(
                    mTableName,
                    projection,
                    /* selection= */ null,
                    /* selectionArgs= */ null,
                    /* groupBy= */ null,
                    /* having= */ null,
                    /* orderBy= */ null
            )) {
                while (cursor.moveToNext()) {
                    String key = cursor.getString(
                            cursor.getColumnIndexOrThrow(VendorDataContract.VendorDataEntry.KEY));
                    keyset.add(key);
                }
                cursor.close();
                return keyset;
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to read all vendor data keys", e);
        }
        return keyset;
    }

    /**
     * Deletes LocalData table for given owner
     */
    public static void deleteTable(Context context, String owner, String certDigest) {
        OnDevicePersonalizationDbHelper dbHelper =
                OnDevicePersonalizationDbHelper.getInstance(context);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        db.execSQL("DROP TABLE " + getTableName(owner, certDigest));
    }
}
