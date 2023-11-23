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

import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_CLASS__TARGETING;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_ATTRIBUTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_DEBUG_KEYS__ATTRIBUTION_TYPE__APP_WEB;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_DELAYED_SOURCE_REGISTRATION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_REPORTS_UPLOADED__TYPE__EVENT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MEASUREMENT_WIPEOUT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_MESUREMENT_REPORTS_UPLOADED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_OUT_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__REGION__ROW;
import static com.android.adservices.service.stats.BackgroundFetchProcessReportedStatsTest.LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.BackgroundFetchProcessReportedStatsTest.NUM_OF_ELIGIBLE_TO_UPDATE_CAS;
import static com.android.adservices.service.stats.BackgroundFetchProcessReportedStatsTest.RESULT_CODE;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.BUYER_DECISION_LOGIC_SCRIPT_TYPE;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.FETCHED_BUYER_DECISION_LOGIC_SCRIPT_SIZE_IN_BYTES;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.FETCHED_TRUSTED_BIDDING_SIGNALS_DATA_SIZE_IN_BYTES;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.GENERATE_BIDS_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.GET_BUYER_DECISION_LOGIC_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.GET_BUYER_DECISION_LOGIC_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.GET_TRUSTED_BIDDING_SIGNALS_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.GET_TRUSTED_BIDDING_SIGNALS_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.NUM_OF_ADS_FOR_BIDDING;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.NUM_OF_KEYS_OF_TRUSTED_BIDDING_SIGNALS;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.RUN_AD_BIDDING_PER_CA_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.RUN_AD_BIDDING_PER_CA_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.RUN_BIDDING_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdBiddingPerCAProcessReportedStatsTest.RUN_BIDDING_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.GET_BUYERS_CUSTOM_AUDIENCE_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.GET_BUYERS_CUSTOM_AUDIENCE_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.NUM_BUYERS_FETCHED;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.NUM_BUYERS_REQUESTED;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.NUM_OF_ADS_ENTERING_BIDDING;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.NUM_OF_CAS_ENTERING_BIDDING;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.NUM_OF_CAS_POSTING_BIDDING;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.RATIO_OF_CAS_SELECTING_RMKT_ADS;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.RUN_AD_BIDDING_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.RUN_AD_BIDDING_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdBiddingProcessReportedStatsTest.TOTAL_AD_BIDDING_STAGE_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.FETCHED_AD_SELECTION_LOGIC_SCRIPT_SIZE_IN_BYTES;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.FETCHED_TRUSTED_SCORING_SIGNALS_DATA_SIZE_IN_BYTES;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.GET_AD_SCORES_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.GET_AD_SCORES_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.GET_AD_SELECTION_LOGIC_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.GET_AD_SELECTION_LOGIC_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.GET_AD_SELECTION_LOGIC_SCRIPT_TYPE;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.GET_TRUSTED_SCORING_SIGNALS_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.GET_TRUSTED_SCORING_SIGNALS_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.NUM_OF_CAS_ENTERING_SCORING;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.NUM_OF_CONTEXTUAL_ADS_ENTERING_SCORING;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.NUM_OF_REMARKETING_ADS_ENTERING_SCORING;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.RUN_AD_SCORING_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.RUN_AD_SCORING_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdScoringProcessReportedStatsTest.SCORE_ADS_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdSelectionProcessReportedStatsTest.DB_AD_SELECTION_SIZE_IN_BYTES;
import static com.android.adservices.service.stats.RunAdSelectionProcessReportedStatsTest.IS_RMKT_ADS_WON;
import static com.android.adservices.service.stats.RunAdSelectionProcessReportedStatsTest.PERSIST_AD_SELECTION_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdSelectionProcessReportedStatsTest.PERSIST_AD_SELECTION_RESULT_CODE;
import static com.android.adservices.service.stats.RunAdSelectionProcessReportedStatsTest.RUN_AD_SELECTION_LATENCY_IN_MILLIS;
import static com.android.adservices.service.stats.RunAdSelectionProcessReportedStatsTest.RUN_AD_SELECTION_RESULT_CODE;
import static com.android.adservices.service.stats.UpdateCustomAudienceProcessReportedStatsTest.DATA_SIZE_OF_ADS_IN_BYTES;
import static com.android.adservices.service.stats.UpdateCustomAudienceProcessReportedStatsTest.NUM_OF_ADS;

