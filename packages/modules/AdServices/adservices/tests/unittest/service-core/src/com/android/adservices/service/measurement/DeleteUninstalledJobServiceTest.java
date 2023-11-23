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

package com.android.adservices.service.measurement;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_EXTSERVICES_JOB_ON_TPLUS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_KILL_SWITCH_ON;
import static com.android.adservices.spe.AdservicesJobInfo.MEASUREMENT_DELETE_UNINSTALLED_JOB;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.job.JobInfo;
import android.app.job.JobParameters;
import android.app.job.JobScheduler;
import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.service.AdServicesConfig;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.compat.ServiceCompatUtils;
import com.android.adservices.service.stats.Clock;
import com.android.adservices.service.stats.StatsdAdServicesLogger;
import com.android.adservices.spe.AdservicesJobServiceLogger;
import com.android.compatibility.common.util.TestUtils;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.quality.Strictness;

import java.util.concurrent.TimeUnit;

public class DeleteUninstalledJobServiceTest {
    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    private static final int MEASUREMENT_DELETE_UNINSTALLED_JOB_ID =
            MEASUREMENT_DELETE_UNINSTALLED_JOB.getJobId();
    private static final long WAIT_IN_MILLIS = 1_000L;

    @Mock private JobScheduler mMockJobScheduler;
    @Mock private MeasurementImpl mMockMeasurementImpl;

    @Spy private DeleteUninstalledJobService mSpyService;

    @Mock private Flags mMockFlags;
    private AdservicesJobServiceLogger mSpyLogger;

    @Test
    public void onStartJob_killSwitchOn_withoutLogging() throws Exception {
        runWithMocks(
                () -> {
                    // Logging killswitch is on.
                    Mockito.doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

                    onStartJob_killSwitchOn();

                    // Verify logging methods are not invoked.
                    verify(mSpyLogger, never()).persistJobExecutionData(anyInt(), anyLong());
                    verify(mSpyLogger, never())
                            .logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
                });
    }

    @Test
    public void onStartJob_killSwitchOn_withLogging() throws Exception {
        runWithMocks(
                () -> {
                    // Logging killswitch is off.
                    Mockito.doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

                    onStartJob_killSwitchOn();

                    // Verify logging methods are invoked.
                    verify(mSpyLogger).persistJobExecutionData(anyInt(), anyLong());
                    verify(mSpyLogger)
                            .logExecutionStats(
                                    anyInt(),
                                    anyLong(),
                                    eq(
                                            AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_KILL_SWITCH_ON),
                                    anyInt());
                });
    }

    @Test
    public void onStartJob_killSwitchOff_withoutLogging() throws Exception {
        runWithMocks(
                () -> {
                    // Logging killswitch is on.
                    Mockito.doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

                    onStartJob_killSwitchOff();

                    // Verify logging methods are not invoked.
                    verify(mSpyLogger, never()).persistJobExecutionData(anyInt(), anyLong());
                    verify(mSpyLogger, never())
                            .logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
                });
    }

    @Test
    public void onStartJob_killSwitchOff_withLogging() throws Exception {
        runWithMocks(
                () -> {
                    // Logging killswitch is off.
                    Mockito.doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

                    onStartJob_killSwitchOff();

                    // Verify logging methods are invoked.
                    verify(mSpyLogger).persistJobExecutionData(anyInt(), anyLong());
                    verify(mSpyLogger).logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
                });
    }

    @Test
    public void onStartJob_shouldDisableJobTrue_withoutLogging() throws Exception {
        runWithMocks(
                () -> {
                    // Logging killswitch is on.
                    ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
                    Mockito.doReturn(true).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

                    onStartJob_shouldDisableJobTrue();

                    // Verify logging method is not invoked.
                    verify(mSpyLogger, never())
                            .logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
                });
    }

    @Test
    public void onStartJob_shouldDisableJobTrue_withLogging() throws Exception {
        runWithMocks(
                () -> {
                    // Logging killswitch is off.
                    ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
                    Mockito.doReturn(false).when(mMockFlags).getBackgroundJobsLoggingKillSwitch();

                    onStartJob_shouldDisableJobTrue();

                    // Verify logging has happened
                    verify(mSpyLogger)
                            .logExecutionStats(
                                    anyInt(),
                                    anyLong(),
                                    eq(
                                            AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SKIP_FOR_EXTSERVICES_JOB_ON_TPLUS),
                                    anyInt());
                });
    }

