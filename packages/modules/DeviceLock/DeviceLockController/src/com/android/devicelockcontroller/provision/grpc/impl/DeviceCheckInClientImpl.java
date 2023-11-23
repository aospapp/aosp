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

import static com.android.devicelockcontroller.proto.ClientProvisionFailureReason.CLIENT_PROVISION_FAILURE_REASON_DELETE_PACKAGE_FAILED;
import static com.android.devicelockcontroller.proto.ClientProvisionFailureReason.CLIENT_PROVISION_FAILURE_REASON_DOWNLOAD_FAILED;
import static com.android.devicelockcontroller.proto.ClientProvisionFailureReason.CLIENT_PROVISION_FAILURE_REASON_INSTALL_EXISTING_FAILED;
import static com.android.devicelockcontroller.proto.ClientProvisionFailureReason.CLIENT_PROVISION_FAILURE_REASON_INSTALL_FAILED;
import static com.android.devicelockcontroller.proto.ClientProvisionFailureReason.CLIENT_PROVISION_FAILURE_REASON_PACKAGE_DOES_NOT_EXIST;
import static com.android.devicelockcontroller.proto.ClientProvisionFailureReason.CLIENT_PROVISION_FAILURE_REASON_SETUP_FAILED;
import static com.android.devicelockcontroller.proto.ClientProvisionFailureReason.CLIENT_PROVISION_FAILURE_REASON_VERIFICATION_FAILED;

import android.util.ArraySet;

import androidx.annotation.Keep;

import com.android.devicelockcontroller.common.DeviceId;
import com.android.devicelockcontroller.common.DeviceLockConstants;
import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceIdType;
import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState;
import com.android.devicelockcontroller.common.DeviceLockConstants.SetupFailureReason;
import com.android.devicelockcontroller.proto.ClientDeviceIdentifier;
import com.android.devicelockcontroller.proto.ClientProvisionFailureReason;
import com.android.devicelockcontroller.proto.ClientProvisionState;
import com.android.devicelockcontroller.proto.DeviceIdentifierType;
import com.android.devicelockcontroller.proto.DeviceLockCheckinServiceGrpc;
import com.android.devicelockcontroller.proto.DeviceLockCheckinServiceGrpc.DeviceLockCheckinServiceBlockingStub;
import com.android.devicelockcontroller.proto.GetDeviceCheckinStatusRequest;
import com.android.devicelockcontroller.proto.IsDeviceInApprovedCountryRequest;
import com.android.devicelockcontroller.proto.PauseDeviceProvisioningReason;
import com.android.devicelockcontroller.proto.PauseDeviceProvisioningRequest;
import com.android.devicelockcontroller.proto.ReportDeviceProvisionStateRequest;
import com.android.devicelockcontroller.provision.grpc.DeviceCheckInClient;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.IsDeviceInApprovedCountryGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.PauseDeviceProvisioningGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.ReportDeviceProvisionStateGrpcResponse;

import javax.annotation.Nullable;

import io.grpc.StatusRuntimeException;
import io.grpc.okhttp.OkHttpChannelBuilder;

/**
 * A client for the {@link  com.android.devicelockcontroller.proto.DeviceLockCheckinServiceGrpc}
 * service.
 */
@Keep
public final class DeviceCheckInClientImpl extends DeviceCheckInClient {
    private final DeviceLockCheckinServiceBlockingStub mBlockingStub;

    public DeviceCheckInClientImpl() {
        mBlockingStub = DeviceLockCheckinServiceGrpc.newBlockingStub(
                        OkHttpChannelBuilder
                                .forAddress(sHostName, sPortNumber)
                                .build())
                .withInterceptors(new ApiKeyClientInterceptor(sApiKey));
    }

    @Override
    public GetDeviceCheckInStatusGrpcResponse getDeviceCheckInStatus(
            ArraySet<DeviceId> deviceIds, String carrierInfo,
            @Nullable String fcmRegistrationToken) {
        try {
            final GetDeviceCheckInStatusGrpcResponse response =
                    new GetDeviceCheckInStatusGrpcResponseWrapper(
                            mBlockingStub.getDeviceCheckinStatus(
                                    createGetDeviceCheckinStatusRequest(deviceIds, carrierInfo)));
            return response;
        } catch (StatusRuntimeException e) {
            return new GetDeviceCheckInStatusGrpcResponseWrapper(e.getStatus());
        }
    }