import static org.junit.Assert.assertEquals;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;

import com.android.adservices.service.measurement.WipeoutStatus;
import com.android.adservices.service.measurement.attribution.AttributionStatus;

import org.junit.Before;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

/** Unit tests for {@link AdServicesLoggerImpl}. */
public class AdServicesLoggerImplTest {
    @Mock StatsdAdServicesLogger mStatsdLoggerMock;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    public void testLogFledgeApiCallStats() {
        final int latencyMs = 10;
        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logFledgeApiCallStats(
                AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS, STATUS_SUCCESS, latencyMs);
        verify(mStatsdLoggerMock)
                .logFledgeApiCallStats(
                        eq(AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS),
                        eq(STATUS_SUCCESS),
                        eq(latencyMs));
    }

    @Test
    public void testLogRunAdSelectionProcessReportedStats() {
        RunAdSelectionProcessReportedStats stats =
                RunAdSelectionProcessReportedStats.builder()
                        .setIsRemarketingAdsWon(IS_RMKT_ADS_WON)
                        .setDBAdSelectionSizeInBytes(DB_AD_SELECTION_SIZE_IN_BYTES)
                        .setPersistAdSelectionLatencyInMillis(
                                PERSIST_AD_SELECTION_LATENCY_IN_MILLIS)
                        .setPersistAdSelectionResultCode(PERSIST_AD_SELECTION_RESULT_CODE)
                        .setRunAdSelectionLatencyInMillis(RUN_AD_SELECTION_LATENCY_IN_MILLIS)
                        .setRunAdSelectionResultCode(RUN_AD_SELECTION_RESULT_CODE)
                        .build();
        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logRunAdSelectionProcessReportedStats(stats);
        ArgumentCaptor<RunAdSelectionProcessReportedStats> argumentCaptor =
                ArgumentCaptor.forClass(RunAdSelectionProcessReportedStats.class);
        verify(mStatsdLoggerMock).logRunAdSelectionProcessReportedStats(argumentCaptor.capture());
        assertEquals(argumentCaptor.getValue().getIsRemarketingAdsWon(), IS_RMKT_ADS_WON);
        assertEquals(
                argumentCaptor.getValue().getDBAdSelectionSizeInBytes(),
                DB_AD_SELECTION_SIZE_IN_BYTES);
        assertEquals(
                argumentCaptor.getValue().getPersistAdSelectionLatencyInMillis(),
                PERSIST_AD_SELECTION_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getRunAdSelectionResultCode(),
                PERSIST_AD_SELECTION_RESULT_CODE);
        assertEquals(
                argumentCaptor.getValue().getRunAdSelectionLatencyInMillis(),
                RUN_AD_SELECTION_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getRunAdSelectionResultCode(),
                RUN_AD_SELECTION_RESULT_CODE);
    }

