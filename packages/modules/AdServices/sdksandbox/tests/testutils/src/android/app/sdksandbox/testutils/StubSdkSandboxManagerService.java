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

package android.app.sdksandbox.testutils;

import android.app.sdksandbox.ILoadSdkCallback;
import android.app.sdksandbox.IRequestSurfacePackageCallback;
import android.app.sdksandbox.ISdkSandboxManager;
import android.app.sdksandbox.ISdkSandboxProcessDeathCallback;
import android.app.sdksandbox.ISharedPreferencesSyncCallback;
import android.app.sdksandbox.SandboxedSdk;
import android.app.sdksandbox.SharedPreferencesUpdate;
import android.os.Bundle;
import android.os.IBinder;
import android.os.RemoteException;

import java.util.Collections;
import java.util.List;

/**
 * A stub implementation for {@link ISdkSandboxManager}.
 *
 * <p>Extend and override methods as needed for your tests.
 */
public class StubSdkSandboxManagerService extends ISdkSandboxManager.Stub {
    private IBinder mAdServicesManager;

    @Override
    public void loadSdk(
            String callingPackageName,
            IBinder clientApplicationThreadBinder,
            String sdkName,
            long timeAppCalledSystemServer,
            Bundle params,
            ILoadSdkCallback callback) {}

    @Override
    public void unloadSdk(
            String callingPackageName, String sdkName, long timeAppCalledSystemServer) {}

    @Override
    public void requestSurfacePackage(
            String callingPackageName,
            String sdkName,
            IBinder hostToken,
            int displayId,
            int width,
            int height,
            long timeAppCalledSystemServer,
            Bundle params,
            IRequestSurfacePackageCallback callback) {}

    @Override
    public List<SandboxedSdk> getSandboxedSdks(
            String callingPackageName, long timeAppCalledSystemServer) {
        return Collections.emptyList();
    }

    @Override
    public void stopSdkSandbox(String callingPackageName) throws RemoteException {}

    @Override
    public void syncDataFromClient(
            String callingPackageName,
            long timeAppCalledSystemServer,
            SharedPreferencesUpdate update,
            ISharedPreferencesSyncCallback callback) {}

    @Override
    public void addSdkSandboxProcessDeathCallback(
            String callingPackageName,
            long timeAppCalledSystemServer,
            ISdkSandboxProcessDeathCallback callback) {}

    @Override
    public void removeSdkSandboxProcessDeathCallback(
            String callingPackageName,
            long timeAppCalledSystemServer,
            ISdkSandboxProcessDeathCallback callback) {}

    @Override
    public void logLatencyFromSystemServerToApp(String method, int latency) {}

    @Override
    public IBinder getAdServicesManager() {
        return mAdServicesManager;
    }
}
