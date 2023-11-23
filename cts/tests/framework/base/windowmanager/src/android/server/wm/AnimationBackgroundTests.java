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

import static android.server.wm.app.Components.CustomTransitionAnimations.BACKGROUND_COLOR;

import android.graphics.Bitmap;
import android.graphics.Color;
import android.platform.test.annotations.Presubmit;

import org.junit.Test;

/**
 * Build/Install/Run:
 * atest CtsWindowManagerDeviceTestCases:AnimationBackgroundTests
 */
@Presubmit
@android.server.wm.annotation.Group1
public class AnimationBackgroundTests extends CustomActivityTransitionTestBase {

    /**
     * Checks that the activity's theme's background color is used as the default animation's
     * background color when no override is specified.
     */
    @Test
    public void testThemeBackgroundColorShowsDuringActivityTransition() {
        launchCustomTransition(BACKGROUND_COLOR, 0);
        final Bitmap screen = screenshotTransition();
        assertAppRegionOfScreenIsColor(screen, Color.WHITE);
    }

    /**
     * Checks that we can override the default background color of the animation through
     * overridePendingTransition
     */
    @Test
    public void testBackgroundColorIsOverridden() {
        launchCustomTransition(BACKGROUND_COLOR, Color.GREEN);
        final Bitmap screen = screenshotTransition();
        assertAppRegionOfScreenIsColor(screen, Color.GREEN);
    }
}
