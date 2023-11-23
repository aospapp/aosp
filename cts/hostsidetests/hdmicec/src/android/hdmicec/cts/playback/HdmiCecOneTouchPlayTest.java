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

import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Rule;
import org.junit.runner.RunWith;
import org.junit.Test;

/** HDMI CEC tests for One Touch Play (Section 11.2.1) */
@RunWith(DeviceJUnit4ClassRunner.class)
public final class HdmiCecOneTouchPlayTest extends BaseHostJUnit4Test {

    private static final int PHYSICAL_ADDRESS = 0x1000;

    @Rule
    public HdmiCecClientWrapper hdmiCecClient =
        new HdmiCecClientWrapper(CecDevice.PLAYBACK_1, this);

    /**
     * Test 11.2.1-1
     * Tests that the device sends a <TEXT_VIEW_ON> when the home key is pressed on device, followed
     * by a <ACTIVE_SOURCE> message.
     */
    @Test
    public void cect_11_2_1_1_OneTouchPlay() throws Exception {
        ITestDevice device = getDevice();
        device.executeShellCommand("input keyevent KEYCODE_HOME");
        hdmiCecClient.checkExpectedOutput(CecDevice.TV, CecMessage.TEXT_VIEW_ON);
        String message = hdmiCecClient.checkExpectedOutput(CecMessage.ACTIVE_SOURCE);
        assertEquals(PHYSICAL_ADDRESS, hdmiCecClient.getParamsFromMessage(message));
    }
}
