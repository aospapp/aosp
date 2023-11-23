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
package com.android.healthconnect.controller.migration.api

import android.health.connect.HealthConnectDataState
import android.health.connect.migration.HealthConnectMigrationUiState
import android.util.Log
import androidx.core.os.asOutcomeReceiver
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext

@Singleton
class LoadMigrationStateUseCase @Inject constructor(private val manager: HealthMigrationManager) {

    companion object {
        private const val TAG = "LoadMigrationState"

        private val migrationStateMapping =
            mapOf(
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_IDLE to MigrationState.IDLE,
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_ALLOWED_MIGRATOR_DISABLED to
                    MigrationState.ALLOWED_MIGRATOR_DISABLED,
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_ALLOWED_NOT_STARTED to
                    MigrationState.ALLOWED_NOT_STARTED,
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_ALLOWED_PAUSED to
                    MigrationState.ALLOWED_PAUSED,
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_ALLOWED_ERROR to
                    MigrationState.ALLOWED_ERROR,
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_IN_PROGRESS to
                    MigrationState.IN_PROGRESS,
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_APP_UPGRADE_REQUIRED to
                    MigrationState.APP_UPGRADE_REQUIRED,
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_MODULE_UPGRADE_REQUIRED to
                    MigrationState.MODULE_UPGRADE_REQUIRED,
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_COMPLETE to
                    MigrationState.COMPLETE,
                HealthConnectMigrationUiState.MIGRATION_UI_STATE_COMPLETE_IDLE to
                    MigrationState.COMPLETE_IDLE,
            )
    }

    suspend operator fun invoke(): MigrationState {
        return withContext(Dispatchers.IO) {
            try {
                // check if module faced an error migrating data and user action is required.
                val dataRestoreState = suspendCancellableCoroutine { continuation ->
                    manager.getHealthDataState(Runnable::run, continuation.asOutcomeReceiver())
                }
                if (HealthConnectDataState.MIGRATION_STATE_MODULE_UPGRADE_REQUIRED ==
                    dataRestoreState.dataMigrationState) {
                    return@withContext MigrationState.MODULE_UPGRADE_REQUIRED
                }

                // check apk migration state.
                val migrationState =
                    suspendCancellableCoroutine { continuation ->
                            manager.getHealthConnectMigrationUiState(
                                Runnable::run, continuation.asOutcomeReceiver())
                        }
                        .healthConnectMigrationUiState

                migrationStateMapping.getOrDefault(migrationState, MigrationState.IDLE)
            } catch (e: Exception) {
                Log.e(TAG, "Load error ", e)
                MigrationState.IDLE
            }
        }
    }
}
