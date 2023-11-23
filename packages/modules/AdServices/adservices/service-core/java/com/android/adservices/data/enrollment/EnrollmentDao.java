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

package com.android.adservices.data.enrollment;

import android.adservices.common.AdTechIdentifier;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.SQLException;
import android.database.sqlite.SQLiteDatabase;
import android.net.Uri;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.adservices.LogUtil;
import com.android.adservices.data.shared.SharedDbHelper;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.adservices.service.measurement.util.Web;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

/** Data Access Object for the EnrollmentData. */
public class EnrollmentDao implements IEnrollmentDao {

    private static EnrollmentDao sSingleton;
    private final SharedDbHelper mDbHelper;
    private final Context mContext;
    private final Flags mFlags;
    @VisibleForTesting static final String ENROLLMENT_SHARED_PREF = "adservices_enrollment";
    @VisibleForTesting static final String IS_SEEDED = "is_seeded";

    @VisibleForTesting
    public EnrollmentDao(Context context, SharedDbHelper dbHelper, Flags flags) {
        this(context, dbHelper, flags, flags.isEnableEnrollmentTestSeed());
    }

    @VisibleForTesting
    public EnrollmentDao(
            Context context, SharedDbHelper dbHelper, Flags flags, boolean enableTestSeed) {
        // performSeed is needed to force seeding in tests that do not have DEVICE_CONFIG
        // permissions
        mContext = context;
        mDbHelper = dbHelper;
        mFlags = flags;
        if (enableTestSeed) {
            seed();
        }
    }

    /** Returns an instance of the EnrollmentDao given a context. */
    @NonNull
    public static EnrollmentDao getInstance(@NonNull Context context) {
        synchronized (EnrollmentDao.class) {
            if (sSingleton == null) {
                sSingleton =
                        new EnrollmentDao(
                                context,
                                SharedDbHelper.getInstance(context),
                                FlagsFactory.getFlags());
            }
            return sSingleton;
        }
    }

    @VisibleForTesting
    boolean isSeeded() {
        SharedPreferences prefs =
                mContext.getSharedPreferences(ENROLLMENT_SHARED_PREF, Context.MODE_PRIVATE);
        return prefs.getBoolean(IS_SEEDED, false);
    }

    @VisibleForTesting
    void seed() {
        if (!isSeeded()) {
            boolean success = true;
            for (EnrollmentData enrollment : PreEnrolledAdTechForTest.getList()) {
                success = success && insert(enrollment);
            }

            if (success) {
                SharedPreferences prefs =
                        mContext.getSharedPreferences(ENROLLMENT_SHARED_PREF, Context.MODE_PRIVATE);
                SharedPreferences.Editor edit = prefs.edit();
                edit.putBoolean(IS_SEEDED, true);
                if (!edit.commit()) {
                    // TODO(b/280579966): Add logging using CEL.
                    LogUtil.e(
                            "Saving shared preferences - %s , %s failed",
                            ENROLLMENT_SHARED_PREF, IS_SEEDED);
                }
            }
        }
    }

    private void unSeed() {
        SharedPreferences prefs =
                mContext.getSharedPreferences(ENROLLMENT_SHARED_PREF, Context.MODE_PRIVATE);
        SharedPreferences.Editor edit = prefs.edit();
        edit.putBoolean(IS_SEEDED, false);
        if (!edit.commit()) {
            // TODO(b/280579966): Add logging using CEL.
            LogUtil.e(
                    "Saving shared preferences - %s , %s failed",
                    ENROLLMENT_SHARED_PREF, IS_SEEDED);
        }
    }

    @Override
    @Nullable
    public EnrollmentData getEnrollmentData(String enrollmentId) {
        SQLiteDatabase db = mDbHelper.safeGetReadableDatabase();
        if (db == null) {
            return null;
        }
        try (Cursor cursor =
                db.query(
                        EnrollmentTables.EnrollmentDataContract.TABLE,
                        /*columns=*/ null,
                        EnrollmentTables.EnrollmentDataContract.ENROLLMENT_ID + " = ? ",
                        new String[] {enrollmentId},
                        /*groupBy=*/ null,
                        /*having=*/ null,
                        /*orderBy=*/ null,
                        /*limit=*/ null)) {
            if (cursor == null || cursor.getCount() == 0) {
                LogUtil.d("Failed to match enrollment for enrollment ID \"%s\"", enrollmentId);
                return null;
            }
            cursor.moveToNext();
            return SqliteObjectMapper.constructEnrollmentDataFromCursor(cursor);
        }
    }

