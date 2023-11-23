/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.devicelock;

import android.content.Context;
import android.util.Slog;

import com.android.server.SystemService;

import java.util.Objects;

/**
 * Service implementing DeviceLock functionality. Delegates actual interface
 * implementation to DeviceLockServiceImpl.
 */
public final class DeviceLockService extends SystemService {

    private static final String TAG = "DeviceLockService";

    private final DeviceLockServiceImpl mImpl;

    public DeviceLockService(Context context) {
        super(context);
        Slog.d(TAG, "DeviceLockService constructor");
        mImpl = new DeviceLockServiceImpl(context);
    }

    @Override
    public void onStart() {
        Slog.i(TAG, "Registering " + Context.DEVICE_LOCK_SERVICE);
        publishBinderService(Context.DEVICE_LOCK_SERVICE, mImpl);
    }

    @Override
    public void onBootPhase(int phase) {
        Slog.d(TAG, "onBootPhase: " + phase);
    }

    @Override
    public void onUserSwitching(TargetUser from, TargetUser to) {
        Objects.requireNonNull(to);
        Slog.d(TAG, "onUserSwitching");
        mImpl.setDeviceLockControllerPackageDefaultEnabledState(to.getUserHandle());
    }

    @Override
    public void onUserUnlocking(TargetUser user) {
        Slog.d(TAG, "onUserUnlocking");
    }

    @Override
    public void onUserStopping(TargetUser user) {
        Slog.d(TAG, "onUserStopping");
    }
}
