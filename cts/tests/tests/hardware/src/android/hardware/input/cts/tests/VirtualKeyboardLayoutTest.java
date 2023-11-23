/*
 * Copyright 2023 The Android Open Source Project
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

import static org.junit.Assert.assertEquals;

import android.hardware.input.InputManager;
import android.hardware.input.VirtualKeyboard;
import android.hardware.input.VirtualKeyboardConfig;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.InputDevice;
import android.view.KeyEvent;

import androidx.test.filters.SmallTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.PollingCheck;

import org.junit.Test;
import org.junit.runner.RunWith;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class VirtualKeyboardLayoutTest extends VirtualDeviceTestCase {
    private static final String DEVICE_NAME = "CtsVirtualKeyboardTestDevice";
    private InputDevice mVirtualInputDevice;
    private InputManager.InputDeviceListener mInputDeviceListener;

    @Override
    void onSetUp() {
        Settings.Global.putString(
                mInstrumentation.getTargetContext().getContentResolver(),
                "settings_new_keyboard_ui", "true");
        mInputManager = mInstrumentation.getTargetContext().getSystemService(InputManager.class);
        mInputDeviceListener = createInputDeviceListener();
        mInputManager.registerInputDeviceListener(
                mInputDeviceListener, new Handler(Looper.getMainLooper()));
        // Tap to gain window focus on the activity
        tapActivityToFocus();
    }

    @Override
    void onSetUpVirtualInputDevice() {
        // Do nothing
    }

    @Override
    void onTearDownVirtualInputDevice() {
        // Do nothing
    }

    @Override
    void onTearDown() {
        Settings.Global.putString(mInstrumentation.getTargetContext().getContentResolver(),
                "settings_new_keyboard_ui",
                "");
        if (mInputManager != null) {
            mInputManager.unregisterInputDeviceListener(mInputDeviceListener);
        }
        super.onTearDown();
    }

    VirtualKeyboard createVirtualKeyboard(String languageTag, String layoutType) {
        final VirtualKeyboardConfig keyboardConfig =
                new VirtualKeyboardConfig.Builder()
                        .setVendorId(VENDOR_ID)
                        .setProductId(PRODUCT_ID)
                        .setInputDeviceName(DEVICE_NAME)
                        .setAssociatedDisplayId(mVirtualDisplay.getDisplay().getDisplayId())
                        .setLanguageTag(languageTag)
                        .setLayoutType(layoutType)
                        .build();
        return mVirtualDevice.createVirtualKeyboard(keyboardConfig);
    }

    @Test
    public void createVirtualKeyboard_layoutSelected() {
        // Creates a virtual keyboard with french layout
        try (VirtualKeyboard virtualKeyboard = createVirtualKeyboard("fr-Latn-FR", "azerty")) {
            PollingCheck.waitFor(
                    () -> isCorrectlyConfigured(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_A),
                    "Waiting for French keyboard to be configured correctly took too long");
            assertKeyMappings(
                    new int[]{
                            KeyEvent.KEYCODE_Q,
                            KeyEvent.KEYCODE_W,
                            KeyEvent.KEYCODE_E,
                            KeyEvent.KEYCODE_R,
                            KeyEvent.KEYCODE_T,
                            KeyEvent.KEYCODE_Y
                    },
                    new int[]{
                            KeyEvent.KEYCODE_A,
                            KeyEvent.KEYCODE_Z,
                            KeyEvent.KEYCODE_E,
                            KeyEvent.KEYCODE_R,
                            KeyEvent.KEYCODE_T,
                            KeyEvent.KEYCODE_Y
                    },
                    "french");
        }

        // Creates a Virtual keyboard with Swiss german layout
        try (VirtualKeyboard virtualKeyboard = createVirtualKeyboard("de-CH", "qwertz")) {
            PollingCheck.waitFor(
                    () -> isCorrectlyConfigured(KeyEvent.KEYCODE_Y, KeyEvent.KEYCODE_Z),
                    "Waiting for Swiss German keyboard to be configured correctly took too long");
            assertKeyMappings(
                    new int[]{
                            KeyEvent.KEYCODE_Q,
                            KeyEvent.KEYCODE_W,
                            KeyEvent.KEYCODE_E,
                            KeyEvent.KEYCODE_R,
                            KeyEvent.KEYCODE_T,
                            KeyEvent.KEYCODE_Y
                    },
                    new int[]{
                            KeyEvent.KEYCODE_Q,
                            KeyEvent.KEYCODE_W,
                            KeyEvent.KEYCODE_E,
                            KeyEvent.KEYCODE_R,
                            KeyEvent.KEYCODE_T,
                            KeyEvent.KEYCODE_Z
                    },
                    "swiss german");
        }
    }

    @Test
    public void createVirtualKeyboard_layoutSelected_differentLayoutType() {
        // Creates a virtual keyboard with English(QWERTY) layout
        try (VirtualKeyboard virtualKeyboard = createVirtualKeyboard("en-Latn-US", "qwerty")) {
            PollingCheck.waitFor(
                    () -> isCorrectlyConfigured(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_Q),
                    "Waiting for English(QWERTY) keyboard to be configured correctly took too "
                            + "long");
            assertKeyMappings(
                    new int[]{
                            KeyEvent.KEYCODE_Q,
                            KeyEvent.KEYCODE_W,
                            KeyEvent.KEYCODE_E,
                            KeyEvent.KEYCODE_R,
                            KeyEvent.KEYCODE_T,
                            KeyEvent.KEYCODE_Y
                    },
                    new int[]{
                            KeyEvent.KEYCODE_Q,
                            KeyEvent.KEYCODE_W,
                            KeyEvent.KEYCODE_E,
                            KeyEvent.KEYCODE_R,
                            KeyEvent.KEYCODE_T,
                            KeyEvent.KEYCODE_Y
                    },
                    "English(QWERTY)");
        }

        // Creates a Virtual keyboard with English(Dvorak) layout
        try (VirtualKeyboard virtualKeyboard = createVirtualKeyboard("en-Latn-US", "dvorak")) {
            PollingCheck.waitFor(
                    () -> isCorrectlyConfigured(KeyEvent.KEYCODE_Q, KeyEvent.KEYCODE_APOSTROPHE),
                    "Waiting for English(Dvorak) keyboard to be configured correctly took too "
                            + "long");
            assertKeyMappings(
                    new int[]{
                            KeyEvent.KEYCODE_Q,
                            KeyEvent.KEYCODE_W,
                            KeyEvent.KEYCODE_E,
                            KeyEvent.KEYCODE_R,
                            KeyEvent.KEYCODE_T,
                            KeyEvent.KEYCODE_Y
                    },
                    new int[]{
                            KeyEvent.KEYCODE_APOSTROPHE,
                            KeyEvent.KEYCODE_COMMA,
                            KeyEvent.KEYCODE_E,
                            KeyEvent.KEYCODE_P,
                            KeyEvent.KEYCODE_Y,
                            KeyEvent.KEYCODE_F
                    },
                    "English(Dvorak)");
        }
    }

    private void assertKeyMappings(int[] fromKeys, int[] toKeys, String layoutName) {
        for (int i = 0; i < fromKeys.length; i++) {
            assertEquals(
                    "Key location "
                            + KeyEvent.keyCodeToString(fromKeys[i])
                            + " should map to "
                            + KeyEvent.keyCodeToString(toKeys[i])
                            + " on a "
                            + layoutName
                            + " layout.",
                    mVirtualInputDevice.getKeyCodeForKeyLocation(fromKeys[i]),
                    toKeys[i]);
        }
    }

    private boolean isCorrectlyConfigured(int fromKeyCode, int toKeyCode) {
        return mVirtualInputDevice != null
                && mVirtualInputDevice.getKeyCodeForKeyLocation(fromKeyCode) == toKeyCode;
    }

    private InputManager.InputDeviceListener createInputDeviceListener() {
        return new InputManager.InputDeviceListener() {
            @Override
            public void onInputDeviceAdded(int deviceId) {
                updateVirtualInputDevice(deviceId);
            }

            @Override
            public void onInputDeviceRemoved(int deviceId) {
                if (mVirtualInputDevice.getId() == deviceId) {
                    mVirtualInputDevice = null;
                }
            }

            @Override
            public void onInputDeviceChanged(int deviceId) {
                updateVirtualInputDevice(deviceId);
            }
        };
    }

    private void updateVirtualInputDevice(int deviceId) {
        InputDevice device = mInputManager.getInputDevice(deviceId);
        if (device != null
                && device.getProductId() == PRODUCT_ID
                && device.getVendorId() == VENDOR_ID) {
            mVirtualInputDevice = device;
        }
    }
}
