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
 * limitations under the License
 */

package android.server.wm;

import static android.content.pm.PackageManager.FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY;
import static android.hardware.display.DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC;
import static android.view.Display.DEFAULT_DISPLAY;
import static android.view.Display.INVALID_DISPLAY;
import static android.view.KeyEvent.ACTION_DOWN;
import static android.view.KeyEvent.ACTION_UP;
import static android.view.KeyEvent.FLAG_CANCELED;
import static android.view.KeyEvent.KEYCODE_0;
import static android.view.KeyEvent.KEYCODE_1;
import static android.view.KeyEvent.KEYCODE_2;
import static android.view.KeyEvent.KEYCODE_3;
import static android.view.KeyEvent.KEYCODE_4;
import static android.view.KeyEvent.KEYCODE_5;
import static android.view.KeyEvent.KEYCODE_6;
import static android.view.KeyEvent.KEYCODE_7;
import static android.view.KeyEvent.KEYCODE_8;

import static androidx.test.InstrumentationRegistry.getInstrumentation;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assume.assumeTrue;
import static org.junit.Assume.assumeFalse;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.ImageReader;
import android.os.SystemClock;
import android.platform.test.annotations.Presubmit;
import android.view.Display;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager.LayoutParams;

import androidx.test.filters.FlakyTest;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.Test;

import java.util.ArrayList;

import javax.annotation.concurrent.GuardedBy;

/**
 * Ensure window focus assignment is executed as expected.
 *
 * Build/Install/Run:
 *     atest WindowFocusTests
 */
@Presubmit
public class WindowFocusTests extends WindowManagerTestBase {

    private static void sendKey(int action, int keyCode, int displayId) {
        final KeyEvent keyEvent = new KeyEvent(action, keyCode);
        keyEvent.setDisplayId(displayId);
        SystemUtil.runWithShellPermissionIdentity(() -> {
            getInstrumentation().sendKeySync(keyEvent);
        });
    }

    private static void sendAndAssertTargetConsumedKey(InputTargetActivity target, int keyCode,
            int targetDisplayId) {
        sendAndAssertTargetConsumedKey(target, ACTION_DOWN, keyCode, targetDisplayId);
        sendAndAssertTargetConsumedKey(target, ACTION_UP, keyCode, targetDisplayId);
    }

    private static void sendAndAssertTargetConsumedKey(InputTargetActivity target, int action,
            int keyCode, int targetDisplayId) {
        final int eventCount = target.getKeyEventCount();
        sendKey(action, keyCode, targetDisplayId);
        target.assertAndConsumeKeyEvent(action, keyCode, 0 /* flags */);
        assertEquals(target.getLogTag() + " must only receive key event sent.", eventCount,
                target.getKeyEventCount());
    }

    private static void tapOnCenterOfDisplay(int displayId) {
        final Point point = new Point();
        getInstrumentation().getTargetContext()
                .getSystemService(DisplayManager.class)
                .getDisplay(displayId)
                .getSize(point);
        final int x = point.x / 2;
        final int y = point.y / 2;
        final long downTime = SystemClock.elapsedRealtime();
        final MotionEvent downEvent = MotionEvent.obtain(downTime, downTime,
                MotionEvent.ACTION_DOWN, x, y, 0 /* metaState */);
        downEvent.setDisplayId(displayId);
        getInstrumentation().sendPointerSync(downEvent);
        final MotionEvent upEvent = MotionEvent.obtain(downTime, SystemClock.elapsedRealtime(),
                MotionEvent.ACTION_UP, x, y, 0 /* metaState */);
        upEvent.setDisplayId(displayId);
        getInstrumentation().sendPointerSync(upEvent);
    }

    /** Checks if the device supports multi-display. */
    private static boolean supportsMultiDisplay() {
        return getInstrumentation().getTargetContext().getPackageManager()
                .hasSystemFeature(FEATURE_ACTIVITIES_ON_SECONDARY_DISPLAYS);
    }

    /** Checks if per-display-focus is enabled in the device. */
    private static boolean perDisplayFocusEnabled() {
        return getInstrumentation().getTargetContext().getResources()
                    .getBoolean(android.R.bool.config_perDisplayFocusEnabled);
    }