    /**
     * Check if the device is in an approved country for the device lock program.
     *
     * @param carrierInfo The information of the device's sim operator which is used to determine
     *                    the device's geological location and eventually eligibility of the
     *                    DeviceLock program. Could be null if unavailable.
     * @return A class that encapsulate the response from the backend server.
     */
    @Override
    public IsDeviceInApprovedCountryGrpcResponse isDeviceInApprovedCountry(
            @Nullable String carrierInfo) {
        try {
            return new IsDeviceInApprovedCountryGrpcResponseWrapper(
                    mBlockingStub.isDeviceInApprovedCountry(
                            createIsDeviceInApprovedCountryRequest(carrierInfo, sRegisteredId)));
        } catch (StatusRuntimeException e) {
            return new IsDeviceInApprovedCountryGrpcResponseWrapper(e.getStatus());
        }
    }

    @Override
    public PauseDeviceProvisioningGrpcResponse pauseDeviceProvisioning(int reason) {
        try {
            return new PauseDeviceProvisioningGrpcResponseWrapper(
                    mBlockingStub.pauseDeviceProvisioning(
                            createPauseDeviceProvisioningRequest(sRegisteredId, reason)));

        } catch (StatusRuntimeException e) {
            return new PauseDeviceProvisioningGrpcResponseWrapper(e.getStatus());
        }
    }

    /**
     * Reports the current provision state of the device.
     *
     * @param reasonOfFailure            one of {@link SetupFailureReason}
     * @param lastReceivedProvisionState one of {@link DeviceProvisionState}.
     *                                   It must be the value from the response when this API
     *                                   was called last time. If this API is called for the first
     *                                   time, then
     *                                   {@link
     *                                   DeviceProvisionState#PROVISION_STATE_UNSPECIFIED }
     *                                   must be used.
     * @param isSuccessful               true if the device has been setup for DeviceLock program
     *                                   successful; false otherwise.
     * @return A class that encapsulate the response from the backend server.
     */
    @Override
    public ReportDeviceProvisionStateGrpcResponse reportDeviceProvisionState(int reasonOfFailure,
            int lastReceivedProvisionState, boolean isSuccessful) {
        try {
            return new ReportDeviceProvisionStateGrpcResponseWrapper(
                    mBlockingStub.reportDeviceProvisionState(
                            createReportDeviceProvisionStateRequest(reasonOfFailure,
                                    lastReceivedProvisionState, isSuccessful, sRegisteredId)));
        } catch (StatusRuntimeException e) {
            return new ReportDeviceProvisionStateGrpcResponseWrapper(e.getStatus());
        }
    }

    private static GetDeviceCheckinStatusRequest createGetDeviceCheckinStatusRequest(
            ArraySet<DeviceId> deviceIds, String carrierInfo) {
        GetDeviceCheckinStatusRequest.Builder builder = GetDeviceCheckinStatusRequest.newBuilder();
        for (DeviceId deviceId : deviceIds) {
            DeviceIdentifierType type;
            switch (deviceId.getType()) {
                case DeviceIdType.DEVICE_ID_TYPE_UNSPECIFIED:
                    type = DeviceIdentifierType.DEVICE_IDENTIFIER_TYPE_UNSPECIFIED;
                    break;
                case DeviceIdType.DEVICE_ID_TYPE_IMEI:
                    type = DeviceIdentifierType.DEVICE_IDENTIFIER_TYPE_IMEI;
                    break;
                case DeviceIdType.DEVICE_ID_TYPE_MEID:
                    type = DeviceIdentifierType.DEVICE_IDENTIFIER_TYPE_MEID;
                    break;
                default:
                    throw new IllegalStateException(
                            "Unexpected DeviceId type: " + deviceId.getType());
            }
            builder.addClientDeviceIdentifiers(
                    ClientDeviceIdentifier.newBuilder()
                            .setDeviceIdentifierType(type)
                            .setDeviceIdentifier(deviceId.getId()));
        }
        builder.setCarrierMccmnc(carrierInfo);
        return builder.build();
    }

