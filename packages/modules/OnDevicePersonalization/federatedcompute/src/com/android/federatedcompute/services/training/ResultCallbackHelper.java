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

import static android.federatedcompute.common.ClientConstants.RESULT_HANDLING_SERVICE_ACTION;
import static android.federatedcompute.common.ClientConstants.STATUS_SUCCESS;

import android.content.Intent;
import android.federatedcompute.aidl.IFederatedComputeCallback;
import android.federatedcompute.aidl.IResultHandlingService;
import android.federatedcompute.common.ExampleConsumption;
import android.federatedcompute.common.TrainingInterval;
import android.federatedcompute.common.TrainingOptions;
import android.net.Uri;
import android.os.RemoteException;
import android.util.Log;

import com.android.federatedcompute.services.common.Flags;
import com.android.federatedcompute.services.common.TrainingResult;
import com.android.federatedcompute.services.data.FederatedTrainingTask;
import com.android.federatedcompute.services.data.fbs.TrainingIntervalOptions;

import com.google.common.util.concurrent.SettableFuture;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * A helper class for binding to client implemented ResultHandlingService and trigger handleResult.
 */
public class ResultCallbackHelper {
    private static final String TAG = "ResultCallbackHelper";

    /** The outcome of the result handling. */
    public enum CallbackResult {
        // Result handling succeeded, and the task completed.
        SUCCESS,
        // Result handling failed.
        FAIL,
    }

    private final List<ExampleConsumption> mExampleConsumptions;
    private final ResultHandlingServiceProvider mResultHandlingServiceProvider;
    private final Flags mFlags;

    public ResultCallbackHelper(
            List<ExampleConsumption> exampleConsumptions,
            ResultHandlingServiceProvider resultHandlingServiceProvider,
            Flags flags) {
        this.mExampleConsumptions = exampleConsumptions;
        this.mResultHandlingServiceProvider = resultHandlingServiceProvider;
        this.mFlags = flags;
    }

    /** Binds to ResultHandlingService and trigger #handleResult. */
    public CallbackResult callHandleResult(
            FederatedTrainingTask task, @TrainingResult int trainingResult) {
        Intent resultHandlingServiceIntent = new Intent();
        resultHandlingServiceIntent
                .setPackage(task.appPackageName())
                .setAction(RESULT_HANDLING_SERVICE_ACTION)
                .setData(new Uri.Builder().scheme("app").build());
        if (!mResultHandlingServiceProvider.bindService(resultHandlingServiceIntent)) {
            Log.w(
                    TAG,
                    "bindService failed for example store service: " + resultHandlingServiceIntent);
            mResultHandlingServiceProvider.unbindService();
            return CallbackResult.FAIL;
        }
        IResultHandlingService resultHandlingService =
                mResultHandlingServiceProvider.getResultHandlingService();
        SettableFuture<Integer> errorCodeFuture = SettableFuture.create();
        IFederatedComputeCallback callback =
                new IFederatedComputeCallback.Stub() {
                    @Override
                    public void onSuccess() {
                        errorCodeFuture.set(STATUS_SUCCESS);
                    }

                    @Override
                    public void onFailure(int errorCode) {
                        errorCodeFuture.set(errorCode);
                    }
                };
        try {
            resultHandlingService.handleResult(
                    buildTrainingOptions(task),
                    trainingResult == TrainingResult.SUCCESS,
                    mExampleConsumptions,
                    callback);
            int statusCode =
                    errorCodeFuture.get(
                            mFlags.getResultHandlingServiceCallbackTimeoutSecs(), TimeUnit.SECONDS);
            return statusCode == STATUS_SUCCESS ? CallbackResult.SUCCESS : CallbackResult.FAIL;
        } catch (RemoteException e) {
            Log.e(
                    TAG,
                    String.format(
                            "ResultHandlingService binding died %s", resultHandlingServiceIntent),
                    e);
            return CallbackResult.FAIL;
        } catch (InterruptedException interruptedException) {
            Log.e(
                    TAG,
                    String.format(
                            "ResultHandlingService callback interrupted %s",
                            resultHandlingServiceIntent),
                    interruptedException);
            return CallbackResult.FAIL;
        } catch (ExecutionException e) {
            Log.e(
                    TAG,
                    String.format(
                            "ResultHandlingService callback failed %s",
                            resultHandlingServiceIntent),
                    e);
            return CallbackResult.FAIL;
        } catch (TimeoutException e) {
            Log.e(
                    TAG,
                    String.format(
                            "ResultHandlingService callback timed out %d Intent: %s",
                            mFlags.getResultHandlingBindServiceTimeoutSecs(),
                            resultHandlingServiceIntent),
                    e);
        } finally {
            mResultHandlingServiceProvider.unbindService();
        }
        return CallbackResult.FAIL;
    }

    private TrainingOptions buildTrainingOptions(FederatedTrainingTask task) {
        TrainingOptions.Builder trainingOptionsBuilder = new TrainingOptions.Builder();
        trainingOptionsBuilder
                .setJobSchedulerJobId(task.jobId())
                .setPopulationName(task.populationName());
        TrainingIntervalOptions intervalOptions = task.getTrainingIntervalOptions();
        if (intervalOptions != null) {
            TrainingInterval interval =
                    new TrainingInterval.Builder()
                            .setSchedulingMode(intervalOptions.schedulingMode())
                            .setMinimumIntervalMillis(intervalOptions.minIntervalMillis())
                            .build();
            trainingOptionsBuilder.setTrainingInterval(interval);
        }
        return trainingOptionsBuilder.build();
    }
}
