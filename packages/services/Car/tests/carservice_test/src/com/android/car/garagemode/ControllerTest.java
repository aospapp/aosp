/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.car.garagemode;

import static com.android.car.garagemode.GarageMode.ACTION_GARAGE_MODE_OFF;
import static com.android.car.garagemode.GarageMode.ACTION_GARAGE_MODE_ON;
import static com.android.car.garagemode.GarageMode.JOB_SNAPSHOT_INITIAL_UPDATE_MS;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.hardware.power.CarPowerManager;
import android.car.hardware.power.CarPowerManager.CarPowerStateListener;
import android.content.Context;
import android.content.Intent;
import android.content.res.Resources;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;

import com.android.car.CarLocalServices;
import com.android.car.R;
import com.android.car.power.CarPowerManagementService;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.user.CarUserService;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

@RunWith(AndroidJUnit4.class)
@SmallTest
public class ControllerTest {

    @Rule public final MockitoRule rule = MockitoJUnit.rule();

    @Mock private Context mContextMock;
    @Mock private Looper mLooperMock;
    @Mock private Handler mHandlerMock;
    @Mock private Car mCarMock;
    @Mock private CarPowerManager mCarPowerManagerMock;
    @Mock private CarUserService mCarUserServiceMock;
    @Mock private SystemInterface mSystemInterfaceMock;
    @Mock private CarPowerManagementService mCarPowerManagementServiceMock;
    private CarUserService mCarUserServiceOriginal;
    private SystemInterface mSystemInterfaceOriginal;
    private CarPowerManagementService mCarPowerManagementServiceOriginal;
    @Captor private ArgumentCaptor<Intent> mIntentCaptor;
    @Captor private ArgumentCaptor<Integer> mIntegerCaptor;

    private static final String[] sTemplateWakeupSchedule = new String[] {
            "15m,1",
            "6h,4",
            "1d,1"};
    private static final int[] sTemplateWakeupScheduleSeconds = new int[] {
            15 * 60,
            6 * 60 * 60,
            6 * 60 * 60,
            6 * 60 * 60,
            6 * 60 * 60,
            24 * 60 * 60};
    private Controller mController;
    private WakeupPolicy mWakeupPolicy;
    private CompletableFuture<Void> mFuture;

