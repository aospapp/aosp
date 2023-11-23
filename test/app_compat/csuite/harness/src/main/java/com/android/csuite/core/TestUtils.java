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
import com.android.csuite.core.DeviceUtils.DropboxEntry;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.testtype.DeviceJUnit4ClassRunner.TestLogData;
import com.android.tradefed.util.ZipUtil;

import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.function.BiFunction;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/** A utility class that contains common methods used by tests. */
public class TestUtils {
    private static final String GMS_PACKAGE_NAME = "com.google.android.gms";
    private final TestInformation mTestInformation;
    private final TestArtifactReceiver mTestArtifactReceiver;
    private final DeviceUtils mDeviceUtils;
    private static final int MAX_CRASH_SNIPPET_LINES = 60;
    // Pattern for finding a package name following one of the tags such as "Process:" or
    // "Package:".
    private static final Pattern DROPBOX_PACKAGE_NAME_PATTERN =
            Pattern.compile(
                    "(Process|Cmdline|Package|Cmd line):("
                            + " *)([a-zA-Z][a-zA-Z0-9_]*(\\.[a-zA-Z][a-zA-Z0-9_]*)+)");

    public enum TakeEffectWhen {
        NEVER,
        ON_FAIL,
        ON_PASS,
        ALWAYS,
    }

    public static TestUtils getInstance(TestInformation testInformation, TestLogData testLogData) {
        return new TestUtils(
                testInformation,
                new TestLogDataTestArtifactReceiver(testLogData),
                DeviceUtils.getInstance(testInformation.getDevice()));
    }

    public static TestUtils getInstance(
            TestInformation testInformation, TestArtifactReceiver testArtifactReceiver) {
        return new TestUtils(
                testInformation,
                testArtifactReceiver,
                DeviceUtils.getInstance(testInformation.getDevice()));
    }

    @VisibleForTesting
    TestUtils(
            TestInformation testInformation,
            TestArtifactReceiver testArtifactReceiver,
            DeviceUtils deviceUtils) {
        mTestInformation = testInformation;
        mTestArtifactReceiver = testArtifactReceiver;
        mDeviceUtils = deviceUtils;
    }

    /**
     * Take a screenshot on the device and save it to the test result artifacts.
     *
     * @param prefix The file name prefix.
     * @throws DeviceNotAvailableException
     */
    public void collectScreenshot(String prefix) throws DeviceNotAvailableException {
        try (InputStreamSource screenSource = mTestInformation.getDevice().getScreenshot()) {
            mTestArtifactReceiver.addTestArtifact(
                    prefix + "_screenshot_" + mTestInformation.getDevice().getSerialNumber(),
                    LogDataType.PNG,
                    screenSource);
        }
    }

    /**
     * Record the device screen while running a task and save the video file to the test result
     * artifacts.
     *
     * @param job A job to run while recording the screen.
     * @param prefix The file name prefix.
     * @throws DeviceNotAvailableException
     */
    public void collectScreenRecord(
            DeviceUtils.RunnableThrowingDeviceNotAvailable job, String prefix)
            throws DeviceNotAvailableException {
        mDeviceUtils.runWithScreenRecording(
                job,
                video -> {
                    if (video != null) {
                        mTestArtifactReceiver.addTestArtifact(
                                prefix
                                        + "_screenrecord_"
                                        + mTestInformation.getDevice().getSerialNumber(),
                                LogDataType.MP4,
                                video);
                    } else {
                        CLog.e("Failed to get screen recording.");
                    }
                });
    }

    /**
     * Saves test APK files when conditions on the test result is met.
     *
     * @param when Conditions to save the apks based on the test result.
     * @param testPassed The test result.
     * @param prefix Output file name prefix
     * @param apks A list of files that can be files, directories, or a mix of both.
     * @return true if apk files are saved as artifacts. False otherwise.
     */
    public boolean saveApks(
            TakeEffectWhen when, boolean testPassed, String prefix, List<File> apks) {
        if (apks.isEmpty() || when == TakeEffectWhen.NEVER) {
            return false;
        }

        if ((when == TakeEffectWhen.ON_FAIL && testPassed)
                || (when == TakeEffectWhen.ON_PASS && !testPassed)) {
            return false;
        }

        try {
            File outputZip = ZipUtil.createZip(apks);
            getTestArtifactReceiver().addTestArtifact(prefix + "-apks", LogDataType.ZIP, outputZip);
            return true;
        } catch (IOException e) {
            CLog.e("Failed to zip the apks: " + e);
        }

        return false;
    }

