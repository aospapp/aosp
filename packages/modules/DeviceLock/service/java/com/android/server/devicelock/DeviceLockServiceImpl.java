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

import static android.app.AppOpsManager.OPSTR_SYSTEM_EXEMPT_FROM_HIBERNATION;
import static android.app.role.RoleManager.MANAGE_HOLDERS_FLAG_DONT_KILL_APP;
import static android.content.IntentFilter.SYSTEM_HIGH_PRIORITY;
import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT;
import static android.content.pm.PackageManager.DONT_KILL_APP;
import static android.content.pm.PackageManager.PERMISSION_GRANTED;
import static android.devicelock.DeviceId.DEVICE_ID_TYPE_IMEI;
import static android.devicelock.DeviceId.DEVICE_ID_TYPE_MEID;

import android.Manifest;
import android.annotation.NonNull;
import android.app.AppOpsManager;
import android.app.role.RoleManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.content.pm.PackageManager.NameNotFoundException;
import android.content.pm.PackageManager.PackageInfoFlags;
import android.content.pm.ServiceInfo;
import android.devicelock.DeviceId.DeviceIdType;
import android.devicelock.DeviceLockManager;
import android.devicelock.IDeviceLockService;
import android.devicelock.IGetDeviceIdCallback;
import android.devicelock.IGetKioskAppsCallback;
import android.devicelock.IIsDeviceLockedCallback;
import android.devicelock.ILockUnlockDeviceCallback;
import android.os.Binder;
import android.os.Bundle;
import android.os.OutcomeReceiver;
import android.os.RemoteCallback;
import android.os.RemoteException;
import android.os.UserHandle;
import android.telephony.TelephonyManager;
import android.text.TextUtils;
import android.util.ArrayMap;
import android.util.Slog;

import com.android.internal.annotations.VisibleForTesting;

import java.util.ArrayList;
import java.util.List;

/**
 * Implementation of {@link android.devicelock.IDeviceLockService} binder service.
 */
final class DeviceLockServiceImpl extends IDeviceLockService.Stub {
    private static final String TAG = "DeviceLockServiceImpl";

    private final Context mContext;

    private final DeviceLockControllerConnector mDeviceLockControllerConnector;

    private final DeviceLockControllerPackageUtils mPackageUtils;

    private final ServiceInfo mServiceInfo;

    // The following should be a SystemApi on AppOpsManager.
    private static final String OPSTR_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION =
            "android:system_exempt_from_activity_bg_start_restriction";

    // Stopgap: this receiver should be replaced by an API on DeviceLockManager.
    private final class DeviceLockClearReceiver extends BroadcastReceiver {
        static final String ACTION_CLEAR = "com.android.devicelock.intent.action.CLEAR";
        static final int CLEAR_SUCCEEDED = 0;
        static final int CLEAR_FAILED = 1;

        @Override
        public void onReceive(Context context, Intent intent) {
            Slog.i(TAG, "Received request to clear device");

            // This receiver should be the only one.
            // The result will still be sent to the 'resultReceiver' of 'sendOrderedBroadcast'.
            abortBroadcast();

            final PendingResult pendingResult = goAsync();

            mDeviceLockControllerConnector.clearDeviceRestrictions(new OutcomeReceiver<>() {

                private void setResult(int resultCode) {
                    pendingResult.setResultCode(resultCode);

                    pendingResult.finish();
                }

                @Override
                public void onResult(Void ignored) {
                    Slog.i(TAG, "Device cleared ");

                    setResult(DeviceLockClearReceiver.CLEAR_SUCCEEDED);
                }

                @Override
                public void onError(Exception ex) {
                    Slog.e(TAG, "Exception clearing device: ", ex);

                    setResult(DeviceLockClearReceiver.CLEAR_FAILED);
                }
            });
        }
    }

    // Last supported device id type
    private static final @DeviceIdType int LAST_DEVICE_ID_TYPE = DEVICE_ID_TYPE_MEID;

    private static final String MANAGE_DEVICE_LOCK_SERVICE_FROM_CONTROLLER =
            "com.android.devicelockcontroller.permission."
                    + "MANAGE_DEVICE_LOCK_SERVICE_FROM_CONTROLLER";

