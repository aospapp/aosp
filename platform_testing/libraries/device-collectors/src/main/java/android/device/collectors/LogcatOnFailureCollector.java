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
package android.device.collectors;

import android.device.collectors.annotations.OptionClass;
import android.os.Bundle;
import android.util.Log;

import androidx.annotation.VisibleForTesting;

import org.junit.runner.Description;
import org.junit.runner.notification.Failure;

import java.io.File;
import java.io.IOException;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.text.SimpleDateFormat;

/**
 * A {@link BaseMetricListener} that captures logcat after each test case failure.
 *
 * This class needs external storage permission. See {@link BaseMetricListener} how to grant
 * external storage permission, especially at install time.
 *
 */
@OptionClass(alias = "logcat-failure-collector")
public class LogcatOnFailureCollector extends BaseMetricListener {
    @VisibleForTesting
    static final SimpleDateFormat DATE_FORMATTER = new SimpleDateFormat("MM-dd HH:mm:ss.SSS");

    @VisibleForTesting static final String METRIC_SEP = "-";
    @VisibleForTesting static final String FILENAME_SUFFIX = "logcat";

    public static final String DEFAULT_DIR = "run_listeners/logcats";
    private static final int BUFFER_SIZE = 16 * 1024;

    private File mDestDir;
    private String mStartTime = null;
    private boolean mTestFailed = false;

    // Map to keep track of test iterations for multiple test iterations.
    private HashMap<Description, Integer> mTestIterations = new HashMap<>();

    public LogcatOnFailureCollector() {
        super();
    }

    /**
     * Constructor to simulate receiving the instrumentation arguments. Should not be used except
     * for testing.
     */
    @VisibleForTesting
    LogcatOnFailureCollector(Bundle args) {
        super(args);
    }

    @Override
    public void onTestRunStart(DataRecord runData, Description description) {
        mDestDir = createAndEmptyDirectory(DEFAULT_DIR);
        // Capture the start time in case onTestStart() is never called due to failure during
        // @BeforeClass.
        mStartTime = getCurrentDate();
    }

    @Override
    public void onTestStart(DataRecord testData, Description description) {
        // Capture the start time for logcat purpose.
        // Overwrites any start time set prior to the test.
        mStartTime = getCurrentDate();
        // Keep track of test iterations.
        mTestIterations.computeIfPresent(description, (desc, iteration) -> iteration + 1);
        mTestIterations.computeIfAbsent(description, desc -> 1);
    }

    /**
     * Mark the test as failed if this is called. The actual collection will be done in {@link
     * onTestEnd} to ensure that all actions around a test failure end up in the logcat.
     */
    @Override
    public void onTestFail(DataRecord testData, Description description, Failure failure) {
        mTestFailed = true;
    }

    /** If the test fails, collect logcat since test start time. */
    @Override
    public void onTestEnd(DataRecord testData, Description description) {
        if (mTestFailed) {
            // Capture logcat from start time
            if (mDestDir == null) {
                return;
            }
            try {
                int iteration = mTestIterations.get(description);
                final String fileName =
                        String.format(
                                "%s.%s%s%s-logcat-on-failure.txt",
                                description.getClassName(),
                                description.getMethodName(),
                                iteration == 1 ? "" : (METRIC_SEP + String.valueOf(iteration)),
                                METRIC_SEP + FILENAME_SUFFIX);
                File logcat = new File(mDestDir, fileName);
                getLogcatSince(mStartTime, logcat);
                testData.addFileMetric(String.format("%s_%s", getTag(), logcat.getName()), logcat);
            } catch (IOException | InterruptedException e) {
                Log.e(getTag(), "Error trying to retrieve logcat.", e);
            }
        }
        // Reset the flag here, as onTestStart might not have been called if a @BeforeClass method
        // fails.
        mTestFailed = false;
        // Update the start time here in case onTestStart() is not called for the next test. If it
        // is called, the start time will be overwritten.
        mStartTime = getCurrentDate();
    }

    /** @hide */
    @VisibleForTesting
    protected void getLogcatSince(String startTime, File saveTo)
            throws IOException, InterruptedException {
        // ProcessBuilder is used here in favor of UiAutomation.executeShellCommand() because the
        // logcat command requires the timestamp to be quoted which in Java requires
        // Runtime.exec(String[]) or ProcessBuilder to work properly, and UiAutomation does not
        // support this for now.
        ProcessBuilder pb = new ProcessBuilder(Arrays.asList("logcat", "-t", startTime));
        pb.redirectOutput(saveTo);
        Process proc = pb.start();
        // Make the process blocking to ensure consistent behavior.
        proc.waitFor();
    }

    /** @hide */
    @VisibleForTesting
    protected String getCurrentDate() {
        // Get time using system (wall clock) time since this is the time that logcat is based on.
        Date date = new Date(System.currentTimeMillis());
        return DATE_FORMATTER.format(date);
    }
}
