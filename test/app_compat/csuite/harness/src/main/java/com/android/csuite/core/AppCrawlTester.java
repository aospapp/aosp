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

package com.android.csuite.core;

import com.android.csuite.core.DeviceUtils.DeviceTimestamp;
import com.android.csuite.core.TestUtils.TestUtilsException;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;
import com.android.tradefed.util.ZipUtil;

import com.google.common.annotations.VisibleForTesting;
import com.google.common.base.Preconditions;
import com.google.common.io.MoreFiles;

import org.junit.Assert;

import java.io.File;
import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.annotation.Nullable;

/** A tester that interact with an app crawler during testing. */
public final class AppCrawlTester {
    @VisibleForTesting Path mOutput;
    private final RunUtilProvider mRunUtilProvider;
    private final TestUtils mTestUtils;
    private final String mPackageName;
    private boolean mRecordScreen = false;
    private boolean mCollectGmsVersion = false;
    private boolean mCollectAppVersion = false;
    private boolean mUiAutomatorMode = false;
    private int mTimeoutSec;
    private String mCrawlControllerEndpoint;
    private Path mApkRoot;
    private Path mRoboscriptFile;
    private Path mCrawlGuidanceProtoFile;
    private Path mLoginConfigDir;
    private FileSystem mFileSystem;

    /**
     * Creates an {@link AppCrawlTester} instance.
     *
     * @param packageName The package name of the apk files.
     * @param testInformation The TradeFed test information.
     * @param testLogData The TradeFed test output receiver.
     * @return an {@link AppCrawlTester} instance.
     */
    public static AppCrawlTester newInstance(
            String packageName, TestInformation testInformation, TestLogData testLogData) {
        return new AppCrawlTester(
                packageName,
                TestUtils.getInstance(testInformation, testLogData),
                () -> new RunUtil(),
                FileSystems.getDefault());
    }

    @VisibleForTesting
    AppCrawlTester(
            String packageName,
            TestUtils testUtils,
            RunUtilProvider runUtilProvider,
            FileSystem fileSystem) {
        mRunUtilProvider = runUtilProvider;
        mPackageName = packageName;
        mTestUtils = testUtils;
        mFileSystem = fileSystem;
    }

    /** An exception class representing crawler test failures. */
    public static final class CrawlerException extends Exception {
        /**
         * Constructs a new {@link CrawlerException} with a meaningful error message.
         *
         * @param message A error message describing the cause of the error.
         */
        private CrawlerException(String message) {
            super(message);
        }

        /**
         * Constructs a new {@link CrawlerException} with a meaningful error message, and a cause.
         *
         * @param message A detailed error message.
         * @param cause A {@link Throwable} capturing the original cause of the CrawlerException.
         */
        private CrawlerException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * Constructs a new {@link CrawlerException} with a cause.
         *
         * @param cause A {@link Throwable} capturing the original cause of the CrawlerException.
         */
        private CrawlerException(Throwable cause) {
            super(cause);
        }
    }

    /**
     * Starts crawling the app and throw AssertionError if app crash is detected.
     *
     * @throws DeviceNotAvailableException When device because unavailable.
     */
    public void startAndAssertAppNoCrash() throws DeviceNotAvailableException {
        DeviceTimestamp startTime = mTestUtils.getDeviceUtils().currentTimeMillis();

        CrawlerException crawlerException = null;
        try {
            start();
        } catch (CrawlerException e) {
            crawlerException = e;
        }

        ArrayList<String> failureMessages = new ArrayList<>();

        try {
            String dropboxCrashLog =
                    mTestUtils.getDropboxPackageCrashLog(mPackageName, startTime, true);
            if (dropboxCrashLog != null) {
                // Put dropbox crash log on the top of the failure messages.
                failureMessages.add(dropboxCrashLog);
            }
        } catch (IOException e) {
            failureMessages.add("Error while getting dropbox crash log: " + e.getMessage());
        }

        if (crawlerException != null) {
            failureMessages.add(crawlerException.getMessage());
        }

        Assert.assertTrue(
                String.join(
                        "\n============\n",
                        failureMessages.toArray(new String[failureMessages.size()])),
                failureMessages.isEmpty());
    }

