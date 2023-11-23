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

package com.android.federatedcompute.services.training;

import static com.google.common.util.concurrent.MoreExecutors.directExecutor;

import android.app.job.JobParameters;
import android.app.job.JobService;
import android.util.Log;

import com.android.federatedcompute.services.common.FederatedComputeExecutors;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

/** Main service for the scheduled federated computation jobs. */
public class FederatedJobService extends JobService {
    private static final String TAG = "FederatedJobService";
    private ListenableFuture<Boolean> mRunCompleteFuture;

    @Override
    public boolean onStartJob(JobParameters params) {
        Log.d(TAG, "FederatedJobService.onStartJob");
        mRunCompleteFuture =
                Futures.submit(
                        () ->
                                FederatedComputeWorker.getInstance(this)
                                        .startTrainingRun(params.getJobId()),
                        FederatedComputeExecutors.getBackgroundExecutor());

        Futures.addCallback(
                mRunCompleteFuture,
                new FutureCallback<Boolean>() {
                    @Override
                    public void onSuccess(Boolean result) {
                        Log.d(TAG, "federated computation job is done!");
                        jobFinished(params, /* wantsReschedule= */ false);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        Log.e(TAG, "Failed to handle computation job: " + params.getJobId());
                        jobFinished(params, /* wantsReschedule= */ false);
                    }
                },
                directExecutor());
        return true;
    }

    @Override
    public boolean onStopJob(JobParameters params) {
        if (mRunCompleteFuture != null) {
            mRunCompleteFuture.cancel(true);
        }
        FederatedComputeWorker.getInstance(this).cancelActiveRun();
        // Reschedule the job since it's not done. TODO: we should implement specify reschedule
        // logic instead.
        return true;
    }
}
