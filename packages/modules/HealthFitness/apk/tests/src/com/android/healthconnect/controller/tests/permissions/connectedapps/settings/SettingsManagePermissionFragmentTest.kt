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
package com.android.healthconnect.controller.tests.permissions.connectedapps.settings

import android.health.connect.HealthConnectManager
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.permissions.connectedapps.ConnectedAppsViewModel
import com.android.healthconnect.controller.permissions.connectedapps.ConnectedAppsViewModel.DisconnectAllState.NotStarted
import com.android.healthconnect.controller.permissions.connectedapps.SettingsManagePermissionFragment
import com.android.healthconnect.controller.shared.app.AppMetadata
import com.android.healthconnect.controller.shared.app.ConnectedAppMetadata
import com.android.healthconnect.controller.shared.app.ConnectedAppStatus.ALLOWED
import com.android.healthconnect.controller.shared.app.ConnectedAppStatus.DENIED
import com.android.healthconnect.controller.shared.app.ConnectedAppStatus.INACTIVE
import com.android.healthconnect.controller.tests.utils.NOW
import com.android.healthconnect.controller.tests.utils.TEST_APP
import com.android.healthconnect.controller.tests.utils.TEST_APP_2
import com.android.healthconnect.controller.tests.utils.TEST_APP_NAME
import com.android.healthconnect.controller.tests.utils.TEST_APP_NAME_2
import com.android.healthconnect.controller.tests.utils.launchFragment
import com.android.healthconnect.controller.tests.utils.whenever
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

@HiltAndroidTest
class SettingsManagePermissionFragmentTest {
    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var manager: HealthConnectManager

    @BindValue
    val viewModel: ConnectedAppsViewModel = Mockito.mock(ConnectedAppsViewModel::class.java)

    @Before
    fun setup() {
        hiltRule.inject()
        whenever(viewModel.disconnectAllState).then { MutableLiveData(NotStarted) }
    }

    @Test
    fun test_displaysSections() {
        whenever(viewModel.connectedApps).then { MutableLiveData(listOf<AppMetadata>()) }

        launchFragment<SettingsManagePermissionFragment>(Bundle())

        onView(withText("Allowed access")).check(matches(isDisplayed()))
        onView(withText("No apps allowed")).check(matches(isDisplayed()))
        onView(withText("Not allowed access")).check(matches(isDisplayed()))
        onView(withText("No apps denied")).check(matches(isDisplayed()))
    }

    @Test
    fun test_allowedApps() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = ALLOWED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<SettingsManagePermissionFragment>(Bundle())

        onView(withText(TEST_APP_NAME)).check(matches(isDisplayed()))
        onView(withText("No apps allowed")).check(doesNotExist())
    }

    @Test
    fun test_deniedApps() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = DENIED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<SettingsManagePermissionFragment>(Bundle())

        onView(withText(TEST_APP_NAME)).check(matches(isDisplayed()))
        onView(withText("No apps denied")).check(doesNotExist())
    }

    @Test
    fun test_accessedHealthData_showsRecentAccessSummary() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = ALLOWED, NOW))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<SettingsManagePermissionFragment>(Bundle())

        onView(withText("Accessed in past 24 hours")).check(matches(isDisplayed()))
    }

    @Test
    fun test_inactiveApp_doesNotShowInactiveApps() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = INACTIVE))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<SettingsManagePermissionFragment>(Bundle())

        onView(withText(TEST_APP_NAME)).check(doesNotExist())
        onView(withText(R.string.inactive_apps)).check(doesNotExist())
    }

    @Test
    fun test_all() {
        val connectApp =
            listOf(
                ConnectedAppMetadata(TEST_APP, status = DENIED),
                ConnectedAppMetadata(TEST_APP_2, status = ALLOWED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<SettingsManagePermissionFragment>(Bundle())

        onView(withText(TEST_APP_NAME)).check(matches(isDisplayed()))
        onView(withText(TEST_APP_NAME_2)).check(matches(isDisplayed()))
        onView(withText("No apps allowed")).check(doesNotExist())
        onView(withText("No apps denied")).check(doesNotExist())
    }
}
