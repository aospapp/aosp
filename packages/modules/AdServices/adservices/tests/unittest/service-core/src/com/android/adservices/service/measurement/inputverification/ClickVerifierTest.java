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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.hardware.input.InputManager;
import android.view.InputEvent;
import android.view.MotionEvent;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.modules.utils.testing.TestableDeviceConfig;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

public final class ClickVerifierTest {
    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule mDeviceConfigRule =
            new TestableDeviceConfig.TestableDeviceConfigRule();

    @Mock private InputManager mInputManager;
    @Mock private Flags mFlags;
    private ClickVerifier mClickVerifier;

    @Before
    public void before() {
        MockitoAnnotations.initMocks(this);
        mClickVerifier = new ClickVerifier(mInputManager, mFlags);
    }

    @Test
    public void testInputEventOutsideTimeRangeReturnsFalse() {
        InputEvent eventOutsideRange = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        assertThat(
                        mClickVerifier.isInputEventWithinValidTimeRange(
                                FlagsFactory.getFlagsForTest()
                                                .getMeasurementRegistrationInputEventValidWindowMs()
                                        + 1,
                                eventOutsideRange))
                .isFalse();
    }

    @Test
    public void testInputEventInsideTimeRangeReturnsTrue() {
        InputEvent eventInsideRange = MotionEvent.obtain(0, 0, MotionEvent.ACTION_DOWN, 0, 0, 0);
        long registerTimestamp =
                FlagsFactory.getFlagsForTest().getMeasurementRegistrationInputEventValidWindowMs();
        when(mFlags.getMeasurementRegistrationInputEventValidWindowMs())
                .thenReturn(registerTimestamp);

        assertThat(
                        mClickVerifier.isInputEventWithinValidTimeRange(
                                registerTimestamp, eventInsideRange))
                .isTrue();
    }

    @Test
    public void testInputEventNotValidatedBySystem() {
        InputEvent inputEvent =
                MotionEvent.obtain(
                        0,
                        FlagsFactory.getFlagsForTest()
                                .getMeasurementRegistrationInputEventValidWindowMs(),
                        MotionEvent.ACTION_DOWN,
                        0,
                        0,
                        0);
        when(mFlags.getMeasurementIsClickVerifiedByInputEvent()).thenReturn(true);
        when(mInputManager.verifyInputEvent(inputEvent)).thenReturn(null);
        assertThat(mClickVerifier.isInputEventVerifiableBySystem(inputEvent)).isFalse();
    }

    @Test
    public void testInputEvent_verifyByInputEventFlagDisabled_Verified() {
        InputEvent inputEvent =
                MotionEvent.obtain(
                        266L,
                        FlagsFactory.getFlagsForTest()
                                .getMeasurementRegistrationInputEventValidWindowMs(),
                        MotionEvent.ACTION_DOWN,
                        24.3f,
                        46.7f,
                        0);
        when(mFlags.getMeasurementIsClickVerifiedByInputEvent()).thenReturn(false);
        assertThat(mClickVerifier.isInputEventVerifiableBySystem(inputEvent)).isTrue();
    }
}
