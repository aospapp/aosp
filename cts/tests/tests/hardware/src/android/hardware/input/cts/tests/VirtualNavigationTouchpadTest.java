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

package android.hardware.input.cts.tests;

import static android.view.Display.DEFAULT_DISPLAY;

import static org.junit.Assert.assertThrows;

import android.hardware.display.VirtualDisplay;
import android.hardware.input.VirtualNavigationTouchpad;
import android.hardware.input.VirtualNavigationTouchpadConfig;
import android.hardware.input.VirtualTouchEvent;
import android.os.SystemClock;
import android.platform.test.annotations.FlakyTest;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VirtualNavigationTouchpadTest extends VirtualDeviceTestCase {

    private static final String DEVICE_NAME = "CtsVirtualNavigationTouchpadTestDevice";
    private static final int TOUCHPAD_HEIGHT = 50;
    private static final int TOUCHPAD_WIDTH = 50;

    private VirtualNavigationTouchpad mVirtualNavigationTouchpad;

    @Override
    void onSetUpVirtualInputDevice() {
        mVirtualNavigationTouchpad = createVirtualNavigationTouchpad(
                mVirtualDisplay.getDisplay().getDisplayId());
    }

    VirtualNavigationTouchpad createVirtualNavigationTouchpad(int displayId) {
        final VirtualNavigationTouchpadConfig navigationTouchpadConfig =
                new VirtualNavigationTouchpadConfig.Builder(TOUCHPAD_WIDTH, TOUCHPAD_HEIGHT)
                        .setVendorId(VENDOR_ID)
                        .setProductId(PRODUCT_ID)
                        .setInputDeviceName(DEVICE_NAME)
                        .setAssociatedDisplayId(displayId)
                        .build();

        return mVirtualDevice.createVirtualNavigationTouchpad(
                navigationTouchpadConfig);
    }

    @Override
    void onTearDownVirtualInputDevice() {
        if (mVirtualNavigationTouchpad != null) {
            mVirtualNavigationTouchpad.close();
        }
    }

    @Test
    public void sendTouchEvent() {
        final float inputSize = 1f;
        final float x = 30f;
        final float y = 30f;
        mVirtualNavigationTouchpad.sendTouchEvent(new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setPointerId(1)
                .setX(x)
                .setY(y)
                .setPressure(255f)
                .setMajorAxisSize(inputSize)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .build());
        mVirtualNavigationTouchpad.sendTouchEvent(new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_UP)
                .setPointerId(1)
                .setX(x)
                .setY(y)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .build());
        // Convert the input axis size to its equivalent fraction of the total touchpad size.
        final float computedSize = inputSize / (TOUCHPAD_WIDTH - 1f);

        verifyEvents(Arrays.asList(
                createMotionEvent(MotionEvent.ACTION_DOWN, x, y,
                        /* pressure= */ 1f, /* size= */ computedSize, /* axisSize= */ inputSize),
                createMotionEvent(MotionEvent.ACTION_UP, x, y,
                        /* pressure= */ 1f, /* size= */ computedSize, /* axisSize= */ inputSize)));
    }

    @Test
    public void sendTouchEvent_withoutCreateVirtualDevicePermission_throwsException() {
        final float inputSize = 1f;
        final float x = 30f;
        final float y = 30f;
        try (DropShellPermissionsTemporarily ignored = new DropShellPermissionsTemporarily()) {
            assertThrows(SecurityException.class,
                    () ->
                            mVirtualNavigationTouchpad.sendTouchEvent(
                                    new VirtualTouchEvent.Builder()
                                            .setAction(VirtualTouchEvent.ACTION_DOWN)
                                            .setPointerId(1)
                                            .setX(x)
                                            .setY(y)
                                            .setPressure(255f)
                                            .setMajorAxisSize(inputSize)
                                            .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                                            .build()));
        }
    }

    @Test
    public void createVirtualNavigationTouchpad_defaultDisplay_throwsException() {
        assertThrows(SecurityException.class,
                () -> createVirtualNavigationTouchpad(DEFAULT_DISPLAY));
    }

    @Test
    public void createVirtualNavigationTouchpad_unownedDisplay_throwsException() {
        VirtualDisplay unownedDisplay = createUnownedVirtualDisplay();
        assertThrows(SecurityException.class,
                () -> createVirtualNavigationTouchpad(unownedDisplay.getDisplay().getDisplayId()));
        unownedDisplay.release();
    }

    @Test
    public void sendTap_motionEventNotConsumed_getsConvertedToDpadCenter() {
        setConsumeGenericMotionEvents(false);

        final float x = 30f;
        final float y = 30f;
        mVirtualNavigationTouchpad.sendTouchEvent(new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setPointerId(1)
                .setX(x)
                .setY(y)
                .setPressure(255f)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .build());
        mVirtualNavigationTouchpad.sendTouchEvent(new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_UP)
                .setPointerId(1)
                .setX(x)
                .setY(y)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .build());

        verifyEvents(Arrays.asList(
                createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER),
                createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER)));
    }

    @FlakyTest(detail = "The test does not reliably simulate a fling action, only way to reliably"
            + "do it is when uinput supports custom timestamps for virtual input events.",
            bugId = 277040837)
    @Test
    public void sendFlingUp_motionEventNotConsumed_getsConvertedToDpadUp() {
        setConsumeGenericMotionEvents(false);

        sendFlingEvents(/* startX= */ 30f, /* startY= */ 30f, /* diffX= */ -10f, /* diffY= */ -30f);

        verifyEvents(Arrays.asList(
                        createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP),
                        createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_UP)));
    }

    @FlakyTest(detail = "The test does not reliably simulate a fling action, only way to reliably"
            + "do it is when uinput supports custom timestamps for virtual input events.",
            bugId = 277040837)
    @Test
    public void sendFlingDown_motionEventNotConsumed_getsConvertedToDpadDown() {
        setConsumeGenericMotionEvents(false);

        sendFlingEvents(/* startX= */ 30f, /* startY= */ 30f, /* diffX= */ 10f, /* diffY= */ 30f);

        verifyEvents(Arrays.asList(
                        createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN),
                        createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_DOWN)));
    }

    @FlakyTest(detail = "The test does not reliably simulate a fling action, only way to reliably"
            + "do it is when uinput supports custom timestamps for virtual input events.",
            bugId = 277040837)
    @Test
    public void sendFlingRight_motionEventNotConsumed_getsConvertedToDpadRight() {
        setConsumeGenericMotionEvents(false);

        sendFlingEvents(/* startX= */ 40f, /* startY= */ 40f, /* diffX= */ 30f, /* diffY= */ 10f);

        verifyEvents(Arrays.asList(
                        createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT),
                        createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT)));
    }

    @FlakyTest(detail = "The test does not reliably simulate a fling action, only way to reliably"
            + "do it is when uinput supports custom timestamps for virtual input events.",
            bugId = 277040837)
    @Test
    public void sendFlingLeft_motionEventNotConsumed_getsConvertedToDpadLeft() {
        setConsumeGenericMotionEvents(false);

        sendFlingEvents(/* startX= */ 30f, /* startY= */ 30f, /* diffX= */ -30f, /* diffY= */ 10f);

        verifyEvents(Arrays.asList(
                        createKeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT),
                        createKeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT)));
    }

    @Test
    public void sendLongPress_motionEventNotConsumed_getsIgnored() {
        setConsumeGenericMotionEvents(false);

        float x = 30f;
        float y = 30f;
        mVirtualNavigationTouchpad.sendTouchEvent(new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setPointerId(1)
                .setX(x)
                .setY(y)
                .setPressure(255f)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .build());
        // TODO(b/277040837): Use custom timestamps for virtual input events instead of sleep.
        SystemClock.sleep(600);
        mVirtualNavigationTouchpad.sendTouchEvent(new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_UP)
                .setPointerId(1)
                .setX(x)
                .setY(y)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .build());

        verifyNoKeyEvents();
    }

    @Test
    public void sendSlowScroll_motionEventNotConsumed_getsIgnored() {
        setConsumeGenericMotionEvents(false);

        sendContinuousEvents(/* startX= */ 30f, /* startY= */ 30f, /* diffX= */ 2f, /* diffY= */ 1f,
                /* eventTimeGapMs= */ 300);

        verifyNoKeyEvents();
    }

    private void sendFlingEvents(float startX, float startY, float diffX, float diffY) {
        sendContinuousEvents(startX, startY, diffX, diffY, /* eventTimeGapMs= */ 7);
    }

    private void sendContinuousEvents(float startX, float startY, float diffX, float diffY,
            long eventTimeGapMs) {
        int eventCount = 4;
        // Starts with ACTION_DOWN.
        mVirtualNavigationTouchpad.sendTouchEvent(new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_DOWN)
                .setPointerId(1)
                .setX(startX)
                .setY(startY)
                .setPressure(255f)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .build());
        SystemClock.sleep(eventTimeGapMs);

        for (int i = 1; i <= eventCount; i++) {
            mVirtualNavigationTouchpad.sendTouchEvent(new VirtualTouchEvent.Builder()
                    .setAction(VirtualTouchEvent.ACTION_MOVE)
                    .setPointerId(1)
                    .setX(startX + i * diffX / eventCount)
                    .setY(startY + i * diffY / eventCount)
                    .setPressure(255f)
                    .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                    .build());
            SystemClock.sleep(eventTimeGapMs);
        }

        // Ends with ACTION_UP.
        mVirtualNavigationTouchpad.sendTouchEvent(new VirtualTouchEvent.Builder()
                .setAction(VirtualTouchEvent.ACTION_UP)
                .setPointerId(1)
                .setX(startX + diffX)
                .setY(startY + diffY)
                .setToolType(VirtualTouchEvent.TOOL_TYPE_FINGER)
                .build());
    }

    private MotionEvent createMotionEvent(int action, float x, float y, float pressure, float size,
            float axisSize) {
        final MotionEvent.PointerProperties pointerProperties = new MotionEvent.PointerProperties();
        pointerProperties.toolType = MotionEvent.TOOL_TYPE_FINGER;
        final MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
        pointerCoords.setAxisValue(MotionEvent.AXIS_X, x);
        pointerCoords.setAxisValue(MotionEvent.AXIS_Y, y);
        pointerCoords.setAxisValue(MotionEvent.AXIS_PRESSURE, pressure);
        pointerCoords.setAxisValue(MotionEvent.AXIS_SIZE, size);
        pointerCoords.setAxisValue(MotionEvent.AXIS_TOUCH_MAJOR, axisSize);
        pointerCoords.setAxisValue(MotionEvent.AXIS_TOUCH_MINOR, axisSize);
        pointerCoords.setAxisValue(MotionEvent.AXIS_TOOL_MAJOR, axisSize);
        pointerCoords.setAxisValue(MotionEvent.AXIS_TOOL_MINOR, axisSize);
        MotionEvent event = MotionEvent.obtain(
                /* downTime= */ 0,
                /* eventTime= */ 0,
                action,
                /* pointerCount= */ 1,
                new MotionEvent.PointerProperties[]{pointerProperties},
                new MotionEvent.PointerCoords[]{pointerCoords},
                /* metaState= */ 0,
                /* buttonState= */ 0,
                /* xPrecision= */ 1f,
                /* yPrecision= */ 1f,
                /* deviceId= */ 0,
                /* edgeFlags= */ 0,
                InputDevice.SOURCE_TOUCH_NAVIGATION,
                /* flags= */ 0);
        return event;
    }

    private KeyEvent createKeyEvent(int action, int code) {
        KeyEvent event = new KeyEvent(action, code);
        event.setSource(InputDevice.SOURCE_TOUCH_NAVIGATION);
        event.setDisplayId(mVirtualDisplay.getDisplay().getDisplayId());
        return event;
    }
}
