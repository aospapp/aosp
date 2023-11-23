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

package com.android.devicelockcontroller.policy;

import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.CLEAR;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.LOCK_DEVICE;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.PROVISIONING_SUCCESS;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.SETUP_COMPLETE;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.SETUP_FAILURE;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.SETUP_SUCCESS;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceEvent.UNLOCK_DEVICE;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.CLEARED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.KIOSK_SETUP;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.LOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.PSEUDO_LOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.PSEUDO_UNLOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.SETUP_FAILED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.SETUP_IN_PROGRESS;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.SETUP_SUCCEEDED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.UNLOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.UNPROVISIONED;

import androidx.test.core.app.ApplicationProvider;

import com.android.devicelockcontroller.TestDeviceLockControllerApplication;
import com.android.devicelockcontroller.storage.UserParameters;

import com.google.common.truth.Truth;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.ParameterizedRobolectricTestRunner;

import java.util.Arrays;
import java.util.List;

@RunWith(ParameterizedRobolectricTestRunner.class)
public class DeviceStateControllerStateTransitionTest {
    private DeviceStateControllerImpl mDeviceStateController;

    @ParameterizedRobolectricTestRunner.Parameter
    @DeviceStateController.DeviceState
    public int mState;

    @ParameterizedRobolectricTestRunner.Parameter(1)
    @DeviceStateController.DeviceEvent
    public int mEvent;

    @ParameterizedRobolectricTestRunner.Parameter(2)
    @DeviceStateController.DeviceState
    public int mNextState;

    @ParameterizedRobolectricTestRunner.Parameters(name =
            "Transition from {0} to {2} when {1} event happens")
    public static List<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
                {UNPROVISIONED, PROVISIONING_SUCCESS, SETUP_IN_PROGRESS},
                {SETUP_FAILED, PROVISIONING_SUCCESS, SETUP_IN_PROGRESS},
                {UNPROVISIONED, LOCK_DEVICE, PSEUDO_LOCKED},
                {SETUP_IN_PROGRESS, SETUP_SUCCESS, SETUP_SUCCEEDED},
                {SETUP_IN_PROGRESS, SETUP_FAILURE, SETUP_FAILED},
                {SETUP_SUCCEEDED, SETUP_COMPLETE, KIOSK_SETUP},
                {KIOSK_SETUP, UNLOCK_DEVICE, UNLOCKED},
                {KIOSK_SETUP, CLEAR, CLEARED},
                {UNLOCKED, LOCK_DEVICE, LOCKED},
                {UNLOCKED, UNLOCK_DEVICE, UNLOCKED},
                {UNLOCKED, CLEAR, CLEARED},
                {LOCKED, UNLOCK_DEVICE, UNLOCKED},
                {LOCKED, LOCK_DEVICE, LOCKED},
                {LOCKED, CLEAR, CLEARED},
                {PSEUDO_LOCKED, UNLOCK_DEVICE, PSEUDO_UNLOCKED},
                {PSEUDO_LOCKED, LOCK_DEVICE, PSEUDO_LOCKED},
                {PSEUDO_LOCKED, PROVISIONING_SUCCESS, SETUP_IN_PROGRESS},
                {PSEUDO_UNLOCKED, LOCK_DEVICE, PSEUDO_LOCKED},
                {PSEUDO_UNLOCKED, UNLOCK_DEVICE, PSEUDO_UNLOCKED},
                {PSEUDO_UNLOCKED, PROVISIONING_SUCCESS, SETUP_IN_PROGRESS}
        });
    }


    @Before
    public void setup() {
        TestDeviceLockControllerApplication testApplication =
                ApplicationProvider.getApplicationContext();
        UserParameters.setDeviceState(testApplication, mState);
        mDeviceStateController = new DeviceStateControllerImpl(testApplication);
    }

    @Test
    public void testGetNextState() throws StateTransitionException {
        Truth.assertThat(mDeviceStateController.getNextState(mEvent)).isEqualTo(mNextState);
    }
}
