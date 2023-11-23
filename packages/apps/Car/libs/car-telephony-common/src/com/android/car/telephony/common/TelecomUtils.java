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

package com.android.car.telephony.common;

import android.Manifest;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Icon;
import android.location.Country;
import android.location.CountryDetector;
import android.net.Uri;
import android.provider.CallLog;
import android.provider.ContactsContract;
import android.provider.ContactsContract.CommonDataKinds.Phone;
import android.provider.ContactsContract.PhoneLookup;
import android.provider.Settings;
import android.telecom.Call;
import android.telephony.PhoneNumberUtils;
import android.telephony.TelephonyManager;
import android.text.BidiFormatter;
import android.text.TextDirectionHeuristics;
import android.text.TextUtils;
import android.widget.ImageView;

import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.drawable.RoundedBitmapDrawable;
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory;

import com.android.car.apps.common.LetterTileDrawable;
import com.android.car.apps.common.log.L;

import com.bumptech.glide.Glide;
import com.bumptech.glide.request.RequestOptions;
import com.google.i18n.phonenumbers.NumberParseException;
import com.google.i18n.phonenumbers.PhoneNumberUtil;
import com.google.i18n.phonenumbers.Phonenumber;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletableFuture;

/**
 * Helper methods.
 */
public class TelecomUtils {
    private static final String TAG = "CD.TelecomUtils";
    private static final int PII_STRING_LENGTH = 4;
    private static final String COUNTRY_US = "US";

    private static String sVoicemailNumber;
    private static TelephonyManager sTelephonyManager;

    /**
     * Get the voicemail number.
     */
    public static String getVoicemailNumber(Context context) {
        if (sVoicemailNumber == null) {
            sVoicemailNumber = getTelephonyManager(context).getVoiceMailNumber();
        }
        return sVoicemailNumber;
    }

    /**
     * Returns {@code true} if the given number is a voice mail number.
     *
     * @see TelephonyManager#getVoiceMailNumber()
     */
    public static boolean isVoicemailNumber(Context context, String number) {
        if (TextUtils.isEmpty(number)) {
            return false;
        }

        if (ContextCompat.checkSelfPermission(context, Manifest.permission.READ_PHONE_STATE)
                != PackageManager.PERMISSION_GRANTED) {
            return false;
        }

        return number.equals(getVoicemailNumber(context));
    }