    @Test
    public void testLogRunAdScoringProcessReportedStats() {
        RunAdScoringProcessReportedStats stats =
                RunAdScoringProcessReportedStats.builder()
                        .setGetAdSelectionLogicLatencyInMillis(
                                GET_AD_SELECTION_LOGIC_LATENCY_IN_MILLIS)
                        .setGetAdSelectionLogicResultCode(GET_AD_SELECTION_LOGIC_RESULT_CODE)
                        .setGetAdSelectionLogicScriptType(GET_AD_SELECTION_LOGIC_SCRIPT_TYPE)
                        .setFetchedAdSelectionLogicScriptSizeInBytes(
                                FETCHED_AD_SELECTION_LOGIC_SCRIPT_SIZE_IN_BYTES)
                        .setGetTrustedScoringSignalsLatencyInMillis(
                                GET_TRUSTED_SCORING_SIGNALS_LATENCY_IN_MILLIS)
                        .setGetTrustedScoringSignalsResultCode(
                                GET_TRUSTED_SCORING_SIGNALS_RESULT_CODE)
                        .setFetchedTrustedScoringSignalsDataSizeInBytes(
                                FETCHED_TRUSTED_SCORING_SIGNALS_DATA_SIZE_IN_BYTES)
                        .setScoreAdsLatencyInMillis(SCORE_ADS_LATENCY_IN_MILLIS)
                        .setGetAdScoresLatencyInMillis(GET_AD_SCORES_LATENCY_IN_MILLIS)
                        .setGetAdScoresResultCode(GET_AD_SCORES_RESULT_CODE)
                        .setNumOfCasEnteringScoring(NUM_OF_CAS_ENTERING_SCORING)
                        .setNumOfRemarketingAdsEnteringScoring(
                                NUM_OF_REMARKETING_ADS_ENTERING_SCORING)
                        .setNumOfContextualAdsEnteringScoring(
                                NUM_OF_CONTEXTUAL_ADS_ENTERING_SCORING)
                        .setRunAdScoringLatencyInMillis(RUN_AD_SCORING_LATENCY_IN_MILLIS)
                        .setRunAdScoringResultCode(RUN_AD_SCORING_RESULT_CODE)
                        .build();

        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logRunAdScoringProcessReportedStats(stats);
        ArgumentCaptor<RunAdScoringProcessReportedStats> argumentCaptor =
                ArgumentCaptor.forClass(RunAdScoringProcessReportedStats.class);
        verify(mStatsdLoggerMock).logRunAdScoringProcessReportedStats(argumentCaptor.capture());
        assertEquals(
                argumentCaptor.getValue().getGetAdSelectionLogicLatencyInMillis(),
                GET_AD_SELECTION_LOGIC_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getGetAdSelectionLogicResultCode(),
                GET_AD_SELECTION_LOGIC_RESULT_CODE);
        assertEquals(
                argumentCaptor.getValue().getGetAdSelectionLogicScriptType(),
                GET_AD_SELECTION_LOGIC_SCRIPT_TYPE);
        assertEquals(
                argumentCaptor.getValue().getFetchedAdSelectionLogicScriptSizeInBytes(),
                FETCHED_AD_SELECTION_LOGIC_SCRIPT_SIZE_IN_BYTES);
        assertEquals(
                argumentCaptor.getValue().getGetTrustedScoringSignalsLatencyInMillis(),
                GET_TRUSTED_SCORING_SIGNALS_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getGetTrustedScoringSignalsResultCode(),
                GET_TRUSTED_SCORING_SIGNALS_RESULT_CODE);
        assertEquals(
                argumentCaptor.getValue().getFetchedTrustedScoringSignalsDataSizeInBytes(),
                FETCHED_TRUSTED_SCORING_SIGNALS_DATA_SIZE_IN_BYTES);
        assertEquals(
                argumentCaptor.getValue().getScoreAdsLatencyInMillis(),
                SCORE_ADS_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getGetAdScoresLatencyInMillis(),
                GET_AD_SCORES_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getGetAdScoresResultCode(), GET_AD_SCORES_RESULT_CODE);
        assertEquals(
                argumentCaptor.getValue().getNumOfCasEnteringScoring(),
                NUM_OF_CAS_ENTERING_SCORING);
        assertEquals(
                argumentCaptor.getValue().getNumOfRemarketingAdsEnteringScoring(),
                NUM_OF_REMARKETING_ADS_ENTERING_SCORING);
        assertEquals(
                argumentCaptor.getValue().getNumOfContextualAdsEnteringScoring(),
                NUM_OF_CONTEXTUAL_ADS_ENTERING_SCORING);
        assertEquals(
                argumentCaptor.getValue().getRunAdScoringLatencyInMillis(),
                RUN_AD_SCORING_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getRunAdScoringResultCode(), RUN_AD_SCORING_RESULT_CODE);
    }

