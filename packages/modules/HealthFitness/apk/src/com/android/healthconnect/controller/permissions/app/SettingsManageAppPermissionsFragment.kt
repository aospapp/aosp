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
 * ```
 *      http://www.apache.org/licenses/LICENSE-2.0
 * ```
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.healthconnect.controller.permissions.app

import android.app.Activity
import android.content.Intent
import android.content.Intent.EXTRA_PACKAGE_NAME
import android.health.connect.HealthConnectManager
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.fragment.app.viewModels
import androidx.preference.PreferenceGroup
import androidx.preference.SwitchPreference
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.migration.MigrationActivity
import com.android.healthconnect.controller.migration.MigrationActivity.Companion.MIGRATION_ACTIVITY_INTENT
import com.android.healthconnect.controller.migration.MigrationActivity.Companion.maybeShowWhatsNewDialog
import com.android.healthconnect.controller.migration.MigrationActivity.Companion.showMigrationInProgressDialog
import com.android.healthconnect.controller.migration.MigrationActivity.Companion.showMigrationPendingDialog
import com.android.healthconnect.controller.migration.MigrationViewModel
import com.android.healthconnect.controller.migration.api.MigrationState
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.permissions.data.HealthPermissionStrings.Companion.fromPermissionType
import com.android.healthconnect.controller.permissions.data.PermissionsAccessType
import com.android.healthconnect.controller.permissions.shared.DisconnectDialogFragment
import com.android.healthconnect.controller.shared.HealthDataCategoryExtensions.fromHealthPermissionType
import com.android.healthconnect.controller.shared.HealthDataCategoryExtensions.icon
import com.android.healthconnect.controller.shared.HealthPermissionReader
import com.android.healthconnect.controller.shared.preference.HealthMainSwitchPreference
import com.android.healthconnect.controller.shared.preference.HealthPreferenceFragment
import com.android.healthconnect.controller.shared.preference.HealthSwitchPreference
import com.android.healthconnect.controller.utils.LocalDateTimeFormatter
import com.android.healthconnect.controller.utils.dismissLoadingDialog
import com.android.healthconnect.controller.utils.logging.PageName
import com.android.healthconnect.controller.utils.logging.PermissionsElement
import com.android.healthconnect.controller.utils.showLoadingDialog
import com.android.settingslib.widget.AppHeaderPreference
import com.android.settingslib.widget.FooterPreference
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/**
 * Fragment to show granted/revoked health permissions for and app. It is used as an entry point
 * from PermissionController.
 */
@AndroidEntryPoint(HealthPreferenceFragment::class)
class SettingsManageAppPermissionsFragment : Hilt_SettingsManageAppPermissionsFragment() {

    companion object {
        private const val ALLOW_ALL_PREFERENCE = "allow_all_preference"
        private const val READ_CATEGORY = "read_permission_category"
        private const val WRITE_CATEGORY = "write_permission_category"
        private const val PERMISSION_HEADER = "manage_app_permission_header"
        private const val FOOTER = "manage_app_permission_footer"
        private const val PARAGRAPH_SEPARATOR = "\n\n"
    }

    init {
        this.setPageName(PageName.MANAGE_PERMISSIONS_PAGE)
    }

    @Inject lateinit var healthPermissionReader: HealthPermissionReader

    private lateinit var packageName: String
    private val viewModel: AppPermissionViewModel by viewModels()
    private val permissionMap: MutableMap<HealthPermission, SwitchPreference> = mutableMapOf()
    private val migrationViewModel: MigrationViewModel by viewModels()

    private val allowAllPreference: HealthMainSwitchPreference? by lazy {
        preferenceScreen.findPreference(ALLOW_ALL_PREFERENCE)
    }

    private val readPermissionCategory: PreferenceGroup? by lazy {
        preferenceScreen.findPreference(READ_CATEGORY)
    }

    private val writePermissionCategory: PreferenceGroup? by lazy {
        preferenceScreen.findPreference(WRITE_CATEGORY)
    }

