/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.view.cts;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.fail;

import android.os.StrictMode;
import android.os.SystemClock;
import android.util.Log;
import android.view.InputDevice;
import android.view.MotionEvent;
import android.view.VelocityTracker;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Set;
import java.util.function.Supplier;

/**
 * Test {@link VelocityTracker}.
 */
@SmallTest
@RunWith(AndroidJUnit4.class)
public class VelocityTrackerTest {
    private static final String TAG = "VelocityTrackerTest";

    // To enable these logs, run:
    // 'adb shell setprop log.tag.VelocityTrackerTest DEBUG' (requires restart)
    private static final boolean DEBUG = Log.isLoggable(TAG, Log.DEBUG);

    private static final float TOLERANCE_EXACT = 0.01f;
    private static final float TOLERANCE_TIGHT = 0.05f;
    private static final float TOLERANCE_WEAK = 0.1f;
    private static final float TOLERANCE_VERY_WEAK = 0.2f;

    private VelocityTracker mPlanarVelocityTracker;
    private VelocityTracker mScrollVelocityTracker;

    // Current axis value, velocity and acceleration.
    private long mTime;
    private long mLastTime;
    private float mPx, mPy, mScroll;
    private float mVx, mVy, mVscroll;
    private float mAx, mAy, mAscroll;

    private final StrictMode.ThreadPolicy mOldThreadPolicy = StrictMode.getThreadPolicy();
    private final StrictMode.VmPolicy mOldVmPolicy = StrictMode.getVmPolicy();

    @Before
    public void setup() {
        mPlanarVelocityTracker = VelocityTracker.obtain();
        mScrollVelocityTracker = VelocityTracker.obtain();

        mTime = 1000;
        mLastTime = 0;
        mPx = 300;
        mPy = 600;
        mVx = 0;
        mVy = 0;
        mAx = 0;
        mAy = 0;
        StrictMode.setThreadPolicy(new StrictMode.ThreadPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .build());
        StrictMode.setVmPolicy(new StrictMode.VmPolicy.Builder()
                .detectAll()
                .penaltyLog()
                .penaltyDeath()
                .build());
    }

    @After
    public void teardown() {
        mPlanarVelocityTracker.recycle();
        mScrollVelocityTracker.recycle();
        StrictMode.setThreadPolicy(mOldThreadPolicy);
        StrictMode.setVmPolicy(mOldVmPolicy);
    }

    @Test
    public void testNoMovement() {
        move(100, 10);
        assertVelocity(TOLERANCE_EXACT, "Expect exact bound when no movement occurs.");
    }

    @Test
    public void testLinearMovement() {
        mVx = 2.0f;
        mVy = -4.0f;
        mVscroll = 3.0f;
        move(100, 10);
        assertVelocity(TOLERANCE_TIGHT, "Expect tight bound for linear motion.");
    }

    @Test
    public void testAcceleratingMovement() {
        // A very good velocity tracking algorithm will produce a tight bound on
        // simple acceleration.  Certain alternate algorithms will fare less well but
        // may be more stable in the presence of bad input data.
        mVx = 2.0f;
        mVy = -4.0f;
        mVscroll = 3.0f;
        mAx = 1.0f;
        mAy = -0.5f;
        mAscroll = 2.0f;
        move(200, 10);
        assertVelocity(TOLERANCE_WEAK, "Expect weak bound when there is acceleration.");
    }

    @Test
    public void testDeceleratingMovement() {
        // A very good velocity tracking algorithm will produce a tight bound on
        // simple acceleration.  Certain alternate algorithms will fare less well but
        // may be more stable in the presence of bad input data.
        mVx = 2.0f;
        mVy = -4.0f;
        mVscroll = 3.0f;
        mAx = -1.0f;
        mAy = 0.2f;
        mAscroll = -0.5f;
        move(200, 10);
        assertVelocity(TOLERANCE_WEAK, "Expect weak bound when there is deceleration.");
    }

    @Test
    public void testLinearSharpDirectionChange() {
        // After a sharp change of direction we expect the velocity to eventually
        // converge but it might take a moment to get there.
        mVx = 2.0f;
        mVy = -4.0f;
        mVscroll = 3.0f;
        move(100, 10);
        assertVelocity(TOLERANCE_TIGHT, "Expect tight bound for linear motion.");
        mVx = -1.0f;
        mVy = -3.0f;
        mVscroll = -2.0f;
        move(100, 10);
        assertVelocity(TOLERANCE_WEAK, "Expect weak bound after 100ms of new direction.");
        move(100, 10);
        assertVelocity(TOLERANCE_TIGHT, "Expect tight bound after 200ms of new direction.");
    }

