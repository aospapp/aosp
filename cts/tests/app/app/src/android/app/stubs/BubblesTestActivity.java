/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.stubs;

import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.Bundle;
import android.provider.Telephony;
import android.util.Log;

/**
 * Used by NotificationManagerTest for testing policy around bubbles.
 */
public class BubblesTestActivity extends Activity {
    final String TAG = BubblesTestActivity.class.getSimpleName();

    // Should be same as wht NotificationManagerTest is using
    private static final String NOTIFICATION_CHANNEL_ID = "NotificationManagerTest";

    public static final String BUBBLE_ACTIVITY_OPENED =
            "android.app.stubs.BUBBLE_ACTIVITY_OPENED";
    public static final int BUBBLE_NOTIF_ID = 1;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main);

        Intent i = new Intent(BUBBLE_ACTIVITY_OPENED);
        sendBroadcast(i);
    }

    /**
     * Sends a bubble notification that would only be allowed to bubble when the app is
     * foreground.
     */
    public void sendBubble(int i) {
        Context context = getApplicationContext();

        final Intent intent = new Intent(context, BubblesTestActivity.class);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP
                | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        intent.setAction(Intent.ACTION_MAIN);
        final PendingIntent pendingIntent =
                PendingIntent.getActivity(context, 0, intent, 0);

        Notification.BubbleMetadata data = new Notification.BubbleMetadata.Builder()
                .setIcon(Icon.createWithResource(context, R.drawable.black))
                .setIntent(pendingIntent)
                .build();
        Notification n = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.black)
                .setWhen(System.currentTimeMillis())
                .setContentTitle("notify#" + BUBBLE_NOTIF_ID)
                .setContentText("This is #" + BUBBLE_NOTIF_ID + "notification  ")
                .setContentIntent(pendingIntent)
                .setBubbleMetadata(data)
                .build();

        NotificationManager noMan = (NotificationManager) context.getSystemService(
                Context.NOTIFICATION_SERVICE);
        noMan.notify(BUBBLE_NOTIF_ID, n);
        Log.d(TAG, "posting bubble: " + n + ", " + i);
    }
}
