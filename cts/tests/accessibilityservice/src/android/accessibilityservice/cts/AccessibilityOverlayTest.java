/*
 * Copyright (C) 2018 The Android Open Source Project
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

package android.accessibilityservice.cts;

import static android.accessibilityservice.cts.utils.ActivityLaunchUtils.launchActivityOnSpecifiedDisplayAndWaitForItToBeOnscreen;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeTrue;

import android.accessibility.cts.common.AccessibilityDumpOnFailureRule;
import android.accessibility.cts.common.InstrumentedAccessibilityService;
import android.accessibility.cts.common.InstrumentedAccessibilityServiceTestRule;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.cts.activities.AccessibilityWindowQueryActivity;
import android.accessibilityservice.cts.utils.ActivityLaunchUtils;
import android.accessibilityservice.cts.utils.AsyncUtils;
import android.accessibilityservice.cts.utils.DisplayUtils;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Binder;
import android.platform.test.annotations.Presubmit;
import android.server.wm.CtsWindowInfoUtils;
import android.text.TextUtils;
import android.util.SparseArray;
import android.view.Display;
import android.view.SurfaceControl;
import android.view.SurfaceControlViewHost;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.Button;
import android.widget.FrameLayout;
import android.window.WindowInfosListenerForTest;

import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.CddTest;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;

// Test that an AccessibilityService can display an accessibility overlay
@RunWith(AndroidJUnit4.class)
@CddTest(requirements = {"3.10/C-1-1,C-1-2"})
@Presubmit
public class AccessibilityOverlayTest {

    private static Instrumentation sInstrumentation;
    private static UiAutomation sUiAutomation;
    InstrumentedAccessibilityService mService;

    private InstrumentedAccessibilityServiceTestRule<StubAccessibilityButtonService>
            mServiceRule = new InstrumentedAccessibilityServiceTestRule<>(
            StubAccessibilityButtonService.class);

    private AccessibilityDumpOnFailureRule mDumpOnFailureRule =
            new AccessibilityDumpOnFailureRule();

    @Rule
    public final RuleChain mRuleChain = RuleChain
            .outerRule(mServiceRule)
            .around(mDumpOnFailureRule);

    @BeforeClass
    public static void oneTimeSetUp() {
        sInstrumentation = InstrumentationRegistry.getInstrumentation();
        sUiAutomation = sInstrumentation
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        AccessibilityServiceInfo info = sUiAutomation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS;
        sUiAutomation.setServiceInfo(info);
    }

    @AfterClass
    public static void postTestTearDown() {
        sUiAutomation.destroy();
    }

    @Before
    public void setUp() {
        mService = mServiceRule.getService();
    }

    @Test
    public void testA11yServiceShowsOverlay_shouldAppear() throws Exception {
        final String overlayTitle = "Overlay title";
        sUiAutomation.executeAndWaitForEvent(() -> mService.runOnServiceSync(() -> {
            addOverlayWindow(mService, overlayTitle);
        }), (event) -> findOverlayWindow(Display.DEFAULT_DISPLAY) != null, AsyncUtils.DEFAULT_TIMEOUT_MS);

        assertTrue(TextUtils.equals(findOverlayWindow(Display.DEFAULT_DISPLAY).getTitle(), overlayTitle));
    }

    @Test
    public void testA11yServiceShowsOverlayOnVirtualDisplay_shouldAppear() throws Exception {
        assumeTrue("Device does not support activities on secondary displays",
                sInstrumentation.getContext().getPackageManager().hasSystemFeature(
                        PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS));
        try (final DisplayUtils.VirtualDisplaySession displaySession =
                     new DisplayUtils.VirtualDisplaySession()) {
            final Display newDisplay = displaySession.createDisplayWithDefaultDisplayMetricsAndWait(
                    mService, false);
            final int displayId = newDisplay.getDisplayId();
            final String overlayTitle = "Overlay title on virtualDisplay";

            // Create an initial activity window on the virtual display to ensure that
            // AccessibilityWindowManager is tracking windows for the display.
            launchActivityOnSpecifiedDisplayAndWaitForItToBeOnscreen(sInstrumentation,
                    sUiAutomation,
                    AccessibilityWindowQueryActivity.class,
                    displayId);

            sUiAutomation.executeAndWaitForEvent(() -> mService.runOnServiceSync(() -> {
                addOverlayWindow(mService.createDisplayContext(newDisplay), overlayTitle);
            }), (event) -> findOverlayWindow(displayId) != null, AsyncUtils.DEFAULT_TIMEOUT_MS);

            assertTrue(TextUtils.equals(findOverlayWindow(displayId).getTitle(), overlayTitle));
        }
    }

    @Test
    public void testA11yServiceShowsDisplayOverlayUsingSurfaceControl_shouldAppearAndDisappear()
            throws Exception {
        final String overlayTitle = "Overlay title";
        final Button button = new Button(mService);
        button.setText("Button");
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = 1;
        params.height = 1;
        params.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
        params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        params.setTitle(overlayTitle);
        Display display =
                mService.getSystemService(DisplayManager.class).getDisplay(Display.DEFAULT_DISPLAY);
        Context context = mService.createDisplayContext(display);
        SurfaceControl sc;
        SurfaceControlViewHost viewHost =
                mService.getOnService(
                        () -> {
                            return new SurfaceControlViewHost(context, display, new Binder());
                        });
        sc = viewHost.getSurfacePackage().getSurfaceControl();
        SurfaceControl.Transaction t = new SurfaceControl.Transaction();
        t.setVisibility(sc, true).setLayer(sc, 1).apply();
        sUiAutomation.executeAndWaitForEvent(
                () ->
                        mService.runOnServiceSync(
                                () -> {
                                    viewHost.setView(button, params);
                                    mService.attachAccessibilityOverlayToDisplay(
                                            Display.DEFAULT_DISPLAY, sc);
                                }),
                (event) ->
                        ActivityLaunchUtils.findWindowByTitle(sUiAutomation, overlayTitle) != null,
                AsyncUtils.DEFAULT_TIMEOUT_MS);
        checkTrustedOverlayExists(overlayTitle);
        // Remove the overlay.
        sUiAutomation.executeAndWaitForEvent(
                () ->
                        mService.runOnServiceSync(
                                () -> {
                                    t.reparent(sc, null).apply();
                                    t.close();
                                    sc.release();
                                }),
                (event) ->
                        ActivityLaunchUtils.findWindowByTitle(sUiAutomation, overlayTitle) == null,
                AsyncUtils.DEFAULT_TIMEOUT_MS);
    }

    @Test
    public void testA11yServiceShowsWindowOverlayUsingSurfaceControl_shouldAppearAndDisappear()
            throws Exception {
        // Show an activity on screen.
        final Activity activity =
                launchActivityOnSpecifiedDisplayAndWaitForItToBeOnscreen(
                        sInstrumentation,
                        sUiAutomation,
                        AccessibilityWindowQueryActivity.class,
                        Display.DEFAULT_DISPLAY);
        try {
            final Display display =
                    mService.getSystemService(DisplayManager.class)
                            .getDisplay(Display.DEFAULT_DISPLAY);
            final Context context = mService.createDisplayContext(display);
            final SurfaceControlViewHost viewHost =
                    mService.getOnService(
                            () -> new SurfaceControlViewHost(context, display, new Binder()));
            final SurfaceControl sc = viewHost.getSurfacePackage().getSurfaceControl();
            final SurfaceControl.Transaction transaction = new SurfaceControl.Transaction();
            transaction.setVisibility(sc, true).apply();

            // Create an accessibility overlay hosting a FrameLayout with the same size
            // as the activity's root node bounds.
            final AccessibilityNodeInfo activityRootNode = sUiAutomation.getRootInActiveWindow();
            final Rect activityRootNodeBounds = new Rect();
            activityRootNode.getBoundsInWindow(activityRootNodeBounds);
            final WindowManager.LayoutParams overlayParams = new WindowManager.LayoutParams();
            final String overlayTitle = "App Overlay title";
            overlayParams.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
            overlayParams.format = PixelFormat.TRANSLUCENT;
            overlayParams.setTitle(overlayTitle);
            overlayParams.accessibilityTitle = overlayTitle;
            overlayParams.height = activityRootNodeBounds.height();
            overlayParams.width = activityRootNodeBounds.width();
            final FrameLayout overlayLayout = new FrameLayout(context);
            mService.runOnServiceSync(() -> viewHost.setView(overlayLayout, overlayParams));

            // Add a new Button view inside the overlay's FrameLayout, directly on top of
            // the window-space bounds of a node within the activity.
            final AccessibilityNodeInfo activityNodeToDrawOver =
                    activityRootNode.findAccessibilityNodeInfosByViewId(
                            "android.accessibilityservice.cts:id/button1").get(0);
            final Rect activityNodeToDrawOverBounds = new Rect();
            activityNodeToDrawOver.getBoundsInWindow(activityNodeToDrawOverBounds);
            Button overlayButton = new Button(context);
            final String buttonText = "overlay button";
            overlayButton.setText(buttonText);
            overlayButton.setX(activityNodeToDrawOverBounds.left);
            overlayButton.setY(activityNodeToDrawOverBounds.top);
            mService.runOnServiceSync(() ->
                    overlayLayout.addView(overlayButton,
                            new FrameLayout.LayoutParams(
                                    activityNodeToDrawOverBounds.width(),
                                    activityNodeToDrawOverBounds.height())));

            // Attach the SurfaceControlViewHost as an accessibility overlay to the activity window.
            sUiAutomation.executeAndWaitForEvent(
                    () ->
                            mService.runOnServiceSync(
                                    () -> mService.attachAccessibilityOverlayToWindow(
                                            activityRootNode.getWindowId(), sc)),
                    (event) -> {
                        final AccessibilityWindowInfo overlayWindow =
                                ActivityLaunchUtils.findWindowByTitle(sUiAutomation, overlayTitle);
                        if (overlayWindow == null) {
                            return false;
                        }
                        if (overlayWindow.getType()
                                == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                            final AccessibilityNodeInfo overlayButtonNode =
                                    overlayWindow.getRoot().findAccessibilityNodeInfosByText(
                                            buttonText).get(0);
                            final Rect expected = new Rect();
                            final Rect actual = new Rect();

                            // The overlay button should have the same window-space and screen-space
                            // bounds as the view in the activity, as configured above.
                            activityNodeToDrawOver.getBoundsInWindow(expected);
                            overlayButtonNode.getBoundsInWindow(actual);
                            assertThat(actual.isEmpty()).isFalse();
                            assertThat(actual).isEqualTo(expected);
                            activityNodeToDrawOver.getBoundsInScreen(expected);
                            overlayButtonNode.getBoundsInScreen(actual);
                            assertThat(actual.isEmpty()).isFalse();
                            assertThat(actual).isEqualTo(expected);
                            return true;
                        }
                        return false;
                    },
                    AsyncUtils.DEFAULT_TIMEOUT_MS);

            checkTrustedOverlayExists(overlayTitle);

            // Remove the overlay.
            sUiAutomation.executeAndWaitForEvent(
                    () ->
                            mService.runOnServiceSync(
                                    () -> {
                                        transaction.reparent(sc, null).apply();
                                        transaction.close();
                                        sc.release();
                                    }),
                    (event) ->
                            // There should be no windows with this window title.
                            ActivityLaunchUtils.findWindowByTitle(sUiAutomation, overlayTitle)
                                    == null,
                    AsyncUtils.DEFAULT_TIMEOUT_MS);
        } finally {
            if (activity != null) {
                activity.finish();
            }
        }
    }

    private void checkTrustedOverlayExists(String overlayTitle) throws Exception {
        try {
            sUiAutomation.adoptShellPermissionIdentity(
                    android.Manifest.permission.ACCESS_SURFACE_FLINGER);
            Predicate<List<WindowInfosListenerForTest.WindowInfo>> windowPredicate =
                    windows -> windows.stream().anyMatch(
                            window -> window.name.contains(overlayTitle)
                                    && window.isTrustedOverlay);
            assertWithMessage("Expected to find trusted overlay window").that(
                    CtsWindowInfoUtils.waitForWindowInfos(windowPredicate,
                            AsyncUtils.DEFAULT_TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        } finally {
            sUiAutomation.dropShellPermissionIdentity();
        }
    }

    private void addOverlayWindow(Context context, String overlayTitle) {
        final Button button = new Button(context);
        button.setText("Button");
        final WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.width = WindowManager.LayoutParams.MATCH_PARENT;
        params.height = WindowManager.LayoutParams.MATCH_PARENT;
        params.flags =
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_INSET_DECOR;
        params.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY;
        params.setTitle(overlayTitle);
        context.getSystemService(WindowManager.class).addView(button, params);
    }

    private AccessibilityWindowInfo findOverlayWindow(int displayId) {
        final SparseArray<List<AccessibilityWindowInfo>> allWindows =
                sUiAutomation.getWindowsOnAllDisplays();
        final List<AccessibilityWindowInfo> windows = allWindows.get(displayId);

        if (windows != null) {
            for (AccessibilityWindowInfo window : windows) {
                if (window.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) {
                    return window;
                }
            }
        }
        return null;
    }
}
