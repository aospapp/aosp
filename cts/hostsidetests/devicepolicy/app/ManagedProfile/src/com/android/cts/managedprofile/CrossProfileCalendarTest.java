/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.cts.managedprofile;

import static com.android.cts.managedprofile.BaseManagedProfileTest.ADMIN_RECEIVER_COMPONENT;
import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.accounts.Account;
import android.accounts.AccountManager;
import android.app.admin.DevicePolicyManager;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.provider.CalendarContract;
import android.provider.Settings.Secure;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.test.AndroidTestCase;
import android.text.TextUtils;
import android.text.format.DateUtils;
import android.text.format.Time;
import android.util.ArraySet;

import androidx.test.InstrumentationRegistry;

import java.util.Arrays;
import java.util.Collections;
import java.util.Set;

/**
 * This class contains tests for cross profile calendar related features. Most of the tests in
 * this class will need different setups, so the tests will be run separately.
 */
public class CrossProfileCalendarTest extends AndroidTestCase {

    private static final String MANAGED_PROFILE_PKG = "com.android.cts.managedprofile";

    private static final String TEST_ACCOUNT_NAME = AccountAuthenticator.ACCOUNT_NAME;
    private static final String TEST_ACCOUNT_TYPE = AccountAuthenticator.ACCOUNT_TYPE;

    private static String WORK_CALENDAR_TITLE = "Calendar1";
    private static int WORK_CALENDAR_COLOR = 0xFFFF0000;
    // Make sure sync_event=1 for the test calendar so that instances table got updated.
    private static int WORK_SYNC_EVENT = 1;
    private static String WORK_TIMEZONE = "America/Los_Angeles";

    private static String WORK_EVENT_TITLE = "event_title1";
    private static String WORK_EVENT_TITLE_2= "event_title2";
    private static final String WORK_EVENT_DTSTART_STRING = "2018-05-01T00:00:00";
    private static final String WORK_EVENT_DTEND_STRING = "2018-05-01T20:00:00";
    private static final String WORK_EVENT_DTSTART_STRING_2 = "2013-05-01T00:00:00";
    private static final String WORK_EVENT_DTEND_STRING_2 = "2013-05-01T20:00:00";
    private static long WORK_EVENT_DTSTART = parseTimeStringToMillis(
            WORK_EVENT_DTSTART_STRING, WORK_TIMEZONE);
    private static long WORK_EVENT_DTEND = parseTimeStringToMillis(
            WORK_EVENT_DTEND_STRING, WORK_TIMEZONE);
    private final long WORK_EVENT_DTSTART_2 = parseTimeStringToMillis(
            WORK_EVENT_DTSTART_STRING_2, WORK_TIMEZONE);
    private final long WORK_EVENT_DTEND_2 = parseTimeStringToMillis(
            WORK_EVENT_DTEND_STRING_2, WORK_TIMEZONE);
    private static long WORK_EVENT_DTSTART_JULIAN_DAY = parseTimeStringToJulianDay(
            WORK_EVENT_DTSTART_STRING, WORK_TIMEZONE);
    private static long WORK_EVENT_DTEND_JULIAN_DAY = parseTimeStringToJulianDay(
            WORK_EVENT_DTEND_STRING, WORK_TIMEZONE);
    private final long WORK_EVENT_DTSTART_2_JULIAN_DAY = parseTimeStringToJulianDay(
            WORK_EVENT_DTSTART_STRING_2, WORK_TIMEZONE);
    private final long WORK_EVENT_DTEND_2_JULIAN_DAY = parseTimeStringToJulianDay(
            WORK_EVENT_DTEND_STRING_2, WORK_TIMEZONE);

    private static int WORK_EVENT_COLOR = 0xff123456;
    private static String WORK_EVENT_LOCATION = "Work event location.";
    private static String WORK_EVENT_DESCRIPTION = "This is a work event.";

    private static final String CROSS_PROFILE_CALENDAR_ENABLED =
            "cross_profile_calendar_enabled";

