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

package com.android.adservices.service.stats;

import com.android.internal.annotations.VisibleForTesting;

import javax.annotation.concurrent.ThreadSafe;

/** AdServicesLogger that delegate to the appropriate Logger Implementations. */
@ThreadSafe
public class AdServicesLoggerImpl implements AdServicesLogger {
    private static volatile AdServicesLoggerImpl sAdServicesLogger;
    private final StatsdAdServicesLogger mStatsdAdServicesLogger;

    private AdServicesLoggerImpl() {
        mStatsdAdServicesLogger = StatsdAdServicesLogger.getInstance();
    }

    @VisibleForTesting
    AdServicesLoggerImpl(StatsdAdServicesLogger statsdAdServicesLogger) {
        mStatsdAdServicesLogger = statsdAdServicesLogger;
    }

    /** Returns an instance of AdServicesLogger. */
    public static AdServicesLoggerImpl getInstance() {
        if (sAdServicesLogger == null) {
            synchronized (AdServicesLoggerImpl.class) {
                if (sAdServicesLogger == null) {
                    sAdServicesLogger = new AdServicesLoggerImpl();
                }
            }
        }
        return sAdServicesLogger;
    }

    @Override
    public void logMeasurementReports(MeasurementReportsStats measurementReportsStats) {
        mStatsdAdServicesLogger.logMeasurementReports(measurementReportsStats);
    }

    @Override
    public void logApiCallStats(ApiCallStats apiCallStats) {
        mStatsdAdServicesLogger.logApiCallStats(apiCallStats);
    }

    @Override
    public void logUIStats(UIStats uiStats) {
        mStatsdAdServicesLogger.logUIStats(uiStats);
    }

    @Override
    public void logFledgeApiCallStats(int apiName, int resultCode, int latencyMs) {
        mStatsdAdServicesLogger.logFledgeApiCallStats(apiName, resultCode, latencyMs);
    }

    @Override
    public void logMeasurementRegistrationsResponseSize(
            MeasurementRegistrationResponseStats stats) {
        mStatsdAdServicesLogger.logMeasurementRegistrationsResponseSize(stats);
    }

    @Override
    public void logRunAdSelectionProcessReportedStats(RunAdSelectionProcessReportedStats stats) {
        mStatsdAdServicesLogger.logRunAdSelectionProcessReportedStats(stats);
    }

    @Override
    public void logRunAdBiddingProcessReportedStats(RunAdBiddingProcessReportedStats stats) {
        mStatsdAdServicesLogger.logRunAdBiddingProcessReportedStats(stats);
    }

    @Override
    public void logRunAdScoringProcessReportedStats(RunAdScoringProcessReportedStats stats) {
        mStatsdAdServicesLogger.logRunAdScoringProcessReportedStats(stats);
    }

    @Override
    public void logRunAdBiddingPerCAProcessReportedStats(
            RunAdBiddingPerCAProcessReportedStats stats) {
        mStatsdAdServicesLogger.logRunAdBiddingPerCAProcessReportedStats(stats);
    }

    @Override
    public void logBackgroundFetchProcessReportedStats(BackgroundFetchProcessReportedStats stats) {
        mStatsdAdServicesLogger.logBackgroundFetchProcessReportedStats(stats);
    }

    @Override
    public void logUpdateCustomAudienceProcessReportedStats(
            UpdateCustomAudienceProcessReportedStats stats) {
        mStatsdAdServicesLogger.logUpdateCustomAudienceProcessReportedStats(stats);
    }

    @Override
    public void logGetTopicsReportedStats(GetTopicsReportedStats stats) {
        mStatsdAdServicesLogger.logGetTopicsReportedStats(stats);
    }

    @Override
    public void logEpochComputationGetTopTopicsStats(EpochComputationGetTopTopicsStats stats) {
        mStatsdAdServicesLogger.logEpochComputationGetTopTopicsStats(stats);
    }

    @Override
    public void logEpochComputationClassifierStats(EpochComputationClassifierStats stats) {
        mStatsdAdServicesLogger.logEpochComputationClassifierStats(stats);
    }

    @Override
    public void logMeasurementDebugKeysMatch(MsmtDebugKeysMatchStats stats) {
        mStatsdAdServicesLogger.logMeasurementDebugKeysMatch(stats);
    }

    @Override
    public void logMeasurementAttributionStats(
            MeasurementAttributionStats measurementAttributionStats) {
        mStatsdAdServicesLogger.logMeasurementAttributionStats(measurementAttributionStats);
    }

    @Override
    public void logMeasurementWipeoutStats(MeasurementWipeoutStats measurementWipeoutStats) {
        mStatsdAdServicesLogger.logMeasurementWipeoutStats(measurementWipeoutStats);
    }

    @Override
    public void logMeasurementDelayedSourceRegistrationStats(
            MeasurementDelayedSourceRegistrationStats measurementDelayedSourceRegistrationStats) {
        mStatsdAdServicesLogger.logMeasurementDelayedSourceRegistrationStats(
                measurementDelayedSourceRegistrationStats);
    }
}
