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

package com.google.android.wearable.support;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.support.v4.app.NotificationCompat;
import android.support.v4.app.NotificationCompat.WearableExtender;
import android.support.v4.app.NotificationManagerCompat;
import android.support.v4.app.RemoteInput;
import android.widget.Toast;


/**
 * Receiver to generate notification cards.
 */
public class CustomPostNotificationReceiver extends BroadcastReceiver {

    public static final String REMOTE_INPUT_KEY = "demoCardQuickReply";
    private static final int NOTIFICATION_CARDS_COUNT = 10;

    public CustomPostNotificationReceiver() {
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String contentTitle = context.getResources().getString(R.string.notification_title);
        // Get an instance of NotificationManager service
        NotificationManagerCompat notificationManager = NotificationManagerCompat.from(context);
        for (int i = 1; i <= NOTIFICATION_CARDS_COUNT; i++) {
            Intent remoteInputIntent = new Intent(context,
                    CustomNotificationRemoteInputActivity.class);
            PendingIntent remoteInputPendingIntent = PendingIntent
                    .getActivity(context, i + NOTIFICATION_CARDS_COUNT, remoteInputIntent,
                            PendingIntent.FLAG_UPDATE_CURRENT);
            RemoteInput remoteInput = new RemoteInput.Builder(REMOTE_INPUT_KEY)
                    .setLabel("Quick reply")
                    .build();

            NotificationCompat.Action action = new NotificationCompat.Action.Builder(
                    R.drawable.ic_reply, "Reply", remoteInputPendingIntent)
                    .addRemoteInput(remoteInput)
                    .build();

            NotificationCompat.Builder notificationBuilder = new NotificationCompat.Builder(context)
                    .setSmallIcon(R.drawable.ic_notification)
                    .setContentTitle(contentTitle + "-" + i)
                    .setCategory(NotificationCompat.CATEGORY_MESSAGE)
                    .setContentText(String.format("From iteration %d", i))
                    .extend(new WearableExtender()
                            .addAction(action));

            // Build the notification and issues it with notification manager
            notificationManager.notify(i, notificationBuilder.build());
        }

        // Show a toast once notifications cards are posted
        Toast.makeText(context, context.getString(R.string.notification_posted), Toast.LENGTH_SHORT)
                .show();
    }
}