/*
 * Copyright (C) 2016 The Android Open Source Project
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
package com.android.tradefed.testtype.suite;

import com.android.ddmlib.testrunner.TestIdentifier;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.IRunUtil;
import com.android.tradefed.util.RunUtil;

import com.google.common.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Listener used to take action such as screenshot, bugreport, logcat collection upon a test failure
 * when requested.
 */
public class TestFailureListener implements ITestInvocationListener {

    private static final int DEFAULT_MAX_LOGCAT_BYTES = 500 * 1024; // 500K
    /* Arbitrary upper limit for mMaxLogcatBytes to avoid un-reasonably high limit */
    private static final int LOGCAT_BYTE_LIMIT = 20 * 1024 * 1024; // 20 MB
    private static final String LOGCAT_ON_FAILURE_SIZE_OPTION = "logcat-on-failure-size";
    private static final long LOGCAT_CAPTURE_TIMEOUT = 2 * 60 * 1000;

    private List<ITestDevice> mListDevice;
    private ITestInvocationListener mListener;
    private boolean mBugReportOnFailure;
    private boolean mLogcatOnFailure;
    private boolean mScreenshotOnFailure;
    private boolean mRebootOnFailure;
    private int mMaxLogcatBytes;
    private Map<TestIdentifier, Long> mTrackStartTime = new HashMap<>();
    private List<Thread> mLogcatThreads = new ArrayList<>();

    public TestFailureListener(
            ITestInvocationListener listener,
            List<ITestDevice> devices,
            boolean bugReportOnFailure,
            boolean logcatOnFailure,
            boolean screenshotOnFailure,
            boolean rebootOnFailure,
            int maxLogcatBytes) {
        mListener = listener;
        mListDevice = devices;
        mBugReportOnFailure = bugReportOnFailure;
        mLogcatOnFailure = logcatOnFailure;
        mScreenshotOnFailure = screenshotOnFailure;
        mRebootOnFailure = rebootOnFailure;
        if (maxLogcatBytes < 0 ) {
            CLog.w("FailureListener could not set %s to '%d', using default value %d",
                    LOGCAT_ON_FAILURE_SIZE_OPTION, maxLogcatBytes,
                    DEFAULT_MAX_LOGCAT_BYTES);
            mMaxLogcatBytes = DEFAULT_MAX_LOGCAT_BYTES;
        } else if (maxLogcatBytes > LOGCAT_BYTE_LIMIT) {
            CLog.w("Value %d for %s exceeds limit %d, using limit value", maxLogcatBytes,
                    LOGCAT_ON_FAILURE_SIZE_OPTION, LOGCAT_BYTE_LIMIT);
            mMaxLogcatBytes = LOGCAT_BYTE_LIMIT;
        } else {
            mMaxLogcatBytes = maxLogcatBytes;
        }
    }

    /**
     * We override testStarted in order to track the start time.
     */
    @Override
    public void testStarted(TestIdentifier test) {
        if (mLogcatOnFailure) {
            try {
                mTrackStartTime.put(test, mListDevice.get(0).getDeviceDate());
            } catch (DeviceNotAvailableException e) {
                CLog.e(e);
                // we fall back to logcat dump on null.
                mTrackStartTime.put(test, null);
            }
        }
    }

    /**
     * Make sure we clean the map when test end to avoid too much overhead.
     */
    @Override
    public void testEnded(TestIdentifier test, Map<String, String> testMetrics) {
        if (mLogcatOnFailure) {
            mTrackStartTime.remove(test);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void testFailed(TestIdentifier test, String trace) {
        CLog.i("FailureListener.testFailed %s %b %b %b", test.toString(), mBugReportOnFailure,
                mLogcatOnFailure, mScreenshotOnFailure);
        for (ITestDevice device : mListDevice) {
            captureFailure(device, test);
        }
    }

    /** Capture the appropriate logs for one device for one test failure. */
    private void captureFailure(ITestDevice device, TestIdentifier test) {
        String serial = device.getSerialNumber();
        if (mScreenshotOnFailure) {
            try {
                try (InputStreamSource screenSource = device.getScreenshot()) {
                    testLog(
                            String.format("%s-%s-screenshot", test.toString(), serial),
                            LogDataType.PNG,
                            screenSource);
                }
            } catch (DeviceNotAvailableException e) {
                CLog.e(e);
                CLog.e("Device %s became unavailable while capturing screenshot", serial);
            }
        }
        if (mBugReportOnFailure) {
            try (InputStreamSource bugSource = device.getBugreportz()) {
                testLog(
                        String.format("%s-%s-bugreport", test.toString(), serial),
                        LogDataType.BUGREPORTZ,
                        bugSource);
            }
        }
        if (mLogcatOnFailure) {
            Runnable captureLogcat =
                    new Runnable() {
                        @Override
                        public void run() {
                            InputStreamSource logSource = null;
                            Long startTime = mTrackStartTime.remove(test);
                            if (startTime != null) {
                                logSource = device.getLogcatSince(startTime);
                            } else {
                                // sleep 2s to ensure test failure stack trace makes it into the
                                // logcat capture
                                getRunUtil().sleep(2 * 1000);
                                logSource = device.getLogcat(mMaxLogcatBytes);
                            }
                            testLog(
                                    String.format("%s-%s-logcat", test.toString(), serial),
                                    LogDataType.LOGCAT,
                                    logSource);
                            logSource.close();
                        }
                    };
            if (mRebootOnFailure) {
                captureLogcat.run();
            } else {
                // If no reboot will be done afterward capture asynchronously the logcat.
                Thread captureThread =
                        new Thread(captureLogcat, String.format("Capture failure logcat %s", test));
                captureThread.setDaemon(true);
                mLogcatThreads.add(captureThread);
                captureThread.start();
            }
        }
        if (mRebootOnFailure) {
            try {
                // Rebooting on all failures can hide legitimate issues and platform instabilities,
                // therefore only allowed on "user-debug" and "eng" builds.
                if ("user".equals(device.getProperty("ro.build.type"))) {
                    CLog.e("Reboot-on-failure should only be used during development," +
                            " this is a\" user\" build device");
                } else {
                    device.reboot();
                }
            } catch (DeviceNotAvailableException e) {
                CLog.e(e);
                CLog.e("Device %s became unavailable while rebooting", serial);
            }
        }
    }

    /** Join on all the logcat capturing threads to ensure they terminate. */
    public void join() {
        synchronized (mLogcatThreads) {
            for (Thread t : mLogcatThreads) {
                if (!t.isAlive()) {
                    continue;
                }
                try {
                    t.join(LOGCAT_CAPTURE_TIMEOUT);
                } catch (InterruptedException e) {
                    CLog.e(e);
                }
            }
            mLogcatThreads.clear();
        }
    }

    @Override
    public void testLog(String dataName, LogDataType dataType, InputStreamSource dataStream) {
        mListener.testLog(dataName, dataType, dataStream);
    }

    /**
     * Get the default {@link IRunUtil} instance
     */
    @VisibleForTesting
    IRunUtil getRunUtil() {
        return RunUtil.getDefault();
    }
}
