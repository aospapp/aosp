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

package android.ondevicepersonalization.aidl;

import android.ondevicepersonalization.aidl.IExecuteCallback;
import android.ondevicepersonalization.aidl.IRequestSurfacePackageCallback;
import android.os.Bundle;

/** @hide */
interface IOnDevicePersonalizationManagingService {
    String getVersion();
    void execute(
        in String callingPackageName,
        in String servicePackageName,
        in PersistableBundle params,
        in IExecuteCallback callback);

    void requestSurfacePackage(
        in String slotResultToken,
        in IBinder hostToken,
        int displayId,
        int width,
        int height,
        in IRequestSurfacePackageCallback callback);
}
