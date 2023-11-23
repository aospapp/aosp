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

package com.android.adservices.download;

import static com.android.adservices.download.MddJobService.KEY_MDD_TASK_TAG;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_EXTSERVICES_JOB_ON_TPLUS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_KILL_SWITCH_ON;
import static com.android.adservices.spe.AdservicesJobInfo.MDD_CELLULAR_CHARGING_PERIODIC_TASK_JOB;
import static com.android.adservices.spe.AdservicesJobInfo.MDD_CHARGING_PERIODIC_TASK_JOB;
import static com.android.adservices.spe.AdservicesJobInfo.MDD_MAINTENANCE_PERIODIC_TASK_JOB;
import static com.android.adservices.spe.AdservicesJobInfo.MDD_WIFI_CHARGING_PERIODIC_TASK_JOB;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.staticMockMarker;

import static com.google.android.libraries.mobiledatadownload.TaskScheduler.WIFI_CHARGING_PERIODIC_TASK;
import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.verifyZeroInteractions;
import static org.mockito.Mockito.when;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.os.PersistableBundle;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.compat.ServiceCompatUtils;
import com.android.adservices.service.stats.Clock;
import com.android.adservices.service.stats.StatsdAdServicesLogger;
import com.android.adservices.spe.AdservicesJobServiceLogger;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import com.google.android.libraries.mobiledatadownload.MobileDataDownload;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.quality.Strictness;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Unit tests for {@link com.android.adservices.download.MddJobService} */
public class MddJobServiceTest {
    private static final int BACKGROUND_TASK_TIMEOUT_MS = 5_000;
    private static final int JOB_SCHEDULED_WAIT_TIME_MS = 1_000;

    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    private static final JobScheduler JOB_SCHEDULER = CONTEXT.getSystemService(JobScheduler.class);
    private static final int MDD_MAINTENANCE_PERIODIC_TASK_JOB_ID =
            MDD_MAINTENANCE_PERIODIC_TASK_JOB.getJobId();
    private static final int MDD_CHARGING_PERIODIC_TASK_JOB_ID =
            MDD_CHARGING_PERIODIC_TASK_JOB.getJobId();
    private static final int MDD_CELLULAR_CHARGING_PERIODIC_TASK_JOB_ID =
            MDD_CELLULAR_CHARGING_PERIODIC_TASK_JOB.getJobId();
    private static final int MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID =
            MDD_WIFI_CHARGING_PERIODIC_TASK_JOB.getJobId();

    @Spy private MddJobService mSpyMddJobService;
    private MockitoSession mStaticMockSession;

    @Mock JobParameters mMockJobParameters;

    @Mock MobileDataDownload mMockMdd;
    @Mock MobileDataDownloadFactory mMockMddFactory;
    @Mock Flags mMockFlags;
    @Mock MddFlags mMockMddFlags;
    @Mock StatsdAdServicesLogger mMockStatsdLogger;
    private AdservicesJobServiceLogger mLogger;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        // Start a mockitoSession to mock static method.
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(MddJobService.class)
                        .spyStatic(MobileDataDownloadFactory.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(MddFlags.class)
                        .spyStatic(AdservicesJobServiceLogger.class)
                        .mockStatic(ServiceCompatUtils.class)
                        .strictness(Strictness.WARN)
                        .startMocking();

        // Mock JobScheduler invocation in EpochJobService
        assertThat(JOB_SCHEDULER).isNotNull();
        assertNull(
                "Job already scheduled before setup!",
                JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID));

        ExtendedMockito.doReturn(JOB_SCHEDULER)
                .when(mSpyMddJobService)
                .getSystemService(JobScheduler.class);

        // Mock AdservicesJobServiceLogger to not actually log the stats to server
        mLogger =
                spy(new AdservicesJobServiceLogger(CONTEXT, Clock.SYSTEM_CLOCK, mMockStatsdLogger));
        Mockito.doNothing()
                .when(mLogger)
                .logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
        ExtendedMockito.doReturn(mLogger)
                .when(() -> AdservicesJobServiceLogger.getInstance(any(Context.class)));

