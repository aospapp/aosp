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
 * limitations under the License.
 */

package android.server.wm;

import static android.app.WindowConfiguration.WINDOWING_MODE_FREEFORM;
import static android.app.WindowConfiguration.WINDOWING_MODE_FULLSCREEN;
import static android.server.wm.app.Components.ANIMATION_TEST_ACTIVITY;
import static android.server.wm.app.Components.LAUNCHING_ACTIVITY;
import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;

import android.content.ComponentName;
import android.platform.test.annotations.Presubmit;
import android.server.wm.WindowManagerState.Display;

import org.junit.Test;

/**
 * Build/Install/Run:
 *     atest CtsWindowManagerDeviceTestCases:AnimationBackgroundTests
 */
@Presubmit
public class AnimationBackgroundTests extends ActivityManagerTestBase {

    @Test
    public void testAnimationBackground_duringAnimation() throws Exception {
        launchActivityOnDisplay(LAUNCHING_ACTIVITY, DEFAULT_DISPLAY);
        getLaunchActivityBuilder()
                .setTargetActivity(ANIMATION_TEST_ACTIVITY)
                .setWaitForLaunched(false)
                .execute();

        // Make sure we're testing an activity that runs on fullscreen display. This animation API
        // doesn't make much sense in freeform displays.
        assumeActivityNotInFreeformDisplay(ANIMATION_TEST_ACTIVITY);

        // Make sure we are in the middle of the animation.
        mAmWmState.waitForWithWmState(state -> state
                .getStandardStackByWindowingMode(WINDOWING_MODE_FULLSCREEN)
                        .isWindowAnimationBackgroundSurfaceShowing(),
                "***Waiting for animation background showing");

        assertTrue("window animation background needs to be showing", mAmWmState.getWmState()
                .getStandardStackByWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .isWindowAnimationBackgroundSurfaceShowing());
    }

    @Test
    public void testAnimationBackground_gone() throws Exception {
        launchActivityOnDisplay(LAUNCHING_ACTIVITY, DEFAULT_DISPLAY);
        getLaunchActivityBuilder().setTargetActivity(ANIMATION_TEST_ACTIVITY).execute();
        mAmWmState.computeState(ANIMATION_TEST_ACTIVITY);
        mAmWmState.waitForAppTransitionIdleOnDisplay(DEFAULT_DISPLAY);

        // Make sure we're testing an activity that runs on fullscreen display. This animation API
        // doesn't make much sense in freeform displays.
        assumeActivityNotInFreeformDisplay(ANIMATION_TEST_ACTIVITY);

        assertFalse("window animation background needs to be gone", mAmWmState.getWmState()
                .getStandardStackByWindowingMode(WINDOWING_MODE_FULLSCREEN)
                .isWindowAnimationBackgroundSurfaceShowing());
    }

    private void assumeActivityNotInFreeformDisplay(ComponentName activity) throws Exception {
        mAmWmState.waitForValidState(activity);
        final int displayId = mAmWmState.getAmState().getDisplayByActivity(activity);
        final Display display = mAmWmState.getWmState().getDisplay(displayId);
        assumeFalse("Animation test activity is in freeform display. It may not run "
                + "cross-task animations.", display.getWindowingMode() == WINDOWING_MODE_FREEFORM);
    }
}
