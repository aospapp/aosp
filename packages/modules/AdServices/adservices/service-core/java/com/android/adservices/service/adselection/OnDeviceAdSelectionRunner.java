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

package com.android.adservices.service.adselection;

import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;

import android.adservices.adselection.AdSelectionConfig;
import android.adservices.common.AdTechIdentifier;
import android.adservices.exceptions.AdServicesException;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;
import android.content.Context;
import android.os.Build;
import android.util.Pair;

import androidx.annotation.RequiresApi;

import com.android.adservices.LoggerFactory;
import com.android.adservices.data.adselection.AdSelectionEntryDao;
import com.android.adservices.data.adselection.DBAdSelection;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.DBCustomAudience;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.AdSelectionServiceFilter;
import com.android.adservices.service.common.httpclient.AdServicesHttpsClient;
import com.android.adservices.service.devapi.CustomAudienceDevOverridesHelper;
import com.android.adservices.service.devapi.DevContext;
import com.android.adservices.service.stats.AdSelectionExecutionLogger;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerUtil;
import com.android.internal.annotations.VisibleForTesting;

import com.google.common.base.Function;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.stream.Collectors;

/** Orchestrate on-device ad selection. */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class OnDeviceAdSelectionRunner extends AdSelectionRunner {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    @NonNull protected final AdsScoreGenerator mAdsScoreGenerator;
    @NonNull protected final AdServicesHttpsClient mAdServicesHttpsClient;
    @NonNull protected final PerBuyerBiddingRunner mPerBuyerBiddingRunner;
    @NonNull protected final AdFilterer mAdFilterer;
    @NonNull protected final AdCounterKeyCopier mAdCounterKeyCopier;

    public OnDeviceAdSelectionRunner(
            @NonNull final Context context,
            @NonNull final CustomAudienceDao customAudienceDao,
            @NonNull final AdSelectionEntryDao adSelectionEntryDao,
            @NonNull final AdServicesHttpsClient adServicesHttpsClient,
            @NonNull final ExecutorService lightweightExecutorService,
            @NonNull final ExecutorService backgroundExecutorService,
            @NonNull final ScheduledThreadPoolExecutor scheduledExecutor,
            @NonNull final AdServicesLogger adServicesLogger,
            @NonNull final DevContext devContext,
            @NonNull final Flags flags,
            @NonNull final AdSelectionExecutionLogger adSelectionExecutionLogger,
            @NonNull final AdSelectionServiceFilter adSelectionServiceFilter,
            @NonNull final AdFilterer adFilterer,
            @NonNull final AdCounterKeyCopier adCounterKeyCopier,
            final int callerUid) {
        super(
                context,
                customAudienceDao,
                adSelectionEntryDao,
                lightweightExecutorService,
                backgroundExecutorService,
                scheduledExecutor,
                adServicesLogger,
                flags,
                adSelectionExecutionLogger,
                adSelectionServiceFilter,
                adFilterer,
                callerUid);

        Objects.requireNonNull(adServicesHttpsClient);
        Objects.requireNonNull(adFilterer);
        Objects.requireNonNull(adCounterKeyCopier);

        mAdServicesHttpsClient = adServicesHttpsClient;
        mAdFilterer = adFilterer;
        mAdCounterKeyCopier = adCounterKeyCopier;
        mAdsScoreGenerator =
                new AdsScoreGeneratorImpl(
                        new AdSelectionScriptEngine(
                                context,
                                () -> flags.getEnforceIsolateMaxHeapSize(),
                                () -> flags.getIsolateMaxHeapSizeBytes(),
                                mAdCounterKeyCopier),
                        mLightweightExecutorService,
                        mBackgroundExecutorService,
                        mScheduledExecutor,
                        mAdServicesHttpsClient,
                        devContext,
                        mAdSelectionEntryDao,
                        flags,
                        adSelectionExecutionLogger);
        mPerBuyerBiddingRunner =
                new PerBuyerBiddingRunner(
                        new AdBidGeneratorImpl(
                                context,
                                mAdServicesHttpsClient,
                                mLightweightExecutorService,
                                mBackgroundExecutorService,
                                mScheduledExecutor,
                                devContext,
                                mCustomAudienceDao,
                                mAdCounterKeyCopier,
                                flags),
                        new TrustedBiddingDataFetcher(
                                adServicesHttpsClient,
                                devContext,
                                new CustomAudienceDevOverridesHelper(devContext, customAudienceDao),
                                lightweightExecutorService),
                        mScheduledExecutor,
                        mBackgroundExecutorService,
                        flags);
    }

    @VisibleForTesting
    OnDeviceAdSelectionRunner(
            @NonNull final Context context,
            @NonNull final CustomAudienceDao customAudienceDao,
            @NonNull final AdSelectionEntryDao adSelectionEntryDao,
            @NonNull final AdServicesHttpsClient adServicesHttpsClient,
            @NonNull final ExecutorService lightweightExecutorService,
            @NonNull final ExecutorService backgroundExecutorService,
            @NonNull final ScheduledThreadPoolExecutor scheduledExecutor,
            @NonNull final AdsScoreGenerator adsScoreGenerator,
            @NonNull final AdSelectionIdGenerator adSelectionIdGenerator,
            @NonNull Clock clock,
            @NonNull final AdServicesLogger adServicesLogger,
            @NonNull final Flags flags,
            int callerUid,
            @NonNull final AdSelectionServiceFilter adSelectionServiceFilter,
            @NonNull final AdSelectionExecutionLogger adSelectionExecutionLogger,
            @NonNull final PerBuyerBiddingRunner perBuyerBiddingRunner,
            @NonNull final AdFilterer adFilterer,
            @NonNull final AdCounterKeyCopier adCounterKeyCopier) {
        super(
                context,
                customAudienceDao,
                adSelectionEntryDao,
                lightweightExecutorService,
                backgroundExecutorService,
                scheduledExecutor,
                adSelectionIdGenerator,
                clock,
                adServicesLogger,
                flags,
                callerUid,
                adSelectionServiceFilter,
                adFilterer,
                adSelectionExecutionLogger);

        Objects.requireNonNull(adsScoreGenerator);
        Objects.requireNonNull(adServicesHttpsClient);
        Objects.requireNonNull(adFilterer);
        Objects.requireNonNull(adCounterKeyCopier);

        mAdsScoreGenerator = adsScoreGenerator;
        mAdServicesHttpsClient = adServicesHttpsClient;
        mPerBuyerBiddingRunner = perBuyerBiddingRunner;
        mAdFilterer = adFilterer;
        mAdCounterKeyCopier = adCounterKeyCopier;
    }

    /**
     * Orchestrate on device ad selection.
     *
     * @param adSelectionConfig Set of data from Sellers and Buyers needed for Ad Auction and
     *     Selection
     */
    public ListenableFuture<AdSelectionOrchestrationResult> orchestrateAdSelection(
            @NonNull final AdSelectionConfig adSelectionConfig,
            @NonNull final String callerPackageName,
            ListenableFuture<List<DBCustomAudience>> buyerCustomAudience) {

        ListenableFuture<List<DBCustomAudience>> filteredCas =
                FluentFuture.from(buyerCustomAudience)
                        .transform(mAdFilterer::filterCustomAudiences, mLightweightExecutorService);

        AsyncFunction<List<DBCustomAudience>, List<AdBiddingOutcome>> bidAds =
                buyerCAs -> runAdBidding(buyerCAs, adSelectionConfig);

        ListenableFuture<List<AdBiddingOutcome>> biddingOutcome =
                Futures.transformAsync(filteredCas, bidAds, mLightweightExecutorService);

        AsyncFunction<List<AdBiddingOutcome>, List<AdScoringOutcome>> mapBidsToScores =
                bids -> runAdScoring(bids, adSelectionConfig);

        ListenableFuture<List<AdScoringOutcome>> scoredAds =
                Futures.transformAsync(
                        biddingOutcome, mapBidsToScores, mLightweightExecutorService);

        Function<List<AdScoringOutcome>, AdScoringOutcome> reduceScoresToWinner =
                scores -> getWinningOutcome(scores);

        ListenableFuture<AdScoringOutcome> winningOutcome =
                Futures.transform(scoredAds, reduceScoresToWinner, mLightweightExecutorService);

        AsyncFunction<AdScoringOutcome, AdSelectionOrchestrationResult> mapWinnerToDBResult =
                scoringWinner -> createAdSelectionResult(scoringWinner);

        ListenableFuture<AdSelectionOrchestrationResult> dbAdSelectionBuilder =
                Futures.transformAsync(
                        winningOutcome, mapWinnerToDBResult, mLightweightExecutorService);

        // Clean up after the future is complete, out of critical path
        dbAdSelectionBuilder.addListener(() -> cleanUpCache(), mLightweightExecutorService);

        return dbAdSelectionBuilder;
    }

    private ListenableFuture<List<AdBiddingOutcome>> runAdBidding(
            @NonNull final List<DBCustomAudience> customAudiences,
            @NonNull final AdSelectionConfig adSelectionConfig) {
        try {
            if (customAudiences.isEmpty()) {
                sLogger.w("Need not invoke bidding on empty list of CAs");
                // Return empty list of bids
                return Futures.immediateFuture(Collections.EMPTY_LIST);
            }
            mAdSelectionExecutionLogger.startRunAdBidding(customAudiences);
            Map<AdTechIdentifier, List<DBCustomAudience>> buyerToCustomAudienceMap =
                    mapBuyerToCustomAudience(customAudiences);

            sLogger.v("Invoking bidding for #%d buyers", buyerToCustomAudienceMap.size());
            long perBuyerBiddingTimeoutMs = mFlags.getAdSelectionBiddingTimeoutPerBuyerMs();
            return FluentFuture.from(
                            Futures.successfulAsList(
                                    buyerToCustomAudienceMap.entrySet().parallelStream()
                                            .map(
                                                    entry -> {
                                                        return mPerBuyerBiddingRunner.runBidding(
                                                                entry.getKey(),
                                                                entry.getValue(),
                                                                perBuyerBiddingTimeoutMs,
                                                                adSelectionConfig);
                                                    })
                                            .flatMap(List::stream)
                                            .collect(Collectors.toList())))
                    .transform(this::endSuccessfulBidding, mLightweightExecutorService)
                    .catching(
                            RuntimeException.class,
                            this::endFailedBiddingWithRuntimeException,
                            mLightweightExecutorService);
        } catch (Exception e) {
            mAdSelectionExecutionLogger.endBiddingProcess(
                    null, AdServicesLoggerUtil.getResultCodeFromException(e));
            throw e;
        }
    }

    @NonNull
    private List<AdBiddingOutcome> endSuccessfulBidding(@NonNull List<AdBiddingOutcome> result) {
        Objects.requireNonNull(result);
        mAdSelectionExecutionLogger.endBiddingProcess(result, STATUS_SUCCESS);
        return result;
    }

    private void endSilentFailedBidding(RuntimeException e) {
        mAdSelectionExecutionLogger.endBiddingProcess(
                null, AdServicesLoggerUtil.getResultCodeFromException(e));
    }

    @Nullable
    private List<AdBiddingOutcome> endFailedBiddingWithRuntimeException(RuntimeException e) {
        mAdSelectionExecutionLogger.endBiddingProcess(
                null, AdServicesLoggerUtil.getResultCodeFromException(e));
        throw e;
    }

    @SuppressLint("DefaultLocale")
    private ListenableFuture<List<AdScoringOutcome>> runAdScoring(
            @NonNull final List<AdBiddingOutcome> adBiddingOutcomes,
            @NonNull final AdSelectionConfig adSelectionConfig)
            throws AdServicesException {
        sLogger.v("Got %d total bidding outcomes", adBiddingOutcomes.size());
        List<AdBiddingOutcome> validBiddingOutcomes =
                adBiddingOutcomes.stream().filter(Objects::nonNull).collect(Collectors.toList());
        sLogger.v("Got %d valid bidding outcomes", validBiddingOutcomes.size());

        if (validBiddingOutcomes.isEmpty() && adSelectionConfig.getBuyerContextualAds().isEmpty()) {
            sLogger.w("Received empty list of successful bidding outcomes and contextual ads");
            throw new IllegalStateException(ERROR_NO_VALID_BIDS_OR_CONTEXTUAL_ADS_FOR_SCORING);
        }
        return mAdsScoreGenerator.runAdScoring(validBiddingOutcomes, adSelectionConfig);
    }

    private AdScoringOutcome getWinningOutcome(
            @NonNull List<AdScoringOutcome> overallAdScoringOutcome) {
        sLogger.v("Scoring completed, generating winning outcome");
        return overallAdScoringOutcome.stream()
                .filter(a -> a.getAdWithScore().getScore() > 0)
                .max(
                        (a, b) ->
                                Double.compare(
                                        a.getAdWithScore().getScore(),
                                        b.getAdWithScore().getScore()))
                .orElseThrow(() -> new IllegalStateException(ERROR_NO_WINNING_AD_FOUND));
    }

    /**
     * This method populates an Ad Selection result ready to be persisted in DB, with all the fields
     * except adSelectionId and creation time, which should be created as close as possible to
     * persistence logic
     *
     * @param scoringWinner Winning Ad for overall Ad Selection
     * @return A {@link Pair} with a Builder for {@link DBAdSelection} populated with necessary data
     *     and a string containing the JS with the decision logic from this buyer.
     */
    @VisibleForTesting
    ListenableFuture<AdSelectionOrchestrationResult> createAdSelectionResult(
            @NonNull AdScoringOutcome scoringWinner) {
        DBAdSelection.Builder dbAdSelectionBuilder = new DBAdSelection.Builder();
        sLogger.v("Creating Ad Selection result from scoring winner");
        dbAdSelectionBuilder
                .setWinningAdBid(scoringWinner.getAdWithScore().getAdWithBid().getBid())
                .setCustomAudienceSignals(scoringWinner.getCustomAudienceSignals())
                .setWinningAdRenderUri(
                        scoringWinner.getAdWithScore().getAdWithBid().getAdData().getRenderUri())
                .setBiddingLogicUri(scoringWinner.getBiddingLogicUri())
                .setContextualSignals("{}");
        // TODO(b/230569187): get the contextualSignal securely = "invoking app name"

        final DBAdSelection.Builder copiedDBAdSelectionBuilder =
                mAdCounterKeyCopier.copyAdCounterKeys(dbAdSelectionBuilder, scoringWinner);

        return getWinnerBiddingLogicJs(scoringWinner)
                .transform(
                        biddingLogicJs ->
                                new AdSelectionOrchestrationResult(
                                        copiedDBAdSelectionBuilder, biddingLogicJs),
                        mLightweightExecutorService);
    }

    @VisibleForTesting
    FluentFuture<String> getWinnerBiddingLogicJs(AdScoringOutcome scoringOutcome) {
        String biddingLogicJs =
                (scoringOutcome.isBiddingLogicJsDownloaded())
                        ? scoringOutcome.getBiddingLogicJs()
                        : "";
        return FluentFuture.from(Futures.immediateFuture(biddingLogicJs));
    }

    private Map<AdTechIdentifier, List<DBCustomAudience>> mapBuyerToCustomAudience(
            final List<DBCustomAudience> customAudienceList) {
        Map<AdTechIdentifier, List<DBCustomAudience>> buyerToCustomAudienceMap = new HashMap<>();

        for (DBCustomAudience customAudience : customAudienceList) {
            buyerToCustomAudienceMap
                    .computeIfAbsent(customAudience.getBuyer(), k -> new ArrayList<>())
                    .add(customAudience);
        }
        sLogger.v("Created mapping for #%d buyers", buyerToCustomAudienceMap.size());
        return buyerToCustomAudienceMap;
    }

    /**
     * Given we no longer need to fetch data from web for this run of Ad Selection, we attempt to
     * clean up cache.
     */
    private void cleanUpCache() {
        mAdServicesHttpsClient.getAssociatedCache().cleanUp();
    }
}
