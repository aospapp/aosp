/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.storagemanager.automatic;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Resources;
import android.provider.Settings;

import com.android.storagemanager.R;

import java.util.concurrent.TimeUnit;

/**
 * NotificationController handles the responses to the Automatic Storage Management low storage
 * notification.
 */
public class NotificationController extends BroadcastReceiver {
    /**
     * Intent action for if the user taps "Turn on" for the automatic storage manager.
     */
    public static final String INTENT_ACTION_ACTIVATE_ASM =
            "com.android.storagemanager.automatic.ACTIVATE";

    /**
     * Intent action for if the user swipes the notification away.
     */
    public static final String INTENT_ACTION_DISMISS =
            "com.android.storagemanager.automatic.DISMISS";

    /**
     * Intent action for if the user explicitly hits "No thanks" on the notification.
     */
    public static final String INTENT_ACTION_NO_THANKS =
            "com.android.storagemanager.automatic.NO_THANKS";


    /**
     * Intent action for if the user explicitly hits "No thanks" on the notification.
     */
    private static final String INTENT_ACTION_DEBUG_NOTIFICATION =
            "com.android.storagemanager.automatic.DEBUG_SHOW_NOTIFICATION";

    /**
     * Intent extra for the notification id.
     */
    public static final String INTENT_EXTRA_ID = "id";

    private static final String SHARED_PREFERENCES_NAME = "NotificationController";
    private static final String NOTIFICATION_NEXT_SHOW_TIME = "notification_next_show_time";
    private static final String NOTIFICATION_SHOWN_COUNT = "notification_shown_count";

    private static final long DISMISS_DELAY = TimeUnit.DAYS.toMillis(15);
    private static final long NO_THANKS_DELAY = TimeUnit.DAYS.toMillis(90);
    private static final long MAXIMUM_SHOWN_COUNT = 4;
    private static final int NOTIFICATION_ID = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        switch (intent.getAction()) {
            case INTENT_ACTION_ACTIVATE_ASM:
                Settings.Secure.putInt(context.getContentResolver(),
                        Settings.Secure.AUTOMATIC_STORAGE_MANAGER_ENABLED,
                        1);
                break;
            case INTENT_ACTION_NO_THANKS:
                delayNextNotification(context, NO_THANKS_DELAY);
                break;
            case INTENT_ACTION_DISMISS:
                delayNextNotification(context, DISMISS_DELAY);
                break;
            case INTENT_ACTION_DEBUG_NOTIFICATION:
                showNotification(context);
                return;
        }
        cancelNotification(context, intent);
    }

    /**
     * If the conditions for showing the activation notification are met, show the activation
     * notification.
     * @param context Context to use for getting resources and to display the notification.
     */
    public static void maybeShowNotification(Context context) {
        if (shouldShowNotification(context)) {
            showNotification(context);
        }
    }

    private static boolean shouldShowNotification(Context context) {
        SharedPreferences sp = context.getSharedPreferences(
                SHARED_PREFERENCES_NAME,
                Context.MODE_PRIVATE);
        int timesShown = sp.getInt(NOTIFICATION_SHOWN_COUNT, 0);
        if (timesShown > MAXIMUM_SHOWN_COUNT) {
            return false;
        }

        long nextTimeToShow = sp.getLong(NOTIFICATION_NEXT_SHOW_TIME, 0);

        return System.currentTimeMillis() > nextTimeToShow;
    }

    private static void showNotification(Context context) {
        Resources res = context.getResources();
        Intent noThanksIntent = new Intent(INTENT_ACTION_NO_THANKS);
        noThanksIntent.putExtra(INTENT_EXTRA_ID, NOTIFICATION_ID);
        Notification.Action.Builder cancelAction = new Notification.Action.Builder(null,
                res.getString(R.string.automatic_storage_manager_cancel_button),
                PendingIntent.getBroadcast(context, 0, noThanksIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT));


        Intent activateIntent = new Intent(INTENT_ACTION_ACTIVATE_ASM);
        activateIntent.putExtra(INTENT_EXTRA_ID, NOTIFICATION_ID);
        Notification.Action.Builder activateAutomaticAction = new Notification.Action.Builder(null,
                res.getString(R.string.automatic_storage_manager_activate_button),
                PendingIntent.getBroadcast(context, 0, activateIntent,
                        PendingIntent.FLAG_UPDATE_CURRENT));

        Intent dismissIntent = new Intent(INTENT_ACTION_DISMISS);
        dismissIntent.putExtra(INTENT_EXTRA_ID, NOTIFICATION_ID);
        PendingIntent deleteIntent = PendingIntent.getBroadcast(context, 0,
                new Intent(INTENT_ACTION_DISMISS),
                PendingIntent.FLAG_ONE_SHOT);

        Notification.Builder builder = new Notification.Builder(context)
                .setSmallIcon(R.drawable.ic_settings_24dp)
                .setContentTitle(
                        res.getString(R.string.automatic_storage_manager_notification_title))
                .setContentText(
                        res.getString(R.string.automatic_storage_manager_notification_summary))
                .setStyle(new Notification.BigTextStyle().bigText(
                        res.getString(R.string.automatic_storage_manager_notification_summary)))
                .addAction(cancelAction.build())
                .addAction(activateAutomaticAction.build())
                .setDeleteIntent(deleteIntent);

        NotificationManager manager =
                ((NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE));
        manager.notify(NOTIFICATION_ID, builder.build());
    }

    private void cancelNotification(Context context, Intent intent) {
        int id = intent.getIntExtra(INTENT_EXTRA_ID, -1);
        if (id == -1) {
            return;
        }
        NotificationManager manager = (NotificationManager) context
                .getSystemService(Context.NOTIFICATION_SERVICE);
        manager.cancel(id);

        incrementNotificationShownCount(context);
    }

    private void incrementNotificationShownCount(Context context) {
        SharedPreferences sp = context.getSharedPreferences(SHARED_PREFERENCES_NAME,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        int shownCount = sp.getInt(NotificationController.NOTIFICATION_SHOWN_COUNT, 0) + 1;
        editor.putInt(NotificationController.NOTIFICATION_SHOWN_COUNT, shownCount);
        editor.apply();
    }

    private void delayNextNotification(Context context, long timeInMillis) {
        SharedPreferences sp = context.getSharedPreferences(SHARED_PREFERENCES_NAME,
                Context.MODE_PRIVATE);
        SharedPreferences.Editor editor = sp.edit();
        editor.putLong(NOTIFICATION_NEXT_SHOW_TIME,
                System.currentTimeMillis() + timeInMillis);
        editor.apply();
    }
}