        // MDD Task Tag.
        PersistableBundle bundle = new PersistableBundle();
        bundle.putString(KEY_MDD_TASK_TAG, WIFI_CHARGING_PERIODIC_TASK);
        when(mMockJobParameters.getExtras()).thenReturn(bundle);
    }

    @After
    public void teardown() {
        JOB_SCHEDULER.cancelAll();
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testOnStartJob_killswitchIsOff_withoutLogging() throws InterruptedException {
        // Logging killswitch is on.
        doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_killswitchIsOff();

        // Verify logging methods are not invoked.
        verify(mLogger, never()).persistJobExecutionData(anyInt(), anyLong());
        verify(mLogger, never()).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testOnStartJob_killswitchIsOff_withLogging() throws InterruptedException {
        // Logging killswitch is off.
        doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_killswitchIsOff();

        // Verify logging methods are invoked.
        verify(mLogger).persistJobExecutionData(anyInt(), anyLong());
        verify(mLogger).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testOnStartJob_killswitchIsOn_withoutLogging() {
        // Logging killswitch is on.
        Mockito.doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_killswitchIsOn();

        // Verify logging methods are not invoked.
        verify(mLogger, never()).persistJobExecutionData(anyInt(), anyLong());
        verify(mLogger, never()).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testOnStartJob_killSwitchOn_withLogging() {
        // Logging killswitch is off.
        Mockito.doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_killswitchIsOn();

        // Verify logging methods are invoked.
        verify(mLogger).persistJobExecutionData(anyInt(), anyLong());
        verify(mLogger)
                .logExecutionStats(
                        anyInt(),
                        anyLong(),
                        eq(
                                AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_KILL_SWITCH_ON),
                        anyInt());
    }

    @Test
    public void testSchedule_killswitchOff() throws InterruptedException {
        // Mock static method MddFlags.getInstance() to return Mock MddFlags.
        ExtendedMockito.doReturn(mMockMddFlags).when(MddFlags::getInstance);
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        // Killswitch is off.
        doReturn(false).when(mMockFlags).getMddBackgroundTaskKillSwitch();

        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testSchedule_killswitchOn() throws InterruptedException {
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        // Killswitch is off.
        doReturn(true).when(mMockFlags).getMddBackgroundTaskKillSwitch();

        mSpyMddJobService.scheduleIfNeeded(CONTEXT, false);

        verifyZeroInteractions(staticMockMarker(MobileDataDownloadFactory.class));
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isFalse();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testOnStopJob_withoutLogging() {
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        // Logging killswitch is on.
        doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStopJob();

        // Verify logging methods are not invoked.
        verify(mLogger, never()).persistJobExecutionData(anyInt(), anyLong());
        verify(mLogger, never()).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testOnStopJob_withLogging() {
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        // Logging killswitch is off.
        doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStopJob();

        // Verify logging methods are invoked.
        verify(mLogger).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testScheduleIfNeeded_Success() throws InterruptedException {
        // Mock static method MddFlags.getInstance() to return Mock MddFlags.
        ExtendedMockito.doReturn(mMockMddFlags).when(MddFlags::getInstance);
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_MAINTENANCE_PERIODIC_TASK_JOB_ID)).isNotNull();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_CHARGING_PERIODIC_TASK_JOB_ID)).isNotNull();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_CELLULAR_CHARGING_PERIODIC_TASK_JOB_ID))
                .isNotNull();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID)).isNotNull();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testScheduleIfNeeded_ScheduledWithSameParameters() throws InterruptedException {
        // Mock static method MddFlags.getInstance() to return Mock MddFlags.
        ExtendedMockito.doReturn(mMockMddFlags).when(MddFlags::getInstance);
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        doReturn(/* MaintenanceGcmTaskPeriod default value */ 86400L)
                .when(mMockMddFlags)
                .maintenanceGcmTaskPeriod();

        // The first invocation of scheduleIfNeeded() schedules the job.
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID)).isNotNull();

        // The second invocation of scheduleIfNeeded() with same parameters skips the scheduling.
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isFalse();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testScheduleIfNeeded_ScheduledWithDifferentParameters()
            throws InterruptedException {
        // Mock static method MddFlags.getInstance() to return Mock MddFlags.
        ExtendedMockito.doReturn(mMockMddFlags).when(MddFlags::getInstance);
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        doReturn(/* MaintenanceGcmTaskPeriod default value */ 86400L)
                .when(mMockMddFlags)
                .maintenanceGcmTaskPeriod();

        // The first invocation of scheduleIfNeeded() schedules the job.
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID)).isNotNull();

        doReturn(/* MaintenanceGcmTaskPeriod default value */ 86400L + 1L)
                .when(mMockMddFlags)
                .maintenanceGcmTaskPeriod();
        // The second invocation of scheduleIfNeeded() with same parameters skips the scheduling.
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testScheduleIfNeeded_forceRun() throws InterruptedException {
        // Mock static method MddFlags.getInstance() to return Mock MddFlags.
        ExtendedMockito.doReturn(mMockMddFlags).when(MddFlags::getInstance);
        // Mock static method FlagsFactory.getFlags() to return test Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);

        doReturn(/* MaintenanceGcmTaskPeriod default value */ 86400L)
                .when(mMockMddFlags)
                .maintenanceGcmTaskPeriod();
        // The first invocation of scheduleIfNeeded() schedules the job.
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_MAINTENANCE_PERIODIC_TASK_JOB_ID)).isNotNull();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_CHARGING_PERIODIC_TASK_JOB_ID)).isNotNull();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_CELLULAR_CHARGING_PERIODIC_TASK_JOB_ID))
                .isNotNull();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID)).isNotNull();

        // The second invocation of scheduleIfNeeded() with same parameters skips the scheduling.
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isFalse();

        // The third invocation of scheduleIfNeeded() is forced and re-schedules the job.
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ true)).isTrue();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testScheduleIfNeededMddSingleTask_mddMaintenancePeriodicTask()
            throws InterruptedException {
        // Mock static method MddFlags.getInstance() to return Mock MddFlags.
        ExtendedMockito.doReturn(mMockMddFlags).when(MddFlags::getInstance);
        // Mock static method FlagsFactory.getFlags() to return test Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        doReturn(/* MaintenanceGcmTaskPeriod default value */ 86400L)
                .when(mMockMddFlags)
                .maintenanceGcmTaskPeriod();
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_MAINTENANCE_PERIODIC_TASK_JOB_ID)).isNotNull();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testScheduleIfNeededMddSingleTask_mddChargingPeriodicTask()
            throws InterruptedException {
        // Mock static method MddFlags.getInstance() to return Mock MddFlags.
        ExtendedMockito.doReturn(mMockMddFlags).when(MddFlags::getInstance);
        // Mock static method FlagsFactory.getFlags() to return test Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        doReturn(/* MaintenanceGcmTaskPeriod default value */ 21600L)
                .when(mMockMddFlags)
                .chargingGcmTaskPeriod();
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_CHARGING_PERIODIC_TASK_JOB_ID)).isNotNull();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testScheduleIfNeededMddSingleTask_mddCellularChargingPeriodicTask()
            throws InterruptedException {
        // Mock static method MddFlags.getInstance() to return Mock MddFlags.
        ExtendedMockito.doReturn(mMockMddFlags).when(MddFlags::getInstance);
        // Mock static method FlagsFactory.getFlags() to return test Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        doReturn(/* MaintenanceGcmTaskPeriod default value */ 21600L)
                .when(mMockMddFlags)
                .cellularChargingGcmTaskPeriod();
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_CELLULAR_CHARGING_PERIODIC_TASK_JOB_ID))
                .isNotNull();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testScheduleIfNeededMddSingleTask_mddWifiChargingPeriodicTask()
            throws InterruptedException {
        // Mock static method MddFlags.getInstance() to return Mock MddFlags.
        ExtendedMockito.doReturn(mMockMddFlags).when(MddFlags::getInstance);
        // Mock static method FlagsFactory.getFlags() to return test Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        doReturn(/* MaintenanceGcmTaskPeriod default value */ 21600L)
                .when(mMockMddFlags)
                .wifiChargingGcmTaskPeriod();
        assertThat(MddJobService.scheduleIfNeeded(CONTEXT, /* forceSchedule */ false)).isTrue();
        assertThat(JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID)).isNotNull();
        // We schedule the job in this test and cancel it in the teardown. There could be a race
        // condition between when the job starts and when we try to cancel it. If the job already
        // started and we exited this test method, the flag would not be mocked anymore and hence
        // the READ_DEVICE_CONFIG exception.
        // We wait here so that the scheduled job can finish.
        Thread.sleep(JOB_SCHEDULED_WAIT_TIME_MS);
    }

    @Test
    public void testOnStartJob_shouldDisableJobTrue_withoutLogging() {
        // Logging killswitch is on.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_shouldDisableJobTrue();

        // Verify logging method is not invoked.
        verify(mLogger, never()).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
    }

    @Test
    public void testOnStartJob_shouldDisableJobTrue_withLogging() {
        // Logging killswitch is off.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

        testOnStartJob_shouldDisableJobTrue();

        // Verify logging has happened
        verify(mLogger)
                .logExecutionStats(
                        anyInt(),
                        anyLong(),
                        eq(
                                AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_EXTSERVICES_JOB_ON_TPLUS),
                        anyInt());
    }

    private void testOnStartJob_killswitchIsOn() {
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        // Killswitch is on.
        doReturn(true).when(mMockFlags).getMddBackgroundTaskKillSwitch();

        doNothing().when(mSpyMddJobService).jobFinished(mMockJobParameters, false);

        // Schedule the job to assert after starting that the scheduled job has been cancelled
        JobInfo existingJobInfo =
                new JobInfo.Builder(
                                MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID,
                                new ComponentName(CONTEXT, MddJobService.class))
                        .setRequiresCharging(true)
                        .setPeriodic(/* periodMs */ 10000, /* flexMs */ 1000)
                        .build();
        JOB_SCHEDULER.schedule(existingJobInfo);
        assertNotNull(JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID));

        // Now verify that when the Job starts, it will unschedule itself.
        assertFalse(mSpyMddJobService.onStartJob(mMockJobParameters));

        assertNull(JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID));

        verify(mSpyMddJobService).jobFinished(mMockJobParameters, false);
        verifyNoMoreInteractions(staticMockMarker(MobileDataDownloadFactory.class));
    }

    private void testOnStartJob_killswitchIsOff() throws InterruptedException {
        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        // Killswitch is off.
        doReturn(false).when(mMockFlags).getMddBackgroundTaskKillSwitch();

        // Add a countDownLatch to ensure background thread gets executed
        CountDownLatch countDownLatch = new CountDownLatch(1);

        ExtendedMockito.doReturn(mMockMdd)
                .when(() -> MobileDataDownloadFactory.getMdd(any(Context.class), any(Flags.class)));

        mSpyMddJobService.onStartJob(mMockJobParameters);

        // The countDownLatch doesn't get decreased and waits until timeout.
        assertThat(countDownLatch.await(BACKGROUND_TASK_TIMEOUT_MS, TimeUnit.MILLISECONDS))
                .isFalse();

        // Check that Mdd.handleTask is executed.
        ExtendedMockito.verify(
                () -> MobileDataDownloadFactory.getMdd(any(Context.class), any(Flags.class)));
        verify(mMockMdd).handleTask(WIFI_CHARGING_PERIODIC_TASK);
    }

    private void testOnStopJob() {
        // Verify nothing throws
        mSpyMddJobService.onStopJob(mMockJobParameters);
    }

    private void testOnStartJob_shouldDisableJobTrue() {
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                ServiceCompatUtils.shouldDisableExtServicesJobOnTPlus(
                                        any(Context.class)));

        doNothing().when(mSpyMddJobService).jobFinished(mMockJobParameters, false);

        // Schedule the job to assert after starting that the scheduled job has been cancelled
        JobInfo existingJobInfo =
                new JobInfo.Builder(
                                MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID,
                                new ComponentName(CONTEXT, MddJobService.class))
                        .setRequiresCharging(true)
                        .setPeriodic(/* periodMs */ 10000, /* flexMs */ 1000)
                        .build();
        JOB_SCHEDULER.schedule(existingJobInfo);
        assertNotNull(JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID));

        // Now verify that when the Job starts, it will unschedule itself.
        assertFalse(mSpyMddJobService.onStartJob(mMockJobParameters));

        assertNull(JOB_SCHEDULER.getPendingJob(MDD_WIFI_CHARGING_PERIODIC_TASK_JOB_ID));

        verify(mSpyMddJobService).jobFinished(mMockJobParameters, false);
        verifyNoMoreInteractions(staticMockMarker(MobileDataDownloadFactory.class));
    }
}