    private static final String SELECTION_ACCOUNT_TYPE = "(" +
            CalendarContract.Calendars.ACCOUNT_TYPE + " = ? )";

    private static final long TEST_VIEW_EVENT_ID = 1;
    private static final long TEST_VIEW_EVENT_START = 100;
    private static final long TEST_VIEW_EVENT_END = 10000;
    private static final boolean TEST_VIEW_EVENT_ALL_DAY = false;
    private static final int TEST_VIEW_EVENT_FLAG = Intent.FLAG_ACTIVITY_NEW_TASK;

    private ContentResolver mResolver;
    private DevicePolicyManager mDevicePolicyManager;

    private static long parseTimeStringToMillis(String timeStr, String timeZone) {
        Time time = new Time(timeZone);
        time.parse3339(timeStr);
        return time.toMillis(/* ignoreDst= */false );
    }

    private static int parseTimeStringToJulianDay(String timeStr, String timeZone) {
        Time time = new Time(timeZone);
        time.parse3339(timeStr);
        return Time.getJulianDay(time.toMillis(/* ignoreDst= */false), time.gmtoff);
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mResolver = mContext.getContentResolver();
        mDevicePolicyManager = mContext.getSystemService(DevicePolicyManager.class);
    }

    public void testCrossProfileCalendarPackage() {
        requireRunningOnManagedProfile();

        Set<String> whitelist = mDevicePolicyManager.getCrossProfileCalendarPackages(
                ADMIN_RECEIVER_COMPONENT);
        assertThat(whitelist).isEmpty();

        mDevicePolicyManager.setCrossProfileCalendarPackages(
                ADMIN_RECEIVER_COMPONENT, new ArraySet<String>(Arrays.asList(MANAGED_PROFILE_PKG)));
        whitelist = mDevicePolicyManager.getCrossProfileCalendarPackages(
                ADMIN_RECEIVER_COMPONENT);
        assertThat(whitelist.size()).isEqualTo(1);
        assertThat(whitelist.contains(MANAGED_PROFILE_PKG)).isTrue();

        mDevicePolicyManager.setCrossProfileCalendarPackages(
                ADMIN_RECEIVER_COMPONENT, Collections.emptySet());
        whitelist = mDevicePolicyManager.getCrossProfileCalendarPackages(
                ADMIN_RECEIVER_COMPONENT);
        assertThat(whitelist).isEmpty();
    }

    // This test should be run when the test package is not whitelised or cross-profile calendar
    // is disabled in settings.
    public void testPrimaryProfile_cannotAccessWorkCalendarsWhenDisabled() {
        requireRunningOnPrimaryProfile();

        assertThrows(UnsupportedOperationException.class, () -> mResolver.query(
                CalendarContract.Calendars.ENTERPRISE_CONTENT_URI,
                null, null, null, null));
    }

    // This test should be run when the test package is not whitelised or cross-profile calendar
    // is disabled in settings.
    public void testPrimaryProfile_cannotAccessWorkEventsWhenDisabled() {
        requireRunningOnPrimaryProfile();

        assertThrows(UnsupportedOperationException.class, () -> mResolver.query(
                CalendarContract.Events.ENTERPRISE_CONTENT_URI,
                null, SELECTION_ACCOUNT_TYPE, new String[]{TEST_ACCOUNT_TYPE}, null));
    }

    // This test should be run when the test package is not whitelised or cross-profile calendar
    // is disabled in settings.
    public void testPrimaryProfile_cannotAccessWorkInstancesWhenDisabled() {
        requireRunningOnPrimaryProfile();

        assertThrows(UnsupportedOperationException.class, () -> mResolver.query(
                buildQueryInstancesUri(CalendarContract.Instances.ENTERPRISE_CONTENT_URI,
                        WORK_EVENT_DTSTART - DateUtils.YEAR_IN_MILLIS,
                        WORK_EVENT_DTEND + DateUtils.YEAR_IN_MILLIS, null),
                null, null, null, null));
    }

