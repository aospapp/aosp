/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static junit.framework.Assert.assertEquals;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.net.wifi.WifiConfiguration;

import androidx.test.filters.SmallTest;

import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;


/** Unit tests for {@link WifiGlobals} */
@SmallTest
public class WifiGlobalsTest extends WifiBaseTest {

    private WifiGlobals mWifiGlobals;
    private MockResources mResources;

    @Mock private Context mContext;

    private static final int TEST_NETWORK_ID = 54;
    private static final String TEST_SSID = "\"GoogleGuest\"";

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mResources = new MockResources();
        mResources.setInteger(R.integer.config_wifiPollRssiIntervalMilliseconds, 3000);
        mResources.setInteger(R.integer.config_wifiClientModeImplNumLogRecs, 200);
        mResources.setBoolean(R.bool.config_wifiSaveFactoryMacToWifiConfigStore, true);
        mResources.setStringArray(R.array.config_wifiForceDisableMacRandomizationSsidPrefixList,
                new String[] {TEST_SSID});
        when(mContext.getResources()).thenReturn(mResources);

        mWifiGlobals = new WifiGlobals(mContext);
    }

    /** Test that the interval for poll RSSI is read from config overlay correctly. */
    @Test
    public void testPollRssiIntervalIsSetCorrectly() throws Exception {
        assertEquals(3000, mWifiGlobals.getPollRssiIntervalMillis());
        mResources.setInteger(R.integer.config_wifiPollRssiIntervalMilliseconds, 9000);
        assertEquals(9000, new WifiGlobals(mContext).getPollRssiIntervalMillis());
    }

    /** Verify that Bluetooth active is set correctly with BT state/connection state changes */
    @Test
    public void verifyBluetoothStateAndConnectionStateChanges() {
        mWifiGlobals.setBluetoothEnabled(true);
        assertThat(mWifiGlobals.isBluetoothConnected()).isFalse();

        mWifiGlobals.setBluetoothConnected(true);
        assertThat(mWifiGlobals.isBluetoothConnected()).isTrue();

        mWifiGlobals.setBluetoothEnabled(false);
        assertThat(mWifiGlobals.isBluetoothConnected()).isFalse();

        mWifiGlobals.setBluetoothEnabled(true);
        assertThat(mWifiGlobals.isBluetoothConnected()).isFalse();

        mWifiGlobals.setBluetoothConnected(true);
        assertThat(mWifiGlobals.isBluetoothConnected()).isTrue();

        mWifiGlobals.setBluetoothConnected(false);
        assertThat(mWifiGlobals.isBluetoothConnected()).isFalse();

        mWifiGlobals.setBluetoothConnected(true);
        assertThat(mWifiGlobals.isBluetoothConnected()).isTrue();
    }

    /** Verify SAE Hash-to-Element overlay. */
    @Test
    public void testSaeH2eSupportOverlay() {
        mResources.setBoolean(R.bool.config_wifiSaeH2eSupported, false);
        mWifiGlobals = new WifiGlobals(mContext);
        assertFalse(mWifiGlobals.isWpa3SaeH2eSupported());

        mResources.setBoolean(R.bool.config_wifiSaeH2eSupported, true);
        mWifiGlobals = new WifiGlobals(mContext);
        assertTrue(mWifiGlobals.isWpa3SaeH2eSupported());
    }

    /** Verify P2P device name customization. */
    @Test
    public void testP2pDeviceNameCustomization() {
        final String customPrefix = "Custom-";
        final int customPostfixDigit = 5;
        mResources.setString(R.string.config_wifiP2pDeviceNamePrefix, customPrefix);
        mResources.setInteger(R.integer.config_wifiP2pDeviceNamePostfixNumDigits,
                customPostfixDigit);
        mWifiGlobals = new WifiGlobals(mContext);
        assertEquals(customPrefix, mWifiGlobals.getWifiP2pDeviceNamePrefix());
        assertEquals(customPostfixDigit, mWifiGlobals.getWifiP2pDeviceNamePostfixNumDigits());
    }

    /** Test that the number of log records is read from config overlay correctly. */
    @Test
    public void testNumLogRecsNormalIsSetCorrectly() throws Exception {
        assertEquals(200, mWifiGlobals.getClientModeImplNumLogRecs());
    }

    @Test
    public void testSaveFactoryMacToConfigStoreEnabled() throws Exception {
        assertEquals(true, mWifiGlobals.isSaveFactoryMacToConfigStoreEnabled());
    }

    @Test
    public void testQuotedStringSsidPrefixParsedCorrectly() throws Exception {
        assertEquals(1, mWifiGlobals.getMacRandomizationUnsupportedSsidPrefixes().size());
        assertTrue(mWifiGlobals.getMacRandomizationUnsupportedSsidPrefixes()
                .contains(TEST_SSID.substring(0, TEST_SSID.length() - 1)));
    }


    /**
     * Test that isDeprecatedSecurityTypeNetwork returns true due to WEP network
     */
    @Test
    public void testDeprecatedNetworkSecurityTypeWep()
            throws Exception {
        mResources.setBoolean(R.bool.config_wifiWepDeprecated, true);
        mWifiGlobals = new WifiGlobals(mContext);
        assertTrue(mWifiGlobals.isWepDeprecated());

        WifiConfiguration config = new WifiConfiguration();
        config.networkId = TEST_NETWORK_ID;
        config.SSID = TEST_SSID;
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_WEP);

        assertTrue(mWifiGlobals.isDeprecatedSecurityTypeNetwork(config));
    }

    /**
     * Test that isDeprecatedSecurityTypeNetwork returns true due to WPA-Personal network
     */
    @Test
    public void testDeprecatedNetworkSecurityTypeWpaPersonal()
            throws Exception {
        mResources.setBoolean(R.bool.config_wifiWpaPersonalDeprecated, true);
        mWifiGlobals = new WifiGlobals(mContext);
        assertTrue(mWifiGlobals.isWpaPersonalDeprecated());

        WifiConfiguration config = new WifiConfiguration();
        config.networkId = TEST_NETWORK_ID;
        config.SSID = TEST_SSID;
        config.setSecurityParams(WifiConfiguration.SECURITY_TYPE_PSK);
        config.allowedProtocols.set(WifiConfiguration.Protocol.WPA);
        config.allowedProtocols.clear(WifiConfiguration.Protocol.RSN);

        assertTrue(mWifiGlobals.isDeprecatedSecurityTypeNetwork(config));
    }
}
