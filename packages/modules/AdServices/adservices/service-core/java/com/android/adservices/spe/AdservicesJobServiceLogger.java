/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.adservices.spe;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__FAILED_WITHOUT_RETRY;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__FAILED_WITH_RETRY;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__HALTED_FOR_UNKNOWN_REASON;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__ONSTOP_CALLED_WITHOUT_RETRY;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__ONSTOP_CALLED_WITH_RETRY;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SUCCESSFUL;
import static com.android.adservices.spe.JobServiceConstants.MILLISECONDS_PER_MINUTE;
import static com.android.adservices.spe.JobServiceConstants.SHARED_PREFS_BACKGROUND_JOBS;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_JOB_EXECUTION_PERIOD;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_JOB_EXECUTION_STOP_TIMESTAMP;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_JOB_LATENCY;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_STOP_REASON;

import android.annotation.NonNull;
import android.annotation.TargetApi;
import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import com.android.adservices.LogUtil;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.stats.Clock;
import com.android.adservices.service.stats.StatsdAdServicesLogger;
import com.android.adservices.spe.stats.ExecutionReportedStats;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;

/** Class for logging methods used by background jobs. */
public final class AdservicesJobServiceLogger {
    private static final Object SINGLETON_LOCK = new Object();

    private static volatile AdservicesJobServiceLogger sSingleton;

    private final Context mContext;
    private final Clock mClock;
    private final StatsdAdServicesLogger mStatsdLogger;

    /** Create an instance of {@link AdservicesJobServiceLogger}. */
    @VisibleForTesting
    public AdservicesJobServiceLogger(
            @NonNull Context context,
            @NonNull Clock clock,
            @NonNull StatsdAdServicesLogger statsdLogger) {
        mContext = context.getApplicationContext();
        mClock = clock;
        mStatsdLogger = statsdLogger;
    }

    /** Get a singleton instance of {@link AdservicesJobServiceLogger} to be used. */
    @NonNull
    public static AdservicesJobServiceLogger getInstance(@NonNull Context context) {
        if (sSingleton == null) {
            synchronized (SINGLETON_LOCK) {
                if (sSingleton == null) {
                    sSingleton =
                            new AdservicesJobServiceLogger(
                                    context,
                                    Clock.SYSTEM_CLOCK,
                                    StatsdAdServicesLogger.getInstance());
                }
            }
        }

        return sSingleton;
    }

    /**
     * {@link JobService} calls this method in {@link JobService#onStartJob(JobParameters)} to
     * record that onStartJob was called.
     *
     * @param jobId the unique id of the job to log for.
     */
    public void recordOnStartJob(int jobId) {
        if (FlagsFactory.getFlags().getBackgroundJobsLoggingKillSwitch()) {
            return;
        }

        long startJobTimestamp = mClock.currentTimeMillis();

        persistJobExecutionData(jobId, startJobTimestamp);
    }

    /**
     * Record that the {@link JobService#jobFinished(JobParameters, boolean)} is called or is about
     * to be called.
     *
     * @param jobId the unique id of the job to log for.
     * @param isSuccessful indicates if the execution is successful.
     * @param shouldRetry indicates whether to retry the execution.
     */
    public void recordJobFinished(int jobId, boolean isSuccessful, boolean shouldRetry) {
        if (FlagsFactory.getFlags().getBackgroundJobsLoggingKillSwitch()) {
            return;
        }

        int resultCode =
                isSuccessful
                        ? AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SUCCESSFUL
                        : (shouldRetry
                                ? AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__FAILED_WITH_RETRY
                                : AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__FAILED_WITHOUT_RETRY);
        logExecutionStats(jobId, mClock.currentTimeMillis(), resultCode, UNAVAILABLE_STOP_REASON);
    }

    /**
     * {@link JobService} calls this method in {@link JobService#onStopJob(JobParameters)}} to
     * enable logging.
     *
     * @param params configured {@link JobParameters}
     * @param jobId the unique id of the job to log for.
     * @param shouldRetry whether to reschedule the job.
     */
    @TargetApi(Build.VERSION_CODES.S)
    public void recordOnStopJob(@NonNull JobParameters params, int jobId, boolean shouldRetry) {
        if (FlagsFactory.getFlags().getBackgroundJobsLoggingKillSwitch()) {
            return;
        }

        long endJobTimestamp = mClock.currentTimeMillis();

        // StopReason is only supported for Android Version S+.
        int stopReason = SdkLevel.isAtLeastS() ? params.getStopReason() : UNAVAILABLE_STOP_REASON;

        int resultCode =
                shouldRetry
                        ? AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__ONSTOP_CALLED_WITH_RETRY
                        : AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__ONSTOP_CALLED_WITHOUT_RETRY;

        logExecutionStats(jobId, endJobTimestamp, resultCode, stopReason);
    }

    /**
     * Log when the execution is skipped due to customized reasons.
     *
     * @param jobId the unique id of the job to log for
     * @param skipReason the result to skip the execution
     */
    public void recordJobSkipped(int jobId, int skipReason) {
        if (FlagsFactory.getFlags().getBackgroundJobsLoggingKillSwitch()) {
            return;
        }

        logExecutionStats(jobId, mClock.currentTimeMillis(), skipReason, UNAVAILABLE_STOP_REASON);
    }