    /**
     * Collect the GMS version name and version code, and save them as test result artifacts.
     *
     * @param prefix The file name prefix.
     * @throws DeviceNotAvailableException
     */
    public void collectGmsVersion(String prefix) throws DeviceNotAvailableException {
        String gmsVersionCode = mDeviceUtils.getPackageVersionCode(GMS_PACKAGE_NAME);
        String gmsVersionName = mDeviceUtils.getPackageVersionName(GMS_PACKAGE_NAME);
        CLog.i("GMS core versionCode=%s, versionName=%s", gmsVersionCode, gmsVersionName);

        // Note: If the file name format needs to be modified, do it with cautions as some users may
        // be parsing the output file name to get the version information.
        mTestArtifactReceiver.addTestArtifact(
                String.format("%s_[GMS_versionCode=%s]", prefix, gmsVersionCode),
                LogDataType.TEXT,
                gmsVersionCode.getBytes());
        mTestArtifactReceiver.addTestArtifact(
                String.format("%s_[GMS_versionName=%s]", prefix, gmsVersionName),
                LogDataType.TEXT,
                gmsVersionName.getBytes());
    }

    /**
     * Collect the given package's version name and version code, and save them as test result
     * artifacts.
     *
     * @param packageName The package name.
     * @throws DeviceNotAvailableException
     */
    public void collectAppVersion(String packageName) throws DeviceNotAvailableException {
        String versionCode = mDeviceUtils.getPackageVersionCode(packageName);
        String versionName = mDeviceUtils.getPackageVersionName(packageName);
        CLog.i("Package %s versionCode=%s, versionName=%s", packageName, versionCode, versionName);

        // Note: If the file name format needs to be modified, do it with cautions as some users may
        // be parsing the output file name to get the version information.
        mTestArtifactReceiver.addTestArtifact(
                String.format("%s_[versionCode=%s]", packageName, versionCode),
                LogDataType.TEXT,
                versionCode.getBytes());
        mTestArtifactReceiver.addTestArtifact(
                String.format("%s_[versionName=%s]", packageName, versionName),
                LogDataType.TEXT,
                versionName.getBytes());
    }

    /**
     * Looks for crash log of a package in the device's dropbox entries.
     *
     * @param packageName The package name of an app.
     * @param startTimeOnDevice The device timestamp after which the check starts. Dropbox items
     *     before this device timestamp will be ignored.
     * @param saveToFile whether to save the package's full dropbox crash logs to a test output
     *     file.
     * @return A string of crash log if crash was found; null otherwise.
     * @throws IOException unexpected IOException
     */
    public String getDropboxPackageCrashLog(
            String packageName, DeviceTimestamp startTimeOnDevice, boolean saveToFile)
            throws IOException {
        BiFunction<String, Integer, String> truncate =
                (text, maxLines) -> {
                    String[] lines = text.split("\\r?\\n");
                    StringBuilder sb = new StringBuilder();
                    for (int i = 0; i < maxLines && i < lines.length; i++) {
                        sb.append(lines[i]);
                        sb.append('\n');
                    }
                    if (lines.length > maxLines) {
                        sb.append("... ");
                        sb.append(lines.length - maxLines);
                        sb.append(" more lines truncated ...\n");
                    }
                    return sb.toString();
                };

        List<DropboxEntry> entries =
                mDeviceUtils.getDropboxEntries(DeviceUtils.DROPBOX_APP_CRASH_TAGS).stream()
                        .filter(entry -> (entry.getTime() >= startTimeOnDevice.get()))
                        .filter(
                                entry ->
                                        isDropboxEntryFromPackageProcess(
                                                entry.getData(), packageName))
                        .collect(Collectors.toList());

        if (entries.size() == 0) {
            return null;
        }

        String fullText =
                entries.stream()
                        .map(
                                entry ->
                                        String.format(
                                                "Dropbox tag: %s\n%s",
                                                entry.getTag(), entry.getData()))
                        .collect(Collectors.joining("\n============\n"));
        String truncatedText =
                entries.stream()
                        .map(
                                entry ->
                                        String.format(
                                                "Dropbox tag: %s\n%s",
                                                entry.getTag(),
                                                truncate.apply(
                                                        entry.getData(), MAX_CRASH_SNIPPET_LINES)))
                        .collect(Collectors.joining("\n============\n"));

        mTestArtifactReceiver.addTestArtifact(
                String.format("%s_dropbox_entries", packageName),
                LogDataType.TEXT,
                fullText.getBytes());
        return truncatedText;
    }

