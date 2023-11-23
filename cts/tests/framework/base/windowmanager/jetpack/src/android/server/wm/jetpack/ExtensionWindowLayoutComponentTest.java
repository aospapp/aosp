/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.server.wm.jetpack.utils.ExtensionUtil.MINIMUM_EXTENSION_VERSION;
import static android.server.wm.jetpack.utils.ExtensionUtil.assertEqualWindowLayoutInfo;
import static android.server.wm.jetpack.utils.ExtensionUtil.assumeExtensionSupportedDevice;
import static android.server.wm.jetpack.utils.ExtensionUtil.assumeHasDisplayFeatures;
import static android.server.wm.jetpack.utils.ExtensionUtil.getExtensionWindowLayoutComponent;
import static android.server.wm.jetpack.utils.ExtensionUtil.getExtensionWindowLayoutInfo;
import static android.server.wm.jetpack.utils.ExtensionUtil.getWindowExtensions;
import static android.server.wm.jetpack.utils.ExtensionUtil.isExtensionVersionValid;
import static android.server.wm.jetpack.utils.SidecarUtil.assumeSidecarSupportedDevice;
import static android.server.wm.jetpack.utils.SidecarUtil.getSidecarInterface;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.areExtensionAndSidecarDeviceStateEqual;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.assertNotBothDimensionsZero;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.assertHasNonNegativeDimensions;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.doesDisplayRotateForOrientation;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.getActivityBounds;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.getActivityWindowToken;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.getMaximumActivityBounds;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.setActivityOrientationActivityDoesNotHandleOrientationChanges;
import static android.server.wm.jetpack.utils.WindowManagerJetpackTestBase.setActivityOrientationActivityHandlesOrientationChanges;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import static androidx.window.extensions.layout.FoldingFeature.TYPE_FOLD;
import static androidx.window.extensions.layout.FoldingFeature.TYPE_HINGE;
import static androidx.window.extensions.layout.FoldingFeature.STATE_FLAT;
import static androidx.window.extensions.layout.FoldingFeature.STATE_HALF_OPENED;

import android.app.Activity;
import android.graphics.Rect;
import android.server.wm.jetpack.utils.WindowManagerJetpackTestBase;
import android.server.wm.jetpack.utils.TestActivity;
import android.server.wm.jetpack.utils.TestConfigChangeHandlingActivity;
import android.server.wm.jetpack.utils.TestValueCountConsumer;
import android.platform.test.annotations.Presubmit;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.window.extensions.layout.DisplayFeature;
import androidx.window.extensions.layout.FoldingFeature;
import androidx.window.extensions.layout.WindowLayoutComponent;
import androidx.window.extensions.layout.WindowLayoutInfo;
import androidx.window.sidecar.SidecarDisplayFeature;
import androidx.window.sidecar.SidecarInterface;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Collections;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Tests for the {@link androidx.window.extensions.layout.WindowLayoutComponent} implementation
 * provided on the device (and only if one is available).
 *
 * Build/Install/Run:
 *     atest CtsWindowManagerJetpackTestCases:ExtensionWindowLayoutComponentTest
 */
@LargeTest
@Presubmit
@RunWith(AndroidJUnit4.class)
public class ExtensionWindowLayoutComponentTest extends WindowManagerJetpackTestBase {

    private TestActivity mActivity;
    private WindowLayoutComponent mWindowLayoutComponent;
    private WindowLayoutInfo mWindowLayoutInfo;

    @Before
    @Override
    public void setUp() {
        super.setUp();
        assumeExtensionSupportedDevice();
        mActivity = (TestActivity) startActivityNewTask(TestActivity.class);
        mWindowLayoutComponent = getExtensionWindowLayoutComponent();
        assumeNotNull(mWindowLayoutComponent);
    }

