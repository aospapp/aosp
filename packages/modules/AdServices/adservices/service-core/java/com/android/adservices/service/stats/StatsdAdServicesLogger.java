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

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_CLASS__UNKNOWN;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACK_COMPAT_EPOCH_COMPUTATION_CLASSIFIER_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_BACK_COMPAT_GET_TOPICS_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_CONSENT_MIGRATED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_EPOCH_COMPUTATION_CLASSIFIER_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_EPOCH_COMPUTATION_GET_TOP_TOPICS_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_GET_TOPICS_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_DEBUG_KEYS;
import static com.android.adservices.service.stats.AdServicesStatsLog.BACKGROUND_FETCH_PROCESS_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.RUN_AD_BIDDING_PER_CA_PROCESS_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.RUN_AD_BIDDING_PROCESS_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.RUN_AD_SCORING_PROCESS_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.RUN_AD_SELECTION_PROCESS_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.UPDATE_CUSTOM_AUDIENCE_PROCESS_REPORTED;

import android.annotation.NonNull;
import android.util.proto.ProtoOutputStream;

import com.android.adservices.errorlogging.AdServicesErrorStats;
import com.android.adservices.errorlogging.StatsdAdServicesErrorLogger;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.spe.stats.ExecutionReportedStats;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;
import com.android.modules.utils.build.SdkLevel;

import javax.annotation.concurrent.ThreadSafe;

/**
 * {@link AdServicesLogger} that log stats to StatsD and {@link StatsdAdServicesErrorLogger} that
 * logs error stats to Statsd.
 */
@ThreadSafe
public class StatsdAdServicesLogger implements AdServicesLogger, StatsdAdServicesErrorLogger {
    private static final int AD_SERVICES_TOPIC_IDS_FIELD_ID = 1;

    @GuardedBy("SINGLETON_LOCK")
    private static volatile StatsdAdServicesLogger sStatsdAdServicesLogger;

    private static final Object SINGLETON_LOCK = new Object();

    @NonNull private final Flags mFlags;

    @VisibleForTesting
    protected StatsdAdServicesLogger(@NonNull Flags mFlags) {
        this.mFlags = mFlags;
    }

    /** Returns an instance of {@link StatsdAdServicesLogger}. */
    public static StatsdAdServicesLogger getInstance() {
        if (sStatsdAdServicesLogger == null) {
            synchronized (SINGLETON_LOCK) {
                if (sStatsdAdServicesLogger == null) {
                    sStatsdAdServicesLogger = new StatsdAdServicesLogger(FlagsFactory.getFlags());
                }
            }
        }
        return sStatsdAdServicesLogger;
    }

    /** log method for measurement reporting. */
    public void logMeasurementReports(MeasurementReportsStats measurementReportsStats) {
        AdServicesStatsLog.write(
                measurementReportsStats.getCode(),
                measurementReportsStats.getType(),
                measurementReportsStats.getResultCode(),
                measurementReportsStats.getFailureType(),
                measurementReportsStats.getUploadMethod(),
                measurementReportsStats.getReportingDelay());
    }

    /** log method for API call stats. */
    public void logApiCallStats(ApiCallStats apiCallStats) {
        AdServicesStatsLog.write(
                apiCallStats.getCode(),
                apiCallStats.getApiClass(),
                apiCallStats.getApiName(),
                apiCallStats.getAppPackageName(),
                apiCallStats.getSdkPackageName(),
                apiCallStats.getLatencyMillisecond(),
                apiCallStats.getResultCode());
    }

    /** log method for UI stats. */
    public void logUIStats(UIStats uiStats) {
        AdServicesStatsLog.write(uiStats.getCode(), uiStats.getRegion(), uiStats.getAction());
    }

    @Override
    public void logFledgeApiCallStats(int apiName, int resultCode, int latencyMs) {
        AdServicesStatsLog.write(
                AD_SERVICES_API_CALLED,
                AD_SERVICES_API_CALLED__API_CLASS__UNKNOWN,
                apiName,
                "",
                "",
                latencyMs,
                resultCode);
    }