    @Test
    public void testLogRunAdBiddingProcessReportedStats() {
        RunAdBiddingProcessReportedStats stats =
                RunAdBiddingProcessReportedStats.builder()
                        .setGetBuyersCustomAudienceLatencyInMills(
                                GET_BUYERS_CUSTOM_AUDIENCE_LATENCY_IN_MILLIS)
                        .setGetBuyersCustomAudienceResultCode(
                                GET_BUYERS_CUSTOM_AUDIENCE_RESULT_CODE)
                        .setNumBuyersRequested(NUM_BUYERS_REQUESTED)
                        .setNumBuyersFetched(NUM_BUYERS_FETCHED)
                        .setNumOfAdsEnteringBidding(NUM_OF_ADS_ENTERING_BIDDING)
                        .setNumOfCasEnteringBidding(NUM_OF_CAS_ENTERING_BIDDING)
                        .setNumOfCasPostBidding(NUM_OF_CAS_POSTING_BIDDING)
                        .setRatioOfCasSelectingRmktAds(RATIO_OF_CAS_SELECTING_RMKT_ADS)
                        .setRunAdBiddingLatencyInMillis(RUN_AD_BIDDING_LATENCY_IN_MILLIS)
                        .setRunAdBiddingResultCode(RUN_AD_BIDDING_RESULT_CODE)
                        .setTotalAdBiddingStageLatencyInMillis(
                                TOTAL_AD_BIDDING_STAGE_LATENCY_IN_MILLIS)
                        .build();

        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logRunAdBiddingProcessReportedStats(stats);
        ArgumentCaptor<RunAdBiddingProcessReportedStats> argumentCaptor =
                ArgumentCaptor.forClass(RunAdBiddingProcessReportedStats.class);
        verify(mStatsdLoggerMock).logRunAdBiddingProcessReportedStats(argumentCaptor.capture());
        assertEquals(
                argumentCaptor.getValue().getGetBuyersCustomAudienceLatencyInMills(),
                GET_BUYERS_CUSTOM_AUDIENCE_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getGetBuyersCustomAudienceResultCode(),
                GET_BUYERS_CUSTOM_AUDIENCE_RESULT_CODE);
        assertEquals(argumentCaptor.getValue().getNumBuyersRequested(), NUM_BUYERS_REQUESTED);
        assertEquals(argumentCaptor.getValue().getNumBuyersFetched(), NUM_BUYERS_FETCHED);
        assertEquals(
                argumentCaptor.getValue().getNumOfAdsEnteringBidding(),
                NUM_OF_ADS_ENTERING_BIDDING);
        assertEquals(
                argumentCaptor.getValue().getNumOfCasEnteringBidding(),
                NUM_OF_CAS_ENTERING_BIDDING);
        assertEquals(
                argumentCaptor.getValue().getNumOfCasPostBidding(), NUM_OF_CAS_POSTING_BIDDING);
        assertEquals(
                argumentCaptor.getValue().getRatioOfCasSelectingRmktAds(),
                RATIO_OF_CAS_SELECTING_RMKT_ADS,
                0.0f);
        assertEquals(
                argumentCaptor.getValue().getRunAdBiddingLatencyInMillis(),
                RUN_AD_BIDDING_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getRunAdBiddingResultCode(), RUN_AD_BIDDING_RESULT_CODE);
        assertEquals(
                argumentCaptor.getValue().getTotalAdBiddingStageLatencyInMillis(),
                TOTAL_AD_BIDDING_STAGE_LATENCY_IN_MILLIS);
    }

