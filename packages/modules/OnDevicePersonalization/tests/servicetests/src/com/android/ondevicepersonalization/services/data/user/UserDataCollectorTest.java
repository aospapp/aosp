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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.content.Context;
import android.text.TextUtils;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

@RunWith(JUnit4.class)
public class UserDataCollectorTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private UserDataCollector mCollector;
    private RawUserData mUserData;

    @Before
    public void setup() {
        mCollector = UserDataCollector.getInstanceForTest(mContext);
        mUserData = RawUserData.getInstance();
    }

    @Test
    public void testUpdateUserData() throws InterruptedException {
        mCollector.updateUserData(mUserData);

        // Test initial collection.
        // TODO(b/261748573): Add manual tests for histogram updates
        assertTrue(mUserData.timeMillis > 0);
        assertTrue(mUserData.timeMillis <= mCollector.getTimeMillis());
        assertNotNull(mUserData.utcOffset);
        assertEquals(mUserData.utcOffset, mCollector.getUtcOffset());

        assertTrue(mUserData.availableBytesMB > 0);
        assertTrue(mUserData.batteryPct > 0);
        assertEquals(mUserData.country, mCollector.getCountry());
        assertEquals(mUserData.language, mCollector.getLanguage());
        assertEquals(mUserData.carrier, mCollector.getCarrier());
        assertEquals(mUserData.connectionType, mCollector.getConnectionType());
        assertEquals(mUserData.networkMeteredStatus, mCollector.getNetworkMeteredStatus());

        OSVersion osVersions = new OSVersion();
        mCollector.getOSVersions(osVersions);
        assertEquals(mUserData.osVersions.major, osVersions.major);
        assertEquals(mUserData.osVersions.minor, osVersions.minor);
        assertEquals(mUserData.osVersions.micro, osVersions.micro);

        DeviceMetrics deviceMetrics = new DeviceMetrics();
        mCollector.getDeviceMetrics(deviceMetrics);
        assertEquals(mUserData.deviceMetrics.make, deviceMetrics.make);
        assertEquals(mUserData.deviceMetrics.model, deviceMetrics.model);
        assertTrue(mUserData.deviceMetrics.screenHeight > 0);
        assertEquals(mUserData.deviceMetrics.screenHeight, deviceMetrics.screenHeight);
        assertTrue(mUserData.deviceMetrics.screenWidth > 0);
        assertEquals(mUserData.deviceMetrics.screenWidth, deviceMetrics.screenWidth);
        assertTrue(mUserData.deviceMetrics.xdpi > 0);
        assertEquals(mUserData.deviceMetrics.xdpi, deviceMetrics.xdpi, 0.01);
        assertTrue(mUserData.deviceMetrics.ydpi > 0);
        assertEquals(mUserData.deviceMetrics.ydpi, deviceMetrics.ydpi, 0.01);
        assertTrue(mUserData.deviceMetrics.pxRatio > 0);
        assertEquals(mUserData.deviceMetrics.pxRatio, deviceMetrics.pxRatio, 0.01);

        List<AppInfo> appsInfo = new ArrayList();
        mCollector.getInstalledApps(appsInfo);
        assertTrue(mUserData.appsInfo.size() > 0);
        assertEquals(mUserData.appsInfo.size(), appsInfo.size());
        for (int i = 0; i < mUserData.appsInfo.size(); ++i) {
            assertFalse(TextUtils.isEmpty(mUserData.appsInfo.get(i).packageName));
            assertEquals(mUserData.appsInfo.get(i).packageName, appsInfo.get(i).packageName);
            assertEquals(mUserData.appsInfo.get(i).installed, appsInfo.get(i).installed);
        }
    }

    @Test
    public void testRealTimeUpdate() {
        // TODO: test orientation modification.
        mCollector.updateUserData(mUserData);
        long oldTimeMillis = mUserData.timeMillis;
        TimeZone tzGmt4 = TimeZone.getTimeZone("GMT+04:00");
        TimeZone.setDefault(tzGmt4);
        mCollector.getRealTimeData(mUserData);
        assertTrue(oldTimeMillis <= mUserData.timeMillis);
        assertEquals(mUserData.utcOffset, 240);
    }

    @Test
    public void testGetCountry() {
        mCollector.setLocale(new Locale("en", "US"));
        mCollector.updateUserData(mUserData);
        assertNotNull(mUserData.country);
        assertEquals(mUserData.country, Country.USA);
    }

    @Test
    public void testUnknownCountry() {
        mCollector.setLocale(new Locale("en"));
        mCollector.updateUserData(mUserData);
        assertNotNull(mUserData.country);
        assertEquals(mUserData.country, Country.UNKNOWN);
    }

    @Test
    public void testGetLanguage() {
        mCollector.setLocale(new Locale("zh", "CN"));
        mCollector.updateUserData(mUserData);
        assertNotNull(mUserData.language);
        assertEquals(mUserData.language, Language.ZH);
    }

    @Test
    public void testUnknownLanguage() {
        mCollector.setLocale(new Locale("nonexist_lang", "CA"));
        mCollector.updateUserData(mUserData);
        assertNotNull(mUserData.language);
        assertEquals(mUserData.language, Language.UNKNOWN);
    }

    @Test
    public void testRecoveryFromSystemCrash() {
        mCollector.updateUserData(mUserData);
        // Backup sample answer.
        final HashMap<String, Long> refAppUsageHistogram =
                copyAppUsageMap(mUserData.appUsageHistory);
        final HashMap<LocationInfo, Long> refLocationHistogram =
                copyLocationMap(mUserData.locationHistory);
        final Deque<AppUsageEntry> refAllowedAppUsageEntries =
                copyAppUsageEntries(mCollector.getAllowedAppUsageEntries());
        final Deque<LocationInfo> refAllowedLocationEntries =
                copyLocationEntries(mCollector.getAllowedLocationEntries());
        final long refLastTimeAppUsageCollected = mCollector.getLastTimeMillisAppUsageCollected();

        // Mock system crash scenario.
        mCollector.clearUserData(mUserData);
        mCollector.clearMetadata();
        mCollector.recoverAppUsageHistogram(mUserData.appUsageHistory);
        mCollector.recoverLocationHistogram(mUserData.locationHistory);

        assertEquals(refAppUsageHistogram.size(), mUserData.appUsageHistory.size());
        for (String key: refAppUsageHistogram.keySet()) {
            assertTrue(mUserData.appUsageHistory.containsKey(key));
            assertEquals(refAppUsageHistogram.get(key), mUserData.appUsageHistory.get(key));
        }
        assertEquals(refLastTimeAppUsageCollected,
                mCollector.getLastTimeMillisAppUsageCollected());
        assertEquals(refAllowedAppUsageEntries.size(),
                mCollector.getAllowedAppUsageEntries().size());

        assertEquals(refLocationHistogram.size(), mUserData.locationHistory.size());
        for (LocationInfo locationInfo: refLocationHistogram.keySet()) {
            assertTrue(mUserData.locationHistory.containsKey(locationInfo));
            assertEquals(refLocationHistogram.get(locationInfo),
                    mUserData.locationHistory.get(locationInfo));
        }
        assertEquals(refAllowedLocationEntries.size(),
                mCollector.getAllowedLocationEntries().size());
    }

    private HashMap<String, Long> copyAppUsageMap(HashMap<String, Long> other) {
        HashMap<String, Long> copy = new HashMap<>();
        for (String key: other.keySet()) {
            copy.put(key, (long) other.get(key));
        }
        return copy;
    }

    private HashMap<LocationInfo, Long> copyLocationMap(HashMap<LocationInfo, Long> other) {
        HashMap<LocationInfo, Long> copy = new HashMap<>();
        for (LocationInfo key: other.keySet()) {
            copy.put(new LocationInfo(key), (long) other.get(key));
        }
        return copy;
    }

    private Deque<AppUsageEntry> copyAppUsageEntries(Deque<AppUsageEntry> other) {
        Deque<AppUsageEntry> copy = new ArrayDeque<>();
        for (AppUsageEntry entry: other) {
            copy.add(new AppUsageEntry(entry));
        }
        return copy;
    }

    private Deque<LocationInfo> copyLocationEntries(Deque<LocationInfo> other) {
        Deque<LocationInfo> copy = new ArrayDeque<>();
        for (LocationInfo entry: other) {
            copy.add(new LocationInfo(entry));
        }
        return copy;
    }

    @After
    public void cleanUp() {
        mCollector.clearUserData(mUserData);
        mCollector.clearMetadata();
        mCollector.clearDatabase();
    }
}
