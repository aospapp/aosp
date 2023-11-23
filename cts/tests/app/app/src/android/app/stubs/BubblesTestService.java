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

import static android.app.Notification.CATEGORY_CALL;

import android.app.Notification;
import android.app.PendingIntent;
import android.app.Person;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.drawable.Icon;
import android.os.IBinder;

/**
 * Used by NotificationManagerTest for testing policy around bubbles.
 */
public class BubblesTestService extends Service {

    // Should be same as wht NotificationManagerTest is using
    private static final String NOTIFICATION_CHANNEL_ID = "NotificationManagerTest";

    // Must configure foreground service notification in different ways for different tests
    public static final String EXTRA_TEST_CASE =
            "android.app.stubs.BubbleTestService.EXTRA_TEST_CASE";
    public static final int TEST_SUCCESS = 0;
    public static final int TEST_NO_PERSON = 1;
    public static final int TEST_NO_CATEGORY = 2;
    public static final int TEST_NO_BUBBLE_METADATA = 3;

    public static final int BUBBLE_NOTIF_ID = 1;

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        final int testCase = intent.getIntExtra(EXTRA_TEST_CASE, TEST_SUCCESS);
        Notification n = getNotificationForTest(testCase, getApplicationContext());
        startForeground(BUBBLE_NOTIF_ID, n);
        return START_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    private Notification getNotificationForTest(final int testCase, final Context context) {
        final Intent intent = new Intent(context, BubblesTestActivity.class);
        final PendingIntent pendingIntent =
                PendingIntent.getActivity(getApplicationContext(), 0, intent, 0);
        Notification.Builder nb = new Notification.Builder(context, NOTIFICATION_CHANNEL_ID)
                .setContentTitle("foofoo")
                .setContentIntent(pendingIntent)
                .setSmallIcon(android.R.drawable.sym_def_app_icon);
        Person person = new Person.Builder()
                .setName("bubblebot")
                .build();
        Notification.BubbleMetadata data = new Notification.BubbleMetadata.Builder()
                .setIcon(Icon.createWithResource(context, R.drawable.black))
                .setIntent(pendingIntent)
                .build();
        if (testCase != TEST_NO_PERSON) {
            nb.addPerson(person);
        }
        if (testCase != TEST_NO_CATEGORY) {
            nb.setCategory(CATEGORY_CALL);
        }
        if (testCase != TEST_NO_BUBBLE_METADATA) {
            nb.setBubbleMetadata(data);
        }
        return nb.build();
    }
}
