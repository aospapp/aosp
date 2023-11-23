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
import android.net.Uri
import android.tools.common.traces.component.ComponentNameMatcher

/** Helper to launch the Maps app (not compatible with AOSP) */
class MapsAppHelper
@JvmOverloads
constructor(
    instrumentation: Instrumentation,
    pkgManager: PackageManager = instrumentation.context.packageManager
) :
    StandardAppHelper(
        instrumentation,
        MapsAppHelper.Companion.getMapLauncherName(pkgManager),
        MapsAppHelper.Companion.getMapComponent(pkgManager)
    ) {
    companion object {
        private fun getMapIntent(): Intent {
            val gmmIntentUri = Uri.parse("google.streetview:cbll=46.414382,10.013988")
            return Intent(Intent.ACTION_VIEW, gmmIntentUri)
        }

        private fun getResolveInfo(pkgManager: PackageManager): ResolveInfo {
            val intent = MapsAppHelper.Companion.getMapIntent()
            return pkgManager.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY)
                ?: error("unable to resolve camera activity")
        }

        private fun getMapComponent(pkgManager: PackageManager): ComponentNameMatcher {
            val resolveInfo = MapsAppHelper.Companion.getResolveInfo(pkgManager)
            return ComponentNameMatcher(
                resolveInfo.activityInfo.packageName,
                className = resolveInfo.activityInfo.name
            )
        }

        private fun getMapLauncherName(pkgManager: PackageManager): String {
            val resolveInfo = MapsAppHelper.Companion.getResolveInfo(pkgManager)
            return resolveInfo.loadLabel(pkgManager).toString()
        }
    }
}
