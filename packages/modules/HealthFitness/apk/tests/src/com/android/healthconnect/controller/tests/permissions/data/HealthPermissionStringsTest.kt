/**
 * Copyright (C) 2022 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License. You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License
 * is distributed on an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express
 * or implied. See the License for the specific language governing permissions and limitations under
 * the License.
 */
package com.android.healthconnect.controller.tests.permissions.data

import android.content.Context
import android.health.connect.HealthConnectManager
import androidx.test.platform.app.InstrumentationRegistry
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.permissions.data.HealthPermissionStrings
import com.android.healthconnect.controller.permissions.data.HealthPermissionType
import com.google.common.truth.Truth.assertThat
import org.junit.Before
import org.junit.Test

class HealthPermissionStringsTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().context
    }

    @Test
    fun allHealthPermissionTypesHaveStrings() {
        for (type in HealthPermissionType.values()) {
            assertThat(HealthPermissionStrings.fromPermissionType(type)).isNotNull()
        }
    }

    @Test
    fun allHealthPermissionsHaveStrings() {
        val allPermissions = HealthConnectManager.getHealthPermissions(context)
        for (permission in allPermissions) {
            val type = HealthPermission.fromPermissionString(permission).healthPermissionType
            assertThat(HealthPermissionStrings.fromPermissionType(type)).isNotNull()
        }
    }
}
