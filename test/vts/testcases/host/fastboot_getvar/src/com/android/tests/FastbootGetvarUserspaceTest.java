/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.tests.fastboot_getvar;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import com.android.tradefed.device.DeviceProperties;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.AfterClassWithInfo;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.testtype.junit4.BeforeClassWithInfo;
import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/* VTS test to verify userspace fastboot getvar information. */
@RunWith(DeviceJUnit4ClassRunner.class)
public class FastbootGetvarUserspaceTest extends BaseHostJUnit4Test {
    private static final int PLATFORM_API_LEVEL_R = 30;
    private static final int ANDROID_RELEASE_VERSION_R = 11;

    private static ITestDevice sDevice;
    private static String sCodeName;
    private static String executeShellKernelARM64 =
            "cat /proc/config.gz | gzip -d | grep CONFIG_ARM64=y";
    private static boolean isGKI10;

    @BeforeClassWithInfo
    public static void setUpClass(TestInformation testInfo) throws Exception {
        sDevice = testInfo.getDevice();

        boolean isKernelARM64 =
                sDevice.executeShellCommand(executeShellKernelARM64).contains("CONFIG_ARM64");
        isGKI10 = false;
        if (isKernelARM64) {
            String output = sDevice.executeShellCommand("uname -r");
            Pattern p = Pattern.compile("^(\\d+)\\.(\\d+)");
            Matcher m1 = p.matcher(output);
            assertTrue(m1.find());
            isGKI10 = (Integer.parseInt(m1.group(1)) == 5 && Integer.parseInt(m1.group(2)) == 4);
        }

        // Gets the code name via adb first. The following test cases might
        // assert different values based on if the build is a final release build
        // or not, where the value of the code name will be "REL" in this case.
        sCodeName = sDevice.getProperty(DeviceProperties.BUILD_CODENAME);
        assertNotNull(sCodeName);
        sCodeName = sCodeName.trim();

        // Transfers from adb to fastbootd.
        if (!isGKI10) {
            sDevice.rebootIntoFastbootd();
        }
    }

    @Before
    public void setUp() throws Exception {
        Assume.assumeFalse("Skipping test for fastbootd on GKI 1.0", isGKI10);
    }

    @AfterClassWithInfo
    public static void tearDownClass(TestInformation testInfo) throws Exception {
        if (!isGKI10) {
            testInfo.getDevice().reboot(); // back to adb.
        }
    }

    /* Devices launching in R and after must export cpu-abi. */
    @Test
    public void testCpuAbiInfo() throws Exception {
        final HashSet<String> allCpuAbis = new HashSet<String>(
                Arrays.asList("armeabi-v7a", "arm64-v8a", "mips", "mips64", "x86", "x86_64"));
        String cpuAbi = sDevice.getFastbootVariable("cpu-abi");
        CLog.d("cpuAbi: '%s'", cpuAbi);
        assertTrue(allCpuAbis.contains(cpuAbi));
    }

    /* Devices launching in R and after must export version-os. */
    @Test
    public void testOsVersion() throws Exception {
        String osVersion = sDevice.getFastbootVariable("version-os");
        CLog.d("os version: '%s'", osVersion);
        // The value of osVersion is derived from "ro.build.version.release",
        // which is a user-visible version string. The value does not have
        // has any particular structure. See https://r.android.com/657597 for
        // details.
        assertNotNull(osVersion);
    }

    /* Devices launching in R and after must export version-vndk. */
    @Test
    public void testVndkVersion() throws Exception {
        String vndkVersion = sDevice.getFastbootVariable("version-vndk");
        CLog.d("vndk version: '%s'", vndkVersion);
        // The value of vndkVersion might be a letter or a string on pre-release builds,
        // e.g., R or Tiramisu.
        // And it is a number representing the API level on final release builds, e.g., 30.
        if ("REL".equals(sCodeName)) {
            try {
                int intVndkVersion = Integer.parseInt(vndkVersion);
                assertTrue(intVndkVersion >= PLATFORM_API_LEVEL_R);
            } catch (NumberFormatException nfe) {
                fail("ro.vndk.version should be a number. But the current value is " + vndkVersion);
            }
        } else {
            assertNotNull(vndkVersion);
        }
    }

    /* Devices launching in R and after must export dynamic-partition. */
    @Test
    public void testDynamicPartition() throws Exception {
        String dynamic_partition = sDevice.getFastbootVariable("dynamic-partition");
        CLog.d("dynamic_partition: '%s'", dynamic_partition);
        assertTrue(dynamic_partition.equals("true"));
    }

