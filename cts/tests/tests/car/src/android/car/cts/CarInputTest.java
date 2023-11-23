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

package android.car.cts;

import static android.car.CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER;
import static android.car.cts.utils.ShellPermissionUtils.runWithShellPermissionIdentity;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_DYNAMIC_ROUTING;
import static android.car.media.CarAudioManager.AUDIO_FEATURE_VOLUME_GROUP_MUTING;
import static android.car.media.CarAudioManager.CarVolumeCallback;

import static com.android.compatibility.common.util.ShellUtils.runShellCommand;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.app.Activity;
import android.app.ActivityOptions;
import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.annotation.ApiRequirements;
import android.car.media.CarAudioManager;
import android.car.test.PermissionsCheckerRule.EnsureHasPermission;
import android.content.Intent;
import android.graphics.Point;
import android.graphics.Rect;
import android.os.ConditionVariable;
import android.util.SparseArray;
import android.view.Display;
import android.view.InputEvent;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.lifecycle.Lifecycle;
import androidx.test.core.app.ActivityScenario;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.CddTest;
import com.android.compatibility.common.util.PollingCheck;
import com.android.internal.annotations.GuardedBy;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

@RunWith(AndroidJUnit4.class)
public class CarInputTest extends AbstractCarTestCase {
    private static final String TAG = CarInputTest.class.getSimpleName();
    private static final long ACTIVITY_WAIT_TIME_OUT_MS = 10_000L;
    private static final int DEFAULT_WAIT_MS = 5_000;
    private static final int NO_EVENT_WAIT_MS = 100;
    private static final String PREFIX_INJECTING_KEY_CMD = "cmd car_service inject-key";
    private static final String PREFIX_INJECTING_MOTION_CMD = "cmd car_service inject-motion";
    private static final String OPTION_SEAT = " -s ";
    private static final String OPTION_ACTION = " -a ";
    private static final String OPTION_COUNT = " -c ";
    private static final String OPTION_POINTER_ID = " -p ";

    private CarOccupantZoneManager mCarOccupantZoneManager;
    private SparseArray<ActivityScenario<TestActivity>> mActivityScenariosPerDisplay =
            new SparseArray<>();
    private SparseArray<TestActivity> mActivitiesPerDisplay = new SparseArray<>();

    @Before
    public void setUp() throws Exception {
        mCarOccupantZoneManager =
                (CarOccupantZoneManager) getCar().getCarManager(Car.CAR_OCCUPANT_ZONE_SERVICE);
    }

    @After
    public void tearDown() throws Exception {
        clearActivities();
    }

    private void clearActivities() {
        for (int i = 0; i < mActivityScenariosPerDisplay.size(); i++) {
            mActivityScenariosPerDisplay.valueAt(i).close();
        }
        mActivityScenariosPerDisplay.clear();
        mActivitiesPerDisplay.clear();
    }