    @Test
    public void testLogRunAdBiddingPerCAProcessReportedStats() {
        RunAdBiddingPerCAProcessReportedStats stats =
                RunAdBiddingPerCAProcessReportedStats.builder()
                        .setNumOfAdsForBidding(NUM_OF_ADS_FOR_BIDDING)
                        .setRunAdBiddingPerCaLatencyInMillis(
                                RUN_AD_BIDDING_PER_CA_LATENCY_IN_MILLIS)
                        .setRunAdBiddingPerCaResultCode(RUN_AD_BIDDING_PER_CA_RESULT_CODE)
                        .setGetBuyerDecisionLogicLatencyInMillis(
                                GET_BUYER_DECISION_LOGIC_LATENCY_IN_MILLIS)
                        .setGetBuyerDecisionLogicResultCode(GET_BUYER_DECISION_LOGIC_RESULT_CODE)
                        .setBuyerDecisionLogicScriptType(BUYER_DECISION_LOGIC_SCRIPT_TYPE)
                        .setFetchedBuyerDecisionLogicScriptSizeInBytes(
                                FETCHED_BUYER_DECISION_LOGIC_SCRIPT_SIZE_IN_BYTES)
                        .setNumOfKeysOfTrustedBiddingSignals(NUM_OF_KEYS_OF_TRUSTED_BIDDING_SIGNALS)
                        .setFetchedTrustedBiddingSignalsDataSizeInBytes(
                                FETCHED_TRUSTED_BIDDING_SIGNALS_DATA_SIZE_IN_BYTES)
                        .setGetTrustedBiddingSignalsLatencyInMillis(
                                GET_TRUSTED_BIDDING_SIGNALS_LATENCY_IN_MILLIS)
                        .setGetTrustedBiddingSignalsResultCode(
                                GET_TRUSTED_BIDDING_SIGNALS_RESULT_CODE)
                        .setGenerateBidsLatencyInMillis(GENERATE_BIDS_LATENCY_IN_MILLIS)
                        .setRunBiddingLatencyInMillis(RUN_BIDDING_LATENCY_IN_MILLIS)
                        .setRunBiddingResultCode(RUN_BIDDING_RESULT_CODE)
                        .build();

        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logRunAdBiddingPerCAProcessReportedStats(stats);
        ArgumentCaptor<RunAdBiddingPerCAProcessReportedStats> argumentCaptor =
                ArgumentCaptor.forClass(RunAdBiddingPerCAProcessReportedStats.class);
        verify(mStatsdLoggerMock)
                .logRunAdBiddingPerCAProcessReportedStats(argumentCaptor.capture());
        assertEquals(argumentCaptor.getValue().getNumOfAdsForBidding(), NUM_OF_ADS_FOR_BIDDING);
        assertEquals(
                argumentCaptor.getValue().getRunAdBiddingPerCaLatencyInMillis(),
                RUN_AD_BIDDING_PER_CA_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getRunAdBiddingPerCaResultCode(),
                RUN_AD_BIDDING_PER_CA_RESULT_CODE);
        assertEquals(
                argumentCaptor.getValue().getGetBuyerDecisionLogicLatencyInMillis(),
                GET_BUYER_DECISION_LOGIC_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getGetBuyerDecisionLogicResultCode(),
                GET_BUYER_DECISION_LOGIC_RESULT_CODE);
        assertEquals(
                argumentCaptor.getValue().getBuyerDecisionLogicScriptType(),
                BUYER_DECISION_LOGIC_SCRIPT_TYPE);
        assertEquals(
                argumentCaptor.getValue().getFetchedBuyerDecisionLogicScriptSizeInBytes(),
                FETCHED_BUYER_DECISION_LOGIC_SCRIPT_SIZE_IN_BYTES);
        assertEquals(
                argumentCaptor.getValue().getNumOfKeysOfTrustedBiddingSignals(),
                NUM_OF_KEYS_OF_TRUSTED_BIDDING_SIGNALS);
        assertEquals(
                argumentCaptor.getValue().getFetchedTrustedBiddingSignalsDataSizeInBytes(),
                FETCHED_TRUSTED_BIDDING_SIGNALS_DATA_SIZE_IN_BYTES);
        assertEquals(
                argumentCaptor.getValue().getGetTrustedBiddingSignalsLatencyInMillis(),
                GET_TRUSTED_BIDDING_SIGNALS_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getGetTrustedBiddingSignalsResultCode(),
                GET_TRUSTED_BIDDING_SIGNALS_RESULT_CODE);
        assertEquals(
                argumentCaptor.getValue().getGenerateBidsLatencyInMillis(),
                GENERATE_BIDS_LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getRunBiddingLatencyInMillis(),
                RUN_BIDDING_LATENCY_IN_MILLIS);
        assertEquals(argumentCaptor.getValue().getRunBiddingResultCode(), RUN_BIDDING_RESULT_CODE);
    }

