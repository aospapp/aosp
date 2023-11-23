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

package com.android.ondevicepersonalization.services.data.user;

import android.content.res.Configuration;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/**
 * A singleton class that holds all most recent in-memory user signals.
 */
public final class RawUserData {

    private static RawUserData sUserData = null;
    private static final String TAG = "UserData";

    // The current system time in milliseconds.
    public long timeMillis = 0;

    // The device time zone +/- minutes offset from UTC.
    public int utcOffset = 0;

    // The device orientation.
    public int orientation = Configuration.ORIENTATION_PORTRAIT;

    // Available bytes in MB.
    public int availableBytesMB = 0;

    // Battery percentage.
    public int batteryPct = 0;

    // The 3-letter ISO-3166 country code
    public Country country = Country.UNKNOWN;

    // The 2-letter ISO-639 language code
    public Language language = Language.UNKNOWN;

    // Mobile carrier.
    public Carrier carrier = Carrier.UNKNOWN;

    // OS versions of the device.
    public OSVersion osVersions = new OSVersion();

    // Connection type values.
    public enum ConnectionType {
        UNKNOWN,
        ETHERNET,
        WIFI,
        CELLULAR_2G,
        CELLULAR_3G,
        CELLULAR_4G,
        CELLULAR_5G
    };

    // Connection type.
    public ConnectionType connectionType = ConnectionType.UNKNOWN;

    // Status if network is metered. False - not metered. True - metered.
    public boolean networkMeteredStatus = false;

    // Connection speed in kbps.
    public int connectionSpeedKbps = 0;

    // Device metrics values.
    public DeviceMetrics deviceMetrics = new DeviceMetrics();

    // installed packages.
    public List<AppInfo> appsInfo = new ArrayList<>();

    // A histogram of app usage: total times used per app in the last 30 days.
    public HashMap<String, Long> appUsageHistory = new HashMap<>();

    // User's most recently available location information.
    public LocationInfo currentLocation = new LocationInfo();

    /**
     * A histogram of location history: total time spent per location in the last 30 days.
     * Default precision level of locations is set to E4.
     */
    public HashMap<LocationInfo, Long> locationHistory = new HashMap<>();

    private RawUserData() { }

    /** Returns an instance of UserData. */
    public static RawUserData getInstance() {
        synchronized (RawUserData.class) {
            if (sUserData == null) {
                sUserData = new RawUserData();
            }
            return sUserData;
        }
    }
}
