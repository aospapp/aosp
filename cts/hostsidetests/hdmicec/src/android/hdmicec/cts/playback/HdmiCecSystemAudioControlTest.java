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
import android.hdmicec.cts.CecMessage;
import android.hdmicec.cts.HdmiCecClientWrapper;
import android.hdmicec.cts.HdmiCecConstants;

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.Test;

import java.util.concurrent.TimeUnit;

/** HDMI CEC test to verify system audio control commands (Section 11.2.15) */
@RunWith(DeviceJUnit4ClassRunner.class)
public final class HdmiCecSystemAudioControlTest extends BaseHostJUnit4Test {
    private static final CecDevice PLAYBACK_DEVICE = CecDevice.PLAYBACK_1;

    @Rule
    public HdmiCecClientWrapper hdmiCecClient =
        new HdmiCecClientWrapper(CecDevice.PLAYBACK_1, this, "-t", "a");

    /**
     * Test 11.2.15-10
     * Tests that the device sends a <GIVE_SYSTEM_AUDIO_STATUS> message when brought out of standby
     */
    @Test
    public void cect_11_2_15_10_GiveSystemAudioModeStatus() throws Exception {
        ITestDevice device = getDevice();
        device.executeShellCommand("input keyevent KEYCODE_SLEEP");
        device.executeShellCommand("input keyevent KEYCODE_WAKEUP");
        hdmiCecClient.checkExpectedOutput(CecDevice.AUDIO_SYSTEM,
                CecMessage.GIVE_SYSTEM_AUDIO_MODE_STATUS);
    }

    /**
     * Test 11.2.15-11
     * Tests that the device sends <USER_CONTROL_PRESSED> and <USER_CONTROL_RELEASED> messages when
     * the volume up and down keys are pressed on the DUT. Test also verifies that the
     * <USER_CONTROL_PRESSED> message has the right control param.
     */
    @Test
    public void cect_11_2_15_11_VolumeUpDownUserControlPressed() throws Exception {
        ITestDevice device = getDevice();
        hdmiCecClient.sendCecMessage(CecDevice.AUDIO_SYSTEM, CecDevice.BROADCAST,
                CecMessage.SET_SYSTEM_AUDIO_MODE, CecMessage.formatParams(1));
        device.executeShellCommand("input keyevent KEYCODE_VOLUME_UP");
        String message = hdmiCecClient.checkExpectedOutput(CecDevice.AUDIO_SYSTEM,
                CecMessage.USER_CONTROL_PRESSED);
        assertEquals(HdmiCecConstants.CEC_CONTROL_VOLUME_UP,
                hdmiCecClient.getParamsFromMessage(message));
        hdmiCecClient.checkExpectedOutput(CecDevice.AUDIO_SYSTEM, CecMessage.USER_CONTROL_RELEASED);


        device.executeShellCommand("input keyevent KEYCODE_VOLUME_DOWN");
        message = hdmiCecClient.checkExpectedOutput(CecDevice.AUDIO_SYSTEM,
                CecMessage.USER_CONTROL_PRESSED);
        assertEquals(HdmiCecConstants.CEC_CONTROL_VOLUME_DOWN,
                hdmiCecClient.getParamsFromMessage(message));
        hdmiCecClient.checkExpectedOutput(CecDevice.AUDIO_SYSTEM, CecMessage.USER_CONTROL_RELEASED);
    }

    /**
     * Test 11.2.15-12
     * Tests that the device sends <USER_CONTROL_PRESSED> and <USER_CONTROL_RELEASED> messages when
     * the mute key is pressed on the DUT. Test also verifies that the <USER_CONTROL_PRESSED>
     * message has the right control param.
     */
    @Test
    public void cect_11_2_15_12_MuteUserControlPressed() throws Exception {
        ITestDevice device = getDevice();
        hdmiCecClient.sendCecMessage(CecDevice.AUDIO_SYSTEM, CecDevice.BROADCAST,
                CecMessage.SET_SYSTEM_AUDIO_MODE, CecMessage.formatParams(1));
        device.executeShellCommand("input keyevent KEYCODE_VOLUME_MUTE");
        String message = hdmiCecClient.checkExpectedOutput(CecDevice.AUDIO_SYSTEM,
                CecMessage.USER_CONTROL_PRESSED);
        assertEquals(HdmiCecConstants.CEC_CONTROL_MUTE,
                hdmiCecClient.getParamsFromMessage(message));
        hdmiCecClient.checkExpectedOutput(CecDevice.AUDIO_SYSTEM, CecMessage.USER_CONTROL_RELEASED);
    }
}