    @Override
    @Nullable
    public EnrollmentData getEnrollmentDataFromMeasurementUrl(Uri url) {
        boolean originMatch = mFlags.getEnforceEnrollmentOriginMatch();
        Optional<Uri> registrationBaseUri =
                originMatch ? Web.originAndScheme(url) : Web.topPrivateDomainAndScheme(url);
        SQLiteDatabase db = mDbHelper.safeGetReadableDatabase();
        if (!registrationBaseUri.isPresent() || db == null) {
            return null;
        }

        String selectionQuery =
                getAttributionUrlSelection(
                                EnrollmentTables.EnrollmentDataContract
                                        .ATTRIBUTION_SOURCE_REGISTRATION_URL,
                                registrationBaseUri.get(),
                                /* isSiteMatch = */ !originMatch)
                        + " OR "
                        + getAttributionUrlSelection(
                                EnrollmentTables.EnrollmentDataContract
                                        .ATTRIBUTION_TRIGGER_REGISTRATION_URL,
                                registrationBaseUri.get(),
                                /* isSiteMatch = */ !originMatch);

        try (Cursor cursor =
                db.query(
                        EnrollmentTables.EnrollmentDataContract.TABLE,
                        /*columns=*/ null,
                        selectionQuery,
                        null,
                        /*groupBy=*/ null,
                        /*having=*/ null,
                        /*orderBy=*/ null,
                        /*limit=*/ null)) {
            if (cursor == null || cursor.getCount() == 0) {
                LogUtil.d("Failed to match enrollment for url \"%s\"", url);
                return null;
            }

            while (cursor.moveToNext()) {
                EnrollmentData data = SqliteObjectMapper.constructEnrollmentDataFromCursor(cursor);
                if (validateAttributionUrl(
                                data.getAttributionSourceRegistrationUrl(),
                                registrationBaseUri,
                                originMatch)
                        || validateAttributionUrl(
                                data.getAttributionTriggerRegistrationUrl(),
                                registrationBaseUri,
                                originMatch)) {
                    return data;
                }
            }
            return null;
        }
    }

    /**
     * Validates enrollment urls returned by selection query by matching its scheme + first
     * subdomain to that of registration uri.
     *
     * @param enrolledUris : urls returned by selection query
     * @param registrationBaseUri : registration base url
     * @return : true if validation is success
     */
    private boolean validateAttributionUrl(
            List<String> enrolledUris, Optional<Uri> registrationBaseUri, boolean originMatch) {
        // This match is needed to avoid matching .co in registration url to .com in enrolled url
        for (String uri : enrolledUris) {
            Optional<Uri> enrolledBaseUri =
                    originMatch
                            ? Web.originAndScheme(Uri.parse(uri))
                            : Web.topPrivateDomainAndScheme(Uri.parse(uri));
            if (registrationBaseUri.equals(enrolledBaseUri)) {
                return true;
            }
        }
        return false;
    }

    private String getAttributionUrlSelection(String field, Uri baseUri, boolean isSiteMatch) {
        String selectionQuery =
                String.format(
                        Locale.ENGLISH,
                        "(%1$s LIKE %2$s)",
                        field,
                        DatabaseUtils.sqlEscapeString("%" + baseUri.toString() + "%"));

        if (isSiteMatch) {
            // site match needs to also match https://%.host.com
            selectionQuery +=
                    String.format(
                            Locale.ENGLISH,
                            "OR (%1$s LIKE %2$s)",
                            field,
                            DatabaseUtils.sqlEscapeString(
                                    "%"
                                            + baseUri.getScheme()
                                            + "://%."
                                            + baseUri.getEncodedAuthority()
                                            + "%"));
        }
        return selectionQuery;
    }

