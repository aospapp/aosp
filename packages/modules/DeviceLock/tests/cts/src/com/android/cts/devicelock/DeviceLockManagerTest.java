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

package com.android.cts.devicelock;

import static android.devicelock.DeviceId.DEVICE_ID_TYPE_IMEI;
import static android.devicelock.DeviceId.DEVICE_ID_TYPE_MEID;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeTrue;

import android.app.admin.DevicePolicyManager;
import android.content.Context;
import android.devicelock.DeviceId;
import android.devicelock.DeviceLockManager;
import android.os.Build;
import android.os.OutcomeReceiver;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.util.ArrayMap;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;
import com.android.server.devicelock.DeviceLockControllerPackageUtils;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Test system DeviceLockManager APIs.
 */
@RunWith(JUnit4.class)
@SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE, codeName = "UpsideDownCake")
public final class DeviceLockManagerTest {
    private final ExecutorService mExecutorService = Executors.newCachedThreadPool();

    private final Context mContext = InstrumentationRegistry.getInstrumentation().getContext();

    private final DeviceLockManager mDeviceLockManager =
            mContext.getSystemService(DeviceLockManager.class);

    private final TelephonyManager mTelephonyManager =
            mContext.getSystemService(TelephonyManager.class);

    private final DevicePolicyManager mDevicePolicyManager =
            mContext.getSystemService(DevicePolicyManager.class);

    private final DeviceLockControllerPackageUtils mPackageUtils =
            new DeviceLockControllerPackageUtils(mContext);

    private static final int TIMEOUT = 1;

    private void addFinancedDeviceKioskRole() {
        final String cmd =
                String.format("cmd role add-role-holder --user %d "
                                + "android.app.role.FINANCED_DEVICE_KIOSK %s 1",
                        UserHandle.myUserId(),
                        mContext.getPackageName());
        SystemUtil.runShellCommandOrThrow(cmd);
    }

    private void removeFinancedDeviceKioskRole() {
        final String cmd =
                String.format("cmd role remove-role-holder --user %d "
                                + "android.app.role.FINANCED_DEVICE_KIOSK %s 1",
                        UserHandle.myUserId(),
                        mContext.getPackageName());
        SystemUtil.runShellCommandOrThrow(cmd);
    }

