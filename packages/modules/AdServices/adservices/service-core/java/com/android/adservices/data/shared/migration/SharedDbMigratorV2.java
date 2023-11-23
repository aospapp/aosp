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

import android.database.sqlite.SQLiteDatabase;

import com.android.adservices.data.DbHelper;

/** Migrates Enrollment DB to version 2. */
public class SharedDbMigratorV2 implements ISharedDbMigrator {
    public SharedDbMigratorV2(DbHelper dbHelper) {}

    @Override
    public void performMigration(SQLiteDatabase db, int oldVersion, int newVersion) {
        // todo (b/277964086) Updating Schema in M8
    }
}