    @VisibleForTesting
    boolean isDropboxEntryFromPackageProcess(String entryData, String packageName) {
        Matcher m = DROPBOX_PACKAGE_NAME_PATTERN.matcher(entryData);

        boolean matched = false;
        while (m.find()) {
            matched = true;
            if (m.group(3).equals(packageName)) {
                return true;
            }
        }

        if (matched) {
            return false;
        }

        // If the process name is not identified, fall back to checking if the package name is
        // present in the entry. This is because the process name detection logic above does not
        // guarantee to identify the process name.
        return Pattern.compile(
                        String.format(
                                // Pattern for checking whether a given package name exists.
                                "(.*(?:[^a-zA-Z0-9_\\.]+)|^)%s((?:[^a-zA-Z0-9_\\.]+).*|$)",
                                packageName.replaceAll("\\.", "\\\\.")))
                .matcher(entryData)
                .find();
    }

    /**
     * Generates a list of APK paths where the base.apk of split apk files are always on the first
     * index if exists.
     *
     * <p>If the input path points to a single apk file, then the same path is returned. If the
     * input path is a directory containing only one non-split apk file, the apk file path is
     * returned. If the apk path is a directory containing split apk files for one package, then the
     * list of apks are returned and the base.apk sits on the first index. If the path contains obb
     * files, then they will be included at the end of the returned path list. If the apk path does
     * not contain any apk files, or multiple apk files without base.apk, then an IOException is
     * thrown.
     *
     * @return A list of APK paths with OBB files if available.
     * @throws TestUtilsException If failed to read the apk path or unexpected number of apk files
     *     are found under the path.
     */
    public static List<Path> listApks(Path root) throws TestUtilsException {
        // The apk path points to a non-split apk file.
        if (Files.isRegularFile(root)) {
            if (!root.toString().endsWith(".apk")) {
                throw new TestUtilsException(
                        "The file on the given apk path is not an apk file: " + root);
            }
            return List.of(root);
        }

        List<Path> apksAndObbs;
        CLog.d("APK path = " + root);
        try (Stream<Path> fileTree = Files.walk(root)) {
            apksAndObbs =
                    fileTree.filter(Files::isRegularFile)
                            .filter(
                                    path ->
                                            path.getFileName()
                                                            .toString()
                                                            .toLowerCase()
                                                            .endsWith(".apk")
                                                    || path.getFileName()
                                                            .toString()
                                                            .toLowerCase()
                                                            .endsWith(".obb"))
                            .collect(Collectors.toList());
        } catch (IOException e) {
            throw new TestUtilsException("Failed to list apk files.", e);
        }

        List<Path> apkFiles =
                apksAndObbs.stream()
                        .filter(path -> path.getFileName().toString().endsWith(".apk"))
                        .collect(Collectors.toList());

        if (apkFiles.isEmpty()) {
            throw new TestUtilsException(
                    "Empty APK directory. Cannot find any APK files under " + root);
        }

        if (apkFiles.stream().map(path -> path.getParent().toString()).distinct().count() != 1) {
            throw new TestUtilsException(
                    "Apk files are not all in the same folder: "
                            + Arrays.deepToString(
                                    apksAndObbs.toArray(new Path[apksAndObbs.size()])));
        }

        if (apkFiles.size() > 1
                && apkFiles.stream()
                                .filter(path -> path.getFileName().toString().equals("base.apk"))
                                .count()
                        == 0) {
            throw new TestUtilsException(
                    "Base apk is not found: "
                            + Arrays.deepToString(
                                    apksAndObbs.toArray(new Path[apksAndObbs.size()])));
        }

        if (apksAndObbs.stream()
                        .filter(
                                path ->
                                        path.getFileName().toString().endsWith(".obb")
                                                && path.getFileName().toString().startsWith("main"))
                        .count()
                > 1) {
            throw new TestUtilsException(
                    "Multiple main obb files are found: "
                            + Arrays.deepToString(
                                    apksAndObbs.toArray(new Path[apksAndObbs.size()])));
        }

        Collections.sort(
                apksAndObbs,
                (first, second) -> {
                    if (first.getFileName().toString().equals("base.apk")) {
                        return -1;
                    } else if (first.getFileName().toString().toLowerCase().endsWith(".obb")) {
                        return 1;
                    } else {
                        return first.getFileName().compareTo(second.getFileName());
                    }
                });

        return apksAndObbs;
    }

