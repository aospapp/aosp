/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.hdmicec.cts.playback;

import static org.junit.Assert.assertEquals;

import android.hdmicec.cts.CecDevice;
import android.hdmicec.cts.HdmiCecClientWrapper;
import android.hdmicec.cts.HdmiCecConstants;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.Test;

import java.util.Scanner;
import java.util.concurrent.TimeUnit;

/** HDMI CEC test to check if the device reports power status correctly (Section 11.2.13) */
@RunWith(DeviceJUnit4ClassRunner.class)
public final class HdmiCecRemoteControlPassThroughTest extends BaseHostJUnit4Test {

    /**
     * The package name of the APK.
     */
    private static final String PACKAGE = "android.hdmicec.app";
    /**
     * The class name of the main activity in the APK.
     */
    private static final String CLASS = "HdmiCecKeyEventCapture";
    /**
     * The command to launch the main activity.
     */
    private static final String START_COMMAND = String.format(
            "am start -W -a android.intent.action.MAIN -n %s/%s.%s", PACKAGE, PACKAGE, CLASS);
    /**
     * The command to clear the main activity.
     */
    private static final String CLEAR_COMMAND = String.format("pm clear %s", PACKAGE);

    private static final int WAIT_TIME = 10;

    @Rule
    public HdmiCecClientWrapper hdmiCecClient =
            new HdmiCecClientWrapper(CecDevice.PLAYBACK_1, this);

    private void lookForLog(String expectedOut) throws Exception {
        ITestDevice device = getDevice();
        TimeUnit.SECONDS.sleep(WAIT_TIME);
        String logs = device.executeAdbCommand("logcat", "-v", "brief", "-d", CLASS + ":I", "*:S");
        // Search for string.
        String testString = "";
        Scanner in = new Scanner(logs);
        while (in.hasNextLine()) {
            String line = in.nextLine();
            if(line.startsWith("I/" + CLASS)) {
                testString = line.split(":")[1].trim();
                break;
            }
        }
        device.executeAdbCommand("logcat", "-c");
        assertEquals(expectedOut, testString);
    }

    /**
     * Test 11.2.13-1
     * Tests that the device responds correctly to a <USER_CONTROL_PRESSED> message followed
     * immediately by a <USER_CONTROL_RELEASED> message.
     */
    @Test
    public void cect_11_2_13_1_UserControlPressAndRelease() throws Exception {
        ITestDevice device = getDevice();
        // Clear activity
        device.executeShellCommand(CLEAR_COMMAND);
        // Clear logcat.
        device.executeAdbCommand("logcat", "-c");
        // Start the APK and wait for it to complete.
        device.executeShellCommand(START_COMMAND);
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_UP, false);
        lookForLog("Short press KEYCODE_DPAD_UP");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_DOWN, false);
        lookForLog("Short press KEYCODE_DPAD_DOWN");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_LEFT, false);
        lookForLog("Short press KEYCODE_DPAD_LEFT");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_RIGHT, false);
        lookForLog("Short press KEYCODE_DPAD_RIGHT");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_SELECT, false);
        lookForLog("Short press KEYCODE_DPAD_CENTER");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_BACK, false);
        lookForLog("Short press KEYCODE_BACK");
    }

    /**
     * Test 11.2.13-2
     * Tests that the device responds correctly to a <USER_CONTROL_PRESSED> message for press and
     * hold operations.
     */
    @Test
    public void cect_11_2_13_2_UserControlPressAndHold() throws Exception {
        ITestDevice device = getDevice();
        // Clear activity
        device.executeShellCommand(CLEAR_COMMAND);
        // Clear logcat.
        device.executeAdbCommand("logcat", "-c");
        // Start the APK and wait for it to complete.
        device.executeShellCommand(START_COMMAND);
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_UP, true);
        lookForLog("Long press KEYCODE_DPAD_UP");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_DOWN, true);
        lookForLog("Long press KEYCODE_DPAD_DOWN");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_LEFT, true);
        lookForLog("Long press KEYCODE_DPAD_LEFT");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_RIGHT, true);
        lookForLog("Long press KEYCODE_DPAD_RIGHT");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_SELECT, true);
        lookForLog("Long press KEYCODE_DPAD_CENTER");
        hdmiCecClient.sendUserControlPressAndRelease(CecDevice.TV, CecDevice.PLAYBACK_1,
                HdmiCecConstants.CEC_CONTROL_BACK, true);
        lookForLog("Long press KEYCODE_BACK");
    }
}