    /**
     * Test the following conditions:
     * - Each display can have a focused window at the same time.
     * - Focused windows can receive display-specified key events.
     * - The top focused window can receive display-unspecified key events.
     * - Taping on a display will make the focused window on it become top-focused.
     * - The window which lost top-focus can receive display-unspecified cancel events.
     */
    @Test
    @FlakyTest(bugId = 131005232)
    public void testKeyReceiving() throws InterruptedException {
        final PrimaryActivity primaryActivity = startActivity(PrimaryActivity.class,
                DEFAULT_DISPLAY);
        sendAndAssertTargetConsumedKey(primaryActivity, KEYCODE_0, INVALID_DISPLAY);
        sendAndAssertTargetConsumedKey(primaryActivity, KEYCODE_1, DEFAULT_DISPLAY);

        assumeTrue(supportsMultiDisplay());
        // If config_perDisplayFocusEnabled, tapping on a display will not move the focus.
        assumeFalse(perDisplayFocusEnabled());
        try (VirtualDisplaySession displaySession = new VirtualDisplaySession()) {
            final int secondaryDisplayId = displaySession.createDisplay(
                    getInstrumentation().getTargetContext()).getDisplayId();
            final SecondaryActivity secondaryActivity =
                    startActivity(SecondaryActivity.class, secondaryDisplayId);
            sendAndAssertTargetConsumedKey(secondaryActivity, KEYCODE_2, INVALID_DISPLAY);
            sendAndAssertTargetConsumedKey(secondaryActivity, KEYCODE_3, secondaryDisplayId);

            primaryActivity.waitAndAssertWindowFocusState(false /* hasFocus */);

            // Press display-unspecified keys and a display-specified key but not release them.
            sendKey(ACTION_DOWN, KEYCODE_5, INVALID_DISPLAY);
            sendKey(ACTION_DOWN, KEYCODE_6, secondaryDisplayId);
            sendKey(ACTION_DOWN, KEYCODE_7, INVALID_DISPLAY);
            secondaryActivity.assertAndConsumeKeyEvent(ACTION_DOWN, KEYCODE_5, 0 /* flags */);
            secondaryActivity.assertAndConsumeKeyEvent(ACTION_DOWN, KEYCODE_6, 0 /* flags */);
            secondaryActivity.assertAndConsumeKeyEvent(ACTION_DOWN, KEYCODE_7, 0 /* flags */);

            tapOnCenterOfDisplay(DEFAULT_DISPLAY);

            // Assert only display-unspecified key would be cancelled after secondary activity is
            // not top focused if per-display focus is enabled. Otherwise, assert all non-released
            // key events sent to secondary activity would be cancelled.
            secondaryActivity.waitAssertAndConsumeKeyEvent(ACTION_UP, KEYCODE_5, FLAG_CANCELED);
            secondaryActivity.waitAssertAndConsumeKeyEvent(ACTION_UP, KEYCODE_7, FLAG_CANCELED);
            secondaryActivity.waitAssertAndConsumeKeyEvent(ACTION_UP, KEYCODE_6, FLAG_CANCELED);
            assertEquals(secondaryActivity.getLogTag() + " must only receive expected events.",
                    0 /* expected event count */, secondaryActivity.getKeyEventCount());

            // Assert primary activity become top focused after tapping on default display.
            sendAndAssertTargetConsumedKey(primaryActivity, KEYCODE_8, INVALID_DISPLAY);
        }
    }