    /* Devices launching in R and after must export treble-enabled. */
    @Test
    public void testTrebleEnable() throws Exception {
        String treble_enabled = sDevice.getFastbootVariable("treble-enabled");
        CLog.d("treble_enabled: '%s'", treble_enabled);
        assertTrue(treble_enabled.equals("true") || treble_enabled.equals("false"));
    }

    /* Devices launching in R and after must export first-api-level. */
    @Test
    public void testFirstApiLevel() throws Exception {
        String first_api_level = sDevice.getFastbootVariable("first-api-level");
        CLog.d("first_api_level: '%s'", first_api_level);
        try {
            int api_level = Integer.parseInt(first_api_level);
            assertTrue(api_level >= PLATFORM_API_LEVEL_R);
        } catch (NumberFormatException nfe) {
            fail("Failed to parse first-api-level: " + first_api_level);
        }
    }

    /* Devices launching in R and after must export security-patch-level. */
    @Test
    public void testSecurityPatchLevel() throws Exception {
        String SPL = sDevice.getFastbootVariable("security-patch-level");
        CLog.d("SPL: '%s'", SPL);
        try {
            SimpleDateFormat template = new SimpleDateFormat("yyyy-MM-dd");
            template.parse(SPL);
        } catch (ParseException e) {
            fail("Failed to parse security-patch-level: " + SPL);
        }
    }

    /* Devices launching in R and after must export system-fingerprint. */
    @Test
    public void testSystemFingerprint() throws Exception {
        String systemFingerprint = sDevice.getFastbootVariable("system-fingerprint");
        CLog.d("system fingerprint: '%s'", systemFingerprint);
        verifyFingerprint(systemFingerprint);
    }

    /* Devices launching in R and after must export vendor-fingerprint. */
    @Test
    public void testVendorFingerprint() throws Exception {
        String vendorFingerprint = sDevice.getFastbootVariable("vendor-fingerprint");
        CLog.d("vendor fingerprint: '%s'", vendorFingerprint);
        verifyFingerprint(vendorFingerprint);
    }

    /*
     *  Verifies the fingerprint defined in CDD.
     *    https://source.android.com/compatibility/cdd
     *
     *  The fingerprint should be of format:
     *    $(BRAND)/$(PRODUCT)/$(DEVICE):$(VERSION.RELEASE)/$(ID)/$(VERSION.INCREMENTAL):$(TYPE)/$(TAGS).
     */
    private void verifyFingerprint(String fingerprint) {
        final HashSet<String> allBuildVariants =
                new HashSet<String>(Arrays.asList("user", "userdebug", "eng"));

        final HashSet<String> allTags =
                new HashSet<String>(Arrays.asList("release-keys", "dev-keys", "test-keys"));

        verifyFingerprintStructure(fingerprint);

        String[] fingerprintSegs = fingerprint.split("/");
        assertTrue(fingerprintSegs[0].matches("^[a-zA-Z0-9_-]+$")); // BRAND
        assertTrue(fingerprintSegs[1].matches("^[a-zA-Z0-9_-]+$")); // PRODUCT

        String[] devicePlatform = fingerprintSegs[2].split(":");
        assertEquals(2, devicePlatform.length);
        assertTrue(devicePlatform[0].matches("^[a-zA-Z0-9_-]+$")); // DEVICE

        assertTrue(fingerprintSegs[3].matches("^[a-zA-Z0-9._-]+$")); // ID

        String[] buildNumberVariant = fingerprintSegs[4].split(":");
        assertTrue(buildNumberVariant[0].matches("^[^ :\\/~]+$")); // VERSION.INCREMENTAL
        assertTrue(allBuildVariants.contains(buildNumberVariant[1])); // TYPE

        assertTrue(allTags.contains(fingerprintSegs[5])); // TAG
    }

    private void verifyFingerprintStructure(String fingerprint) {
        assertEquals("Build fingerprint must not include whitespace", -1, fingerprint.indexOf(' '));

        String[] segments = fingerprint.split("/");
        assertEquals("Build fingerprint does not match expected format", 6, segments.length);

        String[] devicePlatform = segments[2].split(":");
        assertEquals(2, devicePlatform.length);

        assertTrue(segments[4].contains(":"));
        String buildVariant = segments[4].split(":")[1];
        assertTrue(buildVariant.length() > 0);
    }
}
