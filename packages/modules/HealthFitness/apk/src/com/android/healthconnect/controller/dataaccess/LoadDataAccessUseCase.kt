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

package com.android.healthconnect.controller.dataaccess

import com.android.healthconnect.controller.permissions.api.GetGrantedHealthPermissionsUseCase
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.permissions.data.HealthPermissionType
import com.android.healthconnect.controller.permissions.data.PermissionsAccessType
import com.android.healthconnect.controller.service.IoDispatcher
import com.android.healthconnect.controller.shared.HealthPermissionReader
import com.android.healthconnect.controller.shared.app.AppInfoReader
import com.android.healthconnect.controller.shared.app.AppMetadata
import com.android.healthconnect.controller.shared.usecase.UseCaseResults
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.withContext

@Singleton
class LoadDataAccessUseCase
@Inject
constructor(
    private val loadPermissionTypeContributorAppsUseCase: LoadPermissionTypeContributorAppsUseCase,
    private val loadGrantedHealthPermissionsUseCase: GetGrantedHealthPermissionsUseCase,
    private val healthPermissionReader: HealthPermissionReader,
    private val appInfoReader: AppInfoReader,
    @IoDispatcher private val dispatcher: CoroutineDispatcher
) {
    /** Returns a map of [DataAccessAppState] to apps. */
    suspend operator fun invoke(
        permissionType: HealthPermissionType
    ): UseCaseResults<Map<DataAccessAppState, List<AppMetadata>>> =
        withContext(dispatcher) {
            try {
                val appsWithHealthPermissions: List<String> =
                    healthPermissionReader.getAppsWithHealthPermissions()
                val contributingApps: List<AppMetadata> =
                    loadPermissionTypeContributorAppsUseCase.invoke(permissionType)
                val readAppMetadataSet: MutableSet<AppMetadata> = mutableSetOf()
                val writeAppMetadataSet: MutableSet<AppMetadata> = mutableSetOf()
                val writeAppPackageNameSet: MutableSet<String> = mutableSetOf()
                val inactiveAppMetadataSet: MutableSet<AppMetadata> = mutableSetOf()

                appsWithHealthPermissions.forEach {
                    val permissionsPerPackage: List<String> =
                        loadGrantedHealthPermissionsUseCase(it)

                    // Apps that can READ the given healthPermissionType.
                    if (permissionsPerPackage.contains(
                        HealthPermission(permissionType, PermissionsAccessType.READ).toString())) {
                        readAppMetadataSet.add(appInfoReader.getAppMetadata(it))
                    }
                    // Apps that can WRITE the given healthPermissionType.
                    if (permissionsPerPackage.contains(
                        HealthPermission(permissionType, PermissionsAccessType.WRITE).toString())) {
                        writeAppMetadataSet.add(appInfoReader.getAppMetadata(it))
                        writeAppPackageNameSet.add(it)
                    }
                }
                // Apps that are inactive: can no longer WRITE, but still have data in Health
                // Connect.
                contributingApps.forEach { app ->
                    if (!writeAppPackageNameSet.contains(app.packageName)) {
                        inactiveAppMetadataSet.add(app)
                    }
                }

                val appAccess =
                    mapOf(
                        DataAccessAppState.Read to
                            alphabeticallySortedMetadataList(readAppMetadataSet),
                        DataAccessAppState.Write to
                            alphabeticallySortedMetadataList(writeAppMetadataSet),
                        DataAccessAppState.Inactive to
                            alphabeticallySortedMetadataList(inactiveAppMetadataSet))
                UseCaseResults.Success(appAccess)
            } catch (ex: Exception) {
                UseCaseResults.Failed(ex)
            }
        }

    private fun alphabeticallySortedMetadataList(
        packageNames: Set<AppMetadata>
    ): List<AppMetadata> {
        return packageNames.sortedBy { appMetadata -> appMetadata.appName }
    }
}
