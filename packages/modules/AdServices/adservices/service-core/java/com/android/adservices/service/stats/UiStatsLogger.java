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

package com.android.adservices.service.stats;

import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__ACTION_UNSPECIFIED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__BLOCK_APP_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__BLOCK_TOPIC_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__CONFIRMATION_PAGE_DISMISSED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__CONFIRMATION_PAGE_DISPLAYED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__FLEDGE_OPT_IN_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__FLEDGE_OPT_OUT_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_CONFIRMATION_PAGE_DISMISSED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_ADDITIONAL_INFO_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_DISMISSED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_GOT_IT_BUTTON_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_MORE_BUTTON_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_OPT_IN;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_OPT_OUT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_SCROLLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_SCROLLED_TO_BOTTOM;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_SETTINGS_BUTTON_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_CONFIRMATION_PAGE_DISPLAYED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_DISABLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_DISPLAYED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_LANDING_PAGE_DISPLAYED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_REQUESTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_IN_CONFIRMATION_PAGE_GOT_IT_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_IN_CONFIRMATION_PAGE_MORE_INFO_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_IN_CONFIRMATION_PAGE_SETTINGS_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_OUT_CONFIRMATION_PAGE_GOT_IT_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_OUT_CONFIRMATION_PAGE_MORE_INFO_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_OUT_CONFIRMATION_PAGE_SETTINGS_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_ADDITIONAL_INFO_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_DISMISSED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_DISPLAYED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_GOT_IT_BUTTON_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_MORE_BUTTON_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_OPT_IN;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_OPT_OUT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_SCROLLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_SCROLLED_TO_BOTTOM;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_SETTINGS_BUTTON_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MANAGE_APPS_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MANAGE_MEASUREMENT_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MANAGE_TOPICS_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MEASUREMENT_OPT_IN_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MEASUREMENT_OPT_OUT_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__NOTIFICATION_DISABLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__NOTIFICATION_DISPLAYED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__NOTIFICATION_OPT_IN_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__NOTIFICATION_OPT_OUT_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_IN_CONFIRMATION_PAGE_GOT_IT_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_IN_CONFIRMATION_PAGE_SETTINGS_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_IN_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_OUT_CONFIRMATION_PAGE_GOT_IT_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_OUT_CONFIRMATION_PAGE_SETTINGS_CLICKED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_OUT_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__PRIVACY_SANDBOX_SETTINGS_PAGE_DISPLAYED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__REQUESTED_NOTIFICATION;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__RESET_APP_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__RESET_MEASUREMENT_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__RESET_TOPIC_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__TOPICS_OPT_IN_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__TOPICS_OPT_OUT_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__UNBLOCK_APP_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__UNBLOCK_TOPIC_SELECTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_AD_ID_STATE__AD_ID_DISABLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_AD_ID_STATE__AD_ID_ENABLED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_AD_ID_STATE__STATE_UNSPECIFIED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__CONSENT_UNSPECIFIED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__FLEDGE_DEFAULT_OPT_IN;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__FLEDGE_DEFAULT_OPT_OUT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__MEASUREMENT_DEFAULT_OPT_IN;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__MEASUREMENT_DEFAULT_OPT_OUT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__PP_API_DEFAULT_OPT_IN;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__PP_API_DEFAULT_OPT_OUT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__TOPICS_DEFAULT_OPT_IN;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__TOPICS_DEFAULT_OPT_OUT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__FEATURE_UNSPECIFIED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_FIRST_CONSENT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_FIRST_CONSENT_FF;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_RECONSENT;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_RECONSENT_FF;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_UNSUPPORTED;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__REGION__EU;
import static com.android.adservices.service.stats.AdServicesStatsLog.AD_SERVICES_SETTINGS_USAGE_REPORTED__REGION__ROW;

import android.content.Context;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;

import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.feature.PrivacySandboxFeatureType;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.consent.DeviceRegionProvider;

