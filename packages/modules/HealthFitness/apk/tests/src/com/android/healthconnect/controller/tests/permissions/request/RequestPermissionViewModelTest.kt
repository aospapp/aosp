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

package com.android.healthconnect.controller.tests.permissions.request

import android.health.connect.HealthPermissions.READ_HEART_RATE
import android.health.connect.HealthPermissions.READ_STEPS
import com.android.healthconnect.controller.permissions.api.GetGrantedHealthPermissionsUseCase
import com.android.healthconnect.controller.permissions.api.GrantHealthPermissionUseCase
import com.android.healthconnect.controller.permissions.api.HealthPermissionManager
import com.android.healthconnect.controller.permissions.api.RevokeHealthPermissionUseCase
import com.android.healthconnect.controller.permissions.data.HealthPermission.Companion.fromPermissionString
import com.android.healthconnect.controller.permissions.request.RequestPermissionViewModel
import com.android.healthconnect.controller.service.HealthPermissionManagerModule
import com.android.healthconnect.controller.shared.app.AppInfoReader
import com.android.healthconnect.controller.tests.utils.InstantTaskExecutorRule
import com.android.healthconnect.controller.tests.utils.TEST_APP_NAME
import com.android.healthconnect.controller.tests.utils.TEST_APP_PACKAGE_NAME
import com.android.healthconnect.controller.tests.utils.di.FakeHealthPermissionManager
import com.android.healthconnect.controller.tests.utils.getOrAwaitValue
import com.google.common.truth.Truth.*
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test

@ExperimentalCoroutinesApi
@UninstallModules(HealthPermissionManagerModule::class)
@HiltAndroidTest
class RequestPermissionViewModelTest {

    companion object {
        private val permissions = arrayOf(READ_STEPS, READ_HEART_RATE)
    }

    @get:Rule val hiltRule = HiltAndroidRule(this)
    @get:Rule val instantTaskExecutorRule = InstantTaskExecutorRule()

    @BindValue val permissionManager: HealthPermissionManager = FakeHealthPermissionManager()

    @Inject lateinit var appInfoReader: AppInfoReader
    @Inject lateinit var grantHealthPermissionUseCase: GrantHealthPermissionUseCase
    @Inject lateinit var revokeHealthPermissionUseCase: RevokeHealthPermissionUseCase
    @Inject lateinit var getGrantHealthPermissionUseCase: GetGrantedHealthPermissionsUseCase

    lateinit var viewModel: RequestPermissionViewModel

    @Before
    fun setup() {
        hiltRule.inject()
        permissionManager.revokeAllHealthPermissions(TEST_APP_PACKAGE_NAME)
        viewModel =
            RequestPermissionViewModel(
                appInfoReader,
                grantHealthPermissionUseCase,
                revokeHealthPermissionUseCase,
                getGrantHealthPermissionUseCase)
        viewModel.init(TEST_APP_PACKAGE_NAME, permissions)
    }

    @Test
    fun init_initAppInfo_initPermissions() = runTest {
        val appMetaData = viewModel.appMetadata.getOrAwaitValue()
        assertThat(appMetaData.appName).isEqualTo(TEST_APP_NAME)
        assertThat(appMetaData.packageName).isEqualTo(TEST_APP_PACKAGE_NAME)
    }

    @Test
    fun init_initPermissions() = runTest {
        assertThat(viewModel.permissionsList.getOrAwaitValue())
            .isEqualTo(
                listOf(fromPermissionString(READ_STEPS), fromPermissionString(READ_HEART_RATE)))
    }

    @Test
    fun isPermissionGranted_permissionGranted_returnsTrue() = runTest {
        val readStepsPermission = fromPermissionString(READ_STEPS)
        viewModel.updatePermission(readStepsPermission, grant = true)

        assertThat(viewModel.isPermissionGranted(readStepsPermission)).isTrue()
    }

    @Test
    fun isPermissionGranted_permissionRevoked_returnsFalse() = runTest {
        val readStepsPermission = fromPermissionString(READ_STEPS)
        viewModel.updatePermission(readStepsPermission, grant = false)

        assertThat(viewModel.isPermissionGranted(fromPermissionString(READ_STEPS))).isFalse()
    }

    @Test
    fun updatePermission_grant_updatesGrantedPermissions() {
        val readStepsPermission = fromPermissionString(READ_STEPS)
        viewModel.updatePermission(readStepsPermission, grant = true)

        assertThat(viewModel.grantedPermissions.getOrAwaitValue()).contains(readStepsPermission)
    }

    @Test
    fun updatePermission_revoke_updatesGrantedPermissions() {
        val readStepsPermission = fromPermissionString(READ_STEPS)
        viewModel.updatePermission(readStepsPermission, grant = false)

        assertThat(viewModel.grantedPermissions.getOrAwaitValue())
            .doesNotContain(readStepsPermission)
    }

    @Test
    fun updatePermissions_grant_updatesGrantedPermissions() {
        viewModel.updatePermissions(grant = true)

        assertThat(viewModel.grantedPermissions.getOrAwaitValue())
            .containsExactly(
                fromPermissionString(READ_STEPS), fromPermissionString(READ_HEART_RATE))
    }

    @Test
    fun updatePermissions_revoke_updatesGrantedPermissions() {
        viewModel.updatePermissions(grant = false)

        assertThat(viewModel.grantedPermissions.getOrAwaitValue()).isEmpty()
    }

    @Test
    fun request_allPermissionsGranted_updatesPermissionState() {
        viewModel.updatePermissions(true)

        viewModel.request(TEST_APP_PACKAGE_NAME)

        assertThat(permissionManager.getGrantedHealthPermissions(TEST_APP_PACKAGE_NAME))
            .containsExactly(READ_STEPS, READ_HEART_RATE)
    }

    @Test
    fun request_allPermissionsRevoked_updatesPermissionState() {
        viewModel.updatePermissions(false)

        viewModel.request(TEST_APP_PACKAGE_NAME)

        assertThat(permissionManager.getGrantedHealthPermissions(TEST_APP_PACKAGE_NAME)).isEmpty()
    }
}
