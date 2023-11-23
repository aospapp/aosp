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

import android.annotation.Nullable;
import android.content.Context;
import android.net.wifi.WifiConfiguration;
import android.util.ArraySet;

import com.android.modules.utils.build.SdkLevel;
import com.android.wifi.resources.R;

import java.io.FileDescriptor;
import java.io.PrintWriter;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import javax.annotation.concurrent.ThreadSafe;


/** Global wifi service in-memory state that is not persisted. */
@ThreadSafe
public class WifiGlobals {

    private final Context mContext;

    private final AtomicInteger mPollRssiIntervalMillis = new AtomicInteger(-1);
    private final AtomicBoolean mIpReachabilityDisconnectEnabled = new AtomicBoolean(true);
    private final AtomicBoolean mIsBluetoothConnected = new AtomicBoolean(false);

    // These are read from the overlay, cache them after boot up.
    private final boolean mIsWpa3SaeUpgradeEnabled;
    private final boolean mIsWpa3SaeUpgradeOffloadEnabled;
    private final boolean mIsOweUpgradeEnabled;
    private final boolean mFlushAnqpCacheOnWifiToggleOffEvent;
    private final boolean mIsWpa3SaeH2eSupported;
    private final String mP2pDeviceNamePrefix;
    private final int mP2pDeviceNamePostfixNumDigits;
    private final int mClientModeImplNumLogRecs;
    private final boolean mSaveFactoryMacToConfigStoreEnabled;
    private final int mWifiLowConnectedScoreThresholdToTriggerScanForMbb;
    private final int mWifiLowConnectedScoreScanPeriodSeconds;
    private final boolean mWifiAllowInsecureEnterpriseConfiguration;
    private final boolean mIsP2pMacRandomizationSupported;
    private final int mPollRssiShortIntervalMillis;
    private final int mPollRssiLongIntervalMillis;
    private final int mClientRssiMonitorThresholdDbm;
    private final int mClientRssiMonitorHysteresisDb;
    private final boolean mAdjustPollRssiIntervalEnabled;
    private final boolean mWifiInterfaceAddedSelfRecoveryEnabled;
    private final int mNetworkNotFoundEventThreshold;
    private final boolean mIsWepDeprecated;
    private final boolean mIsWpaPersonalDeprecated;

    // This is set by WifiManager#setVerboseLoggingEnabled(int).
    private boolean mIsShowKeyVerboseLoggingModeEnabled = false;
    private boolean mIsUsingExternalScorer = false;
    private boolean mDisableUnwantedNetworkOnLowRssi = false;
    private Set<String> mMacRandomizationUnsupportedSsidPrefixes = new ArraySet<>();

