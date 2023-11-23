/*
 * Copyright (C) 2021 The Android Open Source Project
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

import android.platform.test.annotations.AsbSecurityTest;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

import static org.junit.Assert.*;

@RunWith(DeviceJUnit4ClassRunner.class)
public class CVE_2021_0636 extends SecurityTestCase {

    public void testPocCVE_2021_0636(String mediaFileName) throws Exception {
        /*
         * Non StageFright test.
         */
        AdbUtils.pushResource(
                "/" + mediaFileName, "/sdcard/" + mediaFileName, getDevice());
        AdbUtils.runCommandLine("logcat -c", getDevice());
        AdbUtils.runCommandLine(
                "am start -a android.intent.action.VIEW -t video/avi -d file:///sdcard/"
                    + mediaFileName, getDevice());
        Thread.sleep(4000); // Delay to run the media file and capture output in logcat
        AdbUtils.runCommandLine("rm -rf /sdcard/" + mediaFileName, getDevice());
        AdbUtils.assertNoCrashes(getDevice(), "mediaserver");
    }

    @Test
    @AsbSecurityTest(cveBugId = 189392423)
    public void testPocCVE_2021_0636() throws Exception {
        testPocCVE_2021_0636("cve_2021_0636_1.avi");
        testPocCVE_2021_0636("cve_2021_0636_2.avi");
        testPocCVE_2021_0636("cve_2021_0636_3.avi");
        testPocCVE_2021_0636("cve_2021_0636_4.avi");
    }
}