    @Test
    public void testLogBackgroundFetchProcessReportedStats() {
        BackgroundFetchProcessReportedStats stats =
                BackgroundFetchProcessReportedStats.builder()
                        .setLatencyInMillis(LATENCY_IN_MILLIS)
                        .setNumOfEligibleToUpdateCas(NUM_OF_ELIGIBLE_TO_UPDATE_CAS)
                        .setResultCode(RESULT_CODE)
                        .build();

        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logBackgroundFetchProcessReportedStats(stats);
        ArgumentCaptor<BackgroundFetchProcessReportedStats> argumentCaptor =
                ArgumentCaptor.forClass(BackgroundFetchProcessReportedStats.class);
        verify(mStatsdLoggerMock).logBackgroundFetchProcessReportedStats(argumentCaptor.capture());
        assertEquals(argumentCaptor.getValue().getLatencyInMillis(), LATENCY_IN_MILLIS);
        assertEquals(
                argumentCaptor.getValue().getNumOfEligibleToUpdateCas(),
                NUM_OF_ELIGIBLE_TO_UPDATE_CAS);
        assertEquals(argumentCaptor.getValue().getResultCode(), RESULT_CODE);
    }

    @Test
    public void testLogUpdateCustomAudienceProcessReportedStats() {
        UpdateCustomAudienceProcessReportedStats stats =
                UpdateCustomAudienceProcessReportedStats.builder()
                        .setLatencyInMills(LATENCY_IN_MILLIS)
                        .setResultCode(RESULT_CODE)
                        .setDataSizeOfAdsInBytes(DATA_SIZE_OF_ADS_IN_BYTES)
                        .setNumOfAds(NUM_OF_ADS)
                        .build();
        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logUpdateCustomAudienceProcessReportedStats(stats);
        ArgumentCaptor<UpdateCustomAudienceProcessReportedStats> argumentCaptor =
                ArgumentCaptor.forClass(UpdateCustomAudienceProcessReportedStats.class);
        verify(mStatsdLoggerMock)
                .logUpdateCustomAudienceProcessReportedStats(argumentCaptor.capture());
        assertEquals(argumentCaptor.getValue().getLatencyInMills(), LATENCY_IN_MILLIS);
        assertEquals(argumentCaptor.getValue().getResultCode(), RESULT_CODE);
        assertEquals(
                argumentCaptor.getValue().getDataSizeOfAdsInBytes(), DATA_SIZE_OF_ADS_IN_BYTES);
        assertEquals(argumentCaptor.getValue().getNumOfAds(), NUM_OF_ADS);
    }

    @Test
    public void testLogMeasurementReportReports() {
        MeasurementReportsStats stats =
                new MeasurementReportsStats.Builder()
                        .setCode(AD_SERVICES_MESUREMENT_REPORTS_UPLOADED)
                        .setType(AD_SERVICES_MEASUREMENT_REPORTS_UPLOADED__TYPE__EVENT)
                        .setResultCode(STATUS_SUCCESS)
                        .build();
        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logMeasurementReports(stats);
        ArgumentCaptor<MeasurementReportsStats> argumentCaptor =
                ArgumentCaptor.forClass(MeasurementReportsStats.class);
        verify(mStatsdLoggerMock).logMeasurementReports(argumentCaptor.capture());
        assertEquals(argumentCaptor.getValue().getCode(), AD_SERVICES_MESUREMENT_REPORTS_UPLOADED);
        assertEquals(
                argumentCaptor.getValue().getType(),
                AD_SERVICES_MEASUREMENT_REPORTS_UPLOADED__TYPE__EVENT);
        assertEquals(argumentCaptor.getValue().getResultCode(), STATUS_SUCCESS);
    }

