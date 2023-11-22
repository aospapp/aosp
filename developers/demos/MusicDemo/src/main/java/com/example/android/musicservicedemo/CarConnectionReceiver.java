/*
 * Copyright (C) 2014 The Android Open Source Project
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
package com.example.android.musicservicedemo;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;

import com.example.android.musicservicedemo.utils.LogHelper;

/**
 * Broadcast receiver that gets notified whenever the device is connected to a compatible car.
 */
public class CarConnectionReceiver extends BroadcastReceiver {

    private static final String TAG = "CarPlugReceiver";

    private static final String CONNECTED_ACTION = "com.google.android.gms.car.CONNECTED";
    private static final String DISCONNECTED_ACTION = "com.google.android.gms.car.DISCONNECTED";

    @Override
    public void onReceive(Context context, Intent intent) {
        if (CONNECTED_ACTION.equals(intent.getAction())) {
            LogHelper.i(TAG, "Device is connected to Android Auto");
        } else if (DISCONNECTED_ACTION.equals(intent.getAction())) {
            LogHelper.i(TAG, "Device is disconnected from Android Auto");
        } else {
            LogHelper.w(TAG, "Received unexpected broadcast intent. Intent action: ",
                    intent.getAction());
        }
    }
}
