/*
** Copyright 2019, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

package com.google.android.yukawaservice;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.view.KeyEvent;

import java.util.List;

/**
 * Handles global keys.
 */
public class RemoteSyncReceiver extends BroadcastReceiver {
    private static final String TAG = "YukawaGlobalKey";
    private static final String ACTION_CONNECT_INPUT_NORMAL =
            "com.google.android.intent.action.CONNECT_INPUT";
    private static final String INTENT_EXTRA_NO_INPUT_MODE = "no_input_mode";

    private static void sendPairingIntent(Context context, KeyEvent event) {
        Intent intent = new Intent(ACTION_CONNECT_INPUT_NORMAL)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);

        if (event != null) {
            intent.putExtra(INTENT_EXTRA_NO_INPUT_MODE, true)
                    .putExtra(Intent.EXTRA_KEY_EVENT, event);
        }
        context.startActivity(intent);
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (Intent.ACTION_GLOBAL_BUTTON.equals(intent.getAction())) {
            KeyEvent event = intent.getParcelableExtra(Intent.EXTRA_KEY_EVENT);
            int keyCode = event.getKeyCode();
            int keyAction = event.getAction();
            switch (keyCode) {
                case KeyEvent.KEYCODE_PAIRING:
                    if (keyAction == KeyEvent.ACTION_UP) {
                        sendPairingIntent(context, event);
                    }
                    break;

                default:
                    Log.e(TAG, "Unhandled KeyEvent: " + keyCode);
            }
        }
    }
}
