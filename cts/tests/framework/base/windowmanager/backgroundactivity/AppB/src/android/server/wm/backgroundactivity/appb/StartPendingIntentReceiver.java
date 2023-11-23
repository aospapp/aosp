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
 * limitations under the License
 */

package android.server.wm.backgroundactivity.appb;

import static android.server.wm.backgroundactivity.common.CommonComponents.EVENT_NOTIFIER_EXTRA;

import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.ResultReceiver;
import android.server.wm.backgroundactivity.common.CommonComponents.Event;
import android.util.Log;

/**
 * Receive pending intent from AppA and launch it
 */
public class StartPendingIntentReceiver extends BroadcastReceiver {
    public static final String TAG = "StartPendingIntentReceiver";

    @Override
    public void onReceive(Context context, Intent intent) {
        Components appB = Components.get(context);

        PendingIntent pendingIntent = intent.getParcelableExtra(
                appB.START_PENDING_INTENT_RECEIVER_EXTRA.PENDING_INTENT);
        ResultReceiver eventNotifier = intent.getParcelableExtra(EVENT_NOTIFIER_EXTRA);
        if (eventNotifier != null) {
            eventNotifier.send(Event.APP_B_START_PENDING_INTENT_BROADCAST_RECEIVED, null);
        }

        try {
            Bundle bundle;
            if (intent.hasExtra(appB.START_PENDING_INTENT_ACTIVITY_EXTRA.ALLOW_BAL)) {
                ActivityOptions options = ActivityOptions.makeBasic();
                final boolean allowBal = intent.getBooleanExtra(
                        appB.START_PENDING_INTENT_ACTIVITY_EXTRA.ALLOW_BAL, false);
                options.setPendingIntentBackgroundActivityLaunchAllowed(allowBal);
                bundle = options.toBundle();
            } else if (intent.getBooleanExtra(
                    appB.START_PENDING_INTENT_ACTIVITY_EXTRA.USE_NULL_BUNDLE, false)) {
                bundle = null;
            } else {
                bundle = ActivityOptions.makeBasic().toBundle();
            }
            Log.d(TAG, "sending " + pendingIntent + " with " + bundle + " from "
                    + context.getPackageName() + " at "
                    + context.getApplicationInfo().targetSdkVersion);
            pendingIntent.send(bundle);
        } catch (PendingIntent.CanceledException e) {
            e.printStackTrace();
        }
    }
}