    /**
     * Test if a display targeted by a key event can be moved to top in a single-focus system.
     */
    @Test
    @FlakyTest(bugId = 131005232)
    public void testMovingDisplayToTopByKeyEvent() throws InterruptedException {
        assumeTrue(supportsMultiDisplay());
        assumeFalse(perDisplayFocusEnabled());

        final PrimaryActivity primaryActivity = startActivity(PrimaryActivity.class,
                DEFAULT_DISPLAY);

        try (VirtualDisplaySession displaySession = new VirtualDisplaySession()) {
            final int secondaryDisplayId = displaySession.createDisplay(
                    getInstrumentation().getTargetContext()).getDisplayId();
            final SecondaryActivity secondaryActivity =
                    startActivity(SecondaryActivity.class, secondaryDisplayId);

            sendAndAssertTargetConsumedKey(primaryActivity, KEYCODE_0, DEFAULT_DISPLAY);
            sendAndAssertTargetConsumedKey(primaryActivity, KEYCODE_1, INVALID_DISPLAY);

            sendAndAssertTargetConsumedKey(secondaryActivity, KEYCODE_2, secondaryDisplayId);
            sendAndAssertTargetConsumedKey(secondaryActivity, KEYCODE_3, INVALID_DISPLAY);
        }
    }

    /**
     * Test if the client is notified about window-focus lost after the new focused window is drawn.
     */
    @Test
    public void testDelayLosingFocus() throws InterruptedException {
        final LosingFocusActivity activity = startActivity(LosingFocusActivity.class,
                DEFAULT_DISPLAY);

        getInstrumentation().runOnMainSync(activity::addChildWindow);
        activity.waitAndAssertWindowFocusState(false /* hasFocus */);
        assertFalse("Activity must lose window focus after new focused window is drawn.",
                activity.losesFocusWhenNewFocusIsNotDrawn());
    }


    /**
     * Test the following conditions:
     * - Only the top focused window can have pointer capture.
     * - The window which lost top-focus can be notified about pointer-capture lost.
     */
    @Test
    public void testPointerCapture() throws InterruptedException {
        final PrimaryActivity primaryActivity = startActivity(PrimaryActivity.class,
                DEFAULT_DISPLAY);

        // Assert primary activity can have pointer capture before we have multiple focused windows.
        getInstrumentation().runOnMainSync(primaryActivity::requestPointerCapture);
        primaryActivity.waitAndAssertPointerCaptureState(true /* hasCapture */);

        assumeTrue(supportsMultiDisplay());
        assumeFalse(perDisplayFocusEnabled());
        try (VirtualDisplaySession displaySession = new VirtualDisplaySession()) {
            final int secondaryDisplayId = displaySession.createDisplay(
                    getInstrumentation().getTargetContext()).getDisplayId();
            final SecondaryActivity secondaryActivity =
                    startActivity(SecondaryActivity.class, secondaryDisplayId);

            // Assert primary activity lost pointer capture when it is not top focused.
            primaryActivity.waitAndAssertPointerCaptureState(false /* hasCapture */);

            // Assert secondary activity can have pointer capture when it is top focused.
            getInstrumentation().runOnMainSync(secondaryActivity::requestPointerCapture);
            secondaryActivity.waitAndAssertPointerCaptureState(true /* hasCapture */);

            tapOnCenterOfDisplay(DEFAULT_DISPLAY);

            // Assert secondary activity lost pointer capture when it is not top focused.
            secondaryActivity.waitAndAssertPointerCaptureState(false /* hasCapture */);
        }
    }

    /**
     * Test if the focused window can still have focus after it is moved to another display.
     */
    @Test
    public void testDisplayChanged() throws InterruptedException {
        assumeTrue(supportsMultiDisplay());

        final PrimaryActivity primaryActivity = startActivity(PrimaryActivity.class,
                DEFAULT_DISPLAY);

        final SecondaryActivity secondaryActivity;
        try (VirtualDisplaySession displaySession = new VirtualDisplaySession()) {
            final int secondaryDisplayId = displaySession.createDisplay(
                    getInstrumentation().getTargetContext()).getDisplayId();
            secondaryActivity = startActivity(SecondaryActivity.class, secondaryDisplayId);
        }
        // Secondary display disconnected.

        assertNotNull("SecondaryActivity must be started.", secondaryActivity);
        secondaryActivity.waitAndAssertDisplayId(DEFAULT_DISPLAY);
        secondaryActivity.waitAndAssertWindowFocusState(true /* hasFocus */);

        primaryActivity.waitAndAssertWindowFocusState(false /* hasFocus */);
    }

