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

package com.android.server.nearby.fastpair;

import static com.android.nearby.halfsheet.constants.Constant.ACTION_RESOURCES_APK;

import android.annotation.Nullable;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import com.android.server.nearby.util.Environment;

import java.util.List;
import java.util.stream.Collectors;

/**
 * Helper class for package related methods.
 */
public class PackageUtils {

    /**
     * Gets the package name of HalfSheet.apk
     */
    @Nullable
    public static String getHalfSheetApkPkgName(Context context) {
        List<ResolveInfo> resolveInfos = context
                .getPackageManager().queryIntentActivities(
                        new Intent(ACTION_RESOURCES_APK),
                        PackageManager.MATCH_SYSTEM_ONLY);

        // remove apps that don't live in the nearby apex
        resolveInfos.removeIf(info ->
                !Environment.isAppInNearbyApex(info.activityInfo.applicationInfo));

        if (resolveInfos.isEmpty()) {
            // Resource APK not loaded yet, print a stack trace to see where this is called from
            Log.e("FastPairManager", "Attempted to fetch resources before halfsheet "
                            + " APK is installed or package manager can't resolve correctly!",
                    new IllegalStateException());
            return null;
        }

        if (resolveInfos.size() > 1) {
            // multiple apps found, log a warning, but continue
            Log.w("FastPairManager", "Found > 1 APK that can resolve halfsheet APK intent: "
                    + resolveInfos.stream()
                    .map(info -> info.activityInfo.applicationInfo.packageName)
                    .collect(Collectors.joining(", ")));
        }

        // Assume the first ResolveInfo is the one we're looking for
        ResolveInfo info = resolveInfos.get(0);
        return info.activityInfo.applicationInfo.packageName;
    }
}
