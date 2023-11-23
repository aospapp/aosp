/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.connecteddevice.util;

import static com.android.car.connecteddevice.util.SafeLog.logd;

import android.os.IBinder;
import android.os.RemoteException;

import java.util.function.Consumer;

/**
 * Class that holds the binder of a remote callback and an action to be executed when this
 * binder dies.
 * It registers for death notification of the {@link #mCallbackBinder} and executes
 * {@link #mOnDiedConsumer} when {@link #mCallbackBinder} dies.
 */
public class RemoteCallbackBinder implements IBinder.DeathRecipient {
    private static final String TAG = "BinderClient";
    private final IBinder mCallbackBinder;
    private final Consumer<IBinder> mOnDiedConsumer;

    public RemoteCallbackBinder(IBinder binder, Consumer<IBinder> onBinderDied) {
        mCallbackBinder = binder;
        mOnDiedConsumer = onBinderDied;
        try {
            binder.linkToDeath(this, 0);
        } catch (RemoteException e) {
            logd(TAG, "Cannot link death recipient to binder " + mCallbackBinder + ", "
                    + e);
        }
    }

    @Override
    public void binderDied() {
        logd(TAG, "Binder died " + mCallbackBinder);
        mOnDiedConsumer.accept(mCallbackBinder);
        cleanUp();
    }

    /** Clean up the client. */
    public void cleanUp() {
        mCallbackBinder.unlinkToDeath(this, 0);
    }

    /** Get the callback binder of the client. */
    public IBinder getCallbackBinder() {
        return mCallbackBinder;
    }

    @Override
    public boolean equals(Object obj) {
        return mCallbackBinder.equals(obj);
    }

    @Override
    public int hashCode() {
        return mCallbackBinder.hashCode();
    }
}