    @Test
    public void testWindowLayoutComponent_WindowLayoutInfoListener() {
        TestValueCountConsumer<WindowLayoutInfo> windowLayoutInfoConsumer =
                new TestValueCountConsumer<>();
        // Test that adding and removing callback succeeds
        mWindowLayoutComponent.addWindowLayoutInfoListener(mActivity, windowLayoutInfoConsumer);
        mWindowLayoutComponent.removeWindowLayoutInfoListener(windowLayoutInfoConsumer);
    }

    @Test
    public void testDisplayFeatures()
            throws ExecutionException, InterruptedException, TimeoutException {
        mWindowLayoutInfo = getExtensionWindowLayoutInfo(mActivity);
        assumeHasDisplayFeatures(mWindowLayoutInfo);
        for (DisplayFeature displayFeature : mWindowLayoutInfo.getDisplayFeatures()) {
            // Check that the feature bounds are valid
            final Rect featureRect = displayFeature.getBounds();
            // Feature cannot have negative width or height
            assertHasNonNegativeDimensions(featureRect);
            // The feature cannot have zero area
            assertNotBothDimensionsZero(featureRect);
            // The feature cannot be outside the activity bounds
            assertTrue(getActivityBounds(mActivity).contains(featureRect));

            if (displayFeature instanceof FoldingFeature) {
                // Check that the folding feature has a valid type and state
                final FoldingFeature foldingFeature = (FoldingFeature) displayFeature;
                final int featureType = foldingFeature.getType();
                assertThat(featureType).isIn(Range.range(
                        TYPE_FOLD, BoundType.CLOSED,
                        TYPE_HINGE, BoundType.CLOSED));
                final int featureState = foldingFeature.getState();
                assertThat(featureState).isIn(Range.range(
                        STATE_FLAT, BoundType.CLOSED,
                        STATE_HALF_OPENED, BoundType.CLOSED));
            }
        }
    }

    @Test
    public void testGetWindowLayoutInfo_configChanged_windowLayoutUpdates()
            throws ExecutionException, InterruptedException, TimeoutException {
        mWindowLayoutInfo = getExtensionWindowLayoutInfo(mActivity);
        assumeHasDisplayFeatures(mWindowLayoutInfo);

        TestConfigChangeHandlingActivity configHandlingActivity
                = (TestConfigChangeHandlingActivity) startActivityNewTask(
                TestConfigChangeHandlingActivity.class);

        setActivityOrientationActivityHandlesOrientationChanges(configHandlingActivity,
                ORIENTATION_PORTRAIT);
        final WindowLayoutInfo portraitWindowLayoutInfo = getExtensionWindowLayoutInfo(
                configHandlingActivity);
        final Rect portraitBounds = getActivityBounds(configHandlingActivity);
        final Rect portraitMaximumBounds = getMaximumActivityBounds(configHandlingActivity);

        setActivityOrientationActivityHandlesOrientationChanges(configHandlingActivity,
                ORIENTATION_LANDSCAPE);
        final WindowLayoutInfo landscapeWindowLayoutInfo = getExtensionWindowLayoutInfo(
                configHandlingActivity);
        final Rect landscapeBounds = getActivityBounds(configHandlingActivity);
        final Rect landscapeMaximumBounds = getMaximumActivityBounds(configHandlingActivity);

        final boolean doesDisplayRotateForOrientation = doesDisplayRotateForOrientation(
                portraitMaximumBounds, landscapeMaximumBounds);
        assertEqualWindowLayoutInfo(portraitWindowLayoutInfo, landscapeWindowLayoutInfo,
                portraitBounds, landscapeBounds, doesDisplayRotateForOrientation);
    }

