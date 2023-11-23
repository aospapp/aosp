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

package com.android.adservices.spe;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__HALTED_FOR_UNKNOWN_REASON;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SUCCESSFUL;
import static com.android.adservices.spe.JobServiceConstants.MILLISECONDS_PER_MINUTE;
import static com.android.adservices.spe.JobServiceConstants.SHARED_PREFS_BACKGROUND_JOBS;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_JOB_EXECUTION_PERIOD;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_JOB_EXECUTION_STOP_TIMESTAMP;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_JOB_LATENCY;
import static com.android.adservices.spe.JobServiceConstants.UNAVAILABLE_STOP_REASON;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import android.content.Context;
import android.content.SharedPreferences;

import androidx.test.core.app.ApplicationProvider;

import com.android.adservices.service.stats.Clock;
import com.android.adservices.service.stats.StatsdAdServicesLogger;
import com.android.adservices.spe.stats.ExecutionReportedStats;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

/** Unit test for {@link AdservicesJobServiceLogger}. */
public class AdservicesJobServiceLoggerTest {
    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();

    // Use an arbitrary job ID for testing. It won't have side effect to use production id as
    // the test doesn't actually schedule a job. This avoids complicated mocking logic.
    private static final int JOB_ID_1 =
            AdservicesJobInfo.MDD_WIFI_CHARGING_PERIODIC_TASK_JOB.getJobId();
    private static final int JOB_ID_2 =
            AdservicesJobInfo.MDD_MAINTENANCE_PERIODIC_TASK_JOB.getJobId();

