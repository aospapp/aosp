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

import android.adservices.common.AdTechIdentifier;
import android.annotation.NonNull;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.util.Pair;

import com.android.adservices.LoggerFactory;
import com.android.adservices.concurrency.AdServicesExecutors;
import com.android.adservices.data.adselection.AppInstallDao;
import com.android.adservices.data.customaudience.CustomAudienceDao;
import com.android.adservices.data.customaudience.CustomAudienceStats;
import com.android.adservices.data.customaudience.DBCustomAudienceBackgroundFetchData;
import com.android.adservices.data.enrollment.EnrollmentDao;
import com.android.adservices.service.Flags;
import com.android.adservices.service.common.httpclient.AdServicesHttpsClient;

import com.google.common.util.concurrent.FluentFuture;

import java.io.IOException;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.CancellationException;

/** Runner executing actual background fetch tasks. */
public class BackgroundFetchRunner {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();
    private final CustomAudienceDao mCustomAudienceDao;
    private final AppInstallDao mAppInstallDao;
    private final PackageManager mPackageManager;
    private final EnrollmentDao mEnrollmentDao;
    private final Flags mFlags;
    private final AdServicesHttpsClient mHttpsClient;

    public BackgroundFetchRunner(
            @NonNull CustomAudienceDao customAudienceDao,
            @NonNull AppInstallDao appInstallDao,
            @NonNull PackageManager packageManager,
            @NonNull EnrollmentDao enrollmentDao,
            @NonNull Flags flags) {
        Objects.requireNonNull(customAudienceDao);
        Objects.requireNonNull(appInstallDao);
        Objects.requireNonNull(packageManager);
        Objects.requireNonNull(enrollmentDao);
        Objects.requireNonNull(flags);
        mCustomAudienceDao = customAudienceDao;
        mAppInstallDao = appInstallDao;
        mPackageManager = packageManager;
        mEnrollmentDao = enrollmentDao;
        mFlags = flags;
        mHttpsClient =
                new AdServicesHttpsClient(
                        AdServicesExecutors.getBlockingExecutor(),
                        flags.getFledgeBackgroundFetchNetworkConnectTimeoutMs(),
                        flags.getFledgeBackgroundFetchNetworkReadTimeoutMs(),
                        flags.getFledgeBackgroundFetchMaxResponseSizeB());
    }

    /**
     * Deletes custom audiences whose expiration timestamps have passed.
     *
     * <p>Also clears corresponding update information from the background fetch DB.
     */
    public void deleteExpiredCustomAudiences(@NonNull Instant jobStartTime) {
        Objects.requireNonNull(jobStartTime);

        sLogger.d("Starting expired custom audience garbage collection");
        int numCustomAudiencesDeleted =
                mCustomAudienceDao.deleteAllExpiredCustomAudienceData(jobStartTime);
        sLogger.d("Deleted %d expired custom audiences", numCustomAudiencesDeleted);
    }

    /**
     * Deletes custom audiences whose owner applications which are not installed or in the app
     * allowlist.
     *
     * <p>Also clears corresponding update information from the background fetch table.
     */
    public void deleteDisallowedOwnerCustomAudiences() {
        sLogger.d("Starting custom audience disallowed owner garbage collection");
        CustomAudienceStats deletedCAStats =
                mCustomAudienceDao.deleteAllDisallowedOwnerCustomAudienceData(
                        mPackageManager, mFlags);
        sLogger.d(
                "Deleted %d custom audiences belonging to %d disallowed owner apps",
                deletedCAStats.getTotalCustomAudienceCount(), deletedCAStats.getTotalOwnerCount());
    }

    /**
     * Deletes app install data whose packages are not installed or are not in the app allowlist.
     */
    public void deleteDisallowedPackageAppInstallEntries() {
        sLogger.d("Starting app install disallowed package garbage collection");
        int numDeleted = mAppInstallDao.deleteAllDisallowedPackageEntries(mPackageManager, mFlags);
        sLogger.d("Deleted %d app install entries", numDeleted);
    }

