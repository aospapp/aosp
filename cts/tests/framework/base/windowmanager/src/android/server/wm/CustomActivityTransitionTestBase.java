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

package android.server.wm;

import static android.server.wm.ComponentNameUtils.getActivityName;
import static android.server.wm.app.Components.BackgroundActivityTransition.TRANSITION_REQUESTED;
import static android.server.wm.app.Components.CUSTOM_TRANSITION_EXIT_ACTIVITY;

import static com.android.compatibility.common.util.SystemUtil.runShellCommand;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;

import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.TestUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

@Presubmit
@android.server.wm.annotation.Group1
public class CustomActivityTransitionTestBase extends ActivityManagerTestBase {

    private boolean mAnimationScaleResetRequired = false;
    private String mInitialWindowAnimationScale;
    private String mInitialTransitionAnimationScale;
    private String mInitialAnimatorDurationScale;

    @Rule
    public final DumpOnFailure dumpOnFailure = new DumpOnFailure();

    @Before
    public void setUp() throws Exception {
        assumeTrue(ENABLE_SHELL_TRANSITIONS);
        super.setUp();
        setDefaultAnimationScale();
        mWmState.setSanityCheckWithFocusedWindow(false);
        mWmState.waitForDisplayUnfrozen();
    }

    @After
    public void tearDown() {
        restoreAnimationScale();
        mWmState.setSanityCheckWithFocusedWindow(true);
    }

    /**
     * @param transitionType the type of transition to run.
     *                       See CustomTransitionEnterActivity for supported types.
     * @param backgroundColorOverride a background color override of 0 means we are not going to
     *                                override the background color and just fallback on the default
     *                                value
     */
    protected void launchCustomTransition(String transitionType, int backgroundColorOverride) {
        TestJournalProvider.TestJournalContainer.start();
        final String startActivityCommand = "am start -n "
                + getActivityName(CUSTOM_TRANSITION_EXIT_ACTIVITY) + " "
                + "--es transitionType " + transitionType + " "
                + "--ei backgroundColorOverride " + backgroundColorOverride;
        executeShellCommand(startActivityCommand);
        mWmState.waitForValidState(CUSTOM_TRANSITION_EXIT_ACTIVITY);
    }

    protected Bitmap screenshotTransition() {
        final TestJournalProvider.TestJournal journal = TestJournalProvider.TestJournalContainer
                .get(CUSTOM_TRANSITION_EXIT_ACTIVITY);
        try {
            TestUtils.waitUntil("Waiting for app to request transition",
                    15 /* timeoutSecond */,
                    () -> journal.extras.getBoolean(TRANSITION_REQUESTED));
        } catch (Exception e) {
            throw new RuntimeException(e);
        }

        // The activity transition is set to last 5 seconds, wait half a second to make sure
        // the activity transition has started after we receive confirmation through the test
        // journal that we have requested to start a new activity.
        SystemClock.sleep(500);

        // Take a screenshot during the transition where we hide both the activities to just
        // show the background of the transition which is set to be white.
        return takeScreenshot();
    }

    protected void assertAppRegionOfScreenIsColor(Bitmap screen, int color) {
        final Rect fullyVisibleBounds = getActivityFullyVisibleRegion();

        for (int x = 0; x < screen.getWidth(); x++) {
            for (int y = fullyVisibleBounds.top;
                    y < fullyVisibleBounds.bottom; y++) {
                final Color rawColor = screen.getColor(x, y);
                final Color sRgbColor;
                if (!rawColor.getColorSpace().equals(ColorSpace.get(ColorSpace.Named.SRGB))) {
                    // Conversion is required because the color space of the screenshot may be in
                    // the DCI-P3 color space or some other color space and we want to compare the
                    // color against once in the SRGB color space, so we must convert the color back
                    // to the SRGB color space.
                    sRgbColor = screen.getColor(x, y)
                            .convert(ColorSpace.get(ColorSpace.Named.SRGB));
                } else {
                    sRgbColor = rawColor;
                }
                final Color expectedColor = Color.valueOf(color);
                assertArrayEquals("Screen pixel (" + x + ", " + y + ") is not the right color",
                        new float[] {
                                expectedColor.red(), expectedColor.green(), expectedColor.blue() },
                        new float[] { sRgbColor.red(), sRgbColor.green(), sRgbColor.blue() },
                        0.03f); // need to allow for some variation stemming from conversions
            }
        }
    }

    protected Rect getActivityFullyVisibleRegion() {
        final List<WindowManagerState.WindowState> windows = getWmState().getWindows();
        Optional<WindowManagerState.WindowState> screenDecorOverlay =
                windows.stream().filter(
                        w -> w.getName().equals("ScreenDecorOverlay")).findFirst();
        Optional<WindowManagerState.WindowState> screenDecorOverlayBottom =
                windows.stream().filter(
                        w -> w.getName().equals("ScreenDecorOverlayBottom")).findFirst();
        getWmState().getWindowStateForAppToken("screenDecorOverlay");

        final int screenDecorOverlayHeight = screenDecorOverlay.map(
                WindowManagerState.WindowState::getRequestedHeight).orElse(0);
        final int screenDecorOverlayBottomHeight = screenDecorOverlayBottom.map(
                WindowManagerState.WindowState::getRequestedHeight).orElse(0);

        final Rect activityBounds = getWmState().getActivity(CUSTOM_TRANSITION_EXIT_ACTIVITY)
                .getBounds();

        return new Rect(activityBounds.left, activityBounds.top + screenDecorOverlayHeight,
                activityBounds.right, activityBounds.bottom - screenDecorOverlayBottomHeight);
    }

    private void setDefaultAnimationScale() {
        mInitialWindowAnimationScale =
                runShellCommandSafe("settings get global window_animation_scale");
        mInitialTransitionAnimationScale =
                runShellCommandSafe("settings get global transition_animation_scale");
        mInitialAnimatorDurationScale =
                runShellCommandSafe("settings get global animator_duration_scale");

        if (!mInitialWindowAnimationScale.equals("1")
                || !mInitialTransitionAnimationScale.equals("1")
                || !mInitialAnimatorDurationScale.equals("1")) {
            mAnimationScaleResetRequired = true;
            runShellCommandSafe("settings put global window_animation_scale 1");
            runShellCommandSafe("settings put global transition_animation_scale 1");
            runShellCommandSafe("settings put global animator_duration_scale 1");
        }
    }

    private void restoreAnimationScale() {
        if (mAnimationScaleResetRequired) {
            runShellCommandSafe("settings put global window_animation_scale "
                    + mInitialWindowAnimationScale);
            runShellCommandSafe("settings put global transition_animation_scale "
                    + mInitialTransitionAnimationScale);
            runShellCommandSafe("settings put global animator_duration_scale "
                    + mInitialAnimatorDurationScale);
        }
    }

    private static String runShellCommandSafe(String cmd) {
        try {
            return runShellCommand(InstrumentationRegistry.getInstrumentation(), cmd);
        } catch (IOException e) {
            fail("Failed reading command output: " + e);
            return "";
        }
    }
}
