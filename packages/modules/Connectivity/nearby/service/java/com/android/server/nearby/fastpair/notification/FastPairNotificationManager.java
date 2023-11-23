/*
 * Copyright (C) 2021 The Android Open Source Project
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

import static com.android.server.nearby.fastpair.Constant.TAG;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationChannelGroup;
import android.app.NotificationManager;
import android.content.Context;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;
import com.android.nearby.halfsheet.R;
import com.android.server.nearby.fastpair.HalfSheetResources;
import com.android.server.nearby.fastpair.cache.DiscoveryItem;

import com.google.common.base.Objects;
import com.google.common.cache.Cache;
import com.google.common.cache.CacheBuilder;

import java.util.concurrent.TimeUnit;

/**
 * Responsible for show notification logic.
 */
public class FastPairNotificationManager {

    private static int sInstanceId = 0;
    // Notification channel group ID  for Devices notification channels.
    private static final String DEVICES_CHANNEL_GROUP_ID = "DEVICES_CHANNEL_GROUP_ID";
    // These channels are rebranded string because they are migrated from different channel ID they
    // should not be changed.
    // Channel ID for channel "Devices within reach".
    static final String DEVICES_WITHIN_REACH_CHANNEL_ID = "DEVICES_WITHIN_REACH_REBRANDED";
    // Channel ID for channel "Devices".
    static final String DEVICES_CHANNEL_ID = "DEVICES_REBRANDED";
    // Channel ID for channel "Devices with your account".
    public static final String DEVICES_WITH_YOUR_ACCOUNT_CHANNEL_ID = "DEVICES_WITH_YOUR_ACCOUNT";

    // Default channel importance for channel "Devices within reach".
    private static final int DEFAULT_DEVICES_WITHIN_REACH_CHANNEL_IMPORTANCE =
            NotificationManager.IMPORTANCE_HIGH;
    // Default channel importance for channel "Devices".
    private static final int DEFAULT_DEVICES_CHANNEL_IMPORTANCE =
            NotificationManager.IMPORTANCE_LOW;
    // Default channel importance for channel "Devices with your account".
    private static final int DEFAULT_DEVICES_WITH_YOUR_ACCOUNT_CHANNEL_IMPORTANCE =
            NotificationManager.IMPORTANCE_MIN;

    /** Fixed notification ID that won't duplicated with {@code notificationId}. */
    private static final int MAGIC_PAIR_NOTIFICATION_ID = "magic_pair_notification_id".hashCode();
    /** Fixed notification ID that won't duplicated with {@code mNotificationId}. */
    @VisibleForTesting
    static final int PAIR_SUCCESS_NOTIFICATION_ID = MAGIC_PAIR_NOTIFICATION_ID - 1;
    /** Fixed notification ID for showing the pairing failure notification. */
    @VisibleForTesting static final int PAIR_FAILURE_NOTIFICATION_ID =
            MAGIC_PAIR_NOTIFICATION_ID - 3;

    /**
     * The amount of delay enforced between notifications. The system only allows 10 notifications /
     * second, but delays in the binder IPC can cause overlap.
     */
    private static final long MIN_NOTIFICATION_DELAY_MILLIS = 300;

    // To avoid a (really unlikely) race where the user pairs and succeeds quickly more than once,
    // use a unique ID per session, so we can delay cancellation without worrying.
    // This is for connecting related notifications only. Discovery notification will use item id
    // as notification id.
    @VisibleForTesting
    final int mNotificationId;
    private HalfSheetResources mResources;
    private final FastPairNotifications mNotifications;
    private boolean mDiscoveryNotificationEnable = true;
    // A static cache that remembers all recently shown notifications. We use this to throttle
    // ourselves from showing notifications too rapidly. If we attempt to show a notification faster
    // than once every 100ms, the later notifications will be dropped and we'll show stale state.
    // Maps from Key -> Uptime Millis
    private final Cache<Key, Long> mNotificationCache =
            CacheBuilder.newBuilder()
                    .maximumSize(100)
                    .expireAfterWrite(MIN_NOTIFICATION_DELAY_MILLIS, TimeUnit.MILLISECONDS)
                    .build();
    private NotificationManager mNotificationManager;

    /**
     * FastPair notification manager that handle notification ui for fast pair.
     */
    @VisibleForTesting
    public FastPairNotificationManager(Context context, int notificationId,
            NotificationManager notificationManager, HalfSheetResources resources) {
        mNotificationId = notificationId;
        mNotificationManager = notificationManager;
        mResources = resources;
        mNotifications = new FastPairNotifications(context, mResources);

        configureDevicesNotificationChannels();
    }

    /**
     * FastPair notification manager that handle notification ui for fast pair.
     */
    public FastPairNotificationManager(Context context, int notificationId) {
        this(context, notificationId, context.getSystemService(NotificationManager.class),
                new HalfSheetResources(context));
    }

    /**
     * FastPair notification manager that handle notification ui for fast pair.
     */
    public FastPairNotificationManager(Context context) {
        this(context, /* notificationId= */ MAGIC_PAIR_NOTIFICATION_ID + sInstanceId);

        sInstanceId++;
    }

