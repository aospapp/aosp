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

package com.android.adservices.data.adselection;

import static org.junit.Assert.assertEquals;

import android.app.Instrumentation;
import android.database.Cursor;

import androidx.room.testing.MigrationTestHelper;
import androidx.sqlite.db.SupportSQLiteDatabase;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class AdSelectionDatabaseMigrationTest {
    private static final String QUERY_TABLES_FROM_SQL_MASTER =
            "SELECT * FROM sqlite_master " + "WHERE type='table' AND name='%s';";
    private static final String COLUMN_NAME_NAME = "name";
    private static final String TEST_DB = "migration-test";
    private static final Instrumentation INSTRUMENTATION =
            InstrumentationRegistry.getInstrumentation();

    @Rule
    public MigrationTestHelper helper =
            new MigrationTestHelper(INSTRUMENTATION, AdSelectionDatabase.class);

    @Test
    public void testMigrate1To2() throws IOException {
        String adSelectionOverridesTable = "ad_selection_from_outcomes_overrides";

        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 1);
        Cursor c = db.query(String.format(QUERY_TABLES_FROM_SQL_MASTER, adSelectionOverridesTable));
        assertEquals(0, c.getCount());

        // Re-open the database with version 2 and provide MIGRATION_1_2 as the migration process.
        db = helper.runMigrationsAndValidate(TEST_DB, 2, true);
        c = db.query(String.format(QUERY_TABLES_FROM_SQL_MASTER, adSelectionOverridesTable));
        assertEquals(1, c.getCount());
        c.moveToFirst();
        assertEquals(adSelectionOverridesTable, c.getString(c.getColumnIndex(COLUMN_NAME_NAME)));
    }

    @Test
    public void testMigrate2To3() throws IOException {
        String registeredAdInteractionsTable = "registered_ad_interactions";

        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 2);
        Cursor c =
                db.query(
                        String.format(QUERY_TABLES_FROM_SQL_MASTER, registeredAdInteractionsTable));
        assertEquals(0, c.getCount());

        // Re-open the database with version 3 and provide MIGRATION_2_3 as the migration process.
        db = helper.runMigrationsAndValidate(TEST_DB, 3, true);
        c = db.query(String.format(QUERY_TABLES_FROM_SQL_MASTER, registeredAdInteractionsTable));
        assertEquals(1, c.getCount());
        c.moveToFirst();
        assertEquals(
                registeredAdInteractionsTable, c.getString(c.getColumnIndex(COLUMN_NAME_NAME)));
    }

    @Test
    public void testMigrate3To4() throws IOException {
        String adSelectionTable = "ad_selection";

        SupportSQLiteDatabase db = helper.createDatabase(TEST_DB, 3);
        Cursor cursor = db.query(String.format(QUERY_TABLES_FROM_SQL_MASTER, adSelectionTable));
        // The ad selection table should already exist
        assertEquals(1, cursor.getCount());

        // Re-open the database with version 4 and provide MIGRATION_3_4 as the migration process.
        db = helper.runMigrationsAndValidate(TEST_DB, 4, true);
        cursor = db.query(String.format(QUERY_TABLES_FROM_SQL_MASTER, adSelectionTable));
        assertEquals(1, cursor.getCount());
        cursor.moveToFirst();
        assertEquals(adSelectionTable, cursor.getString(cursor.getColumnIndex(COLUMN_NAME_NAME)));
    }
}
