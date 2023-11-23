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

package com.android.federatedcompute.services;

import android.federatedcompute.aidl.IFederatedComputeCallback;
import android.federatedcompute.aidl.IFederatedComputeService;
import android.federatedcompute.common.TrainingOptions;
import android.os.RemoteException;
import android.util.Log;

/** Implementation of {@link IFederatedComputeService}. */
public class FederatedComputeManagingServiceDelegate extends IFederatedComputeService.Stub {
    private static final String TAG = "FcpServiceDelegate";

    public FederatedComputeManagingServiceDelegate() {}

    @Override
    public void scheduleFederatedCompute(
            TrainingOptions trainingOptions, IFederatedComputeCallback callback) {
        try {
            callback.onSuccess();
        } catch (RemoteException e) {
            Log.e(TAG, "Unable to send results to the callback", e);
        }
    }
}