    /**
     * Deletes custom audiences whose buyer ad techs which are not enrolled to use FLEDGE.
     *
     * <p>Also clears corresponding update information from the background fetch table.
     */
    public void deleteDisallowedBuyerCustomAudiences() {
        sLogger.d("Starting custom audience disallowed buyer garbage collection");
        CustomAudienceStats deletedCAStats =
                mCustomAudienceDao.deleteAllDisallowedBuyerCustomAudienceData(
                        mEnrollmentDao, mFlags);
        sLogger.d(
                "Deleted %d custom audiences belonging to %d disallowed buyer ad techs",
                deletedCAStats.getTotalCustomAudienceCount(), deletedCAStats.getTotalBuyerCount());
    }

    /** Updates a single given custom audience and persists the results. */
    public FluentFuture<?> updateCustomAudience(
            @NonNull Instant jobStartTime,
            @NonNull final DBCustomAudienceBackgroundFetchData fetchData) {
        Objects.requireNonNull(jobStartTime);
        Objects.requireNonNull(fetchData);

        return fetchAndValidateCustomAudienceUpdatableData(
                        jobStartTime, fetchData.getBuyer(), fetchData.getDailyUpdateUri())
                .transform(
                        updatableData -> {
                            DBCustomAudienceBackgroundFetchData updatedData =
                                    fetchData.copyWithUpdatableData(updatableData);

                            if (updatableData.getContainsSuccessfulUpdate()) {
                                mCustomAudienceDao.updateCustomAudienceAndBackgroundFetchData(
                                        updatedData, updatableData);
                            } else {
                                // In a failed update, we don't need to update the main CA table, so
                                // only update the background fetch table
                                mCustomAudienceDao.persistCustomAudienceBackgroundFetchData(
                                        updatedData);
                            }

                            return null;
                        },
                        AdServicesExecutors.getBackgroundExecutor());
    }

    /**
     * Fetches the custom audience update from the given daily update URI and validates the response
     * in a {@link CustomAudienceUpdatableData} object.
     */
    @NonNull
    public FluentFuture<CustomAudienceUpdatableData> fetchAndValidateCustomAudienceUpdatableData(
            @NonNull Instant jobStartTime,
            @NonNull AdTechIdentifier buyer,
            @NonNull Uri dailyFetchUri) {
        Objects.requireNonNull(jobStartTime);
        Objects.requireNonNull(buyer);
        Objects.requireNonNull(dailyFetchUri);

        // TODO(b/234884352): Perform k-anon check on daily fetch URI
        return FluentFuture.from(mHttpsClient.fetchPayload(dailyFetchUri))
                .transform(
                        updateResponse ->
                                Pair.create(
                                        UpdateResultType.SUCCESS, updateResponse.getResponseBody()),
                        AdServicesExecutors.getBackgroundExecutor())
                .catching(
                        Throwable.class,
                        t -> handleThrowable(t, dailyFetchUri),
                        AdServicesExecutors.getBackgroundExecutor())
                .transform(
                        fetchResultAndResponse ->
                                CustomAudienceUpdatableData.createFromResponseString(
                                        jobStartTime,
                                        buyer,
                                        fetchResultAndResponse.first,
                                        fetchResultAndResponse.second,
                                        mFlags),
                        AdServicesExecutors.getBackgroundExecutor());
    }

    private Pair<UpdateResultType, String> handleThrowable(
            Throwable t, @NonNull Uri dailyFetchUri) {
        if (t instanceof IOException) {
            // TODO(b/237342352): Investigate separating connect and read timeouts
            sLogger.e(
                    t,
                    "Timed out while fetching custom audience update from %s",
                    dailyFetchUri.toSafeString());
            return Pair.create(UpdateResultType.NETWORK_FAILURE, "{}");
        }
        if (t instanceof CancellationException) {
            sLogger.e(
                    t,
                    "Custom audience update cancelled while fetching from %s",
                    dailyFetchUri.toSafeString());
            return Pair.create(UpdateResultType.UNKNOWN, "{}");
        }

        sLogger.e(
                t,
                "Encountered unexpected error while fetching custom audience update from" + " %s",
                dailyFetchUri.toSafeString());
        return Pair.create(UpdateResultType.UNKNOWN, "{}");
    }

    /** Represents the result of an update attempt prior to parsing the update response. */
    public enum UpdateResultType {
        SUCCESS,
        UNKNOWN,
        K_ANON_FAILURE,
        // TODO(b/237342352): Consolidate if we don't need to distinguish network timeouts
        NETWORK_FAILURE,
        NETWORK_READ_TIMEOUT_FAILURE,
        RESPONSE_VALIDATION_FAILURE
    }
}
