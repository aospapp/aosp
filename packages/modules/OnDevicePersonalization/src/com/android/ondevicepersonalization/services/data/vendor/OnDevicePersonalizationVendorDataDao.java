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

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;

import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Dao used to manage access to vendor data tables
 */
public class OnDevicePersonalizationVendorDataDao {
    private static final String TAG = "OnDevicePersonalizationVendorDataDao";
    private static final String VENDOR_DATA_TABLE_NAME_PREFIX = "vendordata_";

    private static final Map<String, OnDevicePersonalizationVendorDataDao> sVendorDataDaos =
            new HashMap<>();
    private final OnDevicePersonalizationDbHelper mDbHelper;
    private final String mOwner;
    private final String mCertDigest;
    private final String mTableName;

    private OnDevicePersonalizationVendorDataDao(OnDevicePersonalizationDbHelper dbHelper,
            String owner, String certDigest) {
        this.mDbHelper = dbHelper;
        this.mOwner = owner;
        this.mCertDigest = certDigest;
        this.mTableName = getTableName(owner, certDigest);
    }

    /**
     * Returns an instance of the OnDevicePersonalizationVendorDataDao given a context.
     *
     * @param context    The context of the application
     * @param owner      Name of package that owns the table
     * @param certDigest Hash of the certificate used to sign the package
     * @return Instance of OnDevicePersonalizationVendorDataDao for accessing the requested
     * package's table
     */
    public static OnDevicePersonalizationVendorDataDao getInstance(Context context, String owner,
            String certDigest) {
        synchronized (OnDevicePersonalizationVendorDataDao.class) {
            // TODO: Validate the owner and certDigest
            String tableName = getTableName(owner, certDigest);
            OnDevicePersonalizationVendorDataDao instance = sVendorDataDaos.get(tableName);
            if (instance == null) {
                OnDevicePersonalizationDbHelper dbHelper =
                        OnDevicePersonalizationDbHelper.getInstance(context);
                instance = new OnDevicePersonalizationVendorDataDao(
                        dbHelper, owner, certDigest);
                sVendorDataDaos.put(tableName, instance);
            }
            return instance;
        }
    }

    /**
     * Returns an instance of the OnDevicePersonalizationVendorDataDao given a context. This is used
     * for testing only
     */
    @VisibleForTesting
    public static OnDevicePersonalizationVendorDataDao getInstanceForTest(Context context,
            String owner, String certDigest) {
        synchronized (OnDevicePersonalizationVendorDataDao.class) {
            String tableName = getTableName(owner, certDigest);
            OnDevicePersonalizationVendorDataDao instance = sVendorDataDaos.get(tableName);
            if (instance == null) {
                OnDevicePersonalizationDbHelper dbHelper =
                        OnDevicePersonalizationDbHelper.getInstanceForTest(context);
                instance = new OnDevicePersonalizationVendorDataDao(
                        dbHelper, owner, certDigest);
                sVendorDataDaos.put(tableName, instance);
            }
            return instance;
        }
    }

    private static String getTableName(String owner, String certDigest) {
        owner = owner.replace(".", "_");
        return VENDOR_DATA_TABLE_NAME_PREFIX + owner + "_" + certDigest;
    }

