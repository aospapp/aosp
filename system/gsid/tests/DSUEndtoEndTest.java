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

package com.android.tests.dsu;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.build.IDeviceBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.util.ZipUtil2;

import org.apache.commons.compress.archivers.zip.ZipFile;
import org.junit.After;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.util.concurrent.TimeUnit;

/**
 * Test Dynamic System Updates by booting in and out of a supplied system image
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class DSUEndtoEndTest extends DsuTestBase {
    private static final long kDefaultUserdataSize = 4L * 1024 * 1024 * 1024;
    private static final String LPUNPACK_PATH = "bin/lpunpack";
    private static final String SIMG2IMG_PATH = "bin/simg2img";

    // Example: atest -v DSUEndtoEndTest -- --test-arg \
    // com.android.tradefed.testtype.HostTest:set-option:system_image_path:/full/path/to/system.img
    @Option(name="system_image_path",
            shortName='s',
            description="full path to the system image to use. If not specified, attempt " +
                        "to download the image from the test infrastructure",
            importance=Importance.ALWAYS)
    private String mSystemImagePath;

    private File mUnsparseSystemImage;

    @After
    public void teardown() throws Exception {
        if (mUnsparseSystemImage != null) {
            mUnsparseSystemImage.delete();
        }
    }

    @Test
    public void testDSU() throws Exception {
        String simg2imgPath = "simg2img";
        if (mSystemImagePath == null) {
            IBuildInfo buildInfo = getBuild();
            File imgs = ((IDeviceBuildInfo) buildInfo).getDeviceImageFile();
            Assert.assertNotEquals("Failed to fetch system image. See system_image_path parameter", null, imgs);
            File otaTools = buildInfo.getFile("otatools.zip");
            File tempdir = ZipUtil2.extractZipToTemp(otaTools, "otatools");
            File system = ZipUtil2.extractFileFromZip(new ZipFile(imgs), "system.img");
            if (system == null) {
                File superImg = ZipUtil2.extractFileFromZip(new ZipFile(imgs), "super.img");
                String lpunpackPath = new File(tempdir, LPUNPACK_PATH).getAbsolutePath();
                String outputDir = superImg.getParentFile().getAbsolutePath();
                String[] cmd = {lpunpackPath, "-p", "system_a", superImg.getAbsolutePath(), outputDir};
                Process p = Runtime.getRuntime().exec(cmd);
                p.waitFor();
                if (p.exitValue() == 0) {
                    mSystemImagePath = new File(outputDir, "system_a.img").getAbsolutePath();
                } else {
                    ByteArrayOutputStream stderr = new ByteArrayOutputStream();
                    int len;
                    byte[] buf = new byte[1024];
                    while ((len = p.getErrorStream().read(buf)) != -1) {
                          stderr.write(buf, 0, len);
                    }
                    Assert.assertEquals("non-zero exit value (" + stderr.toString("UTF-8") + ")", 0, p.exitValue());
                }
            } else {
                mSystemImagePath = system.getAbsolutePath();
            }
            simg2imgPath = new File(tempdir, SIMG2IMG_PATH).getAbsolutePath();
        }
        File gsi = new File(mSystemImagePath);
        Assert.assertTrue("not a valid file", gsi.isFile());
        String[] cmd = {simg2imgPath, mSystemImagePath, mSystemImagePath + ".raw"};
        Process p = Runtime.getRuntime().exec(cmd);
        p.waitFor();
        if (p.exitValue() == 0) {
            mUnsparseSystemImage = new File(mSystemImagePath + ".raw");
            gsi = mUnsparseSystemImage;
        }

        boolean wasRoot = getDevice().isAdbRoot();
        if (!wasRoot)
            Assert.assertTrue("Test requires root", getDevice().enableAdbRoot());

        assertDsuStatus("normal");

        // Sleep after installing to allow time for gsi_tool to reboot. This prevents a race between
        // the device rebooting and waitForDeviceAvailable() returning.
        getDevice()
                .executeShellV2Command(
                        String.format(
                                "gsi_tool install --userdata-size %d"
                                        + " --gsi-size %d"
                                        + " && sleep 10000000",
                                getDsuUserdataSize(kDefaultUserdataSize), gsi.length()),
                        gsi,
                        null,
                        10,
                        TimeUnit.MINUTES,
                        1);
        getDevice().waitForDeviceAvailable();
        getDevice().enableAdbRoot();

        assertDsuStatus("running");

        getDevice().rebootUntilOnline();

        assertDsuStatus("installed");

        assertShellCommand("gsi_tool enable");

        getDevice().reboot();

        assertDsuStatus("running");

        getDevice().reboot();

        assertDsuStatus("running");

        assertShellCommand("gsi_tool wipe");

        getDevice().rebootUntilOnline();

        assertDsuStatus("normal");

        if (wasRoot) {
            getDevice().enableAdbRoot();
        }
    }
}