    public WifiGlobals(Context context) {
        mContext = context;

        mIsWpa3SaeUpgradeEnabled = mContext.getResources()
                .getBoolean(R.bool.config_wifiSaeUpgradeEnabled);
        mIsWpa3SaeUpgradeOffloadEnabled = mContext.getResources()
                .getBoolean(R.bool.config_wifiSaeUpgradeOffloadEnabled);
        mIsOweUpgradeEnabled = mContext.getResources()
                .getBoolean(R.bool.config_wifiOweUpgradeEnabled);
        mFlushAnqpCacheOnWifiToggleOffEvent = mContext.getResources()
                .getBoolean(R.bool.config_wifiFlushAnqpCacheOnWifiToggleOffEvent);
        mIsWpa3SaeH2eSupported = mContext.getResources()
                .getBoolean(R.bool.config_wifiSaeH2eSupported);
        mP2pDeviceNamePrefix = mContext.getResources()
                .getString(R.string.config_wifiP2pDeviceNamePrefix);
        mP2pDeviceNamePostfixNumDigits = mContext.getResources()
                .getInteger(R.integer.config_wifiP2pDeviceNamePostfixNumDigits);
        mClientModeImplNumLogRecs = mContext.getResources()
                .getInteger(R.integer.config_wifiClientModeImplNumLogRecs);
        mSaveFactoryMacToConfigStoreEnabled = mContext.getResources()
                .getBoolean(R.bool.config_wifiSaveFactoryMacToWifiConfigStore);
        mWifiLowConnectedScoreThresholdToTriggerScanForMbb = mContext.getResources().getInteger(
                R.integer.config_wifiLowConnectedScoreThresholdToTriggerScanForMbb);
        mWifiLowConnectedScoreScanPeriodSeconds = mContext.getResources().getInteger(
                R.integer.config_wifiLowConnectedScoreScanPeriodSeconds);
        mWifiAllowInsecureEnterpriseConfiguration = mContext.getResources().getBoolean(
                R.bool.config_wifiAllowInsecureEnterpriseConfigurationsForSettingsAndSUW);
        mIsP2pMacRandomizationSupported = mContext.getResources().getBoolean(
                R.bool.config_wifi_p2p_mac_randomization_supported);
        mPollRssiIntervalMillis.set(mContext.getResources().getInteger(
                R.integer.config_wifiPollRssiIntervalMilliseconds));
        mPollRssiShortIntervalMillis = mContext.getResources().getInteger(
                R.integer.config_wifiPollRssiIntervalMilliseconds);
        mPollRssiLongIntervalMillis = mContext.getResources().getInteger(
                R.integer.config_wifiPollRssiLongIntervalMilliseconds);
        mClientRssiMonitorThresholdDbm = mContext.getResources().getInteger(
                R.integer.config_wifiClientRssiMonitorThresholdDbm);
        mClientRssiMonitorHysteresisDb = mContext.getResources().getInteger(
                R.integer.config_wifiClientRssiMonitorHysteresisDb);
        mAdjustPollRssiIntervalEnabled = mContext.getResources().getBoolean(
                R.bool.config_wifiAdjustPollRssiIntervalEnabled);
        mWifiInterfaceAddedSelfRecoveryEnabled = mContext.getResources().getBoolean(
                R.bool.config_wifiInterfaceAddedSelfRecoveryEnabled);
        mDisableUnwantedNetworkOnLowRssi = mContext.getResources().getBoolean(
                R.bool.config_wifiDisableUnwantedNetworkOnLowRssi);
        mNetworkNotFoundEventThreshold = mContext.getResources().getInteger(
                R.integer.config_wifiNetworkNotFoundEventThreshold);
        mIsWepDeprecated = mContext.getResources()
                .getBoolean(R.bool.config_wifiWepDeprecated);
        mIsWpaPersonalDeprecated = mContext.getResources()
                .getBoolean(R.bool.config_wifiWpaPersonalDeprecated);
        Set<String> unsupportedSsidPrefixes = new ArraySet<>(mContext.getResources().getStringArray(
                R.array.config_wifiForceDisableMacRandomizationSsidPrefixList));
        if (!unsupportedSsidPrefixes.isEmpty()) {
            for (String ssid : unsupportedSsidPrefixes) {
                String cleanedSsid = ssid.length() > 1 && (ssid.charAt(0) == '"')
                        && (ssid.charAt(ssid.length() - 1) == '"')
                        ? ssid.substring(0, ssid.length() - 1) : ssid;
                mMacRandomizationUnsupportedSsidPrefixes.add(cleanedSsid);
            }
        }
    }

    public Set<String> getMacRandomizationUnsupportedSsidPrefixes() {
        return mMacRandomizationUnsupportedSsidPrefixes;
    }

    /** Get the interval between RSSI polls, in milliseconds. */
    public int getPollRssiIntervalMillis() {
        return mPollRssiIntervalMillis.get();
    }

    /** Set the interval between RSSI polls, in milliseconds. */
    public void setPollRssiIntervalMillis(int newPollIntervalMillis) {
        mPollRssiIntervalMillis.set(newPollIntervalMillis);
    }

    /** Returns whether CMD_IP_REACHABILITY_LOST events should trigger disconnects. */
    public boolean getIpReachabilityDisconnectEnabled() {
        return mIpReachabilityDisconnectEnabled.get();
    }

    /** Sets whether CMD_IP_REACHABILITY_LOST events should trigger disconnects. */
    public void setIpReachabilityDisconnectEnabled(boolean enabled) {
        mIpReachabilityDisconnectEnabled.set(enabled);
    }

    /** Set whether bluetooth is enabled. */
    public void setBluetoothEnabled(boolean isEnabled) {
        // If BT was connected and then turned off, there is no CONNECTION_STATE_CHANGE message.
        // So set mIsBluetoothConnected to false if we get a bluetooth disable while connected.
        // But otherwise, Bluetooth being turned on doesn't mean that we're connected.
        if (!isEnabled) {
            mIsBluetoothConnected.set(false);
        }
    }

    /** Set whether bluetooth is connected. */
    public void setBluetoothConnected(boolean isConnected) {
        mIsBluetoothConnected.set(isConnected);
    }

    /** Get whether bluetooth is connected */
    public boolean isBluetoothConnected() {
        return mIsBluetoothConnected.get();
    }

    /**
     * Helper method to check if Connected MAC Randomization is supported - onDown events are
     * skipped if this feature is enabled (b/72459123).
     *
     * @return boolean true if Connected MAC randomization is supported, false otherwise
     */
    public boolean isConnectedMacRandomizationEnabled() {
        return mContext.getResources().getBoolean(
                R.bool.config_wifi_connected_mac_randomization_supported);
    }

    /**
     * Helper method to check if WEP networks are deprecated.
     *
     * @return boolean true if WEP networks are deprecated, false otherwise.
     */
    public boolean isWepDeprecated() {
        return mIsWepDeprecated;
    }

