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

import android.content.Intent.EXTRA_PACKAGE_NAME
import androidx.core.os.bundleOf
import androidx.lifecycle.MutableLiveData
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.assertion.ViewAssertions.matches
import androidx.test.espresso.matcher.ViewMatchers.isDisplayed
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.platform.app.InstrumentationRegistry
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.permissions.app.AppPermissionViewModel
import com.android.healthconnect.controller.permissions.app.SettingsManageAppPermissionsFragment
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.permissions.data.HealthPermissionType
import com.android.healthconnect.controller.permissions.data.PermissionsAccessType
import com.android.healthconnect.controller.shared.app.AppMetadata
import com.android.healthconnect.controller.tests.utils.TEST_APP_NAME
import com.android.healthconnect.controller.tests.utils.TEST_APP_PACKAGE_NAME
import com.android.healthconnect.controller.tests.utils.launchFragment
import com.android.healthconnect.controller.tests.utils.setLocale
import com.android.healthconnect.controller.tests.utils.whenever
import com.android.healthconnect.controller.utils.FeatureUtils
import com.android.healthconnect.controller.utils.FeaturesModule
import dagger.Module
import dagger.Provides
import dagger.hilt.android.testing.BindValue
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import java.time.Instant
import java.time.ZoneId
import java.util.Locale
import java.util.TimeZone
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.mockito.Mockito

@HiltAndroidTest
class SettingsManageAppPermissionsFragmentTest {

    @get:Rule val hiltRule = HiltAndroidRule(this)

    @BindValue
    val viewModel: AppPermissionViewModel = Mockito.mock(AppPermissionViewModel::class.java)

    @Before
    fun setup() {
        val context = InstrumentationRegistry.getInstrumentation().context
        context.setLocale(Locale.US)
        TimeZone.setDefault(TimeZone.getTimeZone(ZoneId.of("UTC")))
        hiltRule.inject()

        whenever(viewModel.revokeAllPermissionsState).then {
            MutableLiveData(AppPermissionViewModel.RevokeAllState.NotStarted)
        }
        whenever(viewModel.allAppPermissionsGranted).then { MutableLiveData(false) }
        whenever(viewModel.atLeastOnePermissionGranted).then { MutableLiveData(true) }
        val accessDate = Instant.parse("2022-10-20T18:40:13.00Z")
        whenever(viewModel.loadAccessDate(Mockito.anyString())).thenReturn(accessDate)

        whenever(viewModel.appInfo).then {
            MutableLiveData(
                AppMetadata(
                    TEST_APP_PACKAGE_NAME,
                    TEST_APP_NAME,
                    context.getDrawable(R.drawable.health_connect_logo)))
        }
        val writePermission =
            HealthPermission(HealthPermissionType.EXERCISE, PermissionsAccessType.WRITE)
        val readPermission =
            HealthPermission(HealthPermissionType.DISTANCE, PermissionsAccessType.READ)
        whenever(viewModel.appPermissions).then {
            MutableLiveData(listOf(writePermission, readPermission))
        }
        whenever(viewModel.grantedPermissions).then { MutableLiveData(setOf(writePermission)) }
    }

    @Test
    fun fragment_starts() {
        launchFragment<SettingsManageAppPermissionsFragment>(
            bundleOf(EXTRA_PACKAGE_NAME to TEST_APP_PACKAGE_NAME))

        onView(withText("Allow all")).check(matches(isDisplayed()))
        onView(withText("Allowed to read")).check(matches(isDisplayed()))
        onView(withText("Allowed to write")).check(matches(isDisplayed()))
    }
}

@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [FeaturesModule::class])
object FakeFeaturesUtilsModule {
    @Provides
    fun providesFeaturesUtils() =
        object : FeatureUtils {
            override fun isSessionTypesEnabled(): Boolean = true
            override fun isExerciseRouteEnabled(): Boolean = true
            override fun isEntryPointsEnabled(): Boolean = true
        }
}