/** Logger for UiStats. */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class UiStatsLogger {
    private static AdServicesLoggerImpl sLogger = AdServicesLoggerImpl.getInstance();

    /** Logs that a notification was displayed. */
    public static void logNotificationDisplayed(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_DISPLAYED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__NOTIFICATION_DISPLAYED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the more button on the landing page was displayed. */
    public static void logLandingPageMoreButtonClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_MORE_BUTTON_CLICKED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_MORE_BUTTON_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the additional info dropdown on the landing page was displayed. */
    public static void logLandingPageAdditionalInfoClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_ADDITIONAL_INFO_CLICKED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_ADDITIONAL_INFO_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user scrolled the landing page. */
    public static void logLandingPageScrolled(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_SCROLLED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_SCROLLED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user scrolled to the bottom of the landing page. */
    public static void logLandingPageScrolledToBottom(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_SCROLLED_TO_BOTTOM
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_SCROLLED_TO_BOTTOM);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user clicked the setting button on the landing page. */
    public static void logLandingPageSettingsButtonClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_SETTINGS_BUTTON_CLICKED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_SETTINGS_BUTTON_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user dismissed the landing page. */
    public static void logLandingPageDismissed(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_DISMISSED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_DISMISSED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user clicked the got it button on the landing page. */
    public static void logLandingPageGotItButtonClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_GOT_IT_BUTTON_CLICKED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_GOT_IT_BUTTON_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user opt-in from the landing page. */
    public static void logLandingPageOptIn(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_OPT_IN
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_OPT_IN);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user opt-out from the landing page. */
    public static void logLandingPageOptOut(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_LANDING_PAGE_OPT_OUT
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_OPT_OUT);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user clicked settings on the opt-in confirmation page. */
    public static void logOptInConfirmationPageSettingsClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_IN_CONFIRMATION_PAGE_SETTINGS_CLICKED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_IN_CONFIRMATION_PAGE_SETTINGS_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user clicked settings on the opt-out confirmation page. */
    public static void logOptOutConfirmationPageSettingsClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_OUT_CONFIRMATION_PAGE_SETTINGS_CLICKED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_OUT_CONFIRMATION_PAGE_SETTINGS_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user clicked got it on the opt-in confirmation page. */
    public static void logOptInConfirmationPageGotItClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_IN_CONFIRMATION_PAGE_GOT_IT_CLICKED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_IN_CONFIRMATION_PAGE_GOT_IT_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user clicked got it on the opt-out confirmation page. */
    public static void logOptOutConfirmationPageGotItClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_OUT_CONFIRMATION_PAGE_GOT_IT_CLICKED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_OUT_CONFIRMATION_PAGE_GOT_IT_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** GA only. Logs that the user clicked more info on the opt-in confirmation page. */
    public static void logOptInConfirmationPageMoreInfoClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_IN_CONFIRMATION_PAGE_MORE_INFO_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** GA only. Logs that the user clicked more info on the opt-out confirmation page. */
    public static void logOptOutConfirmationPageMoreInfoClicked(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_OPT_OUT_CONFIRMATION_PAGE_MORE_INFO_CLICKED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the user dismissed the confirmation page. */
    public static void logConfirmationPageDismissed(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_CONFIRMATION_PAGE_DISMISSED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__CONFIRMATION_PAGE_DISMISSED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a notification was requested. */
    public static void logRequestedNotification(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_REQUESTED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__REQUESTED_NOTIFICATION);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that notifications are disabled on a device. */
    public static void logNotificationDisabled(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_DISABLED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__NOTIFICATION_DISABLED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the landing page was shown to a user. */
    public static void logLandingPageDisplayed(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_LANDING_PAGE_DISPLAYED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__LANDING_PAGE_DISPLAYED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that the confirmation page was shown to a user. */
    public static void logConfirmationPageDisplayed(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__GA_UX_NOTIFICATION_CONFIRMATION_PAGE_DISPLAYED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__CONFIRMATION_PAGE_DISPLAYED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs user opt-in action for PP API. */
    public static void logOptInSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__NOTIFICATION_OPT_IN_SELECTED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_IN_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs user opt-out action for PP API. */
    public static void logOptOutSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                FlagsFactory.getFlags().getGaUxFeatureEnabled()
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__NOTIFICATION_OPT_OUT_SELECTED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__OPT_OUT_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs user opt-in action given an ApiType. */
    public static void logOptInSelected(@NonNull Context context, AdServicesApiType apiType) {
        UIStats uiStats = getBaseUiStats(context, apiType);

        uiStats.setAction(getPerApiConsentAction(apiType, /* isOptIn */ true));

        sLogger.logUIStats(uiStats);
    }

    /** Logs user opt-out action given an ApiType. */
    public static void logOptOutSelected(@NonNull Context context, AdServicesApiType apiType) {
        UIStats uiStats = getBaseUiStats(context, apiType);

        uiStats.setAction(getPerApiConsentAction(apiType, /* isOptIn */ false));

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has opened the settings page. */
    public static void logSettingsPageDisplayed(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(
                AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__PRIVACY_SANDBOX_SETTINGS_PAGE_DISPLAYED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has clicked manage topics button. */
    public static void logManageTopicsSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MANAGE_TOPICS_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has clicked manage apps button. */
    public static void logManageAppsSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MANAGE_APPS_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has clicked reset topics button. */
    public static void logResetTopicSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__RESET_TOPIC_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has clicked reset apps button. */
    public static void logResetAppSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__RESET_APP_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has clicked block topic button. */
    public static void logBlockTopicSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__BLOCK_TOPIC_SELECTED);

        sLogger.logUIStats(uiStats);
    }
    /** Logs that a user has clicked unblock topic button. */
    public static void logUnblockTopicSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__UNBLOCK_TOPIC_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has clicked block app button. */
    public static void logBlockAppSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__BLOCK_APP_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has clicked unblock app button. */
    public static void logUnblockAppSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__UNBLOCK_APP_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has clicked manage measurement button. */
    public static void logManageMeasurementSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MANAGE_MEASUREMENT_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    /** Logs that a user has clicked reset measurement button. */
    public static void logResetMeasurementSelected(@NonNull Context context) {
        UIStats uiStats = getBaseUiStats(context);

        uiStats.setAction(AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__RESET_MEASUREMENT_SELECTED);

        sLogger.logUIStats(uiStats);
    }

    private static int getRegion(@NonNull Context context) {
        return DeviceRegionProvider.isEuDevice(context)
                ? AD_SERVICES_SETTINGS_USAGE_REPORTED__REGION__EU
                : AD_SERVICES_SETTINGS_USAGE_REPORTED__REGION__ROW;
    }

    private static int getDefaultConsent(@NonNull Context context) {
        Boolean defaultConsent = ConsentManager.getInstance(context).getDefaultConsent();
        // edge case where the user opens the settings pages before receiving consent notification.
        if (defaultConsent == null) {
            return AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__CONSENT_UNSPECIFIED;
        } else {
            return defaultConsent
                    ? AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__PP_API_DEFAULT_OPT_IN
                    : AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__PP_API_DEFAULT_OPT_OUT;
        }
    }

    private static int getDefaultAdIdState(@NonNull Context context) {
        Boolean defaultAdIdState = ConsentManager.getInstance(context).getDefaultAdIdState();
        // edge case where the user opens the settings pages before receiving consent notification.
        if (defaultAdIdState == null) {
            return AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_AD_ID_STATE__STATE_UNSPECIFIED;
        } else {
            return defaultAdIdState
                    ? AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_AD_ID_STATE__AD_ID_ENABLED
                    : AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_AD_ID_STATE__AD_ID_DISABLED;
        }
    }

    private static int getDefaultConsent(@NonNull Context context, AdServicesApiType apiType) {
        switch (apiType) {
            case TOPICS:
                Boolean topicsDefaultConsent =
                        ConsentManager.getInstance(context).getTopicsDefaultConsent();
                // edge case where the user checks topic consent before receiving consent
                // notification.
                if (topicsDefaultConsent == null) {
                    return AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_AD_ID_STATE__STATE_UNSPECIFIED;
                } else {
                    return topicsDefaultConsent
                            ? AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__TOPICS_DEFAULT_OPT_IN
                            : AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__TOPICS_DEFAULT_OPT_OUT;
                }
            case FLEDGE:
                Boolean fledgeDefaultConsent =
                        ConsentManager.getInstance(context).getFledgeDefaultConsent();
                // edge case where the user checks FLEDGE consent before receiving consent
                // notification.
                if (fledgeDefaultConsent == null) {
                    return AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_AD_ID_STATE__STATE_UNSPECIFIED;
                } else {
                    return fledgeDefaultConsent
                            ? AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__FLEDGE_DEFAULT_OPT_IN
                            : AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__FLEDGE_DEFAULT_OPT_OUT;
                }
            case MEASUREMENTS:
                Boolean measurementDefaultConsent =
                        ConsentManager.getInstance(context).getMeasurementDefaultConsent();
                // edge case where the user checks measurement consent before receiving consent
                // notification.
                if (measurementDefaultConsent == null) {
                    return AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_AD_ID_STATE__STATE_UNSPECIFIED;
                } else {
                    return measurementDefaultConsent
                            ? AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__MEASUREMENT_DEFAULT_OPT_IN
                            : AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__MEASUREMENT_DEFAULT_OPT_OUT;
                }
            default:
                return AD_SERVICES_SETTINGS_USAGE_REPORTED__DEFAULT_CONSENT__CONSENT_UNSPECIFIED;
        }
    }

    private static int getPerApiConsentAction(AdServicesApiType apiType, boolean isOptIn) {
        switch (apiType) {
            case TOPICS:
                return isOptIn
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__TOPICS_OPT_IN_SELECTED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__TOPICS_OPT_OUT_SELECTED;
            case FLEDGE:
                return isOptIn
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__FLEDGE_OPT_IN_SELECTED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__FLEDGE_OPT_OUT_SELECTED;
            case MEASUREMENTS:
                return isOptIn
                        ? AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MEASUREMENT_OPT_IN_SELECTED
                        : AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__MEASUREMENT_OPT_OUT_SELECTED;
            default:
                return AD_SERVICES_SETTINGS_USAGE_REPORTED__ACTION__ACTION_UNSPECIFIED;
        }
    }

    private static int getPrivacySandboxFeatureType(@NonNull Context context) {
        if (!FlagsFactory.getFlags().isUiFeatureTypeLoggingEnabled()) {
            return AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__FEATURE_UNSPECIFIED;
        }

        PrivacySandboxFeatureType featureType =
                ConsentManager.getInstance(context).getCurrentPrivacySandboxFeature();
        if (featureType == null) {
            return AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__FEATURE_UNSPECIFIED;
        }

        switch (featureType) {
            case PRIVACY_SANDBOX_FIRST_CONSENT_FF:
                return AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_FIRST_CONSENT_FF;
            case PRIVACY_SANDBOX_RECONSENT_FF:
                return AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_RECONSENT_FF;
            case PRIVACY_SANDBOX_FIRST_CONSENT:
                return AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_FIRST_CONSENT;
            case PRIVACY_SANDBOX_RECONSENT:
                return AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_RECONSENT;
            case PRIVACY_SANDBOX_UNSUPPORTED:
                return AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__PRIVACY_SANDBOX_UNSUPPORTED;
            default:
                return AD_SERVICES_SETTINGS_USAGE_REPORTED__FEATURE_TYPE__FEATURE_UNSPECIFIED;
        }
    }

    private static UIStats getBaseUiStats(@NonNull Context context) {
        return new UIStats.Builder()
                .setCode(AD_SERVICES_SETTINGS_USAGE_REPORTED)
                .setRegion(getRegion(context))
                .setDefaultConsent(getDefaultConsent(context))
                .setDefaultAdIdState(getDefaultAdIdState(context))
                .setPrivacySandboxFeatureType(getPrivacySandboxFeatureType(context))
                .build();
    }

    private static UIStats getBaseUiStats(@NonNull Context context, AdServicesApiType apiType) {
        return new UIStats.Builder()
                .setCode(AD_SERVICES_SETTINGS_USAGE_REPORTED)
                .setRegion(getRegion(context))
                .setDefaultConsent(getDefaultConsent(context, apiType))
                .setDefaultAdIdState(getDefaultAdIdState(context))
                .setPrivacySandboxFeatureType(getPrivacySandboxFeatureType(context))
                .build();
    }
}
