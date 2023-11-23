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
package com.android.healthconnect.controller.tests.permissions.connectedapps

import android.content.Intent.*
import androidx.core.os.bundleOf
import androidx.lifecycle.MutableLiveData
import androidx.preference.PreferenceCategory
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isChecked
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.permissions.app.AppPermissionViewModel
import com.android.healthconnect.controller.permissions.app.AppPermissionViewModel.RevokeAllState.NotStarted
import com.android.healthconnect.controller.permissions.app.ConnectedAppFragment
import com.android.healthconnect.controller.permissions.app.HealthPermissionStatus
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.permissions.data.HealthPermissionType.DISTANCE
import com.android.healthconnect.controller.permissions.data.HealthPermissionType.EXERCISE
import com.android.healthconnect.controller.permissions.data.PermissionsAccessType.READ
import com.android.healthconnect.controller.permissions.data.PermissionsAccessType.WRITE
import com.android.healthconnect.controller.permissions.shared.Constants.EXTRA_APP_NAME
import com.android.healthconnect.controller.shared.app.AppMetadata
import com.android.healthconnect.controller.tests.TestActivity
import com.android.healthconnect.controller.tests.utils.TEST_APP_NAME
import com.android.healthconnect.controller.tests.utils.TEST_APP_PACKAGE_NAME
import com.android.healthconnect.controller.tests.utils.launchFragment
import com.android.healthconnect.controller.tests.utils.safeEq
import com.android.healthconnect.controller.tests.utils.setLocale
import com.android.healthconnect.controller.tests.utils.whenever
import com.android.settingslib.widget.MainSwitchPreference
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Matchers.*
import org.mockito.Mockito
import org.mockito.Mockito.*

