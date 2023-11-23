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

import static android.adservices.common.AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;

import android.adservices.adselection.AdWithBid;
import android.adservices.common.AdData;
import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdTechIdentifier;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.Uri;
import android.util.Pair;

import com.android.adservices.LoggerFactory;
import com.android.adservices.data.adselection.CustomAudienceSignals;
import com.android.adservices.data.common.DBAdData;
import com.android.adservices.data.common.DecisionLogic;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.DBCustomAudience;
import com.android.adservices.data.customaudience.DBTrustedBiddingData;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.httpclient.AdServicesHttpClientRequest;
import com.android.adservices.service.common.httpclient.AdServicesHttpsClient;
import com.android.adservices.service.devapi.CustomAudienceDevOverridesHelper;
import com.android.adservices.service.devapi.DevContext;
import com.android.adservices.service.js.IsolateSettings;
import com.android.adservices.service.profiling.Tracing;
import com.android.adservices.service.stats.AdServicesLoggerUtil;
import com.android.adservices.service.stats.RunAdBiddingPerCAExecutionLogger;
import com.android.internal.annotations.VisibleForTesting;

import com.google.common.collect.ImmutableMap;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.UncheckedTimeoutException;

import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * This class implements the ad bid generator. A new instance is assumed to be created for every
 * call
 */
public class AdBidGeneratorImpl implements AdBidGenerator {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();

    @VisibleForTesting
    static final String MISSING_TRUSTED_BIDDING_SIGNALS = "Error fetching trusted bidding signals";

    @VisibleForTesting
    static final String BIDDING_TIMED_OUT = "Bidding exceeded allowed time limit";

    @VisibleForTesting
    static final String TOO_HIGH_JS_VERSION =
            "Requested js version is %d while the returned version is %d";

    @VisibleForTesting
    static final String BIDDING_ENCOUNTERED_UNEXPECTED_ERROR =
            "Bidding failed for unexpected error";

    @NonNull private final Context mContext;
    @NonNull private final ListeningExecutorService mLightweightExecutorService;
    @NonNull private final ListeningExecutorService mBackgroundExecutorService;
    @NonNull private final ScheduledThreadPoolExecutor mScheduledExecutor;
    @NonNull private final AdSelectionScriptEngine mAdSelectionScriptEngine;
    @NonNull private final CustomAudienceDevOverridesHelper mCustomAudienceDevOverridesHelper;
    @NonNull private final AdCounterKeyCopier mAdCounterKeyCopier;
    @NonNull private final Flags mFlags;
    @NonNull private final JsFetcher mJsFetcher;

    public AdBidGeneratorImpl(
            @NonNull Context context,
            @NonNull AdServicesHttpsClient adServicesHttpsClient,
            @NonNull ListeningExecutorService lightweightExecutorService,
            @NonNull ListeningExecutorService backgroundExecutorService,
            @NonNull ScheduledThreadPoolExecutor scheduledExecutor,
            @NonNull DevContext devContext,
            @NonNull CustomAudienceDao customAudienceDao,
            @NonNull AdCounterKeyCopier adCounterKeyCopier,
            @NonNull Flags flags) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(adServicesHttpsClient);
        Objects.requireNonNull(lightweightExecutorService);
        Objects.requireNonNull(backgroundExecutorService);
        Objects.requireNonNull(scheduledExecutor);
        Objects.requireNonNull(devContext);
        Objects.requireNonNull(customAudienceDao);
        Objects.requireNonNull(adCounterKeyCopier);
        Objects.requireNonNull(flags);

