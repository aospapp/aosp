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

package com.android.adservices.service.common;

import static android.adservices.common.AdServicesStatusUtils.STATUS_CALLER_NOT_ALLOWED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_PERMISSION_NOT_REQUESTED;
import static android.adservices.common.AdServicesStatusUtils.STATUS_UNAUTHORIZED;

import android.adservices.common.AdServicesStatusUtils;
import android.adservices.common.AdTechIdentifier;
import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.android.adservices.LoggerFactory;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.PhFlags;
import com.android.adservices.service.enrollment.EnrollmentData;
import com.android.adservices.service.stats.AdServicesLogger;
import com.android.internal.annotations.VisibleForTesting;

import java.util.Objects;

/** Verify caller of FLEDGE API has the permission of performing certain behaviour. */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class FledgeAuthorizationFilter {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    @NonNull private final PackageManager mPackageManager;
    @NonNull private final EnrollmentDao mEnrollmentDao;
    @NonNull private final AdServicesLogger mAdServicesLogger;

    @VisibleForTesting
    public FledgeAuthorizationFilter(
            @NonNull PackageManager packageManager,
            @NonNull EnrollmentDao enrollmentDao,
            @NonNull AdServicesLogger adServicesLogger) {
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(enrollmentDao);
        Objects.requireNonNull(adServicesLogger);

        mPackageManager = packageManager;
        mEnrollmentDao = enrollmentDao;
        mAdServicesLogger = adServicesLogger;
    }

    /** Creates an instance of {@link FledgeAuthorizationFilter}. */
    public static FledgeAuthorizationFilter create(
            @NonNull Context context, @NonNull AdServicesLogger adServicesLogger) {
        Objects.requireNonNull(context);

        return new FledgeAuthorizationFilter(
                context.getPackageManager(), EnrollmentDao.getInstance(context), adServicesLogger);
    }

    /**
     * Check if the package name provided by the caller is one of the package of the calling uid.
     *
     * @param callingPackageName the caller-supplied package name
     * @param callingUid the uid get from the Binder
     * @param apiNameLoggingId the id of the api being called
     * @throws CallerMismatchException if the package name provided does not associate with the uid.
     */
    public void assertCallingPackageName(
            @NonNull String callingPackageName, int callingUid, int apiNameLoggingId)
            throws CallerMismatchException {
        Objects.requireNonNull(callingPackageName);

        sLogger.v(
                "Asserting package name \"%s\" is valid for uid %d",
                callingPackageName, callingUid);

        String[] packageNamesForUid = mPackageManager.getPackagesForUid(callingUid);
        for (String packageNameForUid : packageNamesForUid) {
            sLogger.v("Candidate package name \"%s\"", packageNameForUid);
            if (callingPackageName.equals(packageNameForUid)) return;
        }

        sLogger.v("No match found, failing calling package name match in API %d", apiNameLoggingId);
        mAdServicesLogger.logFledgeApiCallStats(apiNameLoggingId, STATUS_UNAUTHORIZED, 0);
        throw new CallerMismatchException();
    }

    /**
     * Check if the app had declared custom audience permission.
     *
     * @param context api service context
     * @param apiNameLoggingId the id of the api being called
     * @throws SecurityException if the package did not declare custom audience permission
     */
    public void assertAppDeclaredPermission(@NonNull Context context, int apiNameLoggingId)
            throws SecurityException {
        Objects.requireNonNull(context);
        if (!PermissionHelper.hasCustomAudiencesPermission(context)) {
            sLogger.v("Permission not declared by caller in API %d", apiNameLoggingId);
            mAdServicesLogger.logFledgeApiCallStats(
                    apiNameLoggingId, STATUS_PERMISSION_NOT_REQUESTED, 0);
            throw new SecurityException(
                    AdServicesStatusUtils
                            .SECURITY_EXCEPTION_PERMISSION_NOT_REQUESTED_ERROR_MESSAGE);
        }
    }

    /**
     * Check if a certain ad tech is enrolled and authorized to perform the operation for the
     * package.
     *
     * @param context api service context
     * @param appPackageName the package name to check against
     * @param adTechIdentifier the ad tech to check against
     * @param apiNameLoggingId the id of the api being called
     * @throws AdTechNotAllowedException if the ad tech is not authorized to perform the operation
     */
    public void assertAdTechAllowed(
            @NonNull Context context,
            @NonNull String appPackageName,
            @NonNull AdTechIdentifier adTechIdentifier,
            int apiNameLoggingId)
            throws AdTechNotAllowedException {
        Objects.requireNonNull(context);
        Objects.requireNonNull(appPackageName);
        Objects.requireNonNull(adTechIdentifier);

        EnrollmentData enrollmentData =
                mEnrollmentDao.getEnrollmentDataForFledgeByAdTechIdentifier(adTechIdentifier);

        if (enrollmentData == null) {
            sLogger.v(
                    "Enrollment data match not found for ad tech \"%s\" while calling API %d",
                    adTechIdentifier.toString(), apiNameLoggingId);
            mAdServicesLogger.logFledgeApiCallStats(apiNameLoggingId, STATUS_CALLER_NOT_ALLOWED, 0);
            throw new AdTechNotAllowedException();
        }

        if (!AppManifestConfigHelper.isAllowedCustomAudiencesAccess(
                context, appPackageName, enrollmentData.getEnrollmentId())) {
            sLogger.v(
                    "App package name \"%s\" with ad tech identifier \"%s\" not authorized to call"
                            + " API %d",
                    appPackageName, adTechIdentifier.toString(), apiNameLoggingId);
            mAdServicesLogger.logFledgeApiCallStats(apiNameLoggingId, STATUS_CALLER_NOT_ALLOWED, 0);
            throw new AdTechNotAllowedException();
        }

        // Check if enrollment is in blocklist.
        if (PhFlags.getInstance().isEnrollmentBlocklisted(enrollmentData.getEnrollmentId())) {
            sLogger.v(
                    "App package name \"%s\" with ad tech identifier \"%s\" not authorized to call"
                            + " API %d",
                    appPackageName, adTechIdentifier.toString(), apiNameLoggingId);
            mAdServicesLogger.logFledgeApiCallStats(apiNameLoggingId, STATUS_CALLER_NOT_ALLOWED, 0);
            throw new AdTechNotAllowedException();
        }
    }

    /**
     * Check if a certain ad tech is enrolled for FLEDGE.
     *
     * @param adTechIdentifier the ad tech to check against
     * @param apiNameLoggingId the id of the api being called
     * @throws AdTechNotAllowedException if the ad tech is not enrolled in FLEDGE
     */
    public void assertAdTechEnrolled(
            @NonNull AdTechIdentifier adTechIdentifier, int apiNameLoggingId)
            throws AdTechNotAllowedException {
        Objects.requireNonNull(adTechIdentifier);

        EnrollmentData enrollmentData =
                mEnrollmentDao.getEnrollmentDataForFledgeByAdTechIdentifier(adTechIdentifier);

        if (enrollmentData == null) {
            sLogger.v(
                    "Enrollment data match not found for ad tech \"%s\" while calling API %d",
                    adTechIdentifier.toString(), apiNameLoggingId);
            mAdServicesLogger.logFledgeApiCallStats(apiNameLoggingId, STATUS_CALLER_NOT_ALLOWED, 0);
            throw new AdTechNotAllowedException();
        }
    }

    /**
     * Internal exception for easy assertion catches specific to checking that a caller matches the
     * given package name.
     *
     * <p>This exception is not meant to be exposed externally and should not be passed outside of
     * the service.
     */
    public static class CallerMismatchException extends SecurityException {
        /**
         * Creates a {@link CallerMismatchException}, used in cases where a caller should match the
         * package name provided to the API.
         */
        public CallerMismatchException() {
            super(
                    AdServicesStatusUtils
                            .SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ON_BEHALF_ERROR_MESSAGE);
        }
    }

    /**
     * Internal exception for easy assertion catches specific to checking that an ad tech is allowed
     * to use the PP APIs.
     *
     * <p>This exception is not meant to be exposed externally and should not be passed outside of
     * the service.
     */
    public static class AdTechNotAllowedException extends SecurityException {
        /**
         * Creates a {@link AdTechNotAllowedException}, used in cases where an ad tech should have
         * been allowed to use the PP APIs.
         */
        public AdTechNotAllowedException() {
            super(AdServicesStatusUtils.SECURITY_EXCEPTION_CALLER_NOT_ALLOWED_ERROR_MESSAGE);
        }
    }
}
