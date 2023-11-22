/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.telephonyprovider.cts;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static com.google.common.truth.Truth.assertThat;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.database.Cursor;
import android.net.Uri;
import android.provider.Telephony;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import javax.annotation.Nullable;

public class MmsPartTest {
    private ContentResolver mContentResolver;

    /**
     * Parts must be inserted in relation to a message, this message ID is used for inserting a
     * part when the message ID is not important in relation to the current test.
     */
    private static final String TEST_MESSAGE_ID = "100";

    @BeforeClass
    public static void ensureDefaultSmsApp() {
        DefaultSmsAppHelper.ensureDefaultSmsApp();
    }

    @Before
    public void setupTestEnvironment() {
        cleanup();
        mContentResolver = getInstrumentation().getContext().getContentResolver();
    }

    @AfterClass
    public static void cleanup() {
        ContentResolver contentResolver = getInstrumentation().getContext().getContentResolver();
        contentResolver.delete(Telephony.Mms.Part.CONTENT_URI, null, null);
    }

    @Test
    public void testMmsPartInsert_cannotInsertPartWithDataColumn() {
        ContentValues values = new ContentValues();
        values.put(Telephony.Mms.Part._DATA, "/dev/urandom");
        values.put(Telephony.Mms.Part.NAME, "testMmsPartInsert_cannotInsertPartWithDataColumn");

        Uri uri = insertTestMmsPartWithValues(values);
        assertThat(uri).isNull();
    }

    @Test
    public void testMmsPartInsert_canInsertPartWithoutDataColumn() {
        String name = "testMmsInsert_canInsertPartWithoutDataColumn";

        Uri uri = insertTestMmsPartWithName(name);
        assertThatMmsPartInsertSucceeded(uri, name);
    }

    @Test
    public void testMmsPart_deletedPartIdsAreNotReused() {
        long id1 = insertAndVerifyMmsPartReturningId("testMmsPart_deletedPartIdsAreNotReused_1");

        deletePartById(id1);

        long id2 = insertAndVerifyMmsPartReturningId("testMmsPart_deletedPartIdsAreNotReused_2");

        assertThat(id2).isGreaterThan(id1);
    }

    private long insertAndVerifyMmsPartReturningId(String name) {
        Uri uri = insertTestMmsPartWithName(name);
        assertThatMmsPartInsertSucceeded(uri, name);
        return Long.parseLong(uri.getLastPathSegment());
    }

    private void deletePartById(long partId) {
        Uri uri = Uri.withAppendedPath(Telephony.Mms.Part.CONTENT_URI, Long.toString(partId));
        int deletedRows = mContentResolver.delete(uri, null, null);
        assertThat(deletedRows).isEqualTo(1);
    }

    private Uri insertTestMmsPartWithName(String name) {
        ContentValues values = new ContentValues();
        values.put(Telephony.Mms.Part.NAME, name);
        return insertTestMmsPartWithValues(values);
    }

    private Uri insertTestMmsPartWithValues(ContentValues values) {
        Uri insertUri = Telephony.Mms.CONTENT_URI.buildUpon()
                .appendPath(TEST_MESSAGE_ID)
                .appendPath("part")
                .build();

        Uri uri = mContentResolver.insert(insertUri, values);
        return uri;
    }

    private void assertThatMmsPartInsertSucceeded(@Nullable Uri uriReturnedFromInsert,
            String nameOfAttemptedInsert) {
        assertThat(uriReturnedFromInsert).isNotNull();

        Cursor cursor = mContentResolver.query(uriReturnedFromInsert, null, null, null);

        assertThat(cursor.getCount()).isEqualTo(1);

        cursor.moveToNext();
        String actualName = cursor.getString(cursor.getColumnIndex(Telephony.Mms.Part.NAME));
        assertThat(actualName).isEqualTo(nameOfAttemptedInsert);
    }

}
