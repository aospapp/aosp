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

package android.server.wm.jetpack.layout;

import static android.server.wm.jetpack.utils.ExtensionUtil.assertHasDisplayFeatures;
import static android.server.wm.jetpack.utils.ExtensionUtil.assumeHasDisplayFeatures;
import static android.server.wm.jetpack.utils.ExtensionUtil.getExtensionWindowLayoutInfo;

import android.server.wm.jetpack.utils.TestActivity;
import android.server.wm.jetpack.utils.TestLetterboxLandscapeActivity;
import android.server.wm.jetpack.utils.TestLetterboxPortraitActivity;
import android.server.wm.jetpack.utils.WindowExtensionTestRule;
import android.server.wm.jetpack.utils.WindowManagerJetpackTestBase;

import androidx.window.extensions.layout.WindowLayoutComponent;
import androidx.window.extensions.layout.WindowLayoutInfo;

import com.android.compatibility.common.util.ApiTest;

import org.junit.Rule;
import org.junit.Test;

/**
 * A class to test features related to letterboxed Activities.
 *
 * Build/Install/Run:
 *     atest CtsWindowManagerJetpackTestCases:WindowLayoutComponentLetterboxTest
 */
public class WindowLayoutComponentLetterboxTest extends WindowManagerJetpackTestBase {

    @Rule
    public final WindowExtensionTestRule mWindowExtensionTestRule =
            new WindowExtensionTestRule(WindowLayoutComponent.class);

    @ApiTest(apis = {"androidx.window.extensions.layout.WindowLayoutInfo#getDisplayFeatures"})
    @Test
    public void testWindowLayoutComponent_providesWindowLayoutFromLetterboxPortraitActivity()
            throws InterruptedException {
        TestActivity activity = startActivityNewTask(TestActivity.class);
        WindowLayoutInfo fullInfo = getExtensionWindowLayoutInfo(activity);
        assumeHasDisplayFeatures(fullInfo);
        TestLetterboxPortraitActivity letterboxActivity =
                startActivityNewTask(TestLetterboxPortraitActivity.class);
        WindowLayoutInfo letterboxWindowLayoutInfo =
                getExtensionWindowLayoutInfo(letterboxActivity);
        assertHasDisplayFeatures(letterboxWindowLayoutInfo);
    }

    @ApiTest(apis = {"androidx.window.extensions.layout.WindowLayoutInfo#getDisplayFeatures"})
    @Test
    public void testWindowLayoutComponent_providesWindowLayoutFromLetterboxLandscapeActivity()
            throws InterruptedException {
        TestActivity activity = startActivityNewTask(TestActivity.class);
        WindowLayoutInfo fullInfo = getExtensionWindowLayoutInfo(activity);
        assumeHasDisplayFeatures(fullInfo);
        TestLetterboxLandscapeActivity letterboxActivity =
                startActivityNewTask(TestLetterboxLandscapeActivity.class);
        WindowLayoutInfo letterboxWindowLayoutInfo =
                getExtensionWindowLayoutInfo(letterboxActivity);
        assertHasDisplayFeatures(letterboxWindowLayoutInfo);
    }
}
