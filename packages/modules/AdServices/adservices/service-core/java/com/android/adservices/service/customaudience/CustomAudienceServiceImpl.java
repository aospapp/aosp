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

package com.android.adservices.service.customaudience;


import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_JOIN_CUSTOM_AUDIENCE;
import static com.android.adservices.service.common.Throttler.ApiKey.FLEDGE_API_LEAVE_CUSTOM_AUDIENCE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_CLASS__FLEDGE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__REMOVE_CUSTOM_AUDIENCE_REMOTE_INFO_OVERRIDE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__RESET_ALL_CUSTOM_AUDIENCE_OVERRIDES;

import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.FledgeErrorResponse;
import android.adservices.customaudience.CustomAudience;
import android.adservices.customaudience.CustomAudienceOverrideCallback;
import android.adservices.customaudience.ICustomAudienceCallback;
import android.adservices.customaudience.ICustomAudienceService;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Build;
import android.os.LimitExceededException;
import android.os.RemoteException;

import androidx.annotation.RequiresApi;

import com.android.adservices.LoggerFactory;
import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.AppImportanceFilter;
import com.android.adservices.service.common.AppImportanceFilter.WrongCallingApplicationStateException;
import com.android.adservices.service.common.CallingAppUidSupplier;
import com.android.adservices.service.common.CallingAppUidSupplierBinderImpl;
import com.android.adservices.service.common.CustomAudienceServiceFilter;
import com.android.adservices.service.common.FledgeAllowListsFilter;
import com.android.adservices.service.common.FledgeAuthorizationFilter;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.devapi.CustomAudienceOverrider;
import com.android.adservices.service.devapi.DevContext;
import com.android.adservices.service.devapi.DevContextFilter;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Implementation of the Custom Audience service. */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class CustomAudienceServiceImpl extends ICustomAudienceService.Stub {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    @NonNull private final Context mContext;
    @NonNull private final CustomAudienceImpl mCustomAudienceImpl;
    @NonNull private final FledgeAuthorizationFilter mFledgeAuthorizationFilter;
    @NonNull private final ConsentManager mConsentManager;
    @NonNull private final ExecutorService mExecutorService;
    @NonNull private final DevContextFilter mDevContextFilter;
    @NonNull private final AdServicesLogger mAdServicesLogger;
    @NonNull private final AppImportanceFilter mAppImportanceFilter;
    @NonNull private final Flags mFlags;
    @NonNull private final CallingAppUidSupplier mCallingAppUidSupplier;

    @NonNull private final CustomAudienceServiceFilter mCustomAudienceServiceFilter;

    private static final String API_NOT_AUTHORIZED_MSG =
            "This API is not enabled for the given app because either dev options are disabled or"
                    + " the app is not debuggable.";

    private CustomAudienceServiceImpl(@NonNull Context context) {
        this(
                context,
                CustomAudienceImpl.getInstance(context),
                FledgeAuthorizationFilter.create(context, AdServicesLoggerImpl.getInstance()),
                ConsentManager.getInstance(context),
                DevContextFilter.create(context),
                AdServicesExecutors.getBackgroundExecutor(),
                AdServicesLoggerImpl.getInstance(),
                AppImportanceFilter.create(
                        context,
                        AD_SERVICES_API_CALLED__API_CLASS__FLEDGE,
                        () -> FlagsFactory.getFlags().getForegroundStatuslLevelForValidation()),
                FlagsFactory.getFlags(),
                CallingAppUidSupplierBinderImpl.create(),
                new CustomAudienceServiceFilter(
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
                        () -> Throttler.getInstance(FlagsFactory.getFlags())));
    }

    /** Creates a new instance of {@link CustomAudienceServiceImpl}. */
    public static CustomAudienceServiceImpl create(@NonNull Context context) {
        return new CustomAudienceServiceImpl(context);
    }

    @VisibleForTesting
    public CustomAudienceServiceImpl(
            @NonNull Context context,
            @NonNull CustomAudienceImpl customAudienceImpl,
            @NonNull FledgeAuthorizationFilter fledgeAuthorizationFilter,
            @NonNull ConsentManager consentManager,
            @NonNull DevContextFilter devContextFilter,
            @NonNull ExecutorService executorService,
            @NonNull AdServicesLogger adServicesLogger,
            @NonNull AppImportanceFilter appImportanceFilter,
            @NonNull Flags flags,
            @NonNull CallingAppUidSupplier callingAppUidSupplier,
            @NonNull CustomAudienceServiceFilter customAudienceServiceFilter) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(customAudienceImpl);
        Objects.requireNonNull(fledgeAuthorizationFilter);
        Objects.requireNonNull(consentManager);
        Objects.requireNonNull(executorService);
        Objects.requireNonNull(adServicesLogger);
        Objects.requireNonNull(appImportanceFilter);
        Objects.requireNonNull(customAudienceServiceFilter);
        mContext = context;
        mCustomAudienceImpl = customAudienceImpl;
        mFledgeAuthorizationFilter = fledgeAuthorizationFilter;
        mConsentManager = consentManager;
        mDevContextFilter = devContextFilter;
        mExecutorService = executorService;
        mAdServicesLogger = adServicesLogger;
        mAppImportanceFilter = appImportanceFilter;
        mFlags = flags;
        mCallingAppUidSupplier = callingAppUidSupplier;
        mCustomAudienceServiceFilter = customAudienceServiceFilter;
    }

    /**
     * Adds a user to a custom audience.
     *
     * @hide
     */
    @Override
    public void joinCustomAudience(
            @NonNull CustomAudience customAudience,
            @NonNull String ownerPackageName,
            @NonNull ICustomAudienceCallback callback) {
        sLogger.v("Entering joinCustomAudience");

        final int apiName = AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(customAudience);
            Objects.requireNonNull(ownerPackageName);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        final int callerUid = getCallingUid(apiName);
        sLogger.v("Running service");
        mExecutorService.execute(
                () -> doJoinCustomAudience(customAudience, ownerPackageName, callback, callerUid));
    }

    /** Try to join the custom audience and signal back to the caller using the callback. */
    private void doJoinCustomAudience(
            @NonNull CustomAudience customAudience,
            @NonNull String ownerPackageName,
            @NonNull ICustomAudienceCallback callback,
            final int callerUid) {
        Objects.requireNonNull(customAudience);
        Objects.requireNonNull(ownerPackageName);
        Objects.requireNonNull(callback);

        sLogger.v("Entering doJoinCustomAudience");

        final int apiName = AD_SERVICES_API_CALLED__API_NAME__JOIN_CUSTOM_AUDIENCE;
        int resultCode = AdServicesStatusUtils.STATUS_UNSET;
        // The filters log internally, so don't accidentally log again
        boolean shouldLog = false;
        try {
            try {
                // Filter and validate request
                mCustomAudienceServiceFilter.filterRequest(
                        customAudience.getBuyer(),
                        ownerPackageName,
                        mFlags.getEnforceForegroundStatusForFledgeCustomAudience(),
                        false,
                        callerUid,
                        apiName,
                        FLEDGE_API_JOIN_CUSTOM_AUDIENCE);

                shouldLog = true;

                // Fail silently for revoked user consent
                if (!mConsentManager.isFledgeConsentRevokedForAppAfterSettingFledgeUse(
                        ownerPackageName)) {
                    sLogger.v("Joining custom audience");
                    mCustomAudienceImpl.joinCustomAudience(customAudience, ownerPackageName);
                    BackgroundFetchJobService.scheduleIfNeeded(mContext, mFlags, false);
                    resultCode = AdServicesStatusUtils.STATUS_SUCCESS;
                } else {
                    sLogger.v("Consent revoked");
                    resultCode = AdServicesStatusUtils.STATUS_USER_CONSENT_REVOKED;
                }
            } catch (Exception exception) {
                sLogger.d(exception, "Error encountered in joinCustomAudience, notifying caller");
                resultCode = notifyFailure(callback, exception);
                return;
            }

            callback.onSuccess();
        } catch (Exception exception) {
            sLogger.e(exception, "Unable to send result to the callback");
            resultCode = AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
        } finally {
            if (shouldLog) {
                mAdServicesLogger.logFledgeApiCallStats(apiName, resultCode, 0);
            }
        }
    }

    private int notifyFailure(ICustomAudienceCallback callback, Exception exception)
            throws RemoteException {
        int resultCode;
        if (exception instanceof NullPointerException
                || exception instanceof IllegalArgumentException) {
            resultCode = AdServicesStatusUtils.STATUS_INVALID_ARGUMENT;
        } else if (exception instanceof WrongCallingApplicationStateException) {
            resultCode = AdServicesStatusUtils.STATUS_BACKGROUND_CALLER;
        } else if (exception instanceof FledgeAuthorizationFilter.CallerMismatchException) {
            resultCode = AdServicesStatusUtils.STATUS_UNAUTHORIZED;
        } else if (exception instanceof FledgeAuthorizationFilter.AdTechNotAllowedException
                || exception instanceof FledgeAllowListsFilter.AppNotAllowedException) {
            resultCode = AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;
        } else if (exception instanceof LimitExceededException) {
            resultCode = AdServicesStatusUtils.STATUS_RATE_LIMIT_REACHED;
        } else if (exception instanceof IllegalStateException) {
            resultCode = AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
        } else {
            sLogger.e(exception, "Unexpected error during operation");
            resultCode = AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
        }
        callback.onFailure(
                new FledgeErrorResponse.Builder()
                        .setStatusCode(resultCode)
                        .setErrorMessage(exception.getMessage())
                        .build());
        return resultCode;
    }

    /**
     * Attempts to remove a user from a custom audience.
     *
     * @hide
     */
    @Override
    public void leaveCustomAudience(
            @NonNull String ownerPackageName,
            @NonNull AdTechIdentifier buyer,
            @NonNull String name,
            @NonNull ICustomAudienceCallback callback) {
        final int apiName = AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(ownerPackageName);
            Objects.requireNonNull(buyer);
            Objects.requireNonNull(name);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INVALID_ARGUMENT, 0);
            // Rethrow because we want to fail fast
            throw exception;
        }

        final int callerUid = getCallingUid(apiName);
        mExecutorService.execute(
                () -> doLeaveCustomAudience(ownerPackageName, buyer, name, callback, callerUid));
    }

    /** Try to leave the custom audience and signal back to the caller using the callback. */
    private void doLeaveCustomAudience(
            @NonNull String ownerPackageName,
            @NonNull AdTechIdentifier buyer,
            @NonNull String name,
            @NonNull ICustomAudienceCallback callback,
            final int callerUid) {
        Objects.requireNonNull(ownerPackageName);
        Objects.requireNonNull(buyer);
        Objects.requireNonNull(name);
        Objects.requireNonNull(callback);

        final int apiName = AD_SERVICES_API_CALLED__API_NAME__LEAVE_CUSTOM_AUDIENCE;
        int resultCode = AdServicesStatusUtils.STATUS_UNSET;
        // The filters log internally, so don't accidentally log again
        boolean shouldLog = false;
        try {
            try {
                // Filter and validate request
                mCustomAudienceServiceFilter.filterRequest(
                        buyer,
                        ownerPackageName,
                        mFlags.getEnforceForegroundStatusForFledgeCustomAudience(),
                        false,
                        callerUid,
                        apiName,
                        FLEDGE_API_LEAVE_CUSTOM_AUDIENCE);

                shouldLog = true;

                // Fail silently for revoked user consent
                if (!mConsentManager.isFledgeConsentRevokedForApp(ownerPackageName)) {
                    sLogger.v("Leaving custom audience");
                    mCustomAudienceImpl.leaveCustomAudience(ownerPackageName, buyer, name);
                    resultCode = AdServicesStatusUtils.STATUS_SUCCESS;
                } else {
                    sLogger.v("Consent revoked");
                    resultCode = AdServicesStatusUtils.STATUS_USER_CONSENT_REVOKED;
                }
            } catch (WrongCallingApplicationStateException
                    | LimitExceededException
                    | FledgeAuthorizationFilter.CallerMismatchException
                    | FledgeAuthorizationFilter.AdTechNotAllowedException
                    | FledgeAllowListsFilter.AppNotAllowedException exception) {
                // Catch these specific exceptions, but report them back to the caller
                sLogger.d(exception, "Error encountered in leaveCustomAudience, notifying caller");
                resultCode = notifyFailure(callback, exception);
                return;
            } catch (Exception exception) {
                // For all other exceptions, report success
                sLogger.e(exception, "Unexpected error leaving custom audience");
                resultCode = AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
            }

            callback.onSuccess();
        } catch (Exception exception) {
            sLogger.e(exception, "Unable to send result to the callback");
            resultCode = AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
        } finally {
            if (shouldLog) {
                mAdServicesLogger.logFledgeApiCallStats(apiName, resultCode, 0);
            }
        }
    }

    /**
     * Adds a custom audience override with the given information.
     *
     * <p>If the owner does not match the calling package name, fail silently.
     *
     * @hide
     */
    @Override
    public void overrideCustomAudienceRemoteInfo(
            @NonNull String owner,
            @NonNull AdTechIdentifier buyer,
            @NonNull String name,
            @NonNull String biddingLogicJS,
            long biddingLogicJsVersion,
            @NonNull AdSelectionSignals trustedBiddingSignals,
            @NonNull CustomAudienceOverrideCallback callback) {
        final int apiName = AD_SERVICES_API_CALLED__API_NAME__OVERRIDE_CUSTOM_AUDIENCE_REMOTE_INFO;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(buyer);
            Objects.requireNonNull(name);
            Objects.requireNonNull(biddingLogicJS);
            Objects.requireNonNull(trustedBiddingSignals);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INVALID_ARGUMENT, 0);
            // Rethrow to fail fast
            throw exception;
        }

        DevContext devContext = mDevContextFilter.createDevContext();

        if (!devContext.getDevOptionsEnabled()) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw new SecurityException(API_NOT_AUTHORIZED_MSG);
        }

        CustomAudienceDao customAudienceDao = mCustomAudienceImpl.getCustomAudienceDao();

        CustomAudienceOverrider overrider =
                new CustomAudienceOverrider(
                        devContext,
                        customAudienceDao,
                        mExecutorService,
                        mContext.getPackageManager(),
                        mConsentManager,
                        mAdServicesLogger,
                        mAppImportanceFilter,
                        mFlags);

        overrider.addOverride(
                owner,
                buyer,
                name,
                biddingLogicJS,
                biddingLogicJsVersion,
                trustedBiddingSignals,
                callback);
    }

    /**
     * Removes a custom audience override with the given information.
     *
     * @hide
     */
    @Override
    public void removeCustomAudienceRemoteInfoOverride(
            @NonNull String owner,
            @NonNull AdTechIdentifier buyer,
            @NonNull String name,
            @NonNull CustomAudienceOverrideCallback callback) {
        final int apiName =
                AD_SERVICES_API_CALLED__API_NAME__REMOVE_CUSTOM_AUDIENCE_REMOTE_INFO_OVERRIDE;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(owner);
            Objects.requireNonNull(buyer);
            Objects.requireNonNull(name);
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INVALID_ARGUMENT, 0);
            // Rethrow to fail fast
            throw exception;
        }

        DevContext devContext = mDevContextFilter.createDevContext();

        if (!devContext.getDevOptionsEnabled()) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw new SecurityException(API_NOT_AUTHORIZED_MSG);
        }

        CustomAudienceDao customAudienceDao = mCustomAudienceImpl.getCustomAudienceDao();

        CustomAudienceOverrider overrider =
                new CustomAudienceOverrider(
                        devContext,
                        customAudienceDao,
                        mExecutorService,
                        mContext.getPackageManager(),
                        mConsentManager,
                        mAdServicesLogger,
                        mAppImportanceFilter,
                        mFlags);

        overrider.removeOverride(owner, buyer, name, callback);
    }


    /**
     * Resets all custom audience overrides for a given caller.
     *
     * @hide
     */
    @Override
    public void resetAllCustomAudienceOverrides(@NonNull CustomAudienceOverrideCallback callback) {
        final int apiName = AD_SERVICES_API_CALLED__API_NAME__RESET_ALL_CUSTOM_AUDIENCE_OVERRIDES;

        // Caller permissions must be checked in the binder thread, before anything else
        mFledgeAuthorizationFilter.assertAppDeclaredPermission(mContext, apiName);

        try {
            Objects.requireNonNull(callback);
        } catch (NullPointerException exception) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INVALID_ARGUMENT, 0);
            // Rethrow to fail fast
            throw exception;
        }

        final int callerUid = getCallingUid(apiName);

        DevContext devContext = mDevContextFilter.createDevContext();

        if (!devContext.getDevOptionsEnabled()) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiName, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw new SecurityException(API_NOT_AUTHORIZED_MSG);
        }

        CustomAudienceDao customAudienceDao = mCustomAudienceImpl.getCustomAudienceDao();

        CustomAudienceOverrider overrider =
                new CustomAudienceOverrider(
                        devContext,
                        customAudienceDao,
                        mExecutorService,
                        mContext.getPackageManager(),
                        mConsentManager,
                        mAdServicesLogger,
                        mAppImportanceFilter,
                        mFlags);

        overrider.removeAllOverrides(callback, callerUid);
    }

    private int getCallingUid(int apiNameLoggingId) throws IllegalStateException {
        try {
            return mCallingAppUidSupplier.getCallingAppUid();
        } catch (IllegalStateException illegalStateException) {
            mAdServicesLogger.logFledgeApiCallStats(
                    apiNameLoggingId, AdServicesStatusUtils.STATUS_INTERNAL_ERROR, 0);
            throw illegalStateException;
        }
    }
}