    /**
     * Ensure that a non focused display becomes focused when tapping on a focusable window on
     * that display.
     */
    @Test
    public void testTapFocusableWindow() throws InterruptedException {
        assumeTrue(supportsMultiDisplay());
        assumeFalse(perDisplayFocusEnabled());

        PrimaryActivity primaryActivity = startActivity(PrimaryActivity.class, DEFAULT_DISPLAY);

        try (VirtualDisplaySession displaySession = new VirtualDisplaySession()) {
            final int secondaryDisplayId = displaySession.createDisplay(
                    getInstrumentation().getTargetContext()).getDisplayId();
            SecondaryActivity secondaryActivity = startActivity(SecondaryActivity.class,
                    secondaryDisplayId);

            tapOnCenterOfDisplay(DEFAULT_DISPLAY);
            // Ensure primary activity got focus
            primaryActivity.waitAndAssertWindowFocusState(true);
            secondaryActivity.waitAndAssertWindowFocusState(false);
        }
    }

    /**
     * Ensure that a non focused display does not become focused when tapping on a non-focusable
     * window on that display.
     */
    @Test
    @FlakyTest(bugId = 130467737)
    public void testTapNonFocusableWindow() throws InterruptedException {
        assumeTrue(supportsMultiDisplay());
        assumeFalse(perDisplayFocusEnabled());

        PrimaryActivity primaryActivity = startActivity(PrimaryActivity.class, DEFAULT_DISPLAY);

        try (VirtualDisplaySession displaySession = new VirtualDisplaySession()) {
            final int secondaryDisplayId = displaySession.createDisplay(
                    getInstrumentation().getTargetContext()).getDisplayId();
            SecondaryActivity secondaryActivity = startActivity(SecondaryActivity.class,
                    secondaryDisplayId);

            // Tap on a window that can't be focused and ensure that the other window in that
            // display, primaryActivity's window, doesn't get focus.
            getInstrumentation().runOnMainSync(() -> {
                View view = new View(primaryActivity);
                LayoutParams p = new LayoutParams();
                p.flags = LayoutParams.FLAG_NOT_FOCUSABLE;
                primaryActivity.getWindowManager().addView(view, p);
            });
            getInstrumentation().waitForIdleSync();

            tapOnCenterOfDisplay(DEFAULT_DISPLAY);
            // Ensure secondary activity still has focus
            secondaryActivity.waitAndAssertWindowFocusState(true);
            primaryActivity.waitAndAssertWindowFocusState(false);
        }
    }

    private static class InputTargetActivity extends FocusableActivity {
        private static final long TIMEOUT_DISPLAY_CHANGED = 1000; // milliseconds
        private static final long TIMEOUT_POINTER_CAPTURE_CHANGED = 1000;
        private static final long TIMEOUT_NEXT_KEY_EVENT = 1000;

        private final Object mLockPointerCapture = new Object();
        private final Object mLockKeyEvent = new Object();

        @GuardedBy("this")
        private int mDisplayId = INVALID_DISPLAY;
        @GuardedBy("mLockPointerCapture")
        private boolean mHasPointerCapture;
        @GuardedBy("mLockKeyEvent")
        private ArrayList<KeyEvent> mKeyEventList = new ArrayList<>();

        @Override
        public void onAttachedToWindow() {
            synchronized (this) {
                mDisplayId = getWindow().getDecorView().getDisplay().getDisplayId();
                notify();
            }
        }

        @Override
        public void onMovedToDisplay(int displayId, Configuration config) {
            synchronized (this) {
                mDisplayId = displayId;
                notify();
            }
        }

        void waitAndAssertDisplayId(int displayId) throws InterruptedException {
            synchronized (this) {
                if (mDisplayId != displayId) {
                    wait(TIMEOUT_DISPLAY_CHANGED);
                }
                assertEquals(getLogTag() + " must be moved to the display.",
                        displayId, mDisplayId);
            }
        }

        @Override
        public void onPointerCaptureChanged(boolean hasCapture) {
            synchronized (mLockPointerCapture) {
                mHasPointerCapture = hasCapture;
                mLockPointerCapture.notify();
            }
        }

