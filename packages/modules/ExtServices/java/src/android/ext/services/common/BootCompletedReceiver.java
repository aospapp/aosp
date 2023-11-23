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

package android.ext.services.common;

import android.annotation.SuppressLint;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.DeviceConfig;
import android.util.Log;

import androidx.annotation.ChecksSdkIntAtLeast;
import androidx.annotation.NonNull;
import androidx.annotation.VisibleForTesting;

import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * Handles the BootCompleted initialization for AdExtServices APK on S-.
 * The BootCompleted receiver re-broadcasts a different intent that is handled by the
 * AdExtBootCompletedReceiver within the AdServices apk. The reason for doing this here instead of
 * within the AdServices APK is due to problematic platform modifications (b/286070595).
 */
public class BootCompletedReceiver extends BroadcastReceiver {
    private static final String TAG = "extservices";
    private static final String KEY_PRIVACY_EXCLUDE_LIST = "privacy_exclude_list";
    private static final String KEY_EXTSERVICES_BOOT_COMPLETE_RECEIVER =
            "extservices_bootcomplete_enabled";

    private static final String ADEXTBOOTCOMPLETEDRECEIVER_CLASS_NAME =
            "com.android.adservices.service.common.AdExtBootCompletedReceiver";
    private static final String REBROADCAST_INTENT_ACTION =
            "android.adservices.action.INIT_EXT_SERVICES";
    private static final String ADSERVICES_SETTINGS_MAINACTIVITY =
            "com.android.adservices.ui.settings.activities.AdServicesSettingsMainActivity";

    @SuppressLint("MissingPermission")
    @Override
    public void onReceive(Context context, Intent intent) {
        Log.i(TAG, "BootCompletedReceiver received BOOT_COMPLETED broadcast (f): "
                + Build.FINGERPRINT);

        // Check if the feature is enabled, otherwise exit without doing anything.
        if (!isReceiverEnabled()) {
            Log.d(TAG, "BootCompletedReceiver not enabled in config, exiting");
            return;
        }

        String adServicesPackageName = getAdExtServicesPackageName(context);
        if (adServicesPackageName == null) {
            Log.d(TAG, "AdServices package was not present, exiting BootCompletedReceiver");
            return;
        }

        // No need to run this on every boot if we're on T+ and the AdExtServices components have
        // already been disabled.
        if (shouldDisableReceiver(context, adServicesPackageName)) {
            context.getPackageManager().setComponentEnabledSetting(
                    new ComponentName(context.getPackageName(), this.getClass().getName()),
                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED,
                    0);
            Log.d(TAG, "Disabled BootCompletedReceiver as AdServices is already initialized.");
            return;
        }

        // Check if this device is among a list of excluded devices
        String excludeList = getExcludedFingerprints();
        Log.d(TAG, "Read BOOT_COMPLETED broadcast exclude list: " + excludeList);
        if (Arrays.stream(excludeList.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .anyMatch(Build.FINGERPRINT::startsWith)) {
            Log.d(TAG, "Device is present in the exclude list, exiting BootCompletedReceiver");
            return;
        }

        // Re-broadcast the intent
        Intent intentToSend = new Intent(REBROADCAST_INTENT_ACTION);
        intentToSend.setComponent(
                new ComponentName(adServicesPackageName, ADEXTBOOTCOMPLETEDRECEIVER_CLASS_NAME));
        intentToSend.setFlags(Intent.FLAG_RECEIVER_FOREGROUND);
        context.sendBroadcast(intentToSend);
        Log.i(TAG, "BootCompletedReceiver sending init broadcast: " + intentToSend);
    }

    @SuppressLint("MissingPermission")
    @VisibleForTesting
    public boolean isReceiverEnabled() {
        return DeviceConfig.getBoolean(
                DeviceConfig.NAMESPACE_ADSERVICES,
                /* flagName */ KEY_EXTSERVICES_BOOT_COMPLETE_RECEIVER,
                /* defaultValue */ false);
    }

    @SuppressLint("MissingPermission")
    @VisibleForTesting
    public String getExcludedFingerprints() {
        return DeviceConfig.getString(
                DeviceConfig.NAMESPACE_ADSERVICES,
                /* flagName */ KEY_PRIVACY_EXCLUDE_LIST,
                /* defaultValue */ "");
    }

    @ChecksSdkIntAtLeast(api = Build.VERSION_CODES.TIRAMISU)
    @VisibleForTesting
    public boolean isAtLeastT() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU;
    }

    private boolean shouldDisableReceiver(@NonNull Context context,
            @NonNull String adServicesPackageName) {
        Objects.requireNonNull(context);
        Objects.requireNonNull(adServicesPackageName);
        return isAtLeastT() && !isExtServicesInitialized(context, adServicesPackageName);
    }

    private boolean isExtServicesInitialized(Context context, String adServicesPackageName) {
        Intent intent = new Intent();
        intent.setComponent(
                new ComponentName(adServicesPackageName, ADSERVICES_SETTINGS_MAINACTIVITY));
        List<ResolveInfo> list = context.getPackageManager().queryIntentActivities(intent,
                PackageManager.MATCH_DEFAULT_ONLY);
        Log.d(TAG, "Components matching AdServicesSettingsMainActivity: " + list);
        return list != null && !list.isEmpty();
    }

    private String getAdExtServicesPackageName(@NonNull Context context) {
        Objects.requireNonNull(context);

        List<PackageInfo> installedPackages =
                context.getPackageManager().getInstalledPackages(PackageManager.MATCH_SYSTEM_ONLY);

        return installedPackages.stream()
                .filter(s -> s.packageName.endsWith("android.ext.adservices.api"))
                .map(s -> s.packageName)
                .findFirst()
                .orElse(null);
    }
}