    public ListenableFuture<Boolean> getIsDeviceLockedFuture() {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mDeviceLockManager.isDeviceLocked(mExecutorService,
                            new OutcomeReceiver<Boolean, Exception>() {
                                @Override
                                public void onResult(Boolean locked) {
                                    completer.set(locked);
                                }

                                @Override
                                public void onError(Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // Used only for debugging.
                    return "isDeviceLocked operation";
                });
    }

    public ListenableFuture<Void> getLockDeviceFuture() {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mDeviceLockManager.lockDevice(mExecutorService,
                            new OutcomeReceiver<Void, Exception>() {
                                @Override
                                public void onResult(Void result) {
                                    completer.set(null);
                                }

                                @Override
                                public void onError(Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // Used only for debugging.
                    return "lockDevice operation";
                });
    }

    public ListenableFuture<Void> getUnlockDeviceFuture() {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mDeviceLockManager.unlockDevice(mExecutorService,
                            new OutcomeReceiver<Void, Exception>() {
                                @Override
                                public void onResult(Void result) {
                                    completer.set(null);
                                }

                                @Override
                                public void onError(Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // Used only for debugging.
                    return "unlockDevice operation";
                });
    }

    public ListenableFuture<DeviceId> getDeviceIdFuture() {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mDeviceLockManager.getDeviceId(mExecutorService,
                            new OutcomeReceiver<DeviceId, Exception>() {
                                @Override
                                public void onResult(DeviceId deviceId) {
                                    completer.set(deviceId);
                                }

                                @Override
                                public void onError(Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // Used only for debugging.
                    return "getDeviceId operation";
                });
    }

    public ListenableFuture<Map<Integer, String>> getKioskAppsFuture() {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mDeviceLockManager.getKioskApps(mExecutorService,
                            new OutcomeReceiver<Map<Integer, String>, Exception>() {
                                @Override
                                public void onResult(Map<Integer, String> result) {
                                    completer.set(result);
                                }

                                @Override
                                public void onError(Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // Used only for debugging.
                    return "getKioskApps operation";
                });
    }

    @Test
    public void lockDevicePermissionCheck() {
        ListenableFuture<Void> lockDeviceFuture = getLockDeviceFuture();

        Exception lockDeviceResponseException =
                assertThrows(
                        ExecutionException.class,
                        () -> {
                            lockDeviceFuture.get(TIMEOUT, TimeUnit.SECONDS);
                        });
        assertThat(lockDeviceResponseException.getCause())
                .isInstanceOf(SecurityException.class);
    }

    @Test
    public void unlockDevicePermissionCheck() {
        ListenableFuture<Void> unlockDeviceFuture = getUnlockDeviceFuture();

        Exception lockDeviceResponseException =
                assertThrows(
                        ExecutionException.class,
                        () -> {
                            unlockDeviceFuture.get(TIMEOUT, TimeUnit.SECONDS);
                        });
        assertThat(lockDeviceResponseException.getCause())
                .isInstanceOf(SecurityException.class);
    }

    @Test
    public void isDeviceLockedPermissionCheck() {
        ListenableFuture<Boolean> isDeviceLockedFuture = getIsDeviceLockedFuture();

        Exception isDeviceLockedResponseException =
                assertThrows(
                        ExecutionException.class,
                        () -> {
                            isDeviceLockedFuture.get(TIMEOUT, TimeUnit.SECONDS);
                        });
        assertThat(isDeviceLockedResponseException.getCause())
                .isInstanceOf(SecurityException.class);
    }

    @Test
    public void getDeviceIdPermissionCheck() {
        ListenableFuture<DeviceId> deviceIdFuture = getDeviceIdFuture();

        Exception isDeviceLockedResponseException =
                assertThrows(
                        ExecutionException.class,
                        () -> {
                            deviceIdFuture.get(TIMEOUT, TimeUnit.SECONDS);
                        });
        assertThat(isDeviceLockedResponseException.getCause())
                .isInstanceOf(SecurityException.class);
    }

    @Test
    public void deviceShouldLockAndUnlock() throws InterruptedException, ExecutionException,
            TimeoutException {

        try {
            addFinancedDeviceKioskRole();

            boolean locked = getIsDeviceLockedFuture().get(TIMEOUT, TimeUnit.SECONDS);
            assertThat(locked).isFalse();

            getLockDeviceFuture().get(TIMEOUT, TimeUnit.SECONDS);

            locked = getIsDeviceLockedFuture().get(TIMEOUT, TimeUnit.SECONDS);
            assertThat(locked).isTrue();

            getUnlockDeviceFuture().get(TIMEOUT, TimeUnit.SECONDS);

            locked = getIsDeviceLockedFuture().get(TIMEOUT, TimeUnit.SECONDS);
            assertThat(locked).isFalse();
        } finally {
            removeFinancedDeviceKioskRole();
        }
    }

    private void skipIfNoIdAvailable() {
        final StringBuilder errorMessage = new StringBuilder();
        final int deviceIdTypeBitmap =
                mPackageUtils.getDeviceIdTypeBitmap(errorMessage);
        assertWithMessage(errorMessage.toString()).that(deviceIdTypeBitmap).isGreaterThan(-1);

        String imei;
        String meid;

        try {
            adoptShellPermissions();

            imei = mTelephonyManager.getImei();
            meid = mTelephonyManager.getMeid();
        } finally {
            dropShellPermissions();
        }

        final boolean imeiAvailable = (imei != null)
                && ((deviceIdTypeBitmap & (1 << DEVICE_ID_TYPE_IMEI)) != 0);
        final boolean meidAvailable = (meid != null)
                && ((deviceIdTypeBitmap & (1 << DEVICE_ID_TYPE_MEID)) != 0);
        final boolean idAvailable = imeiAvailable || meidAvailable;

        assumeTrue("No id available", idAvailable);
    }

    @Test
    public void getDeviceIdShouldReturnAnId()
            throws ExecutionException, InterruptedException, TimeoutException {
        try {
            addFinancedDeviceKioskRole();

            skipIfNoIdAvailable();

            // The device ID is supposed to be obtained from the DeviceLock backend service, passed
            // to the DeviceLockController app, then passed to the DeviceLock system service. Since
            // there is no way to communicate with the backend service within the scope of this test
            // we expect the result to be an exception.
            //
            // TODO(b/281538947): find solution for properly testing the intended behavior.
            assertThrows(ExecutionException.class,
                    () -> getDeviceIdFuture().get(TIMEOUT, TimeUnit.SECONDS));
        } finally {
            removeFinancedDeviceKioskRole();
        }
    }

    @Test
    public void getKioskApp_financedRoleHolderExists_returnsMapping()
            throws ExecutionException, InterruptedException, TimeoutException {
        final ArrayMap expectedKioskApps = new ArrayMap<Integer, String>();
        expectedKioskApps.put(
                DeviceLockManager.DEVICE_LOCK_ROLE_FINANCING, mContext.getPackageName());

        try {
            addFinancedDeviceKioskRole();

            Map<Integer, String> kioskAppsMap = getKioskAppsFuture().get(TIMEOUT, TimeUnit.SECONDS);

            assertThat(kioskAppsMap).isEqualTo(expectedKioskApps);
        } finally {
            removeFinancedDeviceKioskRole();
        }
    }

    @Test
    public void getKioskApp_financedRoleHolderDoesNotExist_returnsEmptyMapping()
            throws ExecutionException, InterruptedException, TimeoutException {
        Map<Integer, String> kioskAppsMap = getKioskAppsFuture().get(TIMEOUT, TimeUnit.SECONDS);
        assertThat(kioskAppsMap).isEmpty();
    }

    private static void adoptShellPermissions() {
        InstrumentationRegistry
                .getInstrumentation().getUiAutomation().adoptShellPermissionIdentity();
    }

    private static void dropShellPermissions() {
        InstrumentationRegistry
                .getInstrumentation().getUiAutomation().dropShellPermissionIdentity();
    }
}
