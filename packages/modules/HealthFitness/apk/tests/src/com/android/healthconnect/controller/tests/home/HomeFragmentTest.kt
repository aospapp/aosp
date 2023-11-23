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
package com.android.healthconnect.controller.tests.home

import android.content.Context
import android.health.connect.HealthDataCategory
import android.os.Bundle
import androidx.lifecycle.MutableLiveData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.android.healthconnect.controller.home.HomeFragment
import com.android.healthconnect.controller.home.HomeFragmentViewModel
import com.android.healthconnect.controller.migration.MigrationViewModel
import com.android.healthconnect.controller.migration.MigrationViewModel.MigrationFragmentState.WithData
import com.android.healthconnect.controller.migration.api.MigrationState
import com.android.healthconnect.controller.recentaccess.RecentAccessEntry
import com.android.healthconnect.controller.recentaccess.RecentAccessViewModel
import com.android.healthconnect.controller.recentaccess.RecentAccessViewModel.RecentAccessState
import com.android.healthconnect.controller.shared.HealthDataCategoryExtensions.uppercaseTitle
import com.android.healthconnect.controller.shared.app.ConnectedAppMetadata
import com.android.healthconnect.controller.shared.app.ConnectedAppStatus
import com.android.healthconnect.controller.tests.utils.TEST_APP
import com.android.healthconnect.controller.tests.utils.TEST_APP_2
import com.android.healthconnect.controller.tests.utils.TEST_APP_NAME
import com.android.healthconnect.controller.tests.utils.launchFragment
import com.android.healthconnect.controller.tests.utils.setLocale
import com.android.healthconnect.controller.tests.utils.whenever
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

@HiltAndroidTest
class HomeFragmentTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)
    private lateinit var context: Context

    @BindValue
    val homeFragmentViewModel: HomeFragmentViewModel =
        Mockito.mock(HomeFragmentViewModel::class.java)

    @BindValue
    val recentAccessViewModel: RecentAccessViewModel =
        Mockito.mock(RecentAccessViewModel::class.java)

    @BindValue
    val migrationViewModel: MigrationViewModel = Mockito.mock(MigrationViewModel::class.java)

    @Before
    fun setup() {
        hiltRule.inject()
        context = InstrumentationRegistry.getInstrumentation().context
        context.setLocale(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("UTC")))
        whenever(migrationViewModel.migrationState).then {
            MutableLiveData(WithData(MigrationState.IDLE))
        }
    }

    @Test
    fun test_HomeFragment_withRecentAccessApps() {
        val recentApp =
            RecentAccessEntry(
                metadata = TEST_APP,
                instantTime = Instant.parse("2022-10-20T18:40:13.00Z"),
                isToday = true,
                dataTypesWritten =
                    mutableSetOf(
                        HealthDataCategory.ACTIVITY.uppercaseTitle(),
                        HealthDataCategory.VITALS.uppercaseTitle()),
                dataTypesRead =
                    mutableSetOf(
                        HealthDataCategory.SLEEP.uppercaseTitle(),
                        HealthDataCategory.NUTRITION.uppercaseTitle()))

        whenever(recentAccessViewModel.recentAccessApps).then {
            MutableLiveData<RecentAccessState>(RecentAccessState.WithData(listOf(recentApp)))
        }
        whenever(homeFragmentViewModel.connectedApps).then {
            MutableLiveData(listOf<ConnectedAppMetadata>())
        }
        launchFragment<HomeFragment>(Bundle())

        onView(
                withText(
                    "Manage the health and fitness data on your phone, and control which apps can access it"))
            .check(matches(isDisplayed()))
        onView(withText("App permissions")).check(matches(isDisplayed()))
        onView(withText("None")).check(matches(isDisplayed()))
        onView(withText("Data and access")).check(matches(isDisplayed()))

        onView(withText("Recent access")).check(matches(isDisplayed()))
        onView(withText(TEST_APP_NAME)).check(matches(isDisplayed()))
        onView(withText("18:40")).check(matches(isDisplayed()))
        onView(withText("See all recent access")).check(matches(isDisplayed()))
    }

    @Test
    fun test_HomeFragment_withNoRecentAccessApps() {
        whenever(recentAccessViewModel.recentAccessApps).then {
            MutableLiveData<RecentAccessState>(RecentAccessState.WithData(emptyList()))
        }
        whenever(homeFragmentViewModel.connectedApps).then {
            MutableLiveData(
                listOf(
                    ConnectedAppMetadata(TEST_APP, ConnectedAppStatus.ALLOWED),
                    ConnectedAppMetadata(TEST_APP_2, ConnectedAppStatus.ALLOWED)))
        }
        launchFragment<HomeFragment>(Bundle())

        onView(
                withText(
                    "Manage the health and fitness data on your phone, and control which apps can access it"))
            .check(matches(isDisplayed()))
        onView(withText("App permissions")).check(matches(isDisplayed()))
        onView(withText("2 apps have access")).check(matches(isDisplayed()))
        onView(withText("Data and access")).check(matches(isDisplayed()))

        onView(withText("Recent access")).check(matches(isDisplayed()))
        onView(withText("No apps recently accessed Health\u00A0Connect"))
            .check(matches(isDisplayed()))
    }

    @Test
    fun test_HomeFragment_withOneApp() {
        whenever(recentAccessViewModel.recentAccessApps).then {
            MutableLiveData<RecentAccessState>(RecentAccessState.WithData(emptyList()))
        }
        whenever(homeFragmentViewModel.connectedApps).then {
            MutableLiveData(listOf(ConnectedAppMetadata(TEST_APP, ConnectedAppStatus.ALLOWED)))
        }
        launchFragment<HomeFragment>(Bundle())

        onView(
                withText(
                    "Manage the health and fitness data on your phone, and control which apps can access it"))
            .check(matches(isDisplayed()))
        onView(withText("App permissions")).check(matches(isDisplayed()))
        onView(withText("1 app has access")).check(matches(isDisplayed()))
        onView(withText("Data and access")).check(matches(isDisplayed()))
    }

    @Test
    fun test_HomeFragment_withOneAppConnected_oneAppNotConnected() {
        whenever(recentAccessViewModel.recentAccessApps).then {
            MutableLiveData<RecentAccessState>(RecentAccessState.WithData(emptyList()))
        }
        whenever(homeFragmentViewModel.connectedApps).then {
            MutableLiveData(
                listOf(
                    ConnectedAppMetadata(TEST_APP, ConnectedAppStatus.ALLOWED),
                    ConnectedAppMetadata(TEST_APP_2, ConnectedAppStatus.DENIED)))
        }

        launchFragment<HomeFragment>(Bundle())

        onView(
                withText(
                    "Manage the health and fitness data on your phone, and control which apps can access it"))
            .check(matches(isDisplayed()))
        onView(withText("App permissions")).check(matches(isDisplayed()))
        onView(withText("1 of 2 apps have access")).check(matches(isDisplayed()))
        onView(withText("Data and access")).check(matches(isDisplayed()))
    }
}
