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

package com.android.car.dialer.sms;

import static com.android.car.messenger.common.MessagingUtils.ACTION_DIRECT_SEND;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.os.IBinder;
import android.util.Log;

import com.android.car.dialer.R;
import com.android.car.messenger.common.MessagingUtils;

import dagger.hilt.android.AndroidEntryPoint;

/**
 * Service used to send SMS from the assistant
 */
@AndroidEntryPoint(Service.class)
public class MessagingService extends Hilt_MessagingService {
    private static final String TAG = "CD.MessagingService";

    private static final String CHANNEL_ID = "ID";

    @Override
    public void onCreate() {
        super.onCreate();
        sendServiceRunningNotification();
    }

    private void sendServiceRunningNotification() {
        NotificationManager mgr = getSystemService(NotificationManager.class);

        NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "title",
                NotificationManager.IMPORTANCE_LOW);
        mgr.createNotificationChannel(channel);

        Notification notification = new Notification.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.sms_notification_title))
                .setContentText(getString(R.string.sms_notification_text))
                .build();

        startForeground(Integer.MAX_VALUE, notification);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent == null || intent.getAction() == null) {
            return START_STICKY;
        }

        String action = intent.getAction();

        Log.d(TAG, "action: " + action);

        switch (action) {
            case ACTION_DIRECT_SEND:
                MessagingUtils.directSend(this, intent);
                break;
            default:
                break;
        }

        return START_STICKY;
    }
}
