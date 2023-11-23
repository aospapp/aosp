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
package com.android.adservices.service.topics;

import static android.adservices.common.AdServicesStatusUtils.STATUS_BACKGROUND_CALLER;
import static android.adservices.common.AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_INTERNAL_ERROR;
import static android.adservices.common.AdServicesStatusUtils.STATUS_PERMISSION_NOT_REQUESTED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_RATE_LIMIT_REACHED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_SUCCESS;
import static android.adservices.common.AdServicesStatusUtils.STATUS_UNAUTHORIZED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_USER_CONSENT_REVOKED;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_CLASS__TARGETING;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS_PREVIEW_API;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__API_CALLBACK_ERROR;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__PACKAGE_NAME_NOT_FOUND_EXCEPTION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__ERROR_CODE__RATE_LIMIT_CALLBACK_FAILURE;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS;

import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.CallerMetadata;
import android.adservices.topics.GetTopicsParam;
import android.adservices.topics.IGetTopicsCallback;
import android.adservices.topics.ITopicsService;
import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.Build;
import android.os.RemoteException;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import com.android.adservices.LoggerFactory;
import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.errorlogging.ErrorLogUtil;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.AllowLists;
import com.android.adservices.service.common.AppImportanceFilter;
import com.android.adservices.service.common.AppImportanceFilter.WrongCallingApplicationStateException;
import com.android.adservices.service.common.AppManifestConfigHelper;
import com.android.adservices.service.common.PermissionHelper;
import com.android.adservices.service.common.SdkRuntimeUtil;
import com.android.adservices.service.common.Throttler;
import com.android.adservices.service.common.compat.ProcessCompatUtils;
import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.adservices.service.stats.AdServicesStatsLog;
import com.android.adservices.service.stats.ApiCallStats;
import com.android.adservices.service.stats.Clock;

import java.util.concurrent.Executor;