        mContext = context;
        mLightweightExecutorService = lightweightExecutorService;
        mBackgroundExecutorService = backgroundExecutorService;
        mScheduledExecutor = scheduledExecutor;
        mCustomAudienceDevOverridesHelper =
                new CustomAudienceDevOverridesHelper(devContext, customAudienceDao);
        mAdCounterKeyCopier = adCounterKeyCopier;
        mFlags = flags;
        mAdSelectionScriptEngine =
                new AdSelectionScriptEngine(
                        mContext,
                        () -> mFlags.getEnforceIsolateMaxHeapSize(),
                        () -> mFlags.getIsolateMaxHeapSizeBytes(),
                        mAdCounterKeyCopier);
        mJsFetcher =
                new JsFetcher(
                        backgroundExecutorService,
                        lightweightExecutorService,
                        adServicesHttpsClient,
                        mFlags);
    }

    @VisibleForTesting
    AdBidGeneratorImpl(
            @NonNull Context context,
            @NonNull ListeningExecutorService lightWeightExecutorService,
            @NonNull ListeningExecutorService backgroundExecutorService,
            @NonNull ScheduledThreadPoolExecutor scheduledExecutor,
            @NonNull AdSelectionScriptEngine adSelectionScriptEngine,
            @NonNull CustomAudienceDevOverridesHelper customAudienceDevOverridesHelper,
            @NonNull AdCounterKeyCopier adCounterKeyCopier,
            @NonNull Flags flags,
            @NonNull IsolateSettings isolateSettings,
            @NonNull JsFetcher jsFetcher) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(lightWeightExecutorService);
        Objects.requireNonNull(backgroundExecutorService);
        Objects.requireNonNull(scheduledExecutor);
        Objects.requireNonNull(adSelectionScriptEngine);
        Objects.requireNonNull(customAudienceDevOverridesHelper);
        Objects.requireNonNull(adCounterKeyCopier);
        Objects.requireNonNull(flags);
        Objects.requireNonNull(isolateSettings);
        Objects.requireNonNull(jsFetcher);

        mContext = context;
        mLightweightExecutorService = lightWeightExecutorService;
        mBackgroundExecutorService = backgroundExecutorService;
        mScheduledExecutor = scheduledExecutor;
        mAdSelectionScriptEngine = adSelectionScriptEngine;
        mCustomAudienceDevOverridesHelper = customAudienceDevOverridesHelper;
        mAdCounterKeyCopier = adCounterKeyCopier;
        mFlags = flags;
        mJsFetcher = jsFetcher;
    }

    @Override
    @NonNull
    public FluentFuture<AdBiddingOutcome> runAdBiddingPerCA(
            @NonNull DBCustomAudience customAudience,
            @NonNull Map<Uri, JSONObject> trustedBiddingDataPerBaseUri,
            @NonNull AdSelectionSignals adSelectionSignals,
            @NonNull AdSelectionSignals buyerSignals,
            @NonNull AdSelectionSignals contextualSignals,
            @NonNull RunAdBiddingPerCAExecutionLogger runAdBiddingPerCAExecutionLogger) {
        Objects.requireNonNull(customAudience);
        Objects.requireNonNull(trustedBiddingDataPerBaseUri);
        Objects.requireNonNull(adSelectionSignals);
        Objects.requireNonNull(buyerSignals);
        Objects.requireNonNull(contextualSignals);

        // Start the runAdBiddingPerCA logger.
        runAdBiddingPerCAExecutionLogger.startRunAdBiddingPerCA(customAudience.getAds().size());

        sLogger.v("Running Ad Bidding for CA : %s", customAudience.getName());
        if (customAudience.getAds().isEmpty()) {
            sLogger.v("No Ads found for CA: %s, skipping", customAudience.getName());
            runAdBiddingPerCAExecutionLogger.close(STATUS_INTERNAL_ERROR);
            return FluentFuture.from(Futures.immediateFuture(null));
        }

        CustomAudienceSignals customAudienceSignals =
                CustomAudienceSignals.buildFromCustomAudience(customAudience);

        long versionRequested = mFlags.getFledgeAdSelectionBiddingLogicJsVersion();
        Map<Integer, Long> jsVersionMap =
                versionRequested >= JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3
                        ? ImmutableMap.of(
                                JsVersionHelper.JS_PAYLOAD_TYPE_BUYER_BIDDING_LOGIC_JS,
                                versionRequested)
                        : ImmutableMap.of();
        AdServicesHttpClientRequest biddingLogicUriHttpRequest =
                JsVersionHelper.getRequestWithVersionHeader(
                        customAudience.getBiddingLogicUri(),
                        jsVersionMap,
                        mFlags.getFledgeHttpJsCachingEnabled());

        FluentFuture<DecisionLogic> buyerDecisionLogic =
                mJsFetcher.getBuyerDecisionLogicWithLogger(
                        biddingLogicUriHttpRequest,
                        mCustomAudienceDevOverridesHelper,
                        customAudience.getOwner(),
                        customAudience.getBuyer(),
                        customAudience.getName(),
                        runAdBiddingPerCAExecutionLogger);

        FluentFuture<Pair<AdWithBid, String>> adWithBidPair =
                buyerDecisionLogic.transformAsync(
                        decisionLogic -> {
                            return runBidding(
                                    decisionLogic,
                                    versionRequested,
                                    customAudience,
                                    buyerSignals,
                                    contextualSignals,
                                    customAudienceSignals,
                                    adSelectionSignals,
                                    trustedBiddingDataPerBaseUri,
                                    runAdBiddingPerCAExecutionLogger);
                        },
                        mLightweightExecutorService);
        int traceCookie = Tracing.beginAsyncSection(Tracing.RUN_BIDDING_PER_CA);
        FluentFuture<AdBiddingOutcome> adBiddingOutcome =
                adWithBidPair
                        .transform(
                                candidate -> {
                                    if (Objects.isNull(candidate)
                                            || Objects.isNull(candidate.first)
                                            || candidate.first.getBid() <= 0.0) {
                                        sLogger.v(
                                                "Bidding for CA completed but result %s is"
                                                        + " filtered out",
                                                candidate);
                                        return null;
                                    }
                                    CustomAudienceBiddingInfo customAudienceInfo =
                                            CustomAudienceBiddingInfo.create(
                                                    customAudience, candidate.second);
                                    sLogger.v(
                                            "Creating Ad Bidding Outcome for CA: %s",
                                            customAudience.getName());
                                    AdBiddingOutcome result =
                                            AdBiddingOutcome.builder()
                                                    .setAdWithBid(candidate.first)
                                                    .setCustomAudienceBiddingInfo(
                                                            customAudienceInfo)
                                                    .build();
                                    sLogger.d(
                                            "Bidding for CA %s transformed",
                                            customAudience.getName());
                                    return result;
                                },
                                mLightweightExecutorService)
                        .withTimeout(
                                mFlags.getAdSelectionBiddingTimeoutPerCaMs(),
                                TimeUnit.MILLISECONDS,
                                mScheduledExecutor)
                        .transform(
                                result -> {
                                    runAdBiddingPerCAExecutionLogger.close(STATUS_SUCCESS);
                                    Tracing.endAsyncSection(
                                            Tracing.RUN_BIDDING_PER_CA, traceCookie);
                                    return result;
                                },
                                mLightweightExecutorService)
                        .catching(
                                JSONException.class,
                                this::handleBiddingError,
                                mLightweightExecutorService)
                        .catching(
                                TimeoutException.class,
                                this::handleTimeoutError,
                                mLightweightExecutorService)
                        .catching(
                                RuntimeException.class,
                                e -> {
                                    runAdBiddingPerCAExecutionLogger.close(
                                            AdServicesLoggerUtil.getResultCodeFromException(e));
                                    Tracing.endAsyncSection(
                                            Tracing.RUN_BIDDING_PER_CA, traceCookie);
                                    throw e;
                                },
                                mLightweightExecutorService);
        return adBiddingOutcome;
    }

    @Nullable
    private AdBiddingOutcome handleTimeoutError(TimeoutException e) {
        sLogger.e(e, "Bid Generation exceeded time limit");
        // Despite this exception will be flattened, after doing `successfulAsList` on bids, keeping
        // it consistent with Scoring and overall Ad Selection timeouts
        throw new UncheckedTimeoutException(BIDDING_TIMED_OUT);
    }

    @Nullable
    private AdBiddingOutcome handleBiddingError(JSONException e) {
        sLogger.e(e, "Failed to generate bids for the ads in this custom audience.");
        IllegalArgumentException exception = new IllegalArgumentException(e);
        throw exception;
    }

    private FluentFuture<AdSelectionSignals> getTrustedBiddingSignals(
            @NonNull DBTrustedBiddingData trustedBiddingData,
            @NonNull Map<Uri, JSONObject> trustedBiddingDataByBaseUri,
            @NonNull String owner,
            @NonNull AdTechIdentifier buyer,
            @NonNull String name,
            @NonNull RunAdBiddingPerCAExecutionLogger adBiddingPerCAExecutionLogger) {
        Objects.requireNonNull(trustedBiddingData);
        int traceCookie = Tracing.beginAsyncSection(Tracing.GET_TRUSTED_BIDDING_SIGNALS);
        final Uri trustedBiddingUri = trustedBiddingData.getUri();
        final List<String> trustedBiddingKeys = trustedBiddingData.getKeys();
        // Set the start of the get-trusted-bidding-signals process in the logger.
        adBiddingPerCAExecutionLogger.startGetTrustedBiddingSignals(trustedBiddingKeys.size());
        FluentFuture<AdSelectionSignals> trustedSignalsOverride =
                FluentFuture.from(
                        mBackgroundExecutorService.submit(
                                () ->
                                        mCustomAudienceDevOverridesHelper
                                                .getTrustedBiddingSignalsOverride(
                                                        owner, buyer, name)));
        return trustedSignalsOverride
                .transformAsync(
                        jsOverride -> {
                            if (jsOverride == null) {
                                sLogger.v("Fetching trusted bidding Signals from server");
                                return Futures.immediateFuture(
                                        TrustedBiddingDataFetcher.extractKeys(
                                                trustedBiddingDataByBaseUri.get(trustedBiddingUri),
                                                trustedBiddingKeys));
                            } else {
                                sLogger.d(
                                        "Developer options enabled and override trusted signals"
                                                + " are provided for the current Custom Audience."
                                                + " Skipping call to server.");
                                return Futures.immediateFuture(jsOverride);
                            }
                        },
                        mLightweightExecutorService)
                .transform(
                        trustedBiddingSignals -> {
                            // TODO(b/260011586): Optimize the logging of trustedBiddingSignals.
                            adBiddingPerCAExecutionLogger.endGetTrustedBiddingSignals(
                                    trustedBiddingSignals);
                            Tracing.endAsyncSection(
                                    Tracing.GET_TRUSTED_BIDDING_SIGNALS, traceCookie);
                            return trustedBiddingSignals;
                        },
                        mLightweightExecutorService)
                .catching(
                        Exception.class,
                        e -> {
                            Tracing.endAsyncSection(
                                    Tracing.GET_TRUSTED_BIDDING_SIGNALS, traceCookie);
                            sLogger.w(e, "Exception encountered when fetching trusted signals");
                            throw new IllegalStateException(MISSING_TRUSTED_BIDDING_SIGNALS);
                        },
                        mLightweightExecutorService);
    }

    /** @return the {@link AdWithBid} with the best bid per CustomAudience. */
    @NonNull
    @VisibleForTesting
    FluentFuture<Pair<AdWithBid, String>> runBidding(
            @NonNull DecisionLogic buyerDecisionLogicJs,
            long versionRequested,
            @NonNull DBCustomAudience customAudience,
            @NonNull AdSelectionSignals buyerSignals,
            @NonNull AdSelectionSignals contextualSignals,
            @NonNull CustomAudienceSignals customAudienceSignals,
            @NonNull AdSelectionSignals adSelectionSignals,
            @NonNull Map<Uri, JSONObject> trustedBiddingDataByBaseUri,
            @NonNull RunAdBiddingPerCAExecutionLogger runAdBiddingPerCAExecutionLogger) {
        runAdBiddingPerCAExecutionLogger.startRunBidding();
        FluentFuture<AdSelectionSignals> trustedBiddingSignals =
                getTrustedBiddingSignals(
                        customAudience.getTrustedBiddingData(),
                        trustedBiddingDataByBaseUri,
                        customAudience.getOwner(),
                        customAudience.getBuyer(),
                        customAudience.getName(),
                        runAdBiddingPerCAExecutionLogger);
        int traceCookie = Tracing.beginAsyncSection(Tracing.RUN_BIDDING);

        FluentFuture<List<AdWithBid>> adsWithBids;
        long buyerDecisionLogicJsVersion =
                buyerDecisionLogicJs.getVersion(
                        JsVersionHelper.JS_PAYLOAD_TYPE_BUYER_BIDDING_LOGIC_JS);
        sLogger.v("Got buyer bidding logic version %d", buyerDecisionLogicJsVersion);
        if (buyerDecisionLogicJsVersion > versionRequested) {
            throw new IllegalStateException(
                    String.format(
                            TOO_HIGH_JS_VERSION, versionRequested, buyerDecisionLogicJsVersion));
        }
        if (JsVersionRegister.BUYER_BIDDING_LOGIC_VERSION_VERSION_3
                == buyerDecisionLogicJsVersion) {
            adsWithBids =
                    trustedBiddingSignals.transformAsync(
                            biddingSignals ->
                                    mAdSelectionScriptEngine.generateBidsV3(
                                            buyerDecisionLogicJs.getPayload(),
                                            customAudience,
                                            adSelectionSignals,
                                            buyerSignals,
                                            biddingSignals,
                                            contextualSignals,
                                            runAdBiddingPerCAExecutionLogger),
                            mLightweightExecutorService);
        } else {
            // We are currently graceful for missing or malformed js version, and fall back to run
            // the old generate bid.
            // TODO(b/231265311): update AdSelectionScriptEngine AdData class objects with DBAdData
            //  classes and remove this conversion.
            List<AdData> ads = new ArrayList<>(customAudience.getAds().size());
            for (DBAdData adData : customAudience.getAds()) {
                AdData.Builder adDataWithoutAdCounterKeysBuilder =
                        new AdData.Builder()
                                .setRenderUri(adData.getRenderUri())
                                .setMetadata(adData.getMetadata())
                                .setAdCounterKeys(adData.getAdCounterKeys());
                ads.add(
                        mAdCounterKeyCopier
                                .copyAdCounterKeys(adDataWithoutAdCounterKeysBuilder, adData)
                                .build());
            }
            adsWithBids =
                    trustedBiddingSignals.transformAsync(
                            biddingSignals ->
                                    mAdSelectionScriptEngine.generateBids(
                                            buyerDecisionLogicJs.getPayload(),
                                            ads,
                                            adSelectionSignals,
                                            buyerSignals,
                                            biddingSignals,
                                            contextualSignals,
                                            customAudienceSignals,
                                            runAdBiddingPerCAExecutionLogger),
                            mLightweightExecutorService);
        }
        return adsWithBids
                .transform(
                        adWithBids -> {
                            return new Pair<>(
                                    getBestAdWithBidPerCA(adWithBids),
                                    buyerDecisionLogicJs.getPayload());
                        },
                        mLightweightExecutorService)
                .transform(
                        result -> {
                            runAdBiddingPerCAExecutionLogger.endRunBidding();
                            Tracing.endAsyncSection(Tracing.RUN_BIDDING, traceCookie);
                            return result;
                        },
                        mLightweightExecutorService);
    }

    @Nullable
    private AdWithBid getBestAdWithBidPerCA(@NonNull List<AdWithBid> adWithBids) {
        if (adWithBids.size() == 0) {
            sLogger.v("No ad with bids for current CA");
            return null;
        }
        AdWithBid maxBidCandidate =
                adWithBids.stream().max(Comparator.comparingDouble(AdWithBid::getBid)).get();
        sLogger.v("Obtained #%d ads with bids for current CA", adWithBids.size());
        if (maxBidCandidate.getBid() <= 0.0) {
            sLogger.v("No positive bids found, no valid bids to return");
            return null;
        }
        sLogger.v("Returning ad candidate with highest bid: %s", maxBidCandidate);
        return maxBidCandidate;
    }
}
