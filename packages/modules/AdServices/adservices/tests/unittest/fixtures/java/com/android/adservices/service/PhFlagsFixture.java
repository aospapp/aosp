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

package com.android.adservices.service;

import static com.android.adservices.service.Flags.FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_CA_MS;
import static com.android.adservices.service.Flags.FLEDGE_AD_SELECTION_FROM_OUTCOMES_OVERALL_TIMEOUT_MS;
import static com.android.adservices.service.Flags.FLEDGE_AD_SELECTION_OVERALL_TIMEOUT_MS;
import static com.android.adservices.service.Flags.FLEDGE_AD_SELECTION_SCORING_TIMEOUT_MS;
import static com.android.adservices.service.Flags.FLEDGE_AD_SELECTION_SELECTING_OUTCOME_TIMEOUT_MS;
import static com.android.adservices.service.Flags.FLEDGE_BACKGROUND_FETCH_NETWORK_CONNECT_TIMEOUT_MS;
import static com.android.adservices.service.Flags.FLEDGE_BACKGROUND_FETCH_NETWORK_READ_TIMEOUT_MS;
import static com.android.adservices.service.Flags.FLEDGE_REPORT_IMPRESSION_OVERALL_TIMEOUT_MS;
import static com.android.adservices.service.Flags.SDK_REQUEST_PERMITS_PER_SECOND;
import static com.android.adservices.service.PhFlags.KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_OVERRIDE;
import static com.android.adservices.service.PhFlags.KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_IMPRESSION;
import static com.android.adservices.service.PhFlags.KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_INTERACTION;
import static com.android.adservices.service.PhFlags.KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_RUN_AD_SELECTION;
import static com.android.adservices.service.PhFlags.KEY_FLEDGE_AD_SELECTION_FILTERING_ENABLED;
import static com.android.adservices.service.PhFlags.KEY_FLEDGE_AD_SELECTION_PREBUILT_URI_ENABLED;
import static com.android.adservices.service.PhFlags.KEY_FLEDGE_BACKGROUND_FETCH_ELIGIBLE_UPDATE_BASE_INTERVAL_S;
import static com.android.adservices.service.PhFlags.KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_COUNT;
import static com.android.adservices.service.PhFlags.KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_NUM_ADS;
import static com.android.adservices.service.PhFlags.KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_OWNER_COUNT;
import static com.android.adservices.service.PhFlags.KEY_FLEDGE_CUSTOM_AUDIENCE_PER_APP_MAX_COUNT;
import static com.android.adservices.service.PhFlags.KEY_FLEDGE_REGISTER_AD_BEACON_ENABLED;
import static com.android.adservices.service.PhFlags.KEY_SDK_REQUEST_PERMITS_PER_SECOND;

import static org.junit.Assert.assertEquals;

import android.provider.DeviceConfig;

/**
 * In order to use this test fixture, make sure your test class includes a TestableDeviceConfigRule
 * Rule or adopts shell permissions as below.
 *
 * <p>{@code @Rule public final TestableDeviceConfig.TestableDeviceConfigRule mDeviceConfigRule =
 * new TestableDeviceConfig.TestableDeviceConfigRule(); }
 *
 * <p>OR
 *
 * <p>{@code
 * InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
 * Manifest.permission.WRITE_DEVICE_CONFIG);}
 */
public class PhFlagsFixture {
    public static final long DEFAULT_API_RATE_LIMIT_SLEEP_MS =
            (long) (1500 / SDK_REQUEST_PERMITS_PER_SECOND) + 100L;

    // TODO(b/273656890): Investigate dynamic timeouts for device types
    public static final long ADDITIONAL_TIMEOUT = 3_000L;
    public static final long EXTENDED_FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_CA_MS =
            FLEDGE_AD_SELECTION_BIDDING_TIMEOUT_PER_CA_MS + ADDITIONAL_TIMEOUT;
    public static final long EXTENDED_FLEDGE_AD_SELECTION_SCORING_TIMEOUT_MS =
            FLEDGE_AD_SELECTION_SCORING_TIMEOUT_MS + ADDITIONAL_TIMEOUT;
    public static final long EXTENDED_FLEDGE_AD_SELECTION_SELECTING_OUTCOME_TIMEOUT_MS =
            FLEDGE_AD_SELECTION_SELECTING_OUTCOME_TIMEOUT_MS + ADDITIONAL_TIMEOUT;
    public static final long EXTENDED_FLEDGE_AD_SELECTION_OVERALL_TIMEOUT_MS =
            FLEDGE_AD_SELECTION_OVERALL_TIMEOUT_MS + ADDITIONAL_TIMEOUT;
    public static final long EXTENDED_FLEDGE_AD_SELECTION_FROM_OUTCOMES_OVERALL_TIMEOUT_MS =
            FLEDGE_AD_SELECTION_FROM_OUTCOMES_OVERALL_TIMEOUT_MS + ADDITIONAL_TIMEOUT;
    public static final long EXTENDED_FLEDGE_REPORT_IMPRESSION_OVERALL_TIMEOUT_MS =
            FLEDGE_REPORT_IMPRESSION_OVERALL_TIMEOUT_MS + ADDITIONAL_TIMEOUT;
    public static final int EXTENDED_FLEDGE_BACKGROUND_FETCH_NETWORK_READ_TIMEOUT_MS =
            FLEDGE_BACKGROUND_FETCH_NETWORK_READ_TIMEOUT_MS + (int) ADDITIONAL_TIMEOUT;
    public static final int EXTENDED_FLEDGE_BACKGROUND_FETCH_NETWORK_CONNECT_TIMEOUT_MS =
            FLEDGE_BACKGROUND_FETCH_NETWORK_CONNECT_TIMEOUT_MS + (int) ADDITIONAL_TIMEOUT;

