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

package com.android.car.connecteddevice.model;

import android.annotation.NonNull;
import android.annotation.Nullable;

import java.util.Objects;

/**
 * Contains basic info of an associated device.
 */
public class AssociatedDevice {

    private final String mDeviceId;

    private final String mDeviceAddress;

    private final String mDeviceName;

    private final boolean mIsConnectionEnabled;


    /**
     * Create a new AssociatedDevice.
     *
     * @param deviceId Id of the associated device.
     * @param deviceAddress Address of the associated device.
     * @param deviceName Name of the associated device. {@code null} if not known.
     * @param isConnectionEnabled If connection is enabled for this device.
     */
    public AssociatedDevice(@NonNull String deviceId, @NonNull String deviceAddress,
            @Nullable String deviceName, boolean isConnectionEnabled) {
        mDeviceId = deviceId;
        mDeviceAddress = deviceAddress;
        mDeviceName = deviceName;
        mIsConnectionEnabled = isConnectionEnabled;
    }

    /** Returns the id for this device. */
    @NonNull
    public String getDeviceId() {
        return mDeviceId;
    }

    /** Returns the address for this device. */
    @NonNull
    public String getDeviceAddress() {
        return mDeviceAddress;
    }

    /** Returns the name for this device or {@code null} if not known. */
    @Nullable
    public String getDeviceName() {
        return mDeviceName;
    }

    /** Return if connection is enabled for this device. */
    public boolean isConnectionEnabled() {
        return mIsConnectionEnabled;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof AssociatedDevice)) {
            return false;
        }
        AssociatedDevice associatedDevice = (AssociatedDevice) obj;
        return Objects.equals(mDeviceId, associatedDevice.mDeviceId)
                && Objects.equals(mDeviceAddress, associatedDevice.mDeviceAddress)
                && Objects.equals(mDeviceName, associatedDevice.mDeviceName)
                && mIsConnectionEnabled == associatedDevice.mIsConnectionEnabled;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceId, mDeviceAddress, mDeviceName, mIsConnectionEnabled);
    }
}
