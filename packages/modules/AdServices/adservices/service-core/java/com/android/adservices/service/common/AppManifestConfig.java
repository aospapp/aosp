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

import android.annotation.NonNull;


/** The object representing the AdServices manifest config. */
public class AppManifestConfig {
    private final AppManifestIncludesSdkLibraryConfig mIncludesSdkLibraryConfig;
    private final AppManifestAttributionConfig mAttributionConfig;
    private final AppManifestCustomAudiencesConfig mCustomAudiencesConfig;
    private final AppManifestTopicsConfig mTopicsConfig;
    private final AppManifestAdIdConfig mAdIdConfig;
    private final AppManifestAppSetIdConfig mAppSetIdConfig;

    /**
     * AdServices manifest config must contain configs for Attribution, Custom Audiences, AdId,
     * AppSetId and Topics.
     *
     * @param includesSdkLibraryConfig the list of Sdk Libraries included in the app.
     * @param attributionConfig the config for Attribution.
     * @param customAudiencesConfig the config for Custom Audiences.
     * @param topicsConfig the config for Topics.
     * @param adIdConfig the config for adId.
     * @param appSetIdConfig the config for appSetId.
     */
    public AppManifestConfig(
            @NonNull AppManifestIncludesSdkLibraryConfig includesSdkLibraryConfig,
            @NonNull AppManifestAttributionConfig attributionConfig,
            @NonNull AppManifestCustomAudiencesConfig customAudiencesConfig,
            @NonNull AppManifestTopicsConfig topicsConfig,
            @NonNull AppManifestAdIdConfig adIdConfig,
            @NonNull AppManifestAppSetIdConfig appSetIdConfig) {
        mIncludesSdkLibraryConfig = includesSdkLibraryConfig;
        mAttributionConfig = attributionConfig;
        mCustomAudiencesConfig = customAudiencesConfig;
        mTopicsConfig = topicsConfig;
        mAdIdConfig = adIdConfig;
        mAppSetIdConfig = appSetIdConfig;
    }

    /** Getter for IncludesSdkLibraryConfig. */
    @NonNull
    public AppManifestIncludesSdkLibraryConfig getIncludesSdkLibraryConfig() {
        return mIncludesSdkLibraryConfig;
    }

    /** Getter for AttributionConfig. */
    @NonNull
    public AppManifestAttributionConfig getAttributionConfig() {
        return mAttributionConfig;
    }

    /**
     * Returns if the ad partner is permitted to access Attribution API for config represented by
     * this object.
     */
    public boolean isAllowedAttributionAccess(@NonNull String enrollmentId) {
        return mAttributionConfig.getAllowAllToAccess()
                || mAttributionConfig.getAllowAdPartnersToAccess().contains(enrollmentId);
    }

    /** Getter for CustomAudiencesConfig. */
    @NonNull
    public AppManifestCustomAudiencesConfig getCustomAudiencesConfig() {
        return mCustomAudiencesConfig;
    }

    /**
     * Returns {@code true} if an ad tech with the given enrollment ID is permitted to access Custom
     * Audience API for config represented by this object.
     */
    @NonNull
    public boolean isAllowedCustomAudiencesAccess(@NonNull String enrollmentId) {
        return mCustomAudiencesConfig.getAllowAllToAccess()
                || mCustomAudiencesConfig.getAllowAdPartnersToAccess().contains(enrollmentId);
    }

    /** Getter for TopicsConfig. */
    @NonNull
    public AppManifestTopicsConfig getTopicsConfig() {
        return mTopicsConfig;
    }

    /**
     * Returns if the ad partner is permitted to access Topics API for config represented by this
     * object.
     */
    public boolean isAllowedTopicsAccess(@NonNull String enrollmentId) {
        return mTopicsConfig.getAllowAllToAccess()
                || mTopicsConfig.getAllowAdPartnersToAccess().contains(enrollmentId);
    }

    /** Getter for AdIdConfig. */
    @NonNull
    public AppManifestAdIdConfig getAdIdConfig() {
        return mAdIdConfig;
    }

    /** Returns if sdk is permitted to access AdId API for config represented by this object. */
    @NonNull
    public boolean isAllowedAdIdAccess(@NonNull String sdk) {
        return mAdIdConfig.getAllowAllToAccess()
                || mAdIdConfig.getAllowAdPartnersToAccess().contains(sdk);
    }

    /** Getter for AppSetIdConfig. */
    @NonNull
    public AppManifestAppSetIdConfig getAppSetIdConfig() {
        return mAppSetIdConfig;
    }

    /** Returns if sdk is permitted to access AppSetId API for config represented by this object. */
    @NonNull
    public boolean isAllowedAppSetIdAccess(@NonNull String sdk) {
        return mAppSetIdConfig.getAllowAllToAccess()
                || mAppSetIdConfig.getAllowAdPartnersToAccess().contains(sdk);
    }
}