    @Test
    public void testLogApiCallStats() {
        final String packageName = "com.android.test";
        final String sdkName = "com.android.container";
        final int latency = 100;
        ApiCallStats stats =
                new ApiCallStats.Builder()
                        .setCode(AD_SERVICES_API_CALLED)
                        .setApiClass(AD_SERVICES_API_CALLED__API_CLASS__TARGETING)
                        .setApiName(AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS)
                        .setAppPackageName(packageName)
                        .setSdkPackageName(sdkName)
                        .setLatencyMillisecond(latency)
                        .setResultCode(STATUS_SUCCESS)
                        .build();
        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logApiCallStats(stats);
        ArgumentCaptor<ApiCallStats> argumentCaptor = ArgumentCaptor.forClass(ApiCallStats.class);
        verify(mStatsdLoggerMock).logApiCallStats(argumentCaptor.capture());
        assertEquals(argumentCaptor.getValue().getCode(), AD_SERVICES_API_CALLED);
        assertEquals(
                argumentCaptor.getValue().getApiClass(),
                AD_SERVICES_API_CALLED__API_CLASS__TARGETING);
        assertEquals(
                argumentCaptor.getValue().getApiName(),
                AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS);
        assertEquals(argumentCaptor.getValue().getAppPackageName(), packageName);
        assertEquals(argumentCaptor.getValue().getSdkPackageName(), sdkName);
        assertEquals(argumentCaptor.getValue().getLatencyMillisecond(), latency);
        assertEquals(argumentCaptor.getValue().getResultCode(), STATUS_SUCCESS);
    }

