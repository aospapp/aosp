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
import static org.junit.Assert.assertFalse;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import org.junit.Test;
import org.junit.runner.RunWith;

@SecurityTest
public class Poc19_03 extends SecurityTestCase {
    /**
     * b/115739809
     */
    @SecurityTest(minPatchLevel = "2019-03")
    public void testPocBug_115739809() throws Exception {
        assertFalse(AdbUtils.runPocCheckExitCode("Bug-115739809", getDevice(), 30));
    }
}
