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
import static com.android.car.settings.qc.SettingsQCRegistry.HOTSPOT_TILE_URI;

import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.net.TetheringManager;
import android.net.Uri;
import android.net.wifi.WifiManager;

import com.android.car.qc.QCItem;
import com.android.car.qc.QCTile;
import com.android.car.settings.R;

/**
 * QCItem for showing a hotspot toggle.
 */
public class HotspotTile extends SettingsQCItem {
    private final TetheringManager mTetheringManager;
    private final WifiManager mWifiManager;
    // Assume hotspot is available until notified otherwise.
    private boolean mIsSupported = true;

    public HotspotTile(Context context) {
        super(context);
        mTetheringManager = context.getSystemService(TetheringManager.class);
        mWifiManager = context.getSystemService(WifiManager.class);
    }

    @Override
    QCItem getQCItem() {
        Icon actionIcon = Icon.createWithResource(getContext(), R.drawable.ic_qc_hotspot);

        QCTile.Builder tileBuilder = new QCTile.Builder()
                .setSubtitle(getContext().getString(R.string.hotspot_settings_title))
                .setIcon(actionIcon)
                .setChecked(HotspotQCUtils.isHotspotEnabled(mWifiManager))
                .setEnabled(!HotspotQCUtils.isHotspotBusy(mWifiManager))
                .setAvailable(mIsSupported)
                .setAction(getBroadcastIntent());
        return tileBuilder.build();
    }

    @Override
    Uri getUri() {
        return HOTSPOT_TILE_URI;
    }

    boolean getHotspotSupported() {
        return mIsSupported;
    }

    void setHotspotSupported(boolean supported) {
        mIsSupported = supported;
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
        return HotspotTileWorker.class;
    }
}