    @Test
    public void testLogUIStats() {
        UIStats stats =
                new UIStats.Builder()
                        .setCode(AD_SERVICES_SETTINGS_USAGE_REPORTED)
                        .setRegion(AD_SERVICES_SETTINGS_USAGE_REPORTED__REGION__ROW)
                        .setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_OUT_SELECTED)
                        .build();
        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logUIStats(stats);
        ArgumentCaptor<UIStats> argumentCaptor = ArgumentCaptor.forClass(UIStats.class);
        verify(mStatsdLoggerMock).logUIStats(argumentCaptor.capture());
        assertEquals(argumentCaptor.getValue().getCode(), AD_SERVICES_SETTINGS_USAGE_REPORTED);
        assertEquals(
                argumentCaptor.getValue().getRegion(),
                AD_SERVICES_SETTINGS_USAGE_REPORTED__REGION__ROW);
        assertEquals(
                argumentCaptor.getValue().getAction(),
                AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_OUT_SELECTED);
    }

    @Test
    public void testLogMsmtDebugKeyMatchStats() {
        final String enrollmentId = "EnrollmentId";
        long hashedValue = 5000L;
        long hashLimit = 10000L;
        MsmtDebugKeysMatchStats stats =
                MsmtDebugKeysMatchStats.builder()
                        .setAdTechEnrollmentId(enrollmentId)
                        .setMatched(true)
                        .setAttributionType(
                                AD_SERVICES_MEASUREMENT_DEBUG_KEYS__ATTRIBUTION_TYPE__APP_WEB)
                        .setDebugJoinKeyHashedValue(hashedValue)
                        .setDebugJoinKeyHashLimit(hashLimit)
                        .build();
        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logMeasurementDebugKeysMatch(stats);
        ArgumentCaptor<MsmtDebugKeysMatchStats> argumentCaptor =
                ArgumentCaptor.forClass(MsmtDebugKeysMatchStats.class);
        verify(mStatsdLoggerMock).logMeasurementDebugKeysMatch(argumentCaptor.capture());
        assertEquals(stats, argumentCaptor.getValue());
    }

    @Test
    public void testLogMeasurementAttributionStats() {
        MeasurementAttributionStats stats =
                new MeasurementAttributionStats.Builder()
                        .setCode(AD_SERVICES_MEASUREMENT_ATTRIBUTION)
                        .setSourceType(AttributionStatus.SourceType.EVENT.ordinal())
                        .setSurfaceType(AttributionStatus.AttributionSurface.APP_WEB.ordinal())
                        .setResult(AttributionStatus.AttributionResult.SUCCESS.ordinal())
                        .setFailureType(AttributionStatus.FailureType.UNKNOWN.ordinal())
                        .setSourceDerived(false)
                        .setInstallAttribution(true)
                        .setAttributionDelay(100L)
                        .build();
        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logMeasurementAttributionStats(stats);
        ArgumentCaptor<MeasurementAttributionStats> argumentCaptor =
                ArgumentCaptor.forClass(MeasurementAttributionStats.class);
        verify(mStatsdLoggerMock).logMeasurementAttributionStats(argumentCaptor.capture());
        assertEquals(argumentCaptor.getValue().getCode(), AD_SERVICES_MEASUREMENT_ATTRIBUTION);
        assertEquals(
                argumentCaptor.getValue().getSourceType(),
                AttributionStatus.SourceType.EVENT.ordinal());
        assertEquals(
                argumentCaptor.getValue().getSurfaceType(),
                AttributionStatus.AttributionSurface.APP_WEB.ordinal());
        assertEquals(
                argumentCaptor.getValue().getResult(),
                AttributionStatus.AttributionResult.SUCCESS.ordinal());
        assertEquals(
                argumentCaptor.getValue().getFailureType(),
                AttributionStatus.FailureType.UNKNOWN.ordinal());
        assertEquals(argumentCaptor.getValue().isSourceDerived(), false);
        assertEquals(argumentCaptor.getValue().isInstallAttribution(), true);
        assertEquals(argumentCaptor.getValue().getAttributionDelay(), 100L);
    }

    @Test
    public void testLogMeasurementWipeoutStats() {
        MeasurementWipeoutStats stats =
                new MeasurementWipeoutStats.Builder()
                        .setCode(AD_SERVICES_MEASUREMENT_WIPEOUT)
                        .setWipeoutType(WipeoutStatus.WipeoutType.CONSENT_FLIP.ordinal())
                        .build();

        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logMeasurementWipeoutStats(stats);
        ArgumentCaptor<MeasurementWipeoutStats> argumentCaptor =
                ArgumentCaptor.forClass(MeasurementWipeoutStats.class);
        verify(mStatsdLoggerMock).logMeasurementWipeoutStats(argumentCaptor.capture());
        assertEquals(argumentCaptor.getValue().getCode(), AD_SERVICES_MEASUREMENT_WIPEOUT);
        assertEquals(
                argumentCaptor.getValue().getWipeoutType(),
                WipeoutStatus.WipeoutType.CONSENT_FLIP.ordinal());
    }

    @Test
    public void testLogMeasurementDelayedSourceRegistrationStats() {
        int UnknownEnumValue = 0;
        long registrationDelay = 500L;
        MeasurementDelayedSourceRegistrationStats stats =
                new MeasurementDelayedSourceRegistrationStats.Builder()
                        .setCode(AD_SERVICES_MEASUREMENT_DELAYED_SOURCE_REGISTRATION)
                        .setRegistrationStatus(UnknownEnumValue)
                        .setRegistrationDelay(registrationDelay)
                        .build();

        AdServicesLoggerImpl adServicesLogger = new AdServicesLoggerImpl(mStatsdLoggerMock);
        adServicesLogger.logMeasurementDelayedSourceRegistrationStats(stats);
        ArgumentCaptor<MeasurementDelayedSourceRegistrationStats> argumentCaptor =
                ArgumentCaptor.forClass(MeasurementDelayedSourceRegistrationStats.class);
        verify(mStatsdLoggerMock)
                .logMeasurementDelayedSourceRegistrationStats(argumentCaptor.capture());
        assertEquals(
                argumentCaptor.getValue().getCode(),
                AD_SERVICES_MEASUREMENT_DELAYED_SOURCE_REGISTRATION);
        assertEquals(argumentCaptor.getValue().getRegistrationStatus(), UnknownEnumValue);
        assertEquals(argumentCaptor.getValue().getRegistrationDelay(), registrationDelay);
    }
}
