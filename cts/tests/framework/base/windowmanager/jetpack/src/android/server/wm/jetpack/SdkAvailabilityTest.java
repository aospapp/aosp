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

package android.server.wm.jetpack;

import static android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.app.ActivityTaskManager;
import android.content.Context;
import android.hardware.display.DisplayManager;
import android.platform.test.annotations.Presubmit;
import android.server.wm.jetpack.utils.ExtensionUtil;
import android.server.wm.jetpack.utils.SidecarUtil;
import android.server.wm.jetpack.utils.WindowManagerJetpackTestBase;
import android.view.Display;
import android.view.WindowManager;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.window.extensions.WindowExtensions;
import androidx.window.extensions.embedding.ActivityEmbeddingComponent;

import com.android.compatibility.common.util.CddTest;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

/**
 * Tests for devices implementations include an Android-compatible display(s)
 * that has a minimum screen dimension greater than or equal to
 * {@link WindowManager#LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP} and support multi window.
 *
 * Build/Install/Run:
 * atest CtsWindowManagerJetpackTestCases:SdkAvailabilityTest
 */
@Presubmit
@RunWith(AndroidJUnit4.class)
@CddTest(requirements = {"7.1.1.1/C-2-1,C-2-2"})
public class SdkAvailabilityTest extends WindowManagerJetpackTestBase {

    @Before
    @Override
    public void setUp() {
        super.setUp();
        assumeHasLargeScreenDisplayOrExtensionEnabled();
        assumeMultiWindowSupported();
    }

    /**
     * MUST implement the latest available stable version of the extensions API
     * to be used by Window Manager Jetpack library, and declares the window extension
     * is enabled.
     */
    @Test
    public void testWindowExtensionsAvailability() {
        assertTrue("WindowExtension version is not latest",
                ExtensionUtil.isExtensionVersionLatest());
        assertTrue("Device must declared that the WindowExtension is enabled",
                WindowManager.hasWindowExtensionsEnabled());
    }

    /**
     * MUST support Activity Embedding APIs and make ActivityEmbeddingComponent available via
     * WindowExtensions interface.
     */
    @Test
    public void testActivityEmbeddingAvailability() {
        WindowExtensions windowExtensions = ExtensionUtil.getWindowExtensions();
        assertNotNull("WindowExtensions is not available", windowExtensions);
        ActivityEmbeddingComponent activityEmbeddingComponent =
                windowExtensions.getActivityEmbeddingComponent();
        assertNotNull("ActivityEmbeddingComponent is not available", activityEmbeddingComponent);
    }


    /**
     * MUST also implement the stable version of sidecar API for compatibility with older
     * applications.
     */
    @Test
    public void testSidecarAvailability() {
        assertTrue("Sidecar is not available", SidecarUtil.isSidecarVersionValid());
    }

    private void assumeHasLargeScreenDisplayOrExtensionEnabled() {
        final DisplayManager displayManager = mContext.getSystemService(DisplayManager.class);
        boolean hasLargeScreenDisplay = Arrays.stream(displayManager.getDisplays())
                .filter(display -> display.getType() == Display.TYPE_INTERNAL)
                .anyMatch(this::isLargeScreenDisplay);
        assumeTrue("Device does not has a minimum screen dimension greater than or equal to "
                        + WindowManager.LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP + "dp",
                hasLargeScreenDisplay || WindowManager.hasWindowExtensionsEnabled());
    }

    private void assumeMultiWindowSupported() {
        assumeTrue("Device's default display doesn't support multi window",
                ActivityTaskManager.supportsMultiWindow(mContext));
    }

    private boolean isLargeScreenDisplay(@NonNull Display display) {
        // Use WindowContext with type application overlay to prevent the metrics overridden by
        // activity bounds. Note that process configuration may still be overridden by
        // foreground Activity.
        final Context appContext = ApplicationProvider.getApplicationContext();
        final Context windowContext = appContext.createWindowContext(display,
                TYPE_APPLICATION_OVERLAY, null /* options */);
        return windowContext.getResources().getConfiguration().smallestScreenWidthDp
                >= WindowManager.LARGE_SCREEN_SMALLEST_SCREEN_WIDTH_DP;
    }
}
