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

package com.android.devicelockcontroller.debug;

import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState.PROVISION_STATE_UNSPECIFIED;
import static com.android.devicelockcontroller.common.DeviceLockConstants.STATUS_UNSPECIFIED;

import android.os.SystemProperties;
import android.util.ArraySet;

import androidx.annotation.Keep;
import androidx.annotation.Nullable;

import com.android.devicelockcontroller.common.DeviceId;
import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceCheckInStatus;
import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState;
import com.android.devicelockcontroller.common.DeviceLockConstants.ProvisioningType;
import com.android.devicelockcontroller.provision.grpc.DeviceCheckInClient;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.IsDeviceInApprovedCountryGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.PauseDeviceProvisioningGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.ProvisioningConfiguration;
import com.android.devicelockcontroller.provision.grpc.ReportDeviceProvisionStateGrpcResponse;

import java.time.Duration;
import java.time.Instant;

/**
 * An implementation of the {@link DeviceCheckInClient} which simulate server responses by
 * reading it from {@link  SystemProperties}.
 */
@Keep
public final class DeviceCheckInClientDebug extends DeviceCheckInClient {

    public static final String TAG = "DeviceCheckInClientDebug";

    /**
     * Check In with DeviceLock backend server and get the next step for the device.
     */
    @Override
    public GetDeviceCheckInStatusGrpcResponse getDeviceCheckInStatus(ArraySet<DeviceId> deviceIds,
            String carrierInfo, @Nullable String fcmRegistrationToken) {
        return new GetDeviceCheckInStatusGrpcResponse() {
            @Override
            @DeviceCheckInStatus
            public int getDeviceCheckInStatus() {
                return DebugLogUtil.logAndReturn(TAG,
                        SystemProperties.getInt("debug.devicelock.checkin.status",
                                STATUS_UNSPECIFIED));
            }

            @Nullable
            @Override
            public String getRegisteredDeviceIdentifier() {
                return DebugLogUtil.logAndReturn(TAG, SystemProperties.get(
                        "debug.devicelock.checkin.registered-id"));
            }

            @Nullable
            @Override
            public Instant getNextCheckInTime() {
                Duration delay = Duration.ofMinutes(
                        SystemProperties.getInt(
                                "debug.devicelock.checkin.retry-delay", /* def= */ 1));
                return DebugLogUtil.logAndReturn(TAG, Instant.now().plusSeconds(delay.toSeconds()));
            }

            @Nullable
            @Override
            public ProvisioningConfiguration getProvisioningConfig() {
                // Can be override using SetupParametersOverrider.
                return null;
            }

            @Override
            public @ProvisioningType int getProvisioningType() {
                return DebugLogUtil.logAndReturn(TAG,
                        SystemProperties.getInt("debug.devicelock.checkin.provision-type",
                                ProvisioningType.TYPE_UNDEFINED));
            }

            @Override
            public boolean isProvisioningMandatory() {
                return DebugLogUtil.logAndReturn(TAG, SystemProperties.getBoolean(
                        "debug.devicelock.checkin.mandatory-provisioning",
                        false));
            }

            @Override
            public boolean isProvisionForced() {
                return DebugLogUtil.logAndReturn(TAG, SystemProperties.getBoolean(
                        "debug.devicelock.checkin.force-provisioning",
                        false));
            }

            @Override
            public boolean isDeviceInApprovedCountry() {
                return DebugLogUtil.logAndReturn(TAG, SystemProperties.getBoolean(
                        "debug.devicelock.checkin.approved-country",
                        true));
            }
        };
    }

    /**
     * Check if the device is in an approved country for the device lock program.
     */
    @Override
    public IsDeviceInApprovedCountryGrpcResponse isDeviceInApprovedCountry(
            @Nullable String carrierInfo) {
        return new IsDeviceInApprovedCountryGrpcResponse() {
            @Override
            public boolean isDeviceInApprovedCountry() {
                return DebugLogUtil.logAndReturn(TAG, SystemProperties.getBoolean(
                        "debug.devicelock.checkin.approved-country",
                        true));
            }
        };
    }

    /**
     * Inform the server that device provisioning has been paused for a certain amount of time.
     */
    @Override
    public PauseDeviceProvisioningGrpcResponse pauseDeviceProvisioning(int reason) {
        return new PauseDeviceProvisioningGrpcResponse() {
            @Override
            public boolean shouldForceProvisioning() {
                return DebugLogUtil.logAndReturn(TAG, SystemProperties.getBoolean(
                        "debug.devicelock.checkin.force-provisioning",
                        true));
            }
        };
    }

    /**
     * Reports the current provision state of the device.
     */
    @Override
    public ReportDeviceProvisionStateGrpcResponse reportDeviceProvisionState(int reasonOfFailure,
            int lastReceivedProvisionState, boolean isSuccessful) {
        return new ReportDeviceProvisionStateGrpcResponse() {
            @Override
            @DeviceProvisionState
            public int getNextClientProvisionState() {
                return DebugLogUtil.logAndReturn(TAG, SystemProperties.getInt(
                        "debug.devicelock.checkin.next-provision-state",
                        PROVISION_STATE_UNSPECIFIED));
            }

            @Nullable
            @Override
            public String getEnrollmentToken() {
                // Not useful in local testing setup.
                return null;
            }

            @Override
            public int getDaysLeftUntilReset() {
                return DebugLogUtil.logAndReturn(TAG,
                        SystemProperties.getInt(
                                "debug.devicelock.checkin.days-left", /* def= */ 1));
            }
        };
    }
}
