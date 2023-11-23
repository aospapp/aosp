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
 *
 *
 */

package com.android.healthconnect.controller.tests.shared

import android.content.Context
import android.health.connect.HealthConnectManager
import android.health.connect.HealthDataCategory.ACTIVITY
import android.health.connect.HealthDataCategory.BODY_MEASUREMENTS
import android.health.connect.HealthDataCategory.CYCLE_TRACKING
import android.health.connect.HealthDataCategory.NUTRITION
import android.health.connect.HealthDataCategory.SLEEP
import android.health.connect.HealthDataCategory.VITALS
import androidx.test.platform.app.InstrumentationRegistry
import com.android.healthconnect.controller.R
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.permissions.data.HealthPermissionType
import com.android.healthconnect.controller.shared.HEALTH_DATA_CATEGORIES
import com.android.healthconnect.controller.shared.HealthDataCategoryExtensions.fromHealthPermissionType
import com.android.healthconnect.controller.shared.HealthDataCategoryExtensions.healthPermissionTypes
import com.android.healthconnect.controller.shared.HealthDataCategoryExtensions.lowercaseTitle
import com.android.healthconnect.controller.shared.HealthDataCategoryExtensions.uppercaseTitle
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class HealthDataCategoryExtensionsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().context
    }

    @Test
    fun allHealthPermission_haveParentCategory() {
        val allPermissions = HealthConnectManager.getHealthPermissions(context)
        allPermissions.forEach { permissionString ->
            val healthPermission = HealthPermission.fromPermissionString(permissionString)
            assertThat(
                    HEALTH_DATA_CATEGORIES.any {
                        it.healthPermissionTypes().contains(healthPermission.healthPermissionType)
                    })
                .isEqualTo(true)
        }
    }

    @Test
    fun uppercaseTitles() {
        assertThat(ACTIVITY.uppercaseTitle()).isEqualTo(R.string.activity_category_uppercase)
        assertThat(BODY_MEASUREMENTS.uppercaseTitle())
            .isEqualTo(R.string.body_measurements_category_uppercase)
        assertThat(CYCLE_TRACKING.uppercaseTitle())
            .isEqualTo(R.string.cycle_tracking_category_uppercase)
        assertThat(NUTRITION.uppercaseTitle()).isEqualTo(R.string.nutrition_category_uppercase)
        assertThat(SLEEP.uppercaseTitle()).isEqualTo(R.string.sleep_category_uppercase)
        assertThat(VITALS.uppercaseTitle()).isEqualTo(R.string.vitals_category_uppercase)
    }

    @Test
    fun uppercaseTitles_categoryNotSupported_throws() {
        assertThrows("Category 100 is not supported", IllegalArgumentException::class.java) {
            100.uppercaseTitle()
        }
    }

    @Test
    fun lowercaseTitles() {
        assertThat(ACTIVITY.lowercaseTitle()).isEqualTo(R.string.activity_category_lowercase)
        assertThat(BODY_MEASUREMENTS.lowercaseTitle())
            .isEqualTo(R.string.body_measurements_category_lowercase)
        assertThat(CYCLE_TRACKING.lowercaseTitle())
            .isEqualTo(R.string.cycle_tracking_category_lowercase)
        assertThat(NUTRITION.lowercaseTitle()).isEqualTo(R.string.nutrition_category_lowercase)
        assertThat(SLEEP.lowercaseTitle()).isEqualTo(R.string.sleep_category_lowercase)
        assertThat(VITALS.lowercaseTitle()).isEqualTo(R.string.vitals_category_lowercase)
    }

    @Test
    fun lowercaseTitles_categoryNotSupported_throws() {
        assertThrows("Category 100 is not supported", IllegalArgumentException::class.java) {
            100.lowercaseTitle()
        }
    }

    @Test
    fun fromHealthPermissionType() {
        assertThat(fromHealthPermissionType(HealthPermissionType.HEART_RATE)).isEqualTo(VITALS)
        assertThat(fromHealthPermissionType(HealthPermissionType.EXERCISE_ROUTE))
            .isEqualTo(ACTIVITY)
    }
}
