/*
 * Copyright (C) 2023 The Android Open Source Project
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

package android.security.cts;

import static org.junit.Assume.assumeNoException;

import android.platform.test.annotations.AsbSecurityTest;

import com.android.sts.common.tradefed.testtype.NonRootSecurityTestCase;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class CVE_2023_21091 extends NonRootSecurityTestCase {

    @AsbSecurityTest(cveBugId = 257954050)
    @Test
    public void testCVE_2023_21091() {
        ITestDevice device = null;
        int testUserId = -1;
        try {
            device = getDevice();

            // Creating a new user.
            testUserId = device.createUser("testUser");
            device.startUser(testUserId);
            device.switchUser(testUserId);

            final String testPkg = "android.security.cts.CVE_2023_21091";
            installPackage("CVE-2023-21091.apk", "--user " + testUserId);

            runDeviceTests(testPkg, testPkg + ".DeviceTest", "testPocCVE_2023_21091");
        } catch (Exception e) {
            assumeNoException(e);
        } finally {
            try {
                if (testUserId != -1) {
                    device.switchUser(0);
                    device.stopUser(testUserId);
                    device.removeUser(testUserId);
                }
            } catch (Exception ignored) {
                // ignore the exceptions
            }
        }
    }
}
