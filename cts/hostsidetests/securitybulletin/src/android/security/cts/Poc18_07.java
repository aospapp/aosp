/**
 * Copyright (C) 2018 The Android Open Source Project
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

import android.platform.test.annotations.SecurityTest;

@SecurityTest
public class Poc18_07 extends SecurityTestCase {

    /**
     * b/76221123
     */
     @SecurityTest(minPatchLevel = "2018-07")
     public void testPocCVE_2018_9424() throws Exception {
       AdbUtils.runCommandLine("logcat -c" , getDevice());
       AdbUtils.runPoc("CVE-2018-9424", getDevice(), 60);
       String result = AdbUtils.runCommandLine("logcat -d", getDevice());
       assertNotMatchesMultiLine("Fatal signal", result);
     }
}
