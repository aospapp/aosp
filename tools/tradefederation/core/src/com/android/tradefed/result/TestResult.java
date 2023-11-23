/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.result;

import com.android.ddmlib.testrunner.TestResult.TestStatus;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;

import com.google.common.base.Joiner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Container for a result of a single test. */
public class TestResult {

    private TestStatus mStatus;
    private String mStackTrace;
    private Map<String, String> mMetrics;
    private HashMap<String, Metric> mProtoMetrics;
    private Map<String, LogFile> mLoggedFiles;
    // the start and end time of the test, measured via {@link System#currentTimeMillis()}
    private long mStartTime = 0;
    private long mEndTime = 0;

    public TestResult() {
        mStatus = TestStatus.INCOMPLETE;
        mStartTime = System.currentTimeMillis();
        mLoggedFiles = new LinkedHashMap<String, LogFile>();
        mMetrics = new HashMap<>();
        mProtoMetrics = new HashMap<>();
    }

    /** Get the {@link TestStatus} result of the test. */
    public TestStatus getStatus() {
        return mStatus;
    }

    /**
     * Get the associated {@link String} stack trace. Should be <code>null</code> if {@link
     * #getStatus()} is {@link TestStatus#PASSED}.
     */
    public String getStackTrace() {
        return mStackTrace;
    }

    /** Get the associated test metrics. */
    public Map<String, String> getMetrics() {
        return mMetrics;
    }

    /** Get the associated test metrics in proto format. */
    public HashMap<String, Metric> getProtoMetrics() {
        return mProtoMetrics;
    }

    /** Set the test metrics, overriding any previous values. */
    public void setMetrics(Map<String, String> metrics) {
        mMetrics = metrics;
    }

    /** Set the test proto metrics format, overriding any previous values. */
    public void setProtoMetrics(HashMap<String, Metric> metrics) {
        mProtoMetrics = metrics;
    }

    /** Add a logged file tracking associated with that test case */
    public void addLoggedFile(String dataName, LogFile loggedFile) {
        mLoggedFiles.put(dataName, loggedFile);
    }

    /** Returns a copy of the map containing all the logged file associated with that test case. */
    public Map<String, LogFile> getLoggedFiles() {
        return new LinkedHashMap<>(mLoggedFiles);
    }

    /**
     * Return the {@link System#currentTimeMillis()} time that the {@link
     * ITestInvocationListener#testStarted(TestDescription)} event was received.
     */
    public long getStartTime() {
        return mStartTime;
    }

    /**
     * Allows to set the time when the test was started, to be used with {@link
     * ITestInvocationListener#testStarted(TestDescription, long)}.
     */
    public void setStartTime(long startTime) {
        mStartTime = startTime;
    }

    /**
     * Return the {@link System#currentTimeMillis()} time that the {@link
     * ITestInvocationListener#testEnded(TestDescription, Map)} event was received.
     */
    public long getEndTime() {
        return mEndTime;
    }

    /** Set the {@link TestStatus}. */
    public TestResult setStatus(TestStatus status) {
        mStatus = status;
        return this;
    }

    /** Set the stack trace. */
    public void setStackTrace(String trace) {
        mStackTrace = trace;
    }

    /** Sets the end time */
    public void setEndTime(long currentTimeMillis) {
        mEndTime = currentTimeMillis;
    }

    @Override
    public int hashCode() {
        return Arrays.hashCode(new Object[] {mMetrics, mStackTrace, mStatus});
    }

    @Override
    public boolean equals(Object obj) {
        if (this == obj) {
            return true;
        }
        if (obj == null) {
            return false;
        }
        if (getClass() != obj.getClass()) {
            return false;
        }
        TestResult other = (TestResult) obj;
        return equal(mMetrics, other.mMetrics)
                && equal(mStackTrace, other.mStackTrace)
                && equal(mStatus, other.mStatus);
    }

