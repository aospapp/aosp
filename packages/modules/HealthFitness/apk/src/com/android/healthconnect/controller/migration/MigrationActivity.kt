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
package com.android.healthconnect.controller.migration

import android.app.Activity
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.os.Bundle
import androidx.fragment.app.FragmentActivity
import androidx.navigation.findNavController
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.migration.MigrationPausedFragment.Companion.INTEGRATION_PAUSED_SEEN_KEY
import com.android.healthconnect.controller.migration.api.MigrationState
import com.android.healthconnect.controller.shared.dialog.AlertDialogBuilder
import com.android.healthconnect.controller.utils.logging.MigrationElement
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint(FragmentActivity::class)
class MigrationActivity : Hilt_MigrationActivity() {

    companion object {
        const val MIGRATION_COMPLETE_KEY = "migration_complete_key"
        const val MIGRATION_ACTIVITY_INTENT = "android.health.connect.action.MIGRATION"

        private fun isMigrationComplete(activity: Activity): Boolean {
            val sharedPreference =
                activity.getSharedPreferences("USER_ACTIVITY_TRACKER", Context.MODE_PRIVATE)
            return sharedPreference.getBoolean(MIGRATION_COMPLETE_KEY, false)
        }

        fun maybeRedirectToMigrationActivity(
            activity: Activity,
            migrationState: MigrationState
        ): Boolean {

            val sharedPreference =
                activity.getSharedPreferences("USER_ACTIVITY_TRACKER", Context.MODE_PRIVATE)

            if (migrationState == MigrationState.MODULE_UPGRADE_REQUIRED) {
                val moduleUpdateSeen =
                    sharedPreference.getBoolean(
                        activity.getString(R.string.module_update_needed_seen), false)

                if (!moduleUpdateSeen) {
                    activity.startActivity(createMigrationActivityIntent(activity))
                    activity.finish()
                    return true
                }
            } else if (migrationState == MigrationState.APP_UPGRADE_REQUIRED) {
                val appUpdateSeen =
                    sharedPreference.getBoolean(
                        activity.getString(R.string.app_update_needed_seen), false)

                if (!appUpdateSeen) {
                    activity.startActivity(createMigrationActivityIntent(activity))
                    activity.finish()
                    return true
                }
            } else if (migrationState == MigrationState.ALLOWED_PAUSED ||
                    migrationState == MigrationState.ALLOWED_NOT_STARTED) {
                val allowedPausedSeen =
                        sharedPreference.getBoolean(INTEGRATION_PAUSED_SEEN_KEY, false)

                if (!allowedPausedSeen) {
                    activity.startActivity(createMigrationActivityIntent(activity))
                    activity.finish()
                    return true
                }
            }

            else if (migrationState == MigrationState.IN_PROGRESS) {
                activity.startActivity(createMigrationActivityIntent(activity))
                activity.finish()
                return true
            }

            return false
        }

        fun showMigrationPendingDialog(
            context: Context,
            message: String,
            positiveButtonAction: DialogInterface.OnClickListener? = null,
            negativeButtonAction: DialogInterface.OnClickListener? = null
        ) {
            AlertDialogBuilder(context)
                .setLogName(MigrationElement.MIGRATION_PENDING_DIALOG_CONTAINER)
                .setTitle(R.string.migration_pending_permissions_dialog_title)
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton(
                    R.string.migration_pending_permissions_dialog_button_start_integration,
                    MigrationElement.MIGRATION_PENDING_DIALOG_CANCEL_BUTTON,
                    negativeButtonAction)
                .setPositiveButton(
                    R.string.migration_pending_permissions_dialog_button_continue,
                    MigrationElement.MIGRATION_PENDING_DIALOG_CONTINUE_BUTTON,
                    positiveButtonAction)
                .create()
                .show()
        }

        fun showMigrationInProgressDialog(
            context: Context,
            message: String,
            negativeButtonAction: DialogInterface.OnClickListener? = null
        ) {
            AlertDialogBuilder(context)
                .setLogName(MigrationElement.MIGRATION_IN_PROGRESS_DIALOG_CONTAINER)
                .setTitle(R.string.migration_in_progress_permissions_dialog_title)
                .setMessage(message)
                .setCancelable(false)
                .setNegativeButton(
                    R.string.migration_in_progress_permissions_dialog_button_got_it,
                    MigrationElement.MIGRATION_IN_PROGRESS_DIALOG_BUTTON,
                    negativeButtonAction)
                .create()
                .show()
        }

        fun maybeShowWhatsNewDialog(context: Context,
                                    negativeButtonAction: DialogInterface.OnClickListener? = null) {
            val sharedPreference =
                    context.getSharedPreferences("USER_ACTIVITY_TRACKER", Context.MODE_PRIVATE)
            val dialogSeen =
                    sharedPreference.getBoolean(context.getString(R.string.whats_new_dialog_seen), false)

            if (!dialogSeen) {
                AlertDialogBuilder(context)
                        .setLogName(MigrationElement.MIGRATION_DONE_DIALOG_CONTAINER)
                        .setTitle(R.string.migration_whats_new_dialog_title)
                        .setMessage(R.string.migration_whats_new_dialog_content)
                        .setCancelable(false)
                        .setNegativeButton(
                                R.string.migration_whats_new_dialog_button, MigrationElement.MIGRATION_DONE_DIALOG_BUTTON) {
                            unusedDialogInterface, unusedInt ->
                            sharedPreference.edit().apply {
                                putBoolean(context.getString(R.string.whats_new_dialog_seen), true)
                                apply()
                            }
                            negativeButtonAction?.onClick(unusedDialogInterface, unusedInt)
                        }
                        .create()
                        .show()
            }
        }

        private fun createMigrationActivityIntent(context: Context): Intent {
            return Intent(context, MigrationActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP)
                addFlags(Intent.FLAG_ACTIVITY_NO_HISTORY)
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_migration)
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

    override fun onResume() {
        super.onResume()
        val navController = findNavController(R.id.nav_host_fragment)
        navController.setGraph(R.navigation.migration_nav_graph)
    }
}
