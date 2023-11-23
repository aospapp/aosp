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

package android.accessibilityservice.cts;

import static android.accessibilityservice.cts.utils.ActivityLaunchUtils.launchActivityAndWaitForItToBeOnscreen;
import static android.accessibilityservice.cts.utils.AsyncUtils.DEFAULT_TIMEOUT_MS;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.accessibility.cts.common.AccessibilityDumpOnFailureRule;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.accessibilityservice.cts.activities.AccessibilityTestActivity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.graphics.Point;
import android.graphics.Rect;
import android.hardware.display.DisplayManager;
import android.os.Bundle;
import android.platform.test.annotations.Presubmit;
import android.server.wm.CtsWindowInfoUtils;
import android.util.DisplayMetrics;
import android.view.Display;
import android.view.SurfaceControlViewHost;
import android.view.SurfaceHolder;
import android.view.SurfaceView;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import androidx.test.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.CddTest;

import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.RuleChain;
import org.junit.runner.RunWith;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Tests that AccessibilityNodeInfos from an embedded hierarchy that is present to another
 * hierarchy are properly populated.
 */
@CddTest(requirements = {"3.10/C-1-1,C-1-2"})
@RunWith(AndroidJUnit4.class)
@Presubmit
public class AccessibilityEmbeddedHierarchyTest {
    private static Instrumentation sInstrumentation;
    private static UiAutomation sUiAutomation;

    private final ActivityTestRule<AccessibilityEmbeddedHierarchyActivity> mActivityRule =
            new ActivityTestRule<>(AccessibilityEmbeddedHierarchyActivity.class, false, false);

    private static final String HOST_PARENT_RESOURCE_NAME =
            "android.accessibilityservice.cts:id/host_surfaceview";
    private static final String EMBEDDED_VIEW_RESOURCE_NAME =
            "android.accessibilityservice.cts:id/embedded_editText";

    private final AccessibilityDumpOnFailureRule mDumpOnFailureRule =
            new AccessibilityDumpOnFailureRule();

    private AccessibilityEmbeddedHierarchyActivity mActivity;

    @Rule
    public final RuleChain mRuleChain = RuleChain
            .outerRule(mActivityRule)
            .around(mDumpOnFailureRule);

    @BeforeClass
    public static void oneTimeSetup() {
        sInstrumentation = InstrumentationRegistry.getInstrumentation();
        sUiAutomation = sInstrumentation.getUiAutomation();
        AccessibilityServiceInfo info = sUiAutomation.getServiceInfo();
        info.flags |= AccessibilityServiceInfo.FLAG_REQUEST_TOUCH_EXPLORATION_MODE;
        sUiAutomation.setServiceInfo(info);
    }

    @AfterClass
    public static void postTestTearDown() {
        sUiAutomation.destroy();
    }

    @Before
    public void setUp() throws Throwable {
        mActivity = launchActivityAndWaitForItToBeOnscreen(sInstrumentation, sUiAutomation,
                mActivityRule);
        mActivity.waitForEmbeddedHierarchy();
    }

    @Test
    public void testEmbeddedViewCanBeFound() {
        final AccessibilityNodeInfo target =
                findEmbeddedAccessibilityNodeInfo(sUiAutomation.getRootInActiveWindow());
        assertThat(target).isNotNull();
    }

    @Test
    public void testEmbeddedView_PerformActionTransfersWindowInputFocus() {
        final View hostView = mActivity.mInputFocusableView;
        final View embeddedView = mActivity.mViewHost.getView();
        final AccessibilityNodeInfo embeddedNode =
                findEmbeddedAccessibilityNodeInfo(sUiAutomation.getRootInActiveWindow());
        assertThat(hostView.isFocusable()).isTrue();
        assertThat(embeddedView.isFocusable()).isTrue();

        // Start by ensuring the host-side view has window input focus.
        hostView.performClick();
        assertThat(CtsWindowInfoUtils.waitForWindowFocus(hostView, true)).isTrue();
        assertThat(embeddedView.hasWindowFocus()).isFalse();

        // ACTION_ACCESSIBILITY_FOCUS should not transfer window input focus.
        embeddedNode.performAction(AccessibilityNodeInfo.ACTION_ACCESSIBILITY_FOCUS);
        assertThat(CtsWindowInfoUtils.waitForWindowFocus(embeddedView, true)).isFalse();

        // Other actions like ACTION_CLICK should transfer window input focus.
        embeddedNode.performAction(AccessibilityNodeInfo.ACTION_CLICK);
        assertThat(CtsWindowInfoUtils.waitForWindowFocus(embeddedView, true)).isTrue();
        assertThat(hostView.hasWindowFocus()).isFalse();
    }

