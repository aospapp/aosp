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

package com.android.car.settings.qc;

import static com.android.car.qc.QCItem.QC_ACTION_TOGGLE_STATE;
import static com.android.car.qc.QCItem.QC_TYPE_ACTION_SWITCH;
import static com.android.car.settings.qc.SettingsQCRegistry.HOTSPOT_ROW_URI;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.TetheringManager;
import android.net.Uri;
import android.net.wifi.SoftApConfiguration;
import android.net.wifi.WifiManager;
import android.text.TextUtils;

import com.android.car.qc.QCActionItem;
import com.android.car.qc.QCItem;
import com.android.car.qc.QCList;
import com.android.car.qc.QCRow;
import com.android.car.settings.R;

/**
 * QCItem for showing a hotspot row element.
 * The row contains an icon, the status, and a switch to enable/disable hotspot.
 */
public class HotspotRow extends SettingsQCItem {
    private final TetheringManager mTetheringManager;
    private final WifiManager mWifiManager;
    // Assume hotspot is available until notified otherwise.
    private boolean mIsSupported = true;
    private int mConnectedDevicesCount;

    public HotspotRow(Context context) {
        super(context);
        mTetheringManager = context.getSystemService(TetheringManager.class);
        mWifiManager = context.getSystemService(WifiManager.class);
    }

    @Override
    QCItem getQCItem() {
        Icon icon = Icon.createWithResource(getContext(), R.drawable.ic_qc_hotspot);

        QCActionItem hotpotToggle = new QCActionItem.Builder(QC_TYPE_ACTION_SWITCH)
                .setChecked(HotspotQCUtils.isHotspotEnabled(mWifiManager))
                .setEnabled(!HotspotQCUtils.isHotspotBusy(mWifiManager))
                .setAvailable(mIsSupported)
                .setAction(getBroadcastIntent())
                .build();

        QCRow hotspotRow = new QCRow.Builder()
                .setIcon(icon)
                .setTitle(getContext().getString(R.string.hotspot_settings_title))
                .setSubtitle(getSubtitle())
                .addEndItem(hotpotToggle)
                .build();

        return new QCList.Builder()
                .addRow(hotspotRow)
                .build();
    }

    @Override
    Uri getUri() {
        return HOTSPOT_ROW_URI;
    }

    boolean getHotspotSupported() {
        return mIsSupported;
    }

    void setHotspotSupported(boolean supported) {
        mIsSupported = supported;
    }

    void setConnectedDevicesCount(int devicesCount) {
        mConnectedDevicesCount = devicesCount;
    }

    @Override
    void onNotifyChange(Intent intent) {
        boolean newState = intent.getBooleanExtra(QC_ACTION_TOGGLE_STATE,
                !mWifiManager.isWifiApEnabled());
        if (newState) {
            HotspotQCUtils.enableHotspot(mTetheringManager,
                    HotspotQCUtils.getDefaultStartTetheringCallback(getContext(), getUri()));
        } else {
            HotspotQCUtils.disableHotspot(mTetheringManager);
        }
    }

    @Override
    Class getBackgroundWorkerClass() {
        return HotspotRowWorker.class;
    }

    /** Returns the subtitle to be shown for the hotspot quick controls item.
     * There are three different states that can be shown:
     * - If tethering is disabled, return the off string.
     * - If tethering is enabled but no devices are connected, return the ssid + password string.
     * - If tethering is enabled and devices are connected, return the devices connected string.
     */
    private String getSubtitle() {
        if (!HotspotQCUtils.isHotspotEnabled(mWifiManager)) {
            return getContext().getString(R.string.wifi_hotspot_state_off);
        }
        if (mConnectedDevicesCount > 0) {
            return getContext().getResources().getQuantityString(
                    R.plurals.wifi_tether_connected_summary, mConnectedDevicesCount,
                    mConnectedDevicesCount);
        }
        String subtitle = getHotspotSSID();
        if (TextUtils.isEmpty(subtitle)) {
            // If there currently is no SSID to show, use a default "On" string
            return getContext().getString(R.string.car_ui_preference_switch_on);
        }
        String password = getHotspotPassword();
        if (!TextUtils.isEmpty(password)) {
            subtitle += " / " + password;
        }
        return subtitle;
    }

    private String getHotspotSSID() {
        return mWifiManager.getSoftApConfiguration().getSsid();
    }

    private String getHotspotPassword() {
        int securityType = mWifiManager.getSoftApConfiguration().getSecurityType();
        if (securityType == SoftApConfiguration.SECURITY_TYPE_OPEN) {
            return null;
        }
        return mWifiManager.getSoftApConfiguration().getPassphrase();
    }
}
