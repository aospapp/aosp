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
 * limitations under the License.
 */

package com.android.car.user;

import android.os.IBinder.DeathRecipient;
import android.os.RemoteException;

import com.android.car.CarLog;
import com.android.internal.os.IResultReceiver;
import com.android.server.utils.Slogf;

import java.io.PrintWriter;

/**
 * Helper DTO to hold info about an app-based {@code UserLifecycleListener}
 */
final class AppLifecycleListener {

    private static final String TAG = CarLog.tagFor(AppLifecycleListener.class);

    private final DeathRecipient mDeathRecipient;

    public final int uid;
    public final String packageName;
    public final IResultReceiver receiver;

    AppLifecycleListener(int uid, String packageName, IResultReceiver receiver,
            BinderDeathCallback binderDeathCallback) {
        this.uid = uid;
        this.packageName = packageName;
        this.receiver = receiver;

        mDeathRecipient = () -> binderDeathCallback.onBinderDeath(this);
        Slogf.v(TAG, "linking death recipient %s", mDeathRecipient);
        try {
            receiver.asBinder().linkToDeath(mDeathRecipient, /* flags= */ 0);
        } catch (RemoteException e) {
            Slogf.wtf(TAG, "Cannot listen to death of %s", mDeathRecipient);
        }
    }

    void onDestroy() {
        Slogf.v(TAG, "onDestroy(): unlinking death recipient %s", mDeathRecipient);
        receiver.asBinder().unlinkToDeath(mDeathRecipient, /* flags= */ 0);
    }

    void dump(PrintWriter writer) {
        writer.printf("uid=%d, pkg=%s\n", uid, packageName);
    }

    String toShortString() {
        return uid + "-" + packageName;
    }

    @Override
    public String toString() {
        return "AppLifecycleListener[uid=" + uid + ", pkg=" + packageName + "]";
    }

    interface BinderDeathCallback {
        void onBinderDeath(AppLifecycleListener listener);
    }
}