    @Test
    public void testGetWindowLayoutInfo_windowRecreated_windowLayoutUpdates()
            throws ExecutionException, InterruptedException, TimeoutException {
        mWindowLayoutInfo = getExtensionWindowLayoutInfo(mActivity);
        assumeHasDisplayFeatures(mWindowLayoutInfo);

        setActivityOrientationActivityDoesNotHandleOrientationChanges(mActivity,
                ORIENTATION_PORTRAIT);
        final WindowLayoutInfo portraitWindowLayoutInfo = getExtensionWindowLayoutInfo(mActivity);
        final Rect portraitBounds = getActivityBounds(mActivity);
        final Rect portraitMaximumBounds = getMaximumActivityBounds(mActivity);

        setActivityOrientationActivityDoesNotHandleOrientationChanges(mActivity,
                ORIENTATION_LANDSCAPE);
        final WindowLayoutInfo landscapeWindowLayoutInfo = getExtensionWindowLayoutInfo(mActivity);
        final Rect landscapeBounds = getActivityBounds(mActivity);
        final Rect landscapeMaximumBounds = getMaximumActivityBounds(mActivity);

        final boolean doesDisplayRotateForOrientation = doesDisplayRotateForOrientation(
                portraitMaximumBounds, landscapeMaximumBounds);
        assertEqualWindowLayoutInfo(portraitWindowLayoutInfo, landscapeWindowLayoutInfo,
                portraitBounds, landscapeBounds, doesDisplayRotateForOrientation);
    }

    /**
     * Tests that if sidecar is also present, then it returns the same display features as
     * extensions.
     */
    @Test
    public void testSidecarHasSameDisplayFeatures()
            throws ExecutionException, InterruptedException, TimeoutException {
        assumeSidecarSupportedDevice(mActivity);
        mWindowLayoutInfo = getExtensionWindowLayoutInfo(mActivity);
        assumeHasDisplayFeatures(mWindowLayoutInfo);

        // Retrieve and sort the extension folding features
        final List<FoldingFeature> extensionFoldingFeatures = new ArrayList<>(
                mWindowLayoutInfo.getDisplayFeatures())
                .stream()
                .filter(d -> d instanceof FoldingFeature)
                .map(d -> (FoldingFeature) d)
                .collect(Collectors.toList());

        // Retrieve and sort the sidecar display features in the same order as the extension
        // display features
        final SidecarInterface sidecarInterface = getSidecarInterface(mActivity);
        final List<SidecarDisplayFeature> sidecarDisplayFeatures = sidecarInterface
                .getWindowLayoutInfo(getActivityWindowToken(mActivity)).displayFeatures;

        // Check that the display features are the same
        assertEquals(extensionFoldingFeatures.size(), sidecarDisplayFeatures.size());
        final int nFeatures = extensionFoldingFeatures.size();
        if (nFeatures == 0) {
            return;
        }
        final boolean[] extensionDisplayFeatureMatched = new boolean[nFeatures];
        final boolean[] sidecarDisplayFeatureMatched = new boolean[nFeatures];
        for (int extensionIndex = 0; extensionIndex < nFeatures; extensionIndex++) {
            if (extensionDisplayFeatureMatched[extensionIndex]) {
                // A match has already been found for this extension folding feature
                continue;
            }
            final FoldingFeature extensionFoldingFeature = extensionFoldingFeatures
                    .get(extensionIndex);
            for (int sidecarIndex = 0; sidecarIndex < nFeatures; sidecarIndex++) {
                if (sidecarDisplayFeatureMatched[sidecarIndex]) {
                    // A match has already been found for this sidecar display feature
                    continue;
                }
                final SidecarDisplayFeature sidecarDisplayFeature = sidecarDisplayFeatures
                        .get(sidecarIndex);
                // Check that the bounds, type, and state match
                if (extensionFoldingFeature.getBounds().equals(sidecarDisplayFeature.getRect())
                        && extensionFoldingFeature.getType() == sidecarDisplayFeature.getType()
                        && areExtensionAndSidecarDeviceStateEqual(
                                extensionFoldingFeature.getState(),
                                sidecarInterface.getDeviceState().posture)) {
                    // Match found
                    extensionDisplayFeatureMatched[extensionIndex] = true;
                    sidecarDisplayFeatureMatched[sidecarIndex] = true;
                }
            }
        }

        // Check that a match was found for each display feature
        for (int i = 0; i < nFeatures; i++) {
            assertTrue(extensionDisplayFeatureMatched[i] && sidecarDisplayFeatureMatched[i]);
        }
    }
}