    private static boolean equal(Object a, Object b) {
        return a == b || (a != null && a.equals(b));
    }

    /**
     * Merge the attempts for a same test case based on the merging strategy.
     *
     * @param results List of {@link TestResult} that will be merged
     * @param strategy the {@link MergeStrategy} to be used to determine the merging outcome.
     * @return the merged {@link TestResult} or null if there is nothing to merge.
     */
    public static TestResult merge(List<TestResult> results, MergeStrategy strategy) {
        if (results.isEmpty()) {
            return null;
        }
        if (MergeStrategy.NO_MERGE.equals(strategy)) {
            throw new IllegalArgumentException(
                    "TestResult#merge cannot be called with NO_MERGE strategy.");
        }
        TestResult mergedResult = new TestResult();

        long earliestStartTime = Long.MAX_VALUE;
        long latestEndTime = Long.MIN_VALUE;

        List<String> errorMsg = new ArrayList<>();
        int pass = 0;
        int fail = 0;
        int assumption_failure = 0;
        int ignored = 0;
        int incomplete = 0;

        for (TestResult attempt : results) {
            mergedResult.mProtoMetrics.putAll(attempt.getProtoMetrics());
            mergedResult.mMetrics.putAll(attempt.getMetrics());
            mergedResult.mLoggedFiles.putAll(attempt.getLoggedFiles());
            earliestStartTime = Math.min(attempt.getStartTime(), earliestStartTime);
            latestEndTime = Math.max(attempt.getEndTime(), latestEndTime);
            switch (attempt.getStatus()) {
                case PASSED:
                    pass++;
                    break;
                case FAILURE:
                    fail++;
                    if (attempt.getStackTrace() != null) {
                        errorMsg.add(attempt.getStackTrace());
                    }
                    break;
                case INCOMPLETE:
                    incomplete++;
                    errorMsg.add("incomplete test case result.");
                    break;
                case ASSUMPTION_FAILURE:
                    assumption_failure++;
                    if (attempt.getStackTrace() != null) {
                        errorMsg.add(attempt.getStackTrace());
                    }
                    break;
                case IGNORED:
                    ignored++;
                    break;
            }
        }

        switch (strategy) {
            case ANY_PASS_IS_PASS:
            case ONE_TESTCASE_PASS_IS_PASS:
                // We prioritize passing the test due to the merging strategy.
                if (pass > 0) {
                    mergedResult.setStatus(TestStatus.PASSED);
                } else if (fail == 0) {
                    if (ignored > 0) {
                        mergedResult.setStatus(TestStatus.IGNORED);
                    } else if (assumption_failure > 0) {
                        mergedResult.setStatus(TestStatus.ASSUMPTION_FAILURE);
                    } else if (incomplete > 0) {
                        mergedResult.setStatus(TestStatus.INCOMPLETE);
                    }
                } else {
                    mergedResult.setStatus(TestStatus.FAILURE);
                }
                break;
            default:
                // We keep a sane default of one failure is a failure that should be reported.
                if (fail > 0) {
                    mergedResult.setStatus(TestStatus.FAILURE);
                } else {
                    if (ignored > 0) {
                        mergedResult.setStatus(TestStatus.IGNORED);
                    } else if (assumption_failure > 0) {
                        mergedResult.setStatus(TestStatus.ASSUMPTION_FAILURE);
                    } else if (incomplete > 0) {
                        mergedResult.setStatus(TestStatus.INCOMPLETE);
                    } else {
                        mergedResult.setStatus(TestStatus.PASSED);
                    }
                }
                break;
        }
        if (errorMsg.isEmpty()) {
            mergedResult.mStackTrace = null;
        } else {
            mergedResult.mStackTrace = Joiner.on("\n\n").join(errorMsg);
        }
        mergedResult.setStartTime(earliestStartTime);
        mergedResult.setEndTime(latestEndTime);
        return mergedResult;
    }
}
