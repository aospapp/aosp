/*
** Copyright 2021, The Android Open Source Project
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

package com.google.android.tv.hotwordmic;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.os.Binder;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.UserManager;
import android.util.Log;

public class HotwordMicToggleService extends Service {
    private static final String TAG = "HotwordMicToggleService";

    private static final String POLLING_THREAD = "mic-switch-poll";
    private static final int MSG_CHECK_HOTWORD_MIC_TOGGLE = 300;
    private static final int HOTWORDMIC_DELAY_MS = 750;

    private final Binder mBinder = new LocalBinder();

    private boolean mMicMuted = false;
    private HandlerThread mHandlerThread;
    private Handler mPollHandler;

    private class PollHandler extends Handler {
        public PollHandler(Looper looper) { super(looper); }

        @Override
        public void handleMessage(Message m) {
            switch (m.what) {
                case MSG_CHECK_HOTWORD_MIC_TOGGLE:
                    checkMicToggleState();
                    break;
            }
        }
    }

    @Override
    public void onCreate() {
        super.onCreate();

        UserManager userManager = getSystemService(UserManager.class);
        if (!userManager.isSystemUser()) {
            Log.w(TAG, "Received boot-complete from non system user");
            return;
        }

        mHandlerThread = new HandlerThread(POLLING_THREAD);
        mHandlerThread.start();
        mPollHandler = new PollHandler(mHandlerThread.getLooper());
        start();
    }

    @Override
    public void onDestroy() {
        mHandlerThread.quitSafely();
        super.onDestroy();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return Service.START_STICKY;
    }

    private void start() { poll(MSG_CHECK_HOTWORD_MIC_TOGGLE, 100); }

    private void poll(int msg, int delay) {
        if (mPollHandler == null) {
            return;
        }
        mPollHandler.removeMessages(msg);
        mPollHandler.sendEmptyMessageDelayed(msg, delay);
    }

    private void checkMicToggleState() {
        poll(MSG_CHECK_HOTWORD_MIC_TOGGLE, HOTWORDMIC_DELAY_MS);

        boolean prevMicMuted = mMicMuted;
        mMicMuted = isMicMuted();
        if (prevMicMuted == mMicMuted) {
            return;
        }
        getContentResolver().notifyChange(HotwordMicToggleProvider.HOTWORDMIC_URI, null);
    }

    private boolean isMicMuted() {
        Cursor data = getContentResolver().query(
                HotwordMicToggleProvider.HOTWORDMIC_URI, null, null, null, null);
        if (data != null && data.moveToFirst()) {
            int state = data.getInt(0);
            if (state == 0) {
                return true;
            } else if (state == 1) {
                return false;
            }
        }
        return true;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class LocalBinder extends Binder {}
}