    // This test should be run when the test package is whitelisted and cross-profile calendar
    // is enabled in settings.
    public void testPrimaryProfile_getCorrectWorkCalendarsWhenEnabled() {
        requireRunningOnPrimaryProfile();

        // Test the return cursor is correct when the all checks are met.
        final String[] projection = new String[] {
                CalendarContract.Calendars.IS_PRIMARY,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.CALENDAR_TIME_ZONE
        };
        final Cursor cursor = mResolver.query(
                CalendarContract.Calendars.ENTERPRISE_CONTENT_URI,
                projection, SELECTION_ACCOUNT_TYPE, new String[]{TEST_ACCOUNT_TYPE}, null);

        assertThat(cursor).isNotNull();
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToFirst();
        assertThat(cursor.getInt(0)).isEqualTo(1);
        assertThat(cursor.getInt(1)).isEqualTo(WORK_CALENDAR_COLOR);
        assertThat(cursor.getString(2)).isEqualTo(WORK_TIMEZONE);
    }

    // This test should be run when the test package is whitelisted and cross-profile calendar
    // is enabled in settings.
    public void testPrimaryProfile_getCorrectWorkEventsWhenEnabled() {
        requireRunningOnPrimaryProfile();

        // Test the return cursor is correct when the all checks are met.
        final String selection = "(" + CalendarContract.Calendars.ACCOUNT_TYPE  + "=? AND "
                + CalendarContract.Events.TITLE  + " =? )";
        final String[] selectionArgs = new String[] {
                TEST_ACCOUNT_TYPE,
                WORK_EVENT_TITLE
        };
        final String[] projection = new String[] {
                CalendarContract.Events.TITLE,
                CalendarContract.Events.EVENT_LOCATION,
                CalendarContract.Events.DTSTART,
                CalendarContract.Calendars.IS_PRIMARY
        };
        final Cursor cursor = mResolver.query(
                CalendarContract.Events.ENTERPRISE_CONTENT_URI,
                projection, selection, selectionArgs, null);

        assertThat(cursor).isNotNull();
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToFirst();
        assertThat(cursor.getString(0)).isEqualTo(WORK_EVENT_TITLE);
        assertThat(cursor.getString(1)).isEqualTo(WORK_EVENT_LOCATION);
        assertThat(cursor.getLong(2)).isEqualTo(WORK_EVENT_DTSTART);
        assertThat(cursor.getInt(3)).isEqualTo(1);
    }

    // This test should be run when the test package is whitelisted and cross-profile calendar
    // is enabled in settings.
    public void testPrimaryProfile_getCorrectWorkInstancesWhenEnabled() {
        requireRunningOnPrimaryProfile();

        // Test the return cursor is correct when the all checks are met.
        final String[] projection = new String[]{
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DTSTART,
                CalendarContract.Instances.IS_PRIMARY,
        };
        final Cursor cursor = mResolver.query(
                buildQueryInstancesUri(CalendarContract.Instances.ENTERPRISE_CONTENT_URI,
                        WORK_EVENT_DTSTART - DateUtils.YEAR_IN_MILLIS,
                        WORK_EVENT_DTEND + DateUtils.YEAR_IN_MILLIS, null),
                projection, null, null, null);

        assertThat(cursor).isNotNull();
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToFirst();
        assertThat(cursor.getString(0)).isEqualTo(WORK_EVENT_TITLE);
        assertThat(cursor.getLong(1)).isEqualTo(WORK_EVENT_DTSTART);
        assertThat(cursor.getInt(2)).isEqualTo(1);
    }

