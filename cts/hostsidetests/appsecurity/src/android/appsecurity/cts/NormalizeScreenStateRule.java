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

package android.appsecurity.cts;

import static org.junit.Assert.fail;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.ITestInformationReceiver;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;

/**
 * {@link TestRule} class that disables screen saver and doze settings before each test method
 * running and restoring to initial values after test method finished.
 */
public class NormalizeScreenStateRule implements TestRule {
    private static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";

    private static final String SETTING_NAMESPACE = "secure";

    private final ITestInformationReceiver mTestInformationReceiver;

    public NormalizeScreenStateRule(ITestInformationReceiver testInformationReceiver) {
        mTestInformationReceiver = testInformationReceiver;
    }

    /** Doze items copied from ActivityManagerTestBase since the keys are hidden. */
    private static final String[] DOZE_SETTINGS = {
            "doze_enabled",
            "doze_always_on",
            "doze_pulse_on_pick_up",
            "doze_pulse_on_long_press",
            "doze_pulse_on_double_tap",
            "doze_wake_screen_gesture",
            "doze_wake_display_gesture",
            "doze_tap_gesture",
            "screensaver_enabled",
    };

    private ITestDevice getDevice() {
        return mTestInformationReceiver.getTestInformation().getDevice();
    }

    private String getSecureSetting(String key) {
        try {
            return getSecureSettingInternal(key);
        } catch (DeviceNotAvailableException e) {
            fail("Get setting failed, could not re-connect to the device: "+ e.getMessage());
            return null;
        }
    }
    private String getSecureSettingInternal(String key) throws DeviceNotAvailableException {
        try {
            return getDevice().getSetting(SETTING_NAMESPACE, key);
        } catch (DeviceNotAvailableException exp) {
            // Retry once to recover the device.
            if (getDevice().waitForDeviceAvailable()) {
                return getDevice().getSetting(SETTING_NAMESPACE, key);
            }
            throw exp;
        }
    }

    private void putSecureSetting(String key, String value) {
        try {
            putSecureStringInternal(key, value);
        } catch(DeviceNotAvailableException e) {
            fail("Put setting failed, could not reconnect to the device: " + e.getMessage());
        }
    }

    private void putSecureStringInternal(String key, String value)
            throws DeviceNotAvailableException {
        try {
            getDevice().setSetting(SETTING_NAMESPACE, key, value);
        } catch (DeviceNotAvailableException exp) {
            // Retry once to recover the device.
            if (getDevice().waitForDeviceAvailable()) {
                getDevice().setSetting(SETTING_NAMESPACE, key, value);
                return;
            }
            throw exp;
        }
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                if (getDevice().hasFeature(FEATURE_AUTOMOTIVE)) {
                    return;
                }
                final Map<String, String> initialValues = new HashMap<>();
                Arrays.stream(DOZE_SETTINGS).forEach(
                        k -> initialValues.put(k, getSecureSetting(k)));
                try {
                    Arrays.stream(DOZE_SETTINGS).forEach(k -> putSecureSetting(k, "0"));
                    base.evaluate();
                } finally {
                    Arrays.stream(DOZE_SETTINGS).forEach(
                            k -> putSecureSetting(k, initialValues.get(k)));
                }
            }
        };
    }
}
