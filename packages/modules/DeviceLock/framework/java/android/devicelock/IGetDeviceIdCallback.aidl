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

package android.devicelock;

/**
  * Callback for a getDeviceId() request.
  * {@hide}
  */
oneway interface IGetDeviceIdCallback {
    void onDeviceIdReceived(int type, in String id);

    const int ERROR_UNKNOWN = 0;
    const int ERROR_SECURITY = 1;
    const int ERROR_INVALID_DEVICE_ID_TYPE_BITMAP = 2;
    const int ERROR_CANNOT_GET_DEVICE_ID = 3;

    void onError(int error);
}
