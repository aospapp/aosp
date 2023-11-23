/**
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

package android.security.cts;

import android.platform.test.annotations.SecurityTest;

@SecurityTest
public class Poc18_06 extends SecurityTestCase {

  /**
   * CVE-2018-5884
   */
  @SecurityTest(minPatchLevel = "2018-06")
  public void testPocCVE_2018_5884() throws Exception {
    String wfd_service = AdbUtils.runCommandLine(
        "pm list package com.qualcomm.wfd.service", getDevice());
    if (wfd_service.contains("com.qualcomm.wfd.service")) {
      String result = AdbUtils.runCommandLine(
          "am broadcast -a qualcomm.intent.action.WIFI_DISPLAY_BITRATE --ei format 3 --ei value 32",
          getDevice());
      assertNotMatchesMultiLine("Broadcast completed", result);
    }
  }

  /**
   * CVE-2018-5892
   */
  @SecurityTest(minPatchLevel = "2018-06")
  public void testPocCVE_2018_5892() throws Exception {
    String result = AdbUtils.runCommandLine(
        "pm list package com.emoji.keyboard.touchpal", getDevice());
    assertFalse(result.contains("com.emoji.keyboard.touchpal"));
  }

    /**
     *  b/73172817
     */
    @SecurityTest
    public void testPocCVE_2018_9344() throws Exception {
        AdbUtils.runCommandLine("logcat -c", getDevice());
        AdbUtils.runPoc("CVE-2018-9344", getDevice(), 30);
        String output = AdbUtils.runCommandLine("logcat -d", getDevice());
        assertNotMatchesMultiLine(">>> /vendor/bin/hw/android.hardware.cas@1.0-service <<<" +
                ".*?signal 11 \\(SIGSEGV\\)", output);
    }
}
