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

package com.android.devicelockcontroller.storage;

import static com.android.devicelockcontroller.storage.IGlobalParametersService.Stub.asInterface;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;

import androidx.annotation.GuardedBy;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.devicelockcontroller.DeviceLockControllerApplication;
import com.android.devicelockcontroller.common.DeviceLockConstants.DeviceProvisionState;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import java.util.List;
import java.util.concurrent.Executors;

/**
 * A class used to access Global Parameters from any user.
 */
public final class GlobalParametersClient extends DlcClient {

    private static final Object sInstanceLock = new Object();

    @SuppressLint("StaticFieldLeak") // Only holds application context.
    @GuardedBy("sInstanceLock")
    private static GlobalParametersClient sClient;

    private GlobalParametersClient(@NonNull Context context,
            ListeningExecutorService executorService) {
        super(context, new ComponentName(context, GlobalParametersService.class), executorService);
    }

    /**
     * Get the GlobalParametersClient singleton instance.
     */
    public static GlobalParametersClient getInstance() {
        return getInstance(DeviceLockControllerApplication.getAppContext(),
                /* executorService= */ null);
    }

    /**
     * Get the GlobalParametersClient singleton instance.
     */
    @VisibleForTesting
    public static GlobalParametersClient getInstance(Context appContext,
            @Nullable ListeningExecutorService executorService) {
        synchronized (sInstanceLock) {
            if (sClient == null) {
                sClient = new GlobalParametersClient(
                        appContext,
                        executorService == null
                                ? MoreExecutors.listeningDecorator(Executors.newCachedThreadPool())
                                : executorService);
            }
            return sClient;
        }
    }

    /**
     * Reset the Client singleton instance
     */
    @VisibleForTesting
    public static void reset() {
        synchronized (sInstanceLock) {
            if (sClient != null) {
                sClient.tearDown();
                sClient = null;
            }
        }
    }

    /**
     * Clear any existing global parameters.
     * Note that this API can only be called in debuggable build for debugging purpose.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<Void> clear() {
        return call(() -> {
            asInterface(getService()).clear();
            return null;
        });
    }

    /**
     * Gets the list of packages allowlisted in lock task mode.
     *
     * @return List of packages that are allowed in lock task mode.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<List<String>> getLockTaskAllowlist() {
        return call(() -> asInterface(getService()).getLockTaskAllowlist());
    }

    /**
     * Sets the list of packages allowlisted in lock task mode.
     *
     * @param allowlist List of packages that are allowed in lock task mode.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<Void> setLockTaskAllowlist(List<String> allowlist) {
        return call(() -> {
            asInterface(getService()).setLockTaskAllowlist(allowlist);
            return null;
        });
    }

    /**
     * Checks if a check-in request needs to be performed.
     *
     * @return true if check-in request needs to be performed.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<Boolean> needCheckIn() {
        return call(() -> asInterface(getService()).needCheckIn());
    }

    /**
     * Sets the value of whether this device needs to perform check-in request.
     *
     * @param needCheckIn new state of whether the device needs to perform check-in request.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<Void> setNeedCheckIn(boolean needCheckIn) {
        return call(() -> {
            asInterface(getService()).setNeedCheckIn(needCheckIn);
            return null;
        });
    }

    /**
     * Gets the unique identifier that is regisered to DeviceLock backend server.
     *
     * @return The registered device unique identifier; null if device has never checked in with
     * backed server.
     */
    @Nullable
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<String> getRegisteredDeviceId() {
        return call(() -> asInterface(getService()).getRegisteredDeviceId());
    }

    /**
     * Set the unique identifier that is registered to DeviceLock backend server.
     *
     * @param registeredDeviceId The registered device unique identifier.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<Void> setRegisteredDeviceId(String registeredDeviceId) {
        return call(() -> {
            asInterface(getService()).setRegisteredDeviceId(registeredDeviceId);
            return null;
        });
    }

    /**
     * Check if provision should be forced.
     *
     * @return True if the provision should be forced without any delays.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<Boolean> isProvisionForced() {
        return call(() -> asInterface(getService()).isProvisionForced());
    }

    /**
     * Set provision is forced
     *
     * @param isForced The new value of the forced provision flag.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<Void> setProvisionForced(boolean isForced) {
        return call(() -> {
            asInterface(getService()).setProvisionForced(isForced);
            return null;
        });
    }

    /**
     * Get the enrollment token assigned by the Device Lock backend server.
     *
     * @return A string value of the enrollment token.
     */
    @Nullable
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<String> getEnrollmentToken() {
        return call(() -> asInterface(getService()).getEnrollmentToken());
    }

    /**
     * Set the enrollment token assigned by the Device Lock backend server.
     *
     * @param token The string value of the enrollment token.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<Void> setEnrollmentToken(String token) {
        return call(() -> {
            asInterface(getService()).setEnrollmentToken(token);
            return null;
        });
    }

    /**
     * Get the kiosk app signature.
     *
     * @return the kiosk app signature.
     */
    @Nullable
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<String> getKioskSignature() {
        return call(() -> asInterface(getService()).getKioskSignature());
    }

    /**
     * Sets the kiosk app signature.
     *
     * @param signature Kiosk app signature.
     */
    @SuppressWarnings("GuardedBy") // mLock already held in "call" (error prone).
    public ListenableFuture<Void> setKioskSignature(String signature) {
        return call(() -> {
            asInterface(getService()).setKioskSignature(signature);
            return null;
        });
    }

    /**
     * Get the last received provision state determined by device lock server.
     *
     * @return one of {@link DeviceProvisionState}.
     */
    public ListenableFuture<Integer> getLastReceivedProvisionState() {
        return call(() -> asInterface(getService()).getLastReceivedProvisionState());
    }

    /**
     * Set the last received provision state determined by device lock server.
     *
     * @param provisionState The provision state determined by device lock server
     */
    public ListenableFuture<Void> setLastReceivedProvisionState(
            @DeviceProvisionState int provisionState) {
        return call(() -> {
            asInterface(getService()).setLastReceivedProvisionState(provisionState);
            return null;
        });
    }
}