    @Before
    public void setUp() {
        mWakeupPolicy = new WakeupPolicy(sTemplateWakeupSchedule);
        mController = new Controller(
                mContextMock,
                mLooperMock,
                mWakeupPolicy,
                mHandlerMock,
                null);
        mController.setCarPowerManager(mCarPowerManagerMock);
        mFuture = new CompletableFuture<>();
        mCarUserServiceOriginal = CarLocalServices.getService(CarUserService.class);
        mCarPowerManagementServiceOriginal = CarLocalServices.getService(
                CarPowerManagementService.class);
        CarLocalServices.removeServiceForTest(CarUserService.class);
        CarLocalServices.addService(CarUserService.class, mCarUserServiceMock);
        CarLocalServices.removeServiceForTest(SystemInterface.class);
        CarLocalServices.addService(SystemInterface.class, mSystemInterfaceMock);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class,
                mCarPowerManagementServiceMock);
        doReturn(new ArrayList<Integer>()).when(mCarUserServiceMock)
                .startAllBackgroundUsersInGarageMode();
        doNothing().when(mSystemInterfaceMock)
                .sendBroadcastAsUser(any(Intent.class), any(UserHandle.class));
    }

    @After
    public void tearDown() {
        CarLocalServices.removeServiceForTest(CarUserService.class);
        CarLocalServices.addService(CarUserService.class, mCarUserServiceOriginal);
        CarLocalServices.removeServiceForTest(SystemInterface.class);
        CarLocalServices.addService(SystemInterface.class, mSystemInterfaceOriginal);
        CarLocalServices.removeServiceForTest(CarPowerManagementService.class);
        CarLocalServices.addService(CarPowerManagementService.class,
                mCarPowerManagementServiceOriginal);
    }

    @Test
    public void testOnShutdownPrepare_shouldInitiateGarageMode() {
        startAndAssertGarageModeWithSignal(CarPowerStateListener.SHUTDOWN_PREPARE);
        verify(mSystemInterfaceMock)
                .sendBroadcastAsUser(mIntentCaptor.capture(), eq(UserHandle.ALL));
        verifyGarageModeBroadcast(mIntentCaptor.getAllValues(), 1, ACTION_GARAGE_MODE_ON);
    }

    @Test
    public void testOnShutdownCancelled_shouldCancelGarageMode() {
        startAndAssertGarageModeWithSignal(CarPowerStateListener.SHUTDOWN_PREPARE);

        // Sending shutdown cancelled signal to controller, GarageMode should wrap up and stop
        mController.onStateChanged(CarPowerStateListener.SHUTDOWN_CANCELLED, null);

        // Verify that wake up counter is reset and GarageMode is not active anymore
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);
        assertThat(mController.isGarageModeActive()).isFalse();

        // Verify that monitoring thread has stopped
        verify(mHandlerMock, Mockito.atLeastOnce()).removeCallbacks(any(Runnable.class));

        // Verify that OFF signal broadcasted to JobScheduler
        verify(mSystemInterfaceMock, times(2))
                .sendBroadcastAsUser(mIntentCaptor.capture(), eq(UserHandle.ALL));
        verifyGarageModeBroadcast(mIntentCaptor.getAllValues(), 1, ACTION_GARAGE_MODE_ON);
        verifyGarageModeBroadcast(mIntentCaptor.getAllValues(), 2, ACTION_GARAGE_MODE_OFF);

        // Verify that bounded future got cancelled
        assertThat(mFuture.isDone()).isTrue();
        assertThat(mFuture.isCancelled()).isTrue();
    }

    @Test
    public void testWakeupTimeProgression() {
        // Doing initial suspend
        startAndAssertGarageModeWithSignal(CarPowerStateListener.SHUTDOWN_PREPARE);

        // Finish GarageMode and check next scheduled time
        mController.finishGarageMode();
        assertThat(mController.isGarageModeActive()).isFalse();

        // Start GarageMode again without waking up car
        mFuture = new CompletableFuture<>();
        mController.onStateChanged(CarPowerStateListener.SHUTDOWN_PREPARE, mFuture);

        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(2);
        assertThat(mController.isGarageModeActive()).isTrue();

        // Finish GarageMode and check next scheduled time
        mController.finishGarageMode();
        assertThat(mController.isGarageModeActive()).isFalse();

        for (int i = 0; i < 4; i++) {
            mFuture = new CompletableFuture<>();
            mController.onStateChanged(CarPowerStateListener.SHUTDOWN_PREPARE, mFuture);
            mController.finishGarageMode();
            assertThat(mController.isGarageModeActive()).isFalse();
            assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(i + 3);
        }
        verify(mCarPowerManagerMock, times(6)).scheduleNextWakeupTime(mIntegerCaptor.capture());
        verifyScheduledTimes(mIntegerCaptor.getAllValues());
    }

    private void verifyGarageModeBroadcast(List<Intent> intents, int times, String action) {
        // Capture sent intent and verify that it is correct
        Intent i = intents.get(times - 1);
        assertThat(i.getAction()).isEqualTo(action);

        // Verify that additional critical flags are bundled as well
        final int flags = Intent.FLAG_RECEIVER_REGISTERED_ONLY | Intent.FLAG_RECEIVER_NO_ABORT;
        boolean areRequiredFlagsSet = ((flags & i.getFlags()) == flags);
        assertThat(areRequiredFlagsSet).isTrue();
    }

    private void verifyScheduledTimes(List<Integer> ints) {
        int idx = 0;
        for (int i : ints) {
            assertThat(i).isEqualTo(sTemplateWakeupScheduleSeconds[idx++]);
        }
    }

    private void startAndAssertGarageModeWithSignal(int signal) {
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(0);

        // Sending notification that state has changed
        mController.onStateChanged(signal, mFuture);

        // Assert that GarageMode has been started
        assertThat(mController.isGarageModeActive()).isTrue();
        assertThat(mController.mWakeupPolicy.mIndex).isEqualTo(1);

        // Verify that worker that polls running jobs from JobScheduler is scheduled.
        verify(mHandlerMock).postDelayed(any(), eq(JOB_SNAPSHOT_INITIAL_UPDATE_MS));
    }

    @Test
    public void testInitAndRelease() {

        GarageMode garageMode = mock(GarageMode.class);
        Controller controller = new Controller(mContextMock, mLooperMock, mWakeupPolicy,
                mHandlerMock, garageMode);

        controller.init();
        controller.release();

        verify(garageMode).init();
        verify(garageMode).release();
    }

    @Test
    public void testConstructor() {
        Resources resourcesMock = mock(Resources.class);
        when(mContextMock.getResources()).thenReturn(resourcesMock);
        when(resourcesMock.getStringArray(R.array.config_garageModeCadence))
                .thenReturn(sTemplateWakeupSchedule);
        Controller controller = new Controller(mContextMock, mLooperMock);

        assertThat(controller).isNotNull();
    }

    @Test
    public void testScheduleNextWakeup() {
        GarageMode garageMode = mock(GarageMode.class);

        // Enter GarageMode only 1 time, no wake up after that
        WakeupPolicy wakeUpPolicy = new WakeupPolicy(new String[] { "15m,1" });

        Controller controller = new Controller(mContextMock, mLooperMock, wakeUpPolicy,
                mHandlerMock, garageMode);
        controller.setCarPowerManager(mCarPowerManagerMock);

        // Imitate entering and leavimg GarageMode
        controller.initiateGarageMode(/* future= */ null);

        controller.scheduleNextWakeup();

        verify(mCarPowerManagerMock).scheduleNextWakeupTime(900);

        // Imitate entering Garage mode after sleep
        controller.initiateGarageMode(/* future= */ null);

        // Should be no more calls to scheduleNextWakeupTime
        controller.scheduleNextWakeup();

        Mockito.verifyNoMoreInteractions(mCarPowerManagerMock);
    }

    @Test
    public void testOnStateChanged() {
        GarageMode garageMode = mock(GarageMode.class);

        Controller controller = Mockito.spy(new Controller(mContextMock, mLooperMock, mWakeupPolicy,
                mHandlerMock, garageMode));

        controller.onStateChanged(CarPowerStateListener.SHUTDOWN_CANCELLED, null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerStateListener.SHUTDOWN_ENTER, null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerStateListener.SUSPEND_ENTER, null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerStateListener.SUSPEND_EXIT, null);
        verify(controller).resetGarageMode();

        clearInvocations(controller);
        controller.onStateChanged(CarPowerStateListener.INVALID , null);
        verify(controller, never()).resetGarageMode();
    }
}
