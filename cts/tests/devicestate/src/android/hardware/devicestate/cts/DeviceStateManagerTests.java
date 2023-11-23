/*
 * Copyright (C) 2021 The Android Open Source Project
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

package android.hardware.devicestate.cts;

import static android.hardware.devicestate.cts.DeviceStateUtils.assertValidState;
import static android.hardware.devicestate.cts.DeviceStateUtils.runWithControlDeviceStatePermission;
import static android.hardware.devicestate.DeviceStateManager.MAXIMUM_DEVICE_STATE;
import static android.hardware.devicestate.DeviceStateManager.MINIMUM_DEVICE_STATE;
import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertFalse;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.hardware.devicestate.DeviceStateManager;
import android.hardware.devicestate.DeviceStateRequest;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.compatibility.common.util.PollingCheck;

import org.junit.runner.RunWith;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.concurrent.Executor;

/** CTS tests for {@link DeviceStateManager} API(s). */
@RunWith(AndroidJUnit4.class)
public class DeviceStateManagerTests extends DeviceStateManagerTestBase {

    public static final int TIMEOUT = 2000;

    /**
     * Tests that {@link DeviceStateManager#getSupportedStates()} returns at least one state and
     * that none of the returned states are in the range
     * [{@link #MINIMUM_DEVICE_STATE}, {@link #MAXIMUM_DEVICE_STATE}].
     */
    @Test
    public void testValidSupportedStates() throws Exception {
        final int[] supportedStates = getDeviceStateManager().getSupportedStates();
        assertTrue(supportedStates.length > 0);

        for (int i = 0; i < supportedStates.length; i++) {
            final int state = supportedStates[i];
            assertValidState(state);
        }
    }

    /**
     * Tests that calling {@link DeviceStateManager#requestState(DeviceStateRequest, Executor,
     * DeviceStateRequest.Callback)} is successful and results in a registered callback being
     * triggered with a value equal to the requested state.
     */
    @Test
    public void testRequestAllSupportedStates() throws Throwable {
        final ArgumentCaptor<Integer> intAgumentCaptor = ArgumentCaptor.forClass(Integer.class);
        final DeviceStateManager.DeviceStateCallback callback
                = mock(DeviceStateManager.DeviceStateCallback.class);
        final DeviceStateManager manager = getDeviceStateManager();
        manager.registerCallback(Runnable::run, callback);

        final int[] supportedStates = manager.getSupportedStates();
        for (int i = 0; i < supportedStates.length; i++) {
            final DeviceStateRequest request
                    = DeviceStateRequest.newBuilder(supportedStates[i]).build();

            runWithRequestActive(request, () -> {
                verify(callback, atLeastOnce()).onStateChanged(intAgumentCaptor.capture());
                assertEquals(intAgumentCaptor.getValue().intValue(), request.getState());
            });
        }
    }

    /**
     * Tests that calling {@link DeviceStateManager#requestState(DeviceStateRequest, Executor,
     * DeviceStateRequest.Callback)} throws an {@link java.lang.IllegalArgumentException} if
     * supplied with a state above {@link MAXIMUM_DEVICE_STATE}.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRequestStateTooLarge() throws Throwable {
        final DeviceStateManager manager = getDeviceStateManager();
        final DeviceStateRequest request
                = DeviceStateRequest.newBuilder(MAXIMUM_DEVICE_STATE + 1).build();
        runWithControlDeviceStatePermission(() -> manager.requestState(request, null, null));
    }

    /**
     * Tests that calling {@link DeviceStateManager#requestState(DeviceStateRequest, Executor,
     * DeviceStateRequest.Callback)} throws an {@link java.lang.IllegalArgumentException} if
     * supplied with a state below {@link MINIMUM_DEVICE_STATE}.
     */
    @Test(expected = IllegalArgumentException.class)
    public void testRequestStateTooSmall() throws Throwable {
        final DeviceStateManager manager = getDeviceStateManager();
        final DeviceStateRequest request
                = DeviceStateRequest.newBuilder(MINIMUM_DEVICE_STATE - 1).build();
        runWithControlDeviceStatePermission(() -> manager.requestState(request, null, null));
    }

    /**
     * Tests that calling {@link DeviceStateManager#requestState(DeviceStateRequest, Executor,
     * DeviceStateRequest.Callback)} is successful and results in a registered callback being
     * triggered with a value equal to the requested state.
     */
    @Test
    public void testRequestStateSucceedsAsTopApp() throws IllegalArgumentException {
        final DeviceStateManager manager = getDeviceStateManager();
        final int[] supportedStates = manager.getSupportedStates();
        // We want to verify that the app can change device state
        // So we only attempt if there are more than 1 possible state.
        if (supportedStates.length < 2) {
            return;
        }
        final StateTrackingCallback callback = new StateTrackingCallback();
        manager.registerCallback(Runnable::run, callback);
        PollingCheck.waitFor(TIMEOUT, () -> callback.mCurrentState != -1);
        final TestActivitySession<DeviceStateTestActivity> activitySession =
                createManagedTestActivitySession();

        activitySession.launchTestActivityOnDisplaySync(
                DeviceStateTestActivity.class,
                DEFAULT_DISPLAY
        );

        DeviceStateTestActivity activity = activitySession.getActivity();

        int newState = determineNewState(callback.mCurrentState, supportedStates);
        activity.requestDeviceStateChange(newState);

        PollingCheck.waitFor(TIMEOUT, () -> callback.mCurrentState == newState);

        assertEquals(newState, callback.mCurrentState);
        assertFalse(activity.requestStateFailed);
    }