        void waitAndAssertPointerCaptureState(boolean hasCapture) throws InterruptedException {
            synchronized (mLockPointerCapture) {
                if (mHasPointerCapture != hasCapture) {
                    mLockPointerCapture.wait(TIMEOUT_POINTER_CAPTURE_CHANGED);
                }
                assertEquals(getLogTag() + " must" + (hasCapture ? "" : " not")
                        + " have pointer capture.", hasCapture, mHasPointerCapture);
            }
        }

        // Should be only called from the main thread.
        void requestPointerCapture() {
            getWindow().getDecorView().requestPointerCapture();
        }

        @Override
        public boolean dispatchKeyEvent(KeyEvent event) {
            synchronized (mLockKeyEvent) {
                mKeyEventList.add(event);
                mLockKeyEvent.notify();
            }
            return super.dispatchKeyEvent(event);
        }

        int getKeyEventCount() {
            synchronized (mLockKeyEvent) {
                return mKeyEventList.size();
            }
        }

        private KeyEvent consumeKeyEvent(int action, int keyCode, int flags) {
            synchronized (mLockKeyEvent) {
                for (int i = mKeyEventList.size() - 1; i >= 0; i--) {
                    final KeyEvent event = mKeyEventList.get(i);
                    if (event.getAction() == action && event.getKeyCode() == keyCode
                            && (event.getFlags() & flags) == flags) {
                        mKeyEventList.remove(event);
                        return event;
                    }
                }
            }
            return null;
        }

        void assertAndConsumeKeyEvent(int action, int keyCode, int flags) {
            assertNotNull(getLogTag() + " must receive key event.",
                    consumeKeyEvent(action, keyCode, flags));
        }

        void waitAssertAndConsumeKeyEvent(int action, int keyCode, int flags)
                throws InterruptedException {
            if (consumeKeyEvent(action, keyCode, flags) == null) {
                synchronized (mLockKeyEvent) {
                    mLockKeyEvent.wait(TIMEOUT_NEXT_KEY_EVENT);
                }
                assertAndConsumeKeyEvent(action, keyCode, flags);
            }
        }
    }

    public static class PrimaryActivity extends InputTargetActivity { }

    public static class SecondaryActivity extends InputTargetActivity { }

    public static class LosingFocusActivity extends InputTargetActivity {
        private boolean mChildWindowHasDrawn = false;

        @GuardedBy("this")
        private boolean mLosesFocusWhenNewFocusIsNotDrawn = false;

        void addChildWindow() {
            getWindowManager().addView(new View(this) {
                @Override
                protected void onDraw(Canvas canvas) {
                    mChildWindowHasDrawn = true;
                }
            }, new LayoutParams());
        }

        @Override
        public void onWindowFocusChanged(boolean hasFocus) {
            if (!hasFocus && !mChildWindowHasDrawn) {
                synchronized (this) {
                    mLosesFocusWhenNewFocusIsNotDrawn = true;
                }
            }
            super.onWindowFocusChanged(hasFocus);
        }

        boolean losesFocusWhenNewFocusIsNotDrawn() {
            synchronized (this) {
                return mLosesFocusWhenNewFocusIsNotDrawn;
            }
        }
    }

    private static class VirtualDisplaySession implements AutoCloseable {
        private static final int WIDTH = 800;
        private static final int HEIGHT = 480;
        private static final int DENSITY = 160;

        private VirtualDisplay mVirtualDisplay;
        private ImageReader mReader;

        Display createDisplay(Context context) {
            if (mReader != null) {
                throw new IllegalStateException(
                        "Only one display can be created during this session.");
            }
            mReader = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888,
                    2 /* maxImages */);
            mVirtualDisplay = context.getSystemService(DisplayManager.class).createVirtualDisplay(
                    "CtsDisplay", WIDTH, HEIGHT, DENSITY, mReader.getSurface(),
                    VIRTUAL_DISPLAY_FLAG_PUBLIC | VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY);
            return mVirtualDisplay.getDisplay();
        }

        @Override
        public void close() {
            if (mVirtualDisplay != null) {
                mVirtualDisplay.release();
            }
            if (mReader != null) {
                mReader.close();
            }
        }
    }
}