@HiltAndroidTest
class ConnectedAppFragmentTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @BindValue val viewModel: AppPermissionViewModel = mock(AppPermissionViewModel::class.java)

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().context
        context.setLocale(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("UTC")))
        hiltRule.inject()

        whenever(viewModel.revokeAllPermissionsState).then { MutableLiveData(NotStarted) }
        whenever(viewModel.allAppPermissionsGranted).then { MutableLiveData(false) }
        whenever(viewModel.atLeastOnePermissionGranted).then { MutableLiveData(true) }
        whenever(viewModel.grantedPermissions).then {
            MutableLiveData(emptySet<HealthPermission>())
        }
        val accessDate = Instant.parse("2022-10-20T18:40:13.00Z")
        whenever(viewModel.loadAccessDate(Mockito.anyString())).thenReturn(accessDate)

        whenever(viewModel.appInfo).then {
            MutableLiveData(
                AppMetadata(
                    TEST_APP_PACKAGE_NAME,
                    TEST_APP_NAME,
                    context.getDrawable(R.drawable.health_connect_logo)))
        }
    }

    @Test
    fun test_noPermissions() {
        whenever(viewModel.appPermissions).then {
            MutableLiveData(listOf<HealthPermissionStatus>())
        }

        val scenario =
            launchFragment<ConnectedAppFragment>(
                bundleOf(
                    EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))

        scenario.onActivity { activity: TestActivity ->
            val fragment =
                activity.supportFragmentManager.findFragmentById(android.R.id.content)
                    as ConnectedAppFragment
            val readCategory =
                fragment.preferenceScreen.findPreference("read_permission_category")
                    as PreferenceCategory?
            val writeCategory =
                fragment.preferenceScreen.findPreference("write_permission_category")
                    as PreferenceCategory?
            assertThat(readCategory?.preferenceCount).isEqualTo(0)
            assertThat(writeCategory?.preferenceCount).isEqualTo(0)
        }
    }

    @Test
    fun test_readPermission() {
        val permission = HealthPermission(DISTANCE, READ)
        whenever(viewModel.appPermissions).then { MutableLiveData(listOf(permission)) }
        whenever(viewModel.grantedPermissions).then { MutableLiveData(setOf(permission)) }

        val scenario =
            launchFragment<ConnectedAppFragment>(
                bundleOf(
                    EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))

        scenario.onActivity { activity: TestActivity ->
            val fragment =
                activity.supportFragmentManager.findFragmentById(android.R.id.content)
                    as ConnectedAppFragment
            val readCategory =
                fragment.preferenceScreen.findPreference("read_permission_category")
                    as PreferenceCategory?
            val writeCategory =
                fragment.preferenceScreen.findPreference("write_permission_category")
                    as PreferenceCategory?
            assertThat(readCategory?.preferenceCount).isEqualTo(1)
            assertThat(writeCategory?.preferenceCount).isEqualTo(0)
        }
        onView(withText("Distance")).check(matches(isDisplayed()))
    }

    @Test
    fun test_writePermission() {
        val permission = HealthPermission(EXERCISE, WRITE)
        whenever(viewModel.appPermissions).then { MutableLiveData(listOf(permission)) }
        whenever(viewModel.grantedPermissions).then { MutableLiveData(setOf(permission)) }

        val scenario =
            launchFragment<ConnectedAppFragment>(
                bundleOf(
                    EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))

        scenario.onActivity { activity: TestActivity ->
            val fragment =
                activity.supportFragmentManager.findFragmentById(android.R.id.content)
                    as ConnectedAppFragment
            val readCategory =
                fragment.preferenceScreen.findPreference("read_permission_category")
                    as PreferenceCategory?
            val writeCategory =
                fragment.preferenceScreen.findPreference("write_permission_category")
                    as PreferenceCategory?
            assertThat(readCategory?.preferenceCount).isEqualTo(0)
            assertThat(writeCategory?.preferenceCount).isEqualTo(1)
        }
        onView(withText("Exercise")).check(matches(isDisplayed()))
    }

    @Test
    fun test_readAndWritePermission() {
        val writePermission = HealthPermission(EXERCISE, WRITE)
        val readPermission = HealthPermission(DISTANCE, READ)
        whenever(viewModel.appPermissions).then {
            MutableLiveData(listOf(writePermission, readPermission))
        }
        whenever(viewModel.grantedPermissions).then { MutableLiveData(setOf(writePermission)) }

        val scenario =
            launchFragment<ConnectedAppFragment>(
                bundleOf(
                    EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))

        scenario.onActivity { activity: TestActivity ->
            val fragment =
                activity.supportFragmentManager.findFragmentById(android.R.id.content)
                    as ConnectedAppFragment
            val readCategory =
                fragment.preferenceScreen.findPreference("read_permission_category")
                    as PreferenceCategory?
            val writeCategory =
                fragment.preferenceScreen.findPreference("write_permission_category")
                    as PreferenceCategory?
            assertThat(readCategory?.preferenceCount).isEqualTo(1)
            assertThat(writeCategory?.preferenceCount).isEqualTo(1)
        }
        onView(withText("Exercise")).check(matches(isDisplayed()))
        onView(withText("Distance")).check(matches(isDisplayed()))
    }

    @Test
    fun test_allowAllToggleOn_whenAllPermissionsOn() {
        val writePermission = HealthPermission(EXERCISE, WRITE)
        val readPermission = HealthPermission(DISTANCE, READ)
        whenever(viewModel.appPermissions).then {
            MutableLiveData(listOf(writePermission, readPermission))
        }
        whenever(viewModel.grantedPermissions).then {
            MutableLiveData(setOf(writePermission, readPermission))
        }
        whenever(viewModel.allAppPermissionsGranted).then { MutableLiveData(true) }

        val scenario =
            launchFragment<ConnectedAppFragment>(
                bundleOf(
                    EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))

        scenario.onActivity { activity: TestActivity ->
            val fragment =
                activity.supportFragmentManager.findFragmentById(android.R.id.content)
                    as ConnectedAppFragment
            val mainSwitchPreference =
                fragment.preferenceScreen.findPreference("allow_all_preference")
                    as MainSwitchPreference?

            assertThat(mainSwitchPreference?.isChecked).isTrue()
        }
    }

    @Test
    fun test_allowAllToggleOff_whenAtLeastOnePermissionOff() {
        val writePermission = HealthPermission(EXERCISE, WRITE)
        val readPermission = HealthPermission(DISTANCE, READ)
        whenever(viewModel.appPermissions).then {
            MutableLiveData(listOf(writePermission, readPermission))
        }
        whenever(viewModel.allAppPermissionsGranted).then { MutableLiveData(false) }

        val scenario =
            launchFragment<ConnectedAppFragment>(
                bundleOf(
                    EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))

        scenario.onActivity { activity: TestActivity ->
            val fragment =
                activity.supportFragmentManager.findFragmentById(android.R.id.content)
                    as ConnectedAppFragment

            val mainSwitchPreference =
                fragment.preferenceScreen.findPreference("allow_all_preference")
                    as MainSwitchPreference?

            assertThat(mainSwitchPreference?.isChecked).isFalse()
        }
    }

    @Test
    fun allowAll_toggleOff_showsDisconnectDialog() {
        val writePermission = HealthPermission(EXERCISE, WRITE)
        val readPermission = HealthPermission(DISTANCE, READ)
        whenever(viewModel.appPermissions).then {
            MutableLiveData(listOf(writePermission, readPermission))
        }
        whenever(viewModel.allAppPermissionsGranted).then { MutableLiveData(true) }
        launchFragment<ConnectedAppFragment>(
            bundleOf(EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))
        onView(withText("Allow all")).perform(click())

        onView(withText("Remove all permissions?")).check(matches(isDisplayed()))
    }

    @Test
    fun allowAll_toggleOff_onDialogRemoveAllClicked_disconnectAllPermissions() {
        val writePermission = HealthPermission(EXERCISE, WRITE)
        val readPermission = HealthPermission(DISTANCE, READ)
        whenever(viewModel.appPermissions).then {
            MutableLiveData(listOf(writePermission, readPermission))
        }
        whenever(viewModel.grantedPermissions).then {
            MutableLiveData(setOf(writePermission, readPermission))
        }
        whenever(viewModel.allAppPermissionsGranted).then { MutableLiveData(true) }
        launchFragment<ConnectedAppFragment>(
            bundleOf(EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))
        onView(withText("Allow all")).perform(click())

        onView(withText("Remove all")).perform(click())

        onView(withText("Exercise")).check(matches(not(isChecked())))
        onView(withText("Distance")).check(matches(not(isChecked())))
    }

    @Test
    fun allowAll_toggleOff_deleteDataSelected_onDialogRemoveAllClicked_deleteIsCalled() {
        val writePermission = HealthPermission(EXERCISE, WRITE)
        val readPermission = HealthPermission(DISTANCE, READ)
        whenever(viewModel.appPermissions).then {
            MutableLiveData(listOf(writePermission, readPermission))
        }
        whenever(viewModel.grantedPermissions).then {
            MutableLiveData(setOf(writePermission, readPermission))
        }
        whenever(viewModel.allAppPermissionsGranted).then { MutableLiveData(true) }
        launchFragment<ConnectedAppFragment>(
            bundleOf(EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))
        onView(withText("Allow all")).perform(click())

        onView(withId(R.id.dialog_checkbox)).perform(click())
        onView(withText("Remove all")).perform(click())

        verify(viewModel).deleteAppData(safeEq(TEST_APP_PACKAGE_NAME), safeEq(TEST_APP_NAME))
    }

    @Test
    fun test_footerIsDisplayed() {
        val permission = HealthPermission(DISTANCE, READ)
        whenever(viewModel.appPermissions).then { MutableLiveData(listOf(permission)) }

        launchFragment<ConnectedAppFragment>(
            bundleOf(EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME, EXTRA_APP_NAME to TEST_APP_NAME))

        onView(
                withText(
                    "$TEST_APP_NAME can read data added after October 20, 2022" +
                        "\n\n" +
                        "To manage other Android permissions this app can " +
                        "access, go to Settings > Apps" +
                        "\n\n" +
                        "Data you share with $TEST_APP_NAME is covered by their privacy policy"))
            .perform(scrollTo())
            .check(matches(isDisplayed()))
        onView(withText("Read privacy policy")).perform(scrollTo()).check(matches(isDisplayed()))
    }
}
