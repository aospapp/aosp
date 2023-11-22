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
package com.android.cts.deviceandprofileowner;

import static com.google.common.truth.Truth.assertWithMessage;

import android.net.MacAddress;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;

import com.android.compatibility.common.util.WifiConfigCreator;

import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Tests that DO/PO can access randomized WiFi addresses.
 */
public class RandomizedWifiMacAddressTest extends BaseDeviceAdminTest {
    /** Mac address returned when the caller doesn't have access. */
    private static final String DEFAULT_MAC_ADDRESS = "02:00:00:00:00:00";
    /** SSID returned when the caller doesn't have access or if WiFi is not connected. */
    private static final String NETWORK_SSID = "TestSSID";
    private static final String DEFAULT_SSID = "<unknown ssid>";

    private int mNetId;
    private WifiConfigCreator mWifiConfigCreator;

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mWifiConfigCreator = new WifiConfigCreator(mContext);
        mNetId = mWifiConfigCreator.addNetwork(NETWORK_SSID, false,
                WifiConfigCreator.SECURITY_TYPE_NONE, null);
        assertWithMessage("Fail to create test network")
                .that(mNetId)
                .isNotEqualTo(-1);
    }

    @Override
    protected void tearDown() throws Exception {
        mWifiConfigCreator.removeNetwork(mNetId);
        super.tearDown();
    }
    public void testGetRandomizedMacAddress() {
        final WifiManager wifiManager = mContext.getSystemService(WifiManager.class);

        final List<WifiConfiguration> wifiConfigs = wifiManager.getConfiguredNetworks();
        for (final WifiConfiguration config : wifiConfigs) {
            if (config.SSID == null) {
                continue;
            }

            if (config.SSID.equals("\"" + NETWORK_SSID + "\"")) {
                final MacAddress macAddress = config.getRandomizedMacAddress();

                assertWithMessage("Device owner should be able to get the randomized MAC address")
                        .that(macAddress)
                        .isNotEqualTo((MacAddress.fromString(DEFAULT_MAC_ADDRESS)));
                return;
            }
        }

        final String ssids = wifiConfigs.stream()
                .map(c -> c.SSID).filter(Objects::nonNull).collect(Collectors.joining(","));

        fail(String.format("Failed to find WifiConfiguration for the current connection, " +
                "current SSID: %s; configured SSIDs: %s", NETWORK_SSID, ssids));
    }
}