    // This test should be run when the test package is whitelisted and cross-profile calendar
    // is enabled in settings.
    public void testPrimaryProfile_getCorrectWorkInstancesByDayWhenEnabled() {
        requireRunningOnPrimaryProfile();

        // Test the return cursor is correct when the all checks are met.
        final String[] projection = new String[]{
                CalendarContract.Instances.TITLE,
                CalendarContract.Instances.DTSTART,
                CalendarContract.Instances.IS_PRIMARY,
        };
        final Cursor cursor = mResolver.query(
                buildQueryInstancesUri(CalendarContract.Instances.ENTERPRISE_CONTENT_BY_DAY_URI,
                        WORK_EVENT_DTSTART_JULIAN_DAY - 1,
                        WORK_EVENT_DTEND_JULIAN_DAY + 1, null),
                projection, null, null, null);

        assertThat(cursor).isNotNull();
        assertThat(cursor.getCount()).isEqualTo(1);
        cursor.moveToFirst();
        assertThat(cursor.getString(0)).isEqualTo(WORK_EVENT_TITLE);
        assertThat(cursor.getLong(1)).isEqualTo(WORK_EVENT_DTSTART);
        assertThat(cursor.getInt(2)).isEqualTo(1);
    }

    // This test should be run when the test package is whitelisted and cross-profile calendar
    // is enabled in settings.
    public void testPrimaryProfile_canAccessWorkInstancesSearch1() {
        requireRunningOnPrimaryProfile();

        // Test the return cursor is correct when the all checks are met.
        final Cursor cursor = mResolver.query(
                buildQueryInstancesUri(CalendarContract.Instances.ENTERPRISE_CONTENT_SEARCH_URI,
                        WORK_EVENT_DTSTART - DateUtils.YEAR_IN_MILLIS,
                        WORK_EVENT_DTEND + DateUtils.YEAR_IN_MILLIS, WORK_EVENT_TITLE),
                null, null, null, null);
        // There is only one event that meets the search criteria.
        assertThat(cursor).isNotNull();
        assertThat(cursor.getCount()).isEqualTo(1);
    }

    // This test should be run when the test package is whitelisted and cross-profile calendar
    // is enabled in settings.
    public void testPrimaryProfile_canAccessWorkInstancesSearch2() {
        requireRunningOnPrimaryProfile();

        // Test the return cursor is correct when the all checks are met.
        final Cursor cursor = mResolver.query(
                buildQueryInstancesUri(CalendarContract.Instances.ENTERPRISE_CONTENT_SEARCH_URI,
                        WORK_EVENT_DTSTART_2 - DateUtils.YEAR_IN_MILLIS,
                        WORK_EVENT_DTEND + DateUtils.YEAR_IN_MILLIS, WORK_EVENT_DESCRIPTION),
                null, null, null, null);
        // There are two events that meet the search criteria.
        assertThat(cursor).isNotNull();
        assertThat(cursor.getCount()).isEqualTo(2);
    }

    // This test should be run when the test package is whitelisted and cross-profile calendar
    // is enabled in settings.
    public void testPrimaryProfile_canAccessWorkInstancesSearchByDay() {
        requireRunningOnPrimaryProfile();

        // Test the return cursor is correct when the all checks are met.
        final Cursor cursor = mResolver.query(
                buildQueryInstancesUri(
                        CalendarContract.Instances.ENTERPRISE_CONTENT_SEARCH_BY_DAY_URI,
                        WORK_EVENT_DTSTART_2_JULIAN_DAY - 1,
                        WORK_EVENT_DTEND_2_JULIAN_DAY + 1,
                        WORK_EVENT_DESCRIPTION),
                null, null, null, null);
        // There are two events that meet the search criteria.
        assertThat(cursor).isNotNull();
        assertThat(cursor.getCount()).isEqualTo(1);
    }

    // This test should be run when the test package is whitelisted and cross-profile calendar
    // is enabled in settings.
    public void testPrimaryProfile_getExceptionWhenQueryNonWhitelistedColumns() {
        requireRunningOnPrimaryProfile();

        // Test the return cursor is correct when the all checks are met.
        final String[] projection = new String[] {
                CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                CalendarContract.Calendars.CALENDAR_COLOR,
                CalendarContract.Calendars.OWNER_ACCOUNT
        };
        assertThrows(IllegalArgumentException.class, () -> mResolver.query(
                CalendarContract.Calendars.ENTERPRISE_CONTENT_URI,
                projection, SELECTION_ACCOUNT_TYPE, new String[]{TEST_ACCOUNT_TYPE}, null));
    }

