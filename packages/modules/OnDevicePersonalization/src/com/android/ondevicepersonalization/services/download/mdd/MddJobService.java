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

package com.android.ondevicepersonalization.services.download.mdd;

import static com.android.ondevicepersonalization.services.download.mdd.MddTaskScheduler.MDD_TASK_TAG_KEY;

import static com.google.android.libraries.mobiledatadownload.TaskScheduler.WIFI_CHARGING_PERIODIC_TASK;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.content.Context;
import android.os.PersistableBundle;
import android.util.Log;

import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;
import com.android.ondevicepersonalization.services.download.OnDevicePersonalizationDownloadProcessingJobService;

import com.google.android.libraries.mobiledatadownload.tracing.PropagatedFutures;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * MDD JobService. This will download MDD files in background tasks.
 */
public class MddJobService extends JobService {
    private static final String TAG = "MddJobService";

    private String mMddTaskTag;

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "onStartJob()");

        // Get the mddTaskTag from input.
        PersistableBundle extras = params.getExtras();
        if (null == extras) {
            Log.e(TAG, "can't find MDD task tag");
            throw new IllegalArgumentException("Can't find MDD Tasks Tag!");
        }
        mMddTaskTag = extras.getString(MDD_TASK_TAG_KEY);

        ListenableFuture<Void> handleTaskFuture =
                PropagatedFutures.submitAsync(
                        () -> MobileDataDownloadFactory.getMdd(this).handleTask(mMddTaskTag),
                        OnDevicePersonalizationExecutors.getBackgroundExecutor());

        Context context = this;
        Futures.addCallback(
                handleTaskFuture,
                new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        Log.d(TAG, "MddJobService.MddHandleTask succeeded!");
                        OnDevicePersonalizationDownloadProcessingJobService.schedule(context);
                        // Tell the JobScheduler that the job has completed and does not needs to be
                        // rescheduled.
                        if (WIFI_CHARGING_PERIODIC_TASK.equals(mMddTaskTag)) {
                            jobFinished(params, /* wantsReschedule = */ false);
                        }
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        Log.e(TAG, "Failed to handle JobService: " + params.getJobId(), t);
                        //  When failure, also tell the JobScheduler that the job has completed and
                        // does not need to be rescheduled.
                        jobFinished(params, /* wantsReschedule = */ false);
                    }
                },
                OnDevicePersonalizationExecutors.getBackgroundExecutor());

        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        // Attempt to process any data downloaded before the worker was stopped.
        if (WIFI_CHARGING_PERIODIC_TASK.equals(mMddTaskTag)) {
            jobFinished(params, /* wantsReschedule = */ false);
        }
        // Reschedule the job since it ended before finishing
        return true;
    }
}
