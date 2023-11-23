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

package com.android.healthconnect.controller.tests.migration.api

import android.health.connect.HealthConnectDataState
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_ALLOWED_ERROR
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_ALLOWED_MIGRATOR_DISABLED
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_ALLOWED_NOT_STARTED
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_ALLOWED_PAUSED
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_APP_UPGRADE_REQUIRED
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_COMPLETE
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_COMPLETE_IDLE
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_IDLE
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_IN_PROGRESS
import android.health.connect.migration.HealthConnectMigrationUiState.MIGRATION_UI_STATE_MODULE_UPGRADE_REQUIRED
import com.android.healthconnect.controller.migration.api.LoadMigrationStateUseCase
import com.android.healthconnect.controller.migration.api.MigrationState
import com.android.healthconnect.controller.tests.utils.di.FakeHealthMigrationManager
import com.google.common.truth.Truth.assertThat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class LoadMigrationStateUseCaseTest {

    private val migrationManager = FakeHealthMigrationManager()

    @Test
    fun invoke_stateIdle_mapsStateToIdle() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_IDLE)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.IDLE)
    }

    @Test
    fun invoke_stateAllowedMigratorDisabled_mapsStateToAllowedMigratorDisabled() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_ALLOWED_MIGRATOR_DISABLED)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.ALLOWED_MIGRATOR_DISABLED)
    }

    @Test
    fun invoke_stateAllowedNotStarted_mapsAllowedNotStarted() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_ALLOWED_NOT_STARTED)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.ALLOWED_NOT_STARTED)
    }

    @Test
    fun invoke_stateAllowedPaused_mapsStateToAllowedPaused() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_ALLOWED_PAUSED)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.ALLOWED_PAUSED)
    }

    @Test
    fun invoke_stateIdleAllowedError_mapsStateToAllowedError() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_ALLOWED_ERROR)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.ALLOWED_ERROR)
    }

    @Test
    fun invoke_stateInProgress_mapsStateToInProgress() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_IN_PROGRESS)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.IN_PROGRESS)
    }

    @Test
    fun invoke_stateAppUpgradeRequired_mapsStateToAppUpgradeRequired() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_APP_UPGRADE_REQUIRED)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.APP_UPGRADE_REQUIRED)
    }

    @Test
    fun invoke_stateModuleUpgradeRequired_mapsStateToModuleUpgradeRequired() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_MODULE_UPGRADE_REQUIRED)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.MODULE_UPGRADE_REQUIRED)
    }

    @Test
    fun invoke_stateComplete_mapsStateToComplete() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_COMPLETE)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.COMPLETE)
    }

    @Test
    fun invoke_stateCompleteIdle_mapsStateToCompleteIdle() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setMigrationState(MIGRATION_UI_STATE_COMPLETE_IDLE)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.COMPLETE_IDLE)
    }

    @Test
    fun invoke_dataMigration_restoreErrorVersionDiff_returnsModuleUpgradeRequired() = runTest {
        val useCase = LoadMigrationStateUseCase(migrationManager)
        migrationManager.setDataMigrationState(
            error = HealthConnectDataState.RESTORE_ERROR_VERSION_DIFF,
            migrationState = HealthConnectDataState.MIGRATION_STATE_MODULE_UPGRADE_REQUIRED)

        assertThat(useCase.invoke()).isEqualTo(MigrationState.MODULE_UPGRADE_REQUIRED)
    }
}
