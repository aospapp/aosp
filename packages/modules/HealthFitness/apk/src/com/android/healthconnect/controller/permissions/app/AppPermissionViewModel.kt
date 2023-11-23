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
 *
 *
 */

/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.healthconnect.controller.permissions.app

import android.health.connect.TimeInstantRangeFilter
import android.util.Log
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.healthconnect.controller.deletion.DeletionType
import com.android.healthconnect.controller.deletion.api.DeleteAppDataUseCase
import com.android.healthconnect.controller.permissions.api.GrantHealthPermissionUseCase
import com.android.healthconnect.controller.permissions.api.LoadAccessDateUseCase
import com.android.healthconnect.controller.permissions.api.RevokeAllHealthPermissionsUseCase
import com.android.healthconnect.controller.permissions.api.RevokeHealthPermissionUseCase
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.service.IoDispatcher
import com.android.healthconnect.controller.shared.app.AppInfoReader
import com.android.healthconnect.controller.shared.app.AppMetadata
import dagger.hilt.android.lifecycle.HiltViewModel
import java.time.Instant
import javax.inject.Inject
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.launch

/** View model for {@link ConnectedAppFragment} . */
@HiltViewModel
class AppPermissionViewModel
@Inject
constructor(
    private val appInfoReader: AppInfoReader,
    private val loadAppPermissionsStatusUseCase: LoadAppPermissionsStatusUseCase,
    private val grantPermissionsStatusUseCase: GrantHealthPermissionUseCase,
    private val revokePermissionsStatusUseCase: RevokeHealthPermissionUseCase,
    private val revokeAllHealthPermissionsUseCase: RevokeAllHealthPermissionsUseCase,
    private val deleteAppDataUseCase: DeleteAppDataUseCase,
    private val loadAccessDateUseCase: LoadAccessDateUseCase,
    @IoDispatcher private val ioDispatcher: CoroutineDispatcher
) : ViewModel() {

    companion object {
        private const val TAG = "AppPermissionViewModel"
    }

    private val _appPermissions = MutableLiveData<List<HealthPermission>>(emptyList())
    val appPermissions: LiveData<List<HealthPermission>>
        get() = _appPermissions

    private val _grantedPermissions = MutableLiveData<Set<HealthPermission>>(emptySet())
    val grantedPermissions: LiveData<Set<HealthPermission>>
        get() = _grantedPermissions

    private val _allAppPermissionsGranted = MutableLiveData(false)
    val allAppPermissionsGranted: LiveData<Boolean>
        get() = _allAppPermissionsGranted

    private val _atLeastOnePermissionGranted = MutableLiveData(false)
    val atLeastOnePermissionGranted: LiveData<Boolean>
        get() = _atLeastOnePermissionGranted

    private val _appInfo = MutableLiveData<AppMetadata>()
    val appInfo: LiveData<AppMetadata>
        get() = _appInfo

    private val _revokeAllPermissionsState =
        MutableLiveData<RevokeAllState>(RevokeAllState.NotStarted)
    val revokeAllPermissionsState: LiveData<RevokeAllState>
        get() = _revokeAllPermissionsState

    var _permissionsStatus: List<HealthPermissionStatus> = listOf()

    fun loadForPackage(packageName: String) {
        viewModelScope.launch {
            _appInfo.postValue(appInfoReader.getAppMetadata(packageName))

            _permissionsStatus = loadAppPermissionsStatusUseCase.invoke(packageName)
            _appPermissions.postValue(_permissionsStatus.map { it.healthPermission })
            _allAppPermissionsGranted.postValue(_permissionsStatus.all { it.isGranted })
            _atLeastOnePermissionGranted.postValue(_permissionsStatus.any { it.isGranted })
            _grantedPermissions.postValue(
                _permissionsStatus.filter { it.isGranted }.map { it.healthPermission }.toSet())
        }
    }

    fun loadAppInfo(packageName: String) {
        viewModelScope.launch { _appInfo.postValue(appInfoReader.getAppMetadata(packageName)) }
    }

    fun loadAccessDate(packageName: String): Instant? {
        return loadAccessDateUseCase.invoke(packageName)
    }

    fun updatePermission(
        packageName: String,
        healthPermission: HealthPermission,
        grant: Boolean
    ): Boolean {
        val grantedPermissions = _grantedPermissions.value.orEmpty().toMutableSet()
        try {
            if (grant) {
                grantPermissionsStatusUseCase.invoke(packageName, healthPermission.toString())
                grantedPermissions.add(healthPermission)
                _grantedPermissions.postValue(grantedPermissions)
            } else {
                grantedPermissions.remove(healthPermission)
                _grantedPermissions.postValue(grantedPermissions)
                revokePermissionsStatusUseCase.invoke(packageName, healthPermission.toString())
            }

            viewModelScope.launch {
                _permissionsStatus = loadAppPermissionsStatusUseCase.invoke(packageName)
                _allAppPermissionsGranted.postValue(_permissionsStatus.all { it.isGranted })
                _atLeastOnePermissionGranted.postValue(_permissionsStatus.any { it.isGranted })
            }

            return true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to update permissions!", ex)
        }
        return false
    }

    fun grantAllPermissions(packageName: String): Boolean {
        try {
            _appPermissions.value?.forEach {
                grantPermissionsStatusUseCase.invoke(packageName, it.toString())
            }
            val grantedPermissions = _grantedPermissions.value.orEmpty().toMutableSet()
            grantedPermissions.addAll(_appPermissions.value.orEmpty())
            _grantedPermissions.postValue(grantedPermissions)
            return true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to update permissions!", ex)
        }
        return false
    }

    fun revokeAllPermissions(packageName: String): Boolean {
        try {
            viewModelScope.launch(ioDispatcher) {
                _revokeAllPermissionsState.postValue(RevokeAllState.Loading)
                revokeAllHealthPermissionsUseCase.invoke(packageName)
                loadForPackage(packageName)
                _revokeAllPermissionsState.postValue(RevokeAllState.Updated)
                _grantedPermissions.postValue(emptySet())
            }
            return true
        } catch (ex: Exception) {
            Log.e(TAG, "Failed to update permissions!", ex)
        }
        return false
    }

    fun deleteAppData(packageName: String, appName: String) {
        viewModelScope.launch {
            val appData = DeletionType.DeletionTypeAppData(packageName, appName)
            val timeRangeFilter =
                TimeInstantRangeFilter.Builder()
                    .setStartTime(Instant.EPOCH)
                    .setEndTime(Instant.ofEpochMilli(Long.MAX_VALUE))
                    .build()
            deleteAppDataUseCase.invoke(appData, timeRangeFilter)
        }
    }

    sealed class RevokeAllState {
        object NotStarted : RevokeAllState()
        object Loading : RevokeAllState()
        object Updated : RevokeAllState()
    }
}
