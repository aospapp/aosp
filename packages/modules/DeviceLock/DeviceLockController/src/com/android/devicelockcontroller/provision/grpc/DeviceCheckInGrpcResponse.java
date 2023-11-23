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

package com.android.devicelockcontroller.provision.grpc;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import io.grpc.Status;
import io.grpc.Status.Code;

/**
 * Base class for encapsulating a device check in server response. This class handles the Grpc
 * status response, subclasses will handle request specific responses.
 */
abstract class DeviceCheckInGrpcResponse {
    @Nullable
    Status mStatus;

    DeviceCheckInGrpcResponse() {
        mStatus = null;
    }

    DeviceCheckInGrpcResponse(@NonNull Status status) {
        mStatus = status;
    }

    public boolean hasRecoverableError() {
        return mStatus != null && mStatus.getCode() == Code.UNAVAILABLE;
    }

    public boolean isSuccessful() {
        return mStatus == null;
    }

    public boolean isStatusAlreadyExists() {
        return mStatus != null && mStatus.getCode() == Code.ALREADY_EXISTS;
    }

    public boolean hasFatalError() {
        return mStatus != null
                && mStatus.getCode() != Code.OK
                && mStatus.getCode() != Code.UNAVAILABLE;
    }

    @Override
    public String toString() {
        return "mStatus: " + mStatus;
    }
}
