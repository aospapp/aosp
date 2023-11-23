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

package com.android.car.am;

import android.app.ActivityManager;
import android.car.Car;
import android.car.app.CarActivityManager;
import android.car.app.ICarActivityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Binder;
import android.os.RemoteException;
import android.os.UserHandle;
import android.util.IndentingPrintWriter;

import com.android.car.CarServiceBase;
import com.android.car.internal.ICarServiceHelper;
import com.android.internal.annotations.GuardedBy;
import com.android.internal.annotations.VisibleForTesting;

/**
 * Service responsible for Activities in Car.
 */
public final class CarActivityService extends ICarActivityService.Stub
        implements CarServiceBase {

    private final Context mContext;

    private final Object mLock = new Object();

    @GuardedBy("mLock")
    ICarServiceHelper mICarServiceHelper;

    public CarActivityService(Context context) {
        mContext = context;
    }

    @Override
    public void init() {}

    @Override
    public void release() {}

    /**
     * Sets {@code ICarServiceHelper}.
     */
    public void setICarServiceHelper(ICarServiceHelper helper) {
        synchronized (mLock) {
            mICarServiceHelper = helper;
        }
    }

    @Override
    public int setPersistentActivity(ComponentName activity, int displayId, int featureId) throws
            RemoteException {
        if (PackageManager.PERMISSION_GRANTED != mContext.checkCallingOrSelfPermission(
                Car.PERMISSION_CONTROL_CAR_APP_LAUNCH)) {
            throw new SecurityException("Requires " + Car.PERMISSION_CONTROL_CAR_APP_LAUNCH);
        }
        int caller = getCaller();
        if (caller != UserHandle.USER_SYSTEM && caller != ActivityManager.getCurrentUser()) {
            return CarActivityManager.RESULT_INVALID_USER;
        }

        ICarServiceHelper helper;
        synchronized (mLock) {
            helper = mICarServiceHelper;
        }
        if (helper == null) {
            throw new IllegalStateException("ICarServiceHelper isn't connected yet");
        }
        return helper.setPersistentActivity(activity, displayId, featureId);
    }

    @VisibleForTesting
    int getCaller() {  // Non static for mocking.
        return UserHandle.getUserId(Binder.getCallingUid());
    }

    @Override
    public void dump(IndentingPrintWriter writer) {}
}
