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

package com.android.adservices.service.common;

import android.adservices.common.AdServicesPermissions;
import android.annotation.NonNull;
import android.content.Context;
import android.content.pm.PackageManager;

/**
 * AdServicesApi permission helper. This class provides helper methods to check for permissions that
 * need to be declared by callers of the APIs provided by AdServicesApi.
 *
 * @hide
 */
public final class PermissionHelper {
    private PermissionHelper() {}

    private static boolean checkSdkPermission(
            @NonNull Context context, @NonNull String sdkName, @NonNull String perm) {
        return context.getPackageManager().checkPermission(perm, sdkName)
                == PackageManager.PERMISSION_GRANTED;
    }

    /** @return {@code true} if the caller has the permission to invoke Topics APIs. */
    public static boolean hasTopicsPermission(
            @NonNull Context context, boolean useSandboxCheck, @NonNull String sdkName) {
        boolean callerPerm =
                context.checkCallingOrSelfPermission(AdServicesPermissions.ACCESS_ADSERVICES_TOPICS)
                        == PackageManager.PERMISSION_GRANTED;
        // Note: Checking permission declared by Sdk running in Sandbox is only for accounting
        // purposes and should not be used as a security measure.
        if (useSandboxCheck) {
            return callerPerm
                    && checkSdkPermission(
                            context, sdkName, AdServicesPermissions.ACCESS_ADSERVICES_TOPICS);
        }
        return callerPerm;
    }

    /** @return {@code true} if the caller has the permission to invoke AdID APIs. */
    public static boolean hasAdIdPermission(
            @NonNull Context context, boolean useSandboxCheck, @NonNull String sdkName) {

        boolean callerPerm =
                context.checkCallingOrSelfPermission(AdServicesPermissions.ACCESS_ADSERVICES_AD_ID)
                        == PackageManager.PERMISSION_GRANTED;
        // Note: Checking permission declared by Sdk running in Sandbox is only for accounting
        // purposes and should not be used as a security measure.
        if (useSandboxCheck) {
            return callerPerm
                    && checkSdkPermission(
                            context, sdkName, AdServicesPermissions.ACCESS_ADSERVICES_AD_ID);
        }
        return callerPerm;
    }

    /** @return {@code true} if the caller has the permission to invoke Attribution APIs. */
    public static boolean hasAttributionPermission(@NonNull Context context) {
        // TODO(b/236267953): Add check for SDK permission.
        int status =
                context.checkCallingOrSelfPermission(
                        AdServicesPermissions.ACCESS_ADSERVICES_ATTRIBUTION);
        return status == PackageManager.PERMISSION_GRANTED;
    }

    /** @return {@code true} if the caller has the permission to invoke Custom Audiences APIs. */
    public static boolean hasCustomAudiencesPermission(@NonNull Context context) {
        // TODO(b/236268316): Add check for SDK permission.
        int status =
                context.checkCallingOrSelfPermission(
                        AdServicesPermissions.ACCESS_ADSERVICES_CUSTOM_AUDIENCE);
        return status == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return {@code true} if the caller has the permission to invoke AdService's state
     *     modification API.
     */
    public static boolean hasModifyAdServicesStatePermission(@NonNull Context context) {
        int status =
                context.checkCallingOrSelfPermission(AdServicesPermissions.MODIFY_ADSERVICES_STATE);
        return status == PackageManager.PERMISSION_GRANTED;
    }

    /**
     * @return {@code true} if the caller has the permission to invoke AdService's state access API.
     */
    public static boolean hasAccessAdServicesStatePermission(@NonNull Context context) {
        int status =
                context.checkCallingOrSelfPermission(AdServicesPermissions.ACCESS_ADSERVICES_STATE);
        return status == PackageManager.PERMISSION_GRANTED;
    }
}
