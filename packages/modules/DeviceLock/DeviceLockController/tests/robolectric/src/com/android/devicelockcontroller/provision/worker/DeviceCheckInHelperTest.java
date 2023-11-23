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

package com.android.devicelockcontroller.provision.worker;

import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceIdType.DEVICE_ID_TYPE_IMEI;
import static com.android.devicelockcontroller.common.DeviceLockConstants.DeviceIdType.DEVICE_ID_TYPE_MEID;
import static com.android.devicelockcontroller.common.DeviceLockConstants.READY_FOR_PROVISION;
import static com.android.devicelockcontroller.common.DeviceLockConstants.RETRY_CHECK_IN;
import static com.android.devicelockcontroller.common.DeviceLockConstants.STOP_CHECK_IN;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.robolectric.annotation.LooperMode.Mode.LEGACY;

import android.telephony.TelephonyManager;
import android.util.ArraySet;

import androidx.annotation.Nullable;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.android.devicelockcontroller.TestDeviceLockControllerApplication;
import com.android.devicelockcontroller.common.DeviceId;
import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceCheckInStatus;
import com.android.devicelockcontroller.policy.DevicePolicyController;
import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent;
import com.android.devicelockcontroller.provision.grpc.GetDeviceCheckInStatusGrpcResponse;
import com.android.devicelockcontroller.provision.grpc.ProvisioningConfiguration;
import com.android.devicelockcontroller.storage.GlobalParametersClient;

import com.google.common.util.concurrent.Futures;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowTelephonyManager;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@LooperMode(LEGACY)
@RunWith(RobolectricTestRunner.class)
public final class DeviceCheckInHelperTest {
    static final Duration TEST_CHECK_RETRY_DURATION = Duration.ofDays(30);
    static final Duration TEST_NEGATIVE_CHECK_RETRY_DURATION =
            Duration.ZERO.minus(TEST_CHECK_RETRY_DURATION);
    public static final boolean IS_PROVISIONING_MANDATORY = false;
    private static final int GET_WORK_INFO_TIMEOUT_MILLIS = 500;
    private TestDeviceLockControllerApplication mTestApplication;
    static final int TOTAL_SLOT_COUNT = 2;
    static final int TOTAL_ID_COUNT = 4;
    static final String IMEI_1 = "IMEI1";
    static final String IMEI_2 = "IMEI2";
    static final String MEID_1 = "MEID1";
    static final String MEID_2 = "MEID2";
    static final ArraySet<DeviceId> ACTUAL_DEVICE_IDs =
            new ArraySet<>(new DeviceId[]{
                    new DeviceId(DEVICE_ID_TYPE_IMEI, IMEI_1),
                    new DeviceId(DEVICE_ID_TYPE_IMEI, IMEI_2),
                    new DeviceId(DEVICE_ID_TYPE_MEID, MEID_1),
                    new DeviceId(DEVICE_ID_TYPE_MEID, MEID_2),
            });
    static final ProvisioningConfiguration TEST_CONFIGURATION = new ProvisioningConfiguration(
            /* kioskAppProviderName= */ "test_provider",
            /* kioskAppPackageName= */ "test_package",
            /* kioskAppMainActivity= */ "test_activity",
            /* kioskAppAllowlistPackages= */ List.of("test_allowed_app1", "test_allowed_app2"),
            /* kioskAppEnableOutgoingCalls= */ false,
            /* kioskAppEnableEnableNotifications= */ true,
            /* disallowInstallingFromUnknownSources= */ false,
            /* termsAndConditionsUrl= */ "test_terms_and_configurations_url",
            /* supportUrl= */ "test_support_url"
    );
    static final int DEVICE_ID_TYPE_BITMAP =
            (1 << DEVICE_ID_TYPE_IMEI) | (1 << DEVICE_ID_TYPE_MEID);
    private DeviceCheckInHelper mHelper;

    private ShadowTelephonyManager mTelephonyManager;
    private GlobalParametersClient mGlobalParametersClient;

    @Before
    public void setUp() {
        mTestApplication = ApplicationProvider.getApplicationContext();

        mTelephonyManager = Shadows.shadowOf(
                mTestApplication.getSystemService(TelephonyManager.class));
        mHelper = new DeviceCheckInHelper(mTestApplication);
        WorkManagerTestInitHelper.initializeTestWorkManager(mTestApplication,
                new Configuration.Builder()
                        .setMinimumLoggingLevel(android.util.Log.DEBUG)
                        .setExecutor(new SynchronousExecutor())
                        .build());
        mGlobalParametersClient = GlobalParametersClient.getInstance();
    }

    @Test
    public void getDeviceAvailableUniqueIds_shouldReturnAllAvailableUniqueIds() {
        mTelephonyManager.setActiveModemCount(TOTAL_SLOT_COUNT);
        mTelephonyManager.setImei(/* slotIndex= */ 0, IMEI_1);
        mTelephonyManager.setImei(/* slotIndex= */ 1, IMEI_2);
        mTelephonyManager.setMeid(/* slotIndex= */ 0, MEID_1);
        mTelephonyManager.setMeid(/* slotIndex= */ 1, MEID_2);
        final ArraySet<DeviceId> deviceIds = mHelper.getDeviceAvailableUniqueIds(
                DEVICE_ID_TYPE_BITMAP);
        assertThat(Objects.requireNonNull(deviceIds).size()).isEqualTo(TOTAL_ID_COUNT);
        assertThat(deviceIds).containsExactlyElementsIn(ACTUAL_DEVICE_IDs);
    }

