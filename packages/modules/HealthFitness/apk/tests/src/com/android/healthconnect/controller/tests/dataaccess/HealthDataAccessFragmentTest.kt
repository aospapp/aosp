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
package com.android.healthconnect.controller.tests.dataaccess

import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.dataaccess.DataAccessAppState
import com.android.healthconnect.controller.dataaccess.HealthDataAccessFragment
import com.android.healthconnect.controller.dataaccess.HealthDataAccessViewModel
import com.android.healthconnect.controller.dataaccess.HealthDataAccessViewModel.DataAccessScreenState
import com.android.healthconnect.controller.dataaccess.HealthDataAccessViewModel.DataAccessScreenState.WithData
import com.android.healthconnect.controller.permissions.data.HealthPermissionType
import com.android.healthconnect.controller.permissiontypes.HealthPermissionTypesFragment.Companion.PERMISSION_TYPE_KEY
import com.android.healthconnect.controller.shared.app.AppMetadata
import com.android.healthconnect.controller.tests.utils.launchFragment
import com.android.healthconnect.controller.tests.utils.whenever
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.hamcrest.Matchers.not
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

@HiltAndroidTest
class HealthDataAccessFragmentTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @BindValue
    val viewModel: HealthDataAccessViewModel = Mockito.mock(HealthDataAccessViewModel::class.java)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun dataAccessFragment_noSections_noneDisplayed() {
        whenever(viewModel.appMetadataMap).then {
            MutableLiveData<DataAccessScreenState>(WithData(emptyMap()))
        }
        launchFragment<HealthDataAccessFragment>(distanceBundle())

        onView(withText("Can read distance")).check(doesNotExist())
        onView(withText("Can write distance")).check(doesNotExist())
        onView(withText("Inactive apps")).check(doesNotExist())
        onView(
                withText(
                    "These apps can no longer write distance, but still have data stored in Health\u00A0Connect"))
            .check(doesNotExist())
        onView(withText("Manage data")).check(matches(isDisplayed()))
        onView(withText("See all entries")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Delete this data")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun dataAccessFragment_readSection_isDisplayed() {
        val map =
            mapOf(
                DataAccessAppState.Read to listOf(AppMetadata("package1", "appName1", null)),
                DataAccessAppState.Write to emptyList(),
                DataAccessAppState.Inactive to emptyList())
        whenever(viewModel.appMetadataMap).then {
            MutableLiveData<DataAccessScreenState>(WithData(map))
        }
        launchFragment<HealthDataAccessFragment>(distanceBundle())

        onView(withText("Can read distance")).check(matches(isDisplayed()))
        onView(withText("Can write distance")).check(doesNotExist())
        onView(withText("Inactive apps")).check(doesNotExist())
        onView(
                withText(
                    "These apps can no longer write distance, but still have data stored in Health\u00A0Connect"))
            .check(doesNotExist())
        onView(withText("Manage data")).check(matches(isDisplayed()))
        onView(withText("See all entries")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Delete this data")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun dataAccessFragment_readAndWriteSections_isDisplayed() {
        val map =
            mapOf(
                DataAccessAppState.Read to listOf(AppMetadata("package1", "appName1", null)),
                DataAccessAppState.Write to listOf(AppMetadata("package1", "appName1", null)),
                DataAccessAppState.Inactive to emptyList())
        whenever(viewModel.appMetadataMap).then {
            MutableLiveData<DataAccessScreenState>(WithData(map))
        }
        launchFragment<HealthDataAccessFragment>(distanceBundle())

        onView(withText("Can read distance")).check(matches(isDisplayed()))
        onView(withText("Can write distance")).check(matches(isDisplayed()))
        onView(withText("Inactive apps")).check(doesNotExist())
        onView(
                withText(
                    "These apps can no longer write distance, but still have data stored in Health\u00A0Connect"))
            .check(doesNotExist())
        onView(withText("Manage data")).check(matches(isDisplayed()))
        onView(withText("See all entries")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Delete this data")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun dataAccessFragment_inactiveSection_isDisplayed() {
        val map =
            mapOf(
                DataAccessAppState.Read to emptyList(),
                DataAccessAppState.Write to emptyList(),
                DataAccessAppState.Inactive to listOf(AppMetadata("package1", "appName1", null)))
        whenever(viewModel.appMetadataMap).then {
            MutableLiveData<DataAccessScreenState>(WithData(map))
        }
        launchFragment<HealthDataAccessFragment>(distanceBundle())

        onView(withText("Can read distance")).check(doesNotExist())
        onView(withText("Can write distance")).check(doesNotExist())
        onView(withText("Inactive apps")).check(matches(isDisplayed()))
        onView(
                withText(
                    "These apps can no longer write distance, but still have data stored in Health\u00A0Connect"))
            .check(matches(isDisplayed()))
        onView(withText("Manage data")).check(matches(isDisplayed()))
        onView(withText("See all entries")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Delete this data")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun dataAccessFragment_loadingState_showsLoading() {
        whenever(viewModel.appMetadataMap).then {
            MutableLiveData<DataAccessScreenState>(DataAccessScreenState.Loading)
        }
        launchFragment<HealthDataAccessFragment>(distanceBundle())
        onView(withId(R.id.progress_indicator)).check(matches(isDisplayed()))
    }

    @Test
    fun dataAccessFragment_withData_hidesLoading() {
        whenever(viewModel.appMetadataMap).then {
            MutableLiveData<DataAccessScreenState>(WithData(emptyMap()))
        }
        launchFragment<HealthDataAccessFragment>(distanceBundle())
        onView(withId(R.id.progress_indicator)).check(matches(not(isDisplayed())))
    }

    @Test
    fun dataAccessFragment_withError_showError() {
        whenever(viewModel.appMetadataMap).then {
            MutableLiveData<DataAccessScreenState>(DataAccessScreenState.Error)
        }
        launchFragment<HealthDataAccessFragment>(distanceBundle())
        onView(withId(R.id.progress_indicator)).check(matches(not(isDisplayed())))
        onView(withId(R.id.error_view)).check(matches(isDisplayed()))
    }

    private fun distanceBundle(): Bundle {
        val bundle = Bundle()
        bundle.putSerializable(PERMISSION_TYPE_KEY, HealthPermissionType.DISTANCE)
        return bundle
    }
}