    @Test
    public void scheduleIfNeeded_killSwitchOn_dontSchedule() throws Exception {
        runWithMocks(
                () -> {
                    // Setup
                    enableKillSwitch();

                    final Context mockContext = mock(Context.class);
                    doReturn(mMockJobScheduler)
                            .when(mockContext)
                            .getSystemService(JobScheduler.class);
                    final JobInfo mockJobInfo = mock(JobInfo.class);
                    doReturn(mockJobInfo)
                            .when(mMockJobScheduler)
                            .getPendingJob(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));

                    // Execute
                    DeleteUninstalledJobService.scheduleIfNeeded(
                            mockContext, /* forceSchedule = */ false);

                    // Validate
                    ExtendedMockito.verify(
                            () -> DeleteUninstalledJobService.schedule(any(), any()), never());
                    verify(mMockJobScheduler, never())
                            .getPendingJob(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));
                });
    }

    @Test
    public void scheduleIfNeeded_killSwitchOff_previouslyExecuted_dontForceSchedule_dontSchedule()
            throws Exception {
        runWithMocks(
                () -> {
                    // Setup
                    disableKillSwitch();

                    final Context mockContext = mock(Context.class);
                    doReturn(mMockJobScheduler)
                            .when(mockContext)
                            .getSystemService(JobScheduler.class);
                    final JobInfo mockJobInfo = mock(JobInfo.class);
                    doReturn(mockJobInfo)
                            .when(mMockJobScheduler)
                            .getPendingJob(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));

                    // Execute
                    DeleteUninstalledJobService.scheduleIfNeeded(
                            mockContext, /* forceSchedule = */ false);

                    // Validate
                    ExtendedMockito.verify(
                            () -> DeleteUninstalledJobService.schedule(any(), any()), never());
                    verify(mMockJobScheduler, times(1))
                            .getPendingJob(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));
                });
    }

    @Test
    public void scheduleIfNeeded_killSwitchOff_previouslyExecuted_forceSchedule_schedule()
            throws Exception {
        runWithMocks(
                () -> {
                    // Setup
                    disableKillSwitch();

                    final Context mockContext = mock(Context.class);
                    doReturn(mMockJobScheduler)
                            .when(mockContext)
                            .getSystemService(JobScheduler.class);
                    final JobInfo mockJobInfo = mock(JobInfo.class);
                    doReturn(mockJobInfo)
                            .when(mMockJobScheduler)
                            .getPendingJob(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));

                    // Execute
                    DeleteUninstalledJobService.scheduleIfNeeded(
                            mockContext, /* forceSchedule = */ true);

                    // Validate
                    ExtendedMockito.verify(
                            () -> DeleteUninstalledJobService.schedule(any(), any()), times(1));
                    verify(mMockJobScheduler, times(1))
                            .getPendingJob(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));
                });
    }

    @Test
    public void scheduleIfNeeded_killSwitchOff_previouslyNotExecuted_dontForceSchedule_schedule()
            throws Exception {
        runWithMocks(
                () -> {
                    // Setup
                    disableKillSwitch();

                    final Context mockContext = mock(Context.class);
                    doReturn(mMockJobScheduler)
                            .when(mockContext)
                            .getSystemService(JobScheduler.class);
                    doReturn(/* noJobInfo = */ null)
                            .when(mMockJobScheduler)
                            .getPendingJob(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));

                    // Execute
                    DeleteUninstalledJobService.scheduleIfNeeded(mockContext, false);

                    // Validate
                    ExtendedMockito.verify(
                            () -> DeleteUninstalledJobService.schedule(any(), any()), times(1));
                    verify(mMockJobScheduler, times(1))
                            .getPendingJob(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));
                });
    }

    @Test
    public void testSchedule_jobInfoIsPersisted() {
        // Setup
        final JobScheduler jobScheduler = mock(JobScheduler.class);
        final ArgumentCaptor<JobInfo> captor = ArgumentCaptor.forClass(JobInfo.class);

        // Execute
        DeleteUninstalledJobService.schedule(mock(Context.class), jobScheduler);

        // Validate
        verify(jobScheduler, times(1)).schedule(captor.capture());
        assertNotNull(captor.getValue());
        assertTrue(captor.getValue().isPersisted());
    }

    private void onStartJob_killSwitchOn() throws Exception {
        // Setup
        enableKillSwitch();

        // Execute
        boolean result = mSpyService.onStartJob(Mockito.mock(JobParameters.class));

        // Validate
        assertFalse(result);

        // Allow background thread to execute
        Thread.sleep(WAIT_IN_MILLIS);
        verify(mMockMeasurementImpl, never()).deleteAllUninstalledMeasurementData();
        verify(mSpyService, times(1)).jobFinished(any(), eq(false));
        verify(mMockJobScheduler, times(1)).cancel(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));
    }

    private void onStartJob_killSwitchOff() throws Exception {
        // Setup
        disableKillSwitch();

        // Execute
        boolean result = mSpyService.onStartJob(Mockito.mock(JobParameters.class));

        // Validate
        assertTrue(result);

        // Allow background thread to execute
        Thread.sleep(WAIT_IN_MILLIS);
        verify(mMockMeasurementImpl, times(1)).deleteAllUninstalledMeasurementData();
        verify(mSpyService, times(1)).jobFinished(any(), anyBoolean());
        verify(mMockJobScheduler, never()).cancel(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));
    }

    private void onStartJob_shouldDisableJobTrue() throws Exception {
        // Setup
        ExtendedMockito.doReturn(true)
                .when(
                        () ->
                                ServiceCompatUtils.shouldDisableExtServicesJobOnTPlus(
                                        any(Context.class)));

        // Execute
        boolean result = mSpyService.onStartJob(Mockito.mock(JobParameters.class));

        // Validate
        assertFalse(result);

        // Allow background thread to execute
        Thread.sleep(WAIT_IN_MILLIS);
        verify(mMockMeasurementImpl, never()).deleteAllUninstalledMeasurementData();
        verify(mSpyService, times(1)).jobFinished(any(), eq(false));
        verify(mMockJobScheduler, times(1)).cancel(eq(MEASUREMENT_DELETE_UNINSTALLED_JOB_ID));
    }

    private void runWithMocks(TestUtils.RunnableWithThrow execute) throws Exception {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .initMocks(this)
                        .spyStatic(AdServicesConfig.class)
                        .spyStatic(MeasurementImpl.class)
                        .spyStatic(DeleteUninstalledJobService.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(AdservicesJobServiceLogger.class)
                        .mockStatic(ServiceCompatUtils.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            // Setup mock everything in job
            ExtendedMockito.doReturn(mMockMeasurementImpl)
                    .when(() -> MeasurementImpl.getInstance(any()));
            doNothing().when(mSpyService).jobFinished(any(), anyBoolean());
            doReturn(mMockJobScheduler).when(mSpyService).getSystemService(JobScheduler.class);
            ExtendedMockito.doReturn(TimeUnit.HOURS.toMillis(24))
                    .when(AdServicesConfig::getMeasurementDeleteUninstalledJobPeriodMs);
            ExtendedMockito.doNothing()
                    .when(() -> DeleteUninstalledJobService.schedule(any(), any()));

            StatsdAdServicesLogger mockStatsdLogger = mock(StatsdAdServicesLogger.class);
            mSpyLogger =
                    spy(
                            new AdservicesJobServiceLogger(
                                    CONTEXT, Clock.SYSTEM_CLOCK, mockStatsdLogger));

            // Mock AdservicesJobServiceLogger to not actually log the stats to server
            Mockito.doNothing()
                    .when(mSpyLogger)
                    .logExecutionStats(anyInt(), anyLong(), anyInt(), anyInt());
            ExtendedMockito.doReturn(mSpyLogger)
                    .when(() -> AdservicesJobServiceLogger.getInstance(any(Context.class)));

            // Execute
            execute.run();
        } finally {
            session.finishMocking();
        }
    }

    private void enableKillSwitch() {
        toggleKillSwitch(true);
    }

    private void disableKillSwitch() {
        toggleKillSwitch(false);
    }

    private void toggleKillSwitch(boolean value) {
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        ExtendedMockito.doReturn(value)
                .when(mMockFlags)
                .getMeasurementJobDeleteUninstalledKillSwitch();
    }
}
