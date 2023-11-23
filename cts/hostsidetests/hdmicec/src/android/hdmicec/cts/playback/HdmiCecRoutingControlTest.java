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

/** HDMI CEC test to test routing control (Section 11.2.2) */
@RunWith(DeviceJUnit4ClassRunner.class)
public final class HdmiCecRoutingControlTest extends BaseHostJUnit4Test {

    private static final int PHYSICAL_ADDRESS = 0x1000;

    @Rule
    public HdmiCecClientWrapper hdmiCecClient =
            new HdmiCecClientWrapper(CecDevice.PLAYBACK_1, this);

    /**
     * Test 11.2.2-1
     * Tests that the device broadcasts a <ACTIVE_SOURCE> in response to a <SET_STREAM_PATH>, when
     * the TV has switched to a different input.
     */
    @Test
    public void cect_11_2_2_1_SetStreamPathToDut() throws Exception {
        final long hdmi2Address = 0x2000;
        /* Switch to HDMI2. Setup assumes DUT is connected to HDMI1. */
        hdmiCecClient.sendCecMessage(CecDevice.PLAYBACK_2, CecDevice.BROADCAST,
                CecMessage.ACTIVE_SOURCE, hdmiCecClient.formatParams(hdmi2Address));
        TimeUnit.SECONDS.sleep(3);
        hdmiCecClient.sendCecMessage(CecDevice.TV, CecDevice.BROADCAST,
                CecMessage.SET_STREAM_PATH,
                hdmiCecClient.formatParams(HdmiCecConstants.PHYSICAL_ADDRESS));
        String message = hdmiCecClient.checkExpectedOutput(CecMessage.ACTIVE_SOURCE);
        assertEquals(HdmiCecConstants.PHYSICAL_ADDRESS,
                hdmiCecClient.getParamsFromMessage(message));
    }

    /**
     * Test 11.2.2-2
     * Tests that the device broadcasts a <ACTIVE_SOURCE> in response to a <REQUEST_ACTIVE_SOURCE>.
     * This test depends on One Touch Play, and will pass only if One Touch Play passes.
     */
    @Test
    public void cect_11_2_2_2_RequestActiveSource() throws Exception {
        ITestDevice device = getDevice();
        device.executeShellCommand("input keyevent KEYCODE_HOME");
        hdmiCecClient.sendCecMessage(CecDevice.TV, CecDevice.BROADCAST,
            CecMessage.REQUEST_ACTIVE_SOURCE);
        String message = hdmiCecClient.checkExpectedOutput(CecMessage.ACTIVE_SOURCE);
        assertEquals(PHYSICAL_ADDRESS, hdmiCecClient.getParamsFromMessage(message));
    }

    /**
     * Test 11.2.2-4
     * Tests that the device sends a <INACTIVE_SOURCE> message when put on standby.
     * This test depends on One Touch Play, and will pass only if One Touch Play passes.
     */
    @Test
    public void cect_11_2_2_4_InactiveSourceOnStandby() throws Exception {
        ITestDevice device = getDevice();
        try {
            device.executeShellCommand("input keyevent KEYCODE_HOME");
            device.executeShellCommand("input keyevent KEYCODE_SLEEP");
            String message = hdmiCecClient.checkExpectedOutput(CecDevice.TV,
                    CecMessage.INACTIVE_SOURCE);
            assertEquals(HdmiCecConstants.PHYSICAL_ADDRESS,
                    hdmiCecClient.getParamsFromMessage(message));
        } finally {
            /* Wake up the device */
            device.executeShellCommand("input keyevent KEYCODE_WAKEUP");
        }
    }
}
