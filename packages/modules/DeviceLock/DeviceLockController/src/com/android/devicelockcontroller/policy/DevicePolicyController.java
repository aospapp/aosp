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

package com.android.devicelockcontroller.policy;

import com.google.common.util.concurrent.ListenableFuture;

import java.time.Duration;

/**
 * Interface for the policy controller that is responsible for applying policies based
 * on state.
 */
public interface DevicePolicyController {
    /**
     * Launches an activity in locked mode. The specific activity is resolved based on the current
     * device state. Returns false if package containing the activity is not in the allowlist.
     */
    ListenableFuture<Boolean> launchActivityInLockedMode();

    /**
     * Similar to
     * {@link DevicePolicyController#enqueueStartLockTaskModeWorkerWithDelay(boolean, Duration)}
     * but with zero delay.
     *
     * @param isMandatory whether starting lock task mode is mandatory at the time of request.
     */
    void enqueueStartLockTaskModeWorker(boolean isMandatory);

    /**
     * Enqueue a worker to start lock task mode and launch corresponding activity with a delay of
     * certain {@link Duration}.
     * The work will be retried until device is in lock task mode.
     *
     * @param isMandatory whether starting lock task mode is mandatory at the time of request.
     * @param delay       The {@link Duration} that should be delayed before device enters lock
     *                    task mode.
     */
    void enqueueStartLockTaskModeWorkerWithDelay(boolean isMandatory, Duration delay);


    /**
     * Factory resets the device when the setup has failed and cannot continue.
     * Returns true if action was successful.
     */
    boolean wipeData();

    /**
     * Get the State Controller associated with this Policy Controller.
     */
    DeviceStateController getStateController();
}
