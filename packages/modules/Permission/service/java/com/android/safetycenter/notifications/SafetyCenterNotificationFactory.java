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

package com.android.safetycenter.notifications;

import static android.os.Build.VERSION_CODES.TIRAMISU;
import static android.safetycenter.SafetyCenterManager.EXTRA_SAFETY_SOURCE_ID;
import static android.safetycenter.SafetyCenterManager.EXTRA_SAFETY_SOURCE_ISSUE_ID;
import static android.safetycenter.SafetyCenterManager.EXTRA_SAFETY_SOURCE_USER_HANDLE;

import android.annotation.ColorInt;
import android.annotation.Nullable;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.os.UserHandle;
import android.safetycenter.SafetySourceData;
import android.safetycenter.SafetySourceIssue;
import android.text.TextUtils;

import androidx.annotation.RequiresApi;

import com.android.modules.utils.build.SdkLevel;
import com.android.safetycenter.PendingIntentFactory;
import com.android.safetycenter.internaldata.SafetyCenterIds;
import com.android.safetycenter.internaldata.SafetyCenterIssueActionId;
import com.android.safetycenter.internaldata.SafetyCenterIssueKey;
import com.android.safetycenter.resources.SafetyCenterResourcesContext;

import java.time.Duration;
import java.util.List;

/**
 * Factory that builds {@link Notification} objects from {@link SafetySourceIssue} instances with
 * appropriate {@link PendingIntent}s for click and dismiss callbacks.
 */
@RequiresApi(TIRAMISU)
final class SafetyCenterNotificationFactory {

    private static final String TAG = "SafetyCenterNF";
    private static final int OPEN_SAFETY_CENTER_REQUEST_CODE = 1221;
    private static final Duration SUCCESS_NOTIFICATION_TIMEOUT = Duration.ofSeconds(10);

    private final Context mContext;
    private final SafetyCenterNotificationChannels mNotificationChannels;
    private final SafetyCenterResourcesContext mResourcesContext;

    SafetyCenterNotificationFactory(
            Context context,
            SafetyCenterNotificationChannels notificationChannels,
            SafetyCenterResourcesContext resourcesContext) {
        mContext = context;
        mNotificationChannels = notificationChannels;
        mResourcesContext = resourcesContext;
    }

    /**
     * Creates and returns a new {@link Notification} for a successful action, or {@code null} if
     * none could be created.
     *
     * <p>The provided {@link NotificationManager} is used to create or update the {@link
     * NotificationChannel} for the notification.
     */
    @Nullable
    Notification newNotificationForSuccessfulAction(
            NotificationManager notificationManager,
            SafetySourceIssue issue,
            SafetySourceIssue.Action action) {
        String channelId = mNotificationChannels.getCreatedChannelId(notificationManager, issue);

        if (channelId == null) {
            return null;
        }

        Notification.Builder builder =
                new Notification.Builder(mContext, channelId)
                        .setSmallIcon(
                                getNotificationIcon(SafetySourceData.SEVERITY_LEVEL_INFORMATION))
                        .setExtras(getNotificationExtras())
                        .setContentTitle(action.getSuccessMessage())
                        .setShowWhen(true)
                        .setTimeoutAfter(SUCCESS_NOTIFICATION_TIMEOUT.toMillis())
                        .setContentIntent(newSafetyCenterPendingIntent(null));

        Integer color = getNotificationColor(SafetySourceData.SEVERITY_LEVEL_INFORMATION);
        if (color != null) {
            builder.setColor(color);
        }

        return builder.build();
    }

    /**
     * Creates and returns a new {@link Notification} instance corresponding to the given issue, or
     * {@code null} if none could be created.
     *
     * <p>The provided {@link NotificationManager} is used to create or update the {@link
     * NotificationChannel} for the notification.
     */
    @Nullable
    Notification newNotificationForIssue(
            NotificationManager notificationManager,
            SafetySourceIssue issue,
            SafetyCenterIssueKey issueKey) {
        String channelId = mNotificationChannels.getCreatedChannelId(notificationManager, issue);

        if (channelId == null) {
            return null;
        }

        CharSequence title = issue.getTitle();
        CharSequence text = issue.getSummary();
        List<SafetySourceIssue.Action> issueActions = issue.getActions();

        if (SdkLevel.isAtLeastU()) {
            SafetySourceIssue.Notification customNotification = issue.getCustomNotification();
            if (customNotification != null) {
                title = customNotification.getTitle();
                text = customNotification.getText();
                issueActions = customNotification.getActions();
            }
        }

        Notification.Builder builder =
                new Notification.Builder(mContext, channelId)
                        .setSmallIcon(getNotificationIcon(issue.getSeverityLevel()))
                        .setExtras(getNotificationExtras())
                        .setShowWhen(true)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setContentIntent(newSafetyCenterPendingIntent(issueKey))
                        .setDeleteIntent(
                                SafetyCenterNotificationReceiver.newNotificationDismissedIntent(
                                        mContext, issueKey));

        Integer color = getNotificationColor(issue.getSeverityLevel());
        if (color != null) {
            builder.setColor(color);
        }

        for (int i = 0; i < issueActions.size(); i++) {
            Notification.Action notificationAction =
                    toNotificationAction(issueKey, issueActions.get(i));
            builder.addAction(notificationAction);
        }

        return builder.build();
    }