    DeviceLockServiceImpl(@NonNull Context context) {
        mContext = context;

        mPackageUtils = new DeviceLockControllerPackageUtils(context);

        final StringBuilder errorMessage = new StringBuilder();
        mServiceInfo = mPackageUtils.findService(errorMessage);

        if (mServiceInfo == null) {
            mDeviceLockControllerConnector = null;

            throw new RuntimeException(errorMessage.toString());
        }

        if (!mServiceInfo.applicationInfo.enabled) {
            Slog.w(TAG, "Device Lock Controller is disabled");
            setDeviceLockControllerPackageDefaultEnabledState(UserHandle.SYSTEM);
        }

        final ComponentName componentName = new ComponentName(mServiceInfo.packageName,
                mServiceInfo.name);

        mDeviceLockControllerConnector = new DeviceLockControllerConnector(context, componentName);

        final IntentFilter intentFilter = new IntentFilter(DeviceLockClearReceiver.ACTION_CLEAR);
        // Run before any eventual app receiver (there should be none).
        intentFilter.setPriority(SYSTEM_HIGH_PRIORITY);
        context.registerReceiver(new DeviceLockClearReceiver(),
                intentFilter,
                Manifest.permission.MANAGE_DEVICE_LOCK_STATE, null /* scheduler */,
                Context.RECEIVER_EXPORTED);
    }

    void setDeviceLockControllerPackageDefaultEnabledState(@NonNull UserHandle userHandle) {
        final String controllerPackageName = mServiceInfo.packageName;

        Context controllerContext;
        try {
            controllerContext = mContext.createPackageContextAsUser(controllerPackageName,
                    0 /* flags */, userHandle);
        } catch (NameNotFoundException e) {
            Slog.e(TAG, "Cannot create package context for: " + userHandle, e);

            return;
        }

        final PackageManager controllerPackageManager = controllerContext.getPackageManager();

        controllerPackageManager.setApplicationEnabledSetting(controllerPackageName,
                COMPONENT_ENABLED_STATE_DEFAULT, DONT_KILL_APP);
    }

    private boolean checkCallerPermission() {
        return mContext.checkCallingOrSelfPermission(Manifest.permission.MANAGE_DEVICE_LOCK_STATE)
                == PERMISSION_GRANTED;
    }

    private void reportDeviceLockedUnlocked(@NonNull ILockUnlockDeviceCallback callback,
            boolean success) {
        try {
            if (success) {
                callback.onDeviceLockedUnlocked();
            } else {
                callback.onError(ILockUnlockDeviceCallback.ERROR_UNKNOWN);
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "Unable to send result to the callback", e);
        }
    }

    private OutcomeReceiver<Void, Exception>
            getLockUnlockOutcomeReceiver(@NonNull ILockUnlockDeviceCallback callback,
                @NonNull String successMessage) {
        return new OutcomeReceiver<>() {
            @Override
            public void onResult(Void ignored) {
                Slog.i(TAG, successMessage);
                reportDeviceLockedUnlocked(callback, true /* success */);
            }

            @Override
            public void onError(Exception ex) {
                Slog.e(TAG, "Exception: ", ex);
                reportDeviceLockedUnlocked(callback, false /* success */);
            }
        };
    }

    @Override
    public void lockDevice(@NonNull ILockUnlockDeviceCallback callback) {
        if (!checkCallerPermission()) {
            try {
                callback.onError(ILockUnlockDeviceCallback.ERROR_SECURITY);
            } catch (RemoteException e) {
                Slog.e(TAG, "lockDevice() - Unable to send error to the callback", e);
            }
            return;
        }

        mDeviceLockControllerConnector.lockDevice(
                getLockUnlockOutcomeReceiver(callback, "Device locked"));
    }

    @Override
    public void unlockDevice(@NonNull ILockUnlockDeviceCallback callback) {
        if (!checkCallerPermission()) {
            try {
                callback.onError(ILockUnlockDeviceCallback.ERROR_SECURITY);
            } catch (RemoteException e) {
                Slog.e(TAG, "unlockDevice() - Unable to send error to the callback", e);
            }
            return;
        }

        mDeviceLockControllerConnector.unlockDevice(
                getLockUnlockOutcomeReceiver(callback, "Device unlocked"));
    }