    public static void configureFledgeBackgroundFetchEligibleUpdateBaseIntervalS(
            final long phOverridingValue) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_FLEDGE_BACKGROUND_FETCH_ELIGIBLE_UPDATE_BASE_INTERVAL_S,
                Long.toString(phOverridingValue),
                /* makeDefault */ false);

        Flags phFlags = FlagsFactory.getFlags();
        assertEquals(
                "Failed to configure P/H flag",
                phOverridingValue,
                phFlags.getFledgeBackgroundFetchEligibleUpdateBaseIntervalS());
    }

    /** Enables test to override the flag enabling ad selection filtering */
    public static void overrideFledgeAdSelectionFilteringEnabled(boolean value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_FLEDGE_AD_SELECTION_FILTERING_ENABLED,
                Boolean.toString(value),
                false);
    }

    /**
     * Enables test to override the flag enabling the foreground status check for callers of the
     * Fledge Run Ad Selection API.
     */
    public static void overrideForegroundStatusForFledgeRunAdSelection(boolean value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_RUN_AD_SELECTION,
                Boolean.toString(value),
                false);
    }

    /**
     * Enables test to override the flag enabling the foreground status check for callers of the
     * Fledge Report Impression API.
     */
    public static void overrideForegroundStatusForFledgeReportImpression(boolean value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_IMPRESSION,
                Boolean.toString(value),
                false);
    }

    /**
     * Enables test to override the flag enabling the foreground status check for callers of the
     * Fledge Report Interaction API.
     */
    public static void overrideForegroundStatusForFledgeReportInteraction(boolean value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_REPORT_INTERACTION,
                Boolean.toString(value),
                false);
    }

    /**
     * Enables test to override the flag enabling the foreground status check for callers of the
     * Fledge Override API.
     */
    public static void overrideForegroundStatusForFledgeOverrides(boolean value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_OVERRIDE,
                Boolean.toString(value),
                false);
    }

    /**
     * Enables test to override the flag enabling the foreground status check for callers of the
     * Custom Audience API.
     */
    public static void overrideForegroundStatusForFledgeCustomAudience(boolean value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                PhFlags.KEY_ENFORCE_FOREGROUND_STATUS_FLEDGE_CUSTOM_AUDIENCE,
                Boolean.toString(value),
                false);
    }

    /**
     * Allows tests to override seed enrollment data flag thereby seeding data into enrollment table
     *
     * @param enable disable enrollment seed
     */
    public static void overrideEnableEnrollmentSeed(boolean enable) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                PhFlags.KEY_ENABLE_ENROLLMENT_TEST_SEED,
                Boolean.toString(enable),
                false);
    }

    /**
     * Enables test to override the flag enabling the enrollment check for callers of Fledge APIs.
     *
     * @param enable whether enable or disable the check
     */
    public static void overrideFledgeEnrollmentCheck(boolean enable) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                PhFlags.KEY_DISABLE_FLEDGE_ENROLLMENT_CHECK,
                Boolean.toString(!enable),
                false);
    }

    public static void overrideEnforceIsolateMaxHeapSize(boolean value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                PhFlags.KEY_ENFORCE_ISOLATE_MAX_HEAP_SIZE,
                Boolean.toString(value),
                false);
    }

    public static void overrideIsolateMaxHeapSizeBytes(long value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                PhFlags.KEY_ISOLATE_MAX_HEAP_SIZE_BYTES,
                Long.toString(value),
                false);
    }

    public static void overrideSdkRequestPermitsPerSecond(int value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_SDK_REQUEST_PERMITS_PER_SECOND,
                Integer.toString(value),
                true);
    }

    /** Configures the maximum number of ads allowed per custom audience. */
    public static void overrideFledgeCustomAudienceMaxNumAds(int value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_NUM_ADS,
                Integer.toString(value),
                true);
    }

    /** Configures the maximum total number of custom audiences in the datastore. */
    public static void overrideFledgeCustomAudienceMaxCount(int value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_COUNT,
                Integer.toString(value),
                true);
    }

    /** Configures the maximum number of custom audiences per owner application in the datastore. */
    public static void overrideFledgeCustomAudiencePerAppMaxCount(int value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_FLEDGE_CUSTOM_AUDIENCE_PER_APP_MAX_COUNT,
                Integer.toString(value),
                true);
    }

    /** Configures the maximum number of owner applications in the datastore. */
    public static void overrideFledgeCustomAudienceMaxOwnerCount(int value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_FLEDGE_CUSTOM_AUDIENCE_MAX_OWNER_COUNT,
                Integer.toString(value),
                true);
    }

    /** Overrides whether the {@code registerAdBeacon} feature is enabled. */
    public static void overrideFledgeRegisterAdBeaconEnabled(boolean value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_FLEDGE_REGISTER_AD_BEACON_ENABLED,
                Boolean.toString(value),
                false);
    }

    /** Overrides whether the {@code prebuilt Uri} feature is enabled. */
    public static void overrideFledgeAdSelectionPrebuiltUriEnabled(boolean value) {
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_FLEDGE_AD_SELECTION_PREBUILT_URI_ENABLED,
                Boolean.toString(value),
                false);
    }
}
