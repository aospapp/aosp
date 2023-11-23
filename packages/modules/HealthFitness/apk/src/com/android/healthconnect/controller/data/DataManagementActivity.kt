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

package com.android.healthconnect.controller.data

import android.os.Bundle
import androidx.activity.viewModels
import androidx.navigation.findNavController
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.migration.MigrationActivity
import com.android.healthconnect.controller.migration.MigrationActivity.Companion.maybeRedirectToMigrationActivity
import com.android.healthconnect.controller.migration.MigrationActivity.Companion.maybeShowWhatsNewDialog
import com.android.healthconnect.controller.migration.MigrationViewModel
import com.android.healthconnect.controller.migration.api.MigrationState
import com.android.healthconnect.controller.navigation.DestinationChangedListener
import com.android.healthconnect.controller.onboarding.OnboardingActivity.Companion.maybeRedirectToOnboardingActivity
import com.android.healthconnect.controller.utils.activity.EmbeddingUtils.maybeRedirectIntoTwoPaneSettings
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.runBlocking

/** Entry point activity for Health Connect Data Management controllers. */
@AndroidEntryPoint(CollapsingToolbarBaseActivity::class)
class DataManagementActivity : Hilt_DataManagementActivity() {
    private val migrationViewModel: MigrationViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_data_management)

        if (maybeRedirectIntoTwoPaneSettings(this)) {
            return
        }

        if (maybeRedirectToOnboardingActivity(this, intent)) {
            return
        }

        val currentMigrationState = runBlocking { migrationViewModel.getCurrentMigrationUiState() }

        if (maybeRedirectToMigrationActivity(this, currentMigrationState)) {
            return
        }

        migrationViewModel.migrationState.observe(this) { migrationState ->
            when (migrationState) {
                is MigrationViewModel.MigrationFragmentState.WithData -> {
                    if (migrationState.migrationState == MigrationState.COMPLETE) {
                        maybeShowWhatsNewDialog(this)
                    }
                }
                else -> {
                    // do nothing
                }
            }
        }
    }

    override fun onStart() {
        super.onStart()
        findNavController(R.id.nav_host_fragment)
            .addOnDestinationChangedListener(DestinationChangedListener(this))
    }

    override fun onResume() {
        super.onResume()
        val currentMigrationState = runBlocking { migrationViewModel.getCurrentMigrationUiState() }

        if (MigrationActivity.maybeRedirectToMigrationActivity(this, currentMigrationState)) {
            return
        }
    }

    override fun onBackPressed() {
        val navController = findNavController(R.id.nav_host_fragment)
        if (!navController.popBackStack()) {
            finish()
        }
    }

    override fun onNavigateUp(): Boolean {
        val navController = findNavController(R.id.nav_host_fragment)
        if (!navController.popBackStack()) {
            finish()
        }
        return true
    }
}
