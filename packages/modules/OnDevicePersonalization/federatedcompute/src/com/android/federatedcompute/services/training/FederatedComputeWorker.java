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

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.util.Log;

import com.android.federatedcompute.services.common.Flags;
import com.android.federatedcompute.services.common.PhFlags;
import com.android.federatedcompute.services.common.TaskRetry;
import com.android.federatedcompute.services.common.TrainingResult;
import com.android.federatedcompute.services.data.FederatedTrainingTask;
import com.android.federatedcompute.services.scheduling.FederatedComputeJobManager;
import com.android.federatedcompute.services.scheduling.SchedulingUtil;

import com.google.common.annotations.VisibleForTesting;

import javax.annotation.concurrent.GuardedBy;

/** The worker to execute federated computation jobs. */
public class FederatedComputeWorker {
    private static final String TAG = "FederatedComputeWorker";

    static final Object LOCK = new Object();

    @GuardedBy("LOCK")
    @Nullable
    private TrainingRun mActiveRun = null;

    @Nullable private final FederatedComputeJobManager mJobManager;
    private static volatile FederatedComputeWorker sFederatedComputeWorker;
    private final Flags mFlags;

    @VisibleForTesting
    public FederatedComputeWorker(FederatedComputeJobManager jobManager, Flags flags) {
        this.mJobManager = jobManager;
        this.mFlags = flags;
    }

    /** Gets an instance of {@link FederatedComputeWorker}. */
    @NonNull
    public static FederatedComputeWorker getInstance(Context context) {
        synchronized (FederatedComputeWorker.class) {
            if (sFederatedComputeWorker == null) {
                sFederatedComputeWorker =
                        new FederatedComputeWorker(
                                FederatedComputeJobManager.getInstance(context),
                                PhFlags.getInstance());
            }
            return sFederatedComputeWorker;
        }
    }

    /** Starts a training run with the given job Id. */
    public boolean startTrainingRun(int jobId) {
        Log.d(TAG, "startTrainingRun()");
        FederatedTrainingTask trainingTask = mJobManager.onTrainingStarted(jobId);
        if (trainingTask == null) {
            Log.i(TAG, String.format("Could not find task to run for job ID %s", jobId));
            return false;
        }

        synchronized (LOCK) {
            // Only allow one concurrent federated computation job.
            if (mActiveRun != null) {
                Log.i(
                        TAG,
                        String.format(
                                "Delaying %d/%s another run is already active!",
                                jobId, trainingTask.populationName()));
                mJobManager.onTrainingCompleted(
                        jobId,
                        trainingTask.populationName(),
                        trainingTask.getTrainingIntervalOptions(),
                        /* taskRetry= */ null,
                        TrainingResult.FAIL);
                return false;
            }
            TrainingRun run = new TrainingRun(jobId, trainingTask);
            this.mActiveRun = run;
            doTraining(run);
            // TODO: get retry info from federated server.
            TaskRetry taskRetry = SchedulingUtil.generateTransientErrorTaskRetry(mFlags);
            finish(this.mActiveRun, taskRetry, TrainingResult.SUCCESS);
        }
        return true;
    }

    /** Cancels the running job if present. */
    public void cancelActiveRun() {
        Log.d(TAG, "cancelActiveRun()");
        synchronized (LOCK) {
            if (mActiveRun == null) {
                return;
            }
            finish(mActiveRun, /* taskRetry= */ null, TrainingResult.FAIL);
        }
    }

    private void finish(
            TrainingRun runToFinish, TaskRetry taskRetry, @TrainingResult int trainingResult) {
        synchronized (LOCK) {
            if (mActiveRun != runToFinish) {
                return;
            }
            mActiveRun = null;
            mJobManager.onTrainingCompleted(
                    runToFinish.mJobId,
                    runToFinish.mTask.populationName(),
                    runToFinish.mTask.getTrainingIntervalOptions(),
                    taskRetry,
                    trainingResult);
        }
    }

    private void doTraining(TrainingRun run) {
        // TODO: add training logic.
        Log.d(TAG, "Start run training job " + run.mJobId);
    }

    private static final class TrainingRun {
        private final int mJobId;
        private final FederatedTrainingTask mTask;

        private TrainingRun(int jobId, FederatedTrainingTask task) {
            this.mJobId = jobId;
            this.mTask = task;
        }
    }
}
