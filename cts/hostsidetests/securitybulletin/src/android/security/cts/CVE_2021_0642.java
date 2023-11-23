/**
 * Copyright (C) 2022 The Android Open Source Project
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

import android.platform.test.annotations.AppModeFull;
import android.platform.test.annotations.AsbSecurityTest;

import com.android.sts.common.tradefed.testtype.StsExtraBusinessLogicHostTestBase;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(DeviceJUnit4ClassRunner.class)
public class CVE_2021_0642 extends StsExtraBusinessLogicHostTestBase {
    static final String TEST_APP = "CVE-2021-0642.apk";
    static final String TEST_PKG = "android.security.cts.cve_2021_0642";
    static final String TEST_CLASS = TEST_PKG + "." + "DeviceTest";

    @Before
    public void setUp() throws Exception {
        ITestDevice device = getDevice();
        AdbUtils.runCommandLine("input keyevent KEYCODE_WAKEUP", device);
        AdbUtils.runCommandLine("input keyevent KEYCODE_MENU", device);
        AdbUtils.runCommandLine("input keyevent KEYCODE_HOME", device);
        uninstallPackage(device, TEST_PKG);
    }

    /**
     * b/185126149
     */
    @AppModeFull
    @AsbSecurityTest(cveBugId = 185126149)
    @Test
    public void testPocCVE_2021_0642() throws Exception {
        installPackage(TEST_APP);
        Assert.assertTrue(runDeviceTests(TEST_PKG, TEST_CLASS, "testCVE_2021_0642"));
    }
}
