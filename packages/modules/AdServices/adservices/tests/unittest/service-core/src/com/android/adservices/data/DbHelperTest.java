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

import static com.android.adservices.data.DbHelper.DATABASE_VERSION;
import static com.android.adservices.data.DbTestUtil.doesIndexExist;
import static com.android.adservices.data.DbTestUtil.doesTableExist;
import static com.android.adservices.data.DbTestUtil.doesTableExistAndColumnCountMatch;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_WRITE_EXCEPTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__PPAPI_NAME_UNSPECIFIED;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;

import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteException;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.data.measurement.DbHelperV1;
import com.android.adservices.data.measurement.MeasurementTables;
import com.android.adservices.data.topics.migration.TopicDbMigratorV7;
import com.android.adservices.errorlogging.ErrorLogUtil;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.quality.Strictness;

import java.util.Arrays;
import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class DbHelperTest {
    protected static final Context sContext = ApplicationProvider.getApplicationContext();

    private MockitoSession mStaticMockSession;

    @Mock private Flags mMockFlags;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(ErrorLogUtil.class)
                        .strictness(Strictness.WARN)
                        .startMocking();
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
    }

    @After
    public void teardown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testOnCreate() {
        SQLiteDatabase db = DbTestUtil.getDbHelperForTest().safeGetReadableDatabase();
        assertNotNull(db);
        assertTrue(doesTableExistAndColumnCountMatch(db, "topics_taxonomy", 4));
        assertTrue(doesTableExistAndColumnCountMatch(db, "topics_app_classification_topics", 6));
        assertTrue(doesTableExistAndColumnCountMatch(db, "topics_caller_can_learn_topic", 6));
        assertTrue(doesTableExistAndColumnCountMatch(db, "topics_top_topics", 10));
        assertTrue(doesTableExistAndColumnCountMatch(db, "topics_returned_topics", 7));
        assertTrue(doesTableExistAndColumnCountMatch(db, "topics_usage_history", 3));
        assertTrue(doesTableExistAndColumnCountMatch(db, "topics_app_usage_history", 3));
        assertTrue(doesTableExistAndColumnCountMatch(db, "enrollment_data", 8));
        assertMeasurementTablesDoNotExist(db);
    }

    public static void assertMeasurementTablesDoNotExist(SQLiteDatabase db) {
        assertFalse(doesTableExist(db, "msmt_source"));
        assertFalse(doesTableExist(db, "msmt_trigger"));
        assertFalse(doesTableExist(db, "msmt_async_registration_contract"));
        assertFalse(doesTableExist(db, "msmt_event_report"));
        assertFalse(doesTableExist(db, "msmt_attribution"));
        assertFalse(doesTableExist(db, "msmt_aggregate_report"));
        assertFalse(doesTableExist(db, "msmt_aggregate_encryption_key"));
        assertFalse(doesTableExist(db, "msmt_debug_report"));
        assertFalse(doesTableExist(db, "msmt_xna_ignored_sources"));
    }

    public static void assertEnrollmentTableDoesNotExist(SQLiteDatabase db) {
        assertFalse(doesTableExist(db, "enrollment-data"));
    }

    @Test
    public void testGetDbFileSize() {
        final String databaseName = "testsize.db";
        DbHelper dbHelper = new DbHelper(sContext, databaseName, 1);

        // Create database
        dbHelper.getReadableDatabase();

        // Verify size should be more than 0 bytes as database was created
        Assert.assertTrue(dbHelper.getDbFileSize() > 0);

        // Delete database file
        sContext.getDatabasePath(databaseName).delete();

        // Verify database does not exist anymore
        Assert.assertEquals(-1, dbHelper.getDbFileSize());
    }

    @Test
    public void onOpen_appliesForeignKeyConstraint() {
        // dbHelper.onOpen gets called implicitly
        SQLiteDatabase db = DbTestUtil.getDbHelperForTest().safeGetReadableDatabase();
        try (Cursor cursor = db.rawQuery("PRAGMA foreign_keys", null)) {
            cursor.moveToNext();
            assertEquals(1, cursor.getLong(0));
        }
    }

    @Test
    public void testOnUpgrade_topicsV7Migration() {
        DbHelper dbHelper = spy(DbTestUtil.getDbHelperForTest());
        SQLiteDatabase db = mock(SQLiteDatabase.class);

        // Do not actually perform queries but verify the invocation.
        TopicDbMigratorV7 topicDbMigratorV7 = Mockito.spy(new TopicDbMigratorV7());
        Mockito.doNothing().when(topicDbMigratorV7).performMigration(db);

        // Ignore Measurement Migrators
        doReturn(false).when(dbHelper).hasV1MeasurementTables(db);
        doReturn(List.of()).when(dbHelper).getOrderedDbMigrators();
        doReturn(List.of(topicDbMigratorV7)).when(dbHelper).topicsGetOrderedDbMigrators();

        // Negative case - target version 5 is not in (oldVersion, newVersion]
        dbHelper.onUpgrade(db, /* oldVersion */ 1, /* new Version */ 5);
        Mockito.verify(topicDbMigratorV7, Mockito.never()).performMigration(db);

        // Positive case - target version 5 is in (oldVersion, newVersion]
        dbHelper.onUpgrade(db, /* oldVersion */ 1, /* new Version */ 7);
        Mockito.verify(topicDbMigratorV7).performMigration(db);
    }

    @Test
    public void testOnDowngrade() {
        DbHelper dbHelper = spy(DbTestUtil.getDbHelperForTest());
        SQLiteDatabase db = mock(SQLiteDatabase.class);

        // Verify no error if downgrading db from current version to V1
        dbHelper.onDowngrade(db, DATABASE_VERSION, 1);
    }

    @Test
    public void testSafeGetReadableDatabase_exceptionOccurs_validatesErrorLogging() {
        DbHelper dbHelper = spy(DbTestUtil.getDbHelperForTest());
        Throwable tr = new SQLiteException();
        Mockito.doThrow(tr).when(dbHelper).getReadableDatabase();
        ExtendedMockito.doNothing().when(() -> ErrorLogUtil.e(any(), anyInt(), anyInt()));

        SQLiteDatabase db = dbHelper.safeGetReadableDatabase();

        assertNull(db);
        ExtendedMockito.verify(
                () ->
                        ErrorLogUtil.e(
                                tr,
                                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_READ_EXCEPTION,
                                AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__PPAPI_NAME_UNSPECIFIED));
    }

    @Test
    public void testSafeGetWriteDatabase_exceptionOccurs_validatesErrorLogging() {
        DbHelper dbHelper = spy(DbTestUtil.getDbHelperForTest());
        Throwable tr = new SQLiteException();
        Mockito.doThrow(tr).when(dbHelper).getWritableDatabase();
        ExtendedMockito.doNothing().when(() -> ErrorLogUtil.e(any(), anyInt(), anyInt()));

        SQLiteDatabase db = dbHelper.safeGetWritableDatabase();

        assertNull(db);
        ExtendedMockito.verify(
                () ->
                        ErrorLogUtil.e(
                                tr,
                                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__DATABASE_WRITE_EXCEPTION,
                                AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__PPAPI_NAME_UNSPECIFIED));
    }

    @Test
    public void testOnUpgrade_measurementMigration_tablesExist() {
        String dbName = "test_db";
        DbHelperV1 dbHelperV1 = new DbHelperV1(sContext, dbName, 1);
        SQLiteDatabase db = dbHelperV1.safeGetWritableDatabase();

        assertEquals(1, db.getVersion());

        DbHelper dbHelper = new DbHelper(sContext, dbName, DATABASE_VERSION);
        dbHelper.onUpgrade(db, 1, DATABASE_VERSION);
        assertMeasurementSchema(db);
    }

    @Test
    public void testOnUpgrade_measurementMigration_tablesDoNotExist() {
        String dbName = "test_db_2";
        DbHelperV1 dbHelperV1 = new DbHelperV1(sContext, dbName, 1);
        SQLiteDatabase db = dbHelperV1.safeGetWritableDatabase();

        assertEquals(1, db.getVersion());
        Arrays.stream(MeasurementTables.V1_TABLES).forEach((table) -> dropTable(db, table));

        DbHelper dbHelper = new DbHelper(sContext, dbName, DATABASE_VERSION);
        dbHelper.onUpgrade(db, 1, DATABASE_VERSION);
        assertMeasurementTablesDoNotExist(db);
    }

    private void dropTable(SQLiteDatabase db, String table) {
        db.execSQL("DROP TABLE IF EXISTS '" + table + "'");
    }

    private void assertMeasurementSchema(SQLiteDatabase db) {
        assertTrue(doesTableExistAndColumnCountMatch(db, "msmt_source", 31));
        assertTrue(doesTableExistAndColumnCountMatch(db, "msmt_trigger", 19));
        assertTrue(doesTableExistAndColumnCountMatch(db, "msmt_async_registration_contract", 18));
        assertTrue(doesTableExistAndColumnCountMatch(db, "msmt_event_report", 17));
        assertTrue(doesTableExistAndColumnCountMatch(db, "msmt_attribution", 10));
        assertTrue(doesTableExistAndColumnCountMatch(db, "msmt_aggregate_report", 14));
        assertTrue(doesTableExistAndColumnCountMatch(db, "msmt_aggregate_encryption_key", 4));
        assertTrue(doesTableExistAndColumnCountMatch(db, "enrollment_data", 8));
        assertTrue(doesTableExistAndColumnCountMatch(db, "msmt_debug_report", 4));
        assertTrue(doesTableExistAndColumnCountMatch(db, "msmt_xna_ignored_sources", 2));
        assertTrue(doesIndexExist(db, "idx_msmt_source_ad_ei_et"));
        assertTrue(doesIndexExist(db, "idx_msmt_source_p_ad_wd_s_et"));
        assertTrue(doesIndexExist(db, "idx_msmt_trigger_ad_ei_tt"));
        assertTrue(doesIndexExist(db, "idx_msmt_source_et"));
        assertTrue(doesIndexExist(db, "idx_msmt_trigger_tt"));
        assertTrue(doesIndexExist(db, "idx_msmt_attribution_ss_so_ds_do_ei_tt"));
    }
}
