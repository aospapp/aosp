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

import static android.adservices.adselection.ReportInteractionRequest.FLAG_REPORTING_DESTINATION_BUYER;
import static android.adservices.adselection.ReportInteractionRequest.FLAG_REPORTING_DESTINATION_SELLER;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION;

import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.ReportImpressionCallback;
import android.adservices.adselection.ReportImpressionInput;
import android.adservices.adselection.ReportInteractionRequest;
import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.FledgeErrorResponse;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.RemoteException;
import android.os.Trace;
import android.util.Pair;

import androidx.annotation.RequiresApi;

import com.android.adservices.LogUtil;
import com.android.adservices.LoggerFactory;
import com.android.adservices.data.adselection.AdSelectionEntryDao;
import com.android.adservices.data.adselection.CustomAudienceSignals;
import com.android.adservices.data.adselection.DBAdSelectionEntry;
import com.android.adservices.data.adselection.DBRegisteredAdInteraction;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.AdSelectionServiceFilter;
import com.android.adservices.service.common.AdTechUriValidator;
import com.android.adservices.service.common.BinderFlagReader;
import com.android.adservices.service.common.FledgeAuthorizationFilter;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.common.ValidatorUtil;
import com.android.adservices.service.common.httpclient.AdServicesHttpClientRequest;
import com.android.adservices.service.common.httpclient.AdServicesHttpsClient;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.devapi.AdSelectionDevOverridesHelper;
import com.android.adservices.service.devapi.CustomAudienceDevOverridesHelper;
import com.android.adservices.service.devapi.DevContext;
import com.android.adservices.service.exception.FilterException;
import com.android.adservices.service.profiling.Tracing;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.internal.util.Preconditions;