    // This test should be run when the test package is not whitelisted.
    public void testViewEventCrossProfile_intentFailedWhenNotWhitelisted() throws Exception {
        requireRunningOnPrimaryProfile();

        assertThat(CalendarContract.startViewCalendarEventInManagedProfile(mContext,
                TEST_VIEW_EVENT_ID, TEST_VIEW_EVENT_START, TEST_VIEW_EVENT_END,
                TEST_VIEW_EVENT_ALL_DAY, TEST_VIEW_EVENT_FLAG)).isFalse();
    }

    // Utils method, not a actual test. Ran from ManagedProfileTest.java to set up for actual tests.
    public void testWhitelistManagedProfilePackage() {
        requireRunningOnManagedProfile();
        mDevicePolicyManager.setCrossProfileCalendarPackages(
                ADMIN_RECEIVER_COMPONENT, new ArraySet<String>(Arrays.asList(MANAGED_PROFILE_PKG)));
    }

    // Utils method, not a actual test. Ran from ManagedProfileTest.java to set up for actual tests.
    public void testWhitelistAllPackages() {
        requireRunningOnManagedProfile();
        mDevicePolicyManager.setCrossProfileCalendarPackages(
                ADMIN_RECEIVER_COMPONENT, null);
    }

    // Utils method, not a actual test. Ran from ManagedProfileTest.java to set up for actual tests.
    public void testCleanupWhitelist() {
        requireRunningOnManagedProfile();
        mDevicePolicyManager.setCrossProfileCalendarPackages(
                ADMIN_RECEIVER_COMPONENT, Collections.emptySet());
    }

    // Utils method, not a actual test. Ran from ManagedProfileTest.java to set up for actual tests.
    public void testAddTestCalendarDataForWorkProfile() throws Exception {
        requireRunningOnManagedProfile();
        addTestAccount();
        final long calendarId = insertWorkCalendar(WORK_CALENDAR_TITLE);
        insertWorkEvent(WORK_EVENT_TITLE, calendarId, WORK_EVENT_DTSTART, WORK_EVENT_DTEND);
        insertWorkEvent(WORK_EVENT_TITLE_2, calendarId, WORK_EVENT_DTSTART_2, WORK_EVENT_DTEND_2);
    }

    // Utils method, not a actual test. Ran from ManagedProfileTest.java to set up for actual tests.
    public void testCleanupTestCalendarDataForWorkProfile() {
        requireRunningOnManagedProfile();
        int numDeleted = mResolver.delete(CalendarContract.Events.CONTENT_URI,
                "(" + CalendarContract.Calendars.ACCOUNT_TYPE + " = ? )",
                new String[]{TEST_ACCOUNT_TYPE});
        assertThat(numDeleted).isEqualTo(2);
        numDeleted = mResolver.delete(CalendarContract.Calendars.CONTENT_URI,
                "(" + CalendarContract.Calendars.ACCOUNT_TYPE + " = ? )",
                new String[]{TEST_ACCOUNT_TYPE});
        assertThat(numDeleted).isEqualTo(1);
        removeTestAccount();
    }

    // Utils method, not a actual test. Ran from ManagedProfileTest.java to set up for actual tests.
    public void testEnableCrossProfileCalendarSettings() {
        requireRunningOnManagedProfile();
        Secure.putInt(mResolver, CROSS_PROFILE_CALENDAR_ENABLED,
                /* value= */1);
    }

    // Utils method, not a actual test. Ran from ManagedProfileTest.java to set up for actual tests.
    public void testDisableCrossProfileCalendarSettings() {
        requireRunningOnManagedProfile();
        Secure.putInt(mResolver, CROSS_PROFILE_CALENDAR_ENABLED,
                /* value= */0);
    }

    // Builds an uri for querying Instances table.
    private Uri buildQueryInstancesUri(Uri uri, long start, long end, String query) {
        Uri.Builder builder = uri.buildUpon();
        ContentUris.appendId(builder, start);
        ContentUris.appendId(builder, end);
        if (!TextUtils.isEmpty(query)) {
            builder = builder.appendPath(query);
        }
        return builder.build();
    }

