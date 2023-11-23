/*
 * Copyright (C) 2016 The Android Open Source Project
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
 * limitations under the License
 */

package android.server.wm;

import static android.app.WindowConfiguration.ACTIVITY_TYPE_STANDARD;
import static android.app.WindowConfiguration.WINDOWING_MODE_SPLIT_SCREEN_PRIMARY;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
import static android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT;
import static android.server.wm.ComponentNameUtils.getActivityName;
import static android.server.wm.ComponentNameUtils.getWindowName;
import static android.server.wm.UiDeviceUtils.pressBackButton;
import static android.server.wm.UiDeviceUtils.pressHomeButton;
import static android.server.wm.app.Components.BROADCAST_RECEIVER_ACTIVITY;
import static android.server.wm.app.Components.DISMISS_KEYGUARD_ACTIVITY;
import static android.server.wm.app.Components.DISMISS_KEYGUARD_METHOD_ACTIVITY;
import static android.server.wm.app.Components.INHERIT_SHOW_WHEN_LOCKED_ADD_ACTIVITY;
import static android.server.wm.app.Components.INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY;
import static android.server.wm.app.Components.INHERIT_SHOW_WHEN_LOCKED_REMOVE_ACTIVITY;
import static android.server.wm.app.Components.KEYGUARD_LOCK_ACTIVITY;
import static android.server.wm.app.Components.LAUNCHING_ACTIVITY;
import static android.server.wm.app.Components.NO_INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_ACTIVITY;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_ATTR_ACTIVITY;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_ATTR_ROTATION_ACTIVITY;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_DIALOG_ACTIVITY;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_WITH_DIALOG_ACTIVITY;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.app.Components.TURN_SCREEN_ON_ATTR_DISMISS_KEYGUARD_ACTIVITY;
import static android.server.wm.app.Components.TURN_SCREEN_ON_DISMISS_KEYGUARD_ACTIVITY;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Surface.ROTATION_90;
import static android.view.WindowManager.LayoutParams.TYPE_WALLPAPER;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.content.ComponentName;
import android.content.res.Configuration;
import android.hardware.display.AmbientDisplayConfiguration;
import android.platform.test.annotations.Presubmit;
import android.provider.Settings;
import android.server.wm.CommandSession.ActivitySession;
import android.server.wm.CommandSession.ActivitySessionClient;
import android.server.wm.WindowManagerState.WindowState;
import android.server.wm.settings.SettingsSession;

import androidx.test.filters.FlakyTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:KeyguardTests
 */
@Presubmit
public class KeyguardTests extends KeyguardTestBase {
    class AodSession extends SettingsSession<Integer> {
        private AmbientDisplayConfiguration mConfig;

        AodSession() {
            super(Settings.Secure.getUriFor(Settings.Secure.DOZE_ALWAYS_ON),
                    Settings.Secure::getInt,
                    Settings.Secure::putInt);
            mConfig = new AmbientDisplayConfiguration(mContext);
        }

        boolean isAodAvailable() {
            return mConfig.alwaysOnAvailable();
        }

