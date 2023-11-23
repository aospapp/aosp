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
package com.android.healthconnect.controller

import android.os.Bundle
import androidx.activity.viewModels
import androidx.navigation.findNavController
import com.android.healthconnect.controller.migration.MigrationActivity.Companion.maybeRedirectToMigrationActivity
import com.android.healthconnect.controller.migration.MigrationViewModel
import com.android.healthconnect.controller.navigation.DestinationChangedListener
import com.android.healthconnect.controller.onboarding.OnboardingActivity.Companion.maybeRedirectToOnboardingActivity
import com.android.healthconnect.controller.utils.activity.EmbeddingUtils.maybeRedirectIntoTwoPaneSettings
import com.android.healthconnect.controller.utils.logging.HealthConnectLogger
import com.android.settingslib.collapsingtoolbar.CollapsingToolbarBaseActivity
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.runBlocking

/** Entry point activity for Health Connect. */
@AndroidEntryPoint(CollapsingToolbarBaseActivity::class)
class MainActivity : Hilt_MainActivity() {
    @Inject lateinit var logger: HealthConnectLogger
    private val migrationViewModel: MigrationViewModel by viewModels()

    public override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        setTitle(R.string.app_label)

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
    }

    override fun onStart() {
        super.onStart()
        findNavController(R.id.nav_host_fragment)
            .addOnDestinationChangedListener(DestinationChangedListener(this))
    }

    override fun onResume() {
        super.onResume()
        val currentMigrationState = runBlocking { migrationViewModel.getCurrentMigrationUiState() }

        if (maybeRedirectToMigrationActivity(this, currentMigrationState)) {
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

    // TODO (b/270864219): implement interaction logging for the menu button
    //    override fun onMenuOpened(featureId: Int, menu: Menu?): Boolean {
    //        logger.logInteraction(ElementName.TOOLBAR_SETTINGS_BUTTON)
    //        return super.onMenuOpened(featureId, menu)
    //    }

}