    /**
     * Returns a {@link PendingIntent} to open Safety Center, optionally navigating to and/or
     * highlighting a specific issue if {@code issueKey} is given.
     */
    private PendingIntent newSafetyCenterPendingIntent(@Nullable SafetyCenterIssueKey issueKey) {
        Intent intent = new Intent(Intent.ACTION_SAFETY_CENTER);
        if (issueKey != null) {
            // Set the encoded issue key as the intent's identifier to ensure the PendingIntents of
            // different notifications do not collide:
            intent.setIdentifier(SafetyCenterIds.encodeToString(issueKey));
            intent.putExtra(EXTRA_SAFETY_SOURCE_ID, issueKey.getSafetySourceId());
            intent.putExtra(EXTRA_SAFETY_SOURCE_ISSUE_ID, issueKey.getSafetySourceIssueId());
            intent.putExtra(EXTRA_SAFETY_SOURCE_USER_HANDLE, UserHandle.of(issueKey.getUserId()));
        }
        // This extra is defined in the PermissionController APK, cannot be referenced directly:
        intent.putExtra("navigation_source_intent_extra", "NOTIFICATION");
        return PendingIntentFactory.getActivityPendingIntent(
                mContext, OPEN_SAFETY_CENTER_REQUEST_CODE, intent, PendingIntent.FLAG_IMMUTABLE);
    }

    private Icon getNotificationIcon(@SafetySourceData.SeverityLevel int severityLevel) {
        String iconResName = "ic_notification_badge_general";
        if (severityLevel == SafetySourceData.SEVERITY_LEVEL_CRITICAL_WARNING) {
            iconResName = "ic_notification_badge_critical";
        }
        Icon icon = mResourcesContext.getIconByDrawableName(iconResName);
        if (icon == null) {
            // In case it was impossible to fetch the above drawable for any reason use this
            // fallback which should be present on all Android devices:
            icon = Icon.createWithResource(mContext, android.R.drawable.ic_dialog_alert);
        }
        return icon;
    }

    @ColorInt
    @Nullable
    private Integer getNotificationColor(@SafetySourceData.SeverityLevel int severityLevel) {
        String colorResName = "notification_tint_normal";
        if (severityLevel == SafetySourceData.SEVERITY_LEVEL_CRITICAL_WARNING) {
            colorResName = "notification_tint_critical";
        }
        return mResourcesContext.getColorByName(colorResName);
    }

    private Bundle getNotificationExtras() {
        Bundle extras = new Bundle();
        String appName = mResourcesContext.getStringByName("notification_channel_group_name");
        if (!TextUtils.isEmpty(appName)) {
            extras.putString(Notification.EXTRA_SUBSTITUTE_APP_NAME, appName);
        }
        return extras;
    }

    private Notification.Action toNotificationAction(
            SafetyCenterIssueKey issueKey, SafetySourceIssue.Action issueAction) {
        PendingIntent pendingIntent = getPendingIntentForAction(issueKey, issueAction);
        return new Notification.Action.Builder(null, issueAction.getLabel(), pendingIntent).build();
    }

    private PendingIntent getPendingIntentForAction(
            SafetyCenterIssueKey issueKey, SafetySourceIssue.Action issueAction) {
        if (issueAction.willResolve()) {
            return getReceiverPendingIntentForResolvingAction(issueKey, issueAction);
        } else {
            return getDirectPendingIntentForNonResolvingAction(issueKey, issueAction);
        }
    }

    private PendingIntent getReceiverPendingIntentForResolvingAction(
            SafetyCenterIssueKey issueKey, SafetySourceIssue.Action issueAction) {
        // We do not use the action's PendingIntent directly here instead we build a new PI which
        // will be handled by our SafetyCenterNotificationReceiver which will in turn dispatch
        // the source-provided action PI. This ensures that action execution is consistent across
        // between Safety Center UI and notifications, for example executing an action from a
        // notification will send an "action in-flight" update to any current listeners.
        SafetyCenterIssueActionId issueActionId =
                SafetyCenterIssueActionId.newBuilder()
                        .setSafetyCenterIssueKey(issueKey)
                        .setSafetySourceIssueActionId(issueAction.getId())
                        .build();
        return SafetyCenterNotificationReceiver.newNotificationActionClickedIntent(
                mContext, issueActionId);
    }

    private PendingIntent getDirectPendingIntentForNonResolvingAction(
            SafetyCenterIssueKey issueKey, SafetySourceIssue.Action issueAction) {
        return issueAction.getPendingIntent();
    }

    private static boolean isDarkTheme(Context context) {
        return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK)
                == Configuration.UI_MODE_NIGHT_YES;
    }
}
