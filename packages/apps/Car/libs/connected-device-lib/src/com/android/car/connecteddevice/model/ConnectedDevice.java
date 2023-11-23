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
 * View model representing a connected device.
 */
public class ConnectedDevice {

    private final String mDeviceId;

    private final String mDeviceName;

    private final boolean mBelongsToActiveUser;

    private final boolean mHasSecureChannel;

    /**
     * Create a new connected device.
     *
     * @param deviceId Id of the connected device.
     * @param deviceName Name of the connected device. {@code null} if not known.
     * @param belongsToActiveUser User associated with this device is currently in the foreground.
     * @param hasSecureChannel {@code true} if a secure channel is available for this device.
     */
    public ConnectedDevice(@NonNull String deviceId, @Nullable String deviceName,
            boolean belongsToActiveUser, boolean hasSecureChannel) {
        mDeviceId = deviceId;
        mDeviceName = deviceName;
        mBelongsToActiveUser = belongsToActiveUser;
        mHasSecureChannel = hasSecureChannel;
    }

    /** Returns the id for this device. */
    @NonNull
    public String getDeviceId() {
        return mDeviceId;
    }

    /** Returns the name for this device or {@code null} if not known. */
    @Nullable
    public String getDeviceName() {
        return mDeviceName;
    }

    /**
     * Returns {@code true} if this device is associated with the user currently in the foreground.
     */
    public boolean isAssociatedWithActiveUser() {
        return mBelongsToActiveUser;
    }

    /** Returns {@code true} if this device has a secure channel available. */
    public boolean hasSecureChannel() {
        return mHasSecureChannel;
    }

    @Override
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }
        if (!(obj instanceof ConnectedDevice)) {
            return false;
        }
        ConnectedDevice connectedDevice = (ConnectedDevice) obj;
        return Objects.equals(mDeviceId, connectedDevice.mDeviceId)
                && Objects.equals(mDeviceName, connectedDevice.mDeviceName)
                && mBelongsToActiveUser == connectedDevice.mBelongsToActiveUser
                && mHasSecureChannel == connectedDevice.mHasSecureChannel;
    }

    @Override
    public int hashCode() {
        return Objects.hash(mDeviceId, mDeviceName, mBelongsToActiveUser, mHasSecureChannel);
    }
}
