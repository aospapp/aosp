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

package com.android.car.dialer.livedata;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;

import androidx.lifecycle.MediatorLiveData;

import com.android.car.dialer.R;
import com.android.car.dialer.log.L;
import com.android.car.dialer.telecom.UiBluetoothMonitor;

import java.util.Set;

/**
 * A {@link androidx.lifecycle.LiveData<String>} that has a string describing the current bluetooth
 * error. If there is no error, its value will be {@link #NO_BT_ERROR}.
 */
public class BluetoothErrorStringLiveData extends MediatorLiveData<String> {
    private static final String TAG = "CD.BluetoothErrorStringLiveData";

    public static final String NO_BT_ERROR = "NO_ERROR";

    private Context mContext;

    private BluetoothHfpStateLiveData mHfpStateLiveData;
    private BluetoothPairListLiveData mPairListLiveData;
    private BluetoothStateLiveData mBluetoothStateLiveData;

    /**
     * Creates {@link BluetoothErrorStringLiveData}.
     *
     * @param context A context for getting strings.
     */
    public static BluetoothErrorStringLiveData get(Context context) {
        return new BluetoothErrorStringLiveData(context);
    }

    private BluetoothErrorStringLiveData(Context context) {
        mContext = context.getApplicationContext();

        if (BluetoothAdapter.getDefaultAdapter() == null) {
            setValue(mContext.getString(R.string.bluetooth_unavailable));
        } else {
            setValue(NO_BT_ERROR);
            UiBluetoothMonitor uiBluetoothMonitor = UiBluetoothMonitor.get();
            mHfpStateLiveData = uiBluetoothMonitor.getHfpStateLiveData();
            mPairListLiveData = uiBluetoothMonitor.getPairListLiveData();
            mBluetoothStateLiveData = uiBluetoothMonitor.getBluetoothStateLiveData();

            addSource(mHfpStateLiveData, this::onHfpStateChanged);
            addSource(mPairListLiveData, this::onPairListChanged);
            addSource(mBluetoothStateLiveData, this::onBluetoothStateChanged);
        }
    }

    private void onHfpStateChanged(Integer state) {
        update();
    }

    private void onPairListChanged(Set<BluetoothDevice> pairedDevices) {
        update();
    }

    private void onBluetoothStateChanged(Integer state) {
        update();
    }

    private void update() {
        boolean isBluetoothEnabled = isBluetoothEnabled();
        boolean hasPairedDevices = hasPairedDevices();
        boolean isHfpConnected = isHfpConnected();
        L.d(TAG, "Update error string."
                        + " isBluetoothEnabled: %s"
                        + " hasPairedDevices: %s"
                        + " isHfpConnected: %s",
                isBluetoothEnabled,
                hasPairedDevices,
                isHfpConnected);
        if (isHfpConnected) {
            if (!NO_BT_ERROR.equals(getValue())) {
                setValue(NO_BT_ERROR);
            }
        } else if (!isBluetoothEnabled) {
            setValue(mContext.getString(R.string.bluetooth_disabled));
        } else if (!hasPairedDevices) {
            setValue(mContext.getString(R.string.bluetooth_unpaired));
        } else {
            setValue(mContext.getString(R.string.no_hfp));
        }
    }

    private boolean isHfpConnected() {
        Integer hfpState = mHfpStateLiveData.getValue();
        return hfpState == null || hfpState == BluetoothProfile.STATE_CONNECTED;
    }

    private boolean isBluetoothEnabled() {
        Integer bluetoothState = mBluetoothStateLiveData.getValue();
        return bluetoothState == null
                || bluetoothState != BluetoothStateLiveData.BluetoothState.DISABLED;
    }

    private boolean hasPairedDevices() {
        Set<BluetoothDevice> pairedDevices = mPairListLiveData.getValue();
        return pairedDevices == null || !pairedDevices.isEmpty();
    }
}