    private AdservicesJobServiceLogger mLogger;
    @Mock StatsdAdServicesLogger mMockStatsdLogger;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);
        mLogger =
                Mockito.spy(
                        new AdservicesJobServiceLogger(
                                CONTEXT, Clock.SYSTEM_CLOCK, mMockStatsdLogger));

        // Clear shared preference
        CONTEXT.deleteSharedPreferences(JobServiceConstants.SHARED_PREFS_BACKGROUND_JOBS);
    }

    @After
    public void teardown() {
        // Clear shared preference
        CONTEXT.deleteSharedPreferences(JobServiceConstants.SHARED_PREFS_BACKGROUND_JOBS);
    }

    @Test
    public void testPersistJobExecutionData_firstExecution() {
        String keyJobStartTime = AdservicesJobServiceLogger.getJobStartTimestampKey(JOB_ID_1);
        String keyExecutionPeriod = AdservicesJobServiceLogger.getExecutionPeriodKey(JOB_ID_1);
        SharedPreferences sharedPreferences =
                CONTEXT.getSharedPreferences(SHARED_PREFS_BACKGROUND_JOBS, Context.MODE_PRIVATE);
        long startJobTimestamp = 100L;

        mLogger.persistJobExecutionData(JOB_ID_1, startJobTimestamp);
        assertThat(
                        sharedPreferences.getLong(
                                keyJobStartTime, UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP))
                .isEqualTo(startJobTimestamp);
        assertThat(sharedPreferences.getLong(keyExecutionPeriod, UNAVAILABLE_JOB_EXECUTION_PERIOD))
                .isEqualTo(UNAVAILABLE_JOB_EXECUTION_PERIOD);
    }

    @Test
    public void testPersistJobExecutionData_openEndedLastExecution() {
        String keyJobStartTime = AdservicesJobServiceLogger.getJobStartTimestampKey(JOB_ID_1);
        String keyExecutionPeriod = AdservicesJobServiceLogger.getExecutionPeriodKey(JOB_ID_1);
        String keyJobStopTime = AdservicesJobServiceLogger.getJobStopTimestampKey(JOB_ID_1);
        // previousJobStopTime < previousJobStartTime, which indicates previous execution finished
        // with an open end.
        long previousJobStartTime = 100L;
        long previousJobStopTime = 50L;
        long previousExecutionPeriod = 100L;
        long currentJobStartTime = 300L;

        // Store previous execution data.
        SharedPreferences sharedPreferences =
                CONTEXT.getSharedPreferences(SHARED_PREFS_BACKGROUND_JOBS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putLong(keyJobStartTime, previousJobStartTime);
        editor.putLong(keyJobStopTime, previousJobStopTime);
        editor.putLong(keyExecutionPeriod, previousExecutionPeriod);
        editor.commit();

        // Do not actually upload to server.
        doNothing()
                .when(mLogger)
                .logJobStatsHelper(
                        JOB_ID_1,
                        UNAVAILABLE_JOB_LATENCY,
                        previousExecutionPeriod,
                        AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__HALTED_FOR_UNKNOWN_REASON,
                        UNAVAILABLE_STOP_REASON);

        mLogger.persistJobExecutionData(JOB_ID_1, currentJobStartTime);

        verify(mLogger)
                .logJobStatsHelper(
                        JOB_ID_1,
                        UNAVAILABLE_JOB_LATENCY,
                        previousExecutionPeriod,
                        AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__HALTED_FOR_UNKNOWN_REASON,
                        UNAVAILABLE_STOP_REASON);
        assertThat(
                        sharedPreferences.getLong(
                                keyJobStartTime, UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP))
                .isEqualTo(currentJobStartTime);
        assertThat(sharedPreferences.getLong(keyExecutionPeriod, UNAVAILABLE_JOB_EXECUTION_PERIOD))
                .isEqualTo(currentJobStartTime - previousJobStartTime);
    }

    @Test
    public void testPersistJobExecutionData_closeEndedLastExecution() {
        String keyJobStartTime = AdservicesJobServiceLogger.getJobStartTimestampKey(JOB_ID_1);
        String keyExecutionPeriod = AdservicesJobServiceLogger.getExecutionPeriodKey(JOB_ID_1);
        String keyJobStopTime = AdservicesJobServiceLogger.getJobStopTimestampKey(JOB_ID_1);
        long previousJobStartTime = 100L;
        long previousJobStopTime = 150L;
        long previousExecutionPeriod = 100L;
        long currentJobStartTime = 300L;

        // Store previous execution data.
        SharedPreferences sharedPreferences =
                CONTEXT.getSharedPreferences(SHARED_PREFS_BACKGROUND_JOBS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putLong(keyJobStartTime, previousJobStartTime);
        editor.putLong(keyJobStopTime, previousJobStopTime);
        editor.putLong(keyExecutionPeriod, previousExecutionPeriod);
        editor.commit();

        doNothing()
                .when(mLogger)
                .logJobStatsHelper(
                        JOB_ID_1,
                        UNAVAILABLE_JOB_LATENCY,
                        previousExecutionPeriod,
                        AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__HALTED_FOR_UNKNOWN_REASON,
                        UNAVAILABLE_STOP_REASON);

        mLogger.persistJobExecutionData(JOB_ID_1, currentJobStartTime);

        verify(mLogger, never())
                .logJobStatsHelper(
                        JOB_ID_1,
                        UNAVAILABLE_JOB_LATENCY,
                        previousExecutionPeriod,
                        AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__HALTED_FOR_UNKNOWN_REASON,
                        UNAVAILABLE_STOP_REASON);
        assertThat(
                        sharedPreferences.getLong(
                                keyJobStartTime, UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP))
                .isEqualTo(currentJobStartTime);
        assertThat(sharedPreferences.getLong(keyExecutionPeriod, UNAVAILABLE_JOB_EXECUTION_PERIOD))
                .isEqualTo(currentJobStartTime - previousJobStartTime);
    }

    @Test
    public void testPersistJobExecutionData_multipleJobs() {
        String keyJobStartTime1 = AdservicesJobServiceLogger.getJobStartTimestampKey(JOB_ID_1);
        String keyExecutionPeriod1 = AdservicesJobServiceLogger.getExecutionPeriodKey(JOB_ID_1);
        String keyJobStartTime2 = AdservicesJobServiceLogger.getJobStartTimestampKey(JOB_ID_2);
        String keyExecutionPeriod2 = AdservicesJobServiceLogger.getExecutionPeriodKey(JOB_ID_2);
        SharedPreferences sharedPreferences =
                CONTEXT.getSharedPreferences(SHARED_PREFS_BACKGROUND_JOBS, Context.MODE_PRIVATE);
        long startJobTimestamp1 = 100L;
        long startJobTimestamp2 = 200L;

        mLogger.persistJobExecutionData(JOB_ID_1, startJobTimestamp1);
        mLogger.persistJobExecutionData(JOB_ID_2, startJobTimestamp2);
        assertThat(
                        sharedPreferences.getLong(
                                keyJobStartTime1, UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP))
                .isEqualTo(startJobTimestamp1);
        assertThat(sharedPreferences.getLong(keyExecutionPeriod1, UNAVAILABLE_JOB_EXECUTION_PERIOD))
                .isEqualTo(UNAVAILABLE_JOB_EXECUTION_PERIOD);
        assertThat(
                        sharedPreferences.getLong(
                                keyJobStartTime2, UNAVAILABLE_JOB_EXECUTION_START_TIMESTAMP))
                .isEqualTo(startJobTimestamp2);
        assertThat(sharedPreferences.getLong(keyExecutionPeriod2, UNAVAILABLE_JOB_EXECUTION_PERIOD))
                .isEqualTo(UNAVAILABLE_JOB_EXECUTION_PERIOD);
    }

    @Test
    public void testLogExecutionStats() {
        String keyJobStartTime = AdservicesJobServiceLogger.getJobStartTimestampKey(JOB_ID_1);
        String keyExecutionPeriod = AdservicesJobServiceLogger.getExecutionPeriodKey(JOB_ID_1);
        String keyJobStopTime = AdservicesJobServiceLogger.getJobStopTimestampKey(JOB_ID_1);
        long jobStartTime = 100L;
        long jobStopTime = 200L;
        long executionPeriod = 50L;
        long executionLatency = jobStopTime - jobStartTime;
        int stopReason = UNAVAILABLE_STOP_REASON;
        int resultCode =
                AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SUCCESSFUL;

        // Store execution data.
        SharedPreferences sharedPreferences =
                CONTEXT.getSharedPreferences(SHARED_PREFS_BACKGROUND_JOBS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putLong(keyJobStartTime, jobStartTime);
        editor.putLong(keyExecutionPeriod, executionPeriod);
        editor.commit();

        doNothing()
                .when(mLogger)
                .logJobStatsHelper(
                        JOB_ID_1, executionLatency, executionPeriod, resultCode, stopReason);
        mLogger.logExecutionStats(JOB_ID_1, jobStopTime, resultCode, stopReason);

        verify(mLogger)
                .logJobStatsHelper(
                        JOB_ID_1, executionLatency, executionPeriod, resultCode, stopReason);
        assertThat(
                        sharedPreferences.getLong(
                                keyJobStopTime, UNAVAILABLE_JOB_EXECUTION_STOP_TIMESTAMP))
                .isEqualTo(jobStopTime);
    }

    @Test
    public void testLogExecutionStats_invalidStats() {
        String keyJobStartTime = AdservicesJobServiceLogger.getJobStartTimestampKey(JOB_ID_1);
        String keyExecutionPeriod = AdservicesJobServiceLogger.getExecutionPeriodKey(JOB_ID_1);
        String keyJobStopTime = AdservicesJobServiceLogger.getJobStopTimestampKey(JOB_ID_1);
        long jobStopTime = 200L;
        long executionPeriod = 50L;
        int stopReason = UNAVAILABLE_STOP_REASON;
        int resultCode =
                AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SUCCESSFUL;

        // Store execution data.
        SharedPreferences sharedPreferences =
                CONTEXT.getSharedPreferences(SHARED_PREFS_BACKGROUND_JOBS, Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sharedPreferences.edit();
        // Invalid start Time.
        editor.putLong(keyJobStartTime, UNAVAILABLE_JOB_EXECUTION_PERIOD);
        editor.putLong(keyExecutionPeriod, executionPeriod);
        editor.commit();

        mLogger.logExecutionStats(JOB_ID_1, jobStopTime, resultCode, stopReason);
        // Verify stop time is not updated.
        assertThat(
                        sharedPreferences.getLong(
                                keyJobStopTime, UNAVAILABLE_JOB_EXECUTION_STOP_TIMESTAMP))
                .isEqualTo(UNAVAILABLE_JOB_EXECUTION_STOP_TIMESTAMP);

        editor = sharedPreferences.edit();
        // Invalid start Time. (later than stop time)
        editor.putLong(keyJobStartTime, jobStopTime + 1);
        editor.commit();
        mLogger.logExecutionStats(JOB_ID_1, jobStopTime, resultCode, stopReason);
        // Verify stop time is not updated.
        assertThat(
                        sharedPreferences.getLong(
                                keyJobStopTime, UNAVAILABLE_JOB_EXECUTION_STOP_TIMESTAMP))
                .isEqualTo(UNAVAILABLE_JOB_EXECUTION_STOP_TIMESTAMP);
    }

    @Test
    public void testLogJobStatsHelper() {
        ArgumentCaptor<ExecutionReportedStats> captor =
                ArgumentCaptor.forClass(ExecutionReportedStats.class);
        long executionDurationInMs = 1000L;
        long executionFrequencyInMs = 2000L * MILLISECONDS_PER_MINUTE;
        int resultCode =
                AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SUCCESSFUL;
        int stopReason = UNAVAILABLE_STOP_REASON;

        mLogger.logJobStatsHelper(
                JOB_ID_1, executionDurationInMs, executionFrequencyInMs, resultCode, stopReason);

        verify(mMockStatsdLogger).logExecutionReportedStats(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(
                        ExecutionReportedStats.builder()
                                .setJobId(JOB_ID_1)
                                .setExecutionLatencyMs((int) executionDurationInMs)
                                .setExecutionPeriodMinute(
                                        (int) (executionFrequencyInMs / MILLISECONDS_PER_MINUTE))
                                .setExecutionResultCode(resultCode)
                                .setStopReason(stopReason)
                                .build());
    }

    @Test
    public void testLogJobStatsHelper_overflowValues() {
        ArgumentCaptor<ExecutionReportedStats> captor =
                ArgumentCaptor.forClass(ExecutionReportedStats.class);
        long executionDurationInMs = 1L + Integer.MAX_VALUE;
        long executionFrequencyInMs = (1L + Integer.MAX_VALUE) * MILLISECONDS_PER_MINUTE;
        int resultCode =
                AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED__EXECUTION_RESULT_CODE__SUCCESSFUL;
        int stopReason = UNAVAILABLE_STOP_REASON;

        mLogger.logJobStatsHelper(
                JOB_ID_1, executionDurationInMs, executionFrequencyInMs, resultCode, stopReason);

        verify(mMockStatsdLogger).logExecutionReportedStats(captor.capture());
        assertThat(captor.getValue())
                .isEqualTo(
                        ExecutionReportedStats.builder()
                                .setJobId(JOB_ID_1)
                                .setExecutionLatencyMs(Integer.MAX_VALUE)
                                .setExecutionPeriodMinute(Integer.MAX_VALUE)
                                .setExecutionResultCode(resultCode)
                                .setStopReason(stopReason)
                                .build());
    }

    @Test
    public void testConvertLongToInteger() {
        assertThat(AdservicesJobServiceLogger.convertLongToInteger((long) Integer.MIN_VALUE - 1))
                .isEqualTo(Integer.MIN_VALUE);
        assertThat(AdservicesJobServiceLogger.convertLongToInteger((long) Integer.MAX_VALUE + 1))
                .isEqualTo(Integer.MAX_VALUE);
        assertThat(AdservicesJobServiceLogger.convertLongToInteger(1000L)).isEqualTo(1000);
    }
}
