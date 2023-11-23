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

package com.android.ondevicepersonalization.services.data.user;

import static android.app.job.JobScheduler.RESULT_FAILURE;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.app.job.JobService;
import android.content.ComponentName;
import android.content.Context;
import android.util.Log;

import com.android.ondevicepersonalization.services.OnDevicePersonalizationConfig;
import com.android.ondevicepersonalization.services.OnDevicePersonalizationExecutors;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/**
 * JobService to collect user data in the background thread.
 */
public class UserDataCollectionJobService extends JobService {
    public static final String TAG = "UserDataCollectionJobService";
    // 4-hour interval.
    private static final long PERIOD_SECONDS = 14400;
    private ListenableFuture<Void> mFuture;
    private UserDataCollector mUserDataCollector;
    private RawUserData mUserData;

    /**
     * Schedules a unique instance of UserDataCollectionJobService to be run.
     */
    public static int schedule(Context context) {
        JobScheduler jobScheduler = context.getSystemService(JobScheduler.class);
        if (jobScheduler.getPendingJob(
                OnDevicePersonalizationConfig.USER_DATA_COLLECTION_ID) != null) {
            Log.d(TAG, "Job is already scheduled. Doing nothing,");
            return RESULT_FAILURE;
        }
        ComponentName serviceComponent = new ComponentName(context,
                UserDataCollectionJobService.class);
        JobInfo.Builder builder = new JobInfo.Builder(
                OnDevicePersonalizationConfig.USER_DATA_COLLECTION_ID, serviceComponent);

        // Constraints
        builder.setRequiresDeviceIdle(true);
        builder.setRequiresBatteryNotLow(true);
        builder.setRequiresStorageNotLow(true);
        builder.setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE);
        builder.setPeriodic(1000 * PERIOD_SECONDS); // JobScheduler uses Milliseconds.
        // persist this job across boots
        builder.setPersisted(true);

        return jobScheduler.schedule(builder.build());
    }

    @Override
    public boolean onStartJob(JobParameters params) {
        // TODO(b/265856477): return false to disable data collection if kid status is enabled.
        Log.d(TAG, "onStartJob()");
        mUserDataCollector = UserDataCollector.getInstance(this);
        mUserData = RawUserData.getInstance();
        mFuture = Futures.submit(new Runnable() {
            @Override
            public void run() {
                Log.d(TAG, "Running user data collection job");
                try {
                    // TODO(b/262749958): add multi-threading support if necessary.
                    mUserDataCollector.updateUserData(mUserData);
                } catch (Exception e) {
                    Log.e(TAG, "Failed to collect user data", e);
                }
            }
        }, OnDevicePersonalizationExecutors.getBackgroundExecutor());

        Futures.addCallback(
                mFuture,
                new FutureCallback<Void>() {
                    @Override
                    public void onSuccess(Void result) {
                        Log.d(TAG, "User data collection job completed.");
                        jobFinished(params, /* wantsReschedule = */ false);
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
        if (mFuture != null) {
            mFuture.cancel(true);
        }
        // Reschedule the job since it ended before finishing
        return true;
    }
}
