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
 * limitations under the License
 */

package android.telecom.cts;

import static com.android.compatibility.common.util.SystemUtil.runWithShellPermissionIdentity;
import static android.telecom.cts.TestUtils.shouldTestTelecom;

import android.telecom.TelecomManager;

import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class TelecomManagerTest extends BaseTelecomTestWithMockServices {
    public void testGetCurrentTtyMode() {
        if (!TestUtils.shouldTestTelecom(mContext)) {
            return;
        }

        LinkedBlockingQueue<Integer> queue = new LinkedBlockingQueue(1);
        runWithShellPermissionIdentity(() ->
                queue.put(mTelecomManager.getCurrentTtyMode()));
        try {
            int currentTtyMode = queue.poll(TestUtils.WAIT_FOR_STATE_CHANGE_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
            assertEquals(TelecomManager.TTY_MODE_OFF, currentTtyMode);
            assertFalse(TelecomManager.TTY_MODE_FULL == currentTtyMode);
            assertFalse(TelecomManager.TTY_MODE_HCO == currentTtyMode);
            assertFalse(TelecomManager.TTY_MODE_VCO == currentTtyMode);
        } catch (InterruptedException e) {
            fail("Couldn't get TTY mode.");
            e.printStackTrace();
        }
    }

    public void testIsInEmergencyCall() {
        if (!TestUtils.shouldTestTelecom(mContext)) {
            return;
        }

        LinkedBlockingQueue<Boolean> queue = new LinkedBlockingQueue(1);
        runWithShellPermissionIdentity(() ->
                queue.put(mTelecomManager.isInEmergencyCall()));
        try {
            boolean isInEmergencyCall = queue.poll(TestUtils.WAIT_FOR_STATE_CHANGE_TIMEOUT_MS,
                    TimeUnit.MILLISECONDS);
            assertFalse(isInEmergencyCall);
        } catch (InterruptedException e) {
            fail("Couldn't check if in emergency call.");
            e.printStackTrace();
        }
    }
}