    @Test
    public void testLinearSharpDirectionChangeAfterALongPause() {
        // Should be able to get a tighter bound if there is a pause before the
        // change of direction.
        mVx = 2.0f;
        mVy = -4.0f;
        mVscroll = 3.0f;
        move(100, 10);
        assertVelocity(TOLERANCE_TIGHT, "Expect tight bound for linear motion.");
        pause(100);
        mVx = -1.0f;
        mVy = -3.0f;
        mVscroll = -2.0f;
        move(100, 10);
        assertVelocity(TOLERANCE_TIGHT,
                "Expect tight bound after a 100ms pause and 100ms of new direction.");
    }

    @Test
    public void testChangingAcceleration() {
        // In real circumstances, the acceleration changes continuously throughout a
        // gesture.  Try to model this and see how the algorithm copes.
        mVx = 2.0f;
        mVy = -4.0f;
        mVscroll = -2.0f;
        for (float change : new float[] { 1, -2, -3, -1, 1 }) {
            mAx += 1.0f * change;
            mAy += -0.5f * change;
            mAscroll += 2.0f * change;
            move(30, 10);
        }
        assertVelocity(TOLERANCE_VERY_WEAK,
                "Expect weak bound when there is changing acceleration.");
    }

    @Test
    public void testUsesRawCoordinates() {
        VelocityTracker vt = VelocityTracker.obtain();
        final int numevents = 5;

        final long downTime = SystemClock.uptimeMillis();
        for (int i = 0; i < numevents; i++) {
            final long eventTime = downTime + i * 10;
            int action = i == 0 ? MotionEvent.ACTION_DOWN : MotionEvent.ACTION_MOVE;
            MotionEvent event = MotionEvent.obtain(downTime, eventTime, action, 0, 0, 0);
            // MotionEvent translation/offset is only applied to pointer sources, like touchscreens.
            event.setSource(InputDevice.SOURCE_TOUCHSCREEN);
            event.offsetLocation(i * 10, i * 10);
            vt.addMovement(event);
        }
        vt.computeCurrentVelocity(1000);
        float xVelocity = vt.getXVelocity();
        float yVelocity = vt.getYVelocity();
        if (xVelocity == 0 || yVelocity == 0) {
            fail("VelocityTracker is using raw coordinates,"
                    + " but it should be using adjusted coordinates");
        }
    }

    @Test
    public void testIsAxisSupported() {
        Set<Integer> expectedSupportedAxes =
                Set.of(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_SCROLL);
        VelocityTracker vt = VelocityTracker.obtain();
        // Note that we are testing up to the max possible axis value, plus 3 more values. We are
        // going beyond the max value to add a bit more protection. "3" is chosen arbitrarily to
        // cover a few more values beyond the max.
        for (int axis = 0; axis <= getLargestDefinedMotionEventAxisValue() + 3; axis++) {
            boolean expectedSupport = expectedSupportedAxes.contains(axis);
            if (vt.isAxisSupported(axis) != expectedSupport) {
                fail(String.format(
                        "Unexpected support found for axis %d (expected support=%b)",
                        axis, expectedSupport));
            }
        }
    }

    @Test
    public void testVelocityCallsWithUnusedPointers() {
        mVx = 2.0f;
        mVy = -4.0f;
        mVscroll = 3.0f;

        move(100, 10);

        assertThat(mPlanarVelocityTracker.getXVelocity(1)).isZero();
        assertThat(mPlanarVelocityTracker.getYVelocity(2)).isZero();
        assertThat(mScrollVelocityTracker.getAxisVelocity(MotionEvent.AXIS_SCROLL, 100)).isZero();
    }

    @Test
    public void testVelocityCallsForUnsupportedAxis() {
        assertThat(mPlanarVelocityTracker.getAxisVelocity(MotionEvent.AXIS_DISTANCE)).isZero();
        assertThat(mPlanarVelocityTracker.getAxisVelocity(MotionEvent.AXIS_GENERIC_10)).isZero();
    }

    private void move(long duration, long step) {
        addMovement();
        while (duration > 0) {
            duration -= step;
            mTime += step;

            mPx += (mAx / 2 * step + mVx) * step;
            mPy += (mAy / 2 * step + mVy) * step;
            // Note that we are not incrementing the scroll-value. Instead, we are overriding the
            // previous value with the new one. This is in accordance to the differential nature of
            // the scroll axis. That is, the axis reports differential values since previous motion
            // events, instead of absolute values.
            mScroll = (mAscroll / 2 * step + mVscroll) * step;

            mVx += mAx * step;
            mVy += mAy * step;
            mVscroll += mAscroll * step;
            addMovement();
        }
    }

    private void pause(long duration) {
        mTime += duration;
    }

