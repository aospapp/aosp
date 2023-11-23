/*
 * Copyright (C) 2015 The Android Open Source Project
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

package com.android.cts.usepermission;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import android.Manifest;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Process;
import android.provider.CalendarContract;

import org.junit.Test;

import java.util.Arrays;

/**
 * Runtime permission behavior tests for apps targeting API 22
 */
public class UsePermissionTest22 extends BasePermissionsTest {
    private static final int REQUEST_CODE_PERMISSIONS = 42;

    private final Context mContext = getInstrumentation().getContext();

    @Test
    public void testCompatDefault() throws Exception {
        // Legacy permission model appears granted
        assertEquals(PackageManager.PERMISSION_GRANTED,
                mContext.checkPermission(android.Manifest.permission.READ_CALENDAR,
                        Process.myPid(), Process.myUid()));
        assertEquals(PackageManager.PERMISSION_GRANTED,
                mContext.checkPermission(android.Manifest.permission.WRITE_CALENDAR,
                        Process.myPid(), Process.myUid()));

        // Read/write access should be allowed
        final Uri uri = insertCalendarItem();
        try (Cursor c = mContext.getContentResolver().query(uri, null, null, null)) {
            assertEquals(1, c.getCount());
        }
    }

    @Test
    public void testCompatRevoked_part1() throws Exception {
        // Revoke the permission
        revokePermissions(new String[] {Manifest.permission.WRITE_CALENDAR}, true);
    }

    @Test
    public void testCompatRevoked_part2() throws Exception {
        // Legacy permission model appears granted
        assertEquals(PackageManager.PERMISSION_GRANTED,
                mContext.checkPermission(android.Manifest.permission.READ_CALENDAR,
                        Process.myPid(), Process.myUid()));
        assertEquals(PackageManager.PERMISSION_GRANTED,
                mContext.checkPermission(android.Manifest.permission.WRITE_CALENDAR,
                        Process.myPid(), Process.myUid()));

        // Read/write access should be ignored
        final Uri uri = insertCalendarItem();
        try (Cursor c = mContext.getContentResolver().query(uri, null, null, null)) {
            assertEquals(0, c.getCount());
        }
    }

    @Test
    public void testAllPermissionsGrantedByDefault() throws Exception {
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.SEND_SMS));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.RECEIVE_SMS));
        // The APK does not request because of other tests Manifest.permission.READ_CONTACTS
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.WRITE_CONTACTS));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.READ_CALENDAR));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.WRITE_CALENDAR));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.READ_SMS));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.RECEIVE_WAP_PUSH));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.RECEIVE_MMS));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission("android.permission.READ_CELL_BROADCASTS"));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.READ_PHONE_STATE));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.CALL_PHONE));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.READ_CALL_LOG));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.WRITE_CALL_LOG));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.ADD_VOICEMAIL));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.USE_SIP));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.PROCESS_OUTGOING_CALLS));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.CAMERA));
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.BODY_SENSORS));

        // Split permissions
        assertEquals(PackageManager.PERMISSION_GRANTED, mContext
                .checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION));
    }

    @Test
    public void testNoRuntimePrompt() throws Exception {
        // Request the permission and do nothing
        BasePermissionActivity.Result result = requestPermissions(
                new String[] {Manifest.permission.SEND_SMS}, REQUEST_CODE_PERMISSIONS,
                BasePermissionActivity.class, null);

        // Expect the permission is not granted
        assertEquals(REQUEST_CODE_PERMISSIONS, result.requestCode);
        assertTrue(Arrays.equals(result.permissions, new String[0]));
        assertTrue(Arrays.equals(result.grantResults, new int[0]));
    }

    @Test
    public void testRevokePropagatedOnUpgradeOldToNewModel_part1() throws Exception {
        // Revoke a permission
        revokePermissions(new String[] {Manifest.permission.WRITE_CALENDAR}, true);
    }

    @Test
    public void testAssertNoCalendarAccess() throws Exception {
        // Without access we're handed back a "fake" Uri that doesn't contain
        // any of the data we tried persisting
        final Uri uri = insertCalendarItem();
        try (Cursor c = mContext.getContentResolver().query(uri, null, null, null)) {
            assertEquals(0, c.getCount());
        }
    }

    @Test
    public void testAssertCalendarAccess() {
        final Uri uri = insertCalendarItem();
        try (Cursor c = mContext.getContentResolver().query(uri, null, null, null)) {
            assertEquals(1, c.getCount());
        }
    }

    /**
     * Attempt to insert a new unique calendar item; this might be ignored if
     * this legacy app has its permission revoked.
     */
    private Uri insertCalendarItem() {
        final ContentValues values = new ContentValues();
        values.put(CalendarContract.Calendars.NAME, "cts" + System.nanoTime());
        values.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, "cts");
        values.put(CalendarContract.Calendars.CALENDAR_COLOR, 0xffff0000);
        return mContext.getContentResolver().insert(CalendarContract.Calendars.CONTENT_URI, values);
    }
}