    @Override
    @Nullable
    public EnrollmentData getEnrollmentDataForFledgeByAdTechIdentifier(
            AdTechIdentifier adTechIdentifier) {
        String adTechIdentifierString = adTechIdentifier.toString();
        SQLiteDatabase db = mDbHelper.safeGetReadableDatabase();
        if (db == null) {
            return null;
        }
        try (Cursor cursor =
                db.query(
                        EnrollmentTables.EnrollmentDataContract.TABLE,
                        /*columns=*/ null,
                        EnrollmentTables.EnrollmentDataContract
                                        .REMARKETING_RESPONSE_BASED_REGISTRATION_URL
                                + " LIKE '%"
                                + adTechIdentifierString
                                + "%'",
                        null,
                        /*groupBy=*/ null,
                        /*having=*/ null,
                        /*orderBy=*/ null,
                        /*limit=*/ null)) {
            if (cursor == null || cursor.getCount() <= 0) {
                LogUtil.d(
                        "Failed to match enrollment for ad tech identifier \"%s\"",
                        adTechIdentifierString);
                return null;
            }

            LogUtil.v(
                    "Found %d rows potentially matching ad tech identifier \"%s\"",
                    cursor.getCount(), adTechIdentifierString);

            while (cursor.moveToNext()) {
                EnrollmentData potentialMatch =
                        SqliteObjectMapper.constructEnrollmentDataFromCursor(cursor);
                for (String rbrUriString :
                        potentialMatch.getRemarketingResponseBasedRegistrationUrl()) {
                    try {
                        // Make sure the URI can be parsed and the parsed host matches the ad tech
                        if (adTechIdentifierString.equalsIgnoreCase(
                                Uri.parse(rbrUriString).getHost())) {
                            LogUtil.v(
                                    "Found positive match RBR URL \"%s\" for ad tech identifier"
                                            + " \"%s\"",
                                    rbrUriString, adTechIdentifierString);

                            return potentialMatch;
                        }
                    } catch (IllegalArgumentException exception) {
                        LogUtil.v(
                                "Error while matching ad tech %s to FLEDGE RBR URI %s; skipping"
                                        + " URI. Error message: %s",
                                adTechIdentifierString, rbrUriString, exception.getMessage());
                    }
                }
            }

            return null;
        }
    }

    @Override
    @NonNull
    public Set<AdTechIdentifier> getAllFledgeEnrolledAdTechs() {
        Set<AdTechIdentifier> enrolledAdTechIdentifiers = new HashSet<>();

        SQLiteDatabase db = mDbHelper.safeGetReadableDatabase();
        if (db == null) {
            return enrolledAdTechIdentifiers;
        }

        try (Cursor cursor =
                db.query(
                        /*distinct=*/ true,
                        /*table=*/ EnrollmentTables.EnrollmentDataContract.TABLE,
                        /*columns=*/ new String[] {
                            EnrollmentTables.EnrollmentDataContract
                                    .REMARKETING_RESPONSE_BASED_REGISTRATION_URL
                        },
                        /*selection=*/ EnrollmentTables.EnrollmentDataContract
                                        .REMARKETING_RESPONSE_BASED_REGISTRATION_URL
                                + " IS NOT NULL",
                        /*selectionArgs=*/ null,
                        /*groupBy=*/ null,
                        /*having=*/ null,
                        /*orderBy=*/ null,
                        /*limit=*/ null)) {
            if (cursor == null || cursor.getCount() <= 0) {
                LogUtil.d("Failed to find any FLEDGE-enrolled ad techs");
                return enrolledAdTechIdentifiers;
            }

            LogUtil.v("Found %d FLEDGE enrollment entries", cursor.getCount());

            while (cursor.moveToNext()) {
                enrolledAdTechIdentifiers.addAll(
                        SqliteObjectMapper.getAdTechIdentifiersFromFledgeCursor(cursor));
            }

            LogUtil.v(
                    "Found %d FLEDGE enrolled ad tech identifiers",
                    enrolledAdTechIdentifiers.size());

            return enrolledAdTechIdentifiers;
        }
    }

