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

/** Interface for Adservices logger. */
public interface AdServicesLogger {
    /** log method for MeasurementReportsStats. */
    void logMeasurementReports(MeasurementReportsStats measurementReportsStats);

    /** log ApiCallStats which has stats about the API call such as the status. */
    void logApiCallStats(ApiCallStats apiCallStats);

    /** log UIStats which has stats about UI events. */
    void logUIStats(UIStats uiStats);

    /** Logs API call stats specific to the FLEDGE APIs as an {@link ApiCallStats} object. */
    void logFledgeApiCallStats(int apiName, int resultCode, int latencyMs);

    /** Logs measurement registrations response size. */
    void logMeasurementRegistrationsResponseSize(MeasurementRegistrationResponseStats stats);

    /**
     * Logs the runAdSelection process stats as an {@link RunAdSelectionProcessReportedStats}
     * object.
     */
    void logRunAdSelectionProcessReportedStats(RunAdSelectionProcessReportedStats stats);

    /**
     * Logs the runAdBidding process stats as an {@link RunAdBiddingProcessReportedStats} object.
     */
    void logRunAdBiddingProcessReportedStats(RunAdBiddingProcessReportedStats stats);

    /**
     * Logs the runAdScoring process stats as an {@link RunAdScoringProcessReportedStats} object.
     */
    void logRunAdScoringProcessReportedStats(RunAdScoringProcessReportedStats stats);

    /**
     * Logs the runAdBiddingPerCA process stats as an {@link RunAdBiddingPerCAProcessReportedStats}
     * object.
     */
    void logRunAdBiddingPerCAProcessReportedStats(RunAdBiddingPerCAProcessReportedStats stats);

    /**
     * Logs the backgroundFetch process stats as an {@link BackgroundFetchProcessReportedStats}
     * object.
     */
    void logBackgroundFetchProcessReportedStats(BackgroundFetchProcessReportedStats stats);

    /**
     * Logs the updateCustomAudience process stats as an {@link
     * com.android.adservices.service.stats.UpdateCustomAudienceProcessReportedStats} objects.
     */
    void logUpdateCustomAudienceProcessReportedStats(
            UpdateCustomAudienceProcessReportedStats stats);

    /**
     * Logs GetTopics API call stats as an {@link
     * com.android.adservices.service.stats.GetTopicsReportedStats} object.
     */
    void logGetTopicsReportedStats(GetTopicsReportedStats stats);

    /**
     * Logs stats for getTopTopics as an {@link
     * com.android.adservices.service.stats.EpochComputationGetTopTopicsStats} object.
     */
    void logEpochComputationGetTopTopicsStats(EpochComputationGetTopTopicsStats stats);

    /**
     * Logs classifier stats during epoch computation as an {@link
     * com.android.adservices.service.stats.EpochComputationClassifierStats} object.
     */
    void logEpochComputationClassifierStats(EpochComputationClassifierStats stats);

    /** Logs measurement debug keys stats. */
    void logMeasurementDebugKeysMatch(MsmtDebugKeysMatchStats stats);

    /** Logs measurement attribution stats. */
    void logMeasurementAttributionStats(MeasurementAttributionStats measurementAttributionStats);

    /** Logs measurement wipeout stats. */
    void logMeasurementWipeoutStats(MeasurementWipeoutStats measurementWipeoutStats);

    /** Logs measurement delayed source registration stats. */
    void logMeasurementDelayedSourceRegistrationStats(
            MeasurementDelayedSourceRegistrationStats measurementDelayedSourceRegistrationStats);
}
