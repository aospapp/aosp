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

package com.android.adservices.data.common;

import android.content.pm.PackageManager;

import androidx.annotation.NonNull;

import com.android.adservices.service.Flags;
import com.android.adservices.service.common.AllowLists;
import com.android.adservices.service.common.compat.PackageManagerCompatUtils;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Contains utility functions for cleaning up data from disallowed apps. */
public final class CleanupUtils {

    /**
     * Takes a list of packages which may or may not be allowed to use PPAPIs and removes all the
     * packages that are allowed to use PPAPIs.
     *
     * @param packages a list of package names
     * @param packageManager the package manager
     * @param flags the adservices flags
     */
    public static void removeAllowedPackages(
            @NonNull List<String> packages,
            @NonNull PackageManager packageManager,
            @NonNull Flags flags) {
        if (!packages.isEmpty()) {
            Set<String> allowedPackages =
                    PackageManagerCompatUtils.getInstalledApplications(packageManager, 0).stream()
                            .map(applicationInfo -> applicationInfo.packageName)
                            .collect(Collectors.toSet());

            String appAllowList = flags.getPpapiAppAllowList();
            if (!AllowLists.doesAllowListAllowAll(appAllowList)) {
                allowedPackages.retainAll(AllowLists.splitAllowList(appAllowList));
            }

            // Packages must be both installed and allowlisted, or else they should be removed
            packages.removeAll(allowedPackages);
        }
    }
}
