/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * ```
 *      http://www.apache.org/licenses/LICENSE-2.0
 * ```
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.healthconnect.controller.shared

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.content.pm.PackageManager.PackageInfoFlags
import android.content.pm.PackageManager.ResolveInfoFlags
import android.health.connect.HealthConnectManager
import android.health.connect.HealthPermissions
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.utils.FeatureUtils
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

/**
 * Class that read permissions declared by Health Connect clients as a string array in their XML
 * resources. see android.health.connect.HealthPermissions
 */
class HealthPermissionReader
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val featureUtils: FeatureUtils
) {

    companion object {
        private const val RESOLVE_INFO_FLAG: Long = PackageManager.MATCH_ALL.toLong()
        private const val PACKAGE_INFO_PERMISSIONS_FLAG: Long =
            PackageManager.GET_PERMISSIONS.toLong()
        private val sessionTypePermissions =
            listOf(
                HealthPermissions.READ_EXERCISE,
                HealthPermissions.WRITE_EXERCISE,
                HealthPermissions.READ_SLEEP,
                HealthPermissions.WRITE_SLEEP,
            )
        private val exerciseRoutePermissions =
            listOf(
                HealthPermissions.WRITE_EXERCISE_ROUTE,
            )
    }

    suspend fun getAppsWithHealthPermissions(): List<String> {
        return try {
            val appsWithDeclaredIntent =
                context.packageManager
                    .queryIntentActivities(
                        getRationaleIntent(), ResolveInfoFlags.of(RESOLVE_INFO_FLAG))
                    .map { it.activityInfo.packageName }

            appsWithDeclaredIntent.filter { getDeclaredPermissions(it).isNotEmpty() }
        } catch (e: Exception) {
            emptyList()
        }
    }

    suspend fun getDeclaredPermissions(packageName: String): List<HealthPermission> {
        return try {
            val appInfo =
                context.packageManager.getPackageInfo(
                    packageName, PackageInfoFlags.of(PACKAGE_INFO_PERMISSIONS_FLAG))
            val healthPermissions = getHealthPermissions()
            appInfo.requestedPermissions
                ?.filter { it in healthPermissions }
                ?.map { permission -> parsePermission(permission) }
                .orEmpty()
        } catch (e: NameNotFoundException) {
            emptyList()
        }
    }

    fun isRationalIntentDeclared(packageName: String): Boolean {
        val intent = getRationaleIntent(packageName)
        val resolvedInfo =
            context.packageManager.queryIntentActivities(
                intent, ResolveInfoFlags.of(RESOLVE_INFO_FLAG))
        return resolvedInfo.any { info -> info.activityInfo.packageName == packageName }
    }

    fun getApplicationRationaleIntent(packageName: String): Intent {
        val intent = getRationaleIntent(packageName)
        val resolvedInfo =
            context.packageManager.queryIntentActivities(
                intent, ResolveInfoFlags.of(RESOLVE_INFO_FLAG))
        resolvedInfo.forEach { info -> intent.setClassName(packageName, info.activityInfo.name) }
        return intent
    }

    private fun parsePermission(permission: String): HealthPermission {
        return HealthPermission.fromPermissionString(permission)
    }

    private fun getHealthPermissions(): List<String> {
        val permissions =
            context.packageManager
                .queryPermissionsByGroup("android.permission-group.HEALTH", 0)
                .map { permissionInfo -> permissionInfo.name }
        return permissions.filterNot { permission ->
            shouldHideExerciseRoute(permission) || shouldHideSessionTypes(permission)
        }
    }

    private fun shouldHideExerciseRoute(permission: String): Boolean {
        return permission in exerciseRoutePermissions && !featureUtils.isExerciseRouteEnabled()
    }

    private fun shouldHideSessionTypes(permission: String): Boolean {
        return permission in sessionTypePermissions && !featureUtils.isSessionTypesEnabled()
    }

    private fun getRationaleIntent(packageName: String? = null): Intent {
        val intent =
            Intent(Intent.ACTION_VIEW_PERMISSION_USAGE).apply {
                addCategory(HealthConnectManager.CATEGORY_HEALTH_PERMISSIONS)
                if (packageName != null) {
                    setPackage(packageName)
                }
            }
        return intent
    }
}
