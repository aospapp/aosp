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

package com.android.adservices.ui.notifications;

import static com.android.adservices.ui.notifications.ConsentNotificationFragment.IS_EU_DEVICE_ARGUMENT_KEY;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import com.android.adservices.api.R;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.stats.UiStatsLogger;
import com.android.adservices.ui.OTAResourcesManager;

/** Provides methods which can be used to display Privacy Sandbox consent notification. */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class ConsentNotificationTrigger {
    /* Random integer for NotificationCompat purposes. */
    public static final int NOTIFICATION_ID = 67920;
    private static final String CHANNEL_ID = "PRIVACY_SANDBOX_CHANNEL";
    private static final int NOTIFICATION_PRIORITY = NotificationCompat.PRIORITY_MAX;

    /**
     * Shows consent notification as the highest priority notification to the user.
     *
     * @param context Context which is used to display {@link NotificationCompat}
     */
    public static void showConsentNotification(@NonNull Context context, boolean isEuDevice) {
        UiStatsLogger.logRequestedNotification(context);

        boolean gaUxFeatureEnabled = FlagsFactory.getFlags().getGaUxFeatureEnabled();

        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        ConsentManager consentManager = ConsentManager.getInstance(context);
        if (!notificationManager.areNotificationsEnabled()) {
            recordNotificationDisplayed(gaUxFeatureEnabled, consentManager);
            UiStatsLogger.logNotificationDisabled(context);
            return;
        }

        // Set OTA resources if it exists.
        if (FlagsFactory.getFlags().getUiOtaStringsFeatureEnabled()) {
            OTAResourcesManager.applyOTAResources(context.getApplicationContext(), true);
        }

        setupConsents(context, isEuDevice, gaUxFeatureEnabled, consentManager);

        createNotificationChannel(context);
        Notification notification = getNotification(context, isEuDevice, gaUxFeatureEnabled);
        notificationManager.notify(NOTIFICATION_ID, notification);

        UiStatsLogger.logNotificationDisplayed(context);
        recordNotificationDisplayed(gaUxFeatureEnabled, consentManager);
    }

    private static void recordNotificationDisplayed(
            boolean gaUxFeatureEnabled, ConsentManager consentManager) {
        if (FlagsFactory.getFlags().getRecordManualInteractionEnabled()
                && consentManager.getUserManualInteractionWithConsent()
                        != ConsentManager.MANUAL_INTERACTIONS_RECORDED) {
            consentManager.recordUserManualInteractionWithConsent(
                    ConsentManager.NO_MANUAL_INTERACTIONS_RECORDED);
        }
        if (gaUxFeatureEnabled) {
            consentManager.recordGaUxNotificationDisplayed();
        }
        consentManager.recordNotificationDisplayed();
    }

    @NonNull
    private static Notification getNotification(
            @NonNull Context context, boolean isEuDevice, boolean gaUxFeatureEnabled) {
        Notification notification;
        if (gaUxFeatureEnabled) {
            if (FlagsFactory.getFlags().getEuNotifFlowChangeEnabled()) {
                notification = getGaV2ConsentNotification(context, isEuDevice);
            } else {
                notification = getGaConsentNotification(context, isEuDevice);
            }
        } else {
            notification = getConsentNotification(context, isEuDevice);
        }

        // make notification sticky (non-dismissible) for EuDevices when the GA UX feature is on
        if (gaUxFeatureEnabled && isEuDevice) {
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
        }
        return notification;
    }

    // setup default consents based on information whether the device is EU or non-EU device and
    // GA UX feature flag is enabled.
    private static void setupConsents(
            @NonNull Context context,
            boolean isEuDevice,
            boolean gaUxFeatureEnabled,
            ConsentManager consentManager) {
        // Keep the feature flag at the upper level to make it easier to cleanup the code once
        // the beta functionality is fully deprecated and abandoned.
        if (gaUxFeatureEnabled) {
            // EU: all APIs are by default disabled
            // ROW: all APIs are by default enabled
            // TODO(b/260266623): change consent state to UNDEFINED
            if (isEuDevice) {
                consentManager.recordTopicsDefaultConsent(false);
                consentManager.recordFledgeDefaultConsent(false);
                consentManager.recordMeasurementDefaultConsent(false);

                consentManager.disable(context, AdServicesApiType.TOPICS);
                consentManager.disable(context, AdServicesApiType.FLEDGE);
                consentManager.disable(context, AdServicesApiType.MEASUREMENTS);
            } else {
                consentManager.recordTopicsDefaultConsent(true);
                consentManager.recordFledgeDefaultConsent(true);
                consentManager.recordMeasurementDefaultConsent(true);

                consentManager.enable(context, AdServicesApiType.TOPICS);
                consentManager.enable(context, AdServicesApiType.FLEDGE);
                consentManager.enable(context, AdServicesApiType.MEASUREMENTS);
            }
        } else {
            // For the ROW devices, set the consent to GIVEN (enabled).
            // For the EU devices, set the consent to REVOKED (disabled)
            if (!isEuDevice) {
                consentManager.enable(context);
            } else {
                consentManager.disable(context);
            }
        }
    }

    private static Notification getGaV2ConsentNotification(
            @NonNull Context context, boolean isEuDevice) {
        Intent intent = new Intent(context, ConsentNotificationActivity.class);
        intent.putExtra(IS_EU_DEVICE_ARGUMENT_KEY, isEuDevice);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.BigTextStyle textStyle =
                new NotificationCompat.BigTextStyle()
                        .bigText(
                                isEuDevice
                                        ? context.getString(
                                        R.string.notificationUI_notification_ga_content_eu_v2)
                                        : context.getString(
                                        R.string.notificationUI_notification_ga_content_v2));
        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_info_icon)
                        .setContentTitle(
                                context.getString(
                                        isEuDevice
                                                ? R.string.notificationUI_notification_ga_title_eu_v2
                                                : R.string.notificationUI_notification_ga_title_v2))
                        .setContentText(
                                context.getString(
                                        isEuDevice
                                                ? R.string.notificationUI_notification_ga_content_eu_v2
                                                : R.string.notificationUI_notification_ga_content_v2))
                        .setStyle(textStyle)
                        .setPriority(NOTIFICATION_PRIORITY)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent);
        return notification.build();
    }

    /**
     * Returns a {@link NotificationCompat.Builder} which can be used to display consent
     * notification to the user when GaUxFeature flag is enabled.
     *
     * @param context {@link Context} which is used to prepare a {@link NotificationCompat}.
     */
    private static Notification getGaConsentNotification(
            @NonNull Context context, boolean isEuDevice) {
        Intent intent = new Intent(context, ConsentNotificationActivity.class);
        intent.putExtra(IS_EU_DEVICE_ARGUMENT_KEY, isEuDevice);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 1, intent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.BigTextStyle textStyle =
                new NotificationCompat.BigTextStyle()
                        .bigText(
                                isEuDevice
                                        ? context.getString(
                                                R.string.notificationUI_notification_ga_content_eu)
                                        : context.getString(
                                                R.string.notificationUI_notification_ga_content));
        NotificationCompat.Builder notification =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.ic_info_icon)
                        .setContentTitle(
                                context.getString(
                                        isEuDevice
                                                ? R.string.notificationUI_notification_ga_title_eu
                                                : R.string.notificationUI_notification_ga_title))
                        .setContentText(
                                context.getString(
                                        isEuDevice
                                                ? R.string.notificationUI_notification_ga_content_eu
                                                : R.string.notificationUI_notification_ga_content))
                        .setStyle(textStyle)
                        .setPriority(NOTIFICATION_PRIORITY)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent);

        if (isEuDevice && !FlagsFactory.getFlags().getNotificationDismissedOnClick()) {
            notification.setAutoCancel(false);
        }

        return notification.build();
    }

    /**
     * Returns a {@link NotificationCompat.Builder} which can be used to display consent
     * notification to the user.
     *
     * @param context {@link Context} which is used to prepare a {@link NotificationCompat}.
     */
    private static Notification getConsentNotification(
            @NonNull Context context, boolean isEuDevice) {
        Intent intent = new Intent(context, ConsentNotificationActivity.class);
        intent.putExtra(IS_EU_DEVICE_ARGUMENT_KEY, isEuDevice);
        PendingIntent pendingIntent =
                PendingIntent.getActivity(
                        context, 1, intent, PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.BigTextStyle textStyle =
                new NotificationCompat.BigTextStyle()
                        .bigText(
                                isEuDevice
                                        ? context.getString(
                                                R.string.notificationUI_notification_content_eu)
                                        : context.getString(
                                                R.string.notificationUI_notification_content));
        return new NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_info_icon)
                .setContentTitle(
                        context.getString(
                                isEuDevice
                                        ? R.string.notificationUI_notification_title_eu
                                        : R.string.notificationUI_notification_title))
                .setContentText(
                        context.getString(
                                isEuDevice
                                        ? R.string.notificationUI_notification_content_eu
                                        : R.string.notificationUI_notification_content))
                .setStyle(textStyle)
                .setPriority(NOTIFICATION_PRIORITY)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .build();
    }

    private static void createNotificationChannel(@NonNull Context context) {
        // TODO (b/230372892): styling -> adjust channels to use Android System labels.
        int importance = NotificationManager.IMPORTANCE_HIGH;
        NotificationChannel channel =
                new NotificationChannel(
                        CHANNEL_ID,
                        context.getString(R.string.settingsUI_main_view_title),
                        importance);
        channel.setDescription(context.getString(R.string.settingsUI_main_view_title));
        NotificationManager notificationManager =
                context.getSystemService(NotificationManager.class);
        notificationManager.createNotificationChannel(channel);
    }
}
