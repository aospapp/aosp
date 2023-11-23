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
import androidx.annotation.MainThread;

import com.google.common.util.concurrent.ListenableFuture;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// TODO: rework the state and events for vNext

/**
 * Interface for the device lock controller state machine.
 */
@MainThread
public interface DeviceStateController {
    /**
     * Enforce all policies for the current device state.
     */
    ListenableFuture<Void> enforcePoliciesForCurrentState();

    /**
     * Moves the device to a new state based on the input event
     */
    ListenableFuture<Void> setNextStateForEvent(@DeviceEvent int event);

    /**
     * Returns the current state of the device
     */
    @DeviceState
    int getState();

    /**
     * Returns true if the device is in locked state.
     */
    boolean isLocked();

    /**
     * Returns true if the device needs to check in with DeviceLock server
     */
    boolean isCheckInNeeded();

    /**
     * Returns true if the device is in setup flow.
     */
    boolean isInSetupState();

    /**
     * Register a callback to get notified on state change.
     */
    void addCallback(StateListener listener);

    /**
     * Remove a previously registered callback.
     */
    void removeCallback(StateListener listener);

    /**
     * Device state definitions
     */
    @Target(ElementType.TYPE_USE)
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DeviceState.UNPROVISIONED,
            DeviceState.SETUP_IN_PROGRESS,
            DeviceState.SETUP_SUCCEEDED,
            DeviceState.SETUP_FAILED,
            DeviceState.KIOSK_SETUP,
            DeviceState.UNLOCKED,
            DeviceState.LOCKED,
            DeviceState.CLEARED,
            DeviceState.PSEUDO_LOCKED,
            DeviceState.PSEUDO_UNLOCKED,
    })
    @interface DeviceState {

        /* DLC is not provisioned */
        int UNPROVISIONED = 0;

        /* Setup flow is in progress. This is where kiosk app will be downloaded. */
        int SETUP_IN_PROGRESS = 1;

        /* Setup has succeeded */
        int SETUP_SUCCEEDED = 2;

        /* Setup has failed */
        int SETUP_FAILED = 3;

        /* Showing kiosk setup activity */
        int KIOSK_SETUP = 4;

        /* Device is unlocked */
        int UNLOCKED = 5;

        /* Device is locked */
        int LOCKED = 6;

        /* Fully cleared from locking */
        int CLEARED = 7;

        /* Device appears to be locked. No Actual locking is performed. Used for testing */
        int PSEUDO_LOCKED = 8;

        /* Device appears to be unlocked. No Actual unlocking is performed. Used for testing */
        int PSEUDO_UNLOCKED = 9;
    }

    /**
     * Get the corresponding string for input {@link DeviceState}.
     */
    static String stateToString(@DeviceState int state) {
        switch (state) {
            case DeviceState.UNPROVISIONED:
                return "UNPROVISIONED";
            case DeviceState.SETUP_IN_PROGRESS:
                return "SETUP_IN_PROGRESS";
            case DeviceState.SETUP_SUCCEEDED:
                return "SETUP_SUCCEEDED";
            case DeviceState.SETUP_FAILED:
                return "SETUP_FAILED";
            case DeviceState.KIOSK_SETUP:
                return "KIOSK_SETUP";
            case DeviceState.UNLOCKED:
                return "UNLOCKED";
            case DeviceState.LOCKED:
                return "LOCKED";
            case DeviceState.CLEARED:
                return "CLEARED";
            case DeviceState.PSEUDO_LOCKED:
                return "PSEUDO_LOCKED";
            case DeviceState.PSEUDO_UNLOCKED:
                return "PSEUDO_UNLOCKED";
            default:
                return "UNKNOWN_STATE";
        }
    }


    /**
     * Device event definitions
     */
    @Retention(RetentionPolicy.SOURCE)
    @IntDef({
            DeviceEvent.PROVISIONING_SUCCESS,
            DeviceEvent.SETUP_SUCCESS,
            DeviceEvent.SETUP_FAILURE,
            DeviceEvent.SETUP_COMPLETE,
            DeviceEvent.LOCK_DEVICE,
            DeviceEvent.UNLOCK_DEVICE,
            DeviceEvent.CLEAR,
    })
    @interface DeviceEvent {

        /* App provisioned */
        int PROVISIONING_SUCCESS = 0;

        /* Setup completed successfully */
        int SETUP_SUCCESS = 1;

        /* Setup failed to complete */
        int SETUP_FAILURE = 2;

        /* Setup has complete */
        int SETUP_COMPLETE = 3;

        /* Lock device */
        int LOCK_DEVICE = 4;

        /* Unlock device */
        int UNLOCK_DEVICE = 5;

        /* Clear device lock restrictions */
        int CLEAR = 6;
    }

    /**
     * Listener interface for state changes.
     */
    interface StateListener {
        /**
         * Notified after the device transitions to a new state
         */
        ListenableFuture<Void> onStateChanged(@DeviceState int newState);
    }


    /**
     * Get the corresponding string for the input {@link DeviceEvent}
     */
    static String eventToString(@DeviceEvent int event) {
        switch (event) {
            case DeviceEvent.PROVISIONING_SUCCESS:
                return "PROVISIONING_SUCCESS";
            case DeviceEvent.SETUP_SUCCESS:
                return "SETUP_SUCCESS";
            case DeviceEvent.SETUP_FAILURE:
                return "SETUP_FAILURE";
            case DeviceEvent.SETUP_COMPLETE:
                return "SETUP_COMPLETE";
            case DeviceEvent.LOCK_DEVICE:
                return "LOCK_DEVICE";
            case DeviceEvent.UNLOCK_DEVICE:
                return "UNLOCK_DEVICE";
            case DeviceEvent.CLEAR:
                return "CLEAR";
            default:
                return "UNKNOWN_EVENT";
        }
    }
}
