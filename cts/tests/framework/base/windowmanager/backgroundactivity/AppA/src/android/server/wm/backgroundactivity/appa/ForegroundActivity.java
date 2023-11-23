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

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Bundle;
import android.os.SystemClock;

/**
 * Foreground activity that makes AppA as foreground.
 */
public class ForegroundActivity extends Activity {
    private Components mA;

    private int mActivityId = -1;
    private boolean mRelaunch = false;

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            int activityId = intent.getIntExtra(mA.FOREGROUND_ACTIVITY_EXTRA.ACTIVITY_ID,
                    mActivityId);
            if (activityId != mActivityId) {
                return;
            }

            if (mA.FOREGROUND_ACTIVITY_ACTIONS.FINISH_ACTIVITY.equals(action)
                    || intent.getBooleanExtra(mA.FOREGROUND_ACTIVITY_EXTRA.FINISH_FIRST, false))  {
                finish();
            }

            if (mA.FOREGROUND_ACTIVITY_ACTIONS.LAUNCH_BACKGROUND_ACTIVITIES.equals(action)) {
                // Need to copy as a new array instead of just casting to Intent[] since a new
                // array of type Parcelable[] is created when deserializing.
                Intent[] intents = intent.getParcelableArrayExtra(
                        mA.FOREGROUND_ACTIVITY_EXTRA.LAUNCH_INTENTS, Intent.class);
                startActivities(intents);
            }
        }
    };

    @Override
    public void onCreate(Bundle bundle) {
        super.onCreate(bundle);
        mA = Components.get(getApplicationContext());

        Intent intent = getIntent();
        mRelaunch = intent.getBooleanExtra(
                mA.FOREGROUND_ACTIVITY_EXTRA.RELAUNCH_FOREGROUND_ACTIVITY_EXTRA, false);
        mActivityId = intent.getIntExtra(mA.FOREGROUND_ACTIVITY_EXTRA.ACTIVITY_ID, -1);

        boolean launchBackground = intent.getBooleanExtra(
                mA.FOREGROUND_ACTIVITY_EXTRA.LAUNCH_BACKGROUND_ACTIVITY, false);
        final int delay = intent.getIntExtra(
                mA.FOREGROUND_ACTIVITY_EXTRA.START_ACTIVITY_FROM_FG_ACTIVITY_DELAY_MS, 0);
        boolean newTask = intent.getBooleanExtra(
                mA.FOREGROUND_ACTIVITY_EXTRA.START_ACTIVITY_FROM_FG_ACTIVITY_NEW_TASK, false);
        if (launchBackground) {
            final Intent newIntent = new Intent();
            newIntent.setClass(this, BackgroundActivity.class);
            if (newTask) {
                newIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            }
            if (delay == 0) {
                startActivity(newIntent);
            } else {
                new Thread(() -> {
                    SystemClock.sleep(delay);
                    startActivity(newIntent);
                }).start();
            }
        }

        boolean launchSecond = intent.getBooleanExtra(
                mA.FOREGROUND_ACTIVITY_EXTRA.LAUNCH_SECOND_BACKGROUND_ACTIVITY, false);
        if (launchSecond) {
            Intent newIntent = new Intent();
            newIntent.setClass(this, SecondBackgroundActivity.class);
            startActivity(newIntent);
        }
        IntentFilter filter = new IntentFilter();
        filter.addAction(mA.FOREGROUND_ACTIVITY_ACTIONS.LAUNCH_BACKGROUND_ACTIVITIES);
        filter.addAction(mA.FOREGROUND_ACTIVITY_ACTIONS.FINISH_ACTIVITY);
        registerReceiver(mReceiver, filter, Context.RECEIVER_EXPORTED);
    }

    @Override
    protected void onPause() {
        super.onPause();

        if (mRelaunch) {
            mRelaunch = false;
            SystemClock.sleep(50);
            startActivity(getIntent());
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterReceiver(mReceiver);
    }
}
