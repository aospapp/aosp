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

package com.android.adservices.service.measurement.attribution;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_EXTSERVICES_JOB_ON_TPLUS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_KILL_SWITCH_ON;
import static com.android.adservices.spe.AdservicesJobInfo.MEASUREMENT_ATTRIBUTION_FALLBACK_JOB;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;

import com.android.adservices.LogUtil;
import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.data.measurement.DatastoreManagerFactory;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.compat.ServiceCompatUtils;
import com.android.adservices.service.measurement.Trigger;
import com.android.adservices.service.measurement.reporting.DebugReportApi;
import com.android.adservices.service.measurement.reporting.DebugReportingJobService;
import com.android.adservices.spe.AdservicesJobServiceLogger;
import com.android.internal.annotations.VisibleForTesting;

import java.util.concurrent.Executor;

/**
 * Fallback attribution job. The actual job execution logic is part of {@link
 * AttributionJobHandler}.
 */
public class AttributionFallbackJobService extends JobService {
    private static final int MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_ID =
            MEASUREMENT_ATTRIBUTION_FALLBACK_JOB.getJobId();
    private static final Executor sBackgroundExecutor = AdServicesExecutors.getBackgroundExecutor();

    @Override
    public void onCreate() {
        LogUtil.d("AttributionFallbackJobService.onCreate");
        super.onCreate();
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // Always ensure that the first thing this job does is check if it should be running, and
        // cancel itself if it's not supposed to be.
        if (ServiceCompatUtils.shouldDisableExtServicesJobOnTPlus(this)) {
            LogUtil.d(
                    "Disabling AttributionFallbackJobService job because it's running in"
                            + " ExtServices on T+");
            return skipAndCancelBackgroundJob(
                    params,
                    AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_EXTSERVICES_JOB_ON_TPLUS);
        }

        AdservicesJobServiceLogger.getInstance(this)
                .recordOnStartJob(MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_ID);

        if (FlagsFactory.getFlags().getMeasurementAttributionFallbackJobKillSwitch()) {
            LogUtil.e("AttributionFallbackJobService is disabled");
            return skipAndCancelBackgroundJob(
                    params,
                    AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_KILL_SWITCH_ON);
        }

        LogUtil.d("AttributionFallbackJobService.onStartJob");
        sBackgroundExecutor.execute(
                () -> {
                    boolean success =
                            new AttributionJobHandler(
                                            DatastoreManagerFactory.getDatastoreManager(
                                                    getApplicationContext()),
                                            new DebugReportApi(
                                                    getApplicationContext(),
                                                    FlagsFactory.getFlags()))
                                    .performPendingAttributions();
                    DebugReportingJobService.scheduleIfNeeded(
                            getApplicationContext(),
                            /* forceSchedule */ true,
                            /* isDebugReportApi */ false);

                    AdservicesJobServiceLogger.getInstance(AttributionFallbackJobService.this)
                            .recordJobFinished(
                                    MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_ID, success, !success);

                    jobFinished(params, !success);
                });
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        LogUtil.d("AttributionFallbackJobService.onStopJob");
        boolean shouldRetry = true;

        AdservicesJobServiceLogger.getInstance(this)
                .recordOnStopJob(params, MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_ID, shouldRetry);
        return shouldRetry;
    }

    /**
     * Schedules {@link AttributionFallbackJobService} to observer {@link Trigger} content URI
     * change.
     */
    @VisibleForTesting
    static void schedule(Context context, JobScheduler jobScheduler) {
        final JobInfo job =
                new JobInfo.Builder(
                                MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_ID,
                                new ComponentName(context, AttributionJobService.class))
                        .setPeriodic(
                                FlagsFactory.getFlags()
                                        .getMeasurementAttributionFallbackJobPeriodMs())
                        .setPersisted(true)
                        .build();
        jobScheduler.schedule(job);
    }

    /**
     * Schedule Attribution Fallback Job if it is not already scheduled
     *
     * @param context the context
     * @param forceSchedule flag to indicate whether to force rescheduling the job.
     */
    public static void scheduleIfNeeded(Context context, boolean forceSchedule) {
        if (FlagsFactory.getFlags().getMeasurementAttributionFallbackJobKillSwitch()) {
            LogUtil.e("AttributionFallbackJobService is disabled, skip scheduling");
            return;
        }

        final JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler == null) {
            LogUtil.e("JobScheduler not found");
            return;
        }

        final JobInfo job = jobScheduler.getPendingJob(MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_ID);
        // Schedule if it hasn't been scheduled already or force rescheduling
        if (job == null || forceSchedule) {
            schedule(context, jobScheduler);
            LogUtil.d("Scheduled AttributionFallbackJobService");
        } else {
            LogUtil.d("AttributionFallbackJobService already scheduled, skipping reschedule");
        }
    }

    private boolean skipAndCancelBackgroundJob(final JobParameters params, int skipReason) {
        final JobScheduler jobScheduler = this.getSystemService(JobScheduler.class);
        if (jobScheduler != null) {
            jobScheduler.cancel(MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_ID);
        }

        AdservicesJobServiceLogger.getInstance(this)
                .recordJobSkipped(MEASUREMENT_ATTRIBUTION_FALLBACK_JOB_ID, skipReason);

        // Tell the JobScheduler that the job has completed and does not need to be rescheduled.
        jobFinished(params, false);

        // Returning false means that this job has completed its work.
        return false;
    }
}
