package com.android.healthconnect.controller.migration

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.viewModels
import androidx.navigation.fragment.findNavController
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.migration.api.MigrationState
import com.android.healthconnect.controller.shared.preference.HealthPreferenceFragment
import dagger.hilt.android.AndroidEntryPoint

@AndroidEntryPoint(HealthPreferenceFragment::class)
class MigrationNavigationFragment : Hilt_MigrationNavigationFragment() {

    private val migrationViewModel: MigrationViewModel by viewModels()
    private lateinit var sharedPreference: SharedPreferences

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View? {
        return inflater.inflate(R.layout.fragment_migration_navigation, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        sharedPreference =
            requireActivity().getSharedPreferences("USER_ACTIVITY_TRACKER", Context.MODE_PRIVATE)

        migrationViewModel.migrationState.observe(viewLifecycleOwner) { migrationState ->
            when (migrationState) {
                is MigrationViewModel.MigrationFragmentState.Loading -> {
                    setLoading(true)
                }
                is MigrationViewModel.MigrationFragmentState.WithData -> {
                    setLoading(false)
                    updateFragment(migrationState.migrationState)
                }
                is MigrationViewModel.MigrationFragmentState.Error -> {
                    setError(true)
                }
            }
        }
    }

    private fun updateFragment(migrationState: MigrationState) {
        when (migrationState) {
            MigrationState.ALLOWED_NOT_STARTED,
            MigrationState.ALLOWED_PAUSED -> {
                showMigrationPausedFragment()
            }
            MigrationState.APP_UPGRADE_REQUIRED -> {
                showAppUpdateRequiredFragment()
            }
            MigrationState.MODULE_UPGRADE_REQUIRED -> {
                showModuleUpdateRequiredFragment()
            }
            MigrationState.IN_PROGRESS -> {
                showInProgressFragment()
            }
            MigrationState.COMPLETE_IDLE,
            MigrationState.COMPLETE -> {
                markMigrationComplete()
                navigateToHomeFragment()
            }
            else -> {
                navigateToHomeFragment()
            }
        }
    }

    private fun showInProgressFragment() {
        findNavController()
            .navigate(R.id.action_migrationNavigationFragment_to_migrationInProgressFragment)
    }

    private fun showAppUpdateRequiredFragment() {
        findNavController()
            .navigate(R.id.action_migrationNavigationFragment_to_migrationAppUpdateNeededFragment)
    }

    private fun showModuleUpdateRequiredFragment() {
        findNavController()
            .navigate(
                R.id.action_migrationNavigationFragment_to_migrationModuleUpdateNeededFragment)
    }

    private fun showMigrationPausedFragment() {
        findNavController()
            .navigate(R.id.action_migrationNavigationFragment_to_migrationPausedFragment)
    }

    private fun navigateToHomeFragment() {
        findNavController().navigate(R.id.action_migrationNavigationFragment_to_homeFragment)
    }

    private fun markMigrationComplete() {
        sharedPreference.edit().apply {
            putBoolean(MigrationActivity.MIGRATION_COMPLETE_KEY, true)
            apply()
        }
    }
}
