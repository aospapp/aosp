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

package com.android.server.nearby.fastpair.notification;

import static com.android.nearby.halfsheet.constants.Constant.ACTION_FAST_PAIR;
import static com.android.server.nearby.fastpair.UserActionHandler.EXTRA_FAST_PAIR_SECRET;
import static com.android.server.nearby.fastpair.UserActionHandler.EXTRA_ITEM_ID;
import static com.android.server.nearby.fastpair.notification.FastPairNotificationManager.DEVICES_WITHIN_REACH_CHANNEL_ID;

import static com.google.common.io.BaseEncoding.base16;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.SystemClock;
import android.provider.Settings;

import com.android.nearby.halfsheet.R;
import com.android.server.nearby.common.fastpair.IconUtils;
import com.android.server.nearby.fastpair.HalfSheetResources;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;

import service.proto.Cache;

/**
 * Collection of utilities to create {@link Notification} objects that are displayed through {@link
 * FastPairNotificationManager}.
 */
public class FastPairNotifications {

    private final Context mContext;
    private final HalfSheetResources mResources;
    /**
     * Note: Idea copied from Google.
     *
     * <p>Request code used for notification pending intents (executed on tap, dismiss).
     *
     * <p>Android only keeps one PendingIntent instance if it thinks multiple pending intents match.
     * As comparing PendingIntents/Intents does not inspect the data in the extras, multiple pending
     * intents can conflict. This can have surprising consequences (see b/68702692#comment8).
     *
     * <p>We also need to avoid conflicts with notifications started by an earlier launch of the app
     * so use the truncated uptime of when the class was instantiated. The uptime will only overflow
     * every ~50 days, and even then chances of conflict will be rare.
     */
    private static int sRequestCode = (int) SystemClock.elapsedRealtime();

    public FastPairNotifications(Context context, HalfSheetResources resources) {
        this.mContext = context;
        this.mResources = resources;
    }

    /**
     * Creates the initial "Your saved device is available" notification when subsequent pairing
     * is available.
     * @param item discovered item which contains title and item id
     * @param accountKey used for generating intent for pairing
     */
    public Notification discoveryNotification(DiscoveryItem item, byte[] accountKey) {
        Notification.Builder builder =
                newBaseBuilder(item)
                        .setContentTitle(mResources.getString(R.string.fast_pair_your_device))
                        .setContentText(item.getTitle())
                        .setContentIntent(getPairIntent(item.getCopyOfStoredItem(), accountKey))
                        .setCategory(Notification.CATEGORY_RECOMMENDATION)
                        .setAutoCancel(false);
        return builder.build();
    }

    /**
     * Creates the in progress "Connecting" notification when the device and headset are paring.
     */
    public Notification progressNotification(DiscoveryItem item) {
        String summary = mResources.getString(R.string.common_connecting);
        Notification.Builder builder =
                newBaseBuilder(item)
                        .setTickerForAccessibility(summary)
                        .setCategory(Notification.CATEGORY_PROGRESS)
                        .setContentTitle(mResources.getString(R.string.fast_pair_your_device))
                        .setContentText(summary)
                        // Intermediate progress bar.
                        .setProgress(0, 0, true)
                        // Tapping does not dismiss this.
                        .setAutoCancel(false);

        return builder.build();
    }

    /**
     * Creates paring failed notification.
     */
    public Notification showPairingFailedNotification(DiscoveryItem item, byte[] accountKey) {
        String couldNotPair = mResources.getString(R.string.fast_pair_unable_to_connect);
        String notificationContent;
        if (accountKey != null) {
            notificationContent = mResources.getString(
                    R.string.fast_pair_turn_on_bt_device_pairing_mode);
        } else {
            notificationContent =
                    mResources.getString(R.string.fast_pair_unable_to_connect_description);
        }
        Notification.Builder builder =
                newBaseBuilder(item)
                        .setTickerForAccessibility(couldNotPair)
                        .setCategory(Notification.CATEGORY_ERROR)
                        .setContentTitle(couldNotPair)
                        .setContentText(notificationContent)
                        .setContentIntent(getBluetoothSettingsIntent())
                        // Dismissing completes the attempt.
                        .setDeleteIntent(getBluetoothSettingsIntent());
        return builder.build();

    }

    /**
     * Creates paring successfully notification.
     */
    public Notification pairingSucceededNotification(
            int batteryLevel,
            @Nullable String deviceName,
            String modelName,
            DiscoveryItem item) {
        final String contentText;
        StringBuilder contentTextBuilder = new StringBuilder();
        contentTextBuilder.append(modelName);
        if (batteryLevel >= 0 && batteryLevel <= 100) {
            contentTextBuilder
                    .append("\n")
                    .append(mResources.getString(R.string.common_battery_level, batteryLevel));
        }
        String pairingComplete =
                deviceName == null
                        ? mResources.getString(R.string.fast_pair_device_ready)
                        : mResources.getString(
                                R.string.fast_pair_device_ready_with_device_name, deviceName);
        contentText = contentTextBuilder.toString();
        Notification.Builder builder =
                newBaseBuilder(item)
                        .setTickerForAccessibility(pairingComplete)
                        .setCategory(Notification.CATEGORY_STATUS)
                        .setContentTitle(pairingComplete)
                        .setContentText(contentText);

        return builder.build();
    }

    private PendingIntent getPairIntent(Cache.StoredDiscoveryItem item, byte[] accountKey) {
        Intent intent =
                new Intent(ACTION_FAST_PAIR)
                        .putExtra(EXTRA_ITEM_ID, item.getId())
                        // Encode account key as a string instead of bytes so that it can be passed
                        // to the string representation of the intent.
                        .putExtra(EXTRA_FAST_PAIR_SECRET, base16().encode(accountKey))
                        .setPackage(mContext.getPackageName());
        return PendingIntent.getBroadcast(mContext, sRequestCode++, intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }

    private PendingIntent getBluetoothSettingsIntent() {
        Intent intent = new Intent(Settings.ACTION_BLUETOOTH_SETTINGS);
        return PendingIntent.getActivity(mContext, sRequestCode++, intent,
                PendingIntent.FLAG_ONE_SHOT | PendingIntent.FLAG_IMMUTABLE);
    }

    private LargeHeadsUpNotificationBuilder newBaseBuilder(DiscoveryItem item) {
        LargeHeadsUpNotificationBuilder builder =
                (LargeHeadsUpNotificationBuilder)
                        (new LargeHeadsUpNotificationBuilder(
                                mContext,
                                DEVICES_WITHIN_REACH_CHANNEL_ID,
                                /* largeIcon= */ true)
                                .setIsDevice(true)
                                // Tapping does not dismiss this.
                                .setSmallIcon(Icon.createWithResource(
                                        mResources.getResourcesContext(),
                                        R.drawable.quantum_ic_devices_other_vd_theme_24)))
                                .setLargeIcon(IconUtils.addWhiteCircleBackground(
                                        mResources.getResourcesContext(), item.getIcon()))
                                // Dismissible.
                                .setOngoing(false)
                                // Timestamp is not relevant, hide it.
                                .setShowWhen(false)
                                .setColor(mResources.getColor(R.color.discovery_activity_accent))
                                .setLocalOnly(true)
                                // don't show these notifications on wear devices
                                .setAutoCancel(true);

        return builder;
    }
}
