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

package com.android.adservices.data.shared.migration;

import android.content.Context;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;

import androidx.annotation.NonNull;

import com.android.adservices.data.enrollment.EnrollmentDbSchemaTrail;

public class MigrationTestHelper {

    public static SQLiteDatabase createReferenceDbAtVersion(
            Context context, String dbName, int version) {
        EmptySqliteOpenHelper dbHelper = new EmptySqliteOpenHelper(context, dbName);
        SQLiteDatabase db = dbHelper.getWritableDatabase();
        for (String createStatement :
                EnrollmentDbSchemaTrail.getCreateTableStatementsByVersion(version)) {
            db.execSQL(createStatement);
        }

        return db;
    }

    private static class EmptySqliteOpenHelper extends SQLiteOpenHelper {
        private static final int DB_VERSION = 1;

        private EmptySqliteOpenHelper(@NonNull Context context, @NonNull String dbName) {
            super(context, dbName, null, DB_VERSION);
        }

        @Override
        public void onCreate(SQLiteDatabase db) {
            // No-op
        }

        @Override
        public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
            // No-op
        }
    }
}
