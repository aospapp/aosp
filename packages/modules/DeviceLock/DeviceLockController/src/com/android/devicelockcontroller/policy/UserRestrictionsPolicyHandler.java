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

package com.android.devicelockcontroller.policy;

import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.CLEARED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.KIOSK_SETUP;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.LOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.PSEUDO_LOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.PSEUDO_UNLOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.SETUP_FAILED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.SETUP_IN_PROGRESS;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.SETUP_SUCCEEDED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.UNLOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.UNPROVISIONED;

import android.app.admin.DevicePolicyManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.UserManager;
import android.util.ArraySet;

import androidx.annotation.MainThread;

import com.android.devicelockcontroller.policy.DeviceStateController.DeviceState;
import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.Collections;
import java.util.Locale;

/**
 * Enforces UserRestriction policies.
 */
final class UserRestrictionsPolicyHandler implements PolicyHandler {

    private static final String TAG = "UserRestrictionsPolicyHandler";

    private final ArraySet<String> mAlwaysOnRestrictions = new ArraySet<>();

    /**
     * A list of restrictions that will be always active, it is optional, partners can config the
     * list via provisioning configs.
     */
    private ArraySet<String> mOptionalAlwaysOnRestrictions;

    private ArraySet<String> mLockModeRestrictions;

    private final DevicePolicyManager mDpm;
    private final UserManager mUserManager;
    private final boolean mIsDebug;

    UserRestrictionsPolicyHandler(DevicePolicyManager dpm, UserManager userManager,
            boolean isDebug) {
        mDpm = dpm;
        mUserManager = userManager;
        mIsDebug = isDebug;

        LogUtil.i(TAG, String.format(Locale.US, "Build type DEBUG = %s", isDebug));

        Collections.addAll(mAlwaysOnRestrictions, UserManager.DISALLOW_SAFE_BOOT);
        if (!isDebug) {
            Collections.addAll(mAlwaysOnRestrictions, UserManager.DISALLOW_DEBUGGING_FEATURES);
        }
    }

    @Override
    @ResultType
    public ListenableFuture<@ResultType Integer> setPolicyForState(@DeviceState int state) {
        final Handler mainHandler = new Handler(Looper.getMainLooper());
        LogUtil.v(TAG, String.format(Locale.US, "Setting restrictions for %d", state));
        switch (state) {
            case SETUP_IN_PROGRESS:
            case SETUP_SUCCEEDED:
            case UNLOCKED:
            case KIOSK_SETUP:
                setupRestrictions(mAlwaysOnRestrictions, true);
                return Futures.whenAllSucceed(
                                setupRestrictions(retrieveOptionalAlwaysOnRestrictions(), true),
                                setupRestrictions(retrieveLockModeRestrictions(), false))
                        .call(
                                () -> SUCCESS, mainHandler::post);
            case LOCKED:
                setupRestrictions(mAlwaysOnRestrictions, true);
                return Futures.whenAllSucceed(
                                setupRestrictions(retrieveOptionalAlwaysOnRestrictions(), true),
                                setupRestrictions(retrieveLockModeRestrictions(), true))
                        .call(
                                () -> SUCCESS, mainHandler::post);
            case UNPROVISIONED:
            case SETUP_FAILED:
            case CLEARED:
                setupRestrictions(mAlwaysOnRestrictions, false);
                return Futures.whenAllSucceed(
                                setupRestrictions(retrieveOptionalAlwaysOnRestrictions(), false),
                                setupRestrictions(retrieveLockModeRestrictions(), false))
                        .call(
                                () -> SUCCESS, mainHandler::post);
            case PSEUDO_LOCKED:
            case PSEUDO_UNLOCKED:
                return Futures.immediateFuture(SUCCESS);
            default:
                return Futures.immediateFailedFuture(
                        new IllegalStateException(String.valueOf(state)));
        }


    }

