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

package com.android.tests.stagedinstall;

import static com.google.common.truth.Truth.assertThat;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInstaller;
import android.util.Log;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class SessionUpdateBroadcastReceiver extends BroadcastReceiver {

    static final BlockingQueue<PackageInstaller.SessionInfo> sessionBroadcasts
            = new LinkedBlockingQueue<>();

    private static final String TAG = "StagedInstallTest";

    @Override
    public void onReceive(Context context, Intent intent) {
        PackageInstaller.SessionInfo info =
                intent.getParcelableExtra(PackageInstaller.EXTRA_SESSION);
        assertThat(info).isNotNull();
        Log.i(TAG, "Received SESSION_UPDATED for session " + info.getSessionId()
                + " isReady:" + info.isStagedSessionReady()
                + " isFailed:" + info.isStagedSessionFailed()
                + " isApplied:" + info.isStagedSessionApplied());
        try {
            sessionBroadcasts.put(info);
        } catch (InterruptedException e) {

        }
    }
}