    @Override
    public void logMeasurementRegistrationsResponseSize(
            MeasurementRegistrationResponseStats stats) {
        AdServicesStatsLog.write(
                stats.getCode(),
                stats.getRegistrationType(),
                stats.getResponseSize(),
                stats.getAdTechDomain(),
                stats.getInteractionType(),
                stats.getSurfaceType(),
                stats.getRegistrationStatus(),
                stats.getFailureType(),
                stats.getRegistrationDelay());
    }

    @Override
    public void logRunAdSelectionProcessReportedStats(RunAdSelectionProcessReportedStats stats) {
        AdServicesStatsLog.write(
                RUN_AD_SELECTION_PROCESS_REPORTED,
                stats.getIsRemarketingAdsWon(),
                stats.getDBAdSelectionSizeInBytes(),
                stats.getPersistAdSelectionLatencyInMillis(),
                stats.getPersistAdSelectionResultCode(),
                stats.getRunAdSelectionLatencyInMillis(),
                stats.getRunAdSelectionResultCode());
    }

    @Override
    public void logRunAdBiddingProcessReportedStats(RunAdBiddingProcessReportedStats stats) {
        AdServicesStatsLog.write(
                RUN_AD_BIDDING_PROCESS_REPORTED,
                stats.getGetBuyersCustomAudienceLatencyInMills(),
                stats.getGetBuyersCustomAudienceResultCode(),
                stats.getNumBuyersRequested(),
                stats.getNumBuyersFetched(),
                stats.getNumOfAdsEnteringBidding(),
                stats.getNumOfCasEnteringBidding(),
                stats.getNumOfCasPostBidding(),
                stats.getRatioOfCasSelectingRmktAds(),
                stats.getRunAdBiddingLatencyInMillis(),
                stats.getRunAdBiddingResultCode(),
                stats.getTotalAdBiddingStageLatencyInMillis());
    }

    @Override
    public void logRunAdScoringProcessReportedStats(RunAdScoringProcessReportedStats stats) {
        AdServicesStatsLog.write(
                RUN_AD_SCORING_PROCESS_REPORTED,
                stats.getGetAdSelectionLogicLatencyInMillis(),
                stats.getGetAdSelectionLogicResultCode(),
                stats.getGetAdSelectionLogicScriptType(),
                stats.getFetchedAdSelectionLogicScriptSizeInBytes(),
                stats.getGetTrustedScoringSignalsLatencyInMillis(),
                stats.getGetTrustedScoringSignalsResultCode(),
                stats.getFetchedTrustedScoringSignalsDataSizeInBytes(),
                stats.getScoreAdsLatencyInMillis(),
                stats.getGetAdScoresLatencyInMillis(),
                stats.getGetAdScoresResultCode(),
                stats.getNumOfCasEnteringScoring(),
                stats.getNumOfRemarketingAdsEnteringScoring(),
                stats.getNumOfContextualAdsEnteringScoring(),
                stats.getRunAdScoringLatencyInMillis(),
                stats.getRunAdScoringResultCode());
    }

    @Override
    public void logRunAdBiddingPerCAProcessReportedStats(
            RunAdBiddingPerCAProcessReportedStats stats) {
        AdServicesStatsLog.write(
                RUN_AD_BIDDING_PER_CA_PROCESS_REPORTED,
                stats.getNumOfAdsForBidding(),
                stats.getRunAdBiddingPerCaLatencyInMillis(),
                stats.getRunAdBiddingPerCaResultCode(),
                stats.getGetBuyerDecisionLogicLatencyInMillis(),
                stats.getGetBuyerDecisionLogicResultCode(),
                stats.getBuyerDecisionLogicScriptType(),
                stats.getFetchedBuyerDecisionLogicScriptSizeInBytes(),
                stats.getNumOfKeysOfTrustedBiddingSignals(),
                stats.getFetchedTrustedBiddingSignalsDataSizeInBytes(),
                stats.getGetTrustedBiddingSignalsLatencyInMillis(),
                stats.getGetTrustedBiddingSignalsResultCode(),
                stats.getGenerateBidsLatencyInMillis(),
                stats.getRunBiddingLatencyInMillis(),
                stats.getRunBiddingResultCode());
    }

