/*
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

import static org.junit.Assert.assertTrue;

import com.android.compatibility.common.tradefed.build.CompatibilityBuildHelper;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.stream.Stream;

@RunWith(DeviceJUnit4ClassRunner.class)
public class SeamendcHostTest extends BaseHostJUnit4Test {

    private ITestDevice mDevice;

    // executable binaries
    private File seamendc;
    private File secilc;
    private File searchpolicy;
    private File libsepolwrap;

    // CIL policies
    private File mPlatPolicyCil;
    private File mPlatCompatCil;
    private File mSystemExtPolicyCil;
    private File mSystemExtMappingCil;
    private File mSystemExtCompatCil;
    private File mProductPolicyCil;
    private File mProductMappingCil;
    private File mVendorPolicyCil;
    private File mPlatPubVersionedCil;
    private File mOdmPolicyCil;
    private File mApexSepolicyCil;
    private File mApexSepolicyDecompiledCil;

    @Before
    public void setUp() throws Exception {
        mDevice = getDevice();

        seamendc = copyResToTempFile("/seamendc");
        seamendc.setExecutable(true);
        secilc = copyResToTempFile("/secilc");
        secilc.setExecutable(true);
        searchpolicy = copyResToTempFile("/searchpolicy");
        searchpolicy.setExecutable(true);
        libsepolwrap = new CompatibilityBuildHelper(getBuild()).getTestFile("libsepolwrap.so");
        libsepolwrap.deleteOnExit();

        // Pull CIL files for policy compilation, using selinux.cpp as reference
        // https://cs.android.com/android/platform/superproject/+/master:system/core/init/selinux.cpp;l=378-453;drc=2d579af880afae96239c80765b11c7dbc2c1b264
        mPlatPolicyCil = getPlatPolicyFromDevice();
        String vendorMappingVersion =
                readFirstLine("/vendor/etc/selinux/", "plat_sepolicy_vers.txt");
        mPlatCompatCil =
                getDeviceFile("/system/etc/selinux/mapping/", vendorMappingVersion + ".cil");
        mSystemExtPolicyCil = getDeviceFile("/system_ext/etc/selinux/", "system_ext_sepolicy.cil");
        mSystemExtMappingCil =
                getDeviceFile("/system_ext/etc/selinux/mapping/", vendorMappingVersion + ".cil");
        mSystemExtCompatCil =
                getDeviceFile(
                        "/system_ext/etc/selinux/mapping/", vendorMappingVersion + ".compat.cil");
        mProductPolicyCil = getDeviceFile("/product/etc/selinux/", "product_sepolicy.cil");
        mProductMappingCil =
                getDeviceFile("/product/etc/selinux/mapping", vendorMappingVersion + ".cil");
        mVendorPolicyCil = getDeviceFile("/vendor/etc/selinux/", "vendor_sepolicy.cil");
        mPlatPubVersionedCil = getDeviceFile("/vendor/etc/selinux/", "plat_pub_versioned.cil");
        mOdmPolicyCil = getDeviceFile("/odm/etc/selinux/", "odm_sepolicy.cil");
        mApexSepolicyCil = copyResToTempFile("/apex_sepolicy.cil");

        mApexSepolicyDecompiledCil = copyResToTempFile("/apex_sepolicy.decompiled.cil");
    }

    /**
     * Verifies that the files necessary for policy compilation exist.
     *
     * @throws Exception
     */
    @Test
    public void testRequiredDeviceFilesArePresent() throws Exception {
        assertTrue(mPlatPolicyCil.getName() + " is missing", mPlatPolicyCil != null);
        assertTrue(mPlatCompatCil.getName() + " is missing", mPlatCompatCil != null);
        assertTrue(mVendorPolicyCil.getName() + " is missing", mVendorPolicyCil != null);
        assertTrue(mPlatPubVersionedCil.getName() + " is missing", mPlatPubVersionedCil != null);
    }

    private File getPlatPolicyFromDevice() throws Exception {
        File policyFile = getDeviceFile("/system_ext/etc/selinux/", "userdebug_plat_sepolicy.cil");
        if (policyFile == null) {
            policyFile = getDeviceFile("/debug_ramdisk/", "userdebug_plat_sepolicy.cil");
        }
        if (policyFile == null) {
            policyFile = getDeviceFile("/system/etc/selinux/", "plat_sepolicy.cil");
        }
        return policyFile;
    }

    private String readFirstLine(String filePath, String fileName) throws Exception {
        try (BufferedReader brTest =
                new BufferedReader(new FileReader(getDeviceFile(filePath, fileName)))) {
            return brTest.readLine();
        }
    }

    private File getDeviceFile(String filePath, String fileName) throws Exception {
        String deviceFile = filePath + fileName;
        File file = File.createTempFile(fileName, ".tmp");
        file.deleteOnExit();
        return mDevice.pullFile(deviceFile, file) ? file : null;
    }

    private static File copyResToTempFile(String resName) throws IOException {
        InputStream is = SeamendcHostTest.class.getResourceAsStream(resName);
        File tempFile = File.createTempFile(resName, ".tmp");
        FileOutputStream os = new FileOutputStream(tempFile);
        byte[] buf = new byte[1024];
        int len;
        while ((len = is.read(buf)) != -1) {
            os.write(buf, 0, len);
        }
        os.flush();
        os.close();
        tempFile.deleteOnExit();
        return tempFile;
    }

    private static String runProcess(String... args) throws Exception {
        ProcessBuilder pb = new ProcessBuilder(args);
        pb.redirectOutput(ProcessBuilder.Redirect.PIPE);
        pb.redirectErrorStream(true);
        Process p = pb.start();
        BufferedReader result = new BufferedReader(new InputStreamReader(p.getInputStream()));
        String line;
        StringBuilder errorString = new StringBuilder();
        while ((line = result.readLine()) != null) {
            errorString.append(line);
            errorString.append("\n");
        }
        p.waitFor();
        return errorString.toString();
    }

    private String searchpolicySource(File policy, String name) throws Exception {
        return runProcess(
                searchpolicy.getAbsolutePath(),
                "--allow",
                "-s",
                name,
                "--libpath",
                libsepolwrap.getAbsolutePath(),
                policy.getAbsolutePath());
    }

    private String searchpolicyTarget(File policy, String name) throws Exception {
        return runProcess(
                searchpolicy.getAbsolutePath(),
                "--allow",
                "-t",
                name,
                "--libpath",
                libsepolwrap.getAbsolutePath(),
                policy.getAbsolutePath());
    }

    /** Uses searchpolicy to verify the two policies wrt the provided source type. */
    private void assertSource(File left, File right, String type, boolean equal) throws Exception {
        String diff = diff(searchpolicySource(left, type), searchpolicySource(right, type));
        if (equal) {
            assertTrue("Policy sources are not equal:\n" + diff, diff.length() == 0);
        } else {
            assertTrue("Policy sources should be different.", diff.length() != 0);
        }
    }

    private void assertSourceEqual(File left, File right, String type) throws Exception {
        assertSource(left, right, type, /* equal= */ true);
    }

    private void assertSourceNotEqual(File left, File right, String type) throws Exception {
        assertSource(left, right, type, /* equal= */ false);
    }

    /** Uses searchpolicy to verify the two policies wrt the provided target type. */
    private void assertTarget(File left, File right, String type, boolean equal) throws Exception {
        String diff = diff(searchpolicyTarget(left, type), searchpolicyTarget(right, type));
        if (equal) {
            assertTrue("Policies are not equal:\n" + diff, diff.length() == 0);
        } else {
            assertTrue("Policies should be different.", diff.length() != 0);
        }
    }

    private void assertTargetEqual(File left, File right, String type) throws Exception {
        assertTarget(left, right, type, /* equal= */ true);
    }

    private void assertTargetNotEqual(File left, File right, String type) throws Exception {
        assertTarget(left, right, type, /* equal= */ false);
    }

    private File runSeamendc(File basePolicy, File... cilFiles) throws Exception {
        File output = File.createTempFile("seamendc-out", ".binary");
        output.deleteOnExit();
        String errorString =
                runProcess(
                        Stream.concat(
                                        Stream.of(
                                                seamendc.getAbsolutePath(),
                                                "-b",
                                                basePolicy.getAbsolutePath(),
                                                "-o",
                                                output.getAbsolutePath()),
                                        Stream.of(cilFiles).map(File::getAbsolutePath))
                                .toArray(String[]::new));
        assertTrue(errorString, errorString.length() == 0);
        return output;
    }

    private File runSecilc(File... cilFiles) throws Exception {
        File fileContexts = File.createTempFile("file_contexts", ".txt");
        fileContexts.deleteOnExit();
        File output = File.createTempFile("secilc-out", ".binary");
        output.deleteOnExit();
        String errorString =
                runProcess(
                        Stream.concat(
                                        Stream.of(
                                                secilc.getAbsolutePath(),
                                                "-m",
                                                "-M",
                                                "true",
                                                "-G",
                                                "-N",
                                                "-c",
                                                "30",
                                                "-f",
                                                fileContexts.getAbsolutePath(),
                                                "-o",
                                                output.getAbsolutePath()),
                                        Stream.of(cilFiles)
                                                .filter(Objects::nonNull)
                                                .map(File::getAbsolutePath))
                                .toArray(String[]::new));
        assertTrue(errorString, errorString.length() == 0);
        return output;
    }

    private static String diff(String left, String right) {
        List<String> leftLines = Arrays.asList(left.split("\\r?\\n"));
        List<String> rightLines = Arrays.asList(right.split("\\r?\\n"));

        // Generate diff information.
        List<String> unifiedDiff =
                difflib.DiffUtils.generateUnifiedDiff(
                        "original",
                        "diff",
                        leftLines,
                        difflib.DiffUtils.diff(leftLines, rightLines),
                        /* contextSize= */ 0);
        StringBuilder stringBuilder = new StringBuilder();
        for (String delta : unifiedDiff) {
            stringBuilder.append(delta);
            stringBuilder.append("\n");
        }

        return stringBuilder.toString();
    }

    /**
     * Verifies the output of seamendc against the binary policy obtained by secilc-compiling the
     * CIL policies on the device. The binary policies must be the same.
     *
     * @throws Exception
     */
    @Test
    public void testSeamendcAgainstSecilc() throws Exception {
        File secilcOutWithApex =
                runSecilc(
                        mPlatPolicyCil,
                        mPlatCompatCil,
                        mSystemExtPolicyCil,
                        mSystemExtMappingCil,
                        mSystemExtCompatCil,
                        mProductPolicyCil,
                        mProductMappingCil,
                        mVendorPolicyCil,
                        mPlatPubVersionedCil,
                        mOdmPolicyCil,
                        mApexSepolicyCil);
        File secilcOutWithoutApex =
                runSecilc(
                        mPlatPolicyCil,
                        mPlatCompatCil,
                        mSystemExtPolicyCil,
                        mSystemExtMappingCil,
                        mSystemExtCompatCil,
                        mProductPolicyCil,
                        mProductMappingCil,
                        mVendorPolicyCil,
                        mPlatPubVersionedCil,
                        mOdmPolicyCil);
        File seamendcOutWithApex = runSeamendc(secilcOutWithoutApex, mApexSepolicyDecompiledCil);

        // system/sepolicy/com.android.sepolicy/33/shell.te
        assertSourceNotEqual(secilcOutWithoutApex, seamendcOutWithApex, "shell");
        assertTargetEqual(secilcOutWithoutApex, seamendcOutWithApex, "shell");
        assertSourceEqual(secilcOutWithApex, seamendcOutWithApex, "shell");
        assertTargetEqual(secilcOutWithApex, seamendcOutWithApex, "shell");
    }
}
