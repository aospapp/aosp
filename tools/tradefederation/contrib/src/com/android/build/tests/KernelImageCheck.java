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

package com.android.build.tests;

import static org.junit.Assert.assertEquals;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.RunUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.File;
import java.io.IOException;

/** A device-less test that test kernel image */
@OptionClass(alias = "kernel-image-check")
@RunWith(DeviceJUnit4ClassRunner.class)
public class KernelImageCheck extends BaseHostJUnit4Test {

    private static final String KERNEL_IMAGE_NAME = "vmlinux";
    private static final int CMD_TIMEOUT = 1000000;

    @Option(
        name = "kernel-image-check-tool",
        description = "The file path of kernel image check tool (mandatory)",
        mandatory = true
    )
    private File mKernelImageCheckTool = null;

    @Option(
        name = "kernel-image-name",
        description = "The file name of the kernel image. Default: vmlinux"
    )
    private String mKernelImageName = KERNEL_IMAGE_NAME;

    @Option(
        name = "kernel-image-alt-path",
        description = "The kernel image alternative path string"
    )
    private String mKernelImageAltPath = null;

    @Option(
        name = "kernel-abi-file",
        description = "The file path of kernel ABI file",
        mandatory = true
    )
    private File mKernelAbiFile = null;

    private IBuildInfo mBuildInfo;
    private File mKernelImageFile = null;

    @Before
    public void setUp() throws Exception {
        if (!mKernelImageCheckTool.exists()) {
            throw new IOException("Cannot find kernel image tool at: " + mKernelImageCheckTool);
        }
        if (!mKernelAbiFile.exists()) {
            throw new IOException("Cannot find kernel ABI representation at: " + mKernelAbiFile);
        }
        // First try to get kernel image from BuildInfo
        mKernelImageFile = getBuild().getFile(mKernelImageName);
        if (mKernelImageFile == null || !mKernelImageFile.exists()) {
            // Then check within alternative path.
            File imageDir = new File(mKernelImageAltPath);
            if (imageDir.isDirectory()) {
                mKernelImageFile = new File(imageDir, mKernelImageName);
            }
        }

        if (mKernelImageFile == null || !mKernelImageFile.exists()) {
            throw new RuntimeException("Cannot find kernel image file: " + mKernelImageName);
        }
    }

    /** Test that kernel ABI is not different from the given ABI representation */
    @Test
    public void test_stable_abi() throws Exception {
        // Generate kernel ABI
        String[] cmd =
                new String[] {
                    mKernelImageCheckTool.getAbsolutePath() + "/abidw",
                    "--linux-tree",
                    mKernelImageFile.getParent(),
                    "--out-file",
                    "abi-new.out"
                };
        CommandResult result = RunUtil.getDefault().runTimedCmd(CMD_TIMEOUT, cmd);
        CLog.i("Result stdout: %s", result.getStdout());
        // TODO: differentiate non-zero exit codes.
        if (result.getExitCode() != 0) {
            CLog.e("Result stderr: %s", result.getStderr());
            CLog.e("Result exit code: %d", result.getExitCode());
        }
        assertEquals(CommandStatus.SUCCESS, result.getStatus());

        // Diff kernel ABI with the given ABI file
        cmd =
                new String[] {
                    mKernelImageCheckTool.getAbsolutePath() + "/abidiff",
                    "abi-new.out",
                    mKernelAbiFile.getAbsolutePath()
                };
        result = RunUtil.getDefault().runTimedCmd(CMD_TIMEOUT, cmd);
        CLog.i("Result stdout: %s", result.getStdout());
        if (result.getExitCode() != 0) {
            CLog.e("Result stderr: %s", result.getStderr());
            CLog.e("Result exit code: %d", result.getExitCode());
        }
        assertEquals(CommandStatus.SUCCESS, result.getStatus());
    }
}