    /**
     * Gets the name and cert of all vendors with VendorData & VendorSettings
     */
    public static List<Map.Entry<String, String>> getVendors(Context context) {
        OnDevicePersonalizationDbHelper dbHelper =
                OnDevicePersonalizationDbHelper.getInstance(context);
        SQLiteDatabase db = dbHelper.getReadableDatabase();
        String[] projection = {VendorSettingsContract.VendorSettingsEntry.OWNER,
                VendorSettingsContract.VendorSettingsEntry.CERT_DIGEST};
        Cursor cursor = db.query(
                /* distinct= */ true,
                VendorSettingsContract.VendorSettingsEntry.TABLE_NAME,
                projection,
                /* selection= */ null,
                /* selectionArgs= */ null,
                /* groupBy= */ null,
                /* having= */ null,
                /* orderBy= */ null,
                /* limit= */ null
        );

        List<Map.Entry<String, String>> result = new ArrayList<>();
        try {
            while (cursor.moveToNext()) {
                String owner = cursor.getString(cursor.getColumnIndexOrThrow(
                        VendorSettingsContract.VendorSettingsEntry.OWNER));
                String cert = cursor.getString(cursor.getColumnIndexOrThrow(
                        VendorSettingsContract.VendorSettingsEntry.CERT_DIGEST));
                result.add(new AbstractMap.SimpleImmutableEntry<>(owner, cert));
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to get Vendors", e);
        } finally {
            cursor.close();
        }
        return result;
    }

    /**
     * Performs a transaction to delete the vendorData table and vendorSettings for a given package.
     */
    public static boolean deleteVendorData(Context context, String owner, String certDigest) {
        OnDevicePersonalizationDbHelper dbHelper =
                OnDevicePersonalizationDbHelper.getInstance(context);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        String vendorDataTableName = getTableName(owner, certDigest);
        try {
            db.beginTransactionNonExclusive();
            // Delete rows from VendorSettings
            String selection = VendorSettingsContract.VendorSettingsEntry.OWNER + " = ? AND "
                    + VendorSettingsContract.VendorSettingsEntry.CERT_DIGEST + " = ?";
            String[] selectionArgs = {owner, certDigest};
            db.delete(VendorSettingsContract.VendorSettingsEntry.TABLE_NAME, selection,
                    selectionArgs);

            // Delete the vendorData table
            db.execSQL("DROP TABLE " + vendorDataTableName);
            OnDevicePersonalizationLocalDataDao.deleteTable(context, owner, certDigest);

            db.setTransactionSuccessful();
        } catch (Exception e) {
            Log.e(TAG, "Failed to delete vendorData for: " + owner, e);
            return false;
        } finally {
            db.endTransaction();
        }
        return true;
    }

    private boolean createTableIfNotExists(String tableName) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            db.execSQL(VendorDataContract.VendorDataEntry.getCreateTableIfNotExistsStatement(
                    tableName));
        } catch (SQLException e) {
            Log.e(TAG, "Failed to create table: " + tableName, e);
            return false;
        }
        return true;
    }

    /**
     * Reads all rows in the vendor data table
     *
     * @return Cursor of all rows in table
     */
    public Cursor readAllVendorData() {
        try {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            return db.query(
                    mTableName,
                    /* columns= */ null,
                    /* selection= */ null,
                    /* selectionArgs= */ null,
                    /* groupBy= */ null,
                    /* having= */ null,
                    /* orderBy= */ null
            );
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to read vendor data rows", e);
        }
        return null;
    }

    /**
     * Reads single row in the vendor data table
     *
     * @return Vendor data for the single row requested
     */
    public byte[] readSingleVendorDataRow(String key) {
        try {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            String[] projection = {VendorDataContract.VendorDataEntry.DATA};
            String selection = VendorDataContract.VendorDataEntry.KEY + " = ?";
            String[] selectionArgs = {key};
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
            Log.e(TAG, "Failed to read vendor data row", e);
        }
        return null;
    }

    /**
     * Reads all keys in the vendor data table
     *
     * @return Set of keys in the vendor data table.
     */
    public Set<String> readAllVendorDataKeys() {
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
     * Batch updates and/or inserts a list of vendor data and a corresponding syncToken and
     * deletes unretained keys.
     *
     * @return true if the transaction is successful. False otherwise.
     */
    public boolean batchUpdateOrInsertVendorDataTransaction(List<VendorData> vendorDataList,
            List<String> retainedKeys, long syncToken) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        try {
            db.beginTransactionNonExclusive();
            if (!createTableIfNotExists(mTableName)) {
                return false;
            }
            if (!OnDevicePersonalizationLocalDataDao.createTableIfNotExists(
                    OnDevicePersonalizationLocalDataDao.getTableName(mOwner, mCertDigest),
                    mDbHelper)) {
                return false;
            }
            if (!deleteUnretainedRows(retainedKeys)) {
                return false;
            }
            for (VendorData vendorData : vendorDataList) {
                if (!updateOrInsertVendorData(vendorData)) {
                    // The query failed. Return and don't finalize the transaction.
                    return false;
                }
            }
            if (!updateOrInsertSyncToken(syncToken)) {
                return false;
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    private boolean deleteUnretainedRows(List<String> retainedKeys) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            String retainedKeysString = retainedKeys.stream().map(s -> "'" + s + "'").collect(
                    Collectors.joining(",", "(", ")"));
            String whereClause = VendorDataContract.VendorDataEntry.KEY + " NOT IN "
                    + retainedKeysString;
            return db.delete(mTableName, whereClause,
                    null) != -1;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to delete unretained rows", e);
        }
        return false;
    }

    /**
     * Updates the given vendor data row, adds it if it doesn't already exist.
     *
     * @return true if the update/insert succeeded, false otherwise
     */
    private boolean updateOrInsertVendorData(VendorData vendorData) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(VendorDataContract.VendorDataEntry.KEY, vendorData.getKey());
            values.put(VendorDataContract.VendorDataEntry.DATA, vendorData.getData());
            return db.insertWithOnConflict(mTableName, null,
                    values, SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to update or insert buyer data", e);
        }
        return false;
    }

    /**
     * Updates the syncToken, adds it if it doesn't already exist.
     *
     * @return true if the update/insert succeeded, false otherwise
     */
    private boolean updateOrInsertSyncToken(long syncToken) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(VendorSettingsContract.VendorSettingsEntry.OWNER, mOwner);
            values.put(VendorSettingsContract.VendorSettingsEntry.CERT_DIGEST, mCertDigest);
            values.put(VendorSettingsContract.VendorSettingsEntry.SYNC_TOKEN, syncToken);
            return db.insertWithOnConflict(VendorSettingsContract.VendorSettingsEntry.TABLE_NAME,
                    null, values, SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to update or insert syncToken", e);
        }
        return false;
    }

    /**
     * Gets the syncToken owned by {@link #mOwner} with cert {@link #mCertDigest}
     *
     * @return syncToken if found, -1 otherwise
     */
    public long getSyncToken() {
        SQLiteDatabase db = mDbHelper.getReadableDatabase();
        String selection = VendorSettingsContract.VendorSettingsEntry.OWNER + " = ? AND "
                + VendorSettingsContract.VendorSettingsEntry.CERT_DIGEST + " = ?";
        String[] selectionArgs = {mOwner, mCertDigest};
        String[] projection = {VendorSettingsContract.VendorSettingsEntry.SYNC_TOKEN};
        Cursor cursor = db.query(
                VendorSettingsContract.VendorSettingsEntry.TABLE_NAME,
                projection,
                selection,
                selectionArgs,
                /* groupBy= */ null,
                /* having= */ null,
                /* orderBy= */ null
        );
        try {
            if (cursor.moveToFirst()) {
                return cursor.getLong(cursor.getColumnIndexOrThrow(
                        VendorSettingsContract.VendorSettingsEntry.SYNC_TOKEN));
            }
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to update or insert syncToken", e);
        } finally {
            cursor.close();
        }
        return -1;
    }
}