    /**
     * Log for various lifecycles of an execution.
     *
     * <p>a completed lifecycle includes job finished in {@link
     * JobService#jobFinished(JobParameters, boolean)} or {@link
     * JobService#onStopJob(JobParameters)}.
     *
     * @param jobId the job id
     * @param jobStopExecutionTimestamp the timestamp of the end of an execution. Note it can happen
     *     in either {@link JobService#jobFinished(JobParameters, boolean)} or {@link
     *     JobService#onStopJob(JobParameters)}.
     * @param executionResultCode the result code for current execution
     * @param possibleStopReason if {@link JobService#onStopJob(JobParameters)} is invoked. Set
     *     {@link JobServiceConstants#UNAVAILABLE_STOP_REASON} if {@link
     *     JobService#onStopJob(JobParameters)} is not invoked.
     */
    @VisibleForTesting
    public void logExecutionStats(
            int jobId,
            long jobStopExecutionTimestamp,
            int executionResultCode,
            int possibleStopReason) {
        String jobStartTimestampKey = getJobStartTimestampKey(jobId);
        String executionPeriodKey = getExecutionPeriodKey(jobId);
        String jobStopTimestampKey = getJobStopTimestampKey(jobId);

        SharedPreferences sharedPreferences =
                mContext.getSharedPreferences(SHARED_PREFS_BACKGROUND_JOBS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();

        long jobStartExecutionTimestamp =
                sharedPreferences.getLong(
                        jobStartTimestampKey, UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP);
        long jobExecutionPeriodMs =
                sharedPreferences.getLong(executionPeriodKey, UNAVAILABLE_JOB_EXECUTION_PERIOD);

        // Stop telemetry the metrics and log error in logcat if the stat is not valid.
        if (jobStartExecutionTimestamp == UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP
                || jobStartExecutionTimestamp > jobStopExecutionTimestamp) {
            // TODO(b/279231865): Log CEL with SPE_UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP
            LogUtil.e(
                    "Execution Stat is INVALID for job %s, jobStartTimestamp: %d, jobStopTimestamp:"
                            + " %d.",
                    AdservicesJobInfo.getJobIdToInfoMap().get(jobId),
                    jobStartExecutionTimestamp,
                    jobStopExecutionTimestamp);
            return;
        }

        // Compute the execution latency.
        long executionLatencyMs = jobStopExecutionTimestamp - jobStartExecutionTimestamp;

        // Update jobStopExecutionTimestamp in storage.
        editor.putLong(jobStopTimestampKey, jobStopExecutionTimestamp);

        if (!editor.commit()) {
            // The commitment failure should be rare. It may result in 1 problematic data but the
            // impact could be ignored compared to a job's lifecycle.
            // TODO(b/279231865): Log CEL with SPE_FAIL_TO_COMMIT_JOB_STOP_TIME
            LogUtil.e(
                    "Failed to update job Ending Execution Logging Data for Job %s.",
                    AdservicesJobInfo.getJobIdToInfoMap().get(jobId).getJobServiceName());
        }

        // Actually upload the metrics to statsD.
        logJobStatsHelper(
                jobId,
                executionLatencyMs,
                jobExecutionPeriodMs,
                executionResultCode,
                possibleStopReason);
    }

    /**
     * Do background job telemetry.
     *
     * @param jobId the job ID
     * @param executionLatencyMs the latency of an execution. Defined as the difference of timestamp
     *     between end and start of an execution.
     * @param executionPeriodMs the execution period. Defined as the difference of timestamp between
     *     current and previous start of an execution. This is only valid for periodical jobs to
     *     monitor the difference between actual and configured execution period.
     * @param resultCode the result code of an execution
     * @param stopReason {@link JobParameters#getStopReason()} if {@link
     *     JobService#onStopJob(JobParameters)} is invoked. Otherwise, set it to {@link
     *     JobServiceConstants#UNAVAILABLE_STOP_REASON}.
     */
    @VisibleForTesting
    public void logJobStatsHelper(
            int jobId,
            long executionLatencyMs,
            long executionPeriodMs,
            int resultCode,
            int stopReason) {
        long executionPeriodMinute = executionPeriodMs / MILLISECONDS_PER_MINUTE;

        ExecutionReportedStats stats =
                ExecutionReportedStats.builder()
                        .setJobId(jobId)
                        .setExecutionLatencyMs(convertLongToInteger(executionLatencyMs))
                        .setExecutionPeriodMinute(convertLongToInteger(executionPeriodMinute))
                        .setExecutionResultCode(resultCode)
                        .setStopReason(stopReason)
                        .build();
        mStatsdLogger.logExecutionReportedStats(stats);

        LogUtil.v(
                "[Adservices background job logging] jobId: %d, executionLatencyInMs: %d, "
                        + "executionPeriodInMs: %d, resultCode: %d, stopReason: %d",
                jobId, executionLatencyMs, executionPeriodMs, resultCode, stopReason);
    }

    /**
     * Compute execution data such as latency and period then store the data in persistent so that
     * we can compute the job stats later. Store start job timestamp and execution period into the
     * storage.
     *
     * @param jobId the job id
     * @param startJobTimestamp the timestamp when {@link JobService#onStartJob(JobParameters)} is
     *     invoked.
     */
    @VisibleForTesting
    public void persistJobExecutionData(int jobId, long startJobTimestamp) {
        SharedPreferences sharedPreferences =
                mContext.getSharedPreferences(SHARED_PREFS_BACKGROUND_JOBS, Context.MODE_PRIVATE);

        String jobStartTimestampKey = getJobStartTimestampKey(jobId);
        String executionPeriodKey = getExecutionPeriodKey(jobId);
        String jobStopTimestampKey = getJobStopTimestampKey(jobId);

        // When onStartJob() is invoked, the data stored in the shared preference is for previous
        // execution.
        //
        // JobService is scheduled as JobStatus in JobScheduler infra. Before a JobStatus instance
        // is pushed to pendingJobQueue, it checks a few criteria like whether a same JobStatus is
        // ready to execute, not pending, not running, etc. To determine if two JobStatus instances
        // are same, it checks jobId, callingUid (the package that schedules the job). Therefore,
        // there won't have two pending/running job instances with a same jobId. For more details,
        // please check source code of JobScheduler.
        long previousJobStartTimestamp =
                sharedPreferences.getLong(
                        jobStartTimestampKey, UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP);
        long previousJobStopTimestamp =
                sharedPreferences.getLong(
                        jobStopTimestampKey, UNAVAILABLE_JOB_EXECUTION_STOP_TIMESTAMP);
        long previousExecutionPeriod =
                sharedPreferences.getLong(executionPeriodKey, UNAVAILABLE_JOB_EXECUTION_PERIOD);

        SharedPreferences.Editor editor = sharedPreferences.edit();

        // The first execution, pass execution period with UNAVAILABLE_JOB_EXECUTION_PERIOD.
        if (previousJobStartTimestamp == UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP) {
            editor.putLong(executionPeriodKey, UNAVAILABLE_JOB_EXECUTION_PERIOD);
        } else {
            // If previousJobStartTimestamp is later than previousJobStopTimestamp, it indicates the
            // last execution didn't finish with calling jobFinished() or onStopJob(). In this case,
            // we log as an unknown issue, which may come from system/device.
            if (previousJobStartTimestamp > previousJobStopTimestamp) {
                logJobStatsHelper(
                        jobId,
                        UNAVAILABLE_JOB_LATENCY,
                        previousExecutionPeriod,
                        AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__HALTED_FOR_UNKNOWN_REASON,
                        UNAVAILABLE_STOP_REASON);
            }

            // Compute execution period if there has been multiple executions.
            // Define the execution period = difference of the timestamp of two consecutive
            // invocations of onStartJob().
            // TODO(b/279231865): TODO log to CEL: SPE_INVALID_EXECUTION_PERIOD
            long executionPeriodInMs = startJobTimestamp - previousJobStartTimestamp;

            // Store the execution period into shared preference.
            editor.putLong(executionPeriodKey, executionPeriodInMs);
        }
        // Store current JobStartTimestamp into shared preference.
        editor.putLong(jobStartTimestampKey, startJobTimestamp);

        if (!editor.commit()) {
            // The commitment failure should be rare. It may result in 1 problematic data but the
            // impact could be ignored compared to a job's lifecycle.
            // TODO(b/279231865): Log CEL with SPE_FAIL_TO_COMMIT_JOB_START_TIME.
            LogUtil.e(
                    "Failed to update onStartJob() Logging Data for Job %s.",
                    AdservicesJobInfo.getJobIdToInfoMap().get(jobId).getJobServiceName());
        }
    }

    @VisibleForTesting
    static String getJobStartTimestampKey(int jobId) {
        return jobId + JobServiceConstants.SHARED_PREFS_START_TIMESTAMP_SUFFIX;
    }

    @VisibleForTesting
    static String getJobStopTimestampKey(int jobId) {
        return jobId + JobServiceConstants.SHARED_PREFS_STOP_TIMESTAMP_SUFFIX;
    }

    @VisibleForTesting
    static String getExecutionPeriodKey(int jobId) {
        return jobId + JobServiceConstants.SHARED_PREFS_EXEC_PERIOD_SUFFIX;
    }

    // Convert a long value to an integer.
    //
    // Used to convert a time period in long-format but needs to be logged with integer-format.
    // Generally, a time period should always be a positive integer with a proper design of its
    // unit.
    //
    // Defensively use this method to avoid any Exception.
    @VisibleForTesting
    static int convertLongToInteger(long longVal) {
        int intValue;

        // The given time period should always be in the range of positive integer. Defensively
        // handle overflow values to avoid potential Exceptions.
        if (longVal <= Integer.MIN_VALUE) {
            intValue = Integer.MIN_VALUE;
        } else if (longVal >= Integer.MAX_VALUE) {
            intValue = Integer.MAX_VALUE;
        } else {
            intValue = (int) longVal;
        }

        return intValue;
    }
}