    /**
     * Starts a crawler run on the configured app.
     *
     * @throws CrawlerException When the crawler was not set up correctly or the crawler run command
     *     failed.
     * @throws DeviceNotAvailableException When device because unavailable.
     */
    public void start() throws CrawlerException, DeviceNotAvailableException {
        if (!AppCrawlTesterHostPreparer.isReady(mTestUtils.getTestInformation())) {
            throw new CrawlerException(
                    "The "
                            + AppCrawlTesterHostPreparer.class.getName()
                            + " is not ready. Please check whether "
                            + AppCrawlTesterHostPreparer.class.getName()
                            + " was included in the test plan and completed successfully.");
        }

        if (mOutput != null) {
            throw new CrawlerException(
                    "The crawler has already run. Multiple runs in the same "
                            + AppCrawlTester.class.getName()
                            + " instance are not supported.");
        }

        try {
            mOutput = Files.createTempDirectory("crawler");
        } catch (IOException e) {
            throw new CrawlerException("Failed to create temp directory for output.", e);
        }

        IRunUtil runUtil = mRunUtilProvider.get();
        AtomicReference<String[]> command = new AtomicReference<>();
        AtomicReference<CommandResult> commandResult = new AtomicReference<>();

        CLog.d("Start to crawl package: %s.", mPackageName);

        Path bin =
                mFileSystem.getPath(
                        AppCrawlTesterHostPreparer.getCrawlerBinPath(
                                mTestUtils.getTestInformation()));
        boolean isUtpClient = false;
        if (Files.exists(bin.resolve("utp-cli-android_deploy.jar"))) {
            command.set(createUtpCrawlerRunCommand(mTestUtils.getTestInformation()));
            runUtil.setEnvVariable(
                    "ANDROID_SDK",
                    AppCrawlTesterHostPreparer.getSdkPath(mTestUtils.getTestInformation())
                            .toString());
            isUtpClient = true;
        } else if (Files.exists(bin.resolve("crawl_launcher_deploy.jar"))) {
            command.set(createCrawlerRunCommand(mTestUtils.getTestInformation()));
            runUtil.setEnvVariable(
                    "GOOGLE_APPLICATION_CREDENTIALS",
                    AppCrawlTesterHostPreparer.getCredentialPath(mTestUtils.getTestInformation())
                            .toString());
        } else {
            throw new CrawlerException(
                    "Crawler executable binaries not found in " + bin.toString());
        }

        if (mCollectGmsVersion) {
            mTestUtils.collectGmsVersion(mPackageName);
        }

        // Minimum timeout 3 minutes plus crawl test timeout.
        long commandTimeout = 3 * 60 * 1000 + mTimeoutSec * 1000;

        // TODO(yuexima): When the obb_file option is supported in espresso mode, the timeout need
        // to be extended.
        if (mRecordScreen) {
            mTestUtils.collectScreenRecord(
                    () -> {
                        commandResult.set(runUtil.runTimedCmd(commandTimeout, command.get()));
                    },
                    mPackageName);
        } else {
            commandResult.set(runUtil.runTimedCmd(commandTimeout, command.get()));
        }

        // Must be done after the crawler run because the app is installed by the crawler.
        if (mCollectAppVersion) {
            mTestUtils.collectAppVersion(mPackageName);
        }

        collectOutputZip();
        collectCrawlStepScreenshots(isUtpClient);

        if (!commandResult.get().getStatus().equals(CommandStatus.SUCCESS)
                || commandResult.get().getStdout().contains("Unknown options:")) {
            throw new CrawlerException("Crawler command failed: " + commandResult.get());
        }

        CLog.i("Completed crawling the package %s. Outputs: %s", mPackageName, commandResult.get());
    }

