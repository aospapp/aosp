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

package com.android.ondevicepersonalization.services.data.user;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;

import java.util.Calendar;
import java.util.List;

/** DAO for accessing to vendor data tables. */
public class UserDataDao {
    private static final String TAG = "UserDataDao";

    private static UserDataDao sUserDataDao;
    private final OnDevicePersonalizationDbHelper mDbHelper;
    public static final int TTL_IN_MEMORY_DAYS = 30;

    private UserDataDao(OnDevicePersonalizationDbHelper dbHelper) {
        this.mDbHelper = dbHelper;
    }

    /**
     * Returns an instance of the UserDataDao given a context.
     *
     * @param context    The context of the application.
     * @return Instance of UserDataDao for accessing the requested package's table.
     */
    public static UserDataDao getInstance(Context context) {
        synchronized (UserDataDao.class) {
            if (sUserDataDao == null) {
                sUserDataDao = new UserDataDao(
                    OnDevicePersonalizationDbHelper.getInstance(context));
            }
            return sUserDataDao;
        }
    }

    /**
     * Returns an instance of the UserDataDao given a context. This is used for testing only.
     */
    @VisibleForTesting
    public static UserDataDao getInstanceForTest(Context context) {
        synchronized (UserDataDao.class) {
            if (sUserDataDao == null) {
                sUserDataDao = new UserDataDao(
                    OnDevicePersonalizationDbHelper.getInstanceForTest(context));
            }
            return sUserDataDao;
        }
    }

    /**
     * Inserts location history row if it doesn't already exist.
     *
     * @return true if the insert succeeded, false otherwise.
     */
    public boolean insertLocationHistoryData(long timeSec, String latitude, String longitude,
                                             int source, boolean isPrecise) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            if (db == null) {
                return false;
            }
            ContentValues values = new ContentValues();
            values.put(UserDataTables.LocationHistory.TIME_SEC, timeSec);
            values.put(UserDataTables.LocationHistory.LATITUDE, latitude);
            values.put(UserDataTables.LocationHistory.LONGITUDE, longitude);
            values.put(UserDataTables.LocationHistory.SOURCE, source);
            values.put(UserDataTables.LocationHistory.IS_PRECISE, isPrecise);
            return db.insertWithOnConflict(UserDataTables.LocationHistory.TABLE_NAME, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to insert location history data", e);
            return false;
        }
    }

    /**
     * Inserts a single app usage history entry.
     *
     * @return true if the insert succeeded, false otherwise.
     */
    public boolean insertAppUsageStatsData(String packageName, long startingTimeSec,
                                             long endingTimeSec, long totalTimeUsedSec) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(UserDataTables.AppUsageHistory.PACKAGE_NAME, packageName);
            values.put(UserDataTables.AppUsageHistory.STARTING_TIME_SEC, startingTimeSec);
            values.put(UserDataTables.AppUsageHistory.ENDING_TIME_SEC, endingTimeSec);
            values.put(UserDataTables.AppUsageHistory.TOTAL_TIME_USED_SEC, totalTimeUsedSec);
            return db.insertWithOnConflict(UserDataTables.AppUsageHistory.TABLE_NAME, null, values,
                    SQLiteDatabase.CONFLICT_REPLACE) != -1;
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to insert app usage history data", e);
            return false;
        }
    }

     /**
     * Batch inserts a list of [UsageStats].
     * @return true if all insertions succeed as a transaction, false otherwise.
     */
    public boolean batchInsertAppUsageStatsData(List<AppUsageEntry> appUsageEntries) {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        if (db == null) {
            return false;
        }
        try {
            db.beginTransaction();
            for (AppUsageEntry entry : appUsageEntries) {
                if (!insertAppUsageStatsData(entry.packageName, entry.startTimeMillis,
                        entry.endTimeMillis, entry.totalTimeUsedMillis)) {
                    return false;
                }
            }
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return true;
    }

    /**
     * Read all app usage rows collected in the last x days.
     * @return
     */
    public Cursor readAppUsageInLastXDays(int dayCount) {
        if (dayCount > TTL_IN_MEMORY_DAYS) {
            Log.e(TAG, "Illegal attempt to read " + dayCount + " rows, which is more than "
                    + TTL_IN_MEMORY_DAYS + " days");
            return null;
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1 * dayCount);
        final long thresholdTimeMillis = cal.getTimeInMillis();
        try {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            String[] columns = new String[]{UserDataTables.AppUsageHistory.PACKAGE_NAME,
                    UserDataTables.AppUsageHistory.STARTING_TIME_SEC,
                    UserDataTables.AppUsageHistory.ENDING_TIME_SEC,
                    UserDataTables.AppUsageHistory.TOTAL_TIME_USED_SEC};
            String selection = UserDataTables.AppUsageHistory.ENDING_TIME_SEC + " >= ?";
            String[] selectionArgs = new String[]{String.valueOf(thresholdTimeMillis)};
            String orderBy = UserDataTables.AppUsageHistory.ENDING_TIME_SEC;
            return db.query(
                    UserDataTables.AppUsageHistory.TABLE_NAME,
                    columns,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    orderBy
            );
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to read " + UserDataTables.AppUsageHistory.TABLE_NAME
                    + " in the last " + dayCount + " days" , e);
        }
        return null;
    }

    /**
     * Return all location rows collected in the last X days.
     * @return
     */
    public Cursor readLocationInLastXDays(int dayCount) {
        if (dayCount > TTL_IN_MEMORY_DAYS) {
            Log.e(TAG, "Illegal attempt to read " + dayCount + " rows, which is more than "
                    + TTL_IN_MEMORY_DAYS + " days");
            return null;
        }
        Calendar cal = Calendar.getInstance();
        cal.add(Calendar.DATE, -1 * dayCount);
        final long thresholdTimeMillis = cal.getTimeInMillis();
        try {
            SQLiteDatabase db = mDbHelper.getReadableDatabase();
            String[] columns = new String[]{UserDataTables.LocationHistory.TIME_SEC,
                    UserDataTables.LocationHistory.LATITUDE,
                    UserDataTables.LocationHistory.LONGITUDE,
                    UserDataTables.LocationHistory.SOURCE,
                    UserDataTables.LocationHistory.IS_PRECISE};
            String selection = UserDataTables.LocationHistory.TIME_SEC + " >= ?";
            String[] selectionArgs = new String[]{String.valueOf(thresholdTimeMillis)};
            String orderBy = UserDataTables.LocationHistory.TIME_SEC;
            return db.query(
                    UserDataTables.LocationHistory.TABLE_NAME,
                    columns,
                    selection,
                    selectionArgs,
                    null,
                    null,
                    orderBy
            );
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to read " + UserDataTables.LocationHistory.TABLE_NAME
                    + " in the last " + dayCount + " days" , e);
        }
        return null;
    }

    /**
     * Clear all records in user data tables.
     * @return true if succeed, false otherwise.
     */
    public boolean clearUserData() {
        SQLiteDatabase db = mDbHelper.getWritableDatabase();
        if (db == null) {
            return false;
        }
        db.delete(UserDataTables.AppUsageHistory.TABLE_NAME, null, null);
        db.delete(UserDataTables.LocationHistory.TABLE_NAME, null, null);
        return true;
    }
}