    private MotionEvent createScrollMotionEvent() {
        MotionEvent.PointerProperties props = new MotionEvent.PointerProperties();
        props.id = 0;

        MotionEvent.PointerCoords coords = new MotionEvent.PointerCoords();
        coords.setAxisValue(MotionEvent.AXIS_SCROLL, mScroll);

        return MotionEvent.obtain(0 /* downTime */,
                mTime,
                MotionEvent.ACTION_SCROLL,
                1 /* pointerCount */,
                new MotionEvent.PointerProperties[] {props},
                new MotionEvent.PointerCoords[] {coords},
                0 /* metaState */,
                0 /* buttonState */,
                0 /* xPrecision */,
                0 /* yPrecision */,
                1 /* deviceId */,
                0 /* edgeFlags */,
                InputDevice.SOURCE_ROTARY_ENCODER,
                0 /* flags */);
    }

    private void addMovement() {
        if (mTime > mLastTime) {
            MotionEvent ev = MotionEvent.obtain(0L, mTime, MotionEvent.ACTION_MOVE, mPx, mPy, 0);
            mPlanarVelocityTracker.addMovement(ev);
            ev.recycle();

            ev = createScrollMotionEvent();
            mScrollVelocityTracker.addMovement(ev);
            ev.recycle();

            mLastTime = mTime;

            mPlanarVelocityTracker.computeCurrentVelocity(1);
            mScrollVelocityTracker.computeCurrentVelocity(1);

            final float estimatedVx = mPlanarVelocityTracker.getXVelocity();
            final float estimatedVy = mPlanarVelocityTracker.getYVelocity();
            final float estimatedVscroll =
                    mPlanarVelocityTracker.getAxisVelocity(MotionEvent.AXIS_SCROLL);

            if (DEBUG) {
                logTrackingInfo(MotionEvent.AXIS_X, mTime, mPx, mVx, estimatedVx, mAx);
                logTrackingInfo(MotionEvent.AXIS_Y, mTime, mPy, mVy, estimatedVy, mAy);
                logTrackingInfo(MotionEvent.AXIS_SCROLL,
                        mTime, mScroll, mVscroll, estimatedVscroll, mAscroll);
            }
        }
    }

    private void assertVelocity(float tolerance, String message) {
        mPlanarVelocityTracker.computeCurrentVelocity(1);
        mScrollVelocityTracker.computeCurrentVelocity(1);

        assertVelocity(mPlanarVelocityTracker::getXVelocity, tolerance, mVx, message);
        assertVelocity(mPlanarVelocityTracker, MotionEvent.AXIS_X, tolerance, mVx, message);

        assertVelocity(mPlanarVelocityTracker::getYVelocity, tolerance, mVy, message);
        assertVelocity(mPlanarVelocityTracker, MotionEvent.AXIS_Y, tolerance, mVy, message);

        assertVelocity(
                mScrollVelocityTracker, MotionEvent.AXIS_SCROLL, tolerance, mVscroll, message);
    }

    private static void assertVelocity(
            VelocityTracker velocityTracker,
            int axis,
            float tolerance,
            float expectedVelocity,
            String message) {
        assertVelocity(
                () -> velocityTracker.getAxisVelocity(axis), tolerance, expectedVelocity, message);
    }

    private static void assertVelocity(
            Supplier<Float> estimatedVelocitySupplier,
            float tolerance,
            float expectedVelocity,
            String message) {
        final float estimatedVelociy = estimatedVelocitySupplier.get();
        float error = error(expectedVelocity, estimatedVelociy);
        if (error > tolerance) {
            fail(String.format("Velocity exceeds tolerance of %6.1f%%: "
                    + "expected=%6.1f. "
                    + "actual=%6.1f (%6.1f%%). %s",
                    tolerance * 100f, expectedVelocity, estimatedVelociy, error * 100f, message));
        }
    }

    private static float error(float expected, float actual) {
        float absError = Math.abs(actual - expected);
        if (absError < 0.001f) {
            return 0;
        }
        if (Math.abs(expected) < 0.001f) {
            return 1;
        }
        return absError / Math.abs(expected);
    }

    private static void logTrackingInfo(
            int axis, long time, float val, float actualV, float estimatedV, float acc) {
        Log.d(TAG, String.format(
                "[%d] %s: val=%6.1f, v=%6.1f, acc=%6.1f, estimatedV=%6.1f (%6.1f%%)",
                time, MotionEvent.axisToString(axis), val, actualV, acc,
                estimatedV, error(actualV, estimatedV) * 100f));
    }

    private static int getLargestDefinedMotionEventAxisValue() {
        int i = 0;
        while (!Integer.toString(i).equals(MotionEvent.axisToString(i))) {
            i++;
        }
        return i - 1;
    }
}
