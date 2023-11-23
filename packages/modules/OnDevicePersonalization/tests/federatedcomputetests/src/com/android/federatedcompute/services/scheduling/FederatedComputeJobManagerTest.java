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

package com.android.federatedcompute.services.scheduling;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.when;

import static java.lang.Math.min;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.federatedcompute.aidl.IFederatedComputeCallback;
import android.federatedcompute.common.TrainingInterval;
import android.federatedcompute.common.TrainingOptions;

import androidx.test.core.app.ApplicationProvider;

import com.android.federatedcompute.services.common.Clock;
import com.android.federatedcompute.services.common.Flags;
import com.android.federatedcompute.services.common.TaskRetry;
import com.android.federatedcompute.services.common.TrainingResult;
import com.android.federatedcompute.services.data.FederatedTrainingTask;
import com.android.federatedcompute.services.data.FederatedTrainingTaskDao;
import com.android.federatedcompute.services.data.FederatedTrainingTaskDbHelper;
import com.android.federatedcompute.services.data.fbs.SchedulingMode;
import com.android.federatedcompute.services.data.fbs.SchedulingReason;
import com.android.federatedcompute.services.data.fbs.TrainingConstraints;
import com.android.federatedcompute.services.data.fbs.TrainingIntervalOptions;

import com.google.flatbuffers.FlatBufferBuilder;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CountDownLatch;

import javax.annotation.Nullable;

@RunWith(MockitoJUnitRunner.class)
public final class FederatedComputeJobManagerTest {
    private static final String POPULATION_NAME1 = "population1";
    private static final String POPULATION_NAME2 = "population2";
    private static final int JOB_ID1 = 700000001;
    private static final int JOB_ID2 = 700000002;
    private static final long DEFAULT_SCHEDULING_PERIOD_SECS = 1234;
    private static final long DEFAULT_SCHEDULING_PERIOD_MILLIS =
            DEFAULT_SCHEDULING_PERIOD_SECS * 1000;
    private static final long MAX_SCHEDULING_PERIOD_SECS = 912000;
    private static final long MAX_SCHEDULING_INTERVAL_SECS_FOR_FEDERATED_COMPUTATION = 604800L;

    private static final String TRAINING_JOB_SERVICE =
            "com.android.federatedcompute.services.training.FederatedJobService";
    private static final long CURRENT_TIME_MILLIS = 1000L;
    private static final byte[] DEFAULT_CONSTRAINTS = createDefaultTrainingConstraints();
    private static final TrainingOptions OPTIONS1 =
            new TrainingOptions.Builder()
                    .setPopulationName(POPULATION_NAME1)
                    .setJobSchedulerJobId(JOB_ID1)
                    .build();
    private static final TrainingOptions OPTIONS2 =
            new TrainingOptions.Builder()
                    .setPopulationName(POPULATION_NAME2)
                    .setJobSchedulerJobId(JOB_ID2)
                    .build();
    private static final TaskRetry TASK_RETRY =
            new TaskRetry.Builder().setMinDelay(5000000).setMaxDelay(6000000).build();

    private FederatedComputeJobManager mJobManager;
    private Context mContext;
    private FederatedTrainingTaskDao mTrainingTaskDao;
    private boolean mSuccess = false;
    private final CountDownLatch mLatch = new CountDownLatch(1);

