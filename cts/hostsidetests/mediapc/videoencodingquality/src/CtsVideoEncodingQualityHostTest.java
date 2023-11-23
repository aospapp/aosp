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
package android.videoencodingquality.cts;

import com.android.compatibility.common.util.CddTest;
import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IAbiReceiver;
import com.android.tradefed.testtype.IBuildReceiver;
import com.android.tradefed.testtype.IDeviceTest;

import org.junit.Assert;
import org.junit.Assume;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.concurrent.TimeUnit;

/**
 * Run the host-side video encoding quality test (go/pc14-veq)
 * The body of this test is implemented in a test script, not within the java here. This java
 * code acquires the testsuite tar file, unpacks it, executes the script (which encodes and
 * measures) that report either PASS or FAIL.
 **/
@RunWith(DeviceJUnit4ClassRunner.class)
@OptionClass(alias = "pc-veq-test")
public class CtsVideoEncodingQualityHostTest implements IAbiReceiver, IBuildReceiver, IDeviceTest {

    static final String BASE_URL =
            "https://storage.googleapis.com/android_media/cts/hostsidetests/pc14_veq/";

    @Option(name = "force-to-run",
            description = "Force to run the test even if the device is not a right performance "
                    + "class device.")
    private boolean mForceToRun = false;

    @Option(name = "disable-b", description = "Disable b-frame-encoding.")
    private boolean mDisableB = false;

    @Option(name = "reset", description = "Start with a fresh directory.")
    private boolean mReset = true;

    @Option(name = "quick-check", description = "Run a quick check.")
    private boolean mQuickCheck = false;

    // test is not valid before sdk 31, aka Android 12, aka Android S
    static final int MINIMUM_VALID_SDK = 31;

    // media performance class 14
    static final int MEDIA_PERFORMANCE_CLASS_14 = 34;

    /** A reference to the build info. */
    private IBuildInfo mBuildInfo;

    /** A reference to the device under test. */
    private ITestDevice mDevice;

    /** A reference to the ABI under test. */
    private IAbi mAbi;

    @Override
    public void setAbi(IAbi abi) {
        mAbi = abi;
    }

    @Override
    public void setBuild(IBuildInfo buildInfo) {
        mBuildInfo = buildInfo;
    }

    @Override
    public void setDevice(ITestDevice device) {
        mDevice = device;
    }

    @Override
    public ITestDevice getDevice() {
        return mDevice;
    }

    private String getProperty(String prop) throws Exception {
        return mDevice.executeShellCommand("getprop " + prop).replace("\n", "");
    }