    @Test
    public void testEmbeddedViewCanFindItsHostParent() {
        final AccessibilityNodeInfo target =
                findEmbeddedAccessibilityNodeInfo(sUiAutomation.getRootInActiveWindow());
        final AccessibilityNodeInfo parent = target.getParent();
        assertThat(parent.getViewIdResourceName()).isEqualTo(HOST_PARENT_RESOURCE_NAME);
    }

    @Test
    public void testEmbeddedViewHasCorrectBound() {
        final AccessibilityNodeInfo target =
                findEmbeddedAccessibilityNodeInfo(sUiAutomation.getRootInActiveWindow());
        final AccessibilityNodeInfo parent = target.getParent();

        final Rect hostViewBoundsInScreen = new Rect();
        final Rect embeddedViewBoundsInScreen = new Rect();
        parent.getBoundsInScreen(hostViewBoundsInScreen);
        target.getBoundsInScreen(embeddedViewBoundsInScreen);

        assertWithMessage(
                "hostViewBoundsInScreen" + hostViewBoundsInScreen.toShortString()
                        + " doesn't contain embeddedViewBoundsInScreen"
                        + embeddedViewBoundsInScreen.toShortString()).that(
                hostViewBoundsInScreen.contains(embeddedViewBoundsInScreen)).isTrue();
    }

    @Test
    public void testEmbeddedViewHasCorrectBoundAfterHostViewMove() throws TimeoutException {
        final AccessibilityNodeInfo target =
                findEmbeddedAccessibilityNodeInfo(sUiAutomation.getRootInActiveWindow());

        final Rect hostViewBoundsInScreen = new Rect();
        final Rect newEmbeddedViewBoundsInScreen = new Rect();
        final Rect oldEmbeddedViewBoundsInScreen = new Rect();
        target.getBoundsInScreen(oldEmbeddedViewBoundsInScreen);

        // Move Host SurfaceView from (0, 0) to (50, 50).
        mActivity.requestNewLayoutForTest(50, 50);

        target.refresh();
        final AccessibilityNodeInfo parent = target.getParent();

        target.getBoundsInScreen(newEmbeddedViewBoundsInScreen);
        parent.getBoundsInScreen(hostViewBoundsInScreen);

        assertWithMessage(
                "hostViewBoundsInScreen" + hostViewBoundsInScreen.toShortString()
                        + " doesn't contain newEmbeddedViewBoundsInScreen"
                        + newEmbeddedViewBoundsInScreen.toShortString()).that(
                hostViewBoundsInScreen.contains(newEmbeddedViewBoundsInScreen)).isTrue();
        assertWithMessage(
                "newEmbeddedViewBoundsInScreen" + newEmbeddedViewBoundsInScreen.toShortString()
                        + " shouldn't be the same with oldEmbeddedViewBoundsInScreen"
                        + oldEmbeddedViewBoundsInScreen.toShortString()).that(
                newEmbeddedViewBoundsInScreen.equals(oldEmbeddedViewBoundsInScreen)).isFalse();
    }

    @Test
    public void testEmbeddedViewIsInvisibleAfterMovingOutOfScreen() throws TimeoutException {
        final AccessibilityNodeInfo target =
                findEmbeddedAccessibilityNodeInfo(sUiAutomation.getRootInActiveWindow());
        assertWithMessage("Embedded view should be visible at beginning.").that(
                target.isVisibleToUser()).isTrue();

        // Move Host SurfaceView out of screen
        final Point screenSize = getScreenSize();
        mActivity.requestNewLayoutForTest(screenSize.x, screenSize.y);

        target.refresh();
        assertWithMessage("Embedded view should be invisible after moving out of screen.").that(
                target.isVisibleToUser()).isFalse();
    }

