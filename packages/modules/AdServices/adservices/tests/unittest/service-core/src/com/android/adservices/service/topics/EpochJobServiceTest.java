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

package com.android.adservices.service.topics;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_EXTSERVICES_JOB_ON_TPLUS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_KILL_SWITCH_ON;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__TOPICS_API_DISABLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS;
import static com.android.adservices.spe.AdservicesJobInfo.TOPICS_EPOCH_JOB;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.staticMockMarker;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.errorlogging.ErrorLogUtil;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.compat.ServiceCompatUtils;
import com.android.adservices.service.stats.Clock;
import com.android.adservices.service.stats.StatsdAdServicesLogger;
import com.android.adservices.spe.AdservicesJobServiceLogger;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Unit tests for {@link com.android.adservices.service.topics.EpochJobService} */
@SuppressWarnings("ConstantConditions")
public class EpochJobServiceTest {
    private static final int BINDER_CONNECTION_TIMEOUT_MS = 5_000;
    private static final int TOPICS_EPOCH_JOB_ID = TOPICS_EPOCH_JOB.getJobId();
    private static final long EPOCH_JOB_PERIOD_MS = 10_000L;
    private static final long EPOCH_JOB_FLEX_MS = 1_000L;
    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    private static final JobScheduler JOB_SCHEDULER = CONTEXT.getSystemService(JobScheduler.class);
    private static final Flags TEST_FLAGS = FlagsFactory.getFlagsForTest();

    @Spy private EpochJobService mSpyEpochJobService;
    private MockitoSession mStaticMockSession;

    // Mock EpochManager and CacheManager as the methods called are tested in corresponding
    // unit test. In this test, only verify whether specific method is initiated.
    @Mock EpochManager mMockEpochManager;
    @Mock CacheManager mMockCacheManager;
    @Mock BlockedTopicsManager mBlockedTopicsManager;
    @Mock AppUpdateManager mMockAppUpdateManager;
    @Mock JobParameters mMockJobParameters;
    @Mock Flags mMockFlags;
    @Mock JobScheduler mMockJobScheduler;
    @Mock StatsdAdServicesLogger mMockStatsdLogger;
    private AdservicesJobServiceLogger mSpyLogger;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        // Start a mockitoSession to mock static method
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(EpochJobService.class)
                        .spyStatic(TopicsWorker.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdservicesJobServiceLogger.class)
                        .spyStatic(ErrorLogUtil.class)
                        .mockStatic(ServiceCompatUtils.class)
                        .strictness(Strictness.WARN)
                        .startMocking();

        // Mock JobScheduler invocation in EpochJobService
        assertThat(JOB_SCHEDULER).isNotNull();
        assertNull(
                "Job already scheduled before setup!",
                JOB_SCHEDULER.getPendingJob(TOPICS_EPOCH_JOB_ID));

        ExtendedMockito.doReturn(JOB_SCHEDULER)
                .when(mSpyEpochJobService)
                .getSystemService(JobScheduler.class);

