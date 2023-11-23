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

import static org.junit.Assert.assertNotEquals;

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

/** HDMI CEC test to verify device vendor specific commands (Section 11.2.9) */
@RunWith(DeviceJUnit4ClassRunner.class)
public final class HdmiCecVendorCommandsTest extends BaseHostJUnit4Test {
    private static final CecDevice PLAYBACK_DEVICE = CecDevice.PLAYBACK_1;
    private static final int INCORRECT_VENDOR_ID = 0x0;

    @Rule
    public HdmiCecClientWrapper hdmiCecClient =
        new HdmiCecClientWrapper(CecDevice.PLAYBACK_1, this);

    /**
     * Test 11.2.9-1
     * Tests that the device responds to a <GIVE_DEVICE_VENDOR_ID> from various source devices
     * with a <DEVICE_VENDOR_ID>.
     */
    @Test
    public void cect_11_2_9_1_GiveDeviceVendorId() throws Exception {
        for (CecDevice cecDevice : CecDevice.values()) {
            hdmiCecClient.sendCecMessage(cecDevice, CecMessage.GIVE_DEVICE_VENDOR_ID);
            String message = hdmiCecClient.checkExpectedOutput(CecMessage.DEVICE_VENDOR_ID);
            assertNotEquals(INCORRECT_VENDOR_ID, hdmiCecClient.getParamsFromMessage(message));
        }
    }

    /**
     * Test 11.2.9-2
     * Tests that the device broadcasts a <DEVICE_VENDOR_ID> message after successful
     * initialisation and address allocation.
     */
    @Test
    public void cect_11_2_9_2_DeviceVendorIdOnInit() throws Exception {
        ITestDevice device = getDevice();
        device.executeShellCommand("reboot");
        device.waitForBootComplete(HdmiCecConstants.REBOOT_TIMEOUT);
        String message = hdmiCecClient.checkExpectedOutput(CecMessage.DEVICE_VENDOR_ID);
        assertNotEquals(INCORRECT_VENDOR_ID, hdmiCecClient.getParamsFromMessage(message));
    }
}
