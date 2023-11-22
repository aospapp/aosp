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

package android.net.wifi.p2p.cts;

import android.net.MacAddress;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pGroup;
import android.test.AndroidTestCase;

public class WifiP2pConfigTest extends AndroidTestCase {
    static final String TEST_NETWORK_NAME = "DIRECT-xy-Hello";
    static final String TEST_PASSPHRASE = "8etterW0r1d";
    static final int TEST_OWNER_BAND = WifiP2pConfig.GROUP_OWNER_BAND_5GHZ;
    static final int TEST_OWNER_FREQ = 2447;
    static final String TEST_DEVICE_ADDRESS = "aa:bb:cc:dd:ee:ff";

    public void testWifiP2pConfigBuilderForPersist() {
        WifiP2pConfig.Builder builder = new WifiP2pConfig.Builder();
        builder.setNetworkName(TEST_NETWORK_NAME)
                .setPassphrase(TEST_PASSPHRASE)
                .setGroupOperatingBand(TEST_OWNER_BAND)
                .setDeviceAddress(MacAddress.fromString(TEST_DEVICE_ADDRESS))
                .enablePersistentMode(true);
        WifiP2pConfig config = builder.build();

        assertTrue(config.deviceAddress.equals(TEST_DEVICE_ADDRESS));
        assertTrue(config.networkName.equals(TEST_NETWORK_NAME));
        assertTrue(config.passphrase.equals(TEST_PASSPHRASE));
        assertEquals(config.groupOwnerBand, TEST_OWNER_BAND);
        assertEquals(config.netId, WifiP2pGroup.PERSISTENT_NET_ID);
    }

    public void testWifiP2pConfigBuilderForNonPersist() {
        WifiP2pConfig.Builder builder = new WifiP2pConfig.Builder();
        builder.setNetworkName(TEST_NETWORK_NAME)
                .setPassphrase(TEST_PASSPHRASE)
                .setGroupOperatingFrequency(TEST_OWNER_FREQ)
                .setDeviceAddress(MacAddress.fromString(TEST_DEVICE_ADDRESS))
                .enablePersistentMode(false);
        WifiP2pConfig config = builder.build();

        assertTrue(config.deviceAddress.equals(TEST_DEVICE_ADDRESS));
        assertTrue(config.networkName.equals(TEST_NETWORK_NAME));
        assertTrue(config.passphrase.equals(TEST_PASSPHRASE));
        assertEquals(config.groupOwnerBand, TEST_OWNER_FREQ);
        assertEquals(config.netId, WifiP2pGroup.TEMPORARY_NET_ID);
    }
}
