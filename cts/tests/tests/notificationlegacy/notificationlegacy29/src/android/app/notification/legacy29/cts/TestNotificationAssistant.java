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

package android.app.notification.legacy29.cts;

import android.app.NotificationManager;
import android.content.ComponentName;
import android.os.Bundle;
import android.service.notification.Adjustment;
import android.service.notification.NotificationAssistantService;
import android.service.notification.StatusBarNotification;

import java.util.List;

public class TestNotificationAssistant extends NotificationAssistantService {
    public static final String TAG = "TestNotificationAssistant";
    public static final String PKG = "android.app.notification.legacy29.cts";

    private static TestNotificationAssistant sNotificationAssistantInstance = null;
    boolean isConnected;
    public List<String> currentCapabilities;
    private NotificationManager mNotificationManager;

    public static String getId() {
        return String.format("%s/%s", TestNotificationAssistant.class.getPackage().getName(),
                TestNotificationAssistant.class.getName());
    }

    public static ComponentName getComponentName() {
        return new ComponentName(TestNotificationAssistant.class.getPackage().getName(),
                TestNotificationAssistant.class.getName());
    }

    @Override
    public void onCreate() {
        super.onCreate();
        mNotificationManager = getSystemService(NotificationManager.class);
    }

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        sNotificationAssistantInstance = this;
        isConnected = true;
    }

    @Override
    public void onListenerDisconnected() {
        isConnected = false;
    }

    public static TestNotificationAssistant getInstance() {
        return sNotificationAssistantInstance;
    }

    @Override
    public void onNotificationSnoozedUntilContext(StatusBarNotification statusBarNotification,
            String s) {
    }

    @Override
    public Adjustment onNotificationEnqueued(StatusBarNotification sbn) {
        Bundle signals = new Bundle();
        signals.putInt(Adjustment.KEY_USER_SENTIMENT, Ranking.USER_SENTIMENT_POSITIVE);
        return new Adjustment(sbn.getPackageName(), sbn.getKey(), signals, "",
                sbn.getUser());
    }

    @Override
    public void onAllowedAdjustmentsChanged() {
        currentCapabilities = mNotificationManager.getAllowedAssistantAdjustments();
    }
}