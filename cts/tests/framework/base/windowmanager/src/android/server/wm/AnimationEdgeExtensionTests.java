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

import static android.server.wm.app.Components.CustomTransitionAnimations.BOTTOM_EDGE_EXTENSION;
import static android.server.wm.app.Components.CustomTransitionAnimations.LEFT_EDGE_EXTENSION;
import static android.server.wm.app.Components.CustomTransitionAnimations.RIGHT_EDGE_EXTENSION;
import static android.server.wm.app.Components.CustomTransitionAnimations.TOP_EDGE_EXTENSION;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.ColorSpace;
import android.graphics.Rect;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;

/**
 * Build/Install/Run:
 * atest CtsWindowManagerDeviceTestCases:AnimationEdgeExtensionTests
 */
@Presubmit
@android.server.wm.annotation.Group1
public class AnimationEdgeExtensionTests extends CustomActivityTransitionTestBase {

    // We need to allow for some variation stemming from color conversions
    private static final float COLOR_VALUE_VARIANCE_TOLERANCE = 0.03f;

    /**
     * Checks that when an activity transition with a left edge extension is run that the animating
     * activity is extended on the left side by clamping the edge pixels of the activity.
     *
     * The test runs an activity transition where the animating activities are X scaled to 50%,
     * positioned of the right side of the screen, and edge extended on the left. Because the
     * animating activities are half red half blue (split at the middle of the X axis of the
     * activity). We expect first 75% pixel columns of the screen to be red (50% from the edge
     * extension and the next 25% from from the activity) and the remaining 25% columns after that
     * to be blue (from the activity).
     */
    @Test
    public void testLeftEdgeExtensionWorksDuringActivityTransition() {
        final Bitmap screenshot = runAndScreenshotTransition(LEFT_EDGE_EXTENSION);
        final Rect fullyVisibleBounds = getActivityFullyVisibleRegion();
        assertColorChangeXIndex(screenshot,
                (fullyVisibleBounds.left + fullyVisibleBounds.right) / 4 * 3);
    }

    /**
     * Checks that when an activity transition with a top edge extension is run that the animating
     * activity is extended on the left side by clamping the edge pixels of the activity.
     *
     * The test runs an activity transition where the animating activities are Y scaled to 50%,
     * positioned of the bottom of the screen, and edge extended on the top. Because the
     * animating activities are half red half blue (split at the middle of the X axis of the
     * activity). We expect first 50% pixel columns of the screen to be red (the top half from the
     * extension and the bottom half from the activity) and the remaining 50% columns after that
     * to be blue (the top half from the extension and the bottom half from the activity).
     */
    @Test
    public void testTopEdgeExtensionWorksDuringActivityTransition() {
        final Bitmap screenshot = runAndScreenshotTransition(TOP_EDGE_EXTENSION);
        final Rect fullyVisibleBounds = getActivityFullyVisibleRegion();
        assertColorChangeXIndex(screenshot,
                (fullyVisibleBounds.left + fullyVisibleBounds.right) / 2);
    }

    /**
     * Checks that when an activity transition with a right edge extension is run that the animating
     * activity is extended on the right side by clamping the edge pixels of the activity.
     *
     * The test runs an activity transition where the animating activities are X scaled to 50% and
     * edge extended on the right. Because the animating activities are half red half blue. We
     * expect first 25% pixel columns of the screen to be red (from the activity) and the remaining
     * 75% columns after that to be blue (25% from the activity and 50% from the edge extension
     * which should be extending the right edge pixel (so red pixels).
     */
    @Test
    public void testRightEdgeExtensionWorksDuringActivityTransition() {
        final Bitmap screenshot = runAndScreenshotTransition(RIGHT_EDGE_EXTENSION);
        final Rect fullyVisibleBounds = getActivityFullyVisibleRegion();
        assertColorChangeXIndex(screenshot,
                (fullyVisibleBounds.left + fullyVisibleBounds.right) / 4);
    }

    /**
     * Checks that when an activity transition with a bottom edge extension is run that the
     * animating activity is extended on the bottom side by clamping the edge pixels of the
     * activity.
     *
     * The test runs an activity transition where the animating activities are Y scaled to 50%,
     * positioned of the top of the screen, and edge extended on the bottom. Because the
     * animating activities are half red half blue (split at the middle of the X axis of the
     * activity). We expect first 50% pixel columns of the screen to be red (the top half from the
     * activity and the bottom half from the extensions) and the remaining 50% columns after that
     * to be blue (the top half from the activity and the bottom half from the extension).
     */
    @Test
    public void testBottomEdgeExtensionWorksDuringActivityTransition() {
        final Bitmap screenshot = runAndScreenshotTransition(BOTTOM_EDGE_EXTENSION);
        final Rect fullyVisibleBounds = getActivityFullyVisibleRegion();
        assertColorChangeXIndex(screenshot,
                (fullyVisibleBounds.left + fullyVisibleBounds.right) / 2);
    }

    private Bitmap runAndScreenshotTransition(String transition) {
        launchCustomTransition(transition, 0);
        return screenshotTransition();
    }

    private void assertColorChangeXIndex(Bitmap screen, int xIndex) {
        final int colorChangeXIndex = getColorChangeXIndex(screen);
        final Rect fullyVisibleBounds = getActivityFullyVisibleRegion();
        // Check to make sure the activity was scaled for an extension to be visible on screen
        assertEquals(xIndex, colorChangeXIndex);

        // The activity we are extending is a half red, half blue.
        // We are scaling the activity in the animation so if the extension doesn't work we should
        // have a blue, then red, then black section, and if it does work we should see on a blue,
        // followed by an extended red section.
        for (int x = 0; x < screen.getWidth(); x++) {
            for (int y = fullyVisibleBounds.top;
                    y < fullyVisibleBounds.bottom; y++) {
                final Color expectedColor;
                if (x < xIndex) {
                    expectedColor = Color.valueOf(Color.BLUE);
                } else {
                    expectedColor = Color.valueOf(Color.RED);
                }

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

                assertArrayEquals("Screen pixel (" + x + ", " + y + ") is not the right color",
                        new float[] {
                                expectedColor.red(), expectedColor.green(), expectedColor.blue() },
                        new float[] { sRgbColor.red(), sRgbColor.green(), sRgbColor.blue() },
                        COLOR_VALUE_VARIANCE_TOLERANCE);
            }
        }
    }

    private int getColorChangeXIndex(Bitmap screen) {
        // Look for color changing index at middle of app
        final int y =
                (getActivityFullyVisibleRegion().top + getActivityFullyVisibleRegion().bottom) / 2;

        Color prevColor = screen.getColor(0, y)
                .convert(ColorSpace.get(ColorSpace.Named.SRGB));
        for (int x = 0; x < screen.getWidth(); x++) {
            final Color c = screen.getColor(x, y)
                    .convert(ColorSpace.get(ColorSpace.Named.SRGB));

            if (!colorsEqual(prevColor, c)) {
                return x;
            }
        }

        throw new RuntimeException("Failed to find color change index");
    }

    private boolean colorsEqual(Color c1, Color c2) {
        return almostEquals(c1.red(), c2.red(), COLOR_VALUE_VARIANCE_TOLERANCE)
                && almostEquals(c1.green(), c2.green(), COLOR_VALUE_VARIANCE_TOLERANCE)
                && almostEquals(c1.blue(), c2.blue(), COLOR_VALUE_VARIANCE_TOLERANCE);
    }

    private boolean almostEquals(float a, float b, float delta) {
        return Math.abs(a - b) < delta;
    }
}
