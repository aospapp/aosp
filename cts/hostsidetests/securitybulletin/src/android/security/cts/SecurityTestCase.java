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

package android.security.cts;

import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.device.NativeDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.ddmlib.Log;

import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;

import java.util.regex.Pattern;
import java.util.regex.Matcher;
import java.util.concurrent.Callable;
import java.math.BigInteger;

import static org.junit.Assert.*;
import static org.junit.Assume.*;

public class SecurityTestCase extends BaseHostJUnit4Test {

    private static final String LOG_TAG = "SecurityTestCase";
    private static final int RADIX_HEX = 16;

    protected static final int TIMEOUT_DEFAULT = 60;
    // account for the poc timer of 5 minutes (+15 seconds for safety)
    protected static final int TIMEOUT_NONDETERMINISTIC = 315;

    private long kernelStartTime;

    private HostsideOomCatcher oomCatcher = new HostsideOomCatcher(this);
    private HostsideMainlineModuleDetector mainlineModuleDetector = new HostsideMainlineModuleDetector(this);

    /**
     * Waits for device to be online, marks the most recent boottime of the device
     */
    @Before
    public void setUp() throws Exception {
        getDevice().waitForDeviceAvailable();
        getDevice().disableAdbRoot();
        updateKernelStartTime();
        // TODO:(badash@): Watch for other things to track.
        //     Specifically time when app framework starts

        oomCatcher.start();
    }

    /**
     * Makes sure the phone is online, and the ensure the current boottime is within 2 seconds
     * (due to rounding) of the previous boottime to check if The phone has crashed.
     */
    @After
    public void tearDown() throws Exception {
        oomCatcher.stop(getDevice().getSerialNumber());

        try {
            getDevice().waitForDeviceAvailable(90 * 1000);
        } catch (DeviceNotAvailableException e) {
            // Force a disconnection of all existing sessions to see if that unsticks adbd.
            getDevice().executeAdbCommand("reconnect");
            getDevice().waitForDeviceAvailable(30 * 1000);
        }

        if (oomCatcher.isOomDetected()) {
            // we don't need to check kernel start time if we intentionally rebooted because oom
            updateKernelStartTime();
            switch (oomCatcher.getOomBehavior()) {
                case FAIL_AND_LOG:
                    fail("The device ran out of memory.");
                    break;
                case PASS_AND_LOG:
                    Log.logAndDisplay(Log.LogLevel.INFO, LOG_TAG, "Skipping test.");
                    break;
                case FAIL_NO_LOG:
                    fail();
                    break;
            }
        } else {
            long deviceTime = getDeviceUptime() + kernelStartTime;
            long hostTime = System.currentTimeMillis() / 1000;
            assertTrue("Phone has had a hard reset", (hostTime - deviceTime) < 2);

            // TODO(badash@): add ability to catch runtime restart
        }
    }

    // TODO convert existing assertMatches*() to RegexUtils.assertMatches*()
    // b/123237827
    @Deprecated
    public void assertMatches(String pattern, String input) throws Exception {
        RegexUtils.assertContains(pattern, input);
    }

    @Deprecated
    public void assertMatchesMultiLine(String pattern, String input) throws Exception {
        RegexUtils.assertContainsMultiline(pattern, input);
    }

    @Deprecated
    public void assertNotMatches(String pattern, String input) throws Exception {
        RegexUtils.assertNotContains(pattern, input);
    }

    @Deprecated
    public void assertNotMatchesMultiLine(String pattern, String input) throws Exception {
        RegexUtils.assertNotContainsMultiline(pattern, input);
    }

