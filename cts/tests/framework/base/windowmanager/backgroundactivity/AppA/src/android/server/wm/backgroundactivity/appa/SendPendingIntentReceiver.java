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

package android.server.wm.backgroundactivity.appa;

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

import java.util.Optional;

/**
 * Receive broadcast command to create a pendingIntent and send it to AppB.
 */
public class SendPendingIntentReceiver extends BroadcastReceiver {
    private static final String TAG = "SendPendingIntentReceiver";

    private static Bundle createBundleWithBalEnabled() {
        ActivityOptions result =
                ActivityOptions.makeBasic();
        result.setPendingIntentBackgroundActivityLaunchAllowed(true);
        return result.toBundle();
    }

    @Override
    public void onReceive(Context context, Intent receivedIntent) {
        Components appA = Components.get(context);

        boolean isBroadcast = receivedIntent.getBooleanExtra(
                appA.SEND_PENDING_INTENT_RECEIVER_EXTRA.IS_BROADCAST, false);
        int startActivityDelayMs = receivedIntent.getIntExtra(
                appA.START_ACTIVITY_RECEIVER_EXTRA.START_ACTIVITY_DELAY_MS, 0);

        Optional<Boolean> extraBalOnIntent =
                getOptionalBooleanExtra(receivedIntent,
                        appA.SEND_PENDING_INTENT_RECEIVER_EXTRA.ALLOW_BAL_EXTRA_ON_PENDING_INTENT,
                        false);

        ResultReceiver eventNotifier = receivedIntent.getParcelableExtra(EVENT_NOTIFIER_EXTRA);

        if (eventNotifier != null) {
            eventNotifier.send(Event.APP_A_SEND_PENDING_INTENT_BROADCAST_RECEIVED, null);
        }

        final PendingIntent pendingIntent;
        if (isBroadcast) {
            // Create a pendingIntent to launch send broadcast to appA and appA will start
            // background activity.
            Intent newIntent = new Intent();
            newIntent.setClass(context, StartBackgroundActivityReceiver.class);
            newIntent.putExtra(appA.START_ACTIVITY_RECEIVER_EXTRA.START_ACTIVITY_DELAY_MS,
                    startActivityDelayMs);
            newIntent.putExtra(EVENT_NOTIFIER_EXTRA, eventNotifier);
            if (extraBalOnIntent.orElse(false)) {
                newIntent.putExtras(createBundleWithBalEnabled());
            }
            pendingIntent =
                    PendingIntent.getBroadcast(
                            context,
                            0,
                            newIntent,
                            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        } else {
            // Create a pendingIntent to launch appA's BackgroundActivity
            Intent newIntent = new Intent();
            newIntent.setClass(context, BackgroundActivity.class);
            newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            if (extraBalOnIntent.orElse(false)) {
                newIntent.putExtras(createBundleWithBalEnabled());
            }
            pendingIntent =
                    PendingIntent.getActivity(
                            context,
                            0,
                            newIntent,
                            PendingIntent.FLAG_CANCEL_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        }

        String appBPackage = receivedIntent.getStringExtra(
                appA.SEND_PENDING_INTENT_RECEIVER_EXTRA.APP_B_PACKAGE);
        if (appBPackage == null) {
            appBPackage = android.server.wm.backgroundactivity.appb.Components.JAVA_PACKAGE_NAME;
        }

        android.server.wm.backgroundactivity.appb.Components appB =
                android.server.wm.backgroundactivity.appb.Components.get(appBPackage);

        Optional<Boolean> extraBal =
                getOptionalBooleanExtra(receivedIntent,
                        appB.START_PENDING_INTENT_ACTIVITY_EXTRA.ALLOW_BAL, false);
        Optional<Boolean> useNullBundle =
                getOptionalBooleanExtra(receivedIntent,
                        appB.START_PENDING_INTENT_ACTIVITY_EXTRA.USE_NULL_BUNDLE, false);


        // Send the pendingIntent to appB
        Intent intent = new Intent();
        intent.setComponent(appB.START_PENDING_INTENT_RECEIVER);
        intent.putExtra(appB.START_PENDING_INTENT_RECEIVER_EXTRA.PENDING_INTENT, pendingIntent);
        intent.putExtra(EVENT_NOTIFIER_EXTRA, eventNotifier);
        extraBal.ifPresent(v -> intent
                .putExtra(appB.START_PENDING_INTENT_ACTIVITY_EXTRA.ALLOW_BAL, v));
        useNullBundle.ifPresent(v -> intent
                .putExtra(appB.START_PENDING_INTENT_ACTIVITY_EXTRA.USE_NULL_BUNDLE, v));
        Log.d(TAG, "sendBroadcast(" + intent + ") from " + context.getPackageName() + " at "
                + context.getApplicationInfo().targetSdkVersion);
        context.sendBroadcast(intent);
    }

    private static Optional<Boolean> getOptionalBooleanExtra(
            Intent receivedIntent, String key, boolean defaultValue) {
        return receivedIntent.hasExtra(key)
                ? Optional.of(receivedIntent.getBooleanExtra(key, defaultValue))
                : Optional.empty();
    }
}
