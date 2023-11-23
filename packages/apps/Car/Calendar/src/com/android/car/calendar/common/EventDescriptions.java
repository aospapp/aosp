/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.car.calendar.common;

import static com.android.i18n.phonenumbers.PhoneNumberUtil.PhoneNumberFormat.INTERNATIONAL;
import static com.android.i18n.phonenumbers.PhoneNumberUtil.ValidationResult.IS_POSSIBLE;
import static com.android.i18n.phonenumbers.PhoneNumberUtil.ValidationResult.IS_POSSIBLE_LOCAL_ONLY;
import static com.android.i18n.phonenumbers.PhoneNumberUtil.ValidationResult.TOO_LONG;

import static com.google.common.base.Verify.verifyNotNull;

import android.net.Uri;

import com.android.car.calendar.common.Dialer.NumberAndAccess;
import com.android.i18n.phonenumbers.NumberParseException;
import com.android.i18n.phonenumbers.PhoneNumberUtil;
import com.android.i18n.phonenumbers.Phonenumber;

import com.google.common.collect.ImmutableList;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.annotation.Nullable;

/** Utilities to manipulate the description of a calendar event which may contain meta-data. */
public class EventDescriptions {

    // Requires a phone number to include only numbers, spaces and dash, optionally a leading "+".
    // The number must be at least 6 characters.
    // The access code must be at least 3 characters.
    // The number and the access to include "pin" or "code" between the numbers.
    private static final Pattern PHONE_PIN_PATTERN =
            Pattern.compile(
                    "(\\+?[\\d -]{6,})(?:.*\\b(?:PIN|code)\\b.*?([\\d,;#*]{3,}))?",
                    Pattern.CASE_INSENSITIVE);

    // Matches numbers in the encoded format "<tel: ... >".
    private static final Pattern TEL_PIN_PATTERN =
            Pattern.compile("<tel:(\\+?[\\d -]{6,})([\\d,;#*]{3,})?>");

    private static final PhoneNumberUtil PHONE_NUMBER_UTIL = PhoneNumberUtil.getInstance();

    // Ensure numbers are over 5 digits to reduce false positives.
    private static final int MIN_NATIONAL_NUMBER = 10_000;

    private final Locale mLocale;

    public EventDescriptions(Locale locale) {
        mLocale = locale;
    }

    /** Find conference call data embedded in the description. */
    public List<NumberAndAccess> extractNumberAndPins(String descriptionText) {
        String decoded = Uri.decode(descriptionText);

        Map<String, NumberAndAccess> results = new LinkedHashMap<>();
        addMatchedNumbers(decoded, results, PHONE_PIN_PATTERN);
        addMatchedNumbers(decoded, results, TEL_PIN_PATTERN);
        return ImmutableList.copyOf(results.values());
    }

    private void addMatchedNumbers(
            String decoded, Map<String, NumberAndAccess> results, Pattern phonePinPattern) {
        Matcher phoneFormatMatcher = phonePinPattern.matcher(decoded);
        while (phoneFormatMatcher.find()) {
            NumberAndAccess numberAndAccess = validNumberAndAccess(phoneFormatMatcher);
            if (numberAndAccess != null) {
                results.put(numberAndAccess.getNumber(), numberAndAccess);
            }
        }
    }

    @Nullable
    private NumberAndAccess validNumberAndAccess(Matcher phoneFormatMatcher) {
        String number = verifyNotNull(phoneFormatMatcher.group(1));
        String access = phoneFormatMatcher.group(2);
        try {
            Phonenumber.PhoneNumber phoneNumber =
                    PHONE_NUMBER_UTIL.parse(number, mLocale.getCountry());
            PhoneNumberUtil.ValidationResult result =
                    PHONE_NUMBER_UTIL.isPossibleNumberWithReason(phoneNumber);
            if (isAcceptableResult(result)) {
                if (phoneNumber.getNationalNumber() < MIN_NATIONAL_NUMBER) {
                    return null;
                }
                String formatted = PHONE_NUMBER_UTIL.format(phoneNumber, INTERNATIONAL);
                return new NumberAndAccess(formatted, access);
            }
        } catch (NumberParseException e) {
            // Ignore invalid numbers.
        }
        return null;
    }

    private boolean isAcceptableResult(PhoneNumberUtil.ValidationResult result) {
        // The result can be too long and still valid because the US locale is used by default
        // which does not accept valid long numbers from other regions.
        return result == IS_POSSIBLE || result == IS_POSSIBLE_LOCAL_ONLY || result == TOO_LONG;
    }
}