    /**
     * Get the {@link TelephonyManager} instance.
     */
    // TODO(deanh): remove this, getSystemService is not slow.
    public static TelephonyManager getTelephonyManager(Context context) {
        if (sTelephonyManager == null) {
            sTelephonyManager =
                    (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        }
        return sTelephonyManager;
    }

    /**
     * Format a number as a phone number.
     */
    public static String getFormattedNumber(Context context, String number) {
        L.d(TAG, "getFormattedNumber: " + piiLog(number));
        if (number == null) {
            return "";
        }

        String countryIso = getCurrentCountryIsoFromLocale(context);
        L.d(TAG, "PhoneNumberUtils.formatNumberToE16, number: "
                    + piiLog(number) + ", country: " + countryIso);

        String e164 = PhoneNumberUtils.formatNumberToE164(number, countryIso);
        String formattedNumber = PhoneNumberUtils.formatNumber(number, e164, countryIso);
        formattedNumber = TextUtils.isEmpty(formattedNumber) ? number : formattedNumber;
        L.d(TAG, "getFormattedNumber, result: " + piiLog(formattedNumber));

        return formattedNumber;
    }

    /**
     * @return The ISO 3166-1 two letters country code of the country the user is in.
     */
    private static String getCurrentCountryIso(Context context, Locale locale) {
        String countryIso = null;
        CountryDetector detector = (CountryDetector) context.getSystemService(
                Context.COUNTRY_DETECTOR);
        if (detector != null) {
            Country country = detector.detectCountry();
            if (country != null) {
                countryIso = country.getCountryIso();
            } else {
                L.e(TAG, "CountryDetector.detectCountry() returned null.");
            }
        }
        if (countryIso == null) {
            countryIso = locale.getCountry();
            L.w(TAG, "No CountryDetector; falling back to countryIso based on locale: "
                    + countryIso);
        }
        if (countryIso == null || countryIso.length() != 2) {
            L.w(TAG, "Invalid locale, falling back to US");
            countryIso = COUNTRY_US;
        }
        return countryIso;
    }

    private static String getCurrentCountryIso(Context context) {
        return getCurrentCountryIso(context, Locale.getDefault());
    }

    private static String getCurrentCountryIsoFromLocale(Context context) {
        String countryIso;
        countryIso = context.getResources().getConfiguration().getLocales().get(0).getCountry();

        if (countryIso == null) {
            L.w(TAG, "Invalid locale, falling back to US");
            countryIso = COUNTRY_US;
        }

        return countryIso;
    }

    /**
     * Creates a new instance of {@link Phonenumber.PhoneNumber} base on the given number and sim
     * card country code. Returns {@code null} if the number in an invalid number.
     */
    @Nullable
    public static Phonenumber.PhoneNumber createI18nPhoneNumber(Context context, String number) {
        try {
            return PhoneNumberUtil.getInstance().parse(number, getCurrentCountryIso(context));
        } catch (NumberParseException e) {
            return null;
        }
    }

    /**
     * Contains all the info used to display a phone number on the screen. Returned by {@link
     * #getPhoneNumberInfo(Context, String)}
     */
    public static final class PhoneNumberInfo {
        private final String mPhoneNumber;
        private final String mDisplayName;
        private final String mInitials;
        private final Uri mAvatarUri;
        private final String mTypeLabel;

        public PhoneNumberInfo(String phoneNumber, String displayName,
                String initials, Uri avatarUri, String typeLabel) {
            mPhoneNumber = phoneNumber;
            mDisplayName = displayName;
            mInitials = initials;
            mAvatarUri = avatarUri;
            mTypeLabel = typeLabel;
        }

        public String getPhoneNumber() {
            return mPhoneNumber;
        }

        public String getDisplayName() {
            return mDisplayName;
        }

        /**
         * Returns the initials of the contact related to the phone number. Returns null if there is
         * no related contact.
         */
        @Nullable
        public String getInitials() {
            return mInitials;
        }

        @Nullable
        public Uri getAvatarUri() {
            return mAvatarUri;
        }

        public String getTypeLabel() {
            return mTypeLabel;
        }

    }

    /**
     * Gets all the info needed to properly display a phone number to the UI. (e.g. if it's the
     * voicemail number, return a string and a uri that represents voicemail, if it's a contact, get
     * the contact's name, its avatar uri, the phone number's label, etc).
     */
    public static CompletableFuture<PhoneNumberInfo> getPhoneNumberInfo(
            Context context, String number) {

        if (TextUtils.isEmpty(number)) {
            return CompletableFuture.completedFuture(new PhoneNumberInfo(
                    number,
                    context.getString(R.string.unknown),
                    null,
                    null,
                    ""));
        }

        if (isVoicemailNumber(context, number)) {
            return CompletableFuture.completedFuture(new PhoneNumberInfo(
                    number,
                    context.getString(R.string.voicemail),
                    null,
                    makeResourceUri(context, R.drawable.ic_voicemail),
                    ""));
        }

        if (InMemoryPhoneBook.isInitialized()) {
            Contact contact = InMemoryPhoneBook.get().lookupContactEntry(number);
            if (contact != null) {
                String name = contact.getDisplayName();
                if (name == null) {
                    name = getFormattedNumber(context, number);
                }

                if (name == null) {
                    name = context.getString(R.string.unknown);
                }

                PhoneNumber phoneNumber = contact.getPhoneNumber(context, number);
                CharSequence typeLabel = "";
                if (phoneNumber != null) {
                    typeLabel = Phone.getTypeLabel(context.getResources(),
                            phoneNumber.getType(),
                            phoneNumber.getLabel());
                }

                return CompletableFuture.completedFuture(new PhoneNumberInfo(
                        number,
                        name,
                        contact.getInitials(),
                        contact.getAvatarUri(),
                        typeLabel.toString()));
            }
        }

        return CompletableFuture.supplyAsync(() -> {
            String name = null;
            String nameAlt = null;
            String photoUriString = null;
            CharSequence typeLabel = "";
            ContentResolver cr = context.getContentResolver();
            String initials;
            try (Cursor cursor = cr.query(
                    Uri.withAppendedPath(PhoneLookup.CONTENT_FILTER_URI, Uri.encode(number)),
                    new String[]{
                            PhoneLookup.DISPLAY_NAME,
                            PhoneLookup.DISPLAY_NAME_ALTERNATIVE,
                            PhoneLookup.PHOTO_URI,
                            PhoneLookup.TYPE,
                            PhoneLookup.LABEL,
                    },
                    null, null, null)) {

                if (cursor != null && cursor.moveToFirst()) {
                    int nameColumn = cursor.getColumnIndex(PhoneLookup.DISPLAY_NAME);
                    int altNameColumn = cursor.getColumnIndex(PhoneLookup.DISPLAY_NAME_ALTERNATIVE);
                    int photoUriColumn = cursor.getColumnIndex(PhoneLookup.PHOTO_URI);
                    int typeColumn = cursor.getColumnIndex(PhoneLookup.TYPE);
                    int labelColumn = cursor.getColumnIndex(PhoneLookup.LABEL);

                    name = cursor.getString(nameColumn);
                    nameAlt = cursor.getString(altNameColumn);
                    photoUriString = cursor.getString(photoUriColumn);
                    int type = cursor.getInt(typeColumn);
                    String label = cursor.getString(labelColumn);
                    typeLabel = Phone.getTypeLabel(context.getResources(), type, label);
                }
            }

            initials = getInitials(name, nameAlt);

            if (name == null) {
                name = getFormattedNumber(context, number);
            }

            if (name == null) {
                name = context.getString(R.string.unknown);
            }

            return new PhoneNumberInfo(number, name, initials,
                    TextUtils.isEmpty(photoUriString) ? null : Uri.parse(photoUriString),
                    typeLabel.toString());
        });
    }

    /**
     * @return A string representation of the call state that can be presented to a user.
     */
    public static String callStateToUiString(Context context, int state) {
        Resources res = context.getResources();
        switch (state) {
            case Call.STATE_ACTIVE:
                return res.getString(R.string.call_state_call_active);
            case Call.STATE_HOLDING:
                return res.getString(R.string.call_state_hold);
            case Call.STATE_NEW:
            case Call.STATE_CONNECTING:
                return res.getString(R.string.call_state_connecting);
            case Call.STATE_SELECT_PHONE_ACCOUNT:
            case Call.STATE_DIALING:
                return res.getString(R.string.call_state_dialing);
            case Call.STATE_DISCONNECTED:
                return res.getString(R.string.call_state_call_ended);
            case Call.STATE_RINGING:
                return res.getString(R.string.call_state_call_ringing);
            case Call.STATE_DISCONNECTING:
                return res.getString(R.string.call_state_call_ending);
            default:
                throw new IllegalStateException("Unknown Call State: " + state);
        }
    }

    /**
     * Returns true if the telephony network is available.
     */
    public static boolean isNetworkAvailable(Context context) {
        TelephonyManager tm =
                (TelephonyManager) context.getSystemService(Context.TELEPHONY_SERVICE);
        return tm.getNetworkType() != TelephonyManager.NETWORK_TYPE_UNKNOWN
                && tm.getSimState() == TelephonyManager.SIM_STATE_READY;
    }

    /**
     * Returns true if airplane mode is on.
     */
    public static boolean isAirplaneModeOn(Context context) {
        return Settings.System.getInt(context.getContentResolver(),
                Settings.Global.AIRPLANE_MODE_ON, 0) != 0;
    }

    /**
     * Sets a Contact avatar onto the provided {@code icon}. The first letter or both letters of the
     * contact's initials.
     */
    public static void setContactBitmapAsync(
            Context context,
            @Nullable final ImageView icon,
            @Nullable final Contact contact) {
        setContactBitmapAsync(context, icon, contact, null);
    }

    /**
     * Sets a Contact avatar onto the provided {@code icon}. The first letter or both letters of the
     * contact's initials or {@code fallbackDisplayName} will be used as a fallback resource if
     * avatar loading fails.
     */
    public static void setContactBitmapAsync(
            Context context,
            @Nullable final ImageView icon,
            @Nullable final Contact contact,
            @Nullable final String fallbackDisplayName) {
        Uri avatarUri = contact != null ? contact.getAvatarUri() : null;
        String initials = contact != null ? contact.getInitials()
                : (fallbackDisplayName == null ? null : getInitials(fallbackDisplayName, null));
        String identifier = contact == null ? fallbackDisplayName : contact.getDisplayName();

        setContactBitmapAsync(context, icon, avatarUri, initials, identifier);
    }

    /**
     * Sets a Contact avatar onto the provided {@code icon}. A letter tile base on the contact's
     * initials and identifier will be used as a fallback resource if avatar loading fails.
     */
    public static void setContactBitmapAsync(
            Context context,
            @Nullable final ImageView icon,
            @Nullable final Uri avatarUri,
            @Nullable final String initials,
            @Nullable final String identifier) {
        if (icon == null) {
            return;
        }

        LetterTileDrawable letterTileDrawable = createLetterTile(context, initials, identifier);

        Glide.with(context)
                .load(avatarUri)
                .apply(new RequestOptions().centerCrop().error(letterTileDrawable))
                .into(icon);
    }

    /**
     * Create a {@link LetterTileDrawable} for the given initials.
     *
     * @param initials   is the letters that will be drawn on the canvas. If it is null, then an
     *                   avatar anonymous icon will be drawn
     * @param identifier will decide the color for the drawable. If null, a default color will be
     *                   used.
     */
    public static LetterTileDrawable createLetterTile(
            Context context,
            @Nullable String initials,
            @Nullable String identifier) {
        int numberOfLetter = context.getResources().getInteger(
                R.integer.config_number_of_letters_shown_for_avatar);
        String letters = initials != null
                ? initials.substring(0, Math.min(initials.length(), numberOfLetter)) : null;
        LetterTileDrawable letterTileDrawable = new LetterTileDrawable(context.getResources(),
                letters, identifier);
        return letterTileDrawable;
    }

    /**
     * Set the given phone number as the primary phone number for its associated contact.
     */
    public static void setAsPrimaryPhoneNumber(Context context, PhoneNumber phoneNumber) {
        // Update the primary values in the data record.
        ContentValues values = new ContentValues(1);
        values.put(ContactsContract.Data.IS_SUPER_PRIMARY, 1);
        values.put(ContactsContract.Data.IS_PRIMARY, 1);

        context.getContentResolver().update(
                ContentUris.withAppendedId(ContactsContract.Data.CONTENT_URI, phoneNumber.getId()),
                values, null, null);
    }

    /**
     * Add a contact to favorite or remove it from favorite.
     */
    public static int setAsFavoriteContact(Context context, Contact contact, boolean isFavorite) {
        if (contact.isStarred() == isFavorite) {
            return 0;
        }

        ContentValues values = new ContentValues(1);
        values.put(ContactsContract.Contacts.STARRED, isFavorite ? 1 : 0);

        String where = ContactsContract.Contacts._ID + " = ?";
        String[] selectionArgs = new String[]{Long.toString(contact.getId())};
        return context.getContentResolver().update(ContactsContract.Contacts.CONTENT_URI, values,
                where, selectionArgs);
    }

    /**
     * Mark missed call log matching given phone number as read. If phone number string is not
     * valid, it will mark all new missed call log as read.
     */
    public static void markCallLogAsRead(Context context, String phoneNumberString) {
        if (context.checkSelfPermission(Manifest.permission.WRITE_CALL_LOG)
                != PackageManager.PERMISSION_GRANTED) {
            L.w(TAG, "Missing WRITE_CALL_LOG permission; not marking missed calls as read.");
            return;
        }
        ContentValues contentValues = new ContentValues();
        contentValues.put(CallLog.Calls.NEW, 0);
        contentValues.put(CallLog.Calls.IS_READ, 1);

        List<String> selectionArgs = new ArrayList<>();
        StringBuilder where = new StringBuilder();
        where.append(CallLog.Calls.NEW);
        where.append(" = 1 AND ");
        where.append(CallLog.Calls.TYPE);
        where.append(" = ?");
        selectionArgs.add(Integer.toString(CallLog.Calls.MISSED_TYPE));
        if (!TextUtils.isEmpty(phoneNumberString)) {
            where.append(" AND ");
            where.append(CallLog.Calls.NUMBER);
            where.append(" = ?");
            selectionArgs.add(phoneNumberString);
        }
        String[] selectionArgsArray = new String[0];
        try {
            context
                    .getContentResolver()
                    .update(
                            CallLog.Calls.CONTENT_URI,
                            contentValues,
                            where.toString(),
                            selectionArgs.toArray(selectionArgsArray));
        } catch (IllegalArgumentException e) {
            L.e(TAG, "markCallLogAsRead failed", e);
        }
    }

    /**
     * Returns the initials based on the name and nameAlt.
     *
     * @param name    should be the display name of a contact.
     * @param nameAlt should be alternative display name of a contact.
     */
    public static String getInitials(String name, String nameAlt) {
        StringBuilder initials = new StringBuilder();
        if (!TextUtils.isEmpty(name) && Character.isLetter(name.charAt(0))) {
            initials.append(Character.toUpperCase(name.charAt(0)));
        }
        if (!TextUtils.isEmpty(nameAlt)
                && !TextUtils.equals(name, nameAlt)
                && Character.isLetter(nameAlt.charAt(0))) {
            initials.append(Character.toUpperCase(nameAlt.charAt(0)));
        }
        return initials.toString();
    }

    /**
     * Creates a Letter Tile Icon that will display the given initials. If the initials are null,
     * then an avatar anonymous icon will be drawn.
     **/
    public static Icon createLetterTile(Context context, @Nullable String initials,
            String identifier, int avatarSize, float cornerRadiusPercent) {
        LetterTileDrawable letterTileDrawable = TelecomUtils.createLetterTile(context, initials,
                identifier);
        RoundedBitmapDrawable roundedBitmapDrawable = RoundedBitmapDrawableFactory.create(
                context.getResources(), letterTileDrawable.toBitmap(avatarSize));
        return createFromRoundedBitmapDrawable(roundedBitmapDrawable, avatarSize,
                cornerRadiusPercent);
    }

    /** Creates an Icon based on the given roundedBitmapDrawable. **/
    public static Icon createFromRoundedBitmapDrawable(RoundedBitmapDrawable roundedBitmapDrawable,
            int avatarSize, float cornerRadiusPercent) {
        float radius = avatarSize * cornerRadiusPercent;
        roundedBitmapDrawable.setCornerRadius(radius);

        final Bitmap result = Bitmap.createBitmap(avatarSize, avatarSize,
                Bitmap.Config.ARGB_8888);
        final Canvas canvas = new Canvas(result);
        roundedBitmapDrawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        roundedBitmapDrawable.draw(canvas);
        return Icon.createWithBitmap(result);
    }

    /**
     * Sets the direction of a string, used for displaying phone numbers.
     */
    public static String getBidiWrappedNumber(String string) {
        return BidiFormatter.getInstance().unicodeWrap(string, TextDirectionHeuristics.LTR);
    }

    private static Uri makeResourceUri(Context context, int resourceId) {
        return new Uri.Builder()
                .scheme(ContentResolver.SCHEME_ANDROID_RESOURCE)
                .encodedAuthority(context.getBasePackageName())
                .appendEncodedPath(String.valueOf(resourceId))
                .build();
    }

    /**
     * This is a workaround for Log.Pii(). It will only show the last {@link #PII_STRING_LENGTH}
     * characters.
     */
    public static String piiLog(Object pii) {
        String piiString = String.valueOf(pii);
        return piiString.length() >= PII_STRING_LENGTH ? "*" + piiString.substring(
                piiString.length() - PII_STRING_LENGTH) : piiString;
    }
}
