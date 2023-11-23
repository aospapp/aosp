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
import android.content.Context;

import com.android.devicelockcontroller.policy.DeviceStateController.DeviceState;
import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.ArrayList;
import java.util.List;

/** Enforces restrictions on Kiosk app and controller. */
final class PackagePolicyHandler implements PolicyHandler {
    private static final String TAG = "PackagePolicyHandler";

    private final Context mContext;
    private final DevicePolicyManager mDpm;

    PackagePolicyHandler(Context context, DevicePolicyManager dpm) {
        mContext = context;
        mDpm = dpm;
    }

    @Override
    public ListenableFuture<@ResultType Integer> setPolicyForState(@DeviceState int state) {
        switch (state) {
            case KIOSK_SETUP:
            case UNLOCKED:
            case LOCKED:
                return enablePackageProtection(true /* enableForKiosk */, state);
            case CLEARED:
            case UNPROVISIONED:
                return enablePackageProtection(false /* enableForKiosk */, state);
            case SETUP_IN_PROGRESS:
            case SETUP_SUCCEEDED:
            case SETUP_FAILED:
            case PSEUDO_LOCKED:
            case PSEUDO_UNLOCKED:
                return Futures.immediateFuture(SUCCESS);
            default:
                return Futures.immediateFailedFuture(
                        new IllegalStateException(String.valueOf(state)));
        }
    }

    private ListenableFuture<@ResultType Integer> enablePackageProtection(boolean enableForKiosk,
            @DeviceState int state) {
        return Futures.transform(SetupParametersClient.getInstance().getKioskPackage(),
                kioskPackageName -> {
                    if (kioskPackageName == null) {
                        LogUtil.d(TAG, "Kiosk package is not set for state: " + state);
                    } else {
                        try {
                            mDpm.setUninstallBlocked(null /* admin */, kioskPackageName,
                                    enableForKiosk);
                        } catch (SecurityException e) {
                            LogUtil.e(TAG, "Unable to set device policy", e);
                            return FAILURE;
                        }
                    }

                    final List<String> pkgList = new ArrayList<>();

                    // The controller itself should always have user control disabled
                    pkgList.add(mContext.getPackageName());

                    if (kioskPackageName != null && enableForKiosk) {
                        pkgList.add(kioskPackageName);
                    }

                    try {
                        mDpm.setUserControlDisabledPackages(null /* admin */, pkgList);
                    } catch (SecurityException e) {
                        LogUtil.e(TAG, "Failed to setUserControlDisabledPackages", e);
                        return FAILURE;
                    }

                    return SUCCESS;
                }, MoreExecutors.directExecutor());
    }
}