/**
 * Implementation of {@link ITopicsService}.
 *
 * @hide
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class TopicsServiceImpl extends ITopicsService.Stub {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getTopicsLogger();
    private static final Executor sBackgroundExecutor = AdServicesExecutors.getBackgroundExecutor();
    private final Context mContext;
    private final TopicsWorker mTopicsWorker;
    private final AdServicesLogger mAdServicesLogger;
    private final ConsentManager mConsentManager;
    private final Clock mClock;
    private final Flags mFlags;
    private final Throttler mThrottler;
    private final EnrollmentDao mEnrollmentDao;
    private final AppImportanceFilter mAppImportanceFilter;

    public TopicsServiceImpl(
            Context context,
            TopicsWorker topicsWorker,
            ConsentManager consentManager,
            AdServicesLogger adServicesLogger,
            Clock clock,
            Flags flags,
            Throttler throttler,
            EnrollmentDao enrollmentDao,
            AppImportanceFilter appImportanceFilter) {
        mContext = context;
        mTopicsWorker = topicsWorker;
        mConsentManager = consentManager;
        mAdServicesLogger = adServicesLogger;
        mClock = clock;
        mFlags = flags;
        mThrottler = throttler;
        mEnrollmentDao = enrollmentDao;
        mAppImportanceFilter = appImportanceFilter;
    }

    @Override
    public void getTopics(
            @NonNull GetTopicsParam topicsParam,
            @NonNull CallerMetadata callerMetadata,
            @NonNull IGetTopicsCallback callback) {

        if (isThrottled(topicsParam, callback)) return;

        final long startServiceTime = mClock.elapsedRealtime();
        // TODO(b/236380919): Verify that the passed App PackageName belongs to the caller uid
        final String packageName = topicsParam.getAppPackageName();
        final String sdkName = topicsParam.getSdkName();
        final String sdkPackageName = topicsParam.getSdkPackageName();

        // We need to save the Calling Uid before offloading to the background executor. Otherwise,
        // the Binder.getCallingUid will return the PPAPI process Uid. This also needs to be final
        // since it's used in the lambda.
        final int callingUid = Binder.getCallingUidOrThrow();

        // Check the permission in the same thread since we're looking for caller's permissions.
        // Note: The permission check uses sdk package name since PackageManager checks if the
        // permission is declared in the manifest of that package name.
        boolean hasTopicsPermission =
                PermissionHelper.hasTopicsPermission(
                        mContext, ProcessCompatUtils.isSdkSandboxUid(callingUid), sdkPackageName);

        sBackgroundExecutor.execute(
                () -> {
                    int resultCode = STATUS_SUCCESS;

                    try {
                        resultCode =
                                canCallerInvokeTopicsService(
                                        hasTopicsPermission, topicsParam, callingUid, callback);
                        if (resultCode != STATUS_SUCCESS) {
                            return;
                        }

                        // Record usage before returning the results.
                        if (topicsParam.shouldRecordObservation()) {
                            mTopicsWorker.recordUsage(
                                    topicsParam.getAppPackageName(), topicsParam.getSdkName());
                        }

                        callback.onResult(mTopicsWorker.getTopics(packageName, sdkName));
                    } catch (RemoteException e) {
                        sLogger.e(e, "Unable to send result to the callback");
                        ErrorLogUtil.e(
                                e,
                                AD_SERVICES_ERROR_REPORTED__ERROR_CODE__API_CALLBACK_ERROR,
                                AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS);
                        resultCode = STATUS_INTERNAL_ERROR;
                    } finally {
                        long binderCallStartTimeMillis = callerMetadata.getBinderElapsedTimestamp();
                        long serviceLatency = mClock.elapsedRealtime() - startServiceTime;
                        // Double it to simulate the return binder time is same to call binder time
                        long binderLatency = (startServiceTime - binderCallStartTimeMillis) * 2;

                        final int apiLatency = (int) (serviceLatency + binderLatency);
                        final int apiName =
                                topicsParam.shouldRecordObservation()
                                        ? AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS
                                        : AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS_PREVIEW_API;
                        mAdServicesLogger.logApiCallStats(
                                new ApiCallStats.Builder()
                                        .setCode(AdServicesStatsLog.AD_SERVICES_API_CALLED)
                                        .setApiClass(AD_SERVICES_API_CALLED__API_CLASS__TARGETING)
                                        .setApiName(apiName)
                                        .setAppPackageName(packageName)
                                        .setSdkPackageName(sdkName)
                                        .setLatencyMillisecond(apiLatency)
                                        .setResultCode(resultCode)
                                        .build());
                    }
                });
    }

    // Throttle the Topics API.
    // Return true if we should throttle (don't allow the API call).
    private boolean isThrottled(GetTopicsParam topicsParam, IGetTopicsCallback callback) {
        // There are 2 cases for throttling:
        // Case 1: the App calls Topics API directly, not via an SDK. In this case,
        // the SdkName == Empty
        // Case 2: the SDK calls Topics API.
        boolean throttled =
                TextUtils.isEmpty(topicsParam.getSdkName())
                        ? !mThrottler.tryAcquire(
                                Throttler.ApiKey.TOPICS_API_APP_PACKAGE_NAME,
                                topicsParam.getAppPackageName())
                        : !mThrottler.tryAcquire(
                                Throttler.ApiKey.TOPICS_API_SDK_NAME, topicsParam.getSdkName());

        if (throttled) {
            sLogger.e("Rate Limit Reached for TOPICS_API");
            try {
                callback.onFailure(STATUS_RATE_LIMIT_REACHED);
            } catch (RemoteException e) {
                sLogger.e(e, "Fail to call the callback on Rate Limit Reached.");
                ErrorLogUtil.e(
                        e,
                        AD_SERVICES_ERROR_REPORTED__ERROR_CODE__RATE_LIMIT_CALLBACK_FAILURE,
                        AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS);
            }
            return true;
        }
        return false;
    }

    // Enforce whether caller is from foreground.
    private void enforceForeground(int callingUid, @NonNull String sdkName) {
        // If caller calls Topics API from Sandbox, regard it as foreground.
        // Also enable a flag to force switch on/off this enforcing.
        if (ProcessCompatUtils.isSdkSandboxUid(callingUid)
                || !mFlags.getEnforceForegroundStatusForTopics()) {
            return;
        }

        // Call utility method in AppImportanceFilter to enforce foreground status
        //  Throw WrongCallingApplicationStateException  if the assertion fails.
        mAppImportanceFilter.assertCallerIsInForeground(
                callingUid, AD_SERVICES_API_CALLED__API_NAME__GET_TOPICS, sdkName);
    }

    /**
     * Check whether caller can invoke the Topics API. The caller is not allowed to do it when one
     * of the following occurs:
     *
     * <ul>
     *   <li>Permission was not requested.
     *   <li>Caller is not allowed - not present in the allowed list.
     *   <li>User consent was revoked.
     * </ul>
     *
     * @param sufficientPermission boolean which tells whether caller has sufficient permissions.
     * @param topicsParam {@link GetTopicsParam} to get information about the request.
     * @param callback {@link IGetTopicsCallback} to invoke when caller is not allowed.
     * @return API response status code.
     */
    private int canCallerInvokeTopicsService(
            boolean sufficientPermission,
            GetTopicsParam topicsParam,
            int callingUid,
            IGetTopicsCallback callback) {
        // Enforce caller calls Topics API from foreground
        try {
            enforceForeground(callingUid, topicsParam.getSdkName());
        } catch (WrongCallingApplicationStateException backgroundCaller) {
            invokeCallbackWithStatus(
                    callback, STATUS_BACKGROUND_CALLER, backgroundCaller.getMessage());
            return STATUS_BACKGROUND_CALLER;
        }

        if (!sufficientPermission) {
            invokeCallbackWithStatus(
                    callback,
                    STATUS_PERMISSION_NOT_REQUESTED,
                    "Unauthorized caller. Permission not requested.");
            return STATUS_PERMISSION_NOT_REQUESTED;
        }

        // This needs to access PhFlag which requires READ_DEVICE_CONFIG which
        // is not granted for binder thread. So we have to check it with one
        // of non-binder thread of the PPAPI.
        if (!AllowLists.isSignatureAllowListed(
                mContext,
                mFlags.getPpapiAppSignatureAllowList(),
                topicsParam.getAppPackageName())) {
            invokeCallbackWithStatus(
                    callback,
                    STATUS_CALLER_NOT_ALLOWED,
                    "Unauthorized caller. Signatures for calling package not allowed.");
            return STATUS_CALLER_NOT_ALLOWED;
        }

        // Check whether calling package belongs to the callingUid
        int resultCode =
                enforceCallingPackageBelongsToUid(topicsParam.getAppPackageName(), callingUid);
        if (resultCode != STATUS_SUCCESS) {
            invokeCallbackWithStatus(callback, resultCode, "Caller is not authorized.");
            return resultCode;
        }

        AdServicesApiConsent userConsent;
        if (mFlags.getGaUxFeatureEnabled()) {
            userConsent = mConsentManager.getConsent(AdServicesApiType.TOPICS);
        } else {
            userConsent = mConsentManager.getConsent();
        }

        if (!userConsent.isGiven()) {
            invokeCallbackWithStatus(
                    callback, STATUS_USER_CONSENT_REVOKED, "User consent revoked.");
            return STATUS_USER_CONSENT_REVOKED;
        }

        // The app developer declares which SDKs they would like to allow Topics
        // access to use the enrollment ID. Get the enrollment ID for this SDK and
        // check that against the app's manifest.
        if (!mFlags.isDisableTopicsEnrollmentCheck() && !topicsParam.getSdkName().isEmpty()) {
            EnrollmentData enrollmentData =
                    mEnrollmentDao.getEnrollmentDataFromSdkName(topicsParam.getSdkName());
            boolean permitted =
                    (enrollmentData != null && enrollmentData.getEnrollmentId() != null)
                            && AppManifestConfigHelper.isAllowedTopicsAccess(
                                    mContext,
                                    ProcessCompatUtils.isSdkSandboxUid(callingUid),
                                    topicsParam.getAppPackageName(),
                                    enrollmentData.getEnrollmentId())
                            && !mFlags.isEnrollmentBlocklisted(enrollmentData.getEnrollmentId());

            if (!permitted) {
                invokeCallbackWithStatus(
                        callback, STATUS_CALLER_NOT_ALLOWED, "Caller is not authorized.");
                return STATUS_CALLER_NOT_ALLOWED;
            }
        }

        return STATUS_SUCCESS;
    }

    private void invokeCallbackWithStatus(
            IGetTopicsCallback callback,
            @AdServicesStatusUtils.StatusCode int statusCode,
            String message) {
        sLogger.e(message);
        try {
            callback.onFailure(statusCode);
        } catch (RemoteException e) {
            sLogger.e(e, String.format("Fail to call the callback. %s", message));
            ErrorLogUtil.e(
                    e,
                    AD_SERVICES_ERROR_REPORTED__ERROR_CODE__API_CALLBACK_ERROR,
                    AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS);
        }
    }

    // Enforce that the callingPackage has the callingUid.
    private int enforceCallingPackageBelongsToUid(String callingPackage, int callingUid) {
        int appCallingUid = SdkRuntimeUtil.getCallingAppUid(callingUid);
        int packageUid;
        try {
            packageUid = mContext.getPackageManager().getPackageUid(callingPackage, /* flags */ 0);
        } catch (PackageManager.NameNotFoundException e) {
            sLogger.e(e, callingPackage + " not found");
            ErrorLogUtil.e(
                    e,
                    AD_SERVICES_ERROR_REPORTED__ERROR_CODE__PACKAGE_NAME_NOT_FOUND_EXCEPTION,
                    AD_SERVICES_ERROR_REPORTED__PPAPI_NAME__TOPICS);
            return STATUS_UNAUTHORIZED;
        }
        if (packageUid != appCallingUid) {
            sLogger.e(callingPackage + " does not belong to uid " + callingUid);
            return STATUS_UNAUTHORIZED;
        }
        return STATUS_SUCCESS;
    }

    /** Init the Topics Service. */
    public void init() {
        // This is to prevent cold-start latency on getTopics API.
        // Load cache when the service is created.
        // The recommended pattern is:
        // 1) In app startup, wake up the TopicsService.
        // 2) The TopicsService will load the Topics Cache from DB into memory.
        // 3) Later, when the app calls Topics API, the returned Topics will be served
        // from
        // Cache in memory.
        sBackgroundExecutor.execute(mTopicsWorker::loadCache);
    }
}
