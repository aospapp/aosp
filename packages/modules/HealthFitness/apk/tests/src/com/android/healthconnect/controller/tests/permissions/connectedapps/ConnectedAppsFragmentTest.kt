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
package com.android.healthconnect.controller.tests.permissions.connectedapps

import android.health.connect.HealthConnectManager
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.recyclerview.widget.RecyclerView
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.contrib.RecyclerViewActions.scrollToLastPosition
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.permissions.connectedapps.ConnectedAppsFragment
import com.android.healthconnect.controller.permissions.connectedapps.ConnectedAppsViewModel
import com.android.healthconnect.controller.permissions.connectedapps.ConnectedAppsViewModel.DisconnectAllState.Loading
import com.android.healthconnect.controller.permissions.connectedapps.ConnectedAppsViewModel.DisconnectAllState.NotStarted
import com.android.healthconnect.controller.permissions.connectedapps.ConnectedAppsViewModel.DisconnectAllState.Updated
import com.android.healthconnect.controller.shared.app.ConnectedAppMetadata
import com.android.healthconnect.controller.shared.app.ConnectedAppStatus.ALLOWED
import com.android.healthconnect.controller.shared.app.ConnectedAppStatus.DENIED
import com.android.healthconnect.controller.shared.app.ConnectedAppStatus.INACTIVE
import com.android.healthconnect.controller.tests.utils.TEST_APP
import com.android.healthconnect.controller.tests.utils.TEST_APP_2
import com.android.healthconnect.controller.tests.utils.TEST_APP_NAME
import com.android.healthconnect.controller.tests.utils.TEST_APP_NAME_2
import com.android.healthconnect.controller.tests.utils.di.FakeDeviceInfoUtils
import com.android.healthconnect.controller.tests.utils.launchFragment
import com.android.healthconnect.controller.tests.utils.whenever
import com.android.healthconnect.controller.utils.DeviceInfoUtils
import com.android.healthconnect.controller.utils.DeviceInfoUtilsModule
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.android.testing.UninstallModules
import javax.inject.Inject
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

@UninstallModules(DeviceInfoUtilsModule::class)
@HiltAndroidTest
class ConnectedAppsFragmentTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @Inject lateinit var manager: HealthConnectManager

    @BindValue
    val viewModel: ConnectedAppsViewModel = Mockito.mock(ConnectedAppsViewModel::class.java)

    @BindValue val deviceInfoUtils: DeviceInfoUtils = FakeDeviceInfoUtils()

    @Before
    fun setup() {
        hiltRule.inject()
        whenever(viewModel.disconnectAllState).then { MutableLiveData(NotStarted) }
    }

    @Test
    fun test_allowedApps() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = ALLOWED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText(TEST_APP_NAME)).check(matches(isDisplayed()))
        onView(withText("No apps allowed")).check(doesNotExist())
    }

    @Test
    fun test_deniedApps() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = DENIED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText(TEST_APP_NAME)).check(matches(isDisplayed()))
        onView(withText("No apps denied")).check(doesNotExist())
    }

    @Test
    fun allowedApps_removeAccessEnabled() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = ALLOWED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText(R.string.disconnect_all_apps)).check(matches(isEnabled()))
    }

    @Test
    fun noAllowedApps_removeAccessDisabled() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = DENIED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText(R.string.disconnect_all_apps)).check(matches(isDisplayed()))
    }

    @Test
    fun showsLoading() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = DENIED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }
        whenever(viewModel.disconnectAllState).then { MutableLiveData(Loading) }

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText(R.string.loading)).check(matches(isDisplayed()))
    }

    @Test
    fun dismissLoading() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = DENIED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }
        whenever(viewModel.disconnectAllState).then { MutableLiveData(Updated) }

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText(R.string.loading)).check(doesNotExist())
    }

    @Test
    fun test_inactiveApp_showsInactiveApps() {
        val connectApp = listOf(ConnectedAppMetadata(TEST_APP, status = INACTIVE))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText(TEST_APP_NAME)).check(matches(isDisplayed()))
        onView(withText(R.string.inactive_apps)).check(matches(isDisplayed()))
    }

    @Test
    fun test_appPermissionsWithNoConnectedApps_isDisplayedCorrectly() {
        val connectApp = listOf<ConnectedAppMetadata>()
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText("You don't currently have any compatible apps installed"))
            .perform(ViewActions.scrollTo())
            .check(matches(isDisplayed()))
        onView(withText("Things to try")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Check for updates")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Make sure installed apps are up-to-date"))
            .perform(scrollTo())
            .check(matches(isDisplayed()))
        onView(withText("See all compatible apps"))
            .perform(scrollTo())
            .check(matches(isDisplayed()))
        onView(withText("Find apps on Google\u00A0Play"))
            .perform(scrollTo())
            .check(matches(isDisplayed()))
        onView(withText("Send feedback")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(
                withText(
                    "Tell us which health & fitness apps you'd like to work with Health\u00A0Connect"))
            .perform(scrollTo())
            .check(matches(isDisplayed()))
        onView(withText("Allowed access")).check(doesNotExist())
        onView(withText("Not allowed access")).check(doesNotExist())
        onView(withText("No apps denied")).check(doesNotExist())
        onView(withText("Settings & help")).check(doesNotExist())
    }

    @Test
    fun test_all() {
        val connectApp =
            listOf(
                ConnectedAppMetadata(TEST_APP, status = DENIED),
                ConnectedAppMetadata(TEST_APP_2, status = ALLOWED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText(TEST_APP_NAME)).check(matches(isDisplayed()))
        onView(withText(TEST_APP_NAME_2)).check(matches(isDisplayed()))
        onView(withText("No apps allowed")).check(doesNotExist())
        onView(withText("No apps denied")).check(doesNotExist())

        onView(withId(androidx.preference.R.id.recycler_view))
            .perform(scrollToLastPosition<RecyclerView.ViewHolder>())
        onView(withText("Inactive apps")).check(doesNotExist())
    }

    @Test
    fun withNoApps_showsEmptyState() {
        whenever(viewModel.connectedApps).then {
            MutableLiveData(emptyList<ConnectedAppMetadata>())
        }
        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText("Send feedback")).check(matches(isDisplayed()))
        onView(withText("See all compatible apps")).check(matches(isDisplayed()))
        onView(withText("Check for updates")).check(matches(isDisplayed()))
    }

    @Test
    fun test_eitherFeedbackOrPlayStoreAvailable_helpAndFeedbackPreferenceShouldBeDisplayed() {
        val connectApp =
            listOf(
                ConnectedAppMetadata(TEST_APP, status = DENIED),
                ConnectedAppMetadata(TEST_APP_2, status = ALLOWED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        (deviceInfoUtils as FakeDeviceInfoUtils).setSendFeedbackAvailability(false)
        deviceInfoUtils.setPlayStoreAvailability(true)

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText("Help & feedback")).check(matches(isDisplayed()))
    }

    @Test
    fun test_bothFeedbackAndPlayStoreNotAvailable_helpAndFeedbackPreferenceShouldNotBeDisplayed() {
        val connectApp =
            listOf(
                ConnectedAppMetadata(TEST_APP, status = DENIED),
                ConnectedAppMetadata(TEST_APP_2, status = ALLOWED))
        whenever(viewModel.connectedApps).then { MutableLiveData(connectApp) }

        (deviceInfoUtils as FakeDeviceInfoUtils).setSendFeedbackAvailability(false)
        deviceInfoUtils.setPlayStoreAvailability(false)

        launchFragment<ConnectedAppsFragment>(Bundle())

        onView(withText("Help & feedback")).check(doesNotExist())
    }
}
