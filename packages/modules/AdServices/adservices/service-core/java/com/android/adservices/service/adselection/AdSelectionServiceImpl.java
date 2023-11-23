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
import static android.adservices.common.AdServicesStatusUtils.STATUS_INVALID_ARGUMENT;
import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_CLASS__FLEDGE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__API_NAME_UNKNOWN;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_AD_SELECTION_CONFIG_REMOTE_INFO;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__REMOVE_AD_SELECTION_CONFIG_REMOTE_INFO_OVERRIDE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__REPORT_INTERACTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__RESET_ALL_AD_SELECTION_CONFIG_REMOTE_OVERRIDES;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__UPDATE_AD_COUNTER_HISTOGRAM;

import android.adservices.adselection.AdSelectionCallback;
import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.AdSelectionFromOutcomesConfig;
import android.adservices.adselection.AdSelectionFromOutcomesInput;
import android.adservices.adselection.AdSelectionInput;
import android.adservices.adselection.AdSelectionOverrideCallback;
import android.adservices.adselection.AdSelectionService;
import android.adservices.adselection.BuyersDecisionLogic;
import android.adservices.adselection.RemoveAdCounterHistogramOverrideInput;
import android.adservices.adselection.ReportImpressionCallback;
import android.adservices.adselection.ReportImpressionInput;
import android.adservices.adselection.ReportInteractionCallback;
import android.adservices.adselection.ReportInteractionInput;
import android.adservices.adselection.SetAdCounterHistogramOverrideInput;
import android.adservices.adselection.SetAppInstallAdvertisersCallback;
import android.adservices.adselection.SetAppInstallAdvertisersInput;
import android.adservices.adselection.UpdateAdCounterHistogramCallback;
import android.adservices.adselection.UpdateAdCounterHistogramInput;
import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.CallerMetadata;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Build;
import android.os.RemoteException;

import androidx.annotation.RequiresApi;

import com.android.adservices.LoggerFactory;
import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.data.adselection.AdSelectionDatabase;
import com.android.adservices.data.adselection.AdSelectionEntryDao;
import com.android.adservices.data.adselection.AppInstallDao;
import com.android.adservices.data.adselection.FrequencyCapDao;
import com.android.adservices.data.adselection.SharedStorageDatabase;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.CustomAudienceDatabase;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.AdSelectionServiceFilter;
import com.android.adservices.service.common.AppImportanceFilter;
import com.android.adservices.service.common.BinderFlagReader;
import com.android.adservices.service.common.CallingAppUidSupplier;
import com.android.adservices.service.common.CallingAppUidSupplierBinderImpl;
import com.android.adservices.service.common.FledgeAllowListsFilter;
import com.android.adservices.service.common.FledgeAuthorizationFilter;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.common.cache.CacheProviderFactory;
import com.android.adservices.service.common.httpclient.AdServicesHttpsClient;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.devapi.AdSelectionOverrider;
import com.android.adservices.service.devapi.DevContext;
import com.android.adservices.service.devapi.DevContextFilter;
import com.android.adservices.service.js.JSSandboxIsNotAvailableException;
import com.android.adservices.service.js.JSScriptEngine;
import com.android.adservices.service.stats.AdSelectionExecutionLogger;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.adservices.service.stats.AdServicesStatsLog;
import com.android.adservices.service.stats.Clock;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

