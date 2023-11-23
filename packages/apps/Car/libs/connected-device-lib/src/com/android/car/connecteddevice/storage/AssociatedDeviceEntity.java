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

package com.android.car.connecteddevice.storage;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.room.Entity;
import androidx.room.PrimaryKey;

import com.android.car.connecteddevice.model.AssociatedDevice;

/** Table entity representing an associated device. */
@Entity(tableName = "associated_devices")
public class AssociatedDeviceEntity {

    /** Id of the device. */
    @PrimaryKey
    @NonNull
    public String id;

    /** Id of user associated with this device. */
    public int userId;

    /** Bluetooth address of the device. */
    @Nullable
    public String address;

    /** Bluetooth device name. */
    @Nullable
    public String name;

    /** {@code true} if the connection is enabled for this device.*/
    public boolean isConnectionEnabled;

    public AssociatedDeviceEntity() { }

    public AssociatedDeviceEntity(int userId, AssociatedDevice associatedDevice,
            boolean isConnectionEnabled) {
        this.userId = userId;
        id = associatedDevice.getDeviceId();
        address = associatedDevice.getDeviceAddress();
        name = associatedDevice.getDeviceName();
        this.isConnectionEnabled = isConnectionEnabled;
    }

    /** Return a new {@link AssociatedDevice} of this entity. */
    public AssociatedDevice toAssociatedDevice() {
        return new AssociatedDevice(id, address, name, isConnectionEnabled);
    }
}