    /**
     * Helper method to check if WPA-Personal networks are deprecated.
     *
     * @return boolean true if WPA-Personal networks are deprecated, false otherwise.
     */
    public boolean isWpaPersonalDeprecated() {
        return mIsWpaPersonalDeprecated;
    }

    /**
     * Helper method to check if the device may not connect to the configuration
     * due to deprecated security type
     */
    public boolean isDeprecatedSecurityTypeNetwork(@Nullable WifiConfiguration config) {
        if (config == null) {
            return false;
        }
        if (isWepDeprecated() && config.isSecurityType(WifiConfiguration.SECURITY_TYPE_WEP)) {
            return true;
        }
        if (isWpaPersonalDeprecated() && config.isWpaPersonalOnlyConfiguration()) {
            return true;
        }
        return false;
    }

    /**
     * Help method to check if WPA3 SAE auto-upgrade is enabled.
     *
     * @return boolean true if auto-upgrade is enabled, false otherwise.
     */
    public boolean isWpa3SaeUpgradeEnabled() {
        return mIsWpa3SaeUpgradeEnabled;
    }

    /**
     * Help method to check if WPA3 SAE auto-upgrade offload is enabled.
     *
     * @return boolean true if auto-upgrade offload is enabled, false otherwise.
     */
    public boolean isWpa3SaeUpgradeOffloadEnabled() {
        return mIsWpa3SaeUpgradeOffloadEnabled;
    }

    /**
     * Help method to check if OWE auto-upgrade is enabled.
     *
     * @return boolean true if auto-upgrade is enabled, false otherwise.
     */
    public boolean isOweUpgradeEnabled() {
        // OWE auto-upgrade is supported on S or newer releases.
        return SdkLevel.isAtLeastS() && mIsOweUpgradeEnabled;
    }

    /**
     * Help method to check if the setting to flush ANQP cache when Wi-Fi is toggled off.
     *
     * @return boolean true to flush ANQP cache on Wi-Fi toggle off event, false otherwise.
     */
    public boolean flushAnqpCacheOnWifiToggleOffEvent() {
        return mFlushAnqpCacheOnWifiToggleOffEvent;
    }

    /*
     * Help method to check if WPA3 SAE Hash-to-Element is supported on this device.
     *
     * @return boolean true if supported;otherwise false.
     */
    public boolean isWpa3SaeH2eSupported() {
        return mIsWpa3SaeH2eSupported;
    }

    /** Set if show key verbose logging mode is enabled. */
    public void setShowKeyVerboseLoggingModeEnabled(boolean enable) {
        mIsShowKeyVerboseLoggingModeEnabled = enable;
    }

    /** Check if show key verbose logging mode is enabled. */
    public boolean getShowKeyVerboseLoggingModeEnabled() {
        return mIsShowKeyVerboseLoggingModeEnabled;
    }

    /** Set whether the external scorer is being used **/
    public void setUsingExternalScorer(boolean isUsingExternalScorer) {
        mIsUsingExternalScorer = isUsingExternalScorer;
    }

    /** Get whether the external scorer is being used **/
    public boolean isUsingExternalScorer() {
        return mIsUsingExternalScorer;
    }

    /** Get the prefix of the default wifi p2p device name. */
    public String getWifiP2pDeviceNamePrefix() {
        return mP2pDeviceNamePrefix;
    }

    /** Get the number of the default wifi p2p device name postfix digit. */
    public int getWifiP2pDeviceNamePostfixNumDigits() {
        return mP2pDeviceNamePostfixNumDigits;
    }

    /** Get the number of log records to maintain. */
    public int getClientModeImplNumLogRecs() {
        return mClientModeImplNumLogRecs;
    }

    /** Get whether to use the saved factory MAC address when available **/
    public boolean isSaveFactoryMacToConfigStoreEnabled() {
        return mSaveFactoryMacToConfigStoreEnabled;
    }

    /** Get the low score threshold to do scan for MBB when external scorer is not used. **/
    public int getWifiLowConnectedScoreThresholdToTriggerScanForMbb() {
        return mWifiLowConnectedScoreThresholdToTriggerScanForMbb;
    }

    /** Get the minimum period between the extra scans triggered for MBB when score is low **/
    public int getWifiLowConnectedScoreScanPeriodSeconds() {
        return mWifiLowConnectedScoreScanPeriodSeconds;
    }

    /** Get whether or not insecure enterprise configuration is allowed. */
    public boolean isInsecureEnterpriseConfigurationAllowed() {
        return mWifiAllowInsecureEnterpriseConfiguration;
    }

    /** Get whether or not P2P MAC randomization is supported */
    public boolean isP2pMacRandomizationSupported() {
        return mIsP2pMacRandomizationSupported;
    }

    /** Get the regular (short) interval between RSSI polls, in milliseconds. */
    public int getPollRssiShortIntervalMillis() {
        return mPollRssiShortIntervalMillis;
    }

