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
package com.android.healthconnect.controller.tests.categories

import android.health.connect.HealthDataCategory
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.doesNotExist
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.isEnabled
import androidx.test.espresso.matcher.ViewMatchers.withId
import androidx.test.espresso.matcher.ViewMatchers.withText
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.autodelete.AutoDeleteRange
import com.android.healthconnect.controller.autodelete.AutoDeleteViewModel
import com.android.healthconnect.controller.autodelete.AutoDeleteViewModel.AutoDeleteState
import com.android.healthconnect.controller.categories.HealthCategoryUiState
import com.android.healthconnect.controller.categories.HealthDataCategoriesFragment
import com.android.healthconnect.controller.categories.HealthDataCategoryViewModel
import com.android.healthconnect.controller.categories.HealthDataCategoryViewModel.CategoriesFragmentState
import com.android.healthconnect.controller.categories.HealthDataCategoryViewModel.CategoriesFragmentState.WithData
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

/** List of all Health data categories. */
private val HEALTH_DATA_ALL_CATEGORIES =
    listOf(
        HealthCategoryUiState(category = HealthDataCategory.ACTIVITY, hasData = true),
        HealthCategoryUiState(category = HealthDataCategory.BODY_MEASUREMENTS, hasData = true),
        HealthCategoryUiState(category = HealthDataCategory.SLEEP, hasData = false),
        HealthCategoryUiState(category = HealthDataCategory.VITALS, hasData = false),
        HealthCategoryUiState(category = HealthDataCategory.CYCLE_TRACKING, hasData = false),
        HealthCategoryUiState(category = HealthDataCategory.NUTRITION, hasData = false),
    )

@HiltAndroidTest
class HealthDataCategoriesFragmentTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @BindValue
    val viewModel: HealthDataCategoryViewModel =
        Mockito.mock(HealthDataCategoryViewModel::class.java)

    @BindValue
    val autoDeleteViewModel: AutoDeleteViewModel = Mockito.mock(AutoDeleteViewModel::class.java)

    @Before
    fun setup() {
        hiltRule.inject()
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
    }

    @Test
    fun categoriesFragment_isDisplayed() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(emptyList()))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("Browse data")).check(matches(isDisplayed()))
        onView(withText("Manage data")).check(matches(isDisplayed()))
        onView(withText("Auto-delete")).check(matches(isDisplayed()))
        onView(withText("Off")).check(matches(isDisplayed()))
    }

    @Test
    fun categoriesFragment_emptyCategories_noDataViewIsDisplayed_deleteIconIsDisabled() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(emptyList()))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("You don't have any data in Health\u00A0Connect"))
            .check(matches(isDisplayed()))
        onView(withText("Delete all data")).check(matches(not(isEnabled())))
    }

    @Test
    fun categoriesFragment_withCategories_categoryInformationIsDisplayed() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(HEALTH_DATA_ALL_CATEGORIES))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("Activity")).check(matches(isDisplayed()))
        onView(withText("Body measurements")).check(matches(isDisplayed()))
    }

    @Test
    fun seeAllCategoriesPreference_isDisplayed() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(HEALTH_DATA_ALL_CATEGORIES))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("See all categories")).check(matches(isDisplayed()))
    }

    @Test
    fun seeAllCategoriesPreference_withNoData_isDisplayed() {
        val categories = listOf(
            HealthCategoryUiState(category = HealthDataCategory.ACTIVITY, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.BODY_MEASUREMENTS, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.SLEEP, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.VITALS, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.CYCLE_TRACKING, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.NUTRITION, hasData = false),
        )
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(categories))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("See all categories")).check(matches(isDisplayed()))
    }

    @Test
    fun deleteAllData_withNoData_isDisabled() {
        val categories = listOf(
            HealthCategoryUiState(category = HealthDataCategory.ACTIVITY, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.BODY_MEASUREMENTS, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.SLEEP, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.VITALS, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.CYCLE_TRACKING, hasData = false),
            HealthCategoryUiState(category = HealthDataCategory.NUTRITION, hasData = false),
        )
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(categories))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("Delete all data")).check(matches(isDisplayed()))
        onView(withText("Delete all data")).check(matches(not(isEnabled())))
    }

    @Test
    fun deleteAllData_withData_isEnabled() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(HEALTH_DATA_ALL_CATEGORIES))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("Delete all data")).check(matches(isDisplayed()))
        onView(withText("Delete all data")).check(matches(isEnabled()))
    }

    @Test
    fun categoriesFragment_withAllCategoriesPresent_seeAllCategoriesPreferenceIsNotDisplayed() {
        val allCategories =
            listOf(
                HealthCategoryUiState(category = HealthDataCategory.ACTIVITY, hasData = true),
                HealthCategoryUiState(
                    category = HealthDataCategory.BODY_MEASUREMENTS, hasData = true),
                HealthCategoryUiState(category = HealthDataCategory.SLEEP, hasData = true),
                HealthCategoryUiState(category = HealthDataCategory.VITALS, hasData = true),
                HealthCategoryUiState(category = HealthDataCategory.CYCLE_TRACKING, hasData = true),
                HealthCategoryUiState(category = HealthDataCategory.NUTRITION, hasData = true),
            )
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(allCategories))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("See all categories")).check(doesNotExist())
    }

    @Test
    fun categoriesFragment_autoDeleteButton_displaysAutoDeleteRangeNever() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(emptyList()))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_NEVER))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("Auto-delete")).check(matches(isDisplayed()))
        onView(withText("Off")).check(matches(isDisplayed()))
    }

    @Test
    fun categoriesFragment_autoDeleteButton_displaysAutoDeleteRange3Months() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(emptyList()))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(
                AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_THREE_MONTHS))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("Auto-delete")).check(matches(isDisplayed()))
        onView(withText("After 3 months")).check(matches(isDisplayed()))
    }

    @Test
    fun categoriesFragment_autoDeleteButton_displaysAutoDeleteRange18Months() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(emptyList()))
        }
        whenever(autoDeleteViewModel.storedAutoDeleteRange).then {
            MutableLiveData(
                AutoDeleteState.WithData(AutoDeleteRange.AUTO_DELETE_RANGE_EIGHTEEN_MONTHS))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())

        onView(withText("Auto-delete")).check(matches(isDisplayed()))
        onView(withText("After 18 months")).check(matches(isDisplayed()))
    }

    @Test
    fun categoriesFragment_loadingState_showsLoading() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(CategoriesFragmentState.Loading)
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())
        onView(withId(R.id.progress_indicator)).check(matches(isDisplayed()))
    }

    @Test
    fun categoriesFragment_withData_hidesLoading() {
        whenever(viewModel.categoriesData).then {
            MutableLiveData<CategoriesFragmentState>(WithData(emptyList()))
        }
        launchFragment<HealthDataCategoriesFragment>(Bundle())
        onView(withId(R.id.progress_indicator)).check(matches(not(isDisplayed())))
    }
}
