/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.devicelockcontroller.debug;

import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.CLEARED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.LOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.UNLOCKED;
import static com.android.devicelockcontroller.policy.DeviceStateController.DeviceState.UNPROVISIONED;

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.UserManager;
import android.text.TextUtils;

import androidx.annotation.StringDef;

import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.DeviceStateController.DeviceState;
import com.android.devicelockcontroller.policy.PolicyObjectsInterface;
import com.android.devicelockcontroller.storage.GlobalParametersClient;
import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.storage.UserParameters;
import com.android.devicelockcontroller.util.LogUtil;

import com.google.common.util.concurrent.FutureCallback;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.MoreExecutors;

import java.lang.annotation.Retention;

/**
 * A {@link BroadcastReceiver} that can handle reset, lock, unlock command.
 * <p>
 * Note:
 * Reboot device are {@link DeviceLockCommandReceiver#onReceive(Context, Intent)} has been called to
 * take effect.
 */
public final class DeviceLockCommandReceiver extends BroadcastReceiver {

    private static final String TAG = "DeviceLockCommandReceiver";
    private static final String EXTRA_COMMAND = "command";

    @Retention(SOURCE)
    @StringDef({
            Commands.RESET,
            Commands.LOCK,
            Commands.UNLOCK,
    })
    private @interface Commands {
        String RESET = "reset";
        String LOCK = "lock";
        String UNLOCK = "unlock";
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        if (!Build.isDebuggable()) {
            throw new SecurityException("This should never be run in production build!");
        }

        if (!TextUtils.equals(intent.getComponent().getClassName(), getClass().getName())) {
            throw new IllegalArgumentException("Intent does not match this class!");
        }

        final boolean isUserProfile =
                context.getSystemService(UserManager.class).isProfile();
        if (isUserProfile) {
            LogUtil.w(TAG, "Broadcast should not target user profiles");
            return;
        }

        @Commands
        String command = String.valueOf(intent.getStringExtra(EXTRA_COMMAND));
        switch (command) {
            case Commands.RESET:
                forceReset(context);
                break;
            case Commands.LOCK:
                Futures.addCallback(forceSetState(context, LOCKED),
                        getSetStateCallBack(LOCKED), MoreExecutors.directExecutor());
                break;
            case Commands.UNLOCK:
                Futures.addCallback(forceSetState(context, UNLOCKED),
                        getSetStateCallBack(UNLOCKED), MoreExecutors.directExecutor());
                break;
            default:
                throw new IllegalArgumentException("Unsupported command: " + command);
        }
    }

    private static ListenableFuture<Void> forceSetState(Context context, @DeviceState int state) {
        PolicyObjectsInterface policyObjectsInterface =
                (PolicyObjectsInterface) context.getApplicationContext();
        policyObjectsInterface.destroyObjects();
        UserParameters.setDeviceStateSync(context, state);
        return policyObjectsInterface.getStateController().enforcePoliciesForCurrentState();
    }

    private static void forceReset(Context context) {
        ListenableFuture<Void> resetFuture = Futures.transformAsync(
                // First clear restrictions
                forceSetState(context, CLEARED),
                // Then clear storage, this will reset state to the default state which is
                // UNPROVISIONED.
                (Void unused) -> clearStorage(context),
                MoreExecutors.directExecutor());
        Futures.addCallback(
                resetFuture,
                getSetStateCallBack(UNPROVISIONED),
                MoreExecutors.directExecutor());
    }

    private static FutureCallback<Void> getSetStateCallBack(@DeviceState int state) {

        return new FutureCallback<>() {

            @Override
            public void onSuccess(Void v) {
                LogUtil.i(TAG,
                        "Successfully set state to: " + DeviceStateController.stateToString(state));
            }

            @Override
            public void onFailure(Throwable t) {
                LogUtil.e(TAG,
                        "Unsuccessfully set state to: "
                                + DeviceStateController.stateToString(state), t);
            }
        };
    }

    private static ListenableFuture<Void> clearStorage(Context context) {
        UserParameters.clear(context);
        return Futures.whenAllSucceed(
                        SetupParametersClient.getInstance().clear(),
                        GlobalParametersClient.getInstance().clear())
                .call(() -> {
                    ((PolicyObjectsInterface) context.getApplicationContext()).destroyObjects();
                    return null;
                }, MoreExecutors.directExecutor());
    }
}