    @Override
    public void isDeviceLocked(@NonNull IIsDeviceLockedCallback callback) {
        if (!checkCallerPermission()) {
            try {
                callback.onError(IIsDeviceLockedCallback.ERROR_SECURITY);
            } catch (RemoteException e) {
                Slog.e(TAG, "isDeviceLocked() - Unable to send error to the callback", e);
            }
            return;
        }

        mDeviceLockControllerConnector.isDeviceLocked(
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Boolean isLocked) {
                        Slog.i(TAG, isLocked ? "Device is locked" : "Device is not locked");
                        try {
                            callback.onIsDeviceLocked(isLocked);
                        } catch (RemoteException e) {
                            Slog.e(TAG, "isDeviceLocked() - Unable to send result to the "
                                    + "callback", e);
                        }
                    }

                    @Override
                    public void onError(Exception ex) {
                            Slog.e(TAG, "Exception: ", ex);
                            try {
                                callback.onError(ILockUnlockDeviceCallback.ERROR_UNKNOWN);
                            } catch (RemoteException e) {
                                Slog.e(TAG, "isDeviceLocked() - Unable to send error to the "
                                        + "callback", e);
                            }
                        }
                });
    }

    @VisibleForTesting
    void getDeviceId(@NonNull IGetDeviceIdCallback callback, int deviceIdTypeBitmap) {
        try {
            if (deviceIdTypeBitmap < 0 || deviceIdTypeBitmap >= (1 << (LAST_DEVICE_ID_TYPE + 1))) {
                callback.onError(IGetDeviceIdCallback.ERROR_INVALID_DEVICE_ID_TYPE_BITMAP);
                return;
            }
        } catch (RemoteException e) {
            Slog.e(TAG, "getDeviceId() - Unable to send result to the callback", e);
        }

        final TelephonyManager telephonyManager =
                mContext.getSystemService(TelephonyManager.class);
        int activeModemCount = telephonyManager.getActiveModemCount();
        List<String> imeiList = new ArrayList<String>();
        List<String> meidList = new ArrayList<String>();

        if ((deviceIdTypeBitmap & (1 << DEVICE_ID_TYPE_IMEI)) != 0) {
            for (int i = 0; i < activeModemCount; i++) {
                String imei = telephonyManager.getImei(i);
                if (!TextUtils.isEmpty(imei)) {
                    imeiList.add(imei);
                }
            }
        }

        if ((deviceIdTypeBitmap & (1 << DEVICE_ID_TYPE_MEID)) != 0) {
            for (int i = 0; i < activeModemCount; i++) {
                String meid = telephonyManager.getMeid(i);
                if (!TextUtils.isEmpty(meid)) {
                    meidList.add(meid);
                }
            }
        }

        mDeviceLockControllerConnector.getDeviceId(new OutcomeReceiver<>() {
                @Override
                public void onResult(String deviceId) {
                    Slog.i(TAG, "Get Device ID ");
                    try {
                        if (meidList.contains(deviceId)) {
                            callback.onDeviceIdReceived(DEVICE_ID_TYPE_MEID, deviceId);
                            return;
                        }
                        if (imeiList.contains(deviceId)) {
                            callback.onDeviceIdReceived(DEVICE_ID_TYPE_IMEI, deviceId);
                            return;
                        }
                        // When a device ID is returned from DLC App, but none of the IDs
                        // got from TelephonyManager matches that device ID.
                        //
                        // TODO(b/270392813): Send the device ID back to the callback
                        // with UNSPECIFIED device ID type.
                        callback.onError(IGetDeviceIdCallback.ERROR_CANNOT_GET_DEVICE_ID);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "getDeviceId() - Unable to send result to the "
                                + "callback", e);
                    }
                }

                @Override
                public void onError(Exception ex) {
                    Slog.e(TAG, "Exception: ", ex);
                    try {
                        callback.onError(IGetDeviceIdCallback.ERROR_CANNOT_GET_DEVICE_ID);
                    } catch (RemoteException e) {
                        Slog.e(TAG, "getDeviceId() - Unable to send error to the "
                                + "callback", e);
                    }
                }
            }
        );
    }

    @Override
    public void getDeviceId(@NonNull IGetDeviceIdCallback callback) {
        if (!checkCallerPermission()) {
            try {
                callback.onError(IGetDeviceIdCallback.ERROR_SECURITY);
            } catch (RemoteException e) {
                Slog.e(TAG, "getDeviceId() - Unable to send error to the callback", e);
            }
            return;
        }

        final StringBuilder errorBuilder = new StringBuilder();

        final long identity = Binder.clearCallingIdentity();
        final int deviceIdTypeBitmap = mPackageUtils.getDeviceIdTypeBitmap(errorBuilder);
        Binder.restoreCallingIdentity(identity);

        if (deviceIdTypeBitmap < 0) {
            Slog.e(TAG, "getDeviceId: " + errorBuilder);
        }

        getDeviceId(callback, deviceIdTypeBitmap);
    }

    @Override
    public void getKioskApps(@NonNull IGetKioskAppsCallback callback) {
        // Caller is not necessarily a kiosk app, and no particular permission enforcing is needed.

        final ArrayMap kioskApps = new ArrayMap<Integer, String>();

        final UserHandle userHandle = Binder.getCallingUserHandle();
        final RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        final long identity = Binder.clearCallingIdentity();
        try {
            List<String> roleHolders = roleManager.getRoleHoldersAsUser(
                    RoleManager.ROLE_FINANCED_DEVICE_KIOSK, userHandle);

            if (!roleHolders.isEmpty()) {
                kioskApps.put(DeviceLockManager.DEVICE_LOCK_ROLE_FINANCING, roleHolders.get(0));
            }

            callback.onKioskAppsReceived(kioskApps);
        } catch (RemoteException e) {
            Slog.e(TAG, "getKioskApps() - Unable to send result to the callback", e);
        } finally {
            Binder.restoreCallingIdentity(identity);
        }
    }

    // For calls from Controller to System Service.

    private void reportErrorToCaller(@NonNull RemoteCallback remoteCallback) {
        final Bundle result = new Bundle();
        result.putBoolean(KEY_REMOTE_CALLBACK_RESULT, false);
        remoteCallback.sendResult(result);
    }

    private boolean checkDeviceLockControllerPermission(@NonNull RemoteCallback remoteCallback) {
        if (mContext.checkCallingOrSelfPermission(MANAGE_DEVICE_LOCK_SERVICE_FROM_CONTROLLER)
                != PERMISSION_GRANTED) {
            reportErrorToCaller(remoteCallback);
            return false;
        }

        return true;
    }

    private void reportResult(boolean accepted, long identity,
            @NonNull RemoteCallback remoteCallback) {
        Binder.restoreCallingIdentity(identity);

        final Bundle result = new Bundle();
        result.putBoolean(KEY_REMOTE_CALLBACK_RESULT, accepted);
        remoteCallback.sendResult(result);
    }

    @Override
    public void addFinancedDeviceKioskRole(@NonNull String packageName,
            @NonNull RemoteCallback remoteCallback) {
        if (!checkDeviceLockControllerPermission(remoteCallback)) {
            return;
        }

        final UserHandle userHandle = Binder.getCallingUserHandle();
        final RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        final long identity = Binder.clearCallingIdentity();

        roleManager.addRoleHolderAsUser(RoleManager.ROLE_FINANCED_DEVICE_KIOSK, packageName,
                MANAGE_HOLDERS_FLAG_DONT_KILL_APP, userHandle, mContext.getMainExecutor(),
                accepted -> reportResult(accepted, identity, remoteCallback));

        Binder.restoreCallingIdentity(identity);
    }

    @Override
    public void removeFinancedDeviceKioskRole(@NonNull String packageName,
            @NonNull RemoteCallback remoteCallback) {
        if (!checkDeviceLockControllerPermission(remoteCallback)) {
            return;
        }

        final UserHandle userHandle = Binder.getCallingUserHandle();
        final RoleManager roleManager = mContext.getSystemService(RoleManager.class);
        final long identity = Binder.clearCallingIdentity();

        roleManager.removeRoleHolderAsUser(RoleManager.ROLE_FINANCED_DEVICE_KIOSK, packageName,
                MANAGE_HOLDERS_FLAG_DONT_KILL_APP, userHandle, mContext.getMainExecutor(),
                accepted -> reportResult(accepted, identity, remoteCallback));

        Binder.restoreCallingIdentity(identity);
    }

    private void setExemption(String packageName, int uid, String appOp, boolean exempt,
            @NonNull RemoteCallback remoteCallback) {
        final AppOpsManager appOpsManager = mContext.getSystemService(AppOpsManager.class);
        final long identity = Binder.clearCallingIdentity();

        final int mode = exempt ? AppOpsManager.MODE_ALLOWED : AppOpsManager.MODE_DEFAULT;

        appOpsManager.setMode(appOp, uid, packageName, mode);

        Binder.restoreCallingIdentity(identity);

        final Bundle result = new Bundle();
        result.putBoolean(KEY_REMOTE_CALLBACK_RESULT, true);
        remoteCallback.sendResult(result);
    }

    @Override
    public void setExemptFromActivityBackgroundStartRestriction(boolean exempt,
            @NonNull RemoteCallback remoteCallback) {
        if (!checkDeviceLockControllerPermission(remoteCallback)) {
            return;
        }

        setExemption(mServiceInfo.packageName, Binder.getCallingUid(),
                OPSTR_SYSTEM_EXEMPT_FROM_ACTIVITY_BG_START_RESTRICTION, exempt, remoteCallback);
    }

    @Override
    public void setExemptFromHibernation(String packageName, boolean exempt,
            @NonNull RemoteCallback remoteCallback) {
        if (!checkDeviceLockControllerPermission(remoteCallback)) {
            return;
        }

        final UserHandle controllerUserHandle = Binder.getCallingUserHandle();
        final int controllerUserId = controllerUserHandle.getIdentifier();
        final PackageManager packageManager = mContext.getPackageManager();
        int kioskUid;
        final long identity = Binder.clearCallingIdentity();
        try {
            kioskUid = packageManager.getPackageUidAsUser(packageName, PackageInfoFlags.of(0),
                    controllerUserId);
        } catch (NameNotFoundException e) {
            Binder.restoreCallingIdentity(identity);
            Slog.e(TAG, "Failed to set hibernation appop", e);
            reportErrorToCaller(remoteCallback);
            return;
        }
        Binder.restoreCallingIdentity(identity);

        setExemption(packageName, kioskUid, OPSTR_SYSTEM_EXEMPT_FROM_HIBERNATION, exempt,
                remoteCallback);
    }
}
