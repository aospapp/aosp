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

package android.app.sdksandbox;

import android.os.Bundle;
import android.os.IBinder;

import android.app.sdksandbox.ILoadSdkCallback;
import android.app.sdksandbox.IRequestSurfacePackageCallback;
import android.app.sdksandbox.ISdkSandboxProcessDeathCallback;
import android.app.sdksandbox.ISharedPreferencesSyncCallback;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SharedPreferencesUpdate;

/** @hide */
interface ISdkSandboxManager {
    /**
    * TODO(b/267994332): Add enum for method calls from SDK for latency metrics
    * List of methods for which latencies are logged with logLatencyFromSystemServerToApp
    */
    const String LOAD_SDK = "LOAD_SDK";
    const String REQUEST_SURFACE_PACKAGE = "REQUEST_SURFACE_PACKAGE";

    void addSdkSandboxProcessDeathCallback(in String callingPackageName, long timeAppCalledSystemServer, in ISdkSandboxProcessDeathCallback callback);
    void removeSdkSandboxProcessDeathCallback(in String callingPackageName, long timeAppCalledSystemServer, in ISdkSandboxProcessDeathCallback callback);
    oneway void loadSdk(in String callingPackageName, in IBinder appProcessToken, in String sdkName, long timeAppCalledSystemServer, in Bundle params, in ILoadSdkCallback callback);
    void unloadSdk(in String callingPackageName, in String sdkName, long timeAppCalledSystemServer);
    // TODO(b/242031240): wrap the many input params in one parcelable object
    oneway void requestSurfacePackage(in String callingPackageName, in String sdkName, in IBinder hostToken, int displayId, int width, int height, long timeAppCalledSystemServer, in Bundle params, IRequestSurfacePackageCallback callback);
    List<SandboxedSdk> getSandboxedSdks(in String callingPackageName, long timeAppCalledSystemServer);
    oneway void syncDataFromClient(in String callingPackageName, long timeAppCalledSystemServer, in SharedPreferencesUpdate update, in ISharedPreferencesSyncCallback callback);
    void stopSdkSandbox(in String callingPackageName);
    void logLatencyFromSystemServerToApp(in String method, int latency);

    // TODO(b/282239822): Remove this workaround on Android VIC
    IBinder getAdServicesManager();
}
