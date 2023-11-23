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
 * limitations under the License
 */

package android.server.wm.backgroundactivity.appb;

import android.app.Activity;
import android.app.ActivityOptions;
import android.app.PendingIntent;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

/**
 * Receive pending intent from AppA and launch it
 */
public class StartPendingIntentActivity extends Activity {
    private Components mB;

    public static final String TAG = "StartPendingIntentActivity";

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        mB = Components.get(getApplicationContext());

        Intent intent = getIntent();

        final PendingIntent pendingIntent = intent.getParcelableExtra(
                mB.START_PENDING_INTENT_RECEIVER_EXTRA.PENDING_INTENT);
        try {
            final Bundle bundle;
            if (intent.hasExtra(mB.START_PENDING_INTENT_ACTIVITY_EXTRA.ALLOW_BAL)) {
                ActivityOptions options = ActivityOptions.makeBasic();
                final boolean allowBal = intent.getBooleanExtra(
                        mB.START_PENDING_INTENT_ACTIVITY_EXTRA.ALLOW_BAL, false);
                options.setPendingIntentBackgroundActivityLaunchAllowed(allowBal);
                bundle = options.toBundle();
            } else if (intent.getBooleanExtra(
                    mB.START_PENDING_INTENT_ACTIVITY_EXTRA.USE_NULL_BUNDLE, false)) {
                bundle = null;
            } else {
                bundle = ActivityOptions.makeBasic().toBundle();
            }
            Log.i(TAG, "pendingIntent.send with bundle: " + bundle);
            pendingIntent.send(bundle);
        } catch (PendingIntent.CanceledException e) {
            throw new AssertionError(e);
        }
    }
}
