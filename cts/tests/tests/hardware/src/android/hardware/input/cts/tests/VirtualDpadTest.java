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
import android.hardware.input.VirtualDpad;
import android.hardware.input.VirtualDpadConfig;
import android.hardware.input.VirtualKeyEvent;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Arrays;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VirtualDpadTest extends VirtualDeviceTestCase {

    private static final String DEVICE_NAME = "CtsVirtualDpadTestDevice";
    private VirtualDpad mVirtualDpad;

    @Override
    void onSetUpVirtualInputDevice() {
        mVirtualDpad = createVirtualDpad(mVirtualDisplay.getDisplay().getDisplayId());
    }

    VirtualDpad createVirtualDpad(int displayId) {
        final VirtualDpadConfig dpadConfig =
                new VirtualDpadConfig.Builder()
                        .setVendorId(VENDOR_ID)
                        .setProductId(PRODUCT_ID)
                        .setInputDeviceName(DEVICE_NAME)
                        .setAssociatedDisplayId(displayId)
                        .build();
        return mVirtualDevice.createVirtualDpad(dpadConfig);
    }

    @Override
    void onTearDownVirtualInputDevice() {
        if (mVirtualDpad != null) {
            mVirtualDpad.close();
        }
    }

    @Test
    public void sendKeyEvent() {
        mVirtualDpad.sendKeyEvent(
                new VirtualKeyEvent.Builder()
                        .setKeyCode(KeyEvent.KEYCODE_DPAD_UP)
                        .setAction(VirtualKeyEvent.ACTION_DOWN)
                        .build());
        mVirtualDpad.sendKeyEvent(
                new VirtualKeyEvent.Builder()
                        .setKeyCode(KeyEvent.KEYCODE_DPAD_UP)
                        .setAction(VirtualKeyEvent.ACTION_UP)
                        .build());
        mVirtualDpad.sendKeyEvent(
                new VirtualKeyEvent.Builder()
                        .setKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
                        .setAction(VirtualKeyEvent.ACTION_DOWN)
                        .build());
        mVirtualDpad.sendKeyEvent(
                new VirtualKeyEvent.Builder()
                        .setKeyCode(KeyEvent.KEYCODE_DPAD_CENTER)
                        .setAction(VirtualKeyEvent.ACTION_UP)
                        .build());
        verifyEvents(
                Arrays.asList(
                        new KeyEvent(
                                /* downTime= */ 0,
                                /* eventTime= */ 0,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_DPAD_UP,
                                /* repeat= */ 0,
                                /* metaState= */ 0,
                                /* deviceId= */ 0,
                                /* scancode= */ 0,
                                /* flags= */ 0,
                                /* source= */ InputDevice.SOURCE_KEYBOARD
                                        | InputDevice.SOURCE_DPAD),
                        new KeyEvent(
                                /* downTime= */ 0,
                                /* eventTime= */ 0,
                                KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_DPAD_UP,
                                /* repeat= */ 0,
                                /* metaState= */ 0,
                                /* deviceId= */ 0,
                                /* scancode= */ 0,
                                /* flags= */ 0,
                                /* source= */ InputDevice.SOURCE_KEYBOARD
                                        | InputDevice.SOURCE_DPAD),
                        new KeyEvent(
                                /* downTime= */ 0,
                                /* eventTime= */ 0,
                                KeyEvent.ACTION_DOWN,
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                /* repeat= */ 0,
                                /* metaState= */ 0,
                                /* deviceId= */ 0,
                                /* scancode= */ 0,
                                /* flags= */ 0,
                                /* source= */ InputDevice.SOURCE_KEYBOARD
                                        | InputDevice.SOURCE_DPAD),
                        new KeyEvent(
                                /* downTime= */ 0,
                                /* eventTime= */ 0,
                                KeyEvent.ACTION_UP,
                                KeyEvent.KEYCODE_DPAD_CENTER,
                                /* repeat= */ 0,
                                /* metaState= */ 0,
                                /* deviceId= */ 0,
                                /* scancode= */ 0,
                                /* flags= */ 0,
                                /* source= */ InputDevice.SOURCE_KEYBOARD
                                        | InputDevice.SOURCE_DPAD)));
    }

    @Test
    public void sendKeyEvent_withoutCreateVirtualDevicePermission_throwsException() {
        try (DropShellPermissionsTemporarily drop = new DropShellPermissionsTemporarily()) {
            assertThrows(SecurityException.class,
                    () -> mVirtualDpad.sendKeyEvent(
                            new VirtualKeyEvent.Builder()
                                    .setKeyCode(KeyEvent.KEYCODE_DPAD_UP)
                                    .setAction(VirtualKeyEvent.ACTION_DOWN)
                                    .build()));
        }
    }

    @Test
    public void rejectsUnsupportedKeyCodes() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mVirtualDpad.sendKeyEvent(
                                new VirtualKeyEvent.Builder()
                                        .setKeyCode(KeyEvent.KEYCODE_Q)
                                        .setAction(VirtualKeyEvent.ACTION_DOWN)
                                        .build()));
    }

    @Test
    public void createVirtualDpad_defaultDisplay_throwsException() {
        assertThrows(SecurityException.class, () -> createVirtualDpad(DEFAULT_DISPLAY));
    }

    @Test
    public void createVirtualDpad_unownedDisplay_throwsException() {
        VirtualDisplay unownedDisplay = createUnownedVirtualDisplay();
        assertThrows(SecurityException.class,
                () -> createVirtualDpad(unownedDisplay.getDisplay().getDisplayId()));
        unownedDisplay.release();
    }
}