    @Override
    @Nullable
    public EnrollmentData getEnrollmentDataFromSdkName(String sdkName) {
        if (sdkName.contains(" ") || sdkName.contains(",")) {
            return null;
        }
        SQLiteDatabase db = mDbHelper.safeGetReadableDatabase();
        if (db == null) {
            return null;
        }
        try (Cursor cursor =
                db.query(
                        EnrollmentTables.EnrollmentDataContract.TABLE,
                        /*columns=*/ null,
                        EnrollmentTables.EnrollmentDataContract.SDK_NAMES
                                + " LIKE '%"
                                + sdkName
                                + "%'",
                        null,
                        /*groupBy=*/ null,
                        /*having=*/ null,
                        /*orderBy=*/ null,
                        /*limit=*/ null)) {
            if (cursor == null || cursor.getCount() == 0) {
                LogUtil.d("Failed to match enrollment for sdk \"%s\"", sdkName);
                return null;
            }
            cursor.moveToNext();
            return SqliteObjectMapper.constructEnrollmentDataFromCursor(cursor);
        }
    }

    @Override
    public boolean insert(EnrollmentData enrollmentData) {
        SQLiteDatabase db = mDbHelper.safeGetWritableDatabase();
        if (db == null) {
            return false;
        }

        ContentValues values = new ContentValues();
        values.put(
                EnrollmentTables.EnrollmentDataContract.ENROLLMENT_ID,
                enrollmentData.getEnrollmentId());
        values.put(
                EnrollmentTables.EnrollmentDataContract.COMPANY_ID, enrollmentData.getCompanyId());
        values.put(
                EnrollmentTables.EnrollmentDataContract.SDK_NAMES,
                String.join(" ", enrollmentData.getSdkNames()));
        values.put(
                EnrollmentTables.EnrollmentDataContract.ATTRIBUTION_SOURCE_REGISTRATION_URL,
                String.join(" ", enrollmentData.getAttributionSourceRegistrationUrl()));
        values.put(
                EnrollmentTables.EnrollmentDataContract.ATTRIBUTION_TRIGGER_REGISTRATION_URL,
                String.join(" ", enrollmentData.getAttributionTriggerRegistrationUrl()));
        values.put(
                EnrollmentTables.EnrollmentDataContract.ATTRIBUTION_REPORTING_URL,
                String.join(" ", enrollmentData.getAttributionReportingUrl()));
        values.put(
                EnrollmentTables.EnrollmentDataContract.REMARKETING_RESPONSE_BASED_REGISTRATION_URL,
                String.join(" ", enrollmentData.getRemarketingResponseBasedRegistrationUrl()));
        values.put(
                EnrollmentTables.EnrollmentDataContract.ENCRYPTION_KEY_URL,
                String.join(" ", enrollmentData.getEncryptionKeyUrl()));
        try {
            db.insertWithOnConflict(
                    EnrollmentTables.EnrollmentDataContract.TABLE,
                    /*nullColumnHack=*/ null,
                    values,
                    SQLiteDatabase.CONFLICT_REPLACE);
        } catch (SQLException e) {
            LogUtil.e("Failed to insert EnrollmentData. Exception : " + e.getMessage());
            return false;
        }
        return true;
    }

    @Override
    public boolean delete(String enrollmentId) {
        Objects.requireNonNull(enrollmentId);
        SQLiteDatabase db = mDbHelper.safeGetWritableDatabase();
        if (db == null) {
            return false;
        }
        try {
            db.delete(
                    EnrollmentTables.EnrollmentDataContract.TABLE,
                    EnrollmentTables.EnrollmentDataContract.ENROLLMENT_ID + " = ?",
                    new String[] {enrollmentId});
        } catch (SQLException e) {
            LogUtil.e("Failed to delete EnrollmentData." + e.getMessage());
            return false;
        }
        return true;
    }

    /** Deletes the whole EnrollmentData table. */
    @Override
    public boolean deleteAll() {
        SQLiteDatabase db = mDbHelper.safeGetWritableDatabase();
        if (db == null) {
            return false;
        }

        boolean success = false;
        // Handle this in a transaction.
        db.beginTransaction();
        try {
            db.delete(EnrollmentTables.EnrollmentDataContract.TABLE, null, null);
            success = true;
            unSeed();
            // Mark the transaction successful.
            db.setTransactionSuccessful();
        } finally {
            db.endTransaction();
        }
        return success;
    }
}