    @Test
    @CddTest(requirements = {"TODO(b/262236403)"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void testHomeKeyForEachPassengerMainDisplay_bringsHomeForTheDisplayOnly()
            throws Exception {
        forEachPassengerMainDisplay((zone, display) -> {
            launchActivitiesOnAllMainDisplays();
            int targetDisplayId = display.getDisplayId();

            injectKeyByShell(zone, KeyEvent.KEYCODE_HOME);

            PollingCheck.waitFor(DEFAULT_WAIT_MS, () -> {
                return mActivityScenariosPerDisplay.get(targetDisplayId).getState()
                        != Lifecycle.State.RESUMED;
            }, "Unable to reach home screen on display " + targetDisplayId);
            for (int i = 0; i < mActivityScenariosPerDisplay.size(); i++) {
                int displayId = mActivityScenariosPerDisplay.keyAt(i);
                if (displayId == targetDisplayId) {
                    continue;
                }
                assertWithMessage("Home key should not affect the other displays."
                        + " Home key was injected to display " + targetDisplayId + ", but display "
                        + displayId + " was affected.")
                        .that(mActivityScenariosPerDisplay.valueAt(i).getState())
                        .isEqualTo(Lifecycle.State.RESUMED);
            }
            // Recreate the test activity for next test
            clearActivities();
        });
    }

    @Test
    @CddTest(requirements = {"TODO(b/262236403)"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void testBackKeyForEachPassengerMainDisplay() throws Exception {
        launchActivitiesOnAllMainDisplays();
        forEachPassengerMainDisplay((zone, display) -> {
            int displayId = display.getDisplayId();

            injectKeyByShell(zone, KeyEvent.KEYCODE_BACK);

            assertReceivedKeyCode(displayId, KeyEvent.KEYCODE_BACK);
        });
    }

    @Test
    @CddTest(requirements = {"TODO(b/262236403)"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void testAKeyForEachPassengerMainDisplay() throws Exception {
        launchActivitiesOnAllMainDisplays();
        forEachPassengerMainDisplay((zone, display) -> {
            int displayId = display.getDisplayId();

            injectKeyByShell(zone, KeyEvent.KEYCODE_A);

            assertReceivedKeyCode(displayId, KeyEvent.KEYCODE_A);
        });
    }

    @Test
    @CddTest(requirements = {"TODO(b/262236403)"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void testPowerKeyForEachPassengerMainDisplay() throws Exception {
        forEachPassengerMainDisplay((zone, display) -> {
            // Screen off
            injectKeyByShell(zone, KeyEvent.KEYCODE_POWER);
            PollingCheck.waitFor(DEFAULT_WAIT_MS, () -> {
                return display.getState() == Display.STATE_OFF;
            }, "Display state should be off");

            // Screen on
            injectKeyByShell(zone, KeyEvent.KEYCODE_POWER);
            PollingCheck.waitFor(DEFAULT_WAIT_MS, () -> {
                return display.getState() == Display.STATE_ON;
            }, "Display state should be on");
        });
    }

    @Test
    @CddTest(requirements = {"TODO(b/262236403)"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    public void testVolumeDownKeyForEachPassengerMainDisplay() throws Exception {
        CarAudioManager audioManager = (CarAudioManager) getCar().getCarManager(Car.AUDIO_SERVICE);
        assumeTrue(audioManager.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING));
        CarVolumeMonitor callback = new CarVolumeMonitor();
        audioManager.registerCarVolumeCallback(callback);

        try {
            forEachPassengerMainDisplay((zone, display) -> {
                injectKeyByShell(zone, KeyEvent.KEYCODE_VOLUME_DOWN);

                assertWithMessage("CarVolumeCallback#onGroupVolumeChanged should be called")
                        .that(callback.receivedGroupVolumeChanged(zone.zoneId))
                        .isTrue();
                callback.reset();
            });
        } finally {
            audioManager.unregisterCarVolumeCallback(callback);
        }
    }

    @Test
    @CddTest(requirements = {"TODO(b/262236403)"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    public void testVolumeUpKeyForEachPassengerMainDisplay() throws Exception {
        CarAudioManager audioManager = (CarAudioManager) getCar().getCarManager(Car.AUDIO_SERVICE);
        assumeTrue(audioManager.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING));
        CarVolumeMonitor callback = new CarVolumeMonitor();
        audioManager.registerCarVolumeCallback(callback);

        try {
            forEachPassengerMainDisplay((zone, display) -> {
                injectKeyByShell(zone, KeyEvent.KEYCODE_VOLUME_UP);

                assertWithMessage("CarVolumeCallback#onGroupVolumeChanged should be called")
                        .that(callback.receivedGroupVolumeChanged(zone.zoneId))
                        .isTrue();
                callback.reset();
            });
        } finally {
            audioManager.unregisterCarVolumeCallback(callback);
        }
    }

    @Test
    @CddTest(requirements = {"TODO(b/262236403)"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    @EnsureHasPermission(Car.PERMISSION_CAR_CONTROL_AUDIO_VOLUME)
    public void testVolumeMuteKeyForEachPassengerMainDisplay() throws Exception {
        CarAudioManager audioManager = (CarAudioManager) getCar().getCarManager(Car.AUDIO_SERVICE);
        assumeTrue(audioManager.isAudioFeatureEnabled(AUDIO_FEATURE_DYNAMIC_ROUTING));
        assumeTrue(audioManager.isAudioFeatureEnabled(AUDIO_FEATURE_VOLUME_GROUP_MUTING));
        CarVolumeMonitor callback = new CarVolumeMonitor();
        audioManager.registerCarVolumeCallback(callback);

        try {
            forEachPassengerMainDisplay((zone, display) -> {
                try {
                    injectKeyByShell(zone, KeyEvent.KEYCODE_VOLUME_MUTE);

                    assertWithMessage("CarVolumeCallback#onMasterMuteChanged should be called")
                            .that(callback.receivedGroupMuteChanged(zone.zoneId))
                            .isTrue();
                    assertThat(audioManager.isVolumeGroupMuted(zone.zoneId, callback.groupId))
                            .isTrue();
                } finally {
                    injectKeyByShell(zone, KeyEvent.KEYCODE_VOLUME_MUTE);
                }
            });
        } finally {
            audioManager.unregisterCarVolumeCallback(callback);
        }
    }

    @Test
    @CddTest(requirements = {"TODO(b/262236403)"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void testSingleTouchForEachPassengerMainDisplay() throws Exception {
        launchActivitiesOnAllMainDisplays();
        forEachPassengerMainDisplay((zone, display) -> {
            int displayId = display.getDisplayId();
            Point pointer1 = getDisplayCenter(displayId);

            injectTouchByShell(zone, MotionEvent.ACTION_DOWN, pointer1);
            assertReceivedMotionAction(displayId, MotionEvent.ACTION_DOWN);

            pointer1.offset(1, 1);
            injectTouchByShell(zone, MotionEvent.ACTION_MOVE, pointer1);
            assertReceivedMotionAction(displayId, MotionEvent.ACTION_MOVE);

            injectTouchByShell(zone, MotionEvent.ACTION_UP, pointer1);
            assertReceivedMotionAction(displayId, MotionEvent.ACTION_UP);
        });
    }

    @Test
    @CddTest(requirements = {"TODO(b/262236403)"})
    @ApiRequirements(minCarVersion = ApiRequirements.CarVersion.UPSIDE_DOWN_CAKE_0,
            minPlatformVersion = ApiRequirements.PlatformVersion.UPSIDE_DOWN_CAKE_0)
    public void testMultiTouchForEachPassengerMainDisplay() throws Exception {
        launchActivitiesOnAllMainDisplays();
        forEachPassengerMainDisplay((zone, display) -> {
            int displayId = display.getDisplayId();
            Point pointer1 = getDisplayCenter(displayId);
            Point pointer2 = getDisplayCenter(displayId);
            pointer2.offset(100, 100);
            Point[] pointers = new Point[] {pointer1, pointer2};

            injectTouchByShell(zone, MotionEvent.ACTION_DOWN, pointer1);
            assertReceivedMotionAction(displayId, MotionEvent.ACTION_DOWN);

            injectTouchByShell(zone, MotionEvent.ACTION_POINTER_DOWN, pointers);
            assertReceivedMotionAction(displayId, MotionEvent.ACTION_POINTER_DOWN);

            pointer2.offset(1, 1);
            injectTouchByShell(zone, MotionEvent.ACTION_MOVE, pointers);
            assertReceivedMotionAction(displayId, MotionEvent.ACTION_MOVE);

            injectTouchByShell(zone, MotionEvent.ACTION_POINTER_UP, pointers);
            assertReceivedMotionAction(displayId, MotionEvent.ACTION_POINTER_UP);

            injectTouchByShell(zone, MotionEvent.ACTION_UP, pointer1);
            assertReceivedMotionAction(displayId, MotionEvent.ACTION_UP);
        });
    }

    private void assertReceivedKeyCode(int displayId, int keyCode) throws Exception {
        InputEvent downEvent = mActivitiesPerDisplay.get(displayId).getInputEvent();
        InputEvent upEvent = mActivitiesPerDisplay.get(displayId).getInputEvent();
        assertWithMessage("Activity on display " + displayId + " must receive key event, keyCode="
                + KeyEvent.keyCodeToString(keyCode))
                .that(downEvent instanceof KeyEvent).isTrue();
        assertWithMessage("Activity on display " + displayId + " must receive key event, keyCode="
                + KeyEvent.keyCodeToString(keyCode))
                .that(upEvent instanceof KeyEvent).isTrue();

        KeyEvent downKey = (KeyEvent) downEvent;
        KeyEvent upKey = (KeyEvent) upEvent;
        assertWithMessage("Activity on display " + displayId
                + " must receive " + KeyEvent.keyCodeToString(keyCode))
                .that(downKey.getKeyCode()).isEqualTo(keyCode);
        assertWithMessage("Activity on display " + displayId
                + " must receive down event, keyCode=" + KeyEvent.keyCodeToString(keyCode))
                .that(downKey.getAction()).isEqualTo(KeyEvent.ACTION_DOWN);
        assertWithMessage("Activity on display " + displayId
                + " must receive " + KeyEvent.keyCodeToString(keyCode))
                .that(upKey.getKeyCode()).isEqualTo(keyCode);
        assertWithMessage("Activity on display " + displayId
                + " must receive up event, keyCode=" + KeyEvent.keyCodeToString(keyCode))
                .that(upKey.getAction()).isEqualTo(KeyEvent.ACTION_UP);

        assertNoEventsExceptFor(displayId);
    }

    private void assertReceivedMotionAction(int displayId, int actionMasked) throws Exception {
        InputEvent event = mActivitiesPerDisplay.get(displayId).getInputEvent();
        assertWithMessage("Activity on display " + displayId + " must receive motion event, action="
                + MotionEvent.actionToString(actionMasked))
                .that(event instanceof MotionEvent).isTrue();
        MotionEvent motionEvent = (MotionEvent) event;
        assertWithMessage("Activity on display " + displayId
                + " must receive " + MotionEvent.actionToString(actionMasked))
                .that(motionEvent.getActionMasked()).isEqualTo(actionMasked);
        assertNoEventsExceptFor(displayId);
    }

    private void assertNoEventsExceptFor(int displayId) throws Exception {
        for (int i = 0; i < mActivitiesPerDisplay.size(); i++) {
            if (mActivitiesPerDisplay.keyAt(i) == displayId) {
                continue;
            }
            mActivitiesPerDisplay.valueAt(i).assertNoEvents();
        }
    }

    private void assertNoEvents(int displayId) throws Exception {
        mActivitiesPerDisplay.get(displayId).assertNoEvents();
    }

    private void launchActivitiesOnAllMainDisplays() throws Exception {
        // Launch the TestActivity on all main displays for driver and passenger
        forEachMainDisplay(/* includesDriver= */ true, (zone, display) -> {
            launchActivity(display.getDisplayId());
        });
    }

    private void launchActivity(int displayId) {
        ConditionVariable activityReferenceObtained = new ConditionVariable();
        // Uses ShellPermisson to launch an Activitiy on the different displays.
        runWithShellPermissionIdentity(() -> {
            Intent intent = new Intent(mContext, TestActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            ActivityScenario<TestActivity> activityScenario = ActivityScenario.launch(intent,
                    ActivityOptions.makeBasic().setLaunchDisplayId(displayId).toBundle());
            activityScenario.onActivity(activity -> {
                mActivitiesPerDisplay.put(displayId, activity);
                activityReferenceObtained.open();
            });
            mActivityScenariosPerDisplay.put(displayId, activityScenario);
        });
        activityReferenceObtained.block(ACTIVITY_WAIT_TIME_OUT_MS);
        assertWithMessage("Failed to acquire activity reference.")
                .that(mActivitiesPerDisplay.get(displayId))
                .isNotNull();
    }

    private static void injectKeyByShell(OccupantZoneInfo zone, int keyCode) {
        assumeNotNull(zone);

        // Generate a command message
        runShellCommand(PREFIX_INJECTING_KEY_CMD + OPTION_SEAT + zone.seat + ' ' + keyCode);
    }

    private static void injectTouchByShell(OccupantZoneInfo zone, int action, Point p) {
        injectTouchByShell(zone, action, new Point[] {p});
    }

    private static void injectTouchByShell(OccupantZoneInfo zone, int action, Point[] p) {
        assumeNotNull(zone);

        int pointerCount = p.length;
        if (action == MotionEvent.ACTION_POINTER_DOWN || action == MotionEvent.ACTION_POINTER_UP) {
            int index = p.length - 1;
            action = (index << MotionEvent.ACTION_POINTER_INDEX_SHIFT) + action;
        }

        // Generate a command message
        StringBuilder sb = new StringBuilder()
                .append(PREFIX_INJECTING_MOTION_CMD)
                .append(OPTION_SEAT)
                .append(zone.seat)
                .append(OPTION_ACTION)
                .append(action)
                .append(OPTION_COUNT)
                .append(pointerCount);
        sb.append(OPTION_POINTER_ID);
        for (int i = 0; i < pointerCount; i++) {
            sb.append(i);
            sb.append(' ');
        }
        for (int i = 0; i < pointerCount; i++) {
            sb.append(p[i].x);
            sb.append(' ');
            sb.append(p[i].y);
            sb.append(' ');
        }
        runShellCommand(sb.toString());
    }

    private Point getDisplayCenter(int displayId) {
        Rect rect = mActivitiesPerDisplay.get(displayId).getWindowManager()
                .getCurrentWindowMetrics().getBounds();
        return new Point(rect.width() / 2, rect.height() / 2);
    }

    private void forEachPassengerMainDisplay(ThrowingBiConsumer<OccupantZoneInfo, Display> consumer)
            throws Exception {
        forEachMainDisplay(/* includesDriver= */ false, consumer);
    }

    private void forEachMainDisplay(boolean includesDriver,
            ThrowingBiConsumer<OccupantZoneInfo, Display> consumer) throws Exception {
        assumeTrue("No passenger zones", mCarOccupantZoneManager.hasPassengerZones());
        List<OccupantZoneInfo> zones = mCarOccupantZoneManager.getAllOccupantZones();
        for (OccupantZoneInfo zone : zones) {
            if (!includesDriver && zone.occupantType == OCCUPANT_TYPE_DRIVER) {
                continue;
            }
            Display display = mCarOccupantZoneManager.getDisplayForOccupant(zone,
                    CarOccupantZoneManager.DISPLAY_TYPE_MAIN);
            if (display == null) {
                continue;
            }
            consumer.acceptOrThrow(zone, display);
        }
    }

    /**
     * A {@link BiConsumer} that allows throwing checked exceptions from its single abstract method.
     *
     * Can be used together with {@link #uncheckExceptions} to effectively turn a lambda expression
     * that throws a checked exception into a regular {@link BiConsumer}
     */
    @FunctionalInterface
    @SuppressWarnings("FunctionalInterfaceMethodChanged")
    public interface ThrowingBiConsumer<T, U> extends BiConsumer<T, U> {
        void acceptOrThrow(T t, U u) throws Exception;

        @Override
        default void accept(T t, U u) {
            try {
                acceptOrThrow(t, u);
            } catch (Exception ex) {
                throw new RuntimeException(ex);
            }
        }
    }

    public static class TestActivity extends Activity {
        private LinkedBlockingQueue<InputEvent> mEvents = new LinkedBlockingQueue<>();

        @Override
        public boolean dispatchTouchEvent(MotionEvent ev) {
            mEvents.add(MotionEvent.obtain(ev));
            return true;
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            mEvents.add(new KeyEvent(event));
            return true;
        }

        public InputEvent getInputEvent() throws InterruptedException {
            return mEvents.poll(DEFAULT_WAIT_MS, TimeUnit.MILLISECONDS);
        }

        public void assertNoEvents() throws InterruptedException {
            InputEvent event = mEvents.poll(NO_EVENT_WAIT_MS, TimeUnit.MILLISECONDS);
            assertWithMessage("Expected no events, but received %s", event).that(event).isNull();
        }
    }

    private static final class CarVolumeMonitor extends CarVolumeCallback {
        // Copied from {@link android.car.CarOccupantZoneManager.OccupantZoneInfo#INVALID_ZONE_ID}
        private static final int INVALID_ZONE_ID = -1;
        // Copied from {@link android.car.media.CarAudioManager#INVALID_VOLUME_GROUP_ID}
        private static final int INVALID_VOLUME_GROUP_ID = -1;
        private final Object mLock = new Object();
        @GuardedBy("mLock")
        private CountDownLatch mGroupVolumeChangeLatch = new CountDownLatch(1);
        @GuardedBy("mLock")
        private CountDownLatch mGroupMuteChangeLatch = new CountDownLatch(1);

        public int zoneId = INVALID_ZONE_ID;
        public int groupId = INVALID_VOLUME_GROUP_ID;

        boolean receivedGroupVolumeChanged(int zoneId) throws InterruptedException {
            CountDownLatch countDownLatch;
            synchronized (mLock) {
                countDownLatch = mGroupVolumeChangeLatch;
            }
            boolean succeed = countDownLatch.await(DEFAULT_WAIT_MS, TimeUnit.MILLISECONDS);
            return succeed && this.zoneId == zoneId;
        }

        boolean receivedGroupMuteChanged(int zoneId) throws InterruptedException {
            CountDownLatch countDownLatch;
            synchronized (mLock) {
                countDownLatch = mGroupMuteChangeLatch;
            }
            boolean succeed = countDownLatch.await(DEFAULT_WAIT_MS, TimeUnit.MILLISECONDS);
            return succeed && this.zoneId == zoneId;
        }

        void reset() {
            synchronized (mLock) {
                mGroupVolumeChangeLatch = new CountDownLatch(1);
                mGroupMuteChangeLatch = new CountDownLatch(1);
                zoneId = INVALID_ZONE_ID;
                groupId = INVALID_VOLUME_GROUP_ID;
            }
        }

        @Override
        public void onGroupVolumeChanged(int zoneId, int groupId, int flags) {
            synchronized (mLock) {
                this.zoneId = zoneId;
                this.groupId = groupId;
                mGroupVolumeChangeLatch.countDown();
            }
        }

        @Override
        public void onGroupMuteChanged(int zoneId, int groupId, int flags) {
            synchronized (mLock) {
                this.zoneId = zoneId;
                this.groupId = groupId;
                mGroupMuteChangeLatch.countDown();
            }
        }
    }
}
