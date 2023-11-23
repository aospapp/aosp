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

import static android.content.res.Configuration.ORIENTATION_LANDSCAPE;
import static android.content.res.Configuration.ORIENTATION_PORTRAIT;
import static android.server.wm.jetpack.utils.ExtensionUtil.EXTENSION_VERSION_2;
import static android.server.wm.jetpack.utils.ExtensionUtil.assertEqualWindowLayoutInfo;
import static android.server.wm.jetpack.utils.ExtensionUtil.assumeHasDisplayFeatures;
import static android.server.wm.jetpack.utils.ExtensionUtil.getExtensionWindowLayoutInfo;
import static android.server.wm.jetpack.utils.ExtensionUtil.isExtensionVersionAtLeast;
import static android.server.wm.jetpack.utils.SidecarUtil.assumeSidecarSupportedDevice;
import static android.server.wm.jetpack.utils.SidecarUtil.getSidecarInterface;
import static android.view.Display.DEFAULT_DISPLAY;

import static androidx.window.extensions.layout.FoldingFeature.STATE_FLAT;
import static androidx.window.extensions.layout.FoldingFeature.STATE_HALF_OPENED;
import static androidx.window.extensions.layout.FoldingFeature.TYPE_FOLD;
import static androidx.window.extensions.layout.FoldingFeature.TYPE_HINGE;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeNotNull;
import static org.junit.Assume.assumeTrue;

import android.content.Context;
import android.content.ContextWrapper;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.platform.test.annotations.Presubmit;
import android.server.wm.DisplayMetricsSession;
import android.server.wm.SetRequestedOrientationRule;
import android.server.wm.jetpack.utils.TestActivity;
import android.server.wm.jetpack.utils.TestConfigChangeHandlingActivity;
import android.server.wm.jetpack.utils.TestValueCountConsumer;
import android.server.wm.jetpack.utils.WindowExtensionTestRule;
import android.server.wm.jetpack.utils.WindowManagerJetpackTestBase;
import android.view.Display;
import android.view.View;
import android.view.WindowManager;
import android.view.WindowMetrics;

import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.LargeTest;
import androidx.window.extensions.layout.DisplayFeature;
import androidx.window.extensions.layout.FoldingFeature;
import androidx.window.extensions.layout.WindowLayoutComponent;
import androidx.window.extensions.layout.WindowLayoutInfo;
import androidx.window.sidecar.SidecarDisplayFeature;
import androidx.window.sidecar.SidecarInterface;

import com.android.compatibility.common.util.ApiTest;
import com.android.compatibility.common.util.CddTest;

import com.google.common.collect.BoundType;
import com.google.common.collect.Range;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Tests for the {@link androidx.window.extensions.layout.WindowLayoutComponent} implementation
 * provided on the device (and only if one is available). This class applies
 * {@link SetRequestedOrientationRule} so that screen rotation is not blocked.
 *
 * Build/Install/Run:
 *     atest CtsWindowManagerJetpackTestCases:ExtensionWindowLayoutComponentTest
 */
@Presubmit
@LargeTest
@RunWith(AndroidJUnit4.class)
public class ExtensionWindowLayoutComponentTest extends WindowManagerJetpackTestBase {

    private WindowLayoutComponent mWindowLayoutComponent;
    private WindowLayoutInfo mWindowLayoutInfo;

    @Rule
    public final WindowExtensionTestRule mWindowExtensionTestRule =
            new WindowExtensionTestRule(WindowLayoutComponent.class);

    // To disable special handling which prevents setRequestedOrientation from changing the screen
    // rotation for large screen devices.
    @ClassRule
    public static final SetRequestedOrientationRule sSetRequestedOrientationRule =
            new SetRequestedOrientationRule();

    @Before
    @Override
    public void setUp() {
        super.setUp();
        mWindowLayoutComponent =
                (WindowLayoutComponent) mWindowExtensionTestRule.getExtensionComponent();
        assumeNotNull(mWindowLayoutComponent);
    }

