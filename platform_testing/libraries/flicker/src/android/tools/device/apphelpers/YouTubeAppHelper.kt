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

package android.tools.device.apphelpers

import android.app.Instrumentation
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ResolveInfo
import android.tools.common.traces.component.ComponentNameMatcher

/**
 * Helper to launch Youtube (not compatible with AOSP)
 *
 * This helper has no other functionality but the app launch.
 */
class YouTubeAppHelper(
    instrumentation: Instrumentation,
    pkgManager: PackageManager = instrumentation.context.packageManager
) :
    StandardAppHelper(
        instrumentation,
        getYoutubeLauncherName(pkgManager),
        getYoutubeComponent(pkgManager)
    ) {
    companion object {
        private fun getYoutubeIntent(pkgManager: PackageManager): Intent {
            return pkgManager.getLaunchIntentForPackage("com.google.android.youtube")
                ?: error("Youtube launch intent not found")
        }

        private fun getResolveInfo(pkgManager: PackageManager): ResolveInfo {
            val intent = getYoutubeIntent(pkgManager)
            return pkgManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?: error("unable to resolve calendar activity")
        }

        private fun getYoutubeComponent(pkgManager: PackageManager): ComponentNameMatcher {
            val resolveInfo = getResolveInfo(pkgManager)
            return ComponentNameMatcher(
                resolveInfo.activityInfo.packageName,
                className = resolveInfo.activityInfo.name
            )
        }

        private fun getYoutubeLauncherName(pkgManager: PackageManager): String {
            val resolveInfo = getResolveInfo(pkgManager)
            return resolveInfo.loadLabel(pkgManager).toString()
        }
    }
}
