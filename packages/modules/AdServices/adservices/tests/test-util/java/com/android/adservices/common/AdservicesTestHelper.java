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

package com.android.adservices.common;

import android.annotation.NonNull;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.content.pm.ServiceInfo;
import android.util.Log;

import com.android.adservices.AdServicesCommon;
import com.android.compatibility.common.util.ShellUtils;

import java.util.List;

/** Class to place Adservices CTS related helper method. */
public class AdservicesTestHelper {
    // Used to get the package name. Copied over from com.android.adservices.AdServicesCommon
    private static final String TOPICS_SERVICE_NAME = "android.adservices.TOPICS_SERVICE";
    private static final String DEFAULT_LOG_TAG = "adservices";
    private static final String FORCE_KILL_PROCESS_COMMAND = "am force-stop";

    /**
     * Used to get the package name. Copied over from com.android.adservices.AndroidServiceBinder
     *
     * @param context the context
     * @param logTag the tag used for logging
     * @return Adservices package name
     */
    public static String getAdServicesPackageName(
            @NonNull Context context, @NonNull String logTag) {
        final Intent intent = new Intent(TOPICS_SERVICE_NAME);
        final List<ResolveInfo> resolveInfos =
                context.getPackageManager()
                        .queryIntentServices(intent, PackageManager.MATCH_SYSTEM_ONLY);
        final ServiceInfo serviceInfo =
                AdServicesCommon.resolveAdServicesService(resolveInfos, TOPICS_SERVICE_NAME);
        if (serviceInfo == null) {
            Log.e(logTag, "Failed to find serviceInfo for adServices service");
            return null;
        }

        return serviceInfo.packageName;
    }

    /**
     * Used to get the package name. An overloading method of {@code
     * getAdservicesPackageName(context, logTag)} by using {@code DEFAULT_LOG_TAG}.
     *
     * @param context the context
     * @return Adservices package name
     */
    public static String getAdServicesPackageName(@NonNull Context context) {
        return getAdServicesPackageName(context, DEFAULT_LOG_TAG);
    }

    /**
     * Kill the Adservices process.
     *
     * @param context the context used to get Adservices package name.
     * @param logTag the tag used for logging
     */
    public static void killAdservicesProcess(@NonNull Context context, @NonNull String logTag) {
        ShellUtils.runShellCommand(
                "%s %s", FORCE_KILL_PROCESS_COMMAND, getAdServicesPackageName(context, logTag));
    }

    /**
     * Kill the Adservices process. An overloading method of {@code killAdservicesProcess(context,
     * logTag)} by using {@code DEFAULT_LOG_TAG}.
     *
     * @param context the context used to get Adservices package name.
     */
    public static void killAdservicesProcess(@NonNull Context context) {
        killAdservicesProcess(context, DEFAULT_LOG_TAG);
    }

    /**
     * Kill the Adservices process. An overloading method of {@code killAdservicesProcess(context,
     * logTag)} by using Adservices package name directly.
     *
     * @param adservicesPackageName the Adservices package name.
     */
    public static void killAdservicesProcess(@NonNull String adservicesPackageName) {
        ShellUtils.runShellCommand("%s %s", FORCE_KILL_PROCESS_COMMAND, adservicesPackageName);
    }

    /**
     * Check whether the device is supported. Adservices doesn't support non-phone device.
     *
     * @return if the device is supported.
     * @deprecated use {@link AdServicesSupportRule} instead.
     */
    @Deprecated
    public static boolean isDeviceSupported() {
        return AdServicesSupportRule.isDeviceSupported();
    }
}
