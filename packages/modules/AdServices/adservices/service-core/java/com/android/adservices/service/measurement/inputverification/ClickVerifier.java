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

package com.android.adservices.service.measurement.inputverification;

import android.content.Context;
import android.hardware.input.InputManager;
import android.view.InputEvent;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.internal.annotations.VisibleForTesting;

/** Class for handling navigation event verification. */
public class ClickVerifier {
    private final InputManager mInputManager;
    private final Flags mFlags;

    public ClickVerifier(Context context) {
        mInputManager = context.getSystemService(InputManager.class);
        mFlags = FlagsFactory.getFlags();
    }

    @VisibleForTesting
    ClickVerifier(InputManager inputManager, Flags flags) {
        mInputManager = inputManager;
        mFlags = flags;
    }

    /**
     * Checks if the {@link InputEvent} passed with a click registration can be verified. In order
     * for an InputEvent to be verified, the event time of the InputEvent has to be within {@link
     * com.android.adservices.service.PhFlags#MEASUREMENT_REGISTRATION_INPUT_EVENT_VALID_WINDOW_MS }
     * of the API call.
     *
     * @param event The InputEvent passed with the registration call.
     * @param registerTimestamp The time of the registration call.
     * @return Whether the InputEvent can be verified.
     */
    public boolean isInputEventVerifiable(InputEvent event, long registerTimestamp) {
        return isInputEventVerifiableBySystem(event)
                && isInputEventWithinValidTimeRange(registerTimestamp, event);
    }

    /** Checks whether the InputEvent can be verified by the system. */
    @VisibleForTesting
    boolean isInputEventVerifiableBySystem(InputEvent event) {
        return !mFlags.getMeasurementIsClickVerifiedByInputEvent()
                || mInputManager.verifyInputEvent(event) != null;
    }

    /**
     * Checks whether the timestamp on the InputEvent and the time of the API call are within the
     * accepted range defined at {@link
     * com.android.adservices.service.PhFlags#MEASUREMENT_REGISTRATION_INPUT_EVENT_VALID_WINDOW_MS}
     */
    @VisibleForTesting
    boolean isInputEventWithinValidTimeRange(long registerTimestamp, InputEvent event) {
        return registerTimestamp - event.getEventTime()
                <= mFlags.getMeasurementRegistrationInputEventValidWindowMs();
    }
}