    /**
     * Tests that calling {@link DeviceStateManager#requestState} is unsuccessful and results in a
     * failure to update the state of the device as expected since the activity is backgrounded.
     */
    @Test
    public void testRequestStateFailsAsBackgroundApp() throws IllegalArgumentException {
        final DeviceStateManager manager = getDeviceStateManager();
        final int[] supportedStates = manager.getSupportedStates();
        // We want to verify that the app can change device state
        // So we only attempt if there are more than 1 possible state.
        if (supportedStates.length < 2) {
            return;
        }
        final StateTrackingCallback callback = new StateTrackingCallback();
        manager.registerCallback(Runnable::run, callback);
        PollingCheck.waitFor(TIMEOUT, () -> callback.mCurrentState != -1);

        final TestActivitySession<DeviceStateTestActivity> activitySession =
                createManagedTestActivitySession();
        activitySession.launchTestActivityOnDisplaySync(
                DeviceStateTestActivity.class,
                DEFAULT_DISPLAY
        );

        DeviceStateTestActivity activity = activitySession.getActivity();
        assertFalse(activity.requestStateFailed);

        launchHomeActivity(); // places our test activity in the background

        int requestedState = determineNewState(callback.mCurrentState, supportedStates);
        activity.requestDeviceStateChange(requestedState);

        assertTrue(activity.requestStateFailed);
    }

    // determine what state we should request that isn't the current state
    // throws an IllegalArgumentException if there is no unique states available
    // in the list of supported states
    private static int determineNewState(int currentState, int[] states)
            throws IllegalArgumentException {
        for (int state : states) {
            if (state != currentState) {
                return state;
            }
        }
        throw new IllegalArgumentException(
                "No unique supported states besides our current state were found");
    }

    /**
     * Tests that calling {@link DeviceStateManager#requestState()} throws a
     * {@link java.lang.SecurityException} without the
     * {@link android.Manifest.permission.CONTROL_DEVICE_STATE} permission held.
     */
    @Test(expected = SecurityException.class)
    public void testRequestStateWithoutPermission() {
        final DeviceStateManager manager = getDeviceStateManager();
        final int[] states = manager.getSupportedStates();
        final DeviceStateRequest request = DeviceStateRequest.newBuilder(states[0]).build();
        manager.requestState(request, null, null);
    }

    /**
     * Tests that calling {@link DeviceStateManager#cancelRequest()} throws a
     * {@link java.lang.SecurityException} without the
     * {@link android.Manifest.permission.CONTROL_DEVICE_STATE} permission held.
     */
    @Test(expected = SecurityException.class)
    public void testCancelRequestWithoutPermission() throws Throwable {
        final DeviceStateManager manager = getDeviceStateManager();
        final int[] states = manager.getSupportedStates();
        final DeviceStateRequest request = DeviceStateRequest.newBuilder(states[0]).build();
        runWithRequestActive(request, () -> {
            manager.cancelRequest(request);
        });
    }

    /**
     * Tests that callbacks added with {@link DeviceStateManager#registerDeviceStateCallback()} are
     * supplied with an initial callback that contains the state at the time of registration.
     */
    @Test
    public void testRegisterCallbackSuppliesInitialValue() throws InterruptedException {
        final ArgumentCaptor<int[]> intArrayAgumentCaptor = ArgumentCaptor.forClass(int[].class);
        final ArgumentCaptor<Integer> intAgumentCaptor = ArgumentCaptor.forClass(Integer.class);

        final DeviceStateManager.DeviceStateCallback callback
                = mock(DeviceStateManager.DeviceStateCallback.class);
        final DeviceStateManager manager = getDeviceStateManager();
        manager.registerCallback(Runnable::run, callback);

        verify(callback, timeout(CALLBACK_TIMEOUT_MS)).onStateChanged(intAgumentCaptor.capture());
        assertValidState(intAgumentCaptor.getValue().intValue());

        verify(callback, timeout(CALLBACK_TIMEOUT_MS))
                .onBaseStateChanged(intAgumentCaptor.capture());
        assertValidState(intAgumentCaptor.getValue().intValue());

        verify(callback, timeout(CALLBACK_TIMEOUT_MS))
                .onSupportedStatesChanged(intArrayAgumentCaptor.capture());
        final int[] supportedStates = intArrayAgumentCaptor.getValue();
        assertTrue(supportedStates.length > 0);
        for (int i = 0; i < supportedStates.length; i++) {
            final int state = supportedStates[i];
            assertValidState(state);
        }
    }

    private class StateTrackingCallback implements  DeviceStateManager.DeviceStateCallback {
        private int mCurrentState = - 1;

        @Override
        public void onStateChanged(int state) {
            mCurrentState = state;
        }
    }
}
