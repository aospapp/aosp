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

package com.android.server.wifi.util;

import android.hardware.wifi.supplicant.KeyMgmtMask;
import android.net.wifi.WifiConfiguration;

import java.util.BitSet;

/**
 * Provide utility functions for HAL AIDL implementation.
 */
public class HalAidlUtil {
    private static int supplicantMaskValueToWifiConfigurationBitSet(int supplicantMask,
            int supplicantValue, BitSet bitset, int bitSetPosition) {
        bitset.set(bitSetPosition, (supplicantMask & supplicantValue) == supplicantValue);
        int modifiedSupplicantMask = supplicantMask & ~supplicantValue;
        return modifiedSupplicantMask;
    }

    /** Convert supplicant key management mask to framework key management mask. */
    public static BitSet supplicantToWifiConfigurationKeyMgmtMask(int mask) {
        BitSet bitset = new BitSet();
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.NONE, bitset,
                WifiConfiguration.KeyMgmt.NONE);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.WPA_PSK, bitset,
                WifiConfiguration.KeyMgmt.WPA_PSK);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.WPA_EAP, bitset,
                WifiConfiguration.KeyMgmt.WPA_EAP);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.IEEE8021X, bitset,
                WifiConfiguration.KeyMgmt.IEEE8021X);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.OSEN, bitset,
                WifiConfiguration.KeyMgmt.OSEN);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.FT_PSK, bitset,
                WifiConfiguration.KeyMgmt.FT_PSK);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.FT_EAP, bitset,
                WifiConfiguration.KeyMgmt.FT_EAP);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.SAE,
                bitset, WifiConfiguration.KeyMgmt.SAE);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.OWE,
                bitset, WifiConfiguration.KeyMgmt.OWE);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.SUITE_B_192,
                bitset, WifiConfiguration.KeyMgmt.SUITE_B_192);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.WPA_PSK_SHA256,
                bitset, WifiConfiguration.KeyMgmt.WPA_PSK_SHA256);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.WPA_EAP_SHA256,
                bitset, WifiConfiguration.KeyMgmt.WPA_EAP_SHA256);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.WAPI_PSK,
                bitset, WifiConfiguration.KeyMgmt.WAPI_PSK);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.WAPI_CERT,
                bitset, WifiConfiguration.KeyMgmt.WAPI_CERT);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.FILS_SHA256,
                bitset, WifiConfiguration.KeyMgmt.FILS_SHA256);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.FILS_SHA384,
                bitset, WifiConfiguration.KeyMgmt.FILS_SHA384);
        mask = supplicantMaskValueToWifiConfigurationBitSet(
                mask, KeyMgmtMask.DPP,
                bitset, WifiConfiguration.KeyMgmt.DPP);
        if (mask != 0) {
            throw new IllegalArgumentException(
                    "invalid key mgmt mask from supplicant: " + mask);
        }
        return bitset;
    }
}
