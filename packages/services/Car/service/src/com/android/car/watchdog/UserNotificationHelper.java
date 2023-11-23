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

package com.android.car.watchdog;

import static com.android.car.watchdog.CarWatchdogService.ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION;
import static com.android.car.watchdog.CarWatchdogService.ACTION_LAUNCH_APP_SETTINGS;
import static com.android.car.watchdog.CarWatchdogService.ACTION_RESOURCE_OVERUSE_DISABLE_APP;
import static com.android.car.watchdog.CarWatchdogService.DEBUG;
import static com.android.car.watchdog.CarWatchdogService.TAG;
import static com.android.car.watchdog.WatchdogPerfHandler.INTENT_EXTRA_ID;

import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.os.UserHandle;
import android.text.TextUtils;

import com.android.car.R;
import com.android.car.admin.NotificationHelper;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;
import com.android.server.utils.Slogf;

import java.util.List;
import java.util.Objects;

/** Helper for sending notifications to the end-user. */
public final class UserNotificationHelper {
    private final Context mContext;
    private final CharSequence mUserNotificationTitleTemplate;
    private final String mUserNotificationTextPrioritizeApp;
    private final String mUserNotificationTextDisableApp;
    private final String mUserNotificationTextUninstallApp;
    private final String mUserNotificationActionTextPrioritizeApp;
    private final String mUserNotificationActionTextDisableApp;
    private final String mUserNotificationActionTextUninstallApp;

    UserNotificationHelper(Context context) {
        mContext = context;
        mUserNotificationTitleTemplate =
                mContext.getText(R.string.resource_overuse_notification_title);
        mUserNotificationTextPrioritizeApp =
                mContext.getString(R.string.resource_overuse_notification_text_prioritize_app);
        mUserNotificationTextDisableApp =
                mContext.getString(R.string.resource_overuse_notification_text_disable_app);
        mUserNotificationTextUninstallApp =
                mContext.getString(R.string.resource_overuse_notification_text_uninstall_app);
        mUserNotificationActionTextPrioritizeApp =
                mContext.getString(R.string.resource_overuse_notification_button_prioritize_app);
        mUserNotificationActionTextDisableApp =
                mContext.getString(R.string.resource_overuse_notification_button_disable_app);
        mUserNotificationActionTextUninstallApp =
                mContext.getString(R.string.resource_overuse_notification_button_uninstall_app);
    }

    void showResourceOveruseNotificationsAsUser(List<PackageNotificationInfo> infos,
            UserHandle userHandle) {
        Preconditions.checkArgument(userHandle.getIdentifier() >= 0,
                "Must provide the user handle for a specific user");
        PackageManager packageManager = mContext.getPackageManager();
        NotificationManager notificationManager =
                mContext.getSystemService(NotificationManager.class);
        int sentNotifications = 0;
        for (int i = 0; i < infos.size(); i++) {
            PackageNotificationInfo info = infos.get(i);
            Notification notification = constructNotification(packageManager, userHandle,
                    info.packageName, info.importance, info.notificationId);
            if (notification == null) {
                continue;
            }
            notificationManager.notifyAsUser(TAG, info.notificationId, notification, userHandle);
            ++sentNotifications;
        }
        if (DEBUG) {
            Slogf.d(TAG, "Sent %d resource overuse notifications successfully", sentNotifications);
        }
    }

    private @Nullable Notification constructNotification(PackageManager packageManager,
            UserHandle userHandle, String packageName,
            @NotificationManager.Importance int importance, int notificationId) {
        CharSequence appName;
        boolean isBundledApp;
        try {
            ApplicationInfo info = packageManager.getApplicationInfoAsUser(packageName,
                    /* flags= */ 0, userHandle);
            appName = info.loadLabel(packageManager);
            isBundledApp = (info.flags & ApplicationInfo.FLAG_SYSTEM) != 0;
        } catch (PackageManager.NameNotFoundException e) {
            Slogf.e(TAG, e, "Package '%s' not found for user %s", packageName, userHandle);
            return null;
        }
        PendingIntent positiveActionIntent = getCarServicePendingIntent(mContext,
                ACTION_LAUNCH_APP_SETTINGS, userHandle, packageName, notificationId);
        PendingIntent deleteIntent = getCarServicePendingIntent(mContext,
                ACTION_DISMISS_RESOURCE_OVERUSE_NOTIFICATION, userHandle, packageName,
                notificationId);
        String text;
        String negativeActionText;
        PendingIntent negativeActionIntent;
        // Bundled apps have disable button rather than uninstall button in the settings app. Follow
        // the same pattern here.
        if (isBundledApp) {
            text = mUserNotificationTextDisableApp + " " + mUserNotificationTextPrioritizeApp;
            negativeActionText = mUserNotificationActionTextDisableApp;
            negativeActionIntent = getCarServicePendingIntent(mContext,
                    ACTION_RESOURCE_OVERUSE_DISABLE_APP, userHandle, packageName, notificationId);
        } else {
            text = mUserNotificationTextUninstallApp + " " + mUserNotificationTextPrioritizeApp;
            negativeActionText = mUserNotificationActionTextUninstallApp;
            negativeActionIntent = positiveActionIntent;
        }

        return NotificationHelper.newNotificationBuilder(mContext, importance)
                .setSmallIcon(R.drawable.car_ic_warning)
                .setContentTitle(TextUtils.expandTemplate(mUserNotificationTitleTemplate, appName))
                .setContentText(text)
                .setCategory(Notification.CATEGORY_CAR_WARNING)
                .addAction(new Notification.Action.Builder(/* icon= */ null,
                        mUserNotificationActionTextPrioritizeApp, positiveActionIntent).build())
                .addAction(new Notification.Action.Builder(/* icon= */ null, negativeActionText,
                        negativeActionIntent).build())
                .setDeleteIntent(deleteIntent)
                .build();
    }

    @VisibleForTesting
    static PendingIntent getCarServicePendingIntent(Context context, String intentAction,
            UserHandle userHandle, String packageName, int notificationId) {
        Intent intent = new Intent(intentAction)
                .putExtra(INTENT_EXTRA_ID, notificationId)
                .putExtra(Intent.EXTRA_PACKAGE_NAME, packageName)
                .putExtra(Intent.EXTRA_USER, userHandle)
                .setPackage(context.getPackageName())
                .setFlags(Intent.FLAG_RECEIVER_REGISTERED_ONLY);
        return PendingIntent.getBroadcastAsUser(context, notificationId, intent,
                PendingIntent.FLAG_IMMUTABLE, userHandle);
    }

    /** Defines the notification info for a specific package. */
    static final class PackageNotificationInfo {
        public final String packageName;
        public final int importance;
        public final int notificationId;
        PackageNotificationInfo(String packageName, @NotificationManager.Importance int importance,
                int notificationId) {
            this.packageName = packageName;
            this.importance = importance;
            this.notificationId = notificationId;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            }
            if (!(obj instanceof PackageNotificationInfo)) {
                return false;
            }
            PackageNotificationInfo other = (PackageNotificationInfo) obj;
            return packageName.equals(other.packageName) && importance == other.importance
                    && notificationId == other.notificationId;
        }

        @Override
        public int hashCode() {
            return Objects.hash(packageName, importance, notificationId);
        }

        @Override
        public String toString() {
            return new StringBuilder().append("PackageNotificationInfo{packageName: ")
                    .append(packageName).append(", importance: ").append(importance)
                    .append(", notificationId: ").append(notificationId).append("}").toString();
        }
    }
}
