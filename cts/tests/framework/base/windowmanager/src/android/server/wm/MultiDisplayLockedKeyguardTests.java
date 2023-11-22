/*
 * Copyright (C) 2017 The Android Open Source Project
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

import static android.server.wm.ActivityManagerState.STATE_RESUMED;
import static android.server.wm.ActivityManagerState.STATE_STOPPED;
import static android.server.wm.ActivityManagerTestBase.LockScreenSession.FLAG_REMOVE_ACTIVITIES_ON_CLOSE;
import static android.server.wm.app.Components.DISMISS_KEYGUARD_ACTIVITY;
import static android.server.wm.app.Components.SHOW_WHEN_LOCKED_ACTIVITY;
import static android.server.wm.app.Components.TEST_ACTIVITY;
import static android.server.wm.app.Components.VIRTUAL_DISPLAY_ACTIVITY;

import static org.junit.Assume.assumeTrue;

import android.platform.test.annotations.Presubmit;
import android.server.wm.ActivityManagerState.ActivityDisplay;

import androidx.test.filters.FlakyTest;

import org.junit.Before;
import org.junit.Test;

/**
 * Display tests that require a locked keyguard.
 *
 * <p>Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:MultiDisplayLockedKeyguardTests
 */
@Presubmit
public class MultiDisplayLockedKeyguardTests extends MultiDisplayTestBase {

    @Before
    @Override
    public void setUp() throws Exception {
        super.setUp();

        assumeTrue(supportsMultiDisplay());
        assumeTrue(supportsSecureLock());
    }

    /**
     * Test that virtual display content is hidden when device is locked.
     */
    @Test
    @FlakyTest(bugId = 131005232)
    public void testVirtualDisplayHidesContentWhenLocked() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession();
             final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            lockScreenSession.setLockCredential();

            // Create new usual virtual display.
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(true)
                    .createDisplay();
            mAmWmState.assertVisibility(VIRTUAL_DISPLAY_ACTIVITY, true /* visible */);

            // Launch activity on new secondary display.
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);
            mAmWmState.assertVisibility(TEST_ACTIVITY, true /* visible */);

            // Lock the device.
            lockScreenSession.gotoKeyguard();
            waitAndAssertActivityState(TEST_ACTIVITY, STATE_STOPPED,
                    "Expected stopped activity on secondary display ");
            mAmWmState.assertVisibility(TEST_ACTIVITY, false /* visible */);

            // Unlock and check if visibility is back.
            lockScreenSession.unlockDevice();

            lockScreenSession.enterAndConfirmLockCredential();
            mAmWmState.waitForKeyguardGone();
            mAmWmState.assertKeyguardGone();
            waitAndAssertActivityState(TEST_ACTIVITY, STATE_RESUMED,
                    "Expected resumed activity on secondary display");
            mAmWmState.assertVisibility(TEST_ACTIVITY, true /* visible */);
        }
    }

    /**
     * Tests that private display cannot show content while device locked.
     */
    @Test
    public void testPrivateDisplayHideContentWhenLocked() throws Exception {
        try (final LockScreenSession lockScreenSession = new LockScreenSession();
             final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            lockScreenSession.setLockCredential();

            final ActivityDisplay newDisplay =
                    virtualDisplaySession.setPublicDisplay(false).createDisplay();
            launchActivityOnDisplay(TEST_ACTIVITY, newDisplay.mId);

            lockScreenSession.gotoKeyguard();

            waitAndAssertActivityState(TEST_ACTIVITY, STATE_STOPPED,
                    "Expected stopped activity on private display");
            mAmWmState.assertVisibility(TEST_ACTIVITY, false /* visible */);
        }
    }

    /**
     * Tests whether a FLAG_DISMISS_KEYGUARD activity on a secondary display dismisses the keyguard.
     */
    @Test
    public void testDismissKeyguard_secondaryDisplay() throws Exception {
        try (final LockScreenSession lockScreenSession =
                     new LockScreenSession(FLAG_REMOVE_ACTIVITIES_ON_CLOSE);
             final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            lockScreenSession.setLockCredential();
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(true).
                    createDisplay();

            lockScreenSession.gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            getLaunchActivityBuilder().setUseInstrumentation()
                    .setTargetActivity(DISMISS_KEYGUARD_ACTIVITY).setNewTask(true)
                    .setMultipleTask(true).setDisplayId(newDisplay.mId)
                    .setWaitForLaunched(false).execute();
            waitAndAssertActivityState(DISMISS_KEYGUARD_ACTIVITY, STATE_STOPPED,
                    "Expected stopped activity on secondary display");
            lockScreenSession.enterAndConfirmLockCredential();
            mAmWmState.waitForKeyguardGone();
            mAmWmState.assertKeyguardGone();
            mAmWmState.assertVisibility(DISMISS_KEYGUARD_ACTIVITY, true);
        }
    }

    @Test
    public void testDismissKeyguard_whileOccluded_secondaryDisplay() throws Exception {
        try (final LockScreenSession lockScreenSession =
                     new LockScreenSession(FLAG_REMOVE_ACTIVITIES_ON_CLOSE);
             final VirtualDisplaySession virtualDisplaySession = new VirtualDisplaySession()) {
            lockScreenSession.setLockCredential();
            final ActivityDisplay newDisplay = virtualDisplaySession.setPublicDisplay(true).
                    createDisplay();

            lockScreenSession.gotoKeyguard();
            mAmWmState.assertKeyguardShowingAndNotOccluded();
            launchActivity(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.computeState(SHOW_WHEN_LOCKED_ACTIVITY);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
            getLaunchActivityBuilder().setUseInstrumentation()
                    .setTargetActivity(DISMISS_KEYGUARD_ACTIVITY).setNewTask(true)
                    .setMultipleTask(true).setDisplayId(newDisplay.mId)
                    .setWaitForLaunched(false).execute();
            waitAndAssertActivityState(DISMISS_KEYGUARD_ACTIVITY, STATE_STOPPED,
                    "Expected stopped activity on secondary display");
            lockScreenSession.enterAndConfirmLockCredential();
            mAmWmState.waitForKeyguardGone();
            mAmWmState.assertKeyguardGone();
            mAmWmState.computeState(DISMISS_KEYGUARD_ACTIVITY);
            mAmWmState.assertVisibility(DISMISS_KEYGUARD_ACTIVITY, true);
            mAmWmState.assertVisibility(SHOW_WHEN_LOCKED_ACTIVITY, true);
        }
    }
}