    @Test
    public void testHandleGetDeviceCheckInStatusResponse_stopCheckIn_shouldSetNeedCheckInFalse() {
        final GetDeviceCheckInStatusGrpcResponse response = createStopResponse();

        assertThat(mHelper.handleGetDeviceCheckInStatusResponse(response)).isTrue();
        assertThat(Futures.getUnchecked(mGlobalParametersClient.needCheckIn())).isFalse();
    }

    @Test
    public void
            handleProvisionReadyResponse_validConfiguration_shouldSetStateAndStartLockTaskMode() {
        GetDeviceCheckInStatusGrpcResponse response = createReadyResponse(TEST_CONFIGURATION);
        DeviceStateController stateController = mTestApplication.getStateController();
        DevicePolicyController policyController = mTestApplication.getPolicyController();
        when(stateController.setNextStateForEvent(DeviceEvent.PROVISIONING_SUCCESS)).thenReturn(
                Futures.immediateVoidFuture());

        assertThat(mHelper.handleProvisionReadyResponse(
                response, stateController, policyController)).isTrue();

        verify(stateController).setNextStateForEvent(eq(DeviceEvent.PROVISIONING_SUCCESS));
        verify(policyController).enqueueStartLockTaskModeWorker(eq(IS_PROVISIONING_MANDATORY));
        assertThat(Futures.getUnchecked(mGlobalParametersClient.needCheckIn())).isFalse();
    }

    @Test
    public void testHandleProvisionReadyResponse_invalidConfiguration_shouldNotSetState() {
        GetDeviceCheckInStatusGrpcResponse response = createReadyResponse(
                /* configuration= */ null);
        DeviceStateController stateController = mTestApplication.getStateController();
        DevicePolicyController policyController = mTestApplication.getPolicyController();

        assertThat(mHelper.handleProvisionReadyResponse(
                response, stateController, policyController)).isFalse();

        verify(stateController, never()).setNextStateForEvent(eq(DeviceEvent.PROVISIONING_SUCCESS));
    }

    @Test
    public void testHandleGetDeviceCheckInStatusResponse_retryCheckIn_shouldEnqueueNewCheckInWork()
            throws ExecutionException, InterruptedException, TimeoutException {
        final GetDeviceCheckInStatusGrpcResponse response = createRetryResponse(
                Instant.now().plus(TEST_CHECK_RETRY_DURATION));

        assertThat(mHelper.handleGetDeviceCheckInStatusResponse(response)).isTrue();

        WorkManager workManager = WorkManager.getInstance(mTestApplication);

        List<WorkInfo> workInfo = workManager.getWorkInfosForUniqueWork(
                DeviceCheckInHelper.CHECK_IN_WORK_NAME).get(GET_WORK_INFO_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS);
        assertThat(workInfo.size()).isEqualTo(1);
    }

    @Test
    public void handleGetDeviceCheckInStatusResponse_retryCheckIn_durationIsNegative_shouldRetry()
            throws ExecutionException, InterruptedException, TimeoutException {
        final GetDeviceCheckInStatusGrpcResponse response = createRetryResponse(
                Instant.now().plus(TEST_NEGATIVE_CHECK_RETRY_DURATION));

        assertThat(mHelper.handleGetDeviceCheckInStatusResponse(response)).isTrue();

        WorkManager workManager = WorkManager.getInstance(mTestApplication);

        List<WorkInfo> workInfo = workManager.getWorkInfosForUniqueWork(
                DeviceCheckInHelper.CHECK_IN_WORK_NAME).get(GET_WORK_INFO_TIMEOUT_MILLIS,
                TimeUnit.MILLISECONDS);
        assertThat(workInfo.size()).isEqualTo(1);
    }

    private static GetDeviceCheckInStatusGrpcResponse createStopResponse() {
        return createMockResponse(STOP_CHECK_IN, /* nextCheckInTime= */ null, /* config= */ null);
    }


    private static GetDeviceCheckInStatusGrpcResponse createRetryResponse(Instant nextCheckInTime) {
        return createMockResponse(RETRY_CHECK_IN, nextCheckInTime, /* config= */ null);
    }

    private static GetDeviceCheckInStatusGrpcResponse createReadyResponse(
            ProvisioningConfiguration configuration) {
        return createMockResponse(READY_FOR_PROVISION, /* nextCheckInTime= */ null, configuration);
    }

    private static GetDeviceCheckInStatusGrpcResponse createMockResponse(
            @DeviceCheckInStatus int checkInStatus,
            @Nullable Instant nextCheckInTime, @Nullable ProvisioningConfiguration config) {
        GetDeviceCheckInStatusGrpcResponse response = Mockito.mock(
                GetDeviceCheckInStatusGrpcResponse.class);
        when(response.getDeviceCheckInStatus()).thenReturn(checkInStatus);
        when(response.isProvisioningMandatory()).thenReturn(IS_PROVISIONING_MANDATORY);
        if (nextCheckInTime != null) {
            when(response.getNextCheckInTime()).thenReturn(nextCheckInTime);
        }
        if (config != null) {
            when(response.getProvisioningConfig()).thenReturn(config);
        }
        return response;
    }
}
