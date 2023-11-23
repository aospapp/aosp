/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.dialer.framework.testdata;

/** Test data for fake database. */
public final class TestData {
    public static final String ACCOUNT_TYPE = "CD.FakeDatabase";
    public static final String ACCOUNT_NAME = "CD.FakeDevice.%d";
    public static final String ACCOUNT_NAME_PREFIX = "CD.FakeDevice.";
    private static final String CONTACT_DATA_FILE = "ContactsForPhone%d.json";
    private static final String CALL_LOG_DATA_FILE = "CallLogForPhone%d.json";

    private static int sDeviceId = 0;

    /** Generates a new device id. */
    public static int newDeviceId() {
        return sDeviceId++;
    }

    /** Returns the contact data file name for the given device id. */
    public static String getContactDataFile(int deviceId) {
        return String.format(CONTACT_DATA_FILE, deviceId % 3 + 1);
    }

    /** Returns the call log data file name for the given device id. */
    public static String getCallLogDataFile(int deviceId) {
        return String.format(CALL_LOG_DATA_FILE, deviceId % 3 + 1);
    }
}
