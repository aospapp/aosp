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

package com.android.ondevicepersonalization.services.download;

import static android.app.job.JobScheduler.RESULT_FAILURE;
import static android.content.pm.PackageManager.GET_META_DATA;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.util.Log;

import com.android.ondevicepersonalization.services.OnDevicePersonalizationConfig;
import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.manifest.AppManifestConfigHelper;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.ArrayList;
import java.util.List;

/**
 * JobService to handle the processing of the downloaded vendor data
 */
public class OnDevicePersonalizationDownloadProcessingJobService extends JobService {
    public static final String TAG = "OnDevicePersonalizationDownloadProcessingJobService";
    private List<ListenableFuture<Void>> mFutures;

    /**
     * Schedules a unique instance of OnDevicePersonalizationDownloadProcessingJobService to be run.
     */
    public static int schedule(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler.getPendingJob(
                OnDevicePersonalizationConfig.DOWNLOAD_PROCESSING_TASK_JOB_ID) != null) {
            Log.d(TAG, "Job is already scheduled. Doing nothing,");
            return RESULT_FAILURE;
        }
        ComponentName serviceComponent = new ComponentName(context,
                OnDevicePersonalizationDownloadProcessingJobService.class);
        JobInfo.Builder builder = new JobInfo.Builder(
                OnDevicePersonalizationConfig.DOWNLOAD_PROCESSING_TASK_JOB_ID, serviceComponent);

        // Constraints.
        builder.setRequiresDeviceIdle(true);
        builder.setRequiresBatteryNotLow(true);
        builder.setRequiresStorageNotLow(true);
        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);

        return jobScheduler.schedule(builder.build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "onStartJob()");
        mFutures = new ArrayList<>();
        for (PackageInfo packageInfo : this.getPackageManager().getInstalledPackages(
                PackageManager.PackageInfoFlags.of(GET_META_DATA))) {
            String packageName = packageInfo.packageName;
            if (AppManifestConfigHelper.manifestContainsOdpSettings(
                    this, packageName)) {
                mFutures.add(Futures.submitAsync(
                        new OnDevicePersonalizationDataProcessingAsyncCallable(packageName,
                                this),
                        OnDevicePersonalizationExecutors.getBackgroundExecutor()));
            }
        }
        Futures.whenAllComplete(mFutures).call(() -> {
            jobFinished(params, /* wantsReschedule */ false);
            return null;
        }, OnDevicePersonalizationExecutors.getLightweightExecutor());

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mFutures != null) {
            for (ListenableFuture<Void> f : mFutures) {
                f.cancel(true);
            }
        }
        // Reschedule the job since it ended before finishing
        return true;
    }
}
