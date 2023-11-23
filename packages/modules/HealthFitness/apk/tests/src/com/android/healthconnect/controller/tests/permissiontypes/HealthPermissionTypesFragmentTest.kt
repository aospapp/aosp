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
package com.android.healthconnect.controller.tests.permissiontypes

import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.action.ViewActions.scrollTo
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.android.healthconnect.controller.categories.HealthDataCategoriesFragment
import com.android.healthconnect.controller.permissions.data.HealthPermissionType
import com.android.healthconnect.controller.permissiontypes.HealthPermissionTypesFragment
import com.android.healthconnect.controller.permissiontypes.HealthPermissionTypesViewModel
import com.android.healthconnect.controller.shared.app.AppMetadata
import com.android.healthconnect.controller.tests.utils.TEST_APP
import com.android.healthconnect.controller.tests.utils.TEST_APP_2
import com.android.healthconnect.controller.tests.utils.TEST_APP_3
import com.android.healthconnect.controller.tests.utils.launchFragment
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

@HiltAndroidTest
class HealthPermissionTypesFragmentTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @BindValue
    val viewModel: HealthPermissionTypesViewModel =
        Mockito.mock(HealthPermissionTypesViewModel::class.java)

    @Before
    fun setup() {
        hiltRule.inject()
    }

    @Test
    fun permissionTypesFragment_isDisplayed() {
        Mockito.`when`(viewModel.permissionTypesData).then {
            MutableLiveData<HealthPermissionTypesViewModel.PermissionTypesState>(
                HealthPermissionTypesViewModel.PermissionTypesState.WithData(
                    listOf(
                        HealthPermissionType.DISTANCE,
                        HealthPermissionType.EXERCISE,
                        HealthPermissionType.STEPS)))
        }
        Mockito.`when`(viewModel.priorityList).then {
            MutableLiveData<HealthPermissionTypesViewModel.PriorityListState>(
                HealthPermissionTypesViewModel.PriorityListState.WithData(
                    listOf(TEST_APP, TEST_APP_2)))
        }
        Mockito.`when`(viewModel.appsWithData).then {
            MutableLiveData<HealthPermissionTypesViewModel.AppsWithDataFragmentState>(
                HealthPermissionTypesViewModel.AppsWithDataFragmentState.WithData(listOf()))
        }
        Mockito.`when`(viewModel.selectedAppFilter).then { MutableLiveData("") }
        Mockito.`when`(viewModel.editedPriorityList).then {
            MutableLiveData<List<AppMetadata>>(listOf(TEST_APP, TEST_APP_2))
        }
        Mockito.`when`(viewModel.categoryLabel).then { MutableLiveData("activity") }
        launchFragment<HealthPermissionTypesFragment>(activityCategoryBundle())

        onView(withText("Active calories burned")).check(doesNotExist())
        onView(withText("Distance")).check(matches(isDisplayed()))
        onView(withText("Elevation gained")).check(doesNotExist())
        onView(withText("Exercise")).check(matches(isDisplayed()))
        onView(withText("Floors climbed")).check(doesNotExist())
        onView(withText("Power")).check(doesNotExist())
        onView(withText("Steps")).check(matches(isDisplayed()))
        onView(withText("Total calories burned")).check(doesNotExist())
        onView(withText("VO2 max")).check(doesNotExist())
        onView(withText("Wheelchair pushes")).check(doesNotExist())
        onView(withText("Manage data")).check(matches(isDisplayed()))
        onView(withText("App priority")).check(matches(isDisplayed()))
        onView(withText("Health Connect test app")).check(matches(isDisplayed()))
        onView(withText("Delete activity data")).check(matches(isDisplayed()))
    }

    @Test
    fun permissionTypesFragment_emptyPriorityList_noPriorityButton() {
        Mockito.`when`(viewModel.permissionTypesData).then {
            MutableLiveData<HealthPermissionTypesViewModel.PermissionTypesState>(
                HealthPermissionTypesViewModel.PermissionTypesState.WithData(
                    listOf(
                        HealthPermissionType.DISTANCE,
                        HealthPermissionType.EXERCISE,
                        HealthPermissionType.STEPS)))
        }
        Mockito.`when`(viewModel.priorityList).then {
            MutableLiveData<HealthPermissionTypesViewModel.PriorityListState>(
                HealthPermissionTypesViewModel.PriorityListState.WithData(listOf()))
        }
        Mockito.`when`(viewModel.appsWithData).then {
            MutableLiveData<HealthPermissionTypesViewModel.AppsWithDataFragmentState>(
                HealthPermissionTypesViewModel.AppsWithDataFragmentState.WithData(listOf()))
        }
        Mockito.`when`(viewModel.selectedAppFilter).then { MutableLiveData("") }
        Mockito.`when`(viewModel.editedPriorityList).then {
            MutableLiveData<List<AppMetadata>>(listOf())
        }
        Mockito.`when`(viewModel.categoryLabel).then { MutableLiveData("activity") }
        launchFragment<HealthPermissionTypesFragment>(activityCategoryBundle())

        onView(withText("Active calories burned")).check(doesNotExist())
        onView(withText("Distance")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Elevation gained")).check(doesNotExist())
        onView(withText("Exercise")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Floors climbed")).check(doesNotExist())
        onView(withText("Power")).check(doesNotExist())
        onView(withText("Steps")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Total calories burned")).check(doesNotExist())
        onView(withText("VO2 max")).check(doesNotExist())
        onView(withText("Wheelchair pushes")).check(doesNotExist())
        onView(withText("Manage data")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("App priority")).check(doesNotExist())
        onView(withText("Health Connect test app")).check(doesNotExist())
        onView(withText("Delete activity data")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun permissionTypesFragment_oneAppInPriorityList_noPriorityButton() {
        Mockito.`when`(viewModel.permissionTypesData).then {
            MutableLiveData<HealthPermissionTypesViewModel.PermissionTypesState>(
                HealthPermissionTypesViewModel.PermissionTypesState.WithData(
                    listOf(
                        HealthPermissionType.DISTANCE,
                        HealthPermissionType.EXERCISE,
                        HealthPermissionType.STEPS)))
        }
        Mockito.`when`(viewModel.priorityList).then {
            MutableLiveData<HealthPermissionTypesViewModel.PriorityListState>(
                HealthPermissionTypesViewModel.PriorityListState.WithData(listOf(TEST_APP)))
        }
        Mockito.`when`(viewModel.appsWithData).then {
            MutableLiveData<HealthPermissionTypesViewModel.AppsWithDataFragmentState>(
                HealthPermissionTypesViewModel.AppsWithDataFragmentState.WithData(listOf()))
        }
        Mockito.`when`(viewModel.selectedAppFilter).then { MutableLiveData("") }
        Mockito.`when`(viewModel.editedPriorityList).then { MutableLiveData(listOf(TEST_APP)) }
        Mockito.`when`(viewModel.categoryLabel).then { MutableLiveData("activity") }
        launchFragment<HealthPermissionTypesFragment>(activityCategoryBundle())

        onView(withText("Active calories burned")).check(doesNotExist())
        onView(withText("Distance")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Elevation gained")).check(doesNotExist())
        onView(withText("Exercise")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Floors climbed")).check(doesNotExist())
        onView(withText("Power")).check(doesNotExist())
        onView(withText("Steps")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("Total calories burned")).check(doesNotExist())
        onView(withText("VO2 max")).check(doesNotExist())
        onView(withText("Wheelchair pushes")).check(doesNotExist())
        onView(withText("Manage data")).perform(scrollTo()).check(matches(isDisplayed()))
        onView(withText("App priority")).check(doesNotExist())
        onView(withText("Health Connect test app")).check(doesNotExist())
        onView(withText("Delete activity data")).perform(scrollTo()).check(matches(isDisplayed()))
    }

    @Test
    fun permissionTypesFragment_priorityListDialog_isDisplayed() {
        Mockito.`when`(viewModel.permissionTypesData).then {
            MutableLiveData<HealthPermissionTypesViewModel.PermissionTypesState>(
                HealthPermissionTypesViewModel.PermissionTypesState.WithData(
                    listOf(
                        HealthPermissionType.DISTANCE,
                        HealthPermissionType.EXERCISE,
                        HealthPermissionType.STEPS)))
        }
        Mockito.`when`(viewModel.priorityList).then {
            MutableLiveData<HealthPermissionTypesViewModel.PriorityListState>(
                HealthPermissionTypesViewModel.PriorityListState.WithData(
                    listOf(TEST_APP, TEST_APP_2)))
        }
        Mockito.`when`(viewModel.appsWithData).then {
            MutableLiveData<HealthPermissionTypesViewModel.AppsWithDataFragmentState>(
                HealthPermissionTypesViewModel.AppsWithDataFragmentState.WithData(listOf()))
        }
        Mockito.`when`(viewModel.selectedAppFilter).then { MutableLiveData("") }
        Mockito.`when`(viewModel.editedPriorityList).then {
            MutableLiveData(listOf(TEST_APP, TEST_APP_2))
        }
        Mockito.`when`(viewModel.categoryLabel).then { MutableLiveData("activity") }
        launchFragment<HealthPermissionTypesFragment>(activityCategoryBundle())

        onView(withText("App priority")).perform(click())
        onView(withText("Set app priority")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(
                withText(
                    "If more than one app adds activity data, Health\u00A0Connect prioritizes " +
                        "the app highest in this list. Drag apps to reorder them."))
            .inRoot(isDialog())
            .check(matches(isDisplayed()))
        onView(withText("0")).inRoot(isDialog()).check(doesNotExist())
        onView(withText("1")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Health Connect test app")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("2")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Health Connect test app 2"))
            .inRoot(isDialog())
            .check(matches(isDisplayed()))
        onView(withText("Save")).inRoot(isDialog()).check(matches(isDisplayed()))
        onView(withText("Cancel")).inRoot(isDialog()).check(matches(isDisplayed()))
    }

    @Test
    fun permissionTypesFragment_withTwoOrMoreContributingApps_appFilters_areDisplayed() {
        Mockito.`when`(viewModel.permissionTypesData).then {
            MutableLiveData<HealthPermissionTypesViewModel.PermissionTypesState>(
                HealthPermissionTypesViewModel.PermissionTypesState.WithData(
                    listOf(
                        HealthPermissionType.DISTANCE,
                        HealthPermissionType.EXERCISE,
                        HealthPermissionType.STEPS)))
        }
        Mockito.`when`(viewModel.priorityList).then {
            MutableLiveData<HealthPermissionTypesViewModel.PriorityListState>(
                HealthPermissionTypesViewModel.PriorityListState.WithData(listOf()))
        }
        Mockito.`when`(viewModel.appsWithData).then {
            MutableLiveData<HealthPermissionTypesViewModel.AppsWithDataFragmentState>(
                HealthPermissionTypesViewModel.AppsWithDataFragmentState.WithData(
                    listOf(TEST_APP, TEST_APP_2)))
        }
        Mockito.`when`(viewModel.selectedAppFilter).then { MutableLiveData("All apps") }
        Mockito.`when`(viewModel.editedPriorityList).then {
            MutableLiveData<List<AppMetadata>>(listOf())
        }
        Mockito.`when`(viewModel.categoryLabel).then { MutableLiveData("activity") }
        launchFragment<HealthPermissionTypesFragment>(activityCategoryBundle())

        onView(withText("All apps")).check(matches(isDisplayed()))
        onView(withText(TEST_APP.appName)).check(matches(isDisplayed()))
        onView(withText(TEST_APP_2.appName)).check(matches(isDisplayed()))
    }

    @Test
    fun permissionTypesFragment_withLessThanTwoContributingApps_appFilters_areNotDisplayed() {
        Mockito.`when`(viewModel.permissionTypesData).then {
            MutableLiveData<HealthPermissionTypesViewModel.PermissionTypesState>(
                HealthPermissionTypesViewModel.PermissionTypesState.WithData(
                    listOf(HealthPermissionType.DISTANCE, HealthPermissionType.EXERCISE)))
        }
        Mockito.`when`(viewModel.priorityList).then {
            MutableLiveData<HealthPermissionTypesViewModel.PriorityListState>(
                HealthPermissionTypesViewModel.PriorityListState.WithData(listOf()))
        }
        Mockito.`when`(viewModel.appsWithData).then {
            MutableLiveData<HealthPermissionTypesViewModel.AppsWithDataFragmentState>(
                HealthPermissionTypesViewModel.AppsWithDataFragmentState.WithData(
                    listOf(TEST_APP_3)))
        }
        Mockito.`when`(viewModel.selectedAppFilter).then { MutableLiveData("All apps") }
        Mockito.`when`(viewModel.editedPriorityList).then {
            MutableLiveData<List<AppMetadata>>(listOf())
        }
        Mockito.`when`(viewModel.categoryLabel).then { MutableLiveData("activity") }
        launchFragment<HealthPermissionTypesFragment>(activityCategoryBundle())

        onView(withText("All apps")).check(doesNotExist())
        onView(withText(TEST_APP_3.appName)).check(doesNotExist())
    }

    @Test
    fun permissionTypesFragment_appFilters_areSelectableCorrectly() {
        Mockito.`when`(viewModel.permissionTypesData).then {
            MutableLiveData<HealthPermissionTypesViewModel.PermissionTypesState>(
                HealthPermissionTypesViewModel.PermissionTypesState.WithData(
                    listOf(HealthPermissionType.DISTANCE, HealthPermissionType.EXERCISE)))
        }
        Mockito.`when`(viewModel.priorityList).then {
            MutableLiveData<HealthPermissionTypesViewModel.PriorityListState>(
                HealthPermissionTypesViewModel.PriorityListState.WithData(listOf()))
        }
        Mockito.`when`(viewModel.appsWithData).then {
            MutableLiveData<HealthPermissionTypesViewModel.AppsWithDataFragmentState>(
                HealthPermissionTypesViewModel.AppsWithDataFragmentState.WithData(
                    listOf(TEST_APP, TEST_APP_3)))
        }
        Mockito.`when`(viewModel.selectedAppFilter).then { MutableLiveData("All apps") }
        Mockito.`when`(viewModel.editedPriorityList).then {
            MutableLiveData<List<AppMetadata>>(listOf())
        }
        Mockito.`when`(viewModel.categoryLabel).then { MutableLiveData("activity") }
        launchFragment<HealthPermissionTypesFragment>(activityCategoryBundle())

        onView(withText(TEST_APP_3.appName)).perform(scrollTo(), click())
        assert(viewModel.selectedAppFilter.value == TEST_APP_3.appName)
    }

    private fun activityCategoryBundle(): Bundle {
        val bundle = Bundle()
        bundle.putInt(HealthDataCategoriesFragment.CATEGORY_KEY, 1)
        return bundle
    }
}