    /**
     *  Shows the notification when found saved device. A notification will be like
     *  "Your saved device is available."
     *  This uses item id as notification Id. This should be disabled when connecting starts.
     */
    public void showDiscoveryNotification(DiscoveryItem item, byte[] accountKey) {
        if (mDiscoveryNotificationEnable) {
            Log.v(TAG, "the discovery notification is disabled");
            return;
        }

        show(item.getId().hashCode(), mNotifications.discoveryNotification(item, accountKey));
    }

    /**
     * Shows pairing in progress notification.
     */
    public void showConnectingNotification(DiscoveryItem item) {
        disableShowDiscoveryNotification();
        cancel(PAIR_FAILURE_NOTIFICATION_ID);
        show(mNotificationId, mNotifications.progressNotification(item));
    }

    /**
     * Shows when Fast Pair successfully pairs the headset.
     */
    public void showPairingSucceededNotification(
            DiscoveryItem item,
            int batteryLevel,
            @Nullable String deviceName) {
        enableShowDiscoveryNotification();
        cancel(mNotificationId);
        show(PAIR_SUCCESS_NOTIFICATION_ID,
                mNotifications
                        .pairingSucceededNotification(
                                batteryLevel, deviceName, item.getTitle(), item));
    }

    /**
     * Shows failed notification.
     */
    public synchronized void showPairingFailedNotification(DiscoveryItem item, byte[] accountKey) {
        enableShowDiscoveryNotification();
        cancel(mNotificationId);
        show(PAIR_FAILURE_NOTIFICATION_ID,
                mNotifications.showPairingFailedNotification(item, accountKey));
    }

    /**
     * Notify the pairing process is done.
     */
    public void notifyPairingProcessDone(boolean success, boolean forceNotify,
            String privateAddress, String publicAddress) {}

    /** Enables the discovery notification when pairing is in progress */
    public void enableShowDiscoveryNotification() {
        Log.v(TAG, "enabling discovery notification");
        mDiscoveryNotificationEnable = true;
    }

    /** Disables the discovery notification when pairing is in progress */
    public synchronized void disableShowDiscoveryNotification() {
        Log.v(TAG, "disabling discovery notification");
        mDiscoveryNotificationEnable = false;
    }

    private void show(int id, Notification notification) {
        mNotificationManager.notify(id, notification);
    }

    /**
     * Configures devices related notification channels, including "Devices" and "Devices within
     * reach" channels.
     */
    private void configureDevicesNotificationChannels() {
        mNotificationManager.createNotificationChannelGroup(
                new NotificationChannelGroup(
                        DEVICES_CHANNEL_GROUP_ID,
                        mResources.get().getString(R.string.common_devices)));
        mNotificationManager.createNotificationChannel(
                createNotificationChannel(
                        DEVICES_WITHIN_REACH_CHANNEL_ID,
                        mResources.get().getString(R.string.devices_within_reach_channel_name),
                        DEFAULT_DEVICES_WITHIN_REACH_CHANNEL_IMPORTANCE,
                        DEVICES_CHANNEL_GROUP_ID));
        mNotificationManager.createNotificationChannel(
                createNotificationChannel(
                        DEVICES_CHANNEL_ID,
                        mResources.get().getString(R.string.common_devices),
                        DEFAULT_DEVICES_CHANNEL_IMPORTANCE,
                        DEVICES_CHANNEL_GROUP_ID));
        mNotificationManager.createNotificationChannel(
                createNotificationChannel(
                        DEVICES_WITH_YOUR_ACCOUNT_CHANNEL_ID,
                        mResources.get().getString(R.string.devices_with_your_account_channel_name),
                        DEFAULT_DEVICES_WITH_YOUR_ACCOUNT_CHANNEL_IMPORTANCE,
                        DEVICES_CHANNEL_GROUP_ID));
    }

    private NotificationChannel createNotificationChannel(
            String channelId, String channelName, int channelImportance, String channelGroupId) {
        NotificationChannel channel =
                new NotificationChannel(channelId, channelName, channelImportance);
        channel.setGroup(channelGroupId);
        if (channelImportance >= NotificationManager.IMPORTANCE_HIGH) {
            channel.setSound(/* sound= */ null, /* audioAttributes= */ null);
            // Disable vibration. Otherwise, the silent sound triggers a vibration if your
            // ring volume is set to vibrate (aka turned down all the way).
            channel.enableVibration(false);
        }

        return channel;
    }

    /** Cancel a previously shown notification. */
    public void cancel(int id) {
        try {
            mNotificationManager.cancel(id);
        } catch (SecurityException e) {
            Log.e(TAG, "Failed to cancel notification " + id, e);
        }
        mNotificationCache.invalidate(new Key(id));
    }

    private static final class Key {
        @Nullable final String mTag;
        final int mId;

        Key(int id) {
            this.mTag = null;
            this.mId = id;
        }

        @Override
        public boolean equals(@Nullable Object o) {
            if (o instanceof Key) {
                Key that = (Key) o;
                return Objects.equal(mTag, that.mTag) && (mId == that.mId);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return Objects.hashCode(mTag == null ? 0 : mTag, mId);
        }
    }
}
