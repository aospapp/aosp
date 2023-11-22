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
import java.util.Arrays;
import java.util.concurrent.Callable;

@SecurityTest
public class Poc17_05 extends SecurityTestCase {

    /**
     *  b/34277115
     */
    @SecurityTest(minPatchLevel = "2017-05")
    public void testPocCVE_2017_0630() throws Exception {
        if (containsDriver(getDevice(), "/sys/kernel/debug/tracing/printk_formats")) {
            String printkFormats = AdbUtils.runCommandLine(
                    "cat /sys/kernel/debug/tracing/printk_formats", getDevice());
            String[] pointerStrings = printkFormats.split("\n");
            assertNotKernelPointer(new Callable<String>() {
                int index;
                @Override
                public String call() {
                  for (; index < pointerStrings.length; index++) {
                      String line = pointerStrings[index];
                      String pattern = "0x";
                      int startIndex = line.indexOf(pattern);
                      if (startIndex == -1) {
                          continue;
                      }
                      return line.substring(startIndex + pattern.length());
                  }
                  return null;
                }
            }, null);
        }
    }
}
