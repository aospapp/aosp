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

/** HDMI CEC test to check if the device reports power status correctly (Section 11.2.14) */
@RunWith(DeviceJUnit4ClassRunner.class)
public final class HdmiCecPowerStatusTest extends BaseHostJUnit4Test {

    private static final int ON = 0x0;
    private static final int OFF = 0x1;

    private static final int WAIT_TIME = 5;

    @Rule
    public HdmiCecClientWrapper hdmiCecClient =
        new HdmiCecClientWrapper(CecDevice.PLAYBACK_1, this);

    /**
     * Test 11.2.14-1
     * Tests that the device broadcasts a <REPORT_POWER_STATUS> with params 0x0 when the device is
     * powered on.
     */
    @Test
    public void cect_11_2_14_1_PowerStatusWhenOn() throws Exception {
        ITestDevice device = getDevice();
        /* Make sure the device is not booting up/in standby */
        device.waitForBootComplete(HdmiCecConstants.REBOOT_TIMEOUT);
        hdmiCecClient.sendCecMessage(CecDevice.TV, CecMessage.GIVE_POWER_STATUS);
        String message = hdmiCecClient.checkExpectedOutput(CecDevice.TV,
                                                            CecMessage.REPORT_POWER_STATUS);
        assertEquals(ON, hdmiCecClient.getParamsFromMessage(message));
    }

    /**
     * Test 11.2.14-2
     * Tests that the device broadcasts a <REPORT_POWER_STATUS> with params 0x1 when the device is
     * powered on.
     */
    @Test
    public void cect_11_2_14_2_PowerStatusWhenOff() throws Exception {
        ITestDevice device = getDevice();
        try {
            /* Make sure the device is not booting up/in standby */
            device.waitForBootComplete(HdmiCecConstants.REBOOT_TIMEOUT);
            device.executeShellCommand("input keyevent KEYCODE_SLEEP");
            TimeUnit.SECONDS.sleep(WAIT_TIME);
            hdmiCecClient.sendCecMessage(CecDevice.TV, CecMessage.GIVE_POWER_STATUS);
            String message = hdmiCecClient.checkExpectedOutput(CecDevice.TV,
                                                              CecMessage.REPORT_POWER_STATUS);
            assertEquals(OFF, hdmiCecClient.getParamsFromMessage(message));
        } finally {
            /* Wake up the device */
            device.executeShellCommand("input keyevent KEYCODE_WAKEUP");
        }
    }
}
