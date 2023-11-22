/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.androidbvt;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;
import android.widget.Toast;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * BroadcastReceiver that listens for package-added events.
 */
public class PackageAddedBroadcastReceiver extends BroadcastReceiver {

    private static final String TEST_TAG = "AndroidBVT";
    public static AtomicBoolean isInstalled = new AtomicBoolean(false);

    @Override
    public void onReceive(Context context, Intent arg1) {
        Log.v(TEST_TAG, "there is a broadcast for app installation");
        isInstalled.compareAndSet(false, true);
    }

    // Tries 10 times/10 secs for install to be completed
    public boolean isInstallCompleted()
            throws InterruptedException {
        int counter = 10;
        while (--counter > 0) {
            if (isInstalled.get()) {
                return true;
            }
            Thread.sleep(1000);
        }
        return false;
    }
}