import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.json.JSONException;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/** Encapsulates the Impression Reporting logic */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class ImpressionReporter {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    public static final String UNABLE_TO_FIND_AD_SELECTION_WITH_GIVEN_ID =
            "Unable to find ad selection with given ID";
    public static final String CALLER_PACKAGE_NAME_MISMATCH =
            "Caller package name does not match name used in ad selection";

    private static final String REPORTING_URI_FIELD_NAME = "reporting URI";
    private static final String EVENT_URI_FIELD_NAME = "event URI";

    @NonNull private final AdSelectionEntryDao mAdSelectionEntryDao;
    @NonNull private final CustomAudienceDao mCustomAudienceDao;
    @NonNull private final AdServicesHttpsClient mAdServicesHttpsClient;
    @NonNull private final ListeningExecutorService mLightweightExecutorService;
    @NonNull private final ListeningExecutorService mBackgroundExecutorService;
    @NonNull private final ScheduledThreadPoolExecutor mScheduledExecutor;
    @NonNull private final ReportImpressionScriptEngine mJsEngine;
    @NonNull private final AdSelectionDevOverridesHelper mAdSelectionDevOverridesHelper;
    @NonNull private final CustomAudienceDevOverridesHelper mCustomAudienceDevOverridesHelper;
    @NonNull private final AdServicesLogger mAdServicesLogger;
    @NonNull private final Flags mFlags;
    @NonNull private final RegisterAdBeaconSupportHelper mRegisterAdBeaconSupportHelper;
    @NonNull private final AdSelectionServiceFilter mAdSelectionServiceFilter;
    @NonNull private final JsFetcher mJsFetcher;
    private int mCallerUid;
    @NonNull private final PrebuiltLogicGenerator mPrebuiltLogicGenerator;
    @NonNull private final FledgeAuthorizationFilter mFledgeAuthorizationFilter;

    public ImpressionReporter(
            @NonNull Context context,
            @NonNull ExecutorService lightweightExecutor,
            @NonNull ExecutorService backgroundExecutor,
            @NonNull ScheduledThreadPoolExecutor scheduledExecutor,
            @NonNull AdSelectionEntryDao adSelectionEntryDao,
            @NonNull CustomAudienceDao customAudienceDao,
            @NonNull AdServicesHttpsClient adServicesHttpsClient,
            @NonNull DevContext devContext,
            @NonNull AdServicesLogger adServicesLogger,
            @NonNull final Flags flags,
            @NonNull final AdSelectionServiceFilter adSelectionServiceFilter,
            @NonNull final FledgeAuthorizationFilter fledgeAuthorizationFilter,
            final int callerUid) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(lightweightExecutor);
        Objects.requireNonNull(backgroundExecutor);
        Objects.requireNonNull(scheduledExecutor);
        Objects.requireNonNull(adSelectionEntryDao);
        Objects.requireNonNull(customAudienceDao);
        Objects.requireNonNull(adServicesHttpsClient);
        Objects.requireNonNull(devContext);
        Objects.requireNonNull(adServicesLogger);
        Objects.requireNonNull(flags);
        Objects.requireNonNull(adSelectionServiceFilter);

        mLightweightExecutorService = MoreExecutors.listeningDecorator(lightweightExecutor);
        mBackgroundExecutorService = MoreExecutors.listeningDecorator(backgroundExecutor);
        mScheduledExecutor = scheduledExecutor;
        mAdSelectionEntryDao = adSelectionEntryDao;
        mCustomAudienceDao = customAudienceDao;
        mAdServicesHttpsClient = adServicesHttpsClient;
        boolean isRegisterAdBeaconEnabled =
                BinderFlagReader.readFlag(flags::getFledgeRegisterAdBeaconEnabled);

        ReportImpressionScriptEngine.RegisterAdBeaconScriptEngineHelper
                registerAdBeaconScriptEngineHelper;
        if (isRegisterAdBeaconEnabled) {
            mRegisterAdBeaconSupportHelper = new RegisterAdBeaconSupportHelperEnabled();
            long maxInteractionReportingUrisSize =
                    BinderFlagReader.readFlag(
                            () ->
                                    flags
                                            .getFledgeReportImpressionMaxRegisteredAdBeaconsPerAdTechCount());
            registerAdBeaconScriptEngineHelper =
                    new ReportImpressionScriptEngine.RegisterAdBeaconScriptEngineHelperEnabled(
                            maxInteractionReportingUrisSize);
        } else {
            mRegisterAdBeaconSupportHelper = new RegisterAdBeaconSupportHelperDisabled();
            registerAdBeaconScriptEngineHelper =
                    new ReportImpressionScriptEngine.RegisterAdBeaconScriptEngineHelperDisabled();
        }
        mJsEngine =
                new ReportImpressionScriptEngine(
                        context,
                        () -> flags.getEnforceIsolateMaxHeapSize(),
                        () -> flags.getIsolateMaxHeapSizeBytes(),
                        registerAdBeaconScriptEngineHelper);

        mAdSelectionDevOverridesHelper =
                new AdSelectionDevOverridesHelper(devContext, mAdSelectionEntryDao);
        mCustomAudienceDevOverridesHelper =
                new CustomAudienceDevOverridesHelper(devContext, mCustomAudienceDao);
        mAdServicesLogger = adServicesLogger;
        mFlags = flags;
        mAdSelectionServiceFilter = adSelectionServiceFilter;
        mCallerUid = callerUid;
        mJsFetcher =
                new JsFetcher(
                        mBackgroundExecutorService,
                        mLightweightExecutorService,
                        mAdServicesHttpsClient,
                        mFlags);
        mPrebuiltLogicGenerator = new PrebuiltLogicGenerator(mFlags);
        mFledgeAuthorizationFilter = fledgeAuthorizationFilter;
    }

    /** Invokes the onFailure function from the callback and handles the exception. */
    private void invokeFailure(
            @NonNull ReportImpressionCallback callback, int resultCode, String errorMessage) {
        try {
            callback.onFailure(
                    new FledgeErrorResponse.Builder()
                            .setStatusCode(resultCode)
                            .setErrorMessage(errorMessage)
                            .build());
        } catch (RemoteException e) {
            sLogger.e(e, "Unable to send failed result to the callback");
            throw e.rethrowFromSystemServer();
        }
    }

    /** Invokes the onSuccess function from the callback and handles the exception. */
    private void invokeSuccess(@NonNull ReportImpressionCallback callback, int resultCode) {
        try {
            callback.onSuccess();
        } catch (RemoteException e) {
            sLogger.e(e, "Unable to send successful result to the callback");
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Run the impression report logic asynchronously. Invoked seller's reportResult() as well as
     * the buyer's reportWin() in the case of a remarketing ad.
     *
     * <p>After invoking the javascript functions, invokes the onSuccess function of the callback
     * and reports URIs resulting from the javascript functions.
     *
     * @param requestParams request parameters containing the {@code adSelectionId}, {@code
     *     adSelectionConfig}, and {@code callerPackageName}
     * @param callback callback function to be called in case of success or failure
     */
    public void reportImpression(
            @NonNull ReportImpressionInput requestParams,
            @NonNull ReportImpressionCallback callback) {
        sLogger.v("Executing reportImpression API");
        long adSelectionId = requestParams.getAdSelectionId();
        long timeoutMs = BinderFlagReader.readFlag(mFlags::getReportImpressionOverallTimeoutMs);
        AdSelectionConfig adSelectionConfig = requestParams.getAdSelectionConfig();
        ListenableFuture<Void> filterAndValidateRequestFuture =
                Futures.submit(
                        () -> {
                            try {
                                Trace.beginSection(Tracing.VALIDATE_REQUEST);
                                sLogger.v("Starting filtering and validation.");
                                mAdSelectionServiceFilter.filterRequest(
                                        adSelectionConfig.getSeller(),
                                        requestParams.getCallerPackageName(),
                                        mFlags
                                                .getEnforceForegroundStatusForFledgeReportImpression(),
                                        true,
                                        mCallerUid,
                                        AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION,
                                        Throttler.ApiKey.FLEDGE_API_REPORT_IMPRESSIONS);
                                validateAdSelectionConfig(adSelectionConfig);
                            } finally {
                                sLogger.v("Completed filtering and validation.");
                                Trace.endSection();
                            }
                        },
                        mLightweightExecutorService);

        FluentFuture.from(filterAndValidateRequestFuture)
                .transformAsync(
                        ignoredVoid ->
                                computeReportingUris(
                                        adSelectionId,
                                        adSelectionConfig,
                                        requestParams.getCallerPackageName()),
                        mLightweightExecutorService)
                .transform(
                        reportingUrisAndContext ->
                                notifySuccessToCaller(
                                        callback,
                                        reportingUrisAndContext.first,
                                        reportingUrisAndContext.second),
                        mLightweightExecutorService)
                .withTimeout(
                        timeoutMs,
                        TimeUnit.MILLISECONDS,
                        // TODO(b/237103033): Comply with thread usage policy for AdServices;
                        //  use a global scheduled executor
                        mScheduledExecutor)
                .addCallback(
                        new FutureCallback<Pair<ReportingUris, ReportingContext>>() {
                            @Override
                            public void onSuccess(Pair<ReportingUris, ReportingContext> result) {
                                sLogger.d("Computed reporting uris successfully!");
                                performReporting(result.first, result.second);
                            }

                            @Override
                            public void onFailure(Throwable t) {
                                sLogger.e(t, "Report Impression invocation failed!");
                                if (t instanceof FilterException
                                        && t.getCause()
                                                instanceof ConsentManager.RevokedConsentException) {
                                    // Skip logging if a FilterException occurs.
                                    // AdSelectionServiceFilter ensures the failing assertion is
                                    // logged internally.

                                    // Fail Silently by notifying success to caller
                                    invokeSuccess(
                                            callback,
                                            AdServicesStatusUtils.STATUS_USER_CONSENT_REVOKED);
                                } else {
                                    notifyFailureToCaller(callback, t);
                                }
                            }
                        },
                        mLightweightExecutorService);
    }

    private void performReporting(ReportingUris reportingUris, ReportingContext ctx) {
        FluentFuture<List<Void>> reportingFuture = FluentFuture.from(doReport(reportingUris, ctx));
        reportingFuture.addCallback(
                new FutureCallback<List<Void>>() {
                    @Override
                    public void onSuccess(List<Void> result) {
                        sLogger.d("Reporting finished successfully!");
                        mAdServicesLogger.logFledgeApiCallStats(
                                AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION,
                                AdServicesStatusUtils.STATUS_SUCCESS,
                                0);
                    }

                    @Override
                    public void onFailure(Throwable t) {
                        sLogger.e(t, "Report Impression failure encountered during reporting!");
                        if (t instanceof IOException) {
                            mAdServicesLogger.logFledgeApiCallStats(
                                    AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION,
                                    AdServicesStatusUtils.STATUS_IO_ERROR,
                                    0);
                        }
                        mAdServicesLogger.logFledgeApiCallStats(
                                AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION,
                                AdServicesStatusUtils.STATUS_INTERNAL_ERROR,
                                0);
                    }
                },
                mLightweightExecutorService);
    }

    private Pair<ReportingUris, ReportingContext> notifySuccessToCaller(
            @NonNull ReportImpressionCallback callback,
            @NonNull ReportingUris reportingUris,
            @NonNull ReportingContext ctx) {
        invokeSuccess(callback, AdServicesStatusUtils.STATUS_SUCCESS);
        return Pair.create(reportingUris, ctx);
    }

    private void notifyFailureToCaller(
            @NonNull ReportImpressionCallback callback, @NonNull Throwable t) {
        int resultCode;

        boolean isFilterException = t instanceof FilterException;

        if (isFilterException) {
            resultCode = FilterException.getResultCode(t);
        } else if (t instanceof IllegalArgumentException) {
            resultCode = AdServicesStatusUtils.STATUS_INVALID_ARGUMENT;
        } else {
            resultCode = AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
        }

        // Skip logging if a FilterException occurs.
        // AdSelectionServiceFilter ensures the failing assertion is logged internally.
        // Note: Failure is logged before the callback to ensure deterministic testing.
        if (!isFilterException) {
            mAdServicesLogger.logFledgeApiCallStats(
                    AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION, resultCode, 0);
        }

        invokeFailure(callback, resultCode, t.getMessage());
    }

    @NonNull
    private ListenableFuture<List<Void>> doReport(
            ReportingUris reportingUris, ReportingContext ctx) {
        sLogger.v("Reporting URIs");

        ListenableFuture<Void> sellerFuture;

        // Validate seller uri before reporting
        AdTechUriValidator sellerValidator =
                new AdTechUriValidator(
                        ValidatorUtil.AD_TECH_ROLE_SELLER,
                        ctx.mAdSelectionConfig.getSeller().toString(),
                        this.getClass().getSimpleName(),
                        REPORTING_URI_FIELD_NAME);
        try {
            sellerValidator.validate(reportingUris.sellerReportingUri);
            // We don't need to verify enrollment since that is done during request filtering
            // Perform reporting if no exception was thrown
            sellerFuture =
                    mAdServicesHttpsClient.getAndReadNothing(reportingUris.sellerReportingUri);
        } catch (IllegalArgumentException e) {
            sLogger.v("Seller reporting URI validation failed!");
            sellerFuture = Futures.immediateFuture(null);
        }

        ListenableFuture<Void> buyerFuture;

        // Validate buyer uri if it exists
        if (!Objects.isNull(reportingUris.buyerReportingUri)) {
            CustomAudienceSignals customAudienceSignals =
                    Objects.requireNonNull(ctx.mDBAdSelectionEntry.getCustomAudienceSignals());

            AdTechUriValidator buyerValidator =
                    new AdTechUriValidator(
                            ValidatorUtil.AD_TECH_ROLE_BUYER,
                            customAudienceSignals.getBuyer().toString(),
                            this.getClass().getSimpleName(),
                            REPORTING_URI_FIELD_NAME);
            try {
                buyerValidator.validate(reportingUris.buyerReportingUri);
                if (!mFlags.getDisableFledgeEnrollmentCheck()) {
                    mFledgeAuthorizationFilter.assertAdTechEnrolled(
                            AdTechIdentifier.fromString(reportingUris.buyerReportingUri.getHost()),
                            AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION);
                }
                // Perform reporting if no exception was thrown
                buyerFuture =
                        mAdServicesHttpsClient.getAndReadNothing(reportingUris.buyerReportingUri);
            } catch (IllegalArgumentException
                    | FledgeAuthorizationFilter.AdTechNotAllowedException e) {
                sLogger.v("Buyer reporting URI validation failed!");
                buyerFuture = Futures.immediateFuture(null);
            }
        } else {
            // In case of contextual ad
            buyerFuture = Futures.immediateFuture(null);
        }

        return Futures.allAsList(sellerFuture, buyerFuture);
    }

    private FluentFuture<Pair<ReportingUris, ReportingContext>> computeReportingUris(
            long adSelectionId, AdSelectionConfig adSelectionConfig, String callerPackageName) {
        return fetchAdSelectionEntry(adSelectionId, callerPackageName)
                .transformAsync(
                        dbAdSelectionEntry -> {
                            LogUtil.v(
                                    "DecisionLogicJs from db entry: "
                                            + dbAdSelectionEntry.getBuyerDecisionLogicJs());
                            LogUtil.v(
                                    "DecisionLogicUri from db entry: "
                                            + dbAdSelectionEntry.getBiddingLogicUri().toString());
                            ReportingContext ctx = new ReportingContext();
                            ctx.mDBAdSelectionEntry = dbAdSelectionEntry;
                            ctx.mAdSelectionConfig = adSelectionConfig;
                            return fetchSellerDecisionLogic(ctx);
                        },
                        mLightweightExecutorService)
                .transformAsync(
                        decisionLogicJsAndCtx ->
                                invokeSellerScript(
                                        decisionLogicJsAndCtx.first, decisionLogicJsAndCtx.second),
                        mLightweightExecutorService)
                .transformAsync(
                        sellerResultAndCtx ->
                                mRegisterAdBeaconSupportHelper.commitSellerRegisteredEvents(
                                        sellerResultAndCtx.first, sellerResultAndCtx.second),
                        mLightweightExecutorService)
                .transformAsync(
                        sellerResultAndCtx ->
                                invokeBuyerScript(
                                        sellerResultAndCtx.first, sellerResultAndCtx.second),
                        mLightweightExecutorService)
                .transformAsync(
                        reportingResultsAndCtx ->
                                mRegisterAdBeaconSupportHelper.commitBuyerRegisteredEvents(
                                        reportingResultsAndCtx.first,
                                        reportingResultsAndCtx.second),
                        mLightweightExecutorService);
    }

    private FluentFuture<DBAdSelectionEntry> fetchAdSelectionEntry(
            long adSelectionId, String callerPackageName) {
        sLogger.v(
                "Fetching ad selection entry ID %d for caller \"%s\"",
                adSelectionId, callerPackageName);
        return FluentFuture.from(
                mBackgroundExecutorService.submit(
                        () -> {
                            Preconditions.checkArgument(
                                    mAdSelectionEntryDao.doesAdSelectionIdExist(adSelectionId),
                                    UNABLE_TO_FIND_AD_SELECTION_WITH_GIVEN_ID);
                            Preconditions.checkArgument(
                                    mAdSelectionEntryDao
                                            .doesAdSelectionMatchingCallerPackageNameExist(
                                                    adSelectionId, callerPackageName),
                                    CALLER_PACKAGE_NAME_MISMATCH);
                            return mAdSelectionEntryDao.getAdSelectionEntityById(adSelectionId);
                        }));
    }

    private FluentFuture<Pair<String, ReportingContext>> fetchSellerDecisionLogic(
            ReportingContext ctx) {
        sLogger.v("Fetching seller reporting script");
        AdServicesHttpClientRequest request =
                AdServicesHttpClientRequest.builder()
                        .setUri(ctx.mAdSelectionConfig.getDecisionLogicUri())
                        .setUseCache(mFlags.getFledgeHttpJsCachingEnabled())
                        .build();

        return mJsFetcher
                .getSellerReportingLogic(
                        request, mAdSelectionDevOverridesHelper, ctx.mAdSelectionConfig)
                .transform(
                        stringResult -> {
                            sLogger.v(
                                    "Seller script from uri: %s: %s",
                                    request.getUri(), stringResult);
                            return Pair.create(stringResult, ctx);
                        },
                        mLightweightExecutorService);
    }

    private FluentFuture<String> fetchBuyerDecisionLogic(
            ReportingContext ctx, CustomAudienceSignals customAudienceSignals) {
        if (!ctx.mDBAdSelectionEntry.getBuyerDecisionLogicJs().isEmpty()) {
            sLogger.v(
                    "Buyer decision logic fetched during ad selection. No need to fetch it again.");
            return FluentFuture.from(
                    Futures.immediateFuture(ctx.mDBAdSelectionEntry.getBuyerDecisionLogicJs()));
        }
        sLogger.v("Fetching buyer script");
        AdServicesHttpClientRequest request =
                AdServicesHttpClientRequest.builder()
                        .setUri(ctx.mDBAdSelectionEntry.getBiddingLogicUri())
                        .setUseCache(mFlags.getFledgeHttpJsCachingEnabled())
                        .build();

        return mJsFetcher.getBuyerReportingLogic(
                request,
                mCustomAudienceDevOverridesHelper,
                customAudienceSignals.getOwner(),
                customAudienceSignals.getBuyer(),
                customAudienceSignals.getName());
    }

    private FluentFuture<Pair<ReportImpressionScriptEngine.SellerReportingResult, ReportingContext>>
            invokeSellerScript(String decisionLogicJs, ReportingContext ctx) {
        sLogger.v("Invoking seller script");
        try {
            return FluentFuture.from(
                            mJsEngine.reportResult(
                                    decisionLogicJs,
                                    ctx.mAdSelectionConfig,
                                    ctx.mDBAdSelectionEntry.getWinningAdRenderUri(),
                                    ctx.mDBAdSelectionEntry.getWinningAdBid(),
                                    AdSelectionSignals.fromString(
                                            ctx.mDBAdSelectionEntry.getContextualSignals())))
                    .transform(
                            sellerResult -> Pair.create(sellerResult, ctx),
                            mLightweightExecutorService);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid JSON data", e);
        }
    }

    private FluentFuture<Pair<ReportingResults, ReportingContext>> invokeBuyerScript(
            ReportImpressionScriptEngine.SellerReportingResult sellerReportingResult,
            ReportingContext ctx) {
        sLogger.v("Invoking buyer script");
        sLogger.v("buyer JS: " + ctx.mDBAdSelectionEntry.getBuyerDecisionLogicJs());
        sLogger.v("Buyer JS Uri: " + ctx.mDBAdSelectionEntry.getBiddingLogicUri());

        final CustomAudienceSignals customAudienceSignals =
                Objects.requireNonNull(ctx.mDBAdSelectionEntry.getCustomAudienceSignals());

        AdSelectionSignals signals =
                Optional.ofNullable(
                                ctx.mAdSelectionConfig
                                        .getPerBuyerSignals()
                                        .get(customAudienceSignals.getBuyer()))
                        .orElse(AdSelectionSignals.EMPTY);

        try {
            // TODO(b/233239475) : Validate Buyer signals in Ad Selection Config
            return FluentFuture.from(
                            mJsEngine.reportWin(
                                    fetchBuyerDecisionLogic(ctx, customAudienceSignals).get(),
                                    ctx.mAdSelectionConfig.getAdSelectionSignals(),
                                    signals,
                                    sellerReportingResult.getSignalsForBuyer(),
                                    AdSelectionSignals.fromString(
                                            ctx.mDBAdSelectionEntry.getContextualSignals()),
                                    customAudienceSignals))
                    .transform(
                            buyerReportingResult ->
                                    Pair.create(
                                            new ReportingResults(
                                                    buyerReportingResult, sellerReportingResult),
                                            ctx),
                            mLightweightExecutorService);
        } catch (JSONException e) {
            throw new IllegalArgumentException("Invalid JSON args", e);
        } catch (InterruptedException | ExecutionException e) {
            throw new IllegalStateException(
                    "Error while fetching buyer script from uri: "
                            + ctx.mDBAdSelectionEntry.getBiddingLogicUri());
        }
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

    private static class ReportingContext {
        @NonNull AdSelectionConfig mAdSelectionConfig;
        @NonNull DBAdSelectionEntry mDBAdSelectionEntry;
    }

    private static final class ReportingUris {
        @Nullable public final Uri buyerReportingUri;
        @NonNull public final Uri sellerReportingUri;

        private ReportingUris(@Nullable Uri buyerReportingUri, @NonNull Uri sellerReportingUri) {
            Objects.requireNonNull(sellerReportingUri);

            this.buyerReportingUri = buyerReportingUri;
            this.sellerReportingUri = sellerReportingUri;
        }
    }

    private static final class ReportingResults {
        @Nullable
        public final ReportImpressionScriptEngine.BuyerReportingResult mBuyerReportingResult;

        @NonNull
        public final ReportImpressionScriptEngine.SellerReportingResult mSellerReportingResult;

        private ReportingResults(
                @Nullable ReportImpressionScriptEngine.BuyerReportingResult buyerReportingResult,
                @NonNull ReportImpressionScriptEngine.SellerReportingResult sellerReportingResult) {
            Objects.requireNonNull(sellerReportingResult);

            mBuyerReportingResult = buyerReportingResult;
            mSellerReportingResult = sellerReportingResult;
        }
    }

    private interface RegisterAdBeaconSupportHelper {
        FluentFuture<Pair<ReportImpressionScriptEngine.SellerReportingResult, ReportingContext>>
                commitSellerRegisteredEvents(
                        ReportImpressionScriptEngine.SellerReportingResult sellerReportingResult,
                        ReportingContext ctx);

        FluentFuture<Pair<ReportingUris, ReportingContext>> commitBuyerRegisteredEvents(
                ReportingResults reportingResults, ReportingContext ctx);
    }

    private class RegisterAdBeaconSupportHelperEnabled implements RegisterAdBeaconSupportHelper {

        @Override
        public FluentFuture<
                        Pair<ReportImpressionScriptEngine.SellerReportingResult, ReportingContext>>
                commitSellerRegisteredEvents(
                        ReportImpressionScriptEngine.SellerReportingResult sellerReportingResult,
                        ReportingContext ctx) {
            // Validate seller uri before reporting
            AdTechUriValidator sellerValidator =
                    new AdTechUriValidator(
                            ValidatorUtil.AD_TECH_ROLE_SELLER,
                            ctx.mAdSelectionConfig.getSeller().toString(),
                            this.getClass().getSimpleName(),
                            EVENT_URI_FIELD_NAME);

            return FluentFuture.from(
                    mBackgroundExecutorService.submit(
                            () -> {
                                commitRegisteredAdInteractionsToDatabase(
                                        sellerReportingResult.getInteractionReportingUris(),
                                        sellerValidator,
                                        ctx.mDBAdSelectionEntry.getAdSelectionId(),
                                        FLAG_REPORTING_DESTINATION_SELLER);
                                return Pair.create(sellerReportingResult, ctx);
                            }));
        }

        @Override
        public FluentFuture<Pair<ReportingUris, ReportingContext>> commitBuyerRegisteredEvents(
                ReportingResults reportingResults, ReportingContext ctx) {
            if (Objects.isNull(reportingResults.mBuyerReportingResult)) {
                return FluentFuture.from(
                        Futures.immediateFuture(
                                Pair.create(
                                        new ReportingUris(
                                                null,
                                                reportingResults.mSellerReportingResult
                                                        .getReportingUri()),
                                        ctx)));
            }

            CustomAudienceSignals customAudienceSignals =
                    Objects.requireNonNull(ctx.mDBAdSelectionEntry.getCustomAudienceSignals());

            AdTechUriValidator buyerValidator =
                    new AdTechUriValidator(
                            ValidatorUtil.AD_TECH_ROLE_BUYER,
                            customAudienceSignals.getBuyer().toString(),
                            this.getClass().getSimpleName(),
                            REPORTING_URI_FIELD_NAME);

            return FluentFuture.from(
                    mBackgroundExecutorService.submit(
                            () -> {
                                commitRegisteredAdInteractionsToDatabase(
                                        reportingResults.mBuyerReportingResult
                                                .getInteractionReportingUris(),
                                        buyerValidator,
                                        ctx.mDBAdSelectionEntry.getAdSelectionId(),
                                        FLAG_REPORTING_DESTINATION_BUYER);
                                return Pair.create(
                                        new ReportingUris(
                                                reportingResults.mBuyerReportingResult
                                                        .getReportingUri(),
                                                reportingResults.mSellerReportingResult
                                                        .getReportingUri()),
                                        ctx);
                            }));
        }

        /**
         * Iterates through each {@link InteractionUriRegistrationInfo}, validates each {@link
         * InteractionUriRegistrationInfo#getInteractionReportingUri()}, and commits it to the
         * {@code registered_ad_interactions} table if it's valid.
         *
         * <p>Note: For system health purposes, we will enforce these limitations: 1. We only commit
         * up to a maximum of {@link
         * ImpressionReporter#mFlags#getReportImpressionMaxRegisteredAdBeaconsTotalCount()} entries
         * to the database. 2. We will not commit an entry to the database if {@link
         * InteractionUriRegistrationInfo#getInteractionKey()} is larger than {@link
         * ImpressionReporter#mFlags#getFledgeReportImpressionRegisteredAdBeaconsMaxInteractionKeySize()}
         */
        private void commitRegisteredAdInteractionsToDatabase(
                @NonNull List<InteractionUriRegistrationInfo> interactionUriRegistrationInfos,
                @NonNull AdTechUriValidator validator,
                long adSelectionId,
                @ReportInteractionRequest.ReportingDestination int reportingDestination) {

            long maxTableSize = mFlags.getFledgeReportImpressionMaxRegisteredAdBeaconsTotalCount();
            long maxInteractionKeySize =
                    mFlags.getFledgeReportImpressionRegisteredAdBeaconsMaxInteractionKeySizeB();
            long maxNumRowsPerDestination =
                    mFlags.getFledgeReportImpressionMaxRegisteredAdBeaconsPerAdTechCount();

            List<DBRegisteredAdInteraction> adInteractionsToRegister = new ArrayList<>();

            for (InteractionUriRegistrationInfo uriRegistrationInfo :
                    interactionUriRegistrationInfos) {
                if (uriRegistrationInfo.getInteractionKey().getBytes().length
                        > maxInteractionKeySize) {
                    sLogger.v(
                            "InteractionKey size exceeds the maximum allowed! Skipping this entry");
                    continue;
                }

                Uri uriToValidate = uriRegistrationInfo.getInteractionReportingUri();
                try {
                    validator.validate(uriToValidate);
                    DBRegisteredAdInteraction dbRegisteredAdInteraction =
                            DBRegisteredAdInteraction.builder()
                                    .setAdSelectionId(adSelectionId)
                                    .setInteractionKey(uriRegistrationInfo.getInteractionKey())
                                    .setInteractionReportingUri(uriToValidate)
                                    .setDestination(reportingDestination)
                                    .build();
                    adInteractionsToRegister.add(dbRegisteredAdInteraction);
                } catch (IllegalArgumentException e) {
                    sLogger.v(
                            "Uri %s failed validation! Skipping persistence of this interaction URI"
                                    + " pair.",
                            uriToValidate);
                }
            }
            mAdSelectionEntryDao.safelyInsertRegisteredAdInteractions(
                    adSelectionId,
                    adInteractionsToRegister,
                    maxTableSize,
                    maxNumRowsPerDestination,
                    reportingDestination);
        }
    }

    private class RegisterAdBeaconSupportHelperDisabled implements RegisterAdBeaconSupportHelper {

        @Override
        public FluentFuture<
                        Pair<ReportImpressionScriptEngine.SellerReportingResult, ReportingContext>>
                commitSellerRegisteredEvents(
                        ReportImpressionScriptEngine.SellerReportingResult sellerReportingResult,
                        ReportingContext ctx) {
            // Return immediately since registerAdBeacon is disabled
            return FluentFuture.from(
                    Futures.immediateFuture(Pair.create(sellerReportingResult, ctx)));
        }

        @Override
        public FluentFuture<Pair<ReportingUris, ReportingContext>> commitBuyerRegisteredEvents(
                ReportingResults reportingResults, ReportingContext ctx) {
            if (Objects.isNull(reportingResults.mBuyerReportingResult)) {
                return FluentFuture.from(
                        Futures.immediateFuture(
                                Pair.create(
                                        new ReportingUris(
                                                null,
                                                reportingResults.mSellerReportingResult
                                                        .getReportingUri()),
                                        ctx)));
            }

            // Return immediately since registerAdBeacon is disabled
            return FluentFuture.from(
                    Futures.immediateFuture(
                            Pair.create(
                                    new ReportingUris(
                                            reportingResults.mBuyerReportingResult
                                                    .getReportingUri(),
                                            reportingResults.mSellerReportingResult
                                                    .getReportingUri()),
                                    ctx)));
        }
    }
}