    private Context createContextWithNonActivityWindow() {
        Display defaultDisplay = mContext.getSystemService(DisplayManager.class).getDisplay(
                DEFAULT_DISPLAY);
        Context windowContext = mContext.createWindowContext(defaultDisplay,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null /* options */);

        mInstrumentation.runOnMainSync(() -> {
            final View view = new View(windowContext);
            WindowManager wm = windowContext.getSystemService(WindowManager.class);
            WindowManager.LayoutParams params = new WindowManager.LayoutParams(
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
            wm.addView(view, params);
        });
        return windowContext;
    }

    private void assumeExtensionVersionSupportsWindowContextLayout() {
        assumeTrue("This test should only be run on devices with version: ",
                isExtensionVersionAtLeast(EXTENSION_VERSION_2));
    }

    /**
     * Test adding and removing a window layout change listener.
     */
    @Test
    @ApiTest(apis = {
            "androidx.window.extensions.layout.WindowLayoutComponent#addWindowLayoutInfoListener"})
    public void testWindowLayoutComponent_onWindowLayoutChangeListener() throws Exception {
        TestActivity testActivity = startFullScreenActivityNewTask(
                TestActivity.class, null /* activityId */);
        changeActivityOrientationThenVerifyWindowLayout(testActivity, testActivity);
    }

    /**
     * Test adding and removing a window layout change listener with a wrapped activity context.
     */
    @Test
    @ApiTest(apis = {
            "androidx.window.extensions.layout.WindowLayoutComponent#addWindowLayoutInfoListener"})
    public void testWindowLayoutComponent_onWindowLayoutChangeListener_wrappedContext()
            throws Exception {
        TestActivity testActivity = startFullScreenActivityNewTask(
                TestActivity.class, null /* activityId */);
        Context wrappedContext = new ContextWrapper(testActivity);
        WindowLayoutInfo windowLayoutInfoFromContext = getExtensionWindowLayoutInfo(testActivity);
        WindowLayoutInfo windowLayoutInfoFromWrappedContext =
                getExtensionWindowLayoutInfo(wrappedContext);
        assertEquals(windowLayoutInfoFromContext, windowLayoutInfoFromWrappedContext);

        changeActivityOrientationThenVerifyWindowLayout(testActivity, wrappedContext);
    }

    /**
     * Test adding and removing a window layout change listener with a window context.
     */
    @Test
    @ApiTest(apis = {
            "androidx.window.extensions.layout.WindowLayoutComponent#addWindowLayoutInfoListener"})
    public void testWindowLayoutComponent_onWindowLayoutChangeListener_windowContext()
            throws Exception {
        try (DisplayMetricsSession displaySession = new DisplayMetricsSession(DEFAULT_DISPLAY)) {
            Context context = createContextWithNonActivityWindow();
            changeDisplayMetricThenVerifyWindowLayout(context, displaySession);
        }
    }

    /**
     * Test adding and removing a window layout change listener with a wrapped window context.
     */
    @Test
    @ApiTest(apis = {
            "androidx.window.extensions.layout.WindowLayoutComponent#addWindowLayoutInfoListener"})
    public void testWindowLayoutComponent_onWindowLayoutChangeListener_wrappedWindowContext()
            throws Exception {
        try (DisplayMetricsSession displaySession = new DisplayMetricsSession(DEFAULT_DISPLAY)) {
            Context context = createContextWithNonActivityWindow();
            Context wrappedContext = new ContextWrapper(context);
            WindowLayoutInfo windowLayoutInfoFromContext = getExtensionWindowLayoutInfo(context);
            WindowLayoutInfo windowLayoutInfoFromWrappedContext =
                    getExtensionWindowLayoutInfo(wrappedContext);
            assertEquals(windowLayoutInfoFromContext, windowLayoutInfoFromWrappedContext);

            changeDisplayMetricThenVerifyWindowLayout(wrappedContext, displaySession);
        }
    }

    @Test
    @ApiTest(apis = {
            "androidx.window.extensions.layout.WindowLayoutComponent#addWindowLayoutInfoListener"})
    public void testWindowLayoutComponent_windowLayoutInfoListener() {
        TestActivity activity = startFullScreenActivityNewTask(TestActivity.class,
                null /* activityId */);
        final TestValueCountConsumer<WindowLayoutInfo> windowLayoutInfoConsumer =
                new TestValueCountConsumer<>();
        // Test that adding and removing callback succeeds
        mWindowLayoutComponent.addWindowLayoutInfoListener(activity, windowLayoutInfoConsumer);
        mWindowLayoutComponent.removeWindowLayoutInfoListener(windowLayoutInfoConsumer);
    }

    @ApiTest(apis = {"androidx.window.extensions.layout.WindowLayoutInfo#getDisplayFeatures"})
    @Test
    public void testWindowLayoutComponent_providesWindowLayoutFromActivity()
            throws InterruptedException {
        TestActivity activity = startActivityNewTask(TestActivity.class);
        mWindowLayoutInfo = getExtensionWindowLayoutInfo(activity);
        assumeHasDisplayFeatures(mWindowLayoutInfo);
        for (DisplayFeature displayFeature : mWindowLayoutInfo.getDisplayFeatures()) {
            // Check that the feature bounds are valid
            final Rect featureRect = displayFeature.getBounds();
            // Feature cannot have negative width or height
            assertHasNonNegativeDimensions(featureRect);
            // The feature cannot have zero area
            assertNotBothDimensionsZero(featureRect);
            // The feature cannot be outside the activity bounds
            assertTrue(getActivityBounds(activity).contains(featureRect));

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
    @ApiTest(apis = {"androidx.window.extensions.layout.WindowLayoutInfo#getDisplayFeatures"})
    public void testWindowLayoutComponent_providesWindowLayoutFromWindowContext()
            throws InterruptedException {
        assumeExtensionVersionSupportsWindowContextLayout();
        Context windowContext = createContextWithNonActivityWindow();

        mWindowLayoutInfo = getExtensionWindowLayoutInfo(windowContext);
        assumeHasDisplayFeatures(mWindowLayoutInfo);

        // Verify that window layouts and metrics are reasonable.
        WindowManager mWm = windowContext.getSystemService(WindowManager.class);
        final WindowMetrics currentMetrics = mWm.getCurrentWindowMetrics();

        for (DisplayFeature displayFeature : mWindowLayoutInfo.getDisplayFeatures()) {
            final Rect featureRect = displayFeature.getBounds();
            assertHasNonNegativeDimensions(featureRect);
            assertNotBothDimensionsZero(featureRect);
            assertTrue(currentMetrics.getBounds().contains(featureRect));
        }
    }

    @Test
    @ApiTest(apis = {
            "androidx.window.extensions.layout.WindowLayoutComponent#addWindowLayoutInfoListener"})
    public void testWindowLayoutComponent_windowLayoutMatchesBetweenActivityAndWindowContext()
            throws InterruptedException {
        assumeExtensionVersionSupportsWindowContextLayout();
        TestConfigChangeHandlingActivity activity =
                (TestConfigChangeHandlingActivity) startFullScreenActivityNewTask(
                        TestConfigChangeHandlingActivity.class, null /* activityId */);
        Context windowContext = createContextWithNonActivityWindow();

        WindowLayoutInfo windowLayoutInfoFromContext = getExtensionWindowLayoutInfo(windowContext);
        WindowLayoutInfo windowLayoutInfoFromActivity = getExtensionWindowLayoutInfo(activity);

        assertEquals(windowLayoutInfoFromContext, windowLayoutInfoFromActivity);
    }

    @CddTest(requirements = {"7.1.1.1"})
    @ApiTest(apis = {"androidx.window.extensions.layout.WindowLayoutInfo#getDisplayFeatures"})
    @Test
    public void testGetWindowLayoutInfo_configChanged_windowLayoutUpdates()
            throws InterruptedException {
        assumeSupportsRotation();

        final TestConfigChangeHandlingActivity activity = startFullScreenActivityNewTask(
                TestConfigChangeHandlingActivity.class, null /* activityId */);

        mWindowLayoutInfo = getExtensionWindowLayoutInfo(activity);
        assumeHasDisplayFeatures(mWindowLayoutInfo);

        setActivityOrientationActivityHandlesOrientationChanges(activity,
                ORIENTATION_PORTRAIT);
        final WindowLayoutInfo portraitWindowLayoutInfo = getExtensionWindowLayoutInfo(
                activity);
        final Rect portraitBounds = getActivityBounds(activity);
        final Rect portraitMaximumBounds = getMaximumActivityBounds(activity);

        setActivityOrientationActivityHandlesOrientationChanges(activity,
                ORIENTATION_LANDSCAPE);
        final WindowLayoutInfo landscapeWindowLayoutInfo = getExtensionWindowLayoutInfo(
                activity);
        final Rect landscapeBounds = getActivityBounds(activity);
        final Rect landscapeMaximumBounds = getMaximumActivityBounds(activity);

        final boolean doesDisplayRotateForOrientation = doesDisplayRotateForOrientation(
                portraitMaximumBounds, landscapeMaximumBounds);
        assertTrue(doesDisplayRotateForOrientation);
        assertEqualWindowLayoutInfo(portraitWindowLayoutInfo, landscapeWindowLayoutInfo,
                portraitBounds, landscapeBounds, doesDisplayRotateForOrientation);
    }

    /**
     * Test updating the display metrics and verify the updated WindowLayoutInfo.
     */
    @Test
    @ApiTest(apis = {
            "androidx.window.extensions.layout.WindowLayoutInfo#getDisplayFeatures"})
    public void testGetWindowLayoutInfo_displayMetricsChanged_windowLayoutUpdates()
            throws Exception {
        try (DisplayMetricsSession displaySession = new DisplayMetricsSession(DEFAULT_DISPLAY)) {
            TestActivity testActivity = startFullScreenActivityNewTask(
                    TestActivity.class, null /* activityId */);
            TestValueCountConsumer<WindowLayoutInfo> windowLayoutInfoConsumer =
                    new TestValueCountConsumer<>();
            windowLayoutInfoConsumer.setCount(1);

            // Get the initial WindowLayoutInfo
            mWindowLayoutComponent.addWindowLayoutInfoListener(
                    testActivity, windowLayoutInfoConsumer);
            WindowLayoutInfo windowLayoutInit = windowLayoutInfoConsumer.waitAndGet();
            assertNotNull(windowLayoutInit);
            windowLayoutInfoConsumer.clearQueue();

            // Update the display metrics and get the updated WindowLayoutInfo
            final double displayResizeRatio = 0.8;
            displaySession.changeDisplayMetrics(displayResizeRatio, 1.0 /* densityRatio */);
            WindowLayoutInfo windowLayoutUpdated = windowLayoutInfoConsumer.waitAndGet();
            assertNotNull(windowLayoutUpdated);
            windowLayoutInfoConsumer.clearQueue();

            assertEquals(
                    windowLayoutInit.getDisplayFeatures().size(),
                    windowLayoutUpdated.getDisplayFeatures().size());

            for (int i = 0; i < windowLayoutInit.getDisplayFeatures().size(); i++) {
                Rect windowLayoutSizeInitBounds =
                        windowLayoutInit.getDisplayFeatures().get(i).getBounds();
                Rect windowLayoutSizeUpdatedBounds =
                        windowLayoutUpdated.getDisplayFeatures().get(i).getBounds();

                // Expect the hinge dimension to shrink in one direction. The actual
                // dimension depends on device implementation.
                assertTrue(
                        windowLayoutSizeInitBounds.width() * displayResizeRatio
                                == windowLayoutSizeUpdatedBounds.width()
                                || windowLayoutSizeInitBounds.height() * displayResizeRatio
                                == windowLayoutSizeUpdatedBounds.height()
                );
            }

            // Clean up
            mWindowLayoutComponent.removeWindowLayoutInfoListener(windowLayoutInfoConsumer);
            displaySession.restoreDisplayMetrics();
        }
    }

    @ApiTest(apis = {"androidx.window.extensions.layout.WindowLayoutInfo#getDisplayFeatures"})
    @Test
    public void testGetWindowLayoutInfo_enterExitPip_windowLayoutInfoMatches()
            throws InterruptedException {
        TestConfigChangeHandlingActivity configHandlingActivity = startActivityNewTask(
                        TestConfigChangeHandlingActivity.class, null);
        mWindowLayoutInfo = getExtensionWindowLayoutInfo(configHandlingActivity);
        assumeHasDisplayFeatures(mWindowLayoutInfo);

        final WindowLayoutInfo initialInfo = getExtensionWindowLayoutInfo(
                configHandlingActivity);

        enterPipActivityHandlesConfigChanges(configHandlingActivity);
        exitPipActivityHandlesConfigChanges(configHandlingActivity);

        final WindowLayoutInfo updatedInfo = getExtensionWindowLayoutInfo(
                configHandlingActivity);

        assertEquals(initialInfo, updatedInfo);
    }

    /**
     * Similar to {@link #testGetWindowLayoutInfo_configChanged_windowLayoutUpdates}, here we
     * trigger rotations with a full screen activity on one Display Area, verify that
     * WindowLayoutInfo from both Activity and WindowContext are updated with callbacks.
     */
    @Test
    @ApiTest(apis = {
            "androidx.window.extensions.layout.WindowLayoutComponent#addWindowLayoutInfoListener",
            "androidx.window.extensions.layout.WindowLayoutComponent#removeWindowLayoutInfoListener"
    })
    public void testWindowLayoutComponent_updatesWindowLayoutFromContextAfterRotation()
            throws InterruptedException {
        assumeExtensionVersionSupportsWindowContextLayout();
        assumeSupportsRotation();

        final TestConfigChangeHandlingActivity activity = startFullScreenActivityNewTask(
                TestConfigChangeHandlingActivity.class, null /* activityId */);

        // Fix the device orientation before the test begins.
        setActivityOrientationActivityHandlesOrientationChanges(activity,
                ORIENTATION_PORTRAIT);

        // Here we make an assumption that the full-screen activity and the APPLICATION_OVERLAY
        // Window are located in the same area on Display.
        Context windowContext = createContextWithNonActivityWindow();
        WindowLayoutInfo firstWindowLayoutContext = getExtensionWindowLayoutInfo(windowContext);
        Rect windowContextBounds = windowContext.getSystemService(
                        WindowManager.class).getCurrentWindowMetrics().getBounds();

        final Rect firstBounds = getActivityBounds(activity);
        final Rect firstMaximumBounds = getMaximumActivityBounds(activity);
        WindowLayoutInfo firstWindowLayoutActivity = getExtensionWindowLayoutInfo(
                activity);
        boolean doesDisplayRotateForOrientation = doesDisplayRotateForOrientation(
                firstMaximumBounds, windowContextBounds);
        assertEqualWindowLayoutInfo(firstWindowLayoutActivity, firstWindowLayoutContext,
                firstBounds, windowContextBounds, doesDisplayRotateForOrientation);

        // Trigger a rotation to the Display via Activity orientation request.
        setActivityOrientationActivityHandlesOrientationChanges(activity,
                ORIENTATION_LANDSCAPE);

        WindowLayoutInfo secondWindowLayoutActivity = getExtensionWindowLayoutInfo(
                activity);
        final Rect secondBounds = getActivityBounds(activity);
        final Rect secondMaximumBounds = getMaximumActivityBounds(activity);

        // We assume after rotation both the Activity and the OVERLAY window are still located
        // in the same area, so their Display Features are still the same.
        WindowLayoutInfo secondWindowLayoutContext =
                getExtensionWindowLayoutInfo(windowContext);
        Rect secondWindowContextBounds = windowContext.getSystemService(
                        WindowManager.class).getCurrentWindowMetrics()
                .getBounds();
        assertEqualWindowLayoutInfo(secondWindowLayoutActivity, secondWindowLayoutContext,
                secondBounds, secondWindowContextBounds,
                doesDisplayRotateForOrientation(secondMaximumBounds,
                        secondWindowContextBounds));

        // Verify Activity Display Feature is consistent regardless of rotation.
        doesDisplayRotateForOrientation = doesDisplayRotateForOrientation(
                firstMaximumBounds, secondMaximumBounds);
        assertEqualWindowLayoutInfo(firstWindowLayoutActivity, secondWindowLayoutActivity,
                firstBounds, secondBounds, doesDisplayRotateForOrientation);
    }

    @Test
    @ApiTest(apis = {
            "androidx.window.extensions.layout.WindowLayoutComponent#addWindowLayoutInfoListener"})
    public void testGetWindowLayoutInfo_windowRecreated_windowLayoutUpdates()
            throws InterruptedException {
        assumeSupportsRotation();
        final TestActivity activity = startFullScreenActivityNewTask(TestActivity.class,
                null /* activityId */);

        mWindowLayoutInfo = getExtensionWindowLayoutInfo(activity);
        assumeHasDisplayFeatures(mWindowLayoutInfo);

        setActivityOrientationActivityDoesNotHandleOrientationChanges(activity,
                ORIENTATION_PORTRAIT);
        final WindowLayoutInfo portraitWindowLayoutInfo =
                getExtensionWindowLayoutInfo(activity);
        final Rect portraitBounds = getActivityBounds(activity);
        final Rect portraitMaximumBounds = getMaximumActivityBounds(activity);

        setActivityOrientationActivityDoesNotHandleOrientationChanges(activity,
                ORIENTATION_LANDSCAPE);
        final WindowLayoutInfo landscapeWindowLayoutInfo =
                getExtensionWindowLayoutInfo(activity);
        final Rect landscapeBounds = getActivityBounds(activity);
        final Rect landscapeMaximumBounds = getMaximumActivityBounds(activity);

        final boolean doesDisplayRotateForOrientation = doesDisplayRotateForOrientation(
                portraitMaximumBounds, landscapeMaximumBounds);
        assertTrue(doesDisplayRotateForOrientation);
        assertEqualWindowLayoutInfo(portraitWindowLayoutInfo, landscapeWindowLayoutInfo,
                portraitBounds, landscapeBounds, doesDisplayRotateForOrientation);
    }

    /**
     * Tests that if sidecar is also present, then it returns the same display features as
     * extensions.
     */
    @CddTest(requirements = {"7.1.1.1"})
    @Test
    public void testSidecarHasSameDisplayFeatures() throws InterruptedException {
        TestActivity activity = startFullScreenActivityNewTask(TestActivity.class,
                null /* activityId */);
        assumeSidecarSupportedDevice(activity);
        mWindowLayoutInfo = getExtensionWindowLayoutInfo(activity);
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
        final SidecarInterface sidecarInterface = getSidecarInterface(activity);
        final List<SidecarDisplayFeature> sidecarDisplayFeatures = sidecarInterface
                .getWindowLayoutInfo(getActivityWindowToken(activity)).displayFeatures;

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

    /**
     * Tests that the public API for {@link DisplayFeature} matches the API
     * provided on device. The window-extensions artifact is provided by OEMs
     * and may not match the API defined in the androidx repository.
     */
    @Test
    public void testDisplayFeature_publicApi() throws NoSuchMethodException {
        Class<DisplayFeature> displayFeatureClass = DisplayFeature.class;

        /* DisplayFeature Method Validation */
        validateClassInfo(displayFeatureClass,
                "getBounds", new Class<?>[]{}, Rect.class);
    }

    /**
     * Tests that the public API of {@link FoldingFeature} matches the implementation provided.
     */
    @Test
    public void testFoldingFeature_publicApi() throws NoSuchMethodException {
        Class<DisplayFeature> displayFeatureClass = DisplayFeature.class;
        Class<FoldingFeature> foldingFeatureClass = FoldingFeature.class;
        // Assert Instance Hierarchy. Reason: OEMs should not change the class hierarchy.
        assertTrue(displayFeatureClass.isAssignableFrom(foldingFeatureClass));

        // Validate the signature of public methods in FoldingFeature
        validateClassInfo(foldingFeatureClass,
                "toString", new Class<?>[]{}, String.class);
        validateClassInfo(foldingFeatureClass,
                "equals", new Class<?>[]{Object.class}, boolean.class);
        validateClassInfo(foldingFeatureClass,
                "hashCode", new Class<?>[]{}, int.class);

        // Create a FoldingFeature instance with any constructor (just checking it works).
        final int foldType = TYPE_FOLD;
        final int foldState = STATE_FLAT;
        final Rect foldBoundaries = new Rect(0, 1, 1, 0);
        FoldingFeature foldingFeatureInstance =
                new FoldingFeature(foldBoundaries, foldType, foldState);

        /* FoldingFeature Instance Validation */
        assertEquals(foldBoundaries, foldingFeatureInstance.getBounds());
        assertEquals(foldType, foldingFeatureInstance.getType());
        assertEquals(foldState, foldingFeatureInstance.getState());
    }

    /**
     * Tests that the public API of {@link WindowLayoutInfo} matches the implementation provided.
     */
    @Test
    public void testWindowLayoutInfo_publicApi() throws NoSuchMethodException {
        // Create a FoldingFeature that will be added to WindowLayoutInfo as a DisplayFeature
        final Rect foldBoundaries = new Rect(0, 1, 1, 0);
        FoldingFeature foldingFeature = new FoldingFeature(foldBoundaries, TYPE_FOLD, STATE_FLAT);
        // Add the FoldingFeature to a list of DisplayFeatures
        List<DisplayFeature> displayFeatures = new ArrayList<>();
        displayFeatures.add(foldingFeature);
        // Create a WindowLayoutInfo that holds the DisplayFeatures
        WindowLayoutInfo windowLayoutInfo = new WindowLayoutInfo(displayFeatures);

        /* WindowLayoutInfo Instance Validation */
        assertEquals(displayFeatures, windowLayoutInfo.getDisplayFeatures());

        Class<WindowLayoutInfo> windowLayoutInfoClass = WindowLayoutInfo.class;
        // Validate the signature of public methods in WindowLayoutInfo
        validateClassInfo(windowLayoutInfoClass,
                "toString", new Class<?>[]{}, String.class);
        validateClassInfo(windowLayoutInfoClass,
                "equals", new Class<?>[]{Object.class}, boolean.class);
        validateClassInfo(windowLayoutInfoClass,
                "hashCode", new Class<?>[]{}, int.class);
    }

    /**
     * Verifies that the inputClass has the expected access modifiers, function name,
     * input parameters, and return type. Fails if any are not what is expected.
     * @param inputClass: The Class in Question; Type: Class.
     * @param methodName: The Name of the Method to Check; Type: String.
     * @param parameterList: A list of classes representing the input parameter's Data Types.
     * @param returnType: The return type of the method; Type: Class.
     * @throws NoSuchMethodException: If No Class found, throws a NoSuchMethodException error.
     */
    public void validateClassInfo(Class<?> inputClass, String methodName, Class<?>[] parameterList,
            Class<?> returnType) throws NoSuchMethodException {
        // Get the specified method from the class.
        // Fails if it cannot find the methodName with the Correct input parameters.
        Method classMethod = inputClass.getMethod(methodName, parameterList);

        assertEquals(returnType, classMethod.getReturnType());
        // This should not fail if getMethod did not fail.
        assertTrue(Modifier.isPublic(classMethod.getModifiers()));
    }

    private void changeActivityOrientationThenVerifyWindowLayout(
            TestActivity testActivity, Context listenerContext)
            throws Exception {
        // Set activity to portrait
        setActivityOrientationActivityDoesNotHandleOrientationChanges(testActivity,
                ORIENTATION_PORTRAIT);

        // Create the callback, onWindowLayoutChanged should only be called twice in this
        // test, not the third time when the orientation will change because the listener will be
        // removed.
        TestValueCountConsumer<WindowLayoutInfo> windowLayoutInfoConsumer =
                new TestValueCountConsumer<>();
        windowLayoutInfoConsumer.setCount(1);

        // Add window layout listener for mWindowToken - onWindowLayoutChanged should be called
        mWindowLayoutComponent.addWindowLayoutInfoListener(
                listenerContext, windowLayoutInfoConsumer);
        // Initial registration invokes a consumer callback synchronously, clear the queue to
        // make sure there's no residual value or from the first orientation change.
        windowLayoutInfoConsumer.clearQueue();

        // Change the activity orientation - onWindowLayoutChanged should be called
        setActivityOrientationActivityDoesNotHandleOrientationChanges(testActivity,
                ORIENTATION_LANDSCAPE);

        // Check we have received exactly one layout update.
        assertNotNull(windowLayoutInfoConsumer.waitAndGet());


        // Remove the listener
        mWindowLayoutComponent.removeWindowLayoutInfoListener(windowLayoutInfoConsumer);
        windowLayoutInfoConsumer.clearQueue();

        // Change the activity orientation - onWindowLayoutChanged should NOT be called
        setActivityOrientationActivityDoesNotHandleOrientationChanges(testActivity,
                ORIENTATION_PORTRAIT);
        WindowLayoutInfo lastValue = windowLayoutInfoConsumer.waitAndGet();
        assertNull(lastValue);
    }

    private void changeDisplayMetricThenVerifyWindowLayout(
            Context context, DisplayMetricsSession displaySession) throws Exception {
        // Create the callback, onWindowLayoutChanged should only be called twice in this
        // test, not the third time when the orientation will change because the listener will be
        // removed.
        TestValueCountConsumer<WindowLayoutInfo> windowLayoutInfoConsumer =
                new TestValueCountConsumer<>();
        windowLayoutInfoConsumer.setCount(1);

        // Add window layout listener for mWindowToken - onWindowLayoutChanged should be called
        mWindowLayoutComponent.addWindowLayoutInfoListener(context, windowLayoutInfoConsumer);
        // Initial registration invokes a consumer callback synchronously, clear the queue to
        // make sure there's no residual value or from the first orientation change.
        WindowLayoutInfo windowLayoutInit = windowLayoutInfoConsumer.waitAndGet();
        assertNotNull(windowLayoutInit);
        windowLayoutInfoConsumer.clearQueue();

        // Change the display size - onWindowLayoutChanged should be called
        final double displayResizeRatio = 0.8;
        displaySession.changeDisplayMetrics(
                displayResizeRatio,
                1.0 /* densityRatio */);
        WindowLayoutInfo windowLayoutUpdated = windowLayoutInfoConsumer.waitAndGet();

        // Check we have received exactly one layout update.
        assertNotNull(windowLayoutUpdated);
        assertEquals(
                windowLayoutInit.getDisplayFeatures().size(),
                windowLayoutUpdated.getDisplayFeatures().size());

        // Remove the listener
        mWindowLayoutComponent.removeWindowLayoutInfoListener(windowLayoutInfoConsumer);
        windowLayoutInfoConsumer.clearQueue();

        // Restore Display to original size - onWindowLayoutChanged should NOT be called
        displaySession.restoreDisplayMetrics();
        WindowLayoutInfo lastValue = windowLayoutInfoConsumer.waitAndGet();
        assertNull(lastValue);
    }
}