    /** Copys the step screenshots into test outputs for easier access. */
    private void collectCrawlStepScreenshots(boolean isUtpClient) {
        if (mOutput == null) {
            CLog.e("Output directory is not created yet. Skipping collecting step screenshots.");
            return;
        }

        Path subDir =
                isUtpClient
                        ? mOutput.resolve("output").resolve("artifacts")
                        : mOutput.resolve("app_firebase_test_lab");
        if (!Files.exists(subDir)) {
            CLog.e(
                    "The crawler output directory is not complete, skipping collecting step"
                            + " screenshots.");
            return;
        }

        try (Stream<Path> files = Files.list(subDir)) {
            files.filter(path -> path.getFileName().toString().toLowerCase().endsWith(".png"))
                    .forEach(
                            path -> {
                                mTestUtils
                                        .getTestArtifactReceiver()
                                        .addTestArtifact(
                                                mPackageName
                                                        + "-crawl_step_screenshot_"
                                                        + path.getFileName(),
                                                LogDataType.PNG,
                                                path.toFile());
                            });
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    /** Puts the zipped crawler output files into test output. */
    private void collectOutputZip() {
        if (mOutput == null) {
            CLog.e("Output directory is not created yet. Skipping collecting output.");
            return;
        }

        // Compress the crawler output directory and add it to test outputs.
        try {
            File outputZip = ZipUtil.createZip(mOutput.toFile());
            mTestUtils
                    .getTestArtifactReceiver()
                    .addTestArtifact(mPackageName + "-crawler_output", LogDataType.ZIP, outputZip);
        } catch (IOException e) {
            CLog.e("Failed to zip the output directory: " + e);
        }
    }

    @VisibleForTesting
    String[] createUtpCrawlerRunCommand(TestInformation testInfo) throws CrawlerException {

        Path bin =
                mFileSystem.getPath(
                        AppCrawlTesterHostPreparer.getCrawlerBinPath(
                                mTestUtils.getTestInformation()));
        ArrayList<String> cmd = new ArrayList<>();
        cmd.addAll(
                Arrays.asList(
                        "java",
                        "-jar",
                        bin.resolve("utp-cli-android_deploy.jar").toString(),
                        "android",
                        "robo",
                        "--device-id",
                        testInfo.getDevice().getSerialNumber(),
                        "--app-id",
                        mPackageName,
                        "--controller-endpoint",
                        "PROD",
                        "--utp-binaries-dir",
                        bin.toString(),
                        "--key-file",
                        AppCrawlTesterHostPreparer.getCredentialPath(
                                        mTestUtils.getTestInformation())
                                .toString(),
                        "--base-crawler-apk",
                        bin.resolve("crawler_app.apk").toString(),
                        "--stub-crawler-apk",
                        bin.resolve("crawler_stubapp_androidx.apk").toString(),
                        "--tmp-dir",
                        mOutput.toString()));

        if (mTimeoutSec > 0) {
            cmd.add("--crawler-flag");
            cmd.add("crawlDurationSec=" + Integer.toString(mTimeoutSec));
        }

        if (mUiAutomatorMode) {
            cmd.addAll(Arrays.asList("--ui-automator-mode", "--app-installed-on-device"));
        } else {
            Preconditions.checkNotNull(
                    mApkRoot, "Apk file path is required when not running in UIAutomator mode");

            List<Path> apks;
            try {
                apks =
                        TestUtils.listApks(mApkRoot).stream()
                                .filter(
                                        path ->
                                                path.getFileName()
                                                        .toString()
                                                        .toLowerCase()
                                                        .endsWith(".apk"))
                                .collect(Collectors.toList());
            } catch (TestUtilsException e) {
                throw new CrawlerException(e);
            }

            cmd.add("--apks-to-crawl");
            cmd.add(apks.stream().map(Path::toString).collect(Collectors.joining(",")));
        }

        if (mRoboscriptFile != null) {
            Assert.assertTrue(
                    "Please provide a valid roboscript file.",
                    Files.isRegularFile(mRoboscriptFile));
            cmd.add("--crawler-asset");
            cmd.add("robo.script=" + mRoboscriptFile.toString());
        }

        if (mCrawlGuidanceProtoFile != null) {
            Assert.assertTrue(
                    "Please provide a valid CrawlGuidance file.",
                    Files.isRegularFile(mCrawlGuidanceProtoFile));
            cmd.add("--crawl-guidance-proto-path");
            cmd.add(mCrawlGuidanceProtoFile.toString());
        }

        if (mLoginConfigDir != null) {
            RoboLoginConfigProvider configProvider = new RoboLoginConfigProvider(mLoginConfigDir);
            cmd.addAll(configProvider.findConfigFor(mPackageName, true).getLoginArgs());
        }

        return cmd.toArray(new String[cmd.size()]);
    }

    @VisibleForTesting
    String[] createCrawlerRunCommand(TestInformation testInfo) throws CrawlerException {

        Path bin =
                mFileSystem.getPath(
                        AppCrawlTesterHostPreparer.getCrawlerBinPath(
                                mTestUtils.getTestInformation()));
        ArrayList<String> cmd = new ArrayList<>();
        cmd.addAll(
                Arrays.asList(
                        "java",
                        "-jar",
                        bin.resolve("crawl_launcher_deploy.jar").toString(),
                        "--android-sdk-path",
                        AppCrawlTesterHostPreparer.getSdkPath(testInfo).toString(),
                        "--device-serial-code",
                        testInfo.getDevice().getSerialNumber(),
                        "--output-dir",
                        mOutput.toString(),
                        "--key-store-file",
                        // Using the publicly known default file name of the debug keystore.
                        bin.resolve("debug.keystore").toString(),
                        "--key-store-password",
                        // Using the publicly known default password of the debug keystore.
                        "android"));

        if (mCrawlControllerEndpoint != null && mCrawlControllerEndpoint.length() > 0) {
            cmd.addAll(Arrays.asList("--endpoint", mCrawlControllerEndpoint));
        }

        if (mUiAutomatorMode) {
            cmd.addAll(Arrays.asList("--ui-automator-mode", "--app-package-name", mPackageName));
        } else {
            Preconditions.checkNotNull(
                    mApkRoot, "Apk file path is required when not running in UIAutomator mode");

            List<Path> apks;
            try {
                apks =
                        TestUtils.listApks(mApkRoot).stream()
                                .filter(
                                        path ->
                                                path.getFileName()
                                                        .toString()
                                                        .toLowerCase()
                                                        .endsWith(".apk"))
                                .collect(Collectors.toList());
            } catch (TestUtilsException e) {
                throw new CrawlerException(e);
            }

            cmd.add("--apk-file");
            cmd.add(apks.get(0).toString());

            for (int i = 1; i < apks.size(); i++) {
                cmd.add("--split-apk-files");
                cmd.add(apks.get(i).toString());
            }
        }

        if (mTimeoutSec > 0) {
            cmd.add("--timeout-sec");
            cmd.add(Integer.toString(mTimeoutSec));
        }

        if (mRoboscriptFile != null) {
            Assert.assertTrue(
                    "Please provide a valid roboscript file.",
                    Files.isRegularFile(mRoboscriptFile));
            cmd.addAll(Arrays.asList("--robo-script-file", mRoboscriptFile.toString()));
        }

        if (mCrawlGuidanceProtoFile != null) {
            Assert.assertTrue(
                    "Please provide a valid CrawlGuidance file.",
                    Files.isRegularFile(mCrawlGuidanceProtoFile));
            cmd.addAll(Arrays.asList("--text-guide-file", mCrawlGuidanceProtoFile.toString()));
        }

        if (mLoginConfigDir != null) {
            RoboLoginConfigProvider configProvider = new RoboLoginConfigProvider(mLoginConfigDir);
            cmd.addAll(configProvider.findConfigFor(mPackageName, false).getLoginArgs());
        }

        return cmd.toArray(new String[cmd.size()]);
    }

    /** Cleans up the crawler output directory. */
    public void cleanUp() {
        if (mOutput == null) {
            return;
        }

        try {
            MoreFiles.deleteRecursively(mOutput);
        } catch (IOException e) {
            CLog.e("Failed to clean up the crawler output directory: " + e);
        }
    }

    /** Sets the option of whether to record the device screen during crawling. */
    public void setRecordScreen(boolean recordScreen) {
        mRecordScreen = recordScreen;
    }

    /** Sets the option of whether to collect GMS version in test artifacts. */
    public void setCollectGmsVersion(boolean collectGmsVersion) {
        mCollectGmsVersion = collectGmsVersion;
    }

    /** Sets the option of whether to collect the app version in test artifacts. */
    public void setCollectAppVersion(boolean collectAppVersion) {
        mCollectAppVersion = collectAppVersion;
    }

    /** Sets the option of whether to run the crawler with UIAutomator mode. */
    public void setUiAutomatorMode(boolean uiAutomatorMode) {
        mUiAutomatorMode = uiAutomatorMode;
    }

    /** Sets the value of the "timeout-sec" param for the crawler launcher. */
    public void setTimeoutSec(int timeoutSec) {
        mTimeoutSec = timeoutSec;
    }

    /** Sets the robo crawler controller endpoint (optional). */
    public void setCrawlControllerEndpoint(String crawlControllerEndpoint) {
        mCrawlControllerEndpoint = crawlControllerEndpoint;
    }

    /**
     * Sets the apk file path. Required when not running in UIAutomator mode.
     *
     * @param apkRoot The root path for an apk or a directory that contains apk files for a package.
     */
    public void setApkPath(Path apkRoot) {
        mApkRoot = apkRoot;
    }

    /**
     * Sets the option of the Roboscript file to be used by the crawler. Null can be passed to
     * remove the reference to the file.
     */
    public void setRoboscriptFile(@Nullable Path roboscriptFile) {
        mRoboscriptFile = roboscriptFile;
    }

    /**
     * Sets the option of the CrawlGuidance file to be used by the crawler. Null can be passed to
     * remove the reference to the file.
     */
    public void setCrawlGuidanceProtoFile(@Nullable Path crawlGuidanceProtoFile) {
        mCrawlGuidanceProtoFile = crawlGuidanceProtoFile;
    }

    /** Sets the option of the directory that contains configuration for login. */
    public void setLoginConfigDir(@Nullable Path loginFilesDir) {
        mLoginConfigDir = loginFilesDir;
    }

    @VisibleForTesting
    interface RunUtilProvider {
        IRunUtil get();
    }
}