    private val header: AppHeaderPreference? by lazy {
        preferenceScreen.findPreference(PERMISSION_HEADER)
    }

    private val mFooter: FooterPreference? by lazy { preferenceScreen.findPreference(FOOTER) }

    private val dateFormatter: LocalDateTimeFormatter by lazy {
        LocalDateTimeFormatter(requireContext())
    }

    override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
        super.onCreatePreferences(savedInstanceState, rootKey)
        setPreferencesFromResource(R.xml.settings_manage_app_permission_screen, rootKey)

        allowAllPreference?.logNameActive = PermissionsElement.ALLOW_ALL_SWITCH
        allowAllPreference?.logNameInactive = PermissionsElement.ALLOW_ALL_SWITCH
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        if (requireArguments().containsKey(EXTRA_PACKAGE_NAME) &&
            requireArguments().getString(EXTRA_PACKAGE_NAME) != null) {
            packageName = requireArguments().getString(EXTRA_PACKAGE_NAME)!!
        }

        viewModel.loadAppInfo(packageName)
        viewModel.loadForPackage(packageName)
        viewModel.appPermissions.observe(viewLifecycleOwner) { permissions ->
            updatePermissions(permissions)
        }
        viewModel.grantedPermissions.observe(viewLifecycleOwner) { granted ->
            permissionMap.forEach { (healthPermission, switchPreference) ->
                switchPreference.isChecked = healthPermission in granted
            }
        }

        viewModel.revokeAllPermissionsState.observe(viewLifecycleOwner) { state ->
            when (state) {
                is AppPermissionViewModel.RevokeAllState.Loading -> {
                    showLoadingDialog()
                }
                else -> {
                    dismissLoadingDialog()
                }
            }
        }

        migrationViewModel.migrationState.observe(viewLifecycleOwner) { migrationState ->
            when (migrationState) {
                is MigrationViewModel.MigrationFragmentState.WithData -> {
                    maybeShowMigrationDialog(migrationState.migrationState)
                }
                else -> {
                    // do nothing
                }
            }
        }