        void setAodEnabled(boolean enabled) throws Exception {
            set(enabled ? 1 : 0);
        }
    }

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();
        assumeTrue(supportsInsecureLock());
        assertFalse(isUiModeLockedToVrHeadset());
    }

    @Test
    public void testKeyguardHidesActivity() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(TEST_ACTIVITY);
            mAmWmState.computeState(TEST_ACTIVITY);
            mAmWmState.assertVisibility(TEST_ACTIVITY, true);
            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            assertTrue(mKeyguardManager.isKeyguardLocked());
            mAmWmState.assertVisibility(TEST_ACTIVITY, false);
        }
        assertFalse(mKeyguardManager.isKeyguardLocked());
    }

    @Test
    @FlakyTest(bugId = 110276714)
    public void testShowWhenLockedActivity() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            lockScreenSession.gotoKeyguard(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            mAmWmState.assertKeyguardShowingAndOccluded();
        }
    }

    /**
     * Tests whether dialogs from SHOW_WHEN_LOCKED activities are also visible if Keyguard is
     * showing.
     */
    @Test
    public void testShowWhenLockedActivity_withDialog() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(SHOW_WHEN_LOCKED_WITH_DIALOG_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_WITH_DIALOG_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_WITH_DIALOG_ACTIVITY, true);
            lockScreenSession.gotoKeyguard(SHOW_WHEN_LOCKED_WITH_DIALOG_ACTIVITY);
            mAmWmState.computeState(true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_WITH_DIALOG_ACTIVITY, true);
            assertTrue(mAmWmState.getWmState().allWindowsVisible(
                    getWindowName(SHOW_WHEN_LOCKED_WITH_DIALOG_ACTIVITY)));
            mAmWmState.assertKeyguardShowingAndOccluded();
        }
    }

    /**
     * Tests whether multiple SHOW_WHEN_LOCKED activities are shown if the topmost is translucent.
     */
    @Test
    public void testMultipleShowWhenLockedActivities() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(SHOW_WHEN_LOCKED_ACTIVITY);
            launchActivity(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ACTIVITY,
                    SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY, true);
            lockScreenSession.gotoKeyguard(
                    SHOW_WHEN_LOCKED_ACTIVITY, SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY);
            mAmWmState.computeState(true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY, true);
            mAmWmState.assertKeyguardShowingAndOccluded();
        }
    }

    /**
     * If we have a translucent SHOW_WHEN_LOCKED_ACTIVITY, the wallpaper should also be showing.
     */
    @Test
    public void testTranslucentShowWhenLockedActivity() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY, true);
            lockScreenSession.gotoKeyguard(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY);
            mAmWmState.computeState(true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY, true);
            assertWallpaperShowing();
            mAmWmState.assertKeyguardShowingAndOccluded();
        }
    }

    /**
     * If we have a translucent SHOW_WHEN_LOCKED activity, the activity behind should not be shown.
     */
    @Test
    @FlakyTest
    public void testTranslucentDoesntRevealBehind() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(TEST_ACTIVITY);
            launchActivity(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY);
            mAmWmState.computeState(TEST_ACTIVITY, SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY);
            mAmWmState.assertVisibility(TEST_ACTIVITY, true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY, true);
            lockScreenSession.gotoKeyguard(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY);
            mAmWmState.computeState(true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_TRANSLUCENT_ACTIVITY, true);
            mAmWmState.assertVisibility(TEST_ACTIVITY, false);
            mAmWmState.assertKeyguardShowingAndOccluded();
        }
    }

    @Test
    public void testDialogShowWhenLockedActivity() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(SHOW_WHEN_LOCKED_DIALOG_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_DIALOG_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_DIALOG_ACTIVITY, true);
            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_DIALOG_ACTIVITY, true);
            assertWallpaperShowing();
            mAmWmState.assertKeyguardShowingAndOccluded();
        }
    }

    /**
     * Test that showWhenLocked activity is fullscreen when shown over keyguard
     */
    @Test
    @Presubmit
    public void testShowWhenLockedActivityWhileSplit() throws Exception {
        assumeTrue(supportsSplitScreenMultiWindow());

        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivitiesInSplitScreen(
                    getLaunchActivityBuilder().setTargetActivity(LAUNCHING_ACTIVITY),
                    getLaunchActivityBuilder().setTargetActivity(SHOW_WHEN_LOCKED_ACTIVITY)
                            .setRandomData(true)
                            .setMultipleTask(false)
            );
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            lockScreenSession.gotoKeyguard(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            mAmWmState.assertKeyguardShowingAndOccluded();
            mAmWmState.assertDoesNotContainStack("Activity must be full screen.",
                    WINDOWING_MODE_SPLIT_SCREEN_PRIMARY, ACTIVITY_TYPE_STANDARD);
        }
    }

    /**
     * Tests whether an activity that has called setInheritShowWhenLocked(true) above a
     * SHOW_WHEN_LOCKED activity is visible if Keyguard is locked.
     */
    @Test
    @FlakyTest
    public void testInheritShowWhenLockedAdd() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, true);

            launchActivity(INHERIT_SHOW_WHEN_LOCKED_ADD_ACTIVITY);
            mAmWmState.computeState(
                    SHOW_WHEN_LOCKED_ATTR_ACTIVITY, INHERIT_SHOW_WHEN_LOCKED_ADD_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, false);
            mAmWmState.assertVisibility(INHERIT_SHOW_WHEN_LOCKED_ADD_ACTIVITY, true);

            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            mAmWmState.assertKeyguardShowingAndOccluded();
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, false);
            mAmWmState.assertVisibility(INHERIT_SHOW_WHEN_LOCKED_ADD_ACTIVITY, true);
        }
    }

    /**
     * Tests whether an activity that has the manifest attribute inheritShowWhenLocked but then
     * calls setInheritShowWhenLocked(false) above a SHOW_WHEN_LOCKED activity is invisible if
     * Keyguard is locked.
     */
    @Test
    @FlakyTest
    public void testInheritShowWhenLockedRemove() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, true);

            launchActivity(INHERIT_SHOW_WHEN_LOCKED_REMOVE_ACTIVITY);
            mAmWmState.computeState(
                    SHOW_WHEN_LOCKED_ATTR_ACTIVITY, INHERIT_SHOW_WHEN_LOCKED_REMOVE_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, false);
            mAmWmState.assertVisibility(INHERIT_SHOW_WHEN_LOCKED_REMOVE_ACTIVITY, true);

            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            assertTrue(mKeyguardManager.isKeyguardLocked());
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, false);
            mAmWmState.assertVisibility(INHERIT_SHOW_WHEN_LOCKED_REMOVE_ACTIVITY, false);
        }
    }

    /**
     * Tests whether an activity that has the manifest attribute inheritShowWhenLocked above a
     * SHOW_WHEN_LOCKED activity is visible if Keyguard is locked.
     * */
    @Test
    @FlakyTest
    public void testInheritShowWhenLockedAttr() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, true);

            launchActivity(INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
            mAmWmState.computeState(
                    SHOW_WHEN_LOCKED_ATTR_ACTIVITY, INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, false);
            mAmWmState.assertVisibility(INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY, true);

            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            mAmWmState.assertKeyguardShowingAndOccluded();
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, false);
            mAmWmState.assertVisibility(INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY, true);
        }
    }

    /**
     * Tests whether an activity that doesn't have the manifest attribute inheritShowWhenLocked
     * above a SHOW_WHEN_LOCKED activity is invisible if Keyguard is locked.
     * */
    @Test
    @FlakyTest
    public void testNoInheritShowWhenLocked() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            launchActivity(SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, true);

            launchActivity(NO_INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
            mAmWmState.computeState(
                    SHOW_WHEN_LOCKED_ATTR_ACTIVITY, NO_INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, false);
            mAmWmState.assertVisibility(NO_INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY, true);

            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            assertTrue(mKeyguardManager.isKeyguardLocked());
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, false);
            mAmWmState.assertVisibility(NO_INHERIT_SHOW_WHEN_LOCKED_ATTR_ACTIVITY, false);
        }
    }

    @Test
    public void testNoTransientConfigurationWhenShowWhenLockedRequestsOrientation() {
        try (final LockScreenSession lockScreenSession = new LockScreenSession();
                final ActivitySessionClient activitySession = new ActivitySessionClient(mContext)) {
            final ActivitySession showWhenLockedActivitySession =
                    activitySession.startActivity(getLaunchActivityBuilder()
                            .setUseInstrumentation()
                            .setTargetActivity(SHOW_WHEN_LOCKED_ATTR_ROTATION_ACTIVITY));
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ATTR_ROTATION_ACTIVITY, true);

            lockScreenSession.gotoKeyguard(SHOW_WHEN_LOCKED_ATTR_ROTATION_ACTIVITY);

            separateTestJournal();

            final int displayId = mAmWmState.getAmState()
                    .getDisplayByActivity(SHOW_WHEN_LOCKED_ATTR_ROTATION_ACTIVITY);
            ActivityManagerState.ActivityDisplay display = mAmWmState.getAmState()
                    .getDisplay(displayId);
            final int origDisplayOrientation = display.mFullConfiguration.orientation;
            final int orientation = origDisplayOrientation == Configuration.ORIENTATION_LANDSCAPE
                    ? SCREEN_ORIENTATION_PORTRAIT
                    : SCREEN_ORIENTATION_LANDSCAPE;
            showWhenLockedActivitySession.requestOrientation(orientation);

            mAmWmState.waitForActivityOrientation(SHOW_WHEN_LOCKED_ATTR_ROTATION_ACTIVITY,
                    orientation == SCREEN_ORIENTATION_LANDSCAPE
                            ? Configuration.ORIENTATION_LANDSCAPE
                            : Configuration.ORIENTATION_PORTRAIT);

            display = mAmWmState.getAmState().getDisplay(displayId);

            // If the window is a non-fullscreen window (e.g. a freeform window) or the display is
            // squared, there won't be activity lifecycle.
            if (display.mFullConfiguration.orientation != origDisplayOrientation) {
                assertActivityLifecycle(SHOW_WHEN_LOCKED_ATTR_ROTATION_ACTIVITY,
                        false /* relaunched */);
            }
        }
    }

    /**
     * Test that when a normal activity finished and an existing FLAG_DISMISS_KEYGUARD activity
     * becomes the top activity, it should be resumed.
     */
    @Test
    @FlakyTest
    public void testResumeDismissKeyguardActivityFromBackground() {
        testResumeOccludingActivityFromBackground(DISMISS_KEYGUARD_ACTIVITY);
    }

    /**
     * Test that when a normal activity finished and an existing SHOW_WHEN_LOCKED activity becomes
     * the top activity, it should be resumed.
     */
    @Test
    public void testResumeShowWhenLockedActivityFromBackground() {
        testResumeOccludingActivityFromBackground(SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
    }

    private void testResumeOccludingActivityFromBackground(ComponentName occludingActivity) {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();

            // Launch an activity which is able to occlude keyguard.
            getLaunchActivityBuilder().setUseInstrumentation()
                    .setTargetActivity(occludingActivity).execute();

            // Launch an activity without SHOW_WHEN_LOCKED and finish it.
            getLaunchActivityBuilder().setUseInstrumentation()
                    .setMultipleTask(true)
                    // Don't wait for activity visible because keyguard will show.
                    .setWaitForLaunched(false)
                    .setTargetActivity(BROADCAST_RECEIVER_ACTIVITY).execute();
            mAmWmState.waitForKeyguardShowingAndNotOccluded();
            mAmWmState.assertKeyguardShowingAndNotOccluded();

            mBroadcastActionTrigger.finishBroadcastReceiverActivity();
            mAmWmState.waitForKeyguardShowingAndOccluded();

            // The occluding activity should be resumed because it becomes the top activity.
            mAmWmState.computeState(occludingActivity);
            mAmWmState.assertVisibility(occludingActivity, true);
            assertTrue(occludingActivity + " must be resumed.",
                    mAmWmState.getAmState().hasActivityState(occludingActivity,
                            ActivityManagerState.STATE_RESUMED));
        }
    }

    /**
     * Tests whether a FLAG_DISMISS_KEYGUARD activity occludes Keyguard.
     */
    @Test
    public void testDismissKeyguardActivity() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            launchActivity(DISMISS_KEYGUARD_ACTIVITY);
            mAmWmState.waitForKeyguardShowingAndOccluded();
            mAmWmState.computeState(DISMISS_KEYGUARD_ACTIVITY);
            mAmWmState.assertVisibility(DISMISS_KEYGUARD_ACTIVITY, true);
            mAmWmState.assertKeyguardShowingAndOccluded();
        }
    }

    @Test
    public void testDismissKeyguardActivity_method() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            separateTestJournal();
            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            launchActivity(DISMISS_KEYGUARD_METHOD_ACTIVITY);
            mAmWmState.waitForKeyguardGone();
            mAmWmState.computeState(DISMISS_KEYGUARD_METHOD_ACTIVITY);
            mAmWmState.assertVisibility(DISMISS_KEYGUARD_METHOD_ACTIVITY, true);
            assertFalse(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            assertOnDismissSucceeded(DISMISS_KEYGUARD_METHOD_ACTIVITY);
        }
    }

    @Test
    public void testDismissKeyguardActivity_method_notTop() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            separateTestJournal();
            lockScreenSession.gotoKeyguard();
            mAmWmState.computeState(true);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            launchActivity(BROADCAST_RECEIVER_ACTIVITY);
            launchActivity(TEST_ACTIVITY);
            mBroadcastActionTrigger.dismissKeyguardByMethod();
            assertOnDismissError(BROADCAST_RECEIVER_ACTIVITY);
        }
    }

    @Test
    public void testDismissKeyguardActivity_method_turnScreenOn() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            separateTestJournal();
            lockScreenSession.sleepDevice();
            mAmWmState.computeState(true);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            launchActivity(TURN_SCREEN_ON_DISMISS_KEYGUARD_ACTIVITY);
            mAmWmState.waitForKeyguardGone();
            mAmWmState.computeState(TURN_SCREEN_ON_DISMISS_KEYGUARD_ACTIVITY);
            mAmWmState.assertVisibility(TURN_SCREEN_ON_DISMISS_KEYGUARD_ACTIVITY, true);
            assertFalse(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            assertOnDismissSucceeded(TURN_SCREEN_ON_DISMISS_KEYGUARD_ACTIVITY);
            assertTrue(isDisplayOn(DEFAULT_DISPLAY));
        }
    }

    @Test
    public void testDismissKeyguard_fromShowWhenLocked_notAllowed() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            launchActivity(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            mAmWmState.assertKeyguardShowingAndOccluded();
            mBroadcastActionTrigger.dismissKeyguardByFlag();
            mAmWmState.assertKeyguardShowingAndOccluded();
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
        }
    }

    @Test
    public void testKeyguardLock() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            launchActivity(KEYGUARD_LOCK_ACTIVITY);
            mAmWmState.computeState(KEYGUARD_LOCK_ACTIVITY);
            mAmWmState.assertVisibility(KEYGUARD_LOCK_ACTIVITY, true);
            mBroadcastActionTrigger.finishBroadcastReceiverActivity();
            mAmWmState.waitForKeyguardShowingAndNotOccluded();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
        }
    }

    @Test
    public void testUnoccludeRotationChange() throws Exception {

        // Go home now to make sure Home is behind Keyguard.
        pressHomeButton();
        try (final LockScreenSession lockScreenSession = new LockScreenSession();
             final RotationSession rotationSession = new RotationSession()) {
            lockScreenSession.gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            launchActivity(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);

            rotationSession.set(ROTATION_90);
            pressBackButton();
            mAmWmState.waitForKeyguardShowingAndNotOccluded();
            mAmWmState.waitForDisplayUnfrozen();
            mAmWmState.waitForAppTransitionIdleOnDisplay(DEFAULT_DISPLAY);
            mAmWmState.assertSanity();
            mAmWmState.assertHomeActivityVisible(false);
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            // The activity may not be destroyed immediately.
            mAmWmState.waitForWithWmState(
                    wmState -> !wmState.containsWindow(getWindowName(SHOW_WHEN_LOCKED_ACTIVITY)),
                    "Waiting for " + getActivityName(SHOW_WHEN_LOCKED_ACTIVITY) + " to be removed");
            // The {@link SHOW_WHEN_LOCKED_ACTIVITY} has gone because of {@link pressBackButton()}.
            mAmWmState.assertNotExist(SHOW_WHEN_LOCKED_ACTIVITY);
        }
    }

    private void assertWallpaperShowing() {
        WindowState wallpaper =
                mAmWmState.getWmState().findFirstWindowWithType(TYPE_WALLPAPER);
        assertNotNull(wallpaper);
        assertTrue(wallpaper.isShown());
    }

    @Test
    public void testDismissKeyguardAttrActivity_method_turnScreenOn() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            lockScreenSession.sleepDevice();

            separateTestJournal();
            mAmWmState.computeState(true);
            assertTrue(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            launchActivity(TURN_SCREEN_ON_ATTR_DISMISS_KEYGUARD_ACTIVITY);
            mAmWmState.waitForKeyguardGone();
            mAmWmState.assertVisibility(TURN_SCREEN_ON_ATTR_DISMISS_KEYGUARD_ACTIVITY, true);
            assertFalse(mAmWmState.getAmState().getKeyguardControllerState().keyguardShowing);
            assertOnDismissSucceeded(TURN_SCREEN_ON_ATTR_DISMISS_KEYGUARD_ACTIVITY);
            assertTrue(isDisplayOn(DEFAULT_DISPLAY));
        }
    }

    @Test
    public void testScreenOffWhileOccludedStopsActivityNoAod() throws Exception {
        try (final AodSession aodSession = new AodSession()) {
            aodSession.setAodEnabled(false);
            testScreenOffWhileOccludedStopsActivity(false /* assertAod */);
        }
    }

    @Test
    public void testScreenOffWhileOccludedStopsActivityAod() throws Exception {
        try (final AodSession aodSession = new AodSession()) {
            assumeTrue(aodSession.isAodAvailable());
            aodSession.setAodEnabled(true);
            testScreenOffWhileOccludedStopsActivity(true /* assertAod */);
        }
    }

    /**
     * @param assertAod {@code true} to check AOD status, {@code false} otherwise. Note that when
     *        AOD is disabled for the default display, AOD status shouldn't be checked.
     */
    private void testScreenOffWhileOccludedStopsActivity(boolean assertAod) {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            separateTestJournal();
            lockScreenSession.gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            launchActivity(SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
            waitAndAssertTopResumedActivity(SHOW_WHEN_LOCKED_ATTR_ACTIVITY, DEFAULT_DISPLAY,
                    "Activity with showWhenLocked attribute should be resumed.");
            mAmWmState.assertKeyguardShowingAndOccluded();
            if (assertAod) {
                mAmWmState.assertAodNotShowing();
            }
            lockScreenSession.sleepDevice();
            if (assertAod) {
                mAmWmState.assertAodShowing();
            }
            mAmWmState.waitForAllStoppedActivities();
            assertSingleLaunchAndStop(SHOW_WHEN_LOCKED_ATTR_ACTIVITY);
        }
    }

    @Test
    public void testScreenOffCausesSingleStopNoAod() throws Exception {
        try (final AodSession aodSession = new AodSession()) {
            aodSession.setAodEnabled(false);
            testScreenOffCausesSingleStop();
        }
    }

    @Test
    public void testScreenOffCausesSingleStopAod() throws Exception {
        try (final AodSession aodSession = new AodSession()) {
            assumeTrue(aodSession.isAodAvailable());
            aodSession.setAodEnabled(true);
            testScreenOffCausesSingleStop();
        }
    }

    private void testScreenOffCausesSingleStop() {
        try (final LockScreenSession lockScreenSession = new LockScreenSession()) {
            separateTestJournal();
            launchActivity(TEST_ACTIVITY);
            mAmWmState.assertVisibility(TEST_ACTIVITY, true);
            lockScreenSession.sleepDevice();
            mAmWmState.waitForAllStoppedActivities();
            assertSingleLaunchAndStop(TEST_ACTIVITY);
        }

    }

}
