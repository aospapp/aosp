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

package com.android.devicelockcontroller.provision.grpc.impl;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.devicelockcontroller.proto.PauseDeviceProvisioningResponse;
import com.android.devicelockcontroller.provision.grpc.PauseDeviceProvisioningGrpcResponse;

import io.grpc.Status;

/**
 * Wrapper for response and status objects for a PauseDeviceProvisioningResponse.
 */
final class PauseDeviceProvisioningGrpcResponseWrapper extends PauseDeviceProvisioningGrpcResponse {
    @Nullable
    private final PauseDeviceProvisioningResponse mResponse;

    //TODO: Consider to use a builder pattern to ensure at least one of the below fields is
    // not null value so that we can eliminate the need of two separate constructors.
    PauseDeviceProvisioningGrpcResponseWrapper(@NonNull Status status) {
        super(status);
        mResponse = null;
    }

    PauseDeviceProvisioningGrpcResponseWrapper(
            @NonNull PauseDeviceProvisioningResponse response) {
        mResponse = response;
    }

    @Override
    public boolean shouldForceProvisioning() {
        return mResponse != null && mResponse.getForceProvisioning();
    }
}