    /**
     * TODO: Add JavaDoc
     */
    /**
     * Verify the video encoding quality requirements for the performance class 14 devices.
     */
    @CddTest(requirements = {"2.2.7.1/5.8/H-1-1"})
    @Test
    public void testEncoding() throws Exception {
        String sdkAsString = getProperty("ro.build.version.sdk");
        int sdk = Integer.parseInt(sdkAsString);
        Assume.assumeTrue(
                "Test requires sdk >= " + MINIMUM_VALID_SDK + " test device has sdk = " + sdk,
                sdk >= MINIMUM_VALID_SDK);

        String os = System.getProperty("os.name").toLowerCase();
        LogUtil.CLog.i("Host OS = " + os);

        String pcAsString = getProperty("ro.odm.build.media_performance_class");
        int mpc = 0;
        try {
            mpc = Integer.parseInt("0" + pcAsString);
        } catch (Exception e) {
            LogUtil.CLog.i("Invalid pcAsString: " + pcAsString + ", exception: " + e);
            mpc = 0;
        }

        // Enable early termination on errors on the devices whose mpc's are not valid.
        // Run the entire test til the end on the devices whose mpc's are valid.
        boolean earlyTermination = mpc < MEDIA_PERFORMANCE_CLASS_14;
        if (mForceToRun) {
            earlyTermination = false;       // Force to run the test til the end.
        }

        String targetSerial = getDevice().getSerialNumber();
        LogUtil.CLog.i("serial:\n\n" + targetSerial);

        String tmpBase = System.getProperty("java.io.tmpdir");
        String dirName = "CtsVideoEncodingQualityHostTest_" + targetSerial;
        String tmpDir = tmpBase + "/" + dirName;

        LogUtil.CLog.i("tmpBase= " + tmpBase + " tmpDir =" + tmpDir);

        if (mReset) {
            // start with a fresh directory
            File cwd = new File(".");
            runCommand("rm -fr " + tmpDir, cwd);
        }

        // set up test directory, make sure it exists
        File destination = new File(tmpDir);
        try {
            if (!destination.isDirectory()) {
                destination.mkdirs();
            }
        } catch (SecurityException e) {
            LogUtil.CLog.e("Unable to establish temp directory " + destination.getPath());
        }
        Assert.assertTrue("Failed to create test director: " + tmpDir, destination.isDirectory());

        // Download the testsuit tar file.
        downloadFile("veqtests.tar.gz", destination);

        // Unpack the testsuit tar file.
        int result = runCommand("tar xvzf veqtests.tar.gz", destination);
        Assert.assertTrue("Failed to untar veqtests.tar.gz", result == 0);

        // Execute the script to run the test.
        String testCommand = "./testit.sh --serial " + targetSerial;
        if (mQuickCheck) testCommand += " --enablequickrun YES";
        if (mDisableB) testCommand += " --enableb NO";
        if (earlyTermination) testCommand += " --exitonerror YES";
        result = runCommand(testCommand, destination);

        if (mpc >= MEDIA_PERFORMANCE_CLASS_14 || mForceToRun) {
            Assert.assertTrue(
                    "test device advertises mpc=" + mpc
                            + ", but failed to pass the video encoding quality test.",
                    result == 0);
        } else {
            Assume.assumeTrue(
                    "test device advertises mpc=" + mpc
                            + ", and did not pass the video encoding quality test.",
                    result == 0);
        }

        LogUtil.CLog.i("Finished executing " + testCommand);
    }

    private int runCommand(String cmd, File dir) throws IOException, InterruptedException {
        Process process = Runtime.getRuntime().exec(cmd, null, dir);

        BufferedReader stdInput =
                new BufferedReader(new InputStreamReader(process.getInputStream()));
        BufferedReader stdError =
                new BufferedReader(new InputStreamReader(process.getErrorStream()));
        String line = "";
        boolean isOutReady = false;
        boolean isErrorReady = false;
        boolean isProcessAlive = false;

        while (process.isAlive()) {
            do {
                isOutReady = stdInput.ready();

                if (isOutReady) {
                    line = stdInput.readLine();
                    LogUtil.CLog.i("== " + line + "\n");
                }

                isErrorReady = stdError.ready();
                if (isErrorReady) {
                    line = stdError.readLine();
                    LogUtil.CLog.i("xx " + line + "\n");
                }

                isProcessAlive = process.isAlive();
                if (!isProcessAlive) {
                    LogUtil.CLog.i("::Process DIED! " + line + "\n");
                    line = null;
                    process.waitFor(1000, TimeUnit.MILLISECONDS);
                }

            } while (line != null);

            process.waitFor(100, TimeUnit.MILLISECONDS);
        }

        return process.exitValue();
    }

    // Download the indicated file (within the base_url folder) to
    // our desired destination/fileName.
    // simple caching -- if file exists, we do not redownload
    private void downloadFile(String fileName, File destDir) {
        File destination = new File(destDir, fileName);

        // save bandwidth, also allows a user to manually preload files
        LogUtil.CLog.i("Do we already have a copy of file " + destination.getPath());
        if (destination.isFile()) {
            LogUtil.CLog.i("Skipping re-download of file " + destination.getPath());
            return;
        }

        String url = BASE_URL + fileName;
        String cmd = "wget -O " + destination.getPath() + " " + url;
        LogUtil.CLog.i("wget_cmd = " + cmd);

        int result = 0;

        try {
            result = runCommand(cmd, destDir);
        } catch (IOException e) {
            result = -2;
        } catch (InterruptedException e) {
            result = -3;
        }
        Assert.assertTrue("downloadFile failed.\n", result == 0);
    }
}