    @Override
    public void logBackgroundFetchProcessReportedStats(BackgroundFetchProcessReportedStats stats) {
        AdServicesStatsLog.write(
                BACKGROUND_FETCH_PROCESS_REPORTED,
                stats.getLatencyInMillis(),
                stats.getNumOfEligibleToUpdateCas(),
                stats.getResultCode());
    }

    @Override
    public void logUpdateCustomAudienceProcessReportedStats(
            UpdateCustomAudienceProcessReportedStats stats) {
        AdServicesStatsLog.write(
                UPDATE_CUSTOM_AUDIENCE_PROCESS_REPORTED,
                stats.getLatencyInMills(),
                stats.getResultCode(),
                stats.getDataSizeOfAdsInBytes(),
                stats.getNumOfAds());
    }

    @Override
    public void logGetTopicsReportedStats(GetTopicsReportedStats stats) {
        boolean isCompatLoggingEnabled = !mFlags.getCompatLoggingKillSwitch();
        if (isCompatLoggingEnabled) {
            AdServicesStatsLog.write(
                    AD_SERVICES_BACK_COMPAT_GET_TOPICS_REPORTED,
                    // TODO(b/266626836) Add topic ids logging once long term solution is identified
                    stats.getDuplicateTopicCount(),
                    stats.getFilteredBlockedTopicCount(),
                    stats.getTopicIdsCount());
        }

        // This atom can only be logged on T+ due to usage of repeated fields. See go/rbc-ww-logging
        // for why we are temporarily double logging on T+.
        if (SdkLevel.isAtLeastT()) {
            AdServicesStatsLog.write(
                    AD_SERVICES_GET_TOPICS_REPORTED,
                    new int[] {}, // TODO(b/256649873): Log empty list until long term solution.
                    stats.getDuplicateTopicCount(),
                    stats.getFilteredBlockedTopicCount(),
                    stats.getTopicIdsCount());
        }
    }

    @Override
    public void logEpochComputationGetTopTopicsStats(EpochComputationGetTopTopicsStats stats) {
        AdServicesStatsLog.write(
                AD_SERVICES_EPOCH_COMPUTATION_GET_TOP_TOPICS_REPORTED,
                stats.getTopTopicCount(),
                stats.getPaddedRandomTopicsCount(),
                stats.getAppsConsideredCount(),
                stats.getSdksConsideredCount());
    }

    @Override
    public void logEpochComputationClassifierStats(EpochComputationClassifierStats stats) {
        int[] topicIds = stats.getTopicIds().stream().mapToInt(Integer::intValue).toArray();

        boolean isCompatLoggingEnabled = !mFlags.getCompatLoggingKillSwitch();
        if (isCompatLoggingEnabled) {
            long modeBytesFieldId =
                    ProtoOutputStream.FIELD_COUNT_REPEATED // topic_ids field is repeated.
                            // topic_id is represented by int32 type.
                            | ProtoOutputStream.FIELD_TYPE_INT32
                            // Field ID of topic_ids field in AdServicesTopicIds proto.
                            | AD_SERVICES_TOPIC_IDS_FIELD_ID;

            AdServicesStatsLog.write(
                    AD_SERVICES_BACK_COMPAT_EPOCH_COMPUTATION_CLASSIFIER_REPORTED,
                    toBytes(modeBytesFieldId, topicIds),
                    stats.getBuildId(),
                    stats.getAssetVersion(),
                    stats.getClassifierType().getCompatLoggingValue(),
                    stats.getOnDeviceClassifierStatus().getCompatLoggingValue(),
                    stats.getPrecomputedClassifierStatus().getCompatLoggingValue());
        }

        // This atom can only be logged on T+ due to usage of repeated fields. See go/rbc-ww-logging
        // for why we are temporarily double logging on T+.
        if (SdkLevel.isAtLeastT()) {
            AdServicesStatsLog.write(
                    AD_SERVICES_EPOCH_COMPUTATION_CLASSIFIER_REPORTED,
                    topicIds,
                    stats.getBuildId(),
                    stats.getAssetVersion(),
                    stats.getClassifierType().getLoggingValue(),
                    stats.getOnDeviceClassifierStatus().getLoggingValue(),
                    stats.getPrecomputedClassifierStatus().getLoggingValue());
        }
    }

