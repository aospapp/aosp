/*
 * Copyright (C) 2023 The Android Open Source Project
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
package com.android.server.ondevicepersonalization;

import static android.app.ondevicepersonalization.OnDevicePersonalizationSystemServiceManager.ON_DEVICE_PERSONALIZATION_SYSTEM_SERVICE;

import android.app.ondevicepersonalization.IOnDevicePersonalizationSystemService;
import android.app.ondevicepersonalization.IOnDevicePersonalizationSystemServiceCallback;
import android.content.Context;
import android.os.Bundle;
import android.os.RemoteException;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.server.SystemService;

/**
 * @hide
 */
public class OnDevicePersonalizationSystemService
        extends IOnDevicePersonalizationSystemService.Stub {
    private static final String TAG = "OnDevicePersonalizationSystemService";

    @VisibleForTesting
    OnDevicePersonalizationSystemService(Context context) {
    }

    @Override public void onRequest(
            Bundle bundle,
            IOnDevicePersonalizationSystemServiceCallback callback) {
        try {
            callback.onResult(null);
        } catch (RemoteException e) {
            Log.e(TAG, "Callback error", e);
        }
    }

    /** @hide */
    public static class Lifecycle extends SystemService {
        private OnDevicePersonalizationSystemService mService;

        /** @hide */
        public Lifecycle(Context context) {
            super(context);
            mService = new OnDevicePersonalizationSystemService(getContext());
        }

        /** @hide */
        @Override
        public void onStart() {
            publishBinderService(ON_DEVICE_PERSONALIZATION_SYSTEM_SERVICE, mService);
            Log.i(TAG, "OnDevicePersonalizationSystemService started!");
        }
    }
}
