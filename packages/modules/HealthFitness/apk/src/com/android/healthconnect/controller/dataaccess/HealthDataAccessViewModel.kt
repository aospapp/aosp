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

import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.android.healthconnect.controller.dataaccess.HealthDataAccessViewModel.DataAccessScreenState.Error
import com.android.healthconnect.controller.dataaccess.HealthDataAccessViewModel.DataAccessScreenState.WithData
import com.android.healthconnect.controller.permissions.data.HealthPermissionType
import com.android.healthconnect.controller.shared.app.AppMetadata
import com.android.healthconnect.controller.shared.usecase.UseCaseResults
import com.android.healthconnect.controller.utils.postValueIfUpdated
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.launch

/** View model for [HealthDataAccessFragment]. */
@HiltViewModel
class HealthDataAccessViewModel
@Inject
constructor(private val loadDataAccessUseCase: LoadDataAccessUseCase) : ViewModel() {

    private val _appMetadataMap = MutableLiveData<DataAccessScreenState>()

    val appMetadataMap: LiveData<DataAccessScreenState>
        get() = _appMetadataMap

    fun loadAppMetaDataMap(permissionType: HealthPermissionType) {
        val appsMap = _appMetadataMap.value
        if (appsMap is WithData && appsMap.appMetadata.isEmpty()) {
            _appMetadataMap.postValue(DataAccessScreenState.Loading)
        }
        viewModelScope.launch {
            when (val result = loadDataAccessUseCase.invoke(permissionType)) {
                is UseCaseResults.Success -> {
                    _appMetadataMap.postValueIfUpdated(WithData(result.data))
                }
                else -> {
                    _appMetadataMap.postValue(Error)
                }
            }
        }
    }

    /** Represents DataAccessFragment state. */
    sealed class DataAccessScreenState {
        object Loading : DataAccessScreenState()

        object Error : DataAccessScreenState()

        data class WithData(val appMetadata: Map<DataAccessAppState, List<AppMetadata>>) :
            DataAccessScreenState()
    }
}
