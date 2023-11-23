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

package com.android.nearby.halfsheet.constants;

/**
 * String constant for half sheet.
 */
public class Constant {
    public static final String TAG = "FastPairHalfSheet";
    private static final String PREFIX = "com.android.nearby.halfsheet.";

    // Intent extra
    public static final String EXTRA_BINDER = "com.android.server.nearby.fastpair.BINDER";
    public static final String EXTRA_BUNDLE = "com.android.server.nearby.fastpair.BUNDLE_EXTRA";

    public static final String EXTRA_TITLE = PREFIX + "HALF_SHEET_TITLE";
    public static final String EXTRA_DESCRIPTION = PREFIX + "HALF_SHEET_DESCRIPTION";
    public static final String EXTRA_HALF_SHEET_ID = PREFIX + "HALF_SHEET_ID";
    public static final String EXTRA_HALF_SHEET_INFO = PREFIX + "HALF_SHEET";
    public static final String EXTRA_HALF_SHEET_TYPE = PREFIX + "HALF_SHEET_TYPE";
    public static final String EXTRA_HALF_SHEET_ACCOUNT_NAME = PREFIX + "HALF_SHEET_ACCOUNT_NAME";
    public static final String EXTRA_HALF_SHEET_CONTENT = PREFIX + "HALF_SHEET_CONTENT";
    public static final String EXTRA_HALF_SHEET_FOREGROUND =
            PREFIX + "EXTRA_HALF_SHEET_FOREGROUND";
    public static final String EXTRA_HALF_SHEET_IS_RETROACTIVE =
            PREFIX + "HALF_SHEET_IS_RETROACTIVE";
    public static final String EXTRA_HALF_SHEET_IS_SUBSEQUENT_PAIR =
            PREFIX + "HALF_SHEET_IS_SUBSEQUENT_PAIR";
    public static final String EXTRA_HALF_SHEET_PAIRING_RESURFACE =
            PREFIX + "EXTRA_HALF_SHEET_PAIRING_RESURFACE";

    // Intent Actions
    public static final String ACTION_HALF_SHEET_FOREGROUND_STATE =
            PREFIX + "ACTION_HALF_SHEET_FOREGROUND_STATE";
    public static final String ACTION_FAST_PAIR_HALF_SHEET_CANCEL =
            "com.android.nearby.ACTION_FAST_PAIR_HALF_SHEET_CANCEL";
    public static final String ACTION_FAST_PAIR_HALF_SHEET_BAN_STATE_RESET =
            "com.android.nearby.ACTION_FAST_PAIR_BAN_STATE_RESET";
    public static final String ACTION_RESOURCES_APK =
            "android.nearby.SHOW_HALFSHEET";
    public static final String ACTION_FAST_PAIR = PREFIX + "ACTION_MAGIC_PAIR";

    public static final String RESULT_FAIL = "RESULT_FAIL";
    public static final String ARG_FRAGMENT_STATE = "ARG_FRAGMENT_STATE";
    public static final String DEVICE_PAIRING_FRAGMENT_TYPE = "DEVICE_PAIRING";

    // Content url for help page about Fast Pair in half sheet.
    // Todo(b/246007000): Add a flag to set up content url of the help page.
    public static final String FAST_PAIR_HALF_SHEET_HELP_URL =
            "https://support.google.com/android/answer/9075925";
}
