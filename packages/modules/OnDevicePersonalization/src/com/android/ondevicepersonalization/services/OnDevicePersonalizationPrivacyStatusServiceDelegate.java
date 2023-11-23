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

package com.android.ondevicepersonalization.services;

import android.annotation.NonNull;
import android.content.Context;
import android.ondevicepersonalization.aidl.IPrivacyStatusService;
import android.ondevicepersonalization.aidl.IPrivacyStatusServiceCallback;
import android.os.RemoteException;
import android.util.Log;

import com.android.ondevicepersonalization.services.data.user.PrivacySignal;
import com.android.ondevicepersonalization.services.data.user.RawUserData;
import com.android.ondevicepersonalization.services.data.user.UserDataCollector;

import java.util.Objects;
import java.util.concurrent.Executor;

/**
 * ODP service that modifies and persists user's privacy status.
 */
public class OnDevicePersonalizationPrivacyStatusServiceDelegate
        extends IPrivacyStatusService.Stub {
    private static final String TAG = "OnDevicePersonalizationPrivacyStatusServiceDelegate";
    private final Context mContext;
    private static final Executor sBackgroundExecutor =
            OnDevicePersonalizationExecutors.getBackgroundExecutor();

    public OnDevicePersonalizationPrivacyStatusServiceDelegate(Context context) {
        mContext = context;
    }

    @Override
    public void setKidStatus(boolean kidStatusEnabled,
            @NonNull IPrivacyStatusServiceCallback callback) {
        Objects.requireNonNull(callback);
        // TODO(b/272823829): Verify caller's permission
        // TODO(b/270468742): Call system server for U+ devices
        sBackgroundExecutor.execute(
                () -> {
                    try {
                        PrivacySignal privacySignal = PrivacySignal.getInstance();

                        if (kidStatusEnabled == privacySignal.isKidStatusEnabled()) {
                            callback.onSuccess();
                            return;
                        }

                        privacySignal.setKidStatusEnabled(kidStatusEnabled);
                        // Rollback all user data if kid status changes
                        RawUserData userData = RawUserData.getInstance();
                        UserDataCollector userDataCollector =
                                UserDataCollector.getInstance(mContext);
                        userDataCollector.clearUserData(userData);
                        userDataCollector.clearMetadata();
                        userDataCollector.clearDatabase();
                        callback.onSuccess();
                    } catch (RemoteException re) {
                        Log.e(TAG, "Unable to send result to the callback.", re);
                    }
                }
        );
    }
}