    /**
     * Get the long interval between RSSI polls, in milliseconds. The long interval is to
     * reduce power consumption of the polls. This value should be greater than the regular
     * interval.
     */
    public int getPollRssiLongIntervalMillis() {
        return mPollRssiLongIntervalMillis;
    }

    /**
     * Get the RSSI threshold for client mode RSSI monitor, in dBm. If the device is stationary
     * and current RSSI >= Threshold + Hysteresis value, set long interval and enable RSSI
     * monitoring using the RSSI threshold. If device is non-stationary or current RSSI <=
     * Threshold, set regular interval and disable RSSI monitoring.
     */
    public int getClientRssiMonitorThresholdDbm() {
        return mClientRssiMonitorThresholdDbm;
    }

    /**
     * Get the hysteresis value in dB for the client mode RSSI monitor threshold. It can avoid
     * frequent switch between regular and long polling intervals.
     */
    public int getClientRssiMonitorHysteresisDb() {
        return mClientRssiMonitorHysteresisDb;
    }

    /**
     * Get whether adjusting the RSSI polling interval between regular and long intervals
     * is enabled.
     */
    public boolean isAdjustPollRssiIntervalEnabled() {
        return mAdjustPollRssiIntervalEnabled;
    }

    /**
     * Get whether hot-plugging an interface will trigger a restart of the wifi stack.
     */
    public boolean isWifiInterfaceAddedSelfRecoveryEnabled() {
        return mWifiInterfaceAddedSelfRecoveryEnabled;
    }

    /**
     * Get whether to temporarily disable a unwanted network that has low RSSI.
     */
    public boolean disableUnwantedNetworkOnLowRssi() {
        return mDisableUnwantedNetworkOnLowRssi;
    }

    /**
     * Get the threshold to use for blocking a network due to NETWORK_NOT_FOUND_EVENT failure.
     */
    public int getNetworkNotFoundEventThreshold() {
        return mNetworkNotFoundEventThreshold;
    }

    /** Dump method for debugging */
    public void dump(FileDescriptor fd, PrintWriter pw, String[] args) {
        pw.println("Dump of WifiGlobals");
        pw.println("mPollRssiIntervalMillis=" + mPollRssiIntervalMillis.get());
        pw.println("mIpReachabilityDisconnectEnabled=" + mIpReachabilityDisconnectEnabled.get());
        pw.println("mIsBluetoothConnected=" + mIsBluetoothConnected.get());
        pw.println("mIsWpa3SaeUpgradeEnabled=" + mIsWpa3SaeUpgradeEnabled);
        pw.println("mIsWpa3SaeUpgradeOffloadEnabled=" + mIsWpa3SaeUpgradeOffloadEnabled);
        pw.println("mIsOweUpgradeEnabled=" + mIsOweUpgradeEnabled);
        pw.println("mFlushAnqpCacheOnWifiToggleOffEvent=" + mFlushAnqpCacheOnWifiToggleOffEvent);
        pw.println("mIsWpa3SaeH2eSupported=" + mIsWpa3SaeH2eSupported);
        pw.println("mP2pDeviceNamePrefix=" + mP2pDeviceNamePrefix);
        pw.println("mP2pDeviceNamePostfixNumDigits=" + mP2pDeviceNamePostfixNumDigits);
        pw.println("mClientModeImplNumLogRecs=" + mClientModeImplNumLogRecs);
        pw.println("mSaveFactoryMacToConfigStoreEnabled=" + mSaveFactoryMacToConfigStoreEnabled);
        pw.println("mWifiLowConnectedScoreThresholdToTriggerScanForMbb="
                + mWifiLowConnectedScoreThresholdToTriggerScanForMbb);
        pw.println("mWifiLowConnectedScoreScanPeriodSeconds="
                + mWifiLowConnectedScoreScanPeriodSeconds);
        pw.println("mIsUsingExternalScorer="
                + mIsUsingExternalScorer);
        pw.println("mWifiAllowInsecureEnterpriseConfiguration="
                + mWifiAllowInsecureEnterpriseConfiguration);
        pw.println("mIsP2pMacRandomizationSupported" + mIsP2pMacRandomizationSupported);
        pw.println("mWifiInterfaceAddedSelfRecoveryEnabled="
                + mWifiInterfaceAddedSelfRecoveryEnabled);
        pw.println("mDisableUnwantedNetworkOnLowRssi=" + mDisableUnwantedNetworkOnLowRssi);
        pw.println("mNetworkNotFoundEventThreshold=" + mNetworkNotFoundEventThreshold);
        pw.println("mIsWepDeprecated=" + mIsWepDeprecated);
        pw.println("mIsWpaPersonalDeprecated=" + mIsWpaPersonalDeprecated);
    }
}