    @MainThread
    public ListenableFuture<ArraySet<String>> retrieveLockModeRestrictions() {
        if (mLockModeRestrictions != null) return Futures.immediateFuture(mLockModeRestrictions);
        final SetupParametersClient parameters = SetupParametersClient.getInstance();
        final ListenableFuture<String> kioskPackageTask = parameters.getKioskPackage();
        final ListenableFuture<Boolean> outgoingCallsDisabledTask =
                parameters.getOutgoingCallsDisabled();
        return Futures.whenAllSucceed(kioskPackageTask,
                        outgoingCallsDisabledTask)
                .call(() -> {
                    if (Futures.getDone(kioskPackageTask) == null) {
                        throw new IllegalStateException("Setup parameters does not exist!");
                    }
                    if (mLockModeRestrictions == null) {
                        mLockModeRestrictions = new ArraySet<>(1);
                        if (Futures.getDone(outgoingCallsDisabledTask)) {
                            mLockModeRestrictions.add(UserManager.DISALLOW_OUTGOING_CALLS);
                        }
                    }
                    return mLockModeRestrictions;
                }, new Handler(Looper.getMainLooper())::post);
    }

    private ListenableFuture<ArraySet<String>> retrieveOptionalAlwaysOnRestrictions() {
        if (mOptionalAlwaysOnRestrictions != null) {
            return Futures.immediateFuture(
                    mOptionalAlwaysOnRestrictions);
        }
        final SetupParametersClient parameters = SetupParametersClient.getInstance();
        final ListenableFuture<String> kioskPackageTask = parameters.getKioskPackage();
        final ListenableFuture<Boolean> installingFromUnknownSourcesDisallowedTask =
                parameters.isInstallingFromUnknownSourcesDisallowed();

        return Futures.whenAllSucceed(kioskPackageTask,
                        installingFromUnknownSourcesDisallowedTask)
                .call(() -> {
                    if (Futures.getDone(kioskPackageTask) == null) {
                        throw new IllegalStateException("Setup parameters does not exist!");
                    }
                    if (mOptionalAlwaysOnRestrictions == null) {
                        mOptionalAlwaysOnRestrictions = new ArraySet<>(1);
                        if (Futures.getDone(installingFromUnknownSourcesDisallowedTask)) {
                            mOptionalAlwaysOnRestrictions.add(
                                    UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES);
                        }
                    }
                    return mOptionalAlwaysOnRestrictions;
                }, new Handler(Looper.getMainLooper())::post);
    }

    @ResultType
    private int setupRestrictions(ArraySet<String> restrictions, boolean enable) {
        Bundle userRestrictionBundle = mUserManager.getUserRestrictions();

        for (int i = 0, size = restrictions.size(); i < size; i++) {
            String restriction = restrictions.valueAt(i);
            if (userRestrictionBundle.getBoolean(restriction, false) != enable) {
                if (enable) {
                    mDpm.addUserRestriction(null /* admin */, restriction);
                    LogUtil.v(TAG, String.format(Locale.US, "enable %s restriction", restriction));
                } else {
                    mDpm.clearUserRestriction(null /* admin */, restriction);
                    LogUtil.v(TAG, String.format(Locale.US, "clear %s restriction", restriction));
                }
            }
        }
        // clear the adb access restriction if we added it before
        if (!mIsDebug
                && enable
                && restrictions.contains(UserManager.DISALLOW_DEBUGGING_FEATURES)) {
            mDpm.clearUserRestriction(null /* admin */, UserManager.DISALLOW_DEBUGGING_FEATURES);
            LogUtil.v(TAG, String.format(Locale.US, "clear %s restriction",
                    UserManager.DISALLOW_DEBUGGING_FEATURES));
        }
        return SUCCESS;
    }

    @ResultType
    private ListenableFuture<@ResultType Integer> setupRestrictions(
            ListenableFuture<ArraySet<String>> restrictionsFuture, boolean enable) {
        return Futures.transform(restrictionsFuture,
                restrictions -> setupRestrictions(restrictions, enable),
                new Handler(Looper.getMainLooper())::post);
    }

    private boolean checkRestrictions(ArraySet<String> restrictions, boolean value) {
        Bundle userRestrictionBundle = mUserManager.getUserRestrictions();

        for (int i = 0, size = restrictions.size(); i < size; i++) {
            String restriction = restrictions.valueAt(i);
            if (value != userRestrictionBundle.getBoolean(restriction, false)) {
                LogUtil.i(TAG, String.format(Locale.US, "%s restriction is not %b",
                        restriction, value));
                return false;
            }
        }

        return true;
    }

    private ListenableFuture<Boolean> checkRestrictions(
            ListenableFuture<ArraySet<String>> restrictionsFuture, boolean value) {
        return Futures.transform(restrictionsFuture,
                restrictions -> checkRestrictions(restrictions, value),
                new Handler(Looper.getMainLooper())::post);
    }
}
