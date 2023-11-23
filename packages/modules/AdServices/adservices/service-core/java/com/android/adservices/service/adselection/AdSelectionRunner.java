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

import static com.android.adservices.service.stats.AdServicesLoggerUtil.getResultCodeFromException;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS;

import android.adservices.adselection.AdSelectionCallback;
import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.AdSelectionInput;
import android.adservices.adselection.AdSelectionResponse;
import android.adservices.adselection.ContextualAds;
import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.FledgeErrorResponse;
import android.adservices.exceptions.AdServicesException;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.os.Trace;

import androidx.annotation.RequiresApi;

import com.android.adservices.LoggerFactory;
import com.android.adservices.data.adselection.AdSelectionEntryDao;
import com.android.adservices.data.adselection.DBAdSelection;
import com.android.adservices.data.adselection.DBBuyerDecisionLogic;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.DBCustomAudience;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.AdSelectionServiceFilter;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.exception.FilterException;
import com.android.adservices.service.js.JSScriptEngine;
import com.android.adservices.service.profiling.Tracing;
import com.android.adservices.service.stats.AdSelectionExecutionLogger;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerUtil;
import com.android.adservices.service.stats.AdServicesStatsLog;
import com.android.internal.annotations.VisibleForTesting;

import com.google.common.base.Preconditions;
import com.google.common.util.concurrent.AsyncFunction;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;
import com.google.common.util.concurrent.UncheckedTimeoutException;