    // This method should align with
    // DummyCrossProfileViewEventActivity#getViewEventCrossProfileString.
    private String getViewEventCrossProfileString(long eventId, long start, long end,
            boolean allDay, int flags) {
        return String.format("id:%d, start:%d, end:%d, allday:%b, flag:%d", eventId,
                start, end, allDay, flags);
    }

    // This method is to guard that particular tests are supposed to run on managed profile.
    private void requireRunningOnManagedProfile() {
        assertThat(isManagedProfile()).isTrue();
    }

    // This method is to guard that particular tests are supposed to run on primary profile.
    private void requireRunningOnPrimaryProfile() {
        assertThat(isManagedProfile()).isFalse();
    }

    private long insertWorkCalendar(String displayName) {
        final ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Calendars.ACCOUNT_TYPE, TEST_ACCOUNT_TYPE);
        cv.put(CalendarContract.Calendars.OWNER_ACCOUNT, TEST_ACCOUNT_NAME);
        cv.put(CalendarContract.Calendars.ACCOUNT_NAME, TEST_ACCOUNT_NAME);
        cv.put(CalendarContract.Calendars.CALENDAR_DISPLAY_NAME, displayName);
        cv.put(CalendarContract.Calendars.CALENDAR_COLOR, WORK_CALENDAR_COLOR);
        cv.put(CalendarContract.Calendars.CALENDAR_TIME_ZONE, WORK_TIMEZONE);
        cv.put(CalendarContract.Calendars.SYNC_EVENTS, WORK_SYNC_EVENT);
        final Uri uri = mResolver.insert(
                addSyncQueryParams(CalendarContract.Calendars.CONTENT_URI, TEST_ACCOUNT_NAME,
                        TEST_ACCOUNT_TYPE), cv);
        return Long.parseLong(uri.getLastPathSegment());
    }

    private void insertWorkEvent(String eventTitle, long calendarId, long dtStart, long dtEnd) {
        final ContentValues cv = new ContentValues();
        cv.put(CalendarContract.Events.TITLE, eventTitle);
        cv.put(CalendarContract.Events.CALENDAR_ID, calendarId);
        cv.put(CalendarContract.Events.DESCRIPTION, WORK_EVENT_DESCRIPTION);
        cv.put(CalendarContract.Events.EVENT_LOCATION, WORK_EVENT_LOCATION);
        cv.put(CalendarContract.Events.EVENT_COLOR, WORK_EVENT_COLOR);
        cv.put(CalendarContract.Events.DTSTART, dtStart);
        cv.put(CalendarContract.Events.DTEND, dtEnd);
        cv.put(CalendarContract.Events.EVENT_TIMEZONE, WORK_TIMEZONE);
        mResolver.insert(CalendarContract.Events.CONTENT_URI, cv);
    }

    /**
     * Constructs a URI from a base URI (e.g. "content://com.android.calendar/calendars"),
     * an account name, and an account type.
     */
    private Uri addSyncQueryParams(Uri uri, String account, String accountType) {
        return uri.buildUpon().appendQueryParameter(CalendarContract.CALLER_IS_SYNCADAPTER, "true")
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_NAME, account)
                .appendQueryParameter(CalendarContract.Calendars.ACCOUNT_TYPE, accountType).build();
    }

    private void addTestAccount() {
        Account account = new Account(TEST_ACCOUNT_NAME, TEST_ACCOUNT_TYPE);
        AccountManager.get(mContext).addAccountExplicitly(account, null, null);
    }

    private void removeTestAccount() {
        Account account = new Account(TEST_ACCOUNT_NAME, TEST_ACCOUNT_TYPE);
        AccountManager.get(mContext).removeAccountExplicitly(account);
    }

    private boolean isManagedProfile() {
        String adminPackage = ADMIN_RECEIVER_COMPONENT.getPackageName();
        return mDevicePolicyManager.isProfileOwnerApp(adminPackage);
    }
}
