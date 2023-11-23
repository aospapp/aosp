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

package com.android.adservices.data;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_WRITE_EXCEPTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__PPAPI_NAME_UNSPECIFIED;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;
import android.database.sqlite.SQLiteOpenHelper;

import com.android.adservices.LogUtil;
import com.android.adservices.data.enrollment.EnrollmentTables;
import com.android.adservices.data.measurement.MeasurementTables;
import com.android.adservices.data.measurement.migration.IMeasurementDbMigrator;
import com.android.adservices.data.measurement.migration.MeasurementDbMigratorV2;
import com.android.adservices.data.measurement.migration.MeasurementDbMigratorV3;
import com.android.adservices.data.measurement.migration.MeasurementDbMigratorV6;
import com.android.adservices.data.topics.TopicsTables;
import com.android.adservices.data.topics.migration.ITopicsDbMigrator;
import com.android.adservices.data.topics.migration.TopicDbMigratorV7;
import com.android.adservices.errorlogging.ErrorLogUtil;
import com.android.internal.annotations.VisibleForTesting;

import com.google.common.collect.ImmutableList;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * Helper to manage the PP API database. Designed as a singleton to make sure that all PP API usages
 * get the same reference.
 */
public class DbHelper extends SQLiteOpenHelper {
    public static final int DATABASE_VERSION = 7;

    private static final String DATABASE_NAME = "adservices.db";

    private static DbHelper sSingleton = null;
    private final File mDbFile;
    // The version when the database is actually created
    private final int mDbVersion;

    /**
     * It's only public to unit test.
     *
     * @param context the context
     * @param dbName Name of database to query
     * @param dbVersion db version
     */
    @VisibleForTesting
    public DbHelper(@NonNull Context context, @NonNull String dbName, int dbVersion) {
        super(context, dbName, null, dbVersion);
        mDbFile = context.getDatabasePath(dbName);
        this.mDbVersion = dbVersion;
    }

    /** Returns an instance of the DbHelper given a context. */
    @NonNull
    public static DbHelper getInstance(@NonNull Context ctx) {
        synchronized (DbHelper.class) {
            if (sSingleton == null) {
                sSingleton = new DbHelper(ctx, DATABASE_NAME, DATABASE_VERSION);
            }
            return sSingleton;
        }
    }

    @Override
    public void onCreate(@NonNull SQLiteDatabase db) {
        LogUtil.d("DbHelper.onCreate with version %d. Name: %s", mDbVersion, mDbFile.getName());
        for (String sql : TopicsTables.CREATE_STATEMENTS) {
            db.execSQL(sql);
        }
        for (String sql : EnrollmentTables.CREATE_STATEMENTS) {
            db.execSQL(sql);
        }
    }

    @Override
    public void onOpen(SQLiteDatabase db) {
        super.onOpen(db);
        db.execSQL("PRAGMA foreign_keys=ON");
    }

    /** Wraps getReadableDatabase to catch SQLiteException and log error. */
    @Nullable
    public SQLiteDatabase safeGetReadableDatabase() {
        try {
            return super.getReadableDatabase();
        } catch (SQLiteException e) {
            LogUtil.e(e, "Failed to get a readable database");
            ErrorLogUtil.e(
                    e,
                    AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION,
                    AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__PPAPI_NAME_UNSPECIFIED);
            return null;
        }
    }

    /** Wraps getWritableDatabase to catch SQLiteException and log error. */
    @Nullable
    public SQLiteDatabase safeGetWritableDatabase() {
        try {
            return super.getWritableDatabase();
        } catch (SQLiteException e) {
            LogUtil.e(e, "Failed to get a writeable database");
            ErrorLogUtil.e(
                    e,
                    AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_WRITE_EXCEPTION,
                    AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__PPAPI_NAME_UNSPECIFIED);
            return null;
        }
    }

    // TODO(b/255964885): Consolidate DB Migrator Class across Rubidium
    @Override
    public void onUpgrade(@NonNull SQLiteDatabase db, int oldVersion, int newVersion) {
        LogUtil.d(
                "DbHelper.onUpgrade. Attempting to upgrade version from %d to %d.",
                oldVersion, newVersion);
        if (hasV1MeasurementTables(db)) {
            getOrderedDbMigrators()
                    .forEach(dbMigrator -> dbMigrator.performMigration(db, oldVersion, newVersion));
        }
        try {
            topicsGetOrderedDbMigrators()
                    .forEach(dbMigrator -> dbMigrator.performMigration(db, oldVersion, newVersion));
        } catch (IllegalArgumentException e) {
            LogUtil.e(
                    "Topics DB Upgrade is not performed! oldVersion: %d, newVersion: %d.",
                    oldVersion, newVersion);
        }
    }

    /** Check if V1 measurement tables exist. */
    @VisibleForTesting
    public boolean hasV1MeasurementTables(SQLiteDatabase db) {
        List<String> selectionArgList = new ArrayList<>(Arrays.asList(MeasurementTables.V1_TABLES));
        selectionArgList.add("table"); // Schema type to match
        String[] selectionArgs = new String[selectionArgList.size()];
        selectionArgList.toArray(selectionArgs);
        return DatabaseUtils.queryNumEntries(
                        db,
                        "sqlite_master",
                        "name IN ("
                                + Stream.generate(() -> "?")
                                        .limit(MeasurementTables.V1_TABLES.length)
                                        .collect(Collectors.joining(","))
                                + ")"
                                + " AND type = ?",
                        selectionArgs)
                == MeasurementTables.V1_TABLES.length;
    }

    @Override
    public void onDowngrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        LogUtil.d("Downgrade database version from %d to %d.", oldVersion, newVersion);
        // prevent parent class to throw SQLiteException
    }

    public long getDbFileSize() {
        return mDbFile != null && mDbFile.exists() ? mDbFile.length() : -1;
    }

    /** Get Migrators in order for Measurement. */
    @VisibleForTesting
    public List<IMeasurementDbMigrator> getOrderedDbMigrators() {
        return ImmutableList.of(
                new MeasurementDbMigratorV2(),
                new MeasurementDbMigratorV3(),
                new MeasurementDbMigratorV6());
    }

    /** Get Migrators in order for Topics. */
    @VisibleForTesting
    public List<ITopicsDbMigrator> topicsGetOrderedDbMigrators() {
        return ImmutableList.of(new TopicDbMigratorV7());
    }
}