    private static IsDeviceInApprovedCountryRequest createIsDeviceInApprovedCountryRequest(
            String carrierInfo, String registeredId) {
        return IsDeviceInApprovedCountryRequest.newBuilder()
                .setCarrierMccmnc(carrierInfo)
                .setRegisteredDeviceIdentifier(registeredId)
                .build();
    }

    private static PauseDeviceProvisioningRequest createPauseDeviceProvisioningRequest(
            String registeredId,
            @DeviceLockConstants.PauseDeviceProvisioningReason int reason) {
        return PauseDeviceProvisioningRequest.newBuilder()
                .setRegisteredDeviceIdentifier(registeredId)
                .setPauseDeviceProvisioningReason(
                        PauseDeviceProvisioningReason.forNumber(reason))
                .build();
    }

    private static ReportDeviceProvisionStateRequest createReportDeviceProvisionStateRequest(
            @SetupFailureReason int reasonOfFailure,
            @DeviceProvisionState int lastReceivedProvisionState,
            boolean isSuccessful,
            String registeredId) {
        ClientProvisionFailureReason reason;
        switch (reasonOfFailure) {
            case SetupFailureReason.SETUP_FAILED:
                reason = CLIENT_PROVISION_FAILURE_REASON_SETUP_FAILED;
                break;
            case SetupFailureReason.DOWNLOAD_FAILED:
                reason = CLIENT_PROVISION_FAILURE_REASON_DOWNLOAD_FAILED;
                break;
            case SetupFailureReason.VERIFICATION_FAILED:
                reason = CLIENT_PROVISION_FAILURE_REASON_VERIFICATION_FAILED;
                break;
            case SetupFailureReason.INSTALL_FAILED:
                reason = CLIENT_PROVISION_FAILURE_REASON_INSTALL_FAILED;
                break;
            case SetupFailureReason.PACKAGE_DOES_NOT_EXIST:
                reason = CLIENT_PROVISION_FAILURE_REASON_PACKAGE_DOES_NOT_EXIST;
                break;
            case SetupFailureReason.DELETE_PACKAGE_FAILED:
                reason = CLIENT_PROVISION_FAILURE_REASON_DELETE_PACKAGE_FAILED;
                break;
            case SetupFailureReason.INSTALL_EXISTING_FAILED:
                reason = CLIENT_PROVISION_FAILURE_REASON_INSTALL_EXISTING_FAILED;
                break;
            default:
                throw new IllegalStateException(
                        "Unexpected provision failure reason value: " + reasonOfFailure);
        }
        ClientProvisionState state;
        switch (lastReceivedProvisionState) {
            case DeviceProvisionState.PROVISION_STATE_UNSPECIFIED:
                state = ClientProvisionState.CLIENT_PROVISION_STATE_UNSPECIFIED;
                break;
            case DeviceProvisionState.PROVISION_STATE_RETRY:
                state = ClientProvisionState.CLIENT_PROVISION_STATE_RETRY;
                break;
            case DeviceProvisionState.PROVISION_STATE_DISMISSIBLE_UI:
                state = ClientProvisionState.CLIENT_PROVISION_STATE_DISMISSIBLE_UI;
                break;
            case DeviceProvisionState.PROVISION_STATE_PERSISTENT_UI:
                state = ClientProvisionState.CLIENT_PROVISION_STATE_PERSISTENT_UI;
                break;
            case DeviceProvisionState.PROVISION_STATE_FACTORY_RESET:
                state = ClientProvisionState.CLIENT_PROVISION_STATE_FACTORY_RESET;
                break;
            case DeviceProvisionState.PROVISION_STATE_SUCCESS:
                state = ClientProvisionState.CLIENT_PROVISION_STATE_SUCCESS;
                break;
            default:
                throw new IllegalStateException(
                        "Unexpected value: " + lastReceivedProvisionState);
        }
        return ReportDeviceProvisionStateRequest.newBuilder()
                .setClientProvisionFailureReason(reason)
                .setPreviousClientProvisionState(state)
                .setProvisionSuccess(isSuccessful)
                .setRegisteredDeviceIdentifier(registeredId)
                .build();
    }

}
