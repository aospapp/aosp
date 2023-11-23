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

import androidx.annotation.IntDef;
import androidx.lifecycle.LifecycleOwner;

import com.android.devicelockcontroller.common.DeviceLockConstants.SetupFailureReason;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;

/**
 * Controller managing communication between setup tasks and UI layer.
 *
 * Note that some APIs return a listenable future because the underlying calls to
 * {@link com.android.devicelockcontroller.storage.SetupParametersClient} return a listenable future
 * for inter process calls.
 */
public interface SetupController {

    /** Definitions for status of the setup. */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            SetupStatus.SETUP_NOT_STARTED,
            SetupStatus.SETUP_IN_PROGRESS,
            SetupStatus.SETUP_FAILED,
            SetupStatus.SETUP_FINISHED})
    @interface SetupStatus {
        /** Setup has not started and will be triggered from the activity. */
        int SETUP_NOT_STARTED = 0;
        /** Setup is in progress */
        int SETUP_IN_PROGRESS = 1;
        /** Setup has failed. */
        int SETUP_FAILED = 2;
        /** Setup has finished successfully. */
        int SETUP_FINISHED = 3;
    }

    /** Registers a callback listener. */
    void addListener(SetupUpdatesCallbacks cb);

    /** Removes a callback listener. */
    void removeListener(SetupUpdatesCallbacks cb);

    /** Returns the status of Setup progress. */
    @SetupStatus
    int getSetupState();

    /** Triggers the setup flow process. */
    ListenableFuture<Void> startSetupFlow(LifecycleOwner owner);

    /** Callback interface for updates on setup tasks */
    interface SetupUpdatesCallbacks {

        /** Method called when setup has failed. */
        void setupFailed(@SetupFailureReason int reason);

        /** Method called when setup tasks have completed successfully. */
        void setupCompleted();
    }
}
