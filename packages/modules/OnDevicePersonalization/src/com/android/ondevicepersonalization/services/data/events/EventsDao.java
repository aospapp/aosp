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

package com.android.ondevicepersonalization.services.data.events;

import android.annotation.NonNull;
import android.content.ContentValues;
import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;

/**
 * Dao used to manage access to Events and Queries tables
 */
public class EventsDao {
    private static final String TAG = "EventsDao";

    private static EventsDao sSingleton;

    private final OnDevicePersonalizationDbHelper mDbHelper;

    public EventsDao(@NonNull OnDevicePersonalizationDbHelper dbHelper) {
        this.mDbHelper = dbHelper;
    }

    /** Returns an instance of the EventsDao given a context. */
    public static EventsDao getInstance(@NonNull Context context) {
        synchronized (EventsDao.class) {
            if (sSingleton == null) {
                OnDevicePersonalizationDbHelper dbHelper =
                        OnDevicePersonalizationDbHelper.getInstance(context);
                sSingleton = new EventsDao(dbHelper);
            }
            return sSingleton;
        }
    }

    /**
     * Returns an instance of the EventsDao given a context. This is used
     * for testing only.
     */
    @VisibleForTesting
    public static EventsDao getInstanceForTest(@NonNull Context context) {
        synchronized (EventsDao.class) {
            if (sSingleton == null) {
                OnDevicePersonalizationDbHelper dbHelper =
                        OnDevicePersonalizationDbHelper.getInstanceForTest(context);
                sSingleton = new EventsDao(dbHelper);
            }
            return sSingleton;
        }
    }

    /**
     * Inserts the Event into the Events table.
     *
     * @return The row id of the newly inserted row if successful, -1 otherwise
     */
    public long insertEvent(@NonNull Event event) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(EventsContract.EventsEntry.QUERY_ID, event.getQueryId());
            values.put(EventsContract.EventsEntry.SLOT_INDEX, event.getSlotIndex());
            values.put(EventsContract.EventsEntry.TIME_MILLIS, event.getTimeMillis());
            values.put(EventsContract.EventsEntry.SLOT_ID, event.getSlotId());
            values.put(EventsContract.EventsEntry.BID_ID, event.getBidId());
            values.put(EventsContract.EventsEntry.SERVICE_PACKAGE_NAME,
                    event.getServicePackageName());
            values.put(EventsContract.EventsEntry.SLOT_POSITION, event.getSlotPosition());
            values.put(EventsContract.EventsEntry.TYPE, event.getType());
            values.put(EventsContract.EventsEntry.EVENT_DATA, event.getEventData());
            return db.insert(EventsContract.EventsEntry.TABLE_NAME, null,
                    values);
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to insert event", e);
        }
        return -1;
    }

    /**
     * Inserts the Query into the Queries table.
     *
     * @return The row id of the newly inserted row if successful, -1 otherwise
     */
    public long insertQuery(@NonNull Query query) {
        try {
            SQLiteDatabase db = mDbHelper.getWritableDatabase();
            ContentValues values = new ContentValues();
            values.put(QueriesContract.QueriesEntry.TIME_MILLIS, query.getTimeMillis());
            values.put(QueriesContract.QueriesEntry.SERVICE_PACKAGE_NAME,
                    query.getServicePackageName());
            values.put(QueriesContract.QueriesEntry.QUERY_DATA, query.getQueryData());
            return db.insert(QueriesContract.QueriesEntry.TABLE_NAME, null,
                    values);
        } catch (SQLiteException e) {
            Log.e(TAG, "Failed to insert query", e);
        }
        return -1;
    }
}