/**
 * Implementation of {@link AdSelectionService}.
 *
 * @hide
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class AdSelectionServiceImpl extends AdSelectionService.Stub {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    @NonNull private final AdSelectionEntryDao mAdSelectionEntryDao;
    @NonNull private final AppInstallDao mAppInstallDao;
    @NonNull private final CustomAudienceDao mCustomAudienceDao;
    @NonNull private final FrequencyCapDao mFrequencyCapDao;
    @NonNull private final AdServicesHttpsClient mAdServicesHttpsClient;
    @NonNull private final ExecutorService mLightweightExecutor;
    @NonNull private final ExecutorService mBackgroundExecutor;
    @NonNull private final ScheduledThreadPoolExecutor mScheduledExecutor;
    @NonNull private final Context mContext;
    @NonNull private final DevContextFilter mDevContextFilter;
    @NonNull private final AdServicesLogger mAdServicesLogger;
    @NonNull private final Flags mFlags;
    @NonNull private final CallingAppUidSupplier mCallingAppUidSupplier;
    @NonNull private final FledgeAuthorizationFilter mFledgeAuthorizationFilter;
    @NonNull private final AdSelectionServiceFilter mAdSelectionServiceFilter;
    @NonNull private final AdFilteringFeatureFactory mAdFilteringFeatureFactory;
    @NonNull private final ConsentManager mConsentManager;

    private static final String API_NOT_AUTHORIZED_MSG =
            "This API is not enabled for the given app because either dev options are disabled or"
                    + " the app is not debuggable.";

    @VisibleForTesting
    public AdSelectionServiceImpl(
            @NonNull AdSelectionEntryDao adSelectionEntryDao,
            @NonNull AppInstallDao appInstallDao,
            @NonNull CustomAudienceDao customAudienceDao,
            @NonNull FrequencyCapDao frequencyCapDao,
            @NonNull AdServicesHttpsClient adServicesHttpsClient,
            @NonNull DevContextFilter devContextFilter,
            @NonNull ExecutorService lightweightExecutorService,
            @NonNull ExecutorService backgroundExecutorService,
            @NonNull ScheduledThreadPoolExecutor scheduledExecutor,
            @NonNull Context context,
            @NonNull AdServicesLogger adServicesLogger,
            @NonNull Flags flags,
            @NonNull CallingAppUidSupplier callingAppUidSupplier,
            @NonNull FledgeAuthorizationFilter fledgeAuthorizationFilter,
            @NonNull AdSelectionServiceFilter adSelectionServiceFilter,
            @NonNull AdFilteringFeatureFactory adFilteringFeatureFactory,
            @NonNull ConsentManager consentManager) {
        Objects.requireNonNull(context, "Context must be provided.");
        Objects.requireNonNull(adSelectionEntryDao);
        Objects.requireNonNull(appInstallDao);
        Objects.requireNonNull(customAudienceDao);
        Objects.requireNonNull(frequencyCapDao);
        Objects.requireNonNull(adServicesHttpsClient);
        Objects.requireNonNull(devContextFilter);
        Objects.requireNonNull(lightweightExecutorService);
        Objects.requireNonNull(backgroundExecutorService);
        Objects.requireNonNull(scheduledExecutor);
        Objects.requireNonNull(adServicesLogger);
        Objects.requireNonNull(flags);
        Objects.requireNonNull(adFilteringFeatureFactory);
        Objects.requireNonNull(consentManager);

        mAdSelectionEntryDao = adSelectionEntryDao;
        mAppInstallDao = appInstallDao;
        mCustomAudienceDao = customAudienceDao;
        mFrequencyCapDao = frequencyCapDao;
        mAdServicesHttpsClient = adServicesHttpsClient;
        mDevContextFilter = devContextFilter;
        mLightweightExecutor = lightweightExecutorService;
        mBackgroundExecutor = backgroundExecutorService;
        mScheduledExecutor = scheduledExecutor;
        mContext = context;
        mAdServicesLogger = adServicesLogger;
        mFlags = flags;
        mCallingAppUidSupplier = callingAppUidSupplier;
        mFledgeAuthorizationFilter = fledgeAuthorizationFilter;
        mAdSelectionServiceFilter = adSelectionServiceFilter;
        mAdFilteringFeatureFactory = adFilteringFeatureFactory;
        mConsentManager = consentManager;
    }

    /** Creates a new instance of {@link AdSelectionServiceImpl}. */
    public static AdSelectionServiceImpl create(@NonNull Context context) {
        return new AdSelectionServiceImpl(context);
    }

    /** Creates an instance of {@link AdSelectionServiceImpl} to be used. */
    private AdSelectionServiceImpl(@NonNull Context context) {
        this(
                AdSelectionDatabase.getInstance(context).adSelectionEntryDao(),
                SharedStorageDatabase.getInstance(context).appInstallDao(),
                CustomAudienceDatabase.getInstance(context).customAudienceDao(),
                SharedStorageDatabase.getInstance(context).frequencyCapDao(),
                new AdServicesHttpsClient(
                        AdServicesExecutors.getBlockingExecutor(),
                        CacheProviderFactory.create(context, FlagsFactory.getFlags())),
                DevContextFilter.create(context),
                AdServicesExecutors.getLightWeightExecutor(),
                AdServicesExecutors.getBackgroundExecutor(),
                AdServicesExecutors.getScheduler(),
                context,
                AdServicesLoggerImpl.getInstance(),
                FlagsFactory.getFlags(),
                CallingAppUidSupplierBinderImpl.create(),
                FledgeAuthorizationFilter.create(context, AdServicesLoggerImpl.getInstance()),
                new AdSelectionServiceFilter(
                        context,
                        ConsentManager.getInstance(context),
                        FlagsFactory.getFlags(),
                        AppImportanceFilter.create(
                                context,
                                AD_SERVICES_API_CALLED__API_CLASS__FLEDGE,
                                () ->
                                        FlagsFactory.getFlags()
                                                .getForegroundStatuslLevelForValidation()),
                        FledgeAuthorizationFilter.create(
                                context, AdServicesLoggerImpl.getInstance()),
                        new FledgeAllowListsFilter(
                                FlagsFactory.getFlags(), AdServicesLoggerImpl.getInstance()),
                        () -> Throttler.getInstance(FlagsFactory.getFlags())),
                new AdFilteringFeatureFactory(
                        SharedStorageDatabase.getInstance(context).appInstallDao(),
                        SharedStorageDatabase.getInstance(context).frequencyCapDao(),
                        FlagsFactory.getFlags()),
                ConsentManager.getInstance(context));
    }

    // TODO(b/233116758): Validate all the fields inside the adSelectionConfig.
    @Override
    public void selectAds(
            @NonNull AdSelectionInput inputParams,
            @NonNull CallerMetadata callerMetadata,
            @NonNull AdSelectionCallback callback) {
        final AdSelectionExecutionLogger adSelectionExecutionLogger =
                new AdSelectionExecutionLogger(
                        callerMetadata, Clock.SYSTEM_CLOCK, mContext, mAdServicesLogger);
        int apiName = AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS;
        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);
        try {
            Objects.requireNonNull(inputParams);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            int overallLatencyMs = adSelectionExecutionLogger.getRunAdSelectionOverallLatencyInMs();
            sLogger.v(
                    "The selectAds(AdSelectionConfig) arguments should not be null, failed with"
                            + " overall latency %d in ms.",
                    overallLatencyMs);
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, STATUS_INVALID_ARGUMENT, overallLatencyMs);
            // Rethrow because we want to fail fast
            throw exception;
        }

        int callingUid = getCallingUid(apiName);

        DevContext devContext = mDevContextFilter.createDevContext();
        mLightweightExecutor.execute(
                () -> {
                    // TODO(b/249298855): Evolve off device ad selection logic.
                    if (mFlags.getAdSelectionOffDeviceEnabled()) {
                        runOffDeviceAdSelection(
                                devContext,
                                inputParams,
                                callback,
                                adSelectionExecutionLogger,
                                mAdSelectionServiceFilter,
                                callingUid);
                    } else {
                        runOnDeviceAdSelection(
                                devContext,
                                inputParams,
                                callback,
                                adSelectionExecutionLogger,
                                mAdSelectionServiceFilter,
                                callingUid);
                    }
                });
    }

    private void runOnDeviceAdSelection(
            DevContext devContext,
            @NonNull AdSelectionInput inputParams,
            @NonNull AdSelectionCallback callback,
            @NonNull AdSelectionExecutionLogger adSelectionExecutionLogger,
            @NonNull AdSelectionServiceFilter adSelectionServiceFilter,
            final int callerUid) {
        OnDeviceAdSelectionRunner runner =
                new OnDeviceAdSelectionRunner(
                        mContext,
                        mCustomAudienceDao,
                        mAdSelectionEntryDao,
                        mAdServicesHttpsClient,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mScheduledExecutor,
                        mAdServicesLogger,
                        devContext,
                        mFlags,
                        adSelectionExecutionLogger,
                        adSelectionServiceFilter,
                        mAdFilteringFeatureFactory.getAdFilterer(),
                        mAdFilteringFeatureFactory.getAdCounterKeyCopier(),
                        callerUid);
        runner.runAdSelection(inputParams, callback);
    }

    private void runOffDeviceAdSelection(
            DevContext devContext,
            @NonNull AdSelectionInput inputParams,
            @NonNull AdSelectionCallback callback,
            @NonNull AdSelectionExecutionLogger adSelectionExecutionLogger,
            @NonNull AdSelectionServiceFilter adSelectionServiceFilter,
            int callerUid) {
        TrustedServerAdSelectionRunner runner =
                new TrustedServerAdSelectionRunner(
                        mContext,
                        mCustomAudienceDao,
                        mAdSelectionEntryDao,
                        mAdServicesHttpsClient,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mScheduledExecutor,
                        mAdServicesLogger,
                        devContext,
                        mFlags,
                        adSelectionExecutionLogger,
                        adSelectionServiceFilter,
                        mAdFilteringFeatureFactory.getAdFilterer(),
                        callerUid);
        runner.runAdSelection(inputParams, callback);
    }

    /**
     * Returns an ultimate winner ad of given list of previous winner ads.
     *
     * @param inputParams includes list of outcomes, signals and uri to download selection logic
     * @param callerMetadata caller's metadata for stat logging
     * @param callback delivers the results via OutcomeReceiver
     */
    @Override
    public void selectAdsFromOutcomes(
            @NonNull AdSelectionFromOutcomesInput inputParams,
            @NonNull CallerMetadata callerMetadata,
            @NonNull AdSelectionCallback callback) {
        int apiName = AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__SELECT_ADS_FROM_OUTCOMES;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        // TODO(257134800): Add telemetry
        try {
            Objects.requireNonNull(inputParams);
            Objects.requireNonNull(callback);
        } catch (NullPointerException e) {
            sLogger.v(
                    "The selectAds(AdSelectionFromOutcomesConfig) arguments should not be null,"
                            + " failed");
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw e;
        }

        int callingUid = getCallingUid(apiName);

        DevContext devContext = mDevContextFilter.createDevContext();
        mLightweightExecutor.execute(
                () -> {
                    OutcomeSelectionRunner runner =
                            new OutcomeSelectionRunner(
                                    mAdSelectionEntryDao,
                                    mBackgroundExecutor,
                                    mLightweightExecutor,
                                    mScheduledExecutor,
                                    mAdServicesHttpsClient,
                                    mAdServicesLogger,
                                    devContext,
                                    mContext,
                                    mFlags,
                                    mAdSelectionServiceFilter,
                                    mAdFilteringFeatureFactory.getAdCounterKeyCopier(),
                                    callingUid);
                    runner.runOutcomeSelection(inputParams, callback);
                });
    }

    @Override
    public void reportImpression(
            @NonNull ReportImpressionInput requestParams,
            @NonNull ReportImpressionCallback callback) {
        int apiName = AD_SERVICES_API_CALLED__API_NAME__REPORT_IMPRESSION;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(requestParams);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        DevContext devContext = mDevContextFilter.createDevContext();

        int callingUid = getCallingUid(apiName);

        ImpressionReporter reporter =
                new ImpressionReporter(
                        mContext,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mScheduledExecutor,
                        mAdSelectionEntryDao,
                        mCustomAudienceDao,
                        mAdServicesHttpsClient,
                        devContext,
                        mAdServicesLogger,
                        mFlags,
                        mAdSelectionServiceFilter,
                        mFledgeAuthorizationFilter,
                        callingUid);
        reporter.reportImpression(requestParams, callback);
    }

    @Override
    public void reportInteraction(
            @NonNull ReportInteractionInput inputParams,
            @NonNull ReportInteractionCallback callback) {
        int apiName = AD_SERVICES_API_CALLED__API_NAME__REPORT_INTERACTION;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(inputParams);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        int callerUid = getCallingUid(apiName);

        InteractionReporter interactionReporter =
                new InteractionReporter(
                        mAdSelectionEntryDao,
                        mAdServicesHttpsClient,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mAdServicesLogger,
                        mFlags,
                        mAdSelectionServiceFilter,
                        callerUid,
                        mFledgeAuthorizationFilter);

        interactionReporter.reportInteraction(inputParams, callback);
    }

    @Override
    public void setAppInstallAdvertisers(
            @NonNull SetAppInstallAdvertisersInput request,
            @NonNull SetAppInstallAdvertisersCallback callback)
            throws RemoteException {
        int apiName =
                AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__SET_APP_INSTALL_ADVERTISERS;
        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(request);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        AppInstallAdvertisersSetter setter =
                new AppInstallAdvertisersSetter(
                        mAppInstallDao,
                        mBackgroundExecutor,
                        mAdServicesLogger,
                        mFlags,
                        mAdSelectionServiceFilter,
                        mConsentManager,
                        getCallingUid(apiName));
        setter.setAppInstallAdvertisers(request, callback);
    }

    @Override
    public void updateAdCounterHistogram(
            @NonNull UpdateAdCounterHistogramInput inputParams,
            @NonNull UpdateAdCounterHistogramCallback callback) {
        int apiName = AD_SERVICES_API_CALLED__API_NAME__UPDATE_AD_COUNTER_HISTOGRAM;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(inputParams);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        int callingUid = getCallingUid(apiName);

        UpdateAdCounterHistogramWorker worker =
                new UpdateAdCounterHistogramWorker(
                        new AdCounterHistogramUpdaterImpl(
                                mAdSelectionEntryDao,
                                mFrequencyCapDao,
                                BinderFlagReader.readFlag(
                                        mFlags::getFledgeAdCounterHistogramAbsoluteMaxEventCount),
                                BinderFlagReader.readFlag(
                                        mFlags::getFledgeAdCounterHistogramLowerMaxEventCount)),
                        mBackgroundExecutor,
                        // TODO(b/235841960): Use the same injected clock as AdSelectionRunner
                        //  after aligning on Clock usage
                        java.time.Clock.systemUTC(),
                        mAdServicesLogger,
                        mFlags,
                        mAdSelectionServiceFilter,
                        mConsentManager,
                        callingUid);

        worker.updateAdCounterHistogram(inputParams, callback);
    }

    @Override
    public void overrideAdSelectionConfigRemoteInfo(
            @NonNull AdSelectionConfig adSelectionConfig,
            @NonNull String decisionLogicJS,
            @NonNull AdSelectionSignals trustedScoringSignals,
            @NonNull BuyersDecisionLogic buyersDecisionLogic,
            @NonNull AdSelectionOverrideCallback callback) {
        int apiName = AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_AD_SELECTION_CONFIG_REMOTE_INFO;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(adSelectionConfig);
            Objects.requireNonNull(decisionLogicJS);
            Objects.requireNonNull(buyersDecisionLogic);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        DevContext devContext = mDevContextFilter.createDevContext();

        if (!devContext.getDevOptionsEnabled()) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw new SecurityException(API_NOT_AUTHORIZED_MSG);
        }

        int callingUid = getCallingUid(apiName);

        AdSelectionOverrider overrider =
                new AdSelectionOverrider(
                        devContext,
                        mAdSelectionEntryDao,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mContext.getPackageManager(),
                        ConsentManager.getInstance(mContext),
                        mAdServicesLogger,
                        AppImportanceFilter.create(
                                mContext,
                                AD_SERVICES_API_CALLED__API_CLASS__FLEDGE,
                                () ->
                                        FlagsFactory.getFlags()
                                                .getForegroundStatuslLevelForValidation()),
                        mFlags,
                        callingUid);

        overrider.addOverride(
                adSelectionConfig,
                decisionLogicJS,
                trustedScoringSignals,
                buyersDecisionLogic,
                callback);
    }

    private int getCallingUid(int apiNameLoggingId) {
        try {
            return mCallingAppUidSupplier.getCallingAppUid();
        } catch (IllegalStateException illegalStateException) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiNameLoggingId, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw illegalStateException;
        }
    }

    @Override
    public void removeAdSelectionConfigRemoteInfoOverride(
            @NonNull AdSelectionConfig adSelectionConfig,
            @NonNull AdSelectionOverrideCallback callback) {
        // Auto-generated variable name is too long for lint check
        int apiName =
                AD_SERVICES_API_CALLED__API_NAME__REMOVE_AD_SELECTION_CONFIG_REMOTE_INFO_OVERRIDE;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(adSelectionConfig);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        DevContext devContext = mDevContextFilter.createDevContext();

        if (!devContext.getDevOptionsEnabled()) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw new SecurityException(API_NOT_AUTHORIZED_MSG);
        }

        int callingUid = getCallingUid(apiName);

        AdSelectionOverrider overrider =
                new AdSelectionOverrider(
                        devContext,
                        mAdSelectionEntryDao,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mContext.getPackageManager(),
                        ConsentManager.getInstance(mContext),
                        mAdServicesLogger,
                        AppImportanceFilter.create(
                                mContext,
                                AD_SERVICES_API_CALLED__API_CLASS__FLEDGE,
                                () ->
                                        FlagsFactory.getFlags()
                                                .getForegroundStatuslLevelForValidation()),
                        mFlags,
                        callingUid);

        overrider.removeOverride(adSelectionConfig, callback);
    }

    @Override
    public void resetAllAdSelectionConfigRemoteOverrides(
            @NonNull AdSelectionOverrideCallback callback) {
        // Auto-generated variable name is too long for lint check
        int apiName =
                AD_SERVICES_API_CALLED__API_NAME__RESET_ALL_AD_SELECTION_CONFIG_REMOTE_OVERRIDES;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        DevContext devContext = mDevContextFilter.createDevContext();

        if (!devContext.getDevOptionsEnabled()) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw new SecurityException(API_NOT_AUTHORIZED_MSG);
        }

        int callingUid = getCallingUid(apiName);

        AdSelectionOverrider overrider =
                new AdSelectionOverrider(
                        devContext,
                        mAdSelectionEntryDao,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mContext.getPackageManager(),
                        ConsentManager.getInstance(mContext),
                        mAdServicesLogger,
                        AppImportanceFilter.create(
                                mContext,
                                AD_SERVICES_API_CALLED__API_CLASS__FLEDGE,
                                () ->
                                        FlagsFactory.getFlags()
                                                .getForegroundStatuslLevelForValidation()),
                        mFlags,
                        callingUid);

        overrider.removeAllOverridesForAdSelectionConfig(callback);
    }

    @Override
    public void overrideAdSelectionFromOutcomesConfigRemoteInfo(
            @NonNull AdSelectionFromOutcomesConfig config,
            @NonNull String selectionLogicJs,
            @NonNull AdSelectionSignals selectionSignals,
            @NonNull AdSelectionOverrideCallback callback) {
        int apiName = AD_SERVICES_API_CALLED__API_NAME__API_NAME_UNKNOWN;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(config);
            Objects.requireNonNull(selectionLogicJs);
            Objects.requireNonNull(selectionSignals);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        DevContext devContext = mDevContextFilter.createDevContext();

        if (!devContext.getDevOptionsEnabled()) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw new SecurityException(API_NOT_AUTHORIZED_MSG);
        }

        int callingUid = getCallingUid(apiName);

        AdSelectionOverrider overrider =
                new AdSelectionOverrider(
                        devContext,
                        mAdSelectionEntryDao,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mContext.getPackageManager(),
                        ConsentManager.getInstance(mContext),
                        mAdServicesLogger,
                        AppImportanceFilter.create(
                                mContext,
                                AD_SERVICES_API_CALLED__API_CLASS__FLEDGE,
                                () ->
                                        FlagsFactory.getFlags()
                                                .getForegroundStatuslLevelForValidation()),
                        mFlags,
                        callingUid);

        overrider.addOverride(config, selectionLogicJs, selectionSignals, callback);
    }

    @Override
    public void removeAdSelectionFromOutcomesConfigRemoteInfoOverride(
            @NonNull AdSelectionFromOutcomesConfig config,
            @NonNull AdSelectionOverrideCallback callback) {
        // Auto-generated variable name is too long for lint check
        int apiName = AD_SERVICES_API_CALLED__API_NAME__API_NAME_UNKNOWN;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(config);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        DevContext devContext = mDevContextFilter.createDevContext();

        if (!devContext.getDevOptionsEnabled()) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw new SecurityException(API_NOT_AUTHORIZED_MSG);
        }

        int callingUid = getCallingUid(apiName);

        AdSelectionOverrider overrider =
                new AdSelectionOverrider(
                        devContext,
                        mAdSelectionEntryDao,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mContext.getPackageManager(),
                        ConsentManager.getInstance(mContext),
                        mAdServicesLogger,
                        AppImportanceFilter.create(
                                mContext,
                                AD_SERVICES_API_CALLED__API_CLASS__FLEDGE,
                                () ->
                                        FlagsFactory.getFlags()
                                                .getForegroundStatuslLevelForValidation()),
                        mFlags,
                        callingUid);

        overrider.removeOverride(config, callback);
    }

    @Override
    public void resetAllAdSelectionFromOutcomesConfigRemoteOverrides(
            @NonNull AdSelectionOverrideCallback callback) {
        // Auto-generated variable name is too long for lint check
        int apiName = AD_SERVICES_API_CALLED__API_NAME__API_NAME_UNKNOWN;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        DevContext devContext = mDevContextFilter.createDevContext();

        if (!devContext.getDevOptionsEnabled()) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw new SecurityException(API_NOT_AUTHORIZED_MSG);
        }

        int callingUid = getCallingUid(apiName);

        AdSelectionOverrider overrider =
                new AdSelectionOverrider(
                        devContext,
                        mAdSelectionEntryDao,
                        mLightweightExecutor,
                        mBackgroundExecutor,
                        mContext.getPackageManager(),
                        ConsentManager.getInstance(mContext),
                        mAdServicesLogger,
                        AppImportanceFilter.create(
                                mContext,
                                AD_SERVICES_API_CALLED__API_CLASS__FLEDGE,
                                () ->
                                        FlagsFactory.getFlags()
                                                .getForegroundStatuslLevelForValidation()),
                        mFlags,
                        callingUid);

        overrider.removeAllOverridesForAdSelectionFromOutcomes(callback);
    }

    @Override
    public void setAdCounterHistogramOverride(
            @NonNull SetAdCounterHistogramOverrideInput inputParams,
            @NonNull AdSelectionOverrideCallback callback) {
        int apiName = AD_SERVICES_API_CALLED__API_NAME__API_NAME_UNKNOWN;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(inputParams);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        // TODO(b/265204820): Implement service
        int status = STATUS_SUCCESS;
        try {
            callback.onSuccess();
        } catch (RemoteException exception) {
            status = STATUS_INTERNAL_ERROR;
        } finally {
            mAdServicesLogger.logFledgeApiCallStats(apiName, status, 0);
        }
    }

    @Override
    public void removeAdCounterHistogramOverride(
            @NonNull RemoveAdCounterHistogramOverrideInput inputParams,
            @NonNull AdSelectionOverrideCallback callback) {
        int apiName = AD_SERVICES_API_CALLED__API_NAME__API_NAME_UNKNOWN;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(inputParams);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        // TODO(b/265204820): Implement service
        int status = STATUS_SUCCESS;
        try {
            callback.onSuccess();
        } catch (RemoteException exception) {
            status = STATUS_INTERNAL_ERROR;
        } finally {
            mAdServicesLogger.logFledgeApiCallStats(apiName, status, 0);
        }
    }

    @Override
    public void resetAllAdCounterHistogramOverrides(@NonNull AdSelectionOverrideCallback callback) {
        int apiName = AD_SERVICES_API_CALLED__API_NAME__API_NAME_UNKNOWN;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(apiName, STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        // TODO(b/265204820): Implement service
        int status = STATUS_SUCCESS;
        try {
            callback.onSuccess();
        } catch (RemoteException exception) {
            status = STATUS_INTERNAL_ERROR;
        } finally {
            mAdServicesLogger.logFledgeApiCallStats(apiName, status, 0);
        }
    }

    /** Close down method to be invoked when the PPAPI process is shut down. */
    @SuppressWarnings("FutureReturnValueIgnored")
    public void destroy() {
        sLogger.i("Shutting down AdSelectionService");
        try {
            JSScriptEngine jsScriptEngine = JSScriptEngine.getInstance(mContext);
            jsScriptEngine.shutdown();
        } catch (JSSandboxIsNotAvailableException exception) {
            sLogger.i("Java script sandbox is not available, not shutting down JSScriptEngine.");
        }
    }
}