        // Mock AdservicesJobServiceLogger to not actually log the stats to server
        mSpyLogger =
                spy(new AdservicesJobServiceLogger(CONTEXT, Clock.SYSTEM_CLOCK, mMockStatsdLogger));
        Mockito.doNothing()
                .when(mSpyLogger)
                .logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
        ExtendedMockito.doReturn(mSpyLogger)
                .when(() -> AdservicesJobServiceLogger.getInstance(any(Context.class)));
    }

    @After
    public void teardown() {
        JOB_SCHEDULER.cancelAll();
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testOnStartJob_killSwitchOff_withoutLogging() throws InterruptedException {
        // Logging killswitch is on.
        Mockito.doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_killSwitchOff();

        // Verify logging methods are not invoked.
        verify(mSpyLogger, never()).persistJobExecutionData(anyInt(), anyLong());
        verify(mSpyLogger, never()).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testOnStartJob_killSwitchOff_withLogging() throws InterruptedException {
        // Logging killswitch is off.
        Mockito.doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_killSwitchOff();

        // Verify logging methods are invoked.
        verify(mSpyLogger).persistJobExecutionData(anyInt(), anyLong());
        verify(mSpyLogger).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testOnStartJob_killSwitchOn_withoutLogging() {
        ExtendedMockito.doNothing()
                .when(() -> ErrorLogUtil.e(anyInt(), anyInt(), anyString(), anyString()));
        // Logging killswitch is on.
        Mockito.doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_killSwitchOn();

        // Verify logging methods are not invoked.
        verify(mSpyLogger, never()).persistJobExecutionData(anyInt(), anyLong());
        verify(mSpyLogger, never()).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
        ExtendedMockito.verify(
                ()-> {
                    ErrorLogUtil.e(
                            eq(AD_SERVICES_ERROR_REPORTED__ERROR_CODE__TOPICS_API_DISABLED),
                            eq(AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS),
                            anyString(),
                            anyString());
                }
        );
    }

    @Test
    public void testOnStartJob_killSwitchOn_withLogging() {
        ExtendedMockito.doNothing()
                .when(() -> ErrorLogUtil.e(anyInt(), anyInt(), anyString(), anyString()));
        // Logging killswitch is off.
        Mockito.doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_killSwitchOn();

        // Verify logging methods are invoked.
        verify(mSpyLogger).persistJobExecutionData(anyInt(), anyLong());
        verify(mSpyLogger)
                .logExecutionStats(
                        anyInt(),
                        anyLong(),
                        eq(
                                AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_KILL_SWITCH_ON),
                        anyInt());
        ExtendedMockito.verify(
                ()-> {
                    ErrorLogUtil.e(
                            eq(AD_SERVICES_ERROR_REPORTED__ERROR_CODE__TOPICS_API_DISABLED),
                            eq(AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS),
                            anyString(),
                            anyString());
                }
        );
    }

    @Test
    public void testOnStartJob_globalKillSwitchOverridesAll() throws InterruptedException {
        // Add a countDownLatch to ensure background thread gets executed
        CountDownLatch countDownLatch = new CountDownLatch(1);

        // Global Killswitch is on.
        doReturn(true).when(mMockFlags).getGlobalKillSwitch();

        // Topics API Killswitch off but is overridden by global killswitch.
        doReturn(false).when(mMockFlags).getTopicsKillSwitch();

        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        mSpyEpochJobService.onStartJob(mMockJobParameters);

        // The countDownLatch doesn't get decreased and waits until timeout.
        assertThat(countDownLatch.await(BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isFalse();

        // When the kill switch is on, the EpochJobService exits early and do nothing.
    }

    @Test
    public void testOnStartJob_shouldDisableJobTrue_withoutLogging() {
        // Logging killswitch is on.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        Mockito.doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_shouldDisableJobTrue();

        // Verify logging method is not invoked.
        verify(mSpyLogger, never()).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testOnStartJob_shouldDisableJobTrue_withLogging() {
        // Logging killswitch is off.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        Mockito.doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_shouldDisableJobTrue();

        // Verify logging has happened
        verify(mSpyLogger)
                .logExecutionStats(
                        anyInt(),
                        anyLong(),
                        eq(
                                AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_EXTSERVICES_JOB_ON_TPLUS),
                        anyInt());
    }

    @Test
    public void testOnStopJob_withoutLogging() {
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        // Logging killswitch is on.
        Mockito.doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStopJob();

        // Verify logging methods are not invoked.
        verify(mSpyLogger, never()).persistJobExecutionData(anyInt(), anyLong());
        verify(mSpyLogger, never()).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testOnStopJob_withLogging() {
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        // Logging killswitch is off.
        Mockito.doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStopJob();

        // Verify logging methods are invoked.
        verify(mSpyLogger).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testScheduleIfNeeded_Success() {
        ExtendedMockito.doReturn(false).when(mMockFlags).getGlobalKillSwitch();

        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        // The first invocation of scheduleIfNeeded() schedules the job.
        assertThat(EpochJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
    }

    @Test
    public void testScheduleIfNeeded_ScheduledWithSameParameters() {
        doReturn(false).when(mMockFlags).getGlobalKillSwitch();
        doReturn(TEST_FLAGS.getTopicsEpochJobPeriodMs())
                .when(mMockFlags)
                .getTopicsEpochJobPeriodMs();
        doReturn(TEST_FLAGS.getTopicsEpochJobFlexMs()).when(mMockFlags).getTopicsEpochJobFlexMs();

        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        // The first invocation of scheduleIfNeeded() schedules the job.
        assertThat(EpochJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(TOPICS_EPOCH_JOB_ID)).isNotNull();

        // The second invocation of scheduleIfNeeded() with same parameters skips the scheduling.
        assertThat(EpochJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isFalse();
    }

    @Test
    public void testScheduleIfNeeded_ScheduledWithDifferentParameters() {
        // Mock Flags in order to change values within this test
        doReturn(TEST_FLAGS.getTopicsEpochJobPeriodMs())
                .when(mMockFlags)
                .getTopicsEpochJobPeriodMs();
        doReturn(TEST_FLAGS.getTopicsEpochJobFlexMs()).when(mMockFlags).getTopicsEpochJobFlexMs();
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        // The first invocation of scheduleIfNeeded() schedules the job.
        assertThat(EpochJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(TOPICS_EPOCH_JOB_ID)).isNotNull();

        // Change the value of a parameter so that the second invocation of scheduleIfNeeded()
        // schedules the job.
        doReturn(TEST_FLAGS.getTopicsEpochJobFlexMs() + 1)
                .when(mMockFlags)
                .getTopicsEpochJobFlexMs();
        assertThat(EpochJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
    }

    @Test
    public void testScheduleIfNeeded_forceRun() {
        doReturn(false).when(mMockFlags).getGlobalKillSwitch();
        doReturn(TEST_FLAGS.getTopicsEpochJobPeriodMs())
                .when(mMockFlags)
                .getTopicsEpochJobPeriodMs();
        doReturn(TEST_FLAGS.getTopicsEpochJobFlexMs()).when(mMockFlags).getTopicsEpochJobFlexMs();

        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        // The first invocation of scheduleIfNeeded() schedules the job.
        assertThat(EpochJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(TOPICS_EPOCH_JOB_ID)).isNotNull();

        // The second invocation of scheduleIfNeeded() with same parameters skips the scheduling.
        assertThat(EpochJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isFalse();

        // The third invocation of scheduleIfNeeded() is forced and re-schedules the job.
        assertThat(EpochJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ true)).isTrue();
    }

    @Test
    public void testScheduleIfNeeded_scheduledWithKillSwichOn() {
        ExtendedMockito.doNothing()
                .when(() -> ErrorLogUtil.e(anyInt(), anyInt(), anyString(), anyString()));
        // Killswitch is on.
        doReturn(true).when(mMockFlags).getTopicsKillSwitch();

        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        // The first invocation of scheduleIfNeeded() schedules the job.
        assertThat(EpochJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isFalse();
        assertThat(JOB_SCHEDULER.getPendingJob(TOPICS_EPOCH_JOB_ID)).isNull();
        ExtendedMockito.verify(
                ()-> {
                    ErrorLogUtil.e(
                            eq(AD_SERVICES_ERROR_REPORTED__ERROR_CODE__TOPICS_API_DISABLED),
                            eq(AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS),
                            anyString(),
                            anyString());
                }
        );
    }

    @Test
    public void testSchedule_jobInfoIsPersisted() {
        final ArgumentCaptor<JobInfo> argumentCaptor = ArgumentCaptor.forClass(JobInfo.class);

        EpochJobService.schedule(
                CONTEXT, mMockJobScheduler, EPOCH_JOB_PERIOD_MS, EPOCH_JOB_FLEX_MS);

        verify(mMockJobScheduler, times(1)).schedule(argumentCaptor.capture());
        assertThat(argumentCaptor.getValue()).isNotNull();
        assertThat(argumentCaptor.getValue().isPersisted()).isTrue();
    }

    private void testOnStartJob_killSwitchOff() throws InterruptedException {
        final TopicsWorker topicsWorker =
                new TopicsWorker(
                        mMockEpochManager,
                        mMockCacheManager,
                        mBlockedTopicsManager,
                        mMockAppUpdateManager,
                        mMockFlags);
        // Add a countDownLatch to ensure background thread gets executed
        CountDownLatch countDownLatch = new CountDownLatch(1);

        // Killswitch is off.
        doReturn(false).when(mMockFlags).getTopicsKillSwitch();

        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        // Mock static method TopicsWorker.getInstance, let it return the local topicsWorker
        // in order to get a test instance.
        ExtendedMockito.doReturn(topicsWorker)
                .when(() -> TopicsWorker.getInstance(any(Context.class)));

        mSpyEpochJobService.onStartJob(mMockJobParameters);

        // The countDownLatch doesn't get decreased and waits until timeout.
        assertThat(countDownLatch.await(BINDER_CONNECTION_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isFalse();

        // Check that processEpoch() and loadCache() are executed to justify
        // TopicsWorker.computeEpoch() is executed.
        ExtendedMockito.verify(() -> TopicsWorker.getInstance(any(Context.class)));
        verify(mMockEpochManager).processEpoch();
        verify(mMockCacheManager).loadCache(anyLong());
    }

    private void testOnStartJob_killSwitchOn() {
        // Killswitch is on.
        doReturn(true).when(mMockFlags).getTopicsKillSwitch();

        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        doNothing().when(mSpyEpochJobService).jobFinished(mMockJobParameters, false);

        // Schedule the job to assert after starting that the scheduled job has been cancelled
        JobInfo existingJobInfo =
                new JobInfo.Builder(
                                TOPICS_EPOCH_JOB_ID,
                                new ComponentName(CONTEXT, EpochJobService.class))
                        .setRequiresCharging(true)
                        .setPeriodic(EPOCH_JOB_PERIOD_MS, EPOCH_JOB_FLEX_MS)
                        .setPersisted(true)
                        .build();
        JOB_SCHEDULER.schedule(existingJobInfo);
        assertNotNull(JOB_SCHEDULER.getPendingJob(TOPICS_EPOCH_JOB_ID));

        // Now verify that when the Job starts, it will unschedule itself.
        assertFalse(mSpyEpochJobService.onStartJob(mMockJobParameters));

        assertNull(JOB_SCHEDULER.getPendingJob(TOPICS_EPOCH_JOB_ID));

        verify(mSpyEpochJobService).jobFinished(mMockJobParameters, false);
        verifyNoMoreInteractions(staticMockMarker(TopicsWorker.class));
    }

    private void testOnStartJob_shouldDisableJobTrue() {
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                ServiceCompatUtils.shouldDisableExtServicesJobOnTPlus(
                                        any(Context.class)));

        doNothing().when(mSpyEpochJobService).jobFinished(mMockJobParameters, false);

        // Schedule the job to assert after starting that the scheduled job has been cancelled
        JobInfo existingJobInfo =
                new JobInfo.Builder(
                                TOPICS_EPOCH_JOB_ID,
                                new ComponentName(CONTEXT, EpochJobService.class))
                        .setRequiresCharging(true)
                        .setPeriodic(EPOCH_JOB_PERIOD_MS, EPOCH_JOB_FLEX_MS)
                        .setPersisted(true)
                        .build();
        JOB_SCHEDULER.schedule(existingJobInfo);
        assertNotNull(JOB_SCHEDULER.getPendingJob(TOPICS_EPOCH_JOB_ID));

        // Now verify that when the Job starts, it will unschedule itself.
        assertFalse(mSpyEpochJobService.onStartJob(mMockJobParameters));

        assertNull(JOB_SCHEDULER.getPendingJob(TOPICS_EPOCH_JOB_ID));

        verify(mSpyEpochJobService).jobFinished(mMockJobParameters, false);
        verifyNoMoreInteractions(staticMockMarker(TopicsWorker.class));
    }

    private void testOnStopJob() {
        // Verify nothing throws
        mSpyEpochJobService.onStopJob(mMockJobParameters);
    }
}