import java.time.Clock;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Orchestrator that runs the Ads Auction/Bidding and Scoring logic The class expects the caller to
 * create a concrete object instance of the class. The instances are mutually exclusive and do not
 * share any values across shared class instance.
 *
 * <p>Class takes in an executor on which it runs the AdSelection logic
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public abstract class AdSelectionRunner {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();

    @VisibleForTesting static final String AD_SELECTION_ERROR_PATTERN = "%s: %s";

    @VisibleForTesting
    static final String ERROR_AD_SELECTION_FAILURE = "Encountered failure during Ad Selection";

    @VisibleForTesting static final String ERROR_NO_WINNING_AD_FOUND = "No winning Ads found";

    @VisibleForTesting
    static final String ERROR_NO_VALID_BIDS_OR_CONTEXTUAL_ADS_FOR_SCORING =
            "No valid bids or contextual ads available for scoring";

    @VisibleForTesting
    static final String ERROR_NO_CA_AND_CONTEXTUAL_ADS_AVAILABLE =
            "No Custom Audience or contextual ads available";

    @VisibleForTesting
    static final String ERROR_NO_BUYERS_OR_CONTEXTUAL_ADS_AVAILABLE =
            "The list of the custom audience buyers and contextual ads both should not be empty.";

    @VisibleForTesting
    static final String AD_SELECTION_TIMED_OUT = "Ad selection exceeded allowed time limit";

    @VisibleForTesting
    static final String JS_SANDBOX_IS_NOT_AVAILABLE =
            String.format(
                    AD_SELECTION_ERROR_PATTERN,
                    ERROR_AD_SELECTION_FAILURE,
                    "JS Sandbox is not available");

    @NonNull protected final CustomAudienceDao mCustomAudienceDao;
    @NonNull protected final AdSelectionEntryDao mAdSelectionEntryDao;
    @NonNull protected final ListeningExecutorService mLightweightExecutorService;
    @NonNull protected final ListeningExecutorService mBackgroundExecutorService;
    @NonNull protected final ScheduledThreadPoolExecutor mScheduledExecutor;
    @NonNull protected final AdSelectionIdGenerator mAdSelectionIdGenerator;
    @NonNull protected final Clock mClock;
    @NonNull protected final AdServicesLogger mAdServicesLogger;
    @NonNull protected final Flags mFlags;
    @NonNull protected final AdSelectionExecutionLogger mAdSelectionExecutionLogger;
    @NonNull private final AdSelectionServiceFilter mAdSelectionServiceFilter;
    @NonNull private final AdFilterer mAdFilterer;
    private final int mCallerUid;
    @NonNull private final PrebuiltLogicGenerator mPrebuiltLogicGenerator;

    /**
     * @param context service context
     * @param customAudienceDao DAO to access custom audience storage
     * @param adSelectionEntryDao DAO to access ad selection storage
     * @param lightweightExecutorService executor for running short tasks
     * @param backgroundExecutorService executor for longer running tasks (ex. network calls)
     * @param scheduledExecutor executor for tasks to be run with a delay or timed executions
     * @param adServicesLogger logger for logging calls to PPAPI
     * @param flags for accessing feature flags
     * @param adSelectionServiceFilter for validating the request
     */
    public AdSelectionRunner(
            @NonNull final Context context,
            @NonNull final CustomAudienceDao customAudienceDao,
            @NonNull final AdSelectionEntryDao adSelectionEntryDao,
            @NonNull final ExecutorService lightweightExecutorService,
            @NonNull final ExecutorService backgroundExecutorService,
            @NonNull final ScheduledThreadPoolExecutor scheduledExecutor,
            @NonNull final AdServicesLogger adServicesLogger,
            @NonNull final Flags flags,
            @NonNull final AdSelectionExecutionLogger adSelectionExecutionLogger,
            @NonNull final AdSelectionServiceFilter adSelectionServiceFilter,
            @NonNull final AdFilterer adFilterer,
            @NonNull final int callerUid) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(customAudienceDao);
        Objects.requireNonNull(adSelectionEntryDao);
        Objects.requireNonNull(lightweightExecutorService);
        Objects.requireNonNull(backgroundExecutorService);
        Objects.requireNonNull(adServicesLogger);
        Objects.requireNonNull(flags);
        Objects.requireNonNull(adSelectionServiceFilter);
        Objects.requireNonNull(adFilterer);

        Preconditions.checkArgument(
                JSScriptEngine.AvailabilityChecker.isJSSandboxAvailable(),
                JS_SANDBOX_IS_NOT_AVAILABLE);
        Objects.requireNonNull(adSelectionExecutionLogger);

        mCustomAudienceDao = customAudienceDao;
        mAdSelectionEntryDao = adSelectionEntryDao;
        mLightweightExecutorService = MoreExecutors.listeningDecorator(lightweightExecutorService);
        mBackgroundExecutorService = MoreExecutors.listeningDecorator(backgroundExecutorService);
        mScheduledExecutor = scheduledExecutor;
        mAdServicesLogger = adServicesLogger;
        mAdSelectionIdGenerator = new AdSelectionIdGenerator();
        mClock = Clock.systemUTC();
        mFlags = flags;
        mAdSelectionExecutionLogger = adSelectionExecutionLogger;
        mAdSelectionServiceFilter = adSelectionServiceFilter;
        mAdFilterer = adFilterer;
        mCallerUid = callerUid;
        mPrebuiltLogicGenerator = new PrebuiltLogicGenerator(mFlags);
    }

    @VisibleForTesting
    AdSelectionRunner(
            @NonNull final Context context,
            @NonNull final CustomAudienceDao customAudienceDao,
            @NonNull final AdSelectionEntryDao adSelectionEntryDao,
            @NonNull final ExecutorService lightweightExecutorService,
            @NonNull final ExecutorService backgroundExecutorService,
            @NonNull final ScheduledThreadPoolExecutor scheduledExecutor,
            @NonNull final AdSelectionIdGenerator adSelectionIdGenerator,
            @NonNull Clock clock,
            @NonNull final AdServicesLogger adServicesLogger,
            @NonNull final Flags flags,
            int callerUid,
            @NonNull AdSelectionServiceFilter adSelectionServiceFilter,
            @NonNull AdFilterer adFilterer,
            @NonNull final AdSelectionExecutionLogger adSelectionExecutionLogger) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(customAudienceDao);
        Objects.requireNonNull(adSelectionEntryDao);
        Objects.requireNonNull(lightweightExecutorService);
        Objects.requireNonNull(backgroundExecutorService);
        Objects.requireNonNull(scheduledExecutor);
        Objects.requireNonNull(adSelectionIdGenerator);
        Objects.requireNonNull(clock);
        Objects.requireNonNull(adServicesLogger);
        Objects.requireNonNull(flags);
        Objects.requireNonNull(adSelectionExecutionLogger);
        Objects.requireNonNull(adFilterer);

        Preconditions.checkArgument(
                JSScriptEngine.AvailabilityChecker.isJSSandboxAvailable(),
                JS_SANDBOX_IS_NOT_AVAILABLE);

        mCustomAudienceDao = customAudienceDao;
        mAdSelectionEntryDao = adSelectionEntryDao;
        mLightweightExecutorService = MoreExecutors.listeningDecorator(lightweightExecutorService);
        mBackgroundExecutorService = MoreExecutors.listeningDecorator(backgroundExecutorService);
        mScheduledExecutor = scheduledExecutor;
        mAdSelectionIdGenerator = adSelectionIdGenerator;
        mClock = clock;
        mAdServicesLogger = adServicesLogger;
        mFlags = flags;
        mAdSelectionExecutionLogger = adSelectionExecutionLogger;
        mAdSelectionServiceFilter = adSelectionServiceFilter;
        mAdFilterer = adFilterer;
        mCallerUid = callerUid;
        mPrebuiltLogicGenerator = new PrebuiltLogicGenerator(mFlags);
    }

    /**
     * Runs the ad selection for a given seller
     *
     * @param inputParams containing {@link AdSelectionConfig} and {@code callerPackageName}
     * @param callback used to notify the result back to the calling seller
     */
    public void runAdSelection(
            @NonNull AdSelectionInput inputParams, @NonNull AdSelectionCallback callback) {
        final int traceCookie = Tracing.beginAsyncSection(Tracing.RUN_AD_SELECTION);
        Objects.requireNonNull(inputParams);
        Objects.requireNonNull(callback);
        AdSelectionConfig adSelectionConfig = inputParams.getAdSelectionConfig();

        try {
            ListenableFuture<Void> filterAndValidateRequestFuture =
                    Futures.submit(
                            () -> {
                                try {
                                    Trace.beginSection(Tracing.VALIDATE_REQUEST);
                                    sLogger.v("Starting filtering and validation.");
                                    mAdSelectionServiceFilter.filterRequest(
                                            adSelectionConfig.getSeller(),
                                            inputParams.getCallerPackageName(),
                                            mFlags
                                                    .getEnforceForegroundStatusForFledgeRunAdSelection(),
                                            true,
                                            mCallerUid,
                                            AdServicesStatsLog
                                                    .AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS,
                                            Throttler.ApiKey.FLEDGE_API_SELECT_ADS);
                                    validateAdSelectionConfig(adSelectionConfig);
                                } finally {
                                    sLogger.v("Completed filtering and validation.");
                                    Trace.endSection();
                                }
                            },
                            mLightweightExecutorService);

            ListenableFuture<DBAdSelection> dbAdSelectionFuture =
                    FluentFuture.from(filterAndValidateRequestFuture)
                            .transformAsync(
                                    ignoredVoid ->
                                            orchestrateAdSelection(
                                                    inputParams.getAdSelectionConfig(),
                                                    inputParams.getCallerPackageName()),
                                    mLightweightExecutorService)
                            .transform(
                                    this::closeSuccessfulAdSelection, mLightweightExecutorService)
                            .catching(
                                    RuntimeException.class,
                                    this::closeFailedAdSelectionWithRuntimeException,
                                    mLightweightExecutorService)
                            .catching(
                                    AdServicesException.class,
                                    this::closeFailedAdSelectionWithAdServicesException,
                                    mLightweightExecutorService);

            Futures.addCallback(
                    dbAdSelectionFuture,
                    new FutureCallback<DBAdSelection>() {
                        @Override
                        public void onSuccess(DBAdSelection result) {
                            Tracing.endAsyncSection(Tracing.RUN_AD_SELECTION, traceCookie);
                            notifySuccessToCaller(result, callback);
                        }

                        @Override
                        public void onFailure(Throwable t) {
                            Tracing.endAsyncSection(Tracing.RUN_AD_SELECTION, traceCookie);
                            if (t instanceof FilterException
                                    && t.getCause()
                                            instanceof ConsentManager.RevokedConsentException) {
                                // Skip logging if a FilterException occurs.
                                // AdSelectionServiceFilter ensures the failing assertion is logged
                                // internally.

                                // Fail Silently by notifying success to caller
                                notifyEmptySuccessToCaller(callback);
                            } else {
                                if (t.getCause() instanceof AdServicesException) {
                                    notifyFailureToCaller(callback, t.getCause());
                                } else {
                                    notifyFailureToCaller(callback, t);
                                }
                            }
                        }
                    },
                    mLightweightExecutorService);
        } catch (Throwable t) {
            Tracing.endAsyncSection(Tracing.RUN_AD_SELECTION, traceCookie);
            sLogger.v("run ad selection fails fast with exception %s.", t.toString());
            notifyFailureToCaller(callback, t);
        }
    }

    @Nullable
    private DBAdSelection closeFailedAdSelectionWithRuntimeException(RuntimeException e) {
        sLogger.v("Close failed ad selection and rethrow the RuntimeException %s.", e.toString());
        int resultCode = AdServicesLoggerUtil.getResultCodeFromException(e);
        mAdSelectionExecutionLogger.close(resultCode);
        throw e;
    }

    @Nullable
    private DBAdSelection closeFailedAdSelectionWithAdServicesException(AdServicesException e) {
        int resultCode = AdServicesLoggerUtil.getResultCodeFromException(e);
        mAdSelectionExecutionLogger.close(resultCode);
        sLogger.v(
                "Close failed ad selection and wrap the AdServicesException with"
                        + " an RuntimeException with message: %s and log with resultCode : %d",
                e.getMessage(), resultCode);
        throw new RuntimeException(e.getMessage(), e.getCause());
    }

    @NonNull
    private DBAdSelection closeSuccessfulAdSelection(@NonNull DBAdSelection dbAdSelection) {
        mAdSelectionExecutionLogger.close(AdServicesStatusUtils.STATUS_SUCCESS);
        return dbAdSelection;
    }

    private void notifySuccessToCaller(
            @NonNull DBAdSelection result, @NonNull AdSelectionCallback callback) {
        try {
            int overallLatencyMs =
                    mAdSelectionExecutionLogger.getRunAdSelectionOverallLatencyInMs();
            sLogger.v(
                    "Ad Selection with Id:%d completed with overall latency %d in ms, "
                            + "attempted notifying success",
                    result.getAdSelectionId(), overallLatencyMs);
            // TODO(b//253522566): When including logging data from bidding & auction server side
            //  should be able to differentiate the data from the on-device telemetry.
            // Note: Success is logged before the callback to ensure deterministic testing.
            mAdServicesLogger.logFledgeApiCallStats(
                    AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS,
                    AdServicesStatusUtils.STATUS_SUCCESS,
                    overallLatencyMs);

            callback.onSuccess(
                    new AdSelectionResponse.Builder()
                            .setAdSelectionId(result.getAdSelectionId())
                            .setRenderUri(result.getWinningAdRenderUri())
                            .build());
        } catch (RemoteException e) {
            sLogger.e(e, "Encountered exception during notifying AdSelection callback");
        }
    }

    /** Sends a successful response to the caller that represents a silent failure. */
    private void notifyEmptySuccessToCaller(@NonNull AdSelectionCallback callback) {
        try {
            callback.onSuccess(
                    new AdSelectionResponse.Builder()
                            .setAdSelectionId(mAdSelectionIdGenerator.generateId())
                            .setRenderUri(Uri.EMPTY)
                            .build());
        } catch (RemoteException e) {
            sLogger.e(e, "Encountered exception during notifying AdSelection callback");
        }
    }

    private void notifyFailureToCaller(
            @NonNull AdSelectionCallback callback, @NonNull Throwable t) {
        try {
            sLogger.e(t, "Ad Selection failure: ");

            int resultCode = AdServicesLoggerUtil.getResultCodeFromException(t);

            // Skip logging if a FilterException occurs.
            // AdSelectionServiceFilter ensures the failing assertion is logged internally.
            // Note: Failure is logged before the callback to ensure deterministic testing.
            if (!(t instanceof FilterException)) {
                int overallLatencyMs =
                        mAdSelectionExecutionLogger.getRunAdSelectionOverallLatencyInMs();
                sLogger.v("Ad Selection failed with overall latency %d in ms", overallLatencyMs);
                // TODO(b//253522566): When including logging data from bidding & auction server
                // side
                //  should be able to differentiate the data from the on-device telemetry.
                mAdServicesLogger.logFledgeApiCallStats(
                        AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS, resultCode, overallLatencyMs);
            }

            FledgeErrorResponse selectionFailureResponse =
                    new FledgeErrorResponse.Builder()
                            .setErrorMessage(
                                    String.format(
                                            AD_SELECTION_ERROR_PATTERN,
                                            ERROR_AD_SELECTION_FAILURE,
                                            t.getMessage()))
                            .setStatusCode(resultCode)
                            .build();
            callback.onFailure(selectionFailureResponse);
        } catch (RemoteException e) {
            sLogger.e(e, "Encountered exception during notifying AdSelection callback");
        }
    }

    /**
     * Overall moderator for running Ad Selection
     *
     * @param adSelectionConfig Set of data from Sellers and Buyers needed for Ad Auction and
     *     Selection
     * @return {@link AdSelectionResponse}
     */
    private ListenableFuture<DBAdSelection> orchestrateAdSelection(
            @NonNull final AdSelectionConfig adSelectionConfig,
            @NonNull final String callerPackageName) {
        sLogger.v("Beginning Ad Selection Orchestration");

        AdSelectionConfig adSelectionConfigInput = adSelectionConfig;
        if (!mFlags.getFledgeAdSelectionContextualAdsEnabled()) {
            // Empty all contextual ads if the feature is disabled
            sLogger.v("Contextual flow is disabled");
            adSelectionConfigInput = getAdSelectionConfigWithoutContextualAds(adSelectionConfig);
        } else {
            sLogger.v("Contextual flow is enabled, filtering contextual ads");
            adSelectionConfigInput = getAdSelectionConfigFilterContextualAds(adSelectionConfig);
        }

        ListenableFuture<List<DBCustomAudience>> buyerCustomAudience =
                getBuyersCustomAudience(adSelectionConfigInput);
        ListenableFuture<AdSelectionOrchestrationResult> dbAdSelection =
                orchestrateAdSelection(
                        adSelectionConfigInput, callerPackageName, buyerCustomAudience);

        AsyncFunction<AdSelectionOrchestrationResult, DBAdSelection> saveResultToPersistence =
                adSelectionAndJs ->
                        persistAdSelection(
                                adSelectionAndJs.mDbAdSelectionBuilder,
                                adSelectionAndJs.mBuyerDecisionLogicJs,
                                callerPackageName);

        return FluentFuture.from(dbAdSelection)
                .transformAsync(saveResultToPersistence, mLightweightExecutorService)
                .withTimeout(
                        mFlags.getAdSelectionOverallTimeoutMs(),
                        TimeUnit.MILLISECONDS,
                        mScheduledExecutor)
                .catching(
                        TimeoutException.class,
                        this::handleTimeoutError,
                        mLightweightExecutorService);
    }

    abstract ListenableFuture<AdSelectionOrchestrationResult> orchestrateAdSelection(
            @NonNull AdSelectionConfig adSelectionConfig,
            @NonNull String callerPackageName,
            @NonNull ListenableFuture<List<DBCustomAudience>> buyerCustomAudience);

    @Nullable
    private DBAdSelection handleTimeoutError(TimeoutException e) {
        sLogger.e(e, "Ad Selection exceeded time limit");
        throw new UncheckedTimeoutException(AD_SELECTION_TIMED_OUT);
    }

    private ListenableFuture<List<DBCustomAudience>> getBuyersCustomAudience(
            final AdSelectionConfig adSelectionConfig) {
        final int traceCookie = Tracing.beginAsyncSection(Tracing.GET_BUYERS_CUSTOM_AUDIENCE);
        return mBackgroundExecutorService.submit(
                () -> {
                    boolean atLeastOnePresent =
                            !(adSelectionConfig.getCustomAudienceBuyers().isEmpty()
                                    && adSelectionConfig.getBuyerContextualAds().isEmpty());

                    Preconditions.checkArgument(
                            atLeastOnePresent, ERROR_NO_BUYERS_OR_CONTEXTUAL_ADS_AVAILABLE);
                    // Set start of bidding stage.
                    mAdSelectionExecutionLogger.startBiddingProcess(
                            countBuyersRequested(adSelectionConfig));
                    List<DBCustomAudience> buyerCustomAudience =
                            mCustomAudienceDao.getActiveCustomAudienceByBuyers(
                                    adSelectionConfig.getCustomAudienceBuyers(),
                                    mClock.instant(),
                                    mFlags.getFledgeCustomAudienceActiveTimeWindowInMs());
                    if ((buyerCustomAudience == null || buyerCustomAudience.isEmpty())
                            && adSelectionConfig.getBuyerContextualAds().isEmpty()) {
                        IllegalStateException exception =
                                new IllegalStateException(ERROR_NO_CA_AND_CONTEXTUAL_ADS_AVAILABLE);
                        mAdSelectionExecutionLogger.endBiddingProcess(
                                null, getResultCodeFromException(exception));
                        throw exception;
                    }
                    // end a successful get-buyers-custom-audience process.
                    mAdSelectionExecutionLogger.endGetBuyersCustomAudience(
                            countBuyersFromCustomAudiences(buyerCustomAudience));
                    Tracing.endAsyncSection(Tracing.GET_BUYERS_CUSTOM_AUDIENCE, traceCookie);
                    return buyerCustomAudience;
                });
    }

    private int countBuyersRequested(@NonNull AdSelectionConfig adSelectionConfig) {
        Objects.requireNonNull(adSelectionConfig);
        return adSelectionConfig.getCustomAudienceBuyers().stream()
                .collect(Collectors.toSet())
                .size();
    }

    private int countBuyersFromCustomAudiences(
            @NonNull List<DBCustomAudience> buyerCustomAudience) {
        Objects.requireNonNull(buyerCustomAudience);
        return buyerCustomAudience.stream()
                .map(a -> a.getBuyer())
                .collect(Collectors.toSet())
                .size();
    }

    private ListenableFuture<DBAdSelection> persistAdSelection(
            @NonNull DBAdSelection.Builder dbAdSelectionBuilder,
            @NonNull String buyerDecisionLogicJS,
            @NonNull String callerPackageName) {
        final int traceCookie = Tracing.beginAsyncSection(Tracing.PERSIST_AD_SELECTION);
        return mBackgroundExecutorService.submit(
                () -> {
                    long adSelectionId = mAdSelectionIdGenerator.generateId();
                    // Retry ID generation in case of collision
                    while (mAdSelectionEntryDao.doesAdSelectionIdExist(adSelectionId)) {
                        adSelectionId = mAdSelectionIdGenerator.generateId();
                    }
                    sLogger.v("Persisting Ad Selection Result for Id:%d", adSelectionId);
                    DBAdSelection dbAdSelection;
                    dbAdSelectionBuilder
                            .setAdSelectionId(adSelectionId)
                            .setCreationTimestamp(mClock.instant())
                            .setCallerPackageName(callerPackageName);
                    dbAdSelection = dbAdSelectionBuilder.build();
                    mAdSelectionExecutionLogger.startPersistAdSelection(dbAdSelection);
                    mAdSelectionEntryDao.persistAdSelection(dbAdSelection);
                    mAdSelectionEntryDao.persistBuyerDecisionLogic(
                            new DBBuyerDecisionLogic.Builder()
                                    .setBuyerDecisionLogicJs(buyerDecisionLogicJS)
                                    .setBiddingLogicUri(dbAdSelection.getBiddingLogicUri())
                                    .build());
                    mAdSelectionExecutionLogger.endPersistAdSelection();
                    Tracing.endAsyncSection(Tracing.PERSIST_AD_SELECTION, traceCookie);
                    return dbAdSelection;
                });
    }

    /**
     * Validates the {@code adSelectionConfig} from the request.
     *
     * @param adSelectionConfig the adSelectionConfig to be validated
     * @throws IllegalArgumentException if the provided {@code adSelectionConfig} is not valid
     */
    private void validateAdSelectionConfig(AdSelectionConfig adSelectionConfig)
            throws IllegalArgumentException {
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        adSelectionConfigValidator.validate(adSelectionConfig);
    }

    private AdSelectionConfig getAdSelectionConfigFilterContextualAds(
            AdSelectionConfig adSelectionConfig) {
        Map<AdTechIdentifier, ContextualAds> filteredContextualAdsMap = new HashMap<>();
        sLogger.v("Filtering contextual ads in Ad Selection Config");
        for (Map.Entry<AdTechIdentifier, ContextualAds> entry :
                adSelectionConfig.getBuyerContextualAds().entrySet()) {
            filteredContextualAdsMap.put(
                    entry.getKey(), mAdFilterer.filterContextualAds(entry.getValue()));
        }
        return adSelectionConfig
                .cloneToBuilder()
                .setBuyerContextualAds(filteredContextualAdsMap)
                .build();
    }

    private AdSelectionConfig getAdSelectionConfigWithoutContextualAds(
            AdSelectionConfig adSelectionConfig) {
        sLogger.v("Emptying contextual ads in Ad Selection Config");
        return adSelectionConfig
                .cloneToBuilder()
                .setBuyerContextualAds(Collections.EMPTY_MAP)
                .build();
    }

    static class AdSelectionOrchestrationResult {
        DBAdSelection.Builder mDbAdSelectionBuilder;
        String mBuyerDecisionLogicJs;

        AdSelectionOrchestrationResult(
                DBAdSelection.Builder dbAdSelectionBuilder, String buyerDecisionLogicJs) {
            this.mDbAdSelectionBuilder = dbAdSelectionBuilder;
            this.mBuyerDecisionLogicJs = buyerDecisionLogicJs;
        }
    }
}
