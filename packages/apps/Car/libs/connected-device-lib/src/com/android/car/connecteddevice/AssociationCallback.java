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

package com.android.car.connecteddevice;

import android.annotation.NonNull;

/** Callbacks that will be invoked during associating a new client. */
public interface AssociationCallback {

    /**
     * Invoked when IHU starts advertising with its device name for association successfully.
     *
     * @param deviceName The device name to identify the car.
     */
    void onAssociationStartSuccess(@NonNull String deviceName);

    /** Invoked when IHU failed to start advertising for association. */
    void onAssociationStartFailure();

    /**
     * Invoked when a {@link ConnectedDeviceManager.DeviceError} has been encountered in attempting
     * to associate a new device.
     *
     * @param error The failure indication.
     */
    void onAssociationError(@ConnectedDeviceManager.DeviceError int error);

    /**
     * Invoked when a verification code needs to be displayed. The user needs to confirm, and
     * then call {@link ConnectedDeviceManager#notifyOutOfBandAccepted()}.
     *
     * @param code The verification code.
     */
    void onVerificationCodeAvailable(@NonNull String code);

    /**
     * Invoked when the association has completed.
     *
     * @param deviceId The id of the newly associated device.
     */
    void onAssociationCompleted(@NonNull String deviceId);
}
