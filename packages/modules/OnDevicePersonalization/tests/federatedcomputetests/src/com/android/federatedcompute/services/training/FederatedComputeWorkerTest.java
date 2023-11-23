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

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.android.federatedcompute.services.common.Flags;
import com.android.federatedcompute.services.common.TrainingResult;
import com.android.federatedcompute.services.data.FederatedTrainingTask;
import com.android.federatedcompute.services.data.fbs.SchedulingMode;
import com.android.federatedcompute.services.data.fbs.SchedulingReason;
import com.android.federatedcompute.services.data.fbs.TrainingConstraints;
import com.android.federatedcompute.services.data.fbs.TrainingIntervalOptions;
import com.android.federatedcompute.services.scheduling.FederatedComputeJobManager;

import com.google.flatbuffers.FlatBufferBuilder;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(JUnit4.class)
public final class FederatedComputeWorkerTest {
    private static final int JOB_ID = 1234;
    private static final String POPULATION_NAME = "barPopulation";
    private static final long CREATION_TIME_MS = 10000L;
    private static final long TASK_EARLIEST_NEXT_RUN_TIME_MS = 1234567L;
    private static final String PACKAGE_NAME = "com.android.federatedcompute.services.training";
    private static final byte[] DEFAULT_TRAINING_CONSTRAINTS =
            createTrainingConstraints(true, true, true);
    private static final long FEDERATED_TRANSIENT_ERROR_RETRY_PERIOD_SECS = 50000;
    private static final byte[] INTERVAL_OPTIONS = createDefaultTrainingIntervalOptions();
    private static final FederatedTrainingTask FEDERATED_TRAINING_TASK_1 =
            FederatedTrainingTask.builder()
                    .appPackageName(PACKAGE_NAME)
                    .creationTime(CREATION_TIME_MS)
                    .lastScheduledTime(TASK_EARLIEST_NEXT_RUN_TIME_MS)
                    .populationName(POPULATION_NAME)
                    .jobId(JOB_ID)
                    .intervalOptions(INTERVAL_OPTIONS)
                    .constraints(DEFAULT_TRAINING_CONSTRAINTS)
                    .earliestNextRunTime(TASK_EARLIEST_NEXT_RUN_TIME_MS)
                    .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                    .build();

    @Mock FederatedComputeJobManager mMockJobManager;
    @Mock private Flags mMockFlags;
    private FederatedComputeWorker mFcpWorker;

    @Before
    public void doBeforeEachTest() {
        MockitoAnnotations.initMocks(this);
        mFcpWorker = new FederatedComputeWorker(mMockJobManager, mMockFlags);
        doNothing()
                .when(mMockJobManager)
                .onTrainingCompleted(anyInt(), anyString(), any(), any(), anyInt());
        when(mMockFlags.getTransientErrorRetryDelayJitterPercent()).thenReturn(0.1f);
        when(mMockFlags.getTransientErrorRetryDelaySecs())
                .thenReturn(FEDERATED_TRANSIENT_ERROR_RETRY_PERIOD_SECS);
    }

    @Test
    public void testTrainingSuccess() {
        when(mMockJobManager.onTrainingStarted(anyInt())).thenReturn(FEDERATED_TRAINING_TASK_1);
        boolean result = mFcpWorker.startTrainingRun(JOB_ID);

        assertTrue(result);
        verify(mMockJobManager, times(1))
                .onTrainingCompleted(
                        eq(JOB_ID), eq(POPULATION_NAME), any(), any(), eq(TrainingResult.SUCCESS));
    }

    @Test
    public void testTrainingFailure_nonExist() {
        when(mMockJobManager.onTrainingStarted(anyInt())).thenReturn(null);
        boolean result = mFcpWorker.startTrainingRun(JOB_ID);

        assertFalse(result);
        verify(mMockJobManager, times(0))
                .onTrainingCompleted(eq(JOB_ID), eq(POPULATION_NAME), any(), any(), anyInt());
    }

    private static byte[] createTrainingConstraints(
            boolean requiresSchedulerIdle,
            boolean requiresSchedulerCharging,
            boolean requiresSchedulerUnmeteredNetwork) {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        builder.finish(
                TrainingConstraints.createTrainingConstraints(
                        builder,
                        requiresSchedulerIdle,
                        requiresSchedulerCharging,
                        requiresSchedulerUnmeteredNetwork));
        return builder.sizedByteArray();
    }

    private static byte[] createDefaultTrainingIntervalOptions() {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        builder.finish(
                TrainingIntervalOptions.createTrainingIntervalOptions(
                        builder, SchedulingMode.ONE_TIME, 0));
        return builder.sizedByteArray();
    }
}