    @Override
    public void logMeasurementDebugKeysMatch(MsmtDebugKeysMatchStats stats) {
        AdServicesStatsLog.write(
                AD_SERVICES_MEASUREMENT_DEBUG_KEYS,
                stats.getAdTechEnrollmentId(),
                stats.getAttributionType(),
                stats.isMatched(),
                stats.getDebugJoinKeyHashedValue(),
                stats.getDebugJoinKeyHashLimit());
    }

    @Override
    public void logAdServicesError(AdServicesErrorStats stats) {
        AdServicesStatsLog.write(
                AD_SERVICES_ERROR_REPORTED,
                stats.getErrorCode(),
                stats.getPpapiName(),
                stats.getClassName(),
                stats.getMethodName(),
                stats.getLineNumber(),
                stats.getLastObservedExceptionName());
    }

    /** Logging method for AdServices background job execution stats. */
    public void logExecutionReportedStats(ExecutionReportedStats stats) {
        AdServicesStatsLog.write(
                AD_SERVICES_BACKGROUND_JOBS_EXECUTION_REPORTED,
                stats.getJobId(),
                stats.getExecutionLatencyMs(),
                stats.getExecutionPeriodMinute(),
                stats.getExecutionResultCode(),
                stats.getStopReason());
    }

    /** log method for measurement attribution. */
    public void logMeasurementAttributionStats(
            MeasurementAttributionStats measurementAttributionStats) {
        AdServicesStatsLog.write(
                measurementAttributionStats.getCode(),
                measurementAttributionStats.getSourceType(),
                measurementAttributionStats.getSurfaceType(),
                measurementAttributionStats.getResult(),
                measurementAttributionStats.getFailureType(),
                measurementAttributionStats.isSourceDerived(),
                measurementAttributionStats.isInstallAttribution(),
                measurementAttributionStats.getAttributionDelay());
    }

    /** log method for measurement wipeout. */
    public void logMeasurementWipeoutStats(MeasurementWipeoutStats measurementWipeoutStats) {
        AdServicesStatsLog.write(
                measurementWipeoutStats.getCode(), measurementWipeoutStats.getWipeoutType());
    }

    /** log method for measurement attribution. */
    public void logMeasurementDelayedSourceRegistrationStats(
            MeasurementDelayedSourceRegistrationStats measurementDelayedSourceRegistrationStats) {
        AdServicesStatsLog.write(
                measurementDelayedSourceRegistrationStats.getCode(),
                measurementDelayedSourceRegistrationStats.getRegistrationStatus(),
                measurementDelayedSourceRegistrationStats.getRegistrationDelay());
    }

    /** log method for consent migrations. */
    public void logConsentMigrationStats(ConsentMigrationStats stats) {
        if (mFlags.getAdservicesConsentMigrationLoggingEnabled()) {
            AdServicesStatsLog.write(
                    AD_SERVICES_CONSENT_MIGRATED,
                    stats.getMsmtConsent(),
                    stats.getTopicsConsent(),
                    stats.getFledgeConsent(),
                    stats.getDefaultConsent(),
                    stats.getMigrationType().getMigrationTypeValue(),
                    stats.getRegion(),
                    stats.getMigrationStatus().getMigrationStatusValue());
        }
    }

    @NonNull
    private byte[] toBytes(long fieldId, @NonNull int[] values) {
        ProtoOutputStream protoOutputStream = new ProtoOutputStream();
        for (int value : values) {
            protoOutputStream.write(fieldId, value);
        }
        return protoOutputStream.getBytes();
    }
}