    /** Returns the test information. */
    public TestInformation getTestInformation() {
        return mTestInformation;
    }

    /** Returns the test artifact receiver. */
    public TestArtifactReceiver getTestArtifactReceiver() {
        return mTestArtifactReceiver;
    }

    /** Returns the device utils. */
    public DeviceUtils getDeviceUtils() {
        return mDeviceUtils;
    }

    /** An exception class representing exceptions thrown from the test utils. */
    public static final class TestUtilsException extends Exception {
        /**
         * Constructs a new {@link TestUtilsException} with a meaningful error message.
         *
         * @param message A error message describing the cause of the error.
         */
        private TestUtilsException(String message) {
            super(message);
        }

        /**
         * Constructs a new {@link TestUtilsException} with a meaningful error message, and a cause.
         *
         * @param message A detailed error message.
         * @param cause A {@link Throwable} capturing the original cause of the TestUtilsException.
         */
        private TestUtilsException(String message, Throwable cause) {
            super(message, cause);
        }

        /**
         * Constructs a new {@link TestUtilsException} with a cause.
         *
         * @param cause A {@link Throwable} capturing the original cause of the TestUtilsException.
         */
        private TestUtilsException(Throwable cause) {
            super(cause);
        }
    }

    public static class TestLogDataTestArtifactReceiver implements TestArtifactReceiver {
        @SuppressWarnings("hiding")
        private final TestLogData mTestLogData;

        public TestLogDataTestArtifactReceiver(TestLogData testLogData) {
            mTestLogData = testLogData;
        }

        @Override
        public void addTestArtifact(String name, LogDataType type, byte[] bytes) {
            mTestLogData.addTestLog(name, type, new ByteArrayInputStreamSource(bytes));
        }

        @Override
        public void addTestArtifact(String name, LogDataType type, File file) {
            mTestLogData.addTestLog(name, type, new FileInputStreamSource(file));
        }

        @Override
        public void addTestArtifact(String name, LogDataType type, InputStreamSource source) {
            mTestLogData.addTestLog(name, type, source);
        }
    }

    public interface TestArtifactReceiver {

        /**
         * Add a test artifact.
         *
         * @param name File name.
         * @param type Output data type.
         * @param bytes The output data.
         */
        void addTestArtifact(String name, LogDataType type, byte[] bytes);

        /**
         * Add a test artifact.
         *
         * @param name File name.
         * @param type Output data type.
         * @param inputStreamSource The inputStreamSource.
         */
        void addTestArtifact(String name, LogDataType type, InputStreamSource inputStreamSource);

        /**
         * Add a test artifact.
         *
         * @param name File name.
         * @param type Output data type.
         * @param file The output file.
         */
        void addTestArtifact(String name, LogDataType type, File file);
    }
}
