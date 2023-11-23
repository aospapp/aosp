/*
 * Copyright 2020 The Android Open Source Project
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

package android.hdmicec.cts.audio;

import static org.junit.Assert.assertThat;
import static org.hamcrest.CoreMatchers.is;

import android.hdmicec.cts.CecDevice;
import android.hdmicec.cts.CecMessage;
import android.hdmicec.cts.HdmiCecClientWrapper;
import android.hdmicec.cts.HdmiCecConstants;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.Test;

/** HDMI CEC test to test system audio mode (Section 11.2.15) */
@RunWith(DeviceJUnit4ClassRunner.class)
public final class HdmiCecSystemAudioModeTest extends BaseHostJUnit4Test {
    private static final CecDevice AUDIO_DEVICE = CecDevice.AUDIO_SYSTEM;
    private static final int ON = 0x1;

    @Rule
    public HdmiCecClientWrapper hdmiCecClient = new HdmiCecClientWrapper(AUDIO_DEVICE, this);

    /**
     * Test 11.2.15-1
     * Tests that the device handles <System Audio Mode Request> messages from various logical
     * addresses correctly as a follower.
     */
    @Test
    public void cect_11_2_15_1_SystemAudioModeRequestAsFollower() throws Exception {
        hdmiCecClient.sendCecMessage(CecDevice.TV, AUDIO_DEVICE,
                CecMessage.SYSTEM_AUDIO_MODE_REQUEST,
                hdmiCecClient.formatParams(HdmiCecConstants.TV_PHYSICAL_ADDRESS));
        String message = hdmiCecClient.checkExpectedOutput(CecMessage.SET_SYSTEM_AUDIO_MODE);
        assertThat(hdmiCecClient.getParamsFromMessage(message), is(ON));

        /* Repeat test for device 0x3 (TUNER_1) */
        hdmiCecClient.sendCecMessage(CecDevice.TUNER_1, AUDIO_DEVICE,
                CecMessage.SYSTEM_AUDIO_MODE_REQUEST,
                hdmiCecClient.formatParams(HdmiCecConstants.TV_PHYSICAL_ADDRESS));
        message = hdmiCecClient.checkExpectedOutput(CecMessage.SET_SYSTEM_AUDIO_MODE);
        assertThat(hdmiCecClient.getParamsFromMessage(message), is(ON));
    }
}