    /**
     * Runs a provided function that collects a String to test against kernel pointer leaks.
     * The getPtrFunction function implementation must return a String that starts with the
     * pointer. i.e. "01234567". Trailing characters are allowed except for [0-9a-fA-F]. In
     * the event that the pointer appears to be vulnerable, a JUnit assert is thrown. Since kernel
     * pointers can be hashed, there is a possiblity the the hashed pointer overlaps into the
     * normal kernel space. The test re-runs to make false positives statistically insignificant.
     * When kernel pointers won't change without a reboot, provide a device to reboot.
     *
     * @param getPtrFunction a function that returns a string that starts with a pointer
     * @param deviceToReboot device to reboot when kernel pointers won't change
     */
    public void assertNotKernelPointer(Callable<String> getPtrFunction, ITestDevice deviceToReboot)
            throws Exception {
        String ptr = null;
        for (int i = 0; i < 4; i++) { // ~0.4% chance of false positive
            ptr = getPtrFunction.call();
            if (ptr == null) {
                return;
            }
            if (!isKptr(ptr)) {
                // quit early because the ptr is likely hashed or zeroed.
                return;
            }
            if (deviceToReboot != null) {
                deviceToReboot.nonBlockingReboot();
                deviceToReboot.waitForDeviceAvailable();
                updateKernelStartTime();
            }
        }
        fail("\"" + ptr + "\" is an exposed kernel pointer.");
    }

    private boolean isKptr(String ptr) {
        Matcher m = Pattern.compile("[0-9a-fA-F]*").matcher(ptr);
        if (!m.find() || m.start() != 0) {
           // ptr string is malformed
           return false;
        }
        int length = m.end();

        if (length == 8) {
          // 32-bit pointer
          BigInteger address = new BigInteger(ptr.substring(0, length), RADIX_HEX);
          // 32-bit kernel memory range: 0xC0000000 -> 0xffffffff
          // 0x3fffffff bytes = 1GB /  0xffffffff = 4 GB
          // 1 in 4 collision for hashed pointers
          return address.compareTo(new BigInteger("C0000000", RADIX_HEX)) >= 0;
        } else if (length == 16) {
          // 64-bit pointer
          BigInteger address = new BigInteger(ptr.substring(0, length), RADIX_HEX);
          // 64-bit kernel memory range: 0x8000000000000000 -> 0xffffffffffffffff
          // 48-bit implementation: 0xffff800000000000; 1 in 131,072 collision
          // 56-bit implementation: 0xff80000000000000; 1 in 512 collision
          // 64-bit implementation: 0x8000000000000000; 1 in 2 collision
          return address.compareTo(new BigInteger("ff80000000000000", RADIX_HEX)) >= 0;
        }

        return false;
    }

    /**
     * Check if a driver is present on a machine.
     */
    protected boolean containsDriver(ITestDevice device, String driver) throws Exception {
        return AdbUtils.runCommandGetExitCode("test -r " + driver, device) == 0;
    }

    private long getDeviceUptime() throws DeviceNotAvailableException {
        String uptime = getDevice().executeShellCommand("cat /proc/uptime");
        return Long.parseLong(uptime.substring(0, uptime.indexOf('.')));
    }

    public void safeReboot() throws DeviceNotAvailableException {
        getDevice().nonBlockingReboot();
        getDevice().waitForDeviceAvailable();
        updateKernelStartTime();
    }

    /**
     * Allows a test to pass if called after a planned reboot.
     */
    public void updateKernelStartTime() throws DeviceNotAvailableException {
        long uptime = getDeviceUptime();
        kernelStartTime = (System.currentTimeMillis() / 1000) - uptime;
    }

    public HostsideOomCatcher getOomCatcher() {
        return oomCatcher;
    }

    /**
     * Return true if a module is play managed.
     *
     * Example of skipping a test based on mainline modules:
     *  <pre>
     *  @Test
     *  public void testPocCVE_1234_5678() throws Exception {
     *      // This will skip the test if MODULE_METADATA mainline module is play managed.
     *      assumeFalse(moduleIsPlayManaged("com.google.android.captiveportallogin"));
     *      // Do testing...
     *  }
     *  * </pre>
     */
    boolean moduleIsPlayManaged(String modulePackageName) throws Exception {
        return mainlineModuleDetector.getPlayManagedModules().contains(modulePackageName);
    }
}