    @Mock private Clock mClock;
    @Mock private Flags mMockFlags;
    private JobScheduler mJobScheduler;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
        mJobScheduler = mContext.getSystemService(JobScheduler.class);
        mJobScheduler.cancelAll();
        mTrainingTaskDao = FederatedTrainingTaskDao.getInstanceForTest(mContext);
        mJobManager =
                new FederatedComputeJobManager(
                        mContext,
                        mTrainingTaskDao,
                        new JobSchedulerHelper(mClock),
                        mClock,
                        mMockFlags);
        when(mClock.currentTimeMillis()).thenReturn(CURRENT_TIME_MILLIS);
        when(mMockFlags.getDefaultSchedulingPeriodSecs())
                .thenReturn(DEFAULT_SCHEDULING_PERIOD_SECS);
        when(mMockFlags.getMaxSchedulingIntervalSecsForFederatedComputation())
                .thenReturn(MAX_SCHEDULING_INTERVAL_SECS_FOR_FEDERATED_COMPUTATION);
        when(mMockFlags.getMinSchedulingIntervalSecsForFederatedComputation()).thenReturn(1L);
        when(mMockFlags.getMaxSchedulingPeriodSecs()).thenReturn(MAX_SCHEDULING_PERIOD_SECS);
    }

    @After
    public void tearDown() {
        // Manually clean up the database.
        mTrainingTaskDao.clearDatabase();
        FederatedTrainingTaskDbHelper dbHelper =
                FederatedTrainingTaskDbHelper.getInstanceForTest(mContext);
        dbHelper.getWritableDatabase().close();
        dbHelper.getReadableDatabase().close();
        dbHelper.close();
    }

    @Test
    public void testOnTrainerStartCalledSuccess() throws Exception {
        when(mClock.currentTimeMillis()).thenReturn(1000L).thenReturn(2000L);

        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());

        assertThat(mSuccess).isTrue();
        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        assertThat(taskList)
                .containsExactly(
                        basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, null)
                                .creationTime(1000L)
                                .lastScheduledTime(1000L)
                                .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                                .earliestNextRunTime(1000 + DEFAULT_SCHEDULING_PERIOD_MILLIS)
                                .build());
    }

    @Test
    public void testOnTrainerStartCalled_firstTime() throws Exception {
        when(mClock.currentTimeMillis()).thenReturn(1000L);
        // Make three onTrainerStart calls, each with different job ID and session name.
        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());
        when(mClock.currentTimeMillis()).thenReturn(2000L);
        mJobManager.onTrainerStartCalled(OPTIONS2, new TestFederatedComputeCallback());
        mLatch.await();

        assertThat(mSuccess).isTrue();
        // verify training tasks in database.
        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        assertThat(taskList)
                .containsExactly(
                        basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, null)
                                .creationTime(1000L)
                                .lastScheduledTime(1000L)
                                .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                                .earliestNextRunTime(1000 + DEFAULT_SCHEDULING_PERIOD_MILLIS)
                                .build(),
                        basicFLTrainingTaskBuilder(JOB_ID2, POPULATION_NAME2, null)
                                .creationTime(2000L)
                                .lastScheduledTime(2000L)
                                .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                                .earliestNextRunTime(2000 + DEFAULT_SCHEDULING_PERIOD_MILLIS)
                                .build());

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(2);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, DEFAULT_SCHEDULING_PERIOD_MILLIS));
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID2),
                buildExpectedJobInfo(JOB_ID2, DEFAULT_SCHEDULING_PERIOD_MILLIS));
    }

    @Test
    public void testOnTrainerStartCalledFL_withIntervalSmallerThanDefaultInterval()
            throws Exception {
        testOnTrainerStartCalledFLWithInterval(
                /* userDefinedIntervalMillis= */ 1000000, /* defaultIntervalMillis= */ 2000000);
    }

    @Test
    public void testOnTrainerStartCalledFL_withIntervalLargerThanDefaultInterval()
            throws Exception {
        testOnTrainerStartCalledFLWithInterval(
                /* userDefinedIntervalMillis= */ 2000000, /* defaultIntervalMillis= */ 1000000);
    }

    private void testOnTrainerStartCalledFLWithInterval(
            long userDefinedIntervalMillis, long defaultIntervalMillis) throws Exception {
        when(mMockFlags.getDefaultSchedulingPeriodSecs()).thenReturn(defaultIntervalMillis / 1000);
        TrainingOptions trainerOptions =
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(userDefinedIntervalMillis)
                                        .build())
                        .build();
        mJobManager.onTrainerStartCalled(trainerOptions, new TestFederatedComputeCallback());

        byte[] trainingIntervalOptions =
                createTrainingIntervalOptions(SchedulingMode.RECURRENT, userDefinedIntervalMillis);

        long expectedInterval = min(userDefinedIntervalMillis, defaultIntervalMillis);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, trainingIntervalOptions)
                        .earliestNextRunTime(CURRENT_TIME_MILLIS + expectedInterval)
                        .lastScheduledTime(CURRENT_TIME_MILLIS)
                        .creationTime(CURRENT_TIME_MILLIS)
                        .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                        .build();
        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);

        assertThat(taskList).containsExactly(expectedTask);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, expectedInterval));
    }

    /**
     * Tests onTrainerStart being called multiple times with the same parameters (the common
     * expected use case).
     *
     * <p>After the first call, most fields in the task (like creation time, earliest next run time,
     * etc.) must be preserved, and only certain fields (like last scheduled time) should be
     * updated.
     */
    @Test
    public void testOnTrainerStartCalled_multipleTimes_sameParams() throws Exception {
        when(mClock.currentTimeMillis()).thenReturn(1000L);
        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());

        when(mClock.currentTimeMillis()).thenReturn(2000L);
        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());

        when(mClock.currentTimeMillis()).thenReturn(3000L);
        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());

        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, null)
                        .earliestNextRunTime(1000 + DEFAULT_SCHEDULING_PERIOD_MILLIS)
                        .lastScheduledTime(3000L)
                        .creationTime(1000L)
                        .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, DEFAULT_SCHEDULING_PERIOD_MILLIS));
    }

    /**
     * Tests when the user specified interval is larger than the maximum server specified interval,
     * multiple scheduling with same user specified interval will not be incorrectly capped at the
     * maximum server specified interval.
     */
    @Test
    public void testOnTrainerStartCalled_multipleTimes_sameParamsFLWithIntervalLargerThanServerMax()
            throws Exception {
        long minIntervalMills = 10000L; // 10 seconds
        // Maximum server specified interval is 5 seconds
        when(mMockFlags.getMaxSchedulingPeriodSecs()).thenReturn(5L);
        TrainingOptions trainingOptions =
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(minIntervalMills)
                                        .build())
                        .build();

        when(mClock.currentTimeMillis()).thenReturn(1000L);
        mJobManager.onTrainerStartCalled(trainingOptions, new TestFederatedComputeCallback());

        when(mClock.currentTimeMillis()).thenReturn(2000L);
        mJobManager.onTrainerStartCalled(trainingOptions, new TestFederatedComputeCallback());

        when(mClock.currentTimeMillis()).thenReturn(3000L);
        mJobManager.onTrainerStartCalled(trainingOptions, new TestFederatedComputeCallback());

        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        byte[] expectedInterval =
                createTrainingIntervalOptions(SchedulingMode.RECURRENT, minIntervalMills);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, expectedInterval)
                        .earliestNextRunTime(1000 + minIntervalMills)
                        .lastScheduledTime(3000L)
                        .creationTime(1000L)
                        .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, minIntervalMills));
    }

    /**
     * Tests when a task got scheduled with the same set of parameters multiple times, the brella
     * defined max for user specified interval has been lowered between the multiple scheduling
     * events, the user specified interval should be always guarded with the latest max.
     */
    @Test
    public void testOnTrainerStartCalled_multipleTimes_sameParamsFLWithIntervalDifferentMax()
            throws Exception {
        // Initial max 20 seconds is larger than the user specified interval.
        when(mMockFlags.getMaxSchedulingIntervalSecsForFederatedComputation()).thenReturn(20L);
        long minIntervalMills = 10000L; // 10 seconds
        TrainingOptions trainingOptions =
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(minIntervalMills)
                                        .build())
                        .build();

        when(mClock.currentTimeMillis()).thenReturn(1000L);
        mJobManager.onTrainerStartCalled(trainingOptions, new TestFederatedComputeCallback());

        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        byte[] expectedInterval =
                createTrainingIntervalOptions(SchedulingMode.RECURRENT, minIntervalMills);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, expectedInterval)
                        .earliestNextRunTime(1000L + minIntervalMills)
                        .lastScheduledTime(1000L)
                        .creationTime(1000L)
                        .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, minIntervalMills));

        // Now lower allowed max for the user specified interval
        long newMaxSec = 5L;
        long newMinIntervalMills = newMaxSec * 1000;
        when(mMockFlags.getMaxSchedulingIntervalSecsForFederatedComputation())
                .thenReturn(newMaxSec);
        TrainingOptions newTrainingOptions =
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(newMinIntervalMills)
                                        .build())
                        .build();

        when(mClock.currentTimeMillis()).thenReturn(2000L);
        mJobManager.onTrainerStartCalled(newTrainingOptions, new TestFederatedComputeCallback());

        taskList = mTrainingTaskDao.getFederatedTrainingTask(null, null);
        expectedInterval =
                createTrainingIntervalOptions(SchedulingMode.RECURRENT, newMinIntervalMills);
        expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, expectedInterval)
                        .earliestNextRunTime(2000L + newMinIntervalMills)
                        .lastScheduledTime(2000L)
                        .creationTime(1000L)
                        .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);
        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, newMinIntervalMills));
    }

    @Test
    public void testOnTrainerStartCalled_fLCustomerSpecifiedIntervalSmallerThanDefinedMin()
            throws Exception {
        when(mMockFlags.getDefaultSchedulingPeriodSecs()).thenReturn(2000L);
        long minTrainingIntervalSecByFederatedCompute = 1800L;
        long minTrainingIntervalMillsByFederatedCompute =
                minTrainingIntervalSecByFederatedCompute * 1000;
        when(mMockFlags.getMinSchedulingIntervalSecsForFederatedComputation())
                .thenReturn(minTrainingIntervalSecByFederatedCompute);

        TrainingOptions trainingOptions =
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(1000L)
                                        .build())
                        .build();

        when(mClock.currentTimeMillis()).thenReturn(1000L);
        mJobManager.onTrainerStartCalled(trainingOptions, new TestFederatedComputeCallback());

        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        byte[] expectedInterval = createTrainingIntervalOptions(SchedulingMode.RECURRENT, 1000L);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, expectedInterval)
                        .earliestNextRunTime(1000L + minTrainingIntervalMillsByFederatedCompute)
                        .lastScheduledTime(1000L)
                        .creationTime(1000L)
                        .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, minTrainingIntervalMillsByFederatedCompute));
    }

    @Test
    public void testOnTrainerStartCalled_trainingIntervalChange_FL() throws Exception {
        when(mClock.currentTimeMillis()).thenReturn(1000L);
        mJobManager.onTrainerStartCalled(
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1).build(),
                new TestFederatedComputeCallback());

        long minTrainingIntervalMillis = 60000L;
        when(mClock.currentTimeMillis()).thenReturn(2000L);
        mJobManager.onTrainerStartCalled(
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(minTrainingIntervalMillis)
                                        .build())
                        .build(),
                new TestFederatedComputeCallback());
        byte[] trainingInterval =
                createTrainingIntervalOptions(SchedulingMode.RECURRENT, minTrainingIntervalMillis);
        verifyTaskAndJobAfterIntervalChange(
                trainingInterval, 1000, 2000, minTrainingIntervalMillis);

        long newInterval = 70000L;
        when(mClock.currentTimeMillis()).thenReturn(3000L);
        mJobManager.onTrainerStartCalled(
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(newInterval)
                                        .build())
                        .build(),
                new TestFederatedComputeCallback());
        byte[] trainingIntervalOption2 =
                createTrainingIntervalOptions(SchedulingMode.RECURRENT, newInterval);
        // Verify the creation time not changed, modified time is set to now, and the min interval
        // is set to the new interval.
        verifyTaskAndJobAfterIntervalChange(trainingIntervalOption2, 1000, 3000, newInterval);

        when(mClock.currentTimeMillis()).thenReturn(4000L);
        mJobManager.onTrainerStartCalled(
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_ONE_TIME)
                                        .build())
                        .build(),
                new TestFederatedComputeCallback());
        byte[] trainingIntervalOption3 = createTrainingIntervalOptions(SchedulingMode.ONE_TIME, 0L);
        // Verify the creation time not changed, modified time is set to now, and the min interval
        // is set to the new interval.
        verifyTaskAndJobAfterIntervalChange(
                trainingIntervalOption3, 1000, 4000, DEFAULT_SCHEDULING_PERIOD_MILLIS);

        // Transition back to not set
        when(mClock.currentTimeMillis()).thenReturn(5000L);
        mJobManager.onTrainerStartCalled(
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1).build(),
                new TestFederatedComputeCallback());
        verifyTaskAndJobAfterIntervalChange(null, 1000, 5000, DEFAULT_SCHEDULING_PERIOD_MILLIS);
    }

    private void verifyTaskAndJobAfterIntervalChange(
            @Nullable byte[] trainingIntervalOptions,
            long createTimeMillis,
            long modifyTimeMillis,
            long expectedIntervalMillis)
            throws Exception {
        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, trainingIntervalOptions)
                        .earliestNextRunTime(modifyTimeMillis + expectedIntervalMillis)
                        .lastScheduledTime(modifyTimeMillis)
                        .creationTime(createTimeMillis)
                        .schedulingReason(SchedulingReason.SCHEDULING_REASON_NEW_TASK)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, expectedIntervalMillis));
    }

    @Test
    public void testOnTrainerStartCalled_multipleTimes_changingPopulationName() throws Exception {
        // Only change the population name.
        int jobId = JOB_ID1;
        doTestOnTrainerStartCalled_multipleTimes_changingParams(
                jobId,
                POPULATION_NAME1,
                jobId,
                POPULATION_NAME2,
                SchedulingReason.SCHEDULING_REASON_NEW_TASK);
    }

    @Test
    public void testOnTrainerStartCalled_twoJobsWithSamePopulationName() throws Exception {
        // Change both the job ID and session name between Trainer.start calls.
        doTestOnTrainerStartCalled_multipleTimes_changingParams(
                JOB_ID1,
                POPULATION_NAME1,
                JOB_ID2,
                POPULATION_NAME1,
                SchedulingReason.SCHEDULING_REASON_NEW_TASK);
    }

    @Test
    public void testOnTrainerStartCalled_multipleTimes_changingJobId() throws Exception {
        // Only change the job ID.
        String populationName = POPULATION_NAME1;
        doTestOnTrainerStartCalled_multipleTimes_changingParams(
                JOB_ID1,
                populationName,
                JOB_ID2,
                populationName,
                SchedulingReason.SCHEDULING_REASON_NEW_TASK);
    }

    private void doTestOnTrainerStartCalled_multipleTimes_changingParams(
            int jobId1,
            String populationName1,
            int jobId2,
            String populationName2,
            int expectedSchedulingReason)
            throws Exception {
        when(mClock.currentTimeMillis()).thenReturn(1000L);
        TrainingOptions options1 =
                new TrainingOptions.Builder()
                        .setPopulationName(populationName1)
                        .setJobSchedulerJobId(jobId1)
                        .build();
        mJobManager.onTrainerStartCalled(options1, new TestFederatedComputeCallback());

        // Pass in a new population name.
        when(mClock.currentTimeMillis()).thenReturn(2000L);
        TrainingOptions options2 =
                new TrainingOptions.Builder()
                        .setPopulationName(populationName2)
                        .setJobSchedulerJobId(jobId2)
                        .build();
        mJobManager.onTrainerStartCalled(options2, new TestFederatedComputeCallback());

        long earliestNextRunTimeMillis = 2000 + DEFAULT_SCHEDULING_PERIOD_MILLIS;
        long minLatencyMillis = DEFAULT_SCHEDULING_PERIOD_MILLIS;
        // If none of the job id, session name, population name and InAppTrainingConstraints
        // changes,
        // the previous earliest next
        // run time will not change.
        if (jobId1 == jobId2 && populationName1.equals(populationName2)) {
            earliestNextRunTimeMillis = 1000 + DEFAULT_SCHEDULING_PERIOD_MILLIS;
        }
        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(jobId2, populationName2, null)
                        .earliestNextRunTime(earliestNextRunTimeMillis)
                        .lastScheduledTime(2000L)
                        .creationTime(populationName1.equals(populationName2) ? 1000L : 2000L)
                        .constraints(DEFAULT_CONSTRAINTS)
                        .schedulingReason(expectedSchedulingReason)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(jobId2),
                buildExpectedJobInfo(jobId2, minLatencyMillis));
    }

    @Test
    public void testOnTrainingStarted_doesNotExist() throws Exception {
        when(mClock.currentTimeMillis()).thenReturn(1000L);
        FederatedTrainingTask taskToRun = mJobManager.onTrainingStarted(JOB_ID1);

        // No task should be found.
        assertThat(taskToRun).isNull();
        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        assertThat(taskList).isEmpty();
    }

    @Test
    public void testOnTrainingStarted_taskTtling_noTtlSet() throws Exception {
        // Set task TTL to 0, which should disable TTLing.
        when(mMockFlags.getTrainingTimeForLiveSeconds()).thenReturn(0L);

        long nowMillis = 1000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());

        // Simulate attempting to run a task a lot later. This should not fail, b/c we're not yet
        // past the TTL threshold.
        assertThat(mJobManager.onTrainingStarted(JOB_ID1)).isNotNull();
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).hasSize(1);
    }

    @Test
    public void testOnTrainingStarted_taskTtling() throws Exception {
        // Set task TTL to 1 second.
        when(mMockFlags.getTrainingTimeForLiveSeconds()).thenReturn(1L);

        when(mClock.currentTimeMillis()).thenReturn(1000L);
        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());

        // Simulate attempting to run a task one second later. This should not fail, b/c we're not
        // yet
        // past the TTL threshold.
        long nowMillis = 2000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        assertThat(mJobManager.onTrainingStarted(JOB_ID1)).isNotNull();
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).hasSize(1);

        // Now reschedule again, should keep the task alive for another second.
        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());

        // The task should again still be alive a second later.
        nowMillis = 3000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        assertThat(mJobManager.onTrainingStarted(JOB_ID1)).isNotNull();

        // Now move forward one millisecond. The task should now get TTLd.
        nowMillis = 3001;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        assertThat(mJobManager.onTrainingStarted(JOB_ID1)).isNull();
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).isEmpty();
    }

    @Test
    public void testRescheduleFLTask_success() throws Exception {
        long nowMillis = 1000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());

        nowMillis = 2000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingStarted(JOB_ID1);

        nowMillis = 3000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingCompleted(
                JOB_ID1,
                POPULATION_NAME1,
                createTrainingIntervalOptionsAsRoot(SchedulingMode.RECURRENT, 0),
                TASK_RETRY,
                TrainingResult.SUCCESS);

        assertThat(mJobManager.onTrainingStarted(JOB_ID1)).isNotNull();
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).hasSize(1);
    }

    @Test
    public void testRescheduleFLTask_oneoff_success() throws Exception {
        long nowMillis = 1000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainerStartCalled(OPTIONS1, new TestFederatedComputeCallback());

        nowMillis = 2000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingStarted(JOB_ID1);

        nowMillis = 3000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingCompleted(
                JOB_ID1,
                POPULATION_NAME1,
                createTrainingIntervalOptionsAsRoot(SchedulingMode.ONE_TIME, 0),
                TASK_RETRY,
                TrainingResult.SUCCESS);

        assertThat(mJobManager.onTrainingStarted(JOB_ID1)).isNull();
        assertThat(mTrainingTaskDao.getFederatedTrainingTask(null, null)).isEmpty();
    }

    @Test
    public void testRescheduleFLTask_didnotContribute_oneOff() throws Exception {
        long serverRetryDelayMillis = 5000_000;

        long nowMillis = 1000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        TrainingOptions trainerOptions =
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_ONE_TIME)
                                        .build())
                        .build();
        mJobManager.onTrainerStartCalled(trainerOptions, new TestFederatedComputeCallback());

        nowMillis = 2000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingStarted(JOB_ID1);

        nowMillis = 3000;
        byte[] intervalOptions = createTrainingIntervalOptions(SchedulingMode.ONE_TIME, 0);
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingCompleted(
                JOB_ID1,
                POPULATION_NAME1,
                TrainingIntervalOptions.getRootAsTrainingIntervalOptions(
                        ByteBuffer.wrap(intervalOptions)),
                new TaskRetry.Builder()
                        .setMinDelay(serverRetryDelayMillis)
                        .setMaxDelay(serverRetryDelayMillis)
                        .build(),
                TrainingResult.FAIL);

        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, intervalOptions)
                        .creationTime(1000L)
                        .lastScheduledTime(1000L)
                        .lastRunStartTime(2000L)
                        .lastRunEndTime(3000L)
                        .schedulingReason(
                                SchedulingReason.SCHEDULING_REASON_FEDERATED_COMPUTATION_RETRY)
                        .earliestNextRunTime(3000 + serverRetryDelayMillis)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, serverRetryDelayMillis));
    }

    /** Reschedule a recurrent fl task with the user defined interval. */
    @Test
    public void testRescheduleFLTask_success_recurrent_userDefinedInterval() throws Exception {
        // The user defined interval is larger than the server specified interval.
        long minRetryDelayMillis = 3000_000;
        long maxRetryDelayMillis = 3000_000;
        long userDefinedIntervalMillis = 4000_000;
        TrainingOptions trainerOptions =
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(userDefinedIntervalMillis)
                                        .build())
                        .build();
        long nowMillis = 1000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainerStartCalled(trainerOptions, new TestFederatedComputeCallback());

        nowMillis = 2000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingStarted(JOB_ID1);

        nowMillis = 3000;
        byte[] intervalOptions =
                createTrainingIntervalOptions(SchedulingMode.RECURRENT, userDefinedIntervalMillis);
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingCompleted(
                JOB_ID1,
                POPULATION_NAME1,
                TrainingIntervalOptions.getRootAsTrainingIntervalOptions(
                        ByteBuffer.wrap(intervalOptions)),
                new TaskRetry.Builder()
                        .setMinDelay(minRetryDelayMillis)
                        .setMaxDelay(maxRetryDelayMillis)
                        .build(),
                TrainingResult.SUCCESS);

        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, intervalOptions)
                        .creationTime(1000L)
                        .lastScheduledTime(1000L)
                        .lastRunStartTime(2000L) // Match the time of calling onTrainingStarted()
                        .lastRunEndTime(3000L) // Match the time of calling onTrainingCompleted()
                        .schedulingReason(
                                SchedulingReason.SCHEDULING_REASON_FEDERATED_COMPUTATION_RETRY)
                        .earliestNextRunTime(3000 + userDefinedIntervalMillis)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, userDefinedIntervalMillis));
    }

    @Test
    public void testRescheduleFLTask_recurrent_serverDefinedInterval() throws Exception {
        // Define a server returned interval which is larger than the user defined interval
        long serverDefinedIntervalMillis = 4000_000;
        long userDefinedIntervalMillis = 3000_000;

        TrainingOptions trainerOptions =
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(userDefinedIntervalMillis)
                                        .build())
                        .build();

        long nowMillis = 1000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainerStartCalled(trainerOptions, new TestFederatedComputeCallback());

        nowMillis = 2000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingStarted(JOB_ID1);

        nowMillis = 3000;
        byte[] intervalOptions =
                createTrainingIntervalOptions(SchedulingMode.RECURRENT, userDefinedIntervalMillis);
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingCompleted(
                JOB_ID1,
                POPULATION_NAME1,
                TrainingIntervalOptions.getRootAsTrainingIntervalOptions(
                        ByteBuffer.wrap(intervalOptions)),
                new TaskRetry.Builder()
                        .setMinDelay(serverDefinedIntervalMillis)
                        .setMaxDelay(serverDefinedIntervalMillis)
                        .build(),
                TrainingResult.SUCCESS);

        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, intervalOptions)
                        .creationTime(1000L)
                        .lastScheduledTime(1000L)
                        .lastRunStartTime(2000L) // Match the time of calling onTrainingStarted()
                        .lastRunEndTime(3000L) // Match the time of calling onTrainingCompleted()
                        .schedulingReason(
                                SchedulingReason.SCHEDULING_REASON_FEDERATED_COMPUTATION_RETRY)
                        .earliestNextRunTime(3000 + serverDefinedIntervalMillis)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, serverDefinedIntervalMillis));
    }

    @Test
    public void testRescheduleFLTask_recurrent_didnotContribute() throws Exception {
        // Define a server returned interval which is larger than the user defined interval
        long serverDefinedIntervalMillis = 4000_000;
        long userDefinedIntervalMillis = 3000_000;

        TrainingOptions trainerOptions =
                basicFLOptionsBuilder(JOB_ID1, POPULATION_NAME1)
                        .setTrainingInterval(
                                new TrainingInterval.Builder()
                                        .setSchedulingMode(
                                                TrainingInterval.SCHEDULING_MODE_RECURRENT)
                                        .setMinimumIntervalMillis(userDefinedIntervalMillis)
                                        .build())
                        .build();

        long nowMillis = 1000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainerStartCalled(trainerOptions, new TestFederatedComputeCallback());

        nowMillis = 2000;
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingStarted(JOB_ID1);

        nowMillis = 3000;
        byte[] intervalOptions =
                createTrainingIntervalOptions(SchedulingMode.RECURRENT, userDefinedIntervalMillis);
        when(mClock.currentTimeMillis()).thenReturn(nowMillis);
        mJobManager.onTrainingCompleted(
                JOB_ID1,
                POPULATION_NAME1,
                TrainingIntervalOptions.getRootAsTrainingIntervalOptions(
                        ByteBuffer.wrap(intervalOptions)),
                new TaskRetry.Builder()
                        .setMinDelay(serverDefinedIntervalMillis)
                        .setMaxDelay(serverDefinedIntervalMillis)
                        .build(),
                TrainingResult.FAIL);

        List<FederatedTrainingTask> taskList =
                mTrainingTaskDao.getFederatedTrainingTask(null, null);
        FederatedTrainingTask expectedTask =
                basicFLTrainingTaskBuilder(JOB_ID1, POPULATION_NAME1, intervalOptions)
                        .creationTime(1000L)
                        .lastScheduledTime(1000L)
                        .lastRunStartTime(2000L) // Match the time of calling onTrainingStarted()
                        .lastRunEndTime(3000L) // Match the time of calling onTrainingCompleted()
                        .schedulingReason(
                                SchedulingReason.SCHEDULING_REASON_FEDERATED_COMPUTATION_RETRY)
                        .earliestNextRunTime(3000 + serverDefinedIntervalMillis)
                        .build();
        assertThat(taskList).containsExactly(expectedTask);

        assertThat(mJobScheduler.getAllPendingJobs()).hasSize(1);
        assertJobInfosMatch(
                mJobScheduler.getPendingJob(JOB_ID1),
                buildExpectedJobInfo(JOB_ID1, serverDefinedIntervalMillis));
    }

    /**
     * Helper for checking that two JobInfos match, since JobInfos unfortunately can't be compared
     * directly.
     */
    public static void assertJobInfosMatch(JobInfo pendingJob, JobInfo expectedJobInfo) {
        // Compare most of JobInfo's properties that may be set by our code.
        assertWithMessage("id").that(pendingJob.getId()).isEqualTo(expectedJobInfo.getId());
        assertWithMessage("service")
                .that(pendingJob.getService())
                .isEqualTo(expectedJobInfo.getService());
        assertWithMessage("persisted")
                .that(pendingJob.isPersisted())
                .isEqualTo(expectedJobInfo.isPersisted());
        assertWithMessage("networkType")
                .that(pendingJob.getNetworkType())
                .isEqualTo(expectedJobInfo.getNetworkType());
        assertWithMessage("requireDeviceIdle")
                .that(pendingJob.isRequireDeviceIdle())
                .isEqualTo(expectedJobInfo.isRequireDeviceIdle());
        assertWithMessage("requireCharging")
                .that(pendingJob.isRequireCharging())
                .isEqualTo(expectedJobInfo.isRequireCharging());
        assertWithMessage("minLatencyMillis")
                .that(pendingJob.getMinLatencyMillis())
                .isEqualTo(expectedJobInfo.getMinLatencyMillis());
        assertWithMessage("maxExecutionDelayMillis")
                .that(pendingJob.getMaxExecutionDelayMillis())
                .isEqualTo(expectedJobInfo.getMaxExecutionDelayMillis());
    }

    private static TrainingOptions.Builder basicFLOptionsBuilder(int jobId, String population) {
        return new TrainingOptions.Builder()
                .setPopulationName(population)
                .setJobSchedulerJobId(jobId);
    }

    private JobInfo buildExpectedJobInfo(int jobId, long minLatencyMillis) {
        JobInfo.Builder jobInfo =
                new JobInfo.Builder(
                                jobId,
                                new ComponentName(mContext.getPackageName(), TRAINING_JOB_SERVICE))
                        .setPersisted(true)
                        .setRequiresDeviceIdle(true)
                        // the latency should be capped.
                        .setMinimumLatency(minLatencyMillis)
                        .setRequiresCharging(true);
        jobInfo.setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED);

        return jobInfo.build();
    }

    private FederatedTrainingTask.Builder basicFLTrainingTaskBuilder(
            int jobId, String population, @Nullable byte[] trainingIntervalOptions) {
        FederatedTrainingTask.Builder builder =
                FederatedTrainingTask.builder()
                        .jobId(jobId)
                        .populationName(population)
                        .lastScheduledTime(0L)
                        .lastRunStartTime(0L)
                        .lastRunEndTime(0L)
                        .constraints(DEFAULT_CONSTRAINTS)
                        .appPackageName(mContext.getPackageName());
        if (trainingIntervalOptions != null) {
            builder.intervalOptions(trainingIntervalOptions);
        }
        return builder;
    }

    private static TrainingIntervalOptions createTrainingIntervalOptionsAsRoot(
            int schedulingMode, long intervalMillis) {
        byte[] intervalOptions = createTrainingIntervalOptions(schedulingMode, intervalMillis);
        return TrainingIntervalOptions.getRootAsTrainingIntervalOptions(
                ByteBuffer.wrap(intervalOptions));
    }

    private static byte[] createTrainingIntervalOptions(int schedulingMode, long intervalMillis) {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        builder.finish(
                TrainingIntervalOptions.createTrainingIntervalOptions(
                        builder, schedulingMode, intervalMillis));
        return builder.sizedByteArray();
    }

    private static byte[] createDefaultTrainingConstraints() {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        builder.finish(TrainingConstraints.createTrainingConstraints(builder, true, true, true));
        return builder.sizedByteArray();
    }

    class TestFederatedComputeCallback extends IFederatedComputeCallback.Stub {
        @Override
        public void onSuccess() {
            mSuccess = true;
            mLatch.countDown();
        }

        @Override
        public void onFailure(int errorCode) {
            mLatch.countDown();
        }
    }
}