        setupHeader()
    }

    private fun maybeShowMigrationDialog(migrationState: MigrationState) {
        when (migrationState) {
            MigrationState.IN_PROGRESS -> {
                showMigrationInProgressDialog(
                    requireContext(),
                    getString(
                        R.string.migration_in_progress_permissions_dialog_content,
                        viewModel.appInfo.value?.appName)) { _, _ ->
                        requireActivity().finish()
                    }
            }
            MigrationState.ALLOWED_PAUSED,
            MigrationState.ALLOWED_NOT_STARTED,
            MigrationState.APP_UPGRADE_REQUIRED,
            MigrationState.MODULE_UPGRADE_REQUIRED -> {
                showMigrationPendingDialog(
                    requireContext(),
                    getString(
                        R.string.migration_pending_permissions_dialog_content,
                        viewModel.appInfo.value?.appName),
                        positiveButtonAction = null,
                        negativeButtonAction = { _, _ ->
                            requireContext().startActivity(Intent(MIGRATION_ACTIVITY_INTENT))
                            requireActivity().finish()
                        })
            }
            MigrationState.COMPLETE -> {
                maybeShowWhatsNewDialog(requireContext())
            }
            else -> {
                // Show nothing
            }
        }
    }

    private fun setupHeader() {
        viewModel.appInfo.observe(viewLifecycleOwner) { appMetadata ->
            packageName = appMetadata.packageName
            setupAllowAllPreference(appMetadata.appName)
            setupFooter(appMetadata.appName)
            header?.apply {
                setIcon(appMetadata.icon)
                setTitle(appMetadata.appName)
            }
        }
    }

    private fun setupFooter(appName: String) {
        viewModel.atLeastOnePermissionGranted.observe(viewLifecycleOwner) { isAtLeastOneGranted ->
            updateFooter(isAtLeastOneGranted, appName)
        }
    }

    private fun setupAllowAllPreference(appName: String) {
        allowAllPreference?.addOnSwitchChangeListener { preference, grantAll ->
            if (preference.isPressed) {
                if (grantAll) {
                    viewModel.grantAllPermissions(packageName)
                } else {
                    showRevokeAllPermissions(appName)
                }
            }
        }
        viewModel.allAppPermissionsGranted.observe(viewLifecycleOwner) { isAllGranted ->
            allowAllPreference?.isChecked = isAllGranted
        }
    }

    private fun showRevokeAllPermissions(appName: String) {
        childFragmentManager.setFragmentResultListener(
            DisconnectDialogFragment.DISCONNECT_CANCELED_EVENT, this) { _, _ ->
                allowAllPreference?.isChecked = true
            }

        childFragmentManager.setFragmentResultListener(
            DisconnectDialogFragment.DISCONNECT_ALL_EVENT, this) { _, bundle ->
                if (!viewModel.revokeAllPermissions(packageName)) {
                    Toast.makeText(requireContext(), R.string.default_error, Toast.LENGTH_SHORT)
                        .show()
                }
                if (bundle.containsKey(DisconnectDialogFragment.KEY_DELETE_DATA) &&
                    bundle.getBoolean(DisconnectDialogFragment.KEY_DELETE_DATA)) {
                    viewModel.deleteAppData(packageName, appName)
                }
            }

        DisconnectDialogFragment(appName = appName, enableDeleteData = false)
            .show(childFragmentManager, DisconnectDialogFragment.TAG)
    }

    private fun updatePermissions(permissions: List<HealthPermission>) {
        readPermissionCategory?.removeAll()
        writePermissionCategory?.removeAll()
        permissionMap.clear()

        permissions
            .sortedBy {
                requireContext()
                    .getString(fromPermissionType(it.healthPermissionType).uppercaseLabel)
            }
            .forEach { permission ->
                val category =
                    if (permission.permissionsAccessType == PermissionsAccessType.READ) {
                        readPermissionCategory
                    } else {
                        writePermissionCategory
                    }
                val switchPreference =
                    HealthSwitchPreference(requireContext()).also {
                        val healthCategory =
                            fromHealthPermissionType(permission.healthPermissionType)
                        it.icon = healthCategory.icon(requireContext())
                        it.setTitle(
                            fromPermissionType(permission.healthPermissionType).uppercaseLabel)
                        it.logNameActive = PermissionsElement.PERMISSION_SWITCH
                        it.logNameInactive = PermissionsElement.PERMISSION_SWITCH
                        it.setOnPreferenceChangeListener { _, newValue ->
                            val checked = newValue as Boolean
                            val permissionUpdated =
                                viewModel.updatePermission(packageName, permission, checked)
                            if (!permissionUpdated) {
                                Toast.makeText(
                                        requireContext(),
                                        R.string.default_error,
                                        Toast.LENGTH_SHORT)
                                    .show()
                            }
                            permissionUpdated
                        }
                    }
                permissionMap[permission] = switchPreference
                category?.addPreference(switchPreference)
            }
    }

    private fun updateFooter(isAtLeastOneGranted: Boolean, appName: String) {
        var title = getString(R.string.manage_permissions_rationale, appName)

        if (isAtLeastOneGranted) {
            val dataAccessDate = viewModel.loadAccessDate(packageName)
            dataAccessDate?.let {
                val formattedDate = dateFormatter.formatLongDate(dataAccessDate)
                title =
                    getString(R.string.manage_permissions_time_frame, appName, formattedDate) +
                        PARAGRAPH_SEPARATOR +
                        title
            }
        }

        mFooter?.title = title
        if (healthPermissionReader.isRationalIntentDeclared(packageName)) {
            mFooter?.setLearnMoreText(getString(R.string.manage_permissions_learn_more))
            mFooter?.setLearnMoreAction {
                val startRationaleIntent =
                    healthPermissionReader.getApplicationRationaleIntent(packageName)
                startActivity(startRationaleIntent)
            }
        }
    }
}
