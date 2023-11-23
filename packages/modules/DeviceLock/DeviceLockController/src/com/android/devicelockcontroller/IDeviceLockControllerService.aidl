/**
 * Copyright (c) 2022, The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.devicelockcontroller;

import android.os.RemoteCallback;

/**
 * Binder interface to communicate with DeviceLockController.
 * {@hide}
 */
oneway interface IDeviceLockControllerService {
    const String KEY_LOCK_DEVICE_RESULT = "KEY_LOCK_DEVICE_RESULT";
    void lockDevice(in RemoteCallback callback);

    const String KEY_UNLOCK_DEVICE_RESULT = "KEY_UNLOCK_DEVICE_RESULT";
    void unlockDevice(in RemoteCallback callback);

    const String KEY_IS_DEVICE_LOCKED_RESULT = "KEY_IS_DEVICE_LOCKED_RESULT";
    void isDeviceLocked(in RemoteCallback callback);

    const String KEY_HARDWARE_ID_RESULT = "KEY_HARDWARE_ID_RESULT";
    void getDeviceIdentifier(in RemoteCallback callback);

    const String KEY_CLEAR_DEVICE_RESULT = "KEY_CLEAR_DEVICE_RESULT";
    void clearDeviceRestrictions(in RemoteCallback callback);
}
