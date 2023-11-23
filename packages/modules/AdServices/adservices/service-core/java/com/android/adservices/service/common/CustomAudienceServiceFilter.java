/*
 * Copyright (C) 2023 The Android Open Source Project
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

import android.adservices.common.AdTechIdentifier;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.content.Context;
import android.os.Build;
import android.os.LimitExceededException;

import androidx.annotation.RequiresApi;

import com.android.adservices.LoggerFactory;
import com.android.adservices.service.Flags;
import com.android.adservices.service.consent.ConsentManager;

import java.util.Objects;
import java.util.function.Supplier;

/** Composite filter for CustomAudienceService request. */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class CustomAudienceServiceFilter extends AbstractFledgeServiceFilter {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();

    public CustomAudienceServiceFilter(
            @NonNull Context context,
            @NonNull ConsentManager consentManager,
            @NonNull Flags flags,
            @NonNull AppImportanceFilter appImportanceFilter,
            @NonNull FledgeAuthorizationFilter fledgeAuthorizationFilter,
            @NonNull FledgeAllowListsFilter fledgeAllowListsFilter,
            @NonNull Supplier<Throttler> throttlerSupplier) {
        super(
                context,
                consentManager,
                flags,
                appImportanceFilter,
                fledgeAuthorizationFilter,
                fledgeAllowListsFilter,
                throttlerSupplier);
    }

    /**
     * Composite filter for FLEDGE's CustomAudience-specific requests.
     *
     * @param adTech the ad tech associated with the request. If null, the enrollment check will be
     *     skipped.
     * @param callerPackageName caller package name to be validated
     * @param enforceForeground whether to enforce a foreground check
     * @param enforceConsent currently unused in CustomAudienceServiceFilter
     * @param callerUid caller's uid from the Binder thread
     * @param apiName the id of the api being called
     * @param apiKey api-specific throttler key
     * @throws FledgeAuthorizationFilter.CallerMismatchException if the {@code callerPackageName} is
     *     not valid
     * @throws AppImportanceFilter.WrongCallingApplicationStateException if the foreground check is
     *     enabled and fails
     * @throws FledgeAuthorizationFilter.AdTechNotAllowedException if the ad tech is not authorized
     *     to perform the operation
     * @throws FledgeAllowListsFilter.AppNotAllowedException if the package is not authorized.
     * @throws LimitExceededException if the provided {@code callerPackageName} exceeds the rate
     *     limits
     */
    @Override
    public void filterRequest(
            @Nullable AdTechIdentifier adTech,
            @NonNull String callerPackageName,
            boolean enforceForeground,
            boolean enforceConsent,
            int callerUid,
            int apiName,
            @NonNull Throttler.ApiKey apiKey) {
        Objects.requireNonNull(callerPackageName);
        Objects.requireNonNull(apiKey);

        sLogger.v("Validating caller package name.");
        assertCallerPackageName(callerPackageName, callerUid, apiName);

        sLogger.v("Validating API is not throttled.");
        assertCallerNotThrottled(callerPackageName, apiKey);

        if (enforceForeground) {
            sLogger.v("Checking caller is in foreground.");
            assertForegroundCaller(callerUid, apiName);
        }
        if (!Objects.isNull(adTech)) {
            sLogger.v("Checking ad tech is allowed to use FLEDGE.");
            assertFledgeEnrollment(adTech, callerPackageName, apiName);
        }

        sLogger.v("Validating caller package is in allow list.");
        assertAppInAllowList(callerPackageName, apiName);
    }
}
