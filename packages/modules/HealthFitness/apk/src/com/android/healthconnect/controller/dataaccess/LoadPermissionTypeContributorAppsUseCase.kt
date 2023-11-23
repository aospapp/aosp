package com.android.healthconnect.controller.dataaccess

import android.health.connect.HealthConnectManager
import android.health.connect.RecordTypeInfoResponse
import android.health.connect.datatypes.Record
import androidx.core.os.asOutcomeReceiver
import com.android.healthconnect.controller.permissions.data.HealthPermissionType
import com.android.healthconnect.controller.permissions.data.fromHealthPermissionCategory
import com.android.healthconnect.controller.service.IoDispatcher
import com.android.healthconnect.controller.shared.app.AppInfoReader
import com.android.healthconnect.controller.shared.app.AppMetadata
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Singleton
class LoadPermissionTypeContributorAppsUseCase
@Inject
constructor(
    private val appInfoReader: AppInfoReader,
    private val healthConnectManager: HealthConnectManager,
    @IoDispatcher private val dispatcher: CoroutineDispatcher
) {
    suspend operator fun invoke(permissionType: HealthPermissionType): List<AppMetadata> =
        withContext(dispatcher) {
            try {
                val recordTypeInfoMap: Map<Class<out Record>, RecordTypeInfoResponse> =
                    suspendCancellableCoroutine { continuation ->
                        healthConnectManager.queryAllRecordTypesInfo(
                            Runnable::run, continuation.asOutcomeReceiver())
                    }
                val packages =
                    recordTypeInfoMap.values
                        .filter {
                            fromHealthPermissionCategory(it.permissionCategory) == permissionType &&
                                it.contributingPackages.isNotEmpty()
                        }
                        .map { it.contributingPackages }
                        .flatten()
                packages
                    .map { appInfoReader.getAppMetadata(it.packageName) }
                    .sortedBy { it.appName }
            } catch (e: Exception) {
                emptyList()
            }
        }
}
