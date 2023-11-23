/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.server.wifi;

import android.app.StatsManager;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.util.Log;
import android.util.StatsEvent;

import com.android.server.wifi.proto.WifiStatsLog;

import java.util.List;

/**
 * This is used to log pulled atoms to StatsD via Callback.
 */
public class WifiPulledAtomLogger {
    // Internal version number for tracking.
    // Digits 0-1 is target build SDK level
    // Digits 2-3 is the month of the mainline train
    public static final int WIFI_VERSION_NUMBER = 340899999;
    public static final String WIFI_BUILD_FROM_SOURCE_PACKAGE_NAME = "com.android.wifi";
    private int mWifiBuildType = 0;

    private static final String TAG = "WifiPulledAtomLogger";
    private final StatsManager mStatsManager;
    private final Handler mWifiHandler;
    private final Context mContext;
    private StatsManager.StatsPullAtomCallback mStatsPullAtomCallback;
    public WifiPulledAtomLogger(StatsManager statsManager, Handler handler, Context context) {
        mStatsManager = statsManager;
        mWifiHandler = handler;
        mStatsPullAtomCallback = new WifiPullAtomCallback();
        mContext = context;
    }

    /**
     * Set up an atom to get pulled.
     * @param atomTag
     */
    public void setPullAtomCallback(int atomTag) {
        if (mStatsManager == null) {
            Log.e(TAG, "StatsManager is null. Failed to set wifi pull atom callback for atomTag="
                    + atomTag);
            return;
        }
        mStatsManager.setPullAtomCallback(
                atomTag,
                null, // use default meta data values
                command -> mWifiHandler.post(command), // Executor posting to wifi handler thread
                mStatsPullAtomCallback
        );
    }

    /**
     * Implementation of StatsPullAtomCallback. This will check the atom tag and log data
     * correspondingly.
     */
    public class WifiPullAtomCallback implements StatsManager.StatsPullAtomCallback {
        @Override
        public int onPullAtom(int atomTag, List<StatsEvent> data) {
            switch (atomTag) {
                case WifiStatsLog.WIFI_MODULE_INFO:
                    return handleWifiVersionPull(atomTag, data);
                default:
                    return StatsManager.PULL_SKIP;
            }
        }
    }

    private int handleWifiVersionPull(int atomTag, List<StatsEvent> data) {
        if (mWifiBuildType != 0) {
            // build type already cached. No need to get it again.
            data.add(WifiStatsLog.buildStatsEvent(atomTag, WIFI_VERSION_NUMBER, mWifiBuildType));
            return StatsManager.PULL_SUCCESS;
        }
        // Query build type and cache if not already cached.
        PackageManager pm = mContext.getPackageManager();
        if (pm == null) {
            Log.e(TAG, "Failed to get package manager");
            return StatsManager.PULL_SKIP;
        }
        List<PackageInfo> packageInfos = pm.getInstalledPackages(PackageManager.MATCH_APEX);
        boolean found = false;
        for (PackageInfo packageInfo : packageInfos) {
            if (WIFI_BUILD_FROM_SOURCE_PACKAGE_NAME.equals(packageInfo.packageName)) {
                found = true;
                break;
            }
        }
        mWifiBuildType = found
                ? WifiStatsLog.WIFI_MODULE_INFO__BUILD_TYPE__TYPE_BUILT_FROM_SOURCE
                : WifiStatsLog.WIFI_MODULE_INFO__BUILD_TYPE__TYPE_PREBUILT;
        data.add(WifiStatsLog.buildStatsEvent(atomTag, WIFI_VERSION_NUMBER, mWifiBuildType));
        return StatsManager.PULL_SUCCESS;
    }
}