    private AccessibilityNodeInfo findEmbeddedAccessibilityNodeInfo(AccessibilityNodeInfo root) {
        final int childCount = root.getChildCount();
        for (int i = 0; i < childCount; i++) {
            final AccessibilityNodeInfo info = root.getChild(i);
            if (info == null) {
                continue;
            }
            if (EMBEDDED_VIEW_RESOURCE_NAME.equals(info.getViewIdResourceName())) {
                return info;
            }
            if (info.getChildCount() != 0) {
                return findEmbeddedAccessibilityNodeInfo(info);
            }
        }
        return null;
    }

    private Point getScreenSize() {
        final DisplayManager dm = sInstrumentation.getContext().getSystemService(
                DisplayManager.class);
        final Display display = dm.getDisplay(Display.DEFAULT_DISPLAY);
        final DisplayMetrics metrics = new DisplayMetrics();
        display.getRealMetrics(metrics);
        return new Point(metrics.widthPixels, metrics.heightPixels);
    }

    /**
     * This class is an dummy {@link android.app.Activity} used to perform embedded hierarchy
     * testing of the accessibility feature by interaction with the UI widgets.
     */
    public static class AccessibilityEmbeddedHierarchyActivity extends
            AccessibilityTestActivity implements SurfaceHolder.Callback {
        private final CountDownLatch mCountDownLatch = new CountDownLatch(1);

        private static final int DEFAULT_WIDTH = 150;
        private static final int DEFAULT_HEIGHT = 150;

        private SurfaceView mSurfaceView;
        private View mInputFocusableView;
        private SurfaceControlViewHost mViewHost;

        @Override
        protected void onCreate(Bundle savedInstanceState) {
            super.onCreate(savedInstanceState);
            setContentView(R.layout.accessibility_embedded_hierarchy_test_host_side);
            mSurfaceView = findViewById(R.id.host_surfaceview);
            mSurfaceView.getHolder().addCallback(this);
            mInputFocusableView = findViewById(R.id.host_editText);
        }

        @Override
        public void surfaceCreated(SurfaceHolder holder) {
            mViewHost = new SurfaceControlViewHost(this, this.getDisplay(),
                    mSurfaceView.getHostToken());

            mSurfaceView.setChildSurfacePackage(mViewHost.getSurfacePackage());

            View layout = getLayoutInflater().inflate(
                    R.layout.accessibility_embedded_hierarchy_test_embedded_side, null);
            mViewHost.setView(layout, DEFAULT_WIDTH, DEFAULT_HEIGHT);
            mCountDownLatch.countDown();
        }

        @Override
        public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
            // No-op
        }

        @Override
        public void surfaceDestroyed(SurfaceHolder holder) {
            // No-op
        }

        public void waitForEmbeddedHierarchy() {
            try {
                assertWithMessage("timed out waiting for embedded hierarchy to init.").that(
                        mCountDownLatch.await(3, TimeUnit.SECONDS)).isTrue();
            } catch (InterruptedException e) {
                throw new AssertionError(e);
            }
        }

        public void requestNewLayoutForTest(int x, int y) throws TimeoutException {
            sUiAutomation.executeAndWaitForEvent(
                    () -> sInstrumentation.runOnMainSync(() -> {
                        mSurfaceView.setX(x);
                        mSurfaceView.setY(y);
                        mSurfaceView.requestLayout();
                    }),
                    (event) -> {
                        final Rect boundsInScreen = new Rect();
                        final AccessibilityWindowInfo window =
                                sUiAutomation.getRootInActiveWindow().getWindow();
                        window.getBoundsInScreen(boundsInScreen);
                        return !boundsInScreen.isEmpty();
                    }, DEFAULT_TIMEOUT_MS);
        }
    }
}
