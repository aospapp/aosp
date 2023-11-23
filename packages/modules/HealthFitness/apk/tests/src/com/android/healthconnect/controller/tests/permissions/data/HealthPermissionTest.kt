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
package com.android.healthconnect.controller.tests.permissions.data

import android.content.Context
import android.health.connect.HealthConnectManager
import androidx.test.platform.app.InstrumentationRegistry
import com.android.healthconnect.controller.permissions.data.HealthPermission
import com.android.healthconnect.controller.permissions.data.HealthPermission.Companion.fromPermissionString
import com.android.healthconnect.controller.permissions.data.HealthPermissionType.ACTIVE_CALORIES_BURNED
import com.android.healthconnect.controller.permissions.data.HealthPermissionType.BLOOD_GLUCOSE
import com.android.healthconnect.controller.permissions.data.PermissionsAccessType
import com.google.common.truth.Truth.assertThat
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test

class HealthPermissionTest {

    private lateinit var context: Context

    @Before
    fun setup() {
        context = InstrumentationRegistry.getInstrumentation().context
    }

    @Test
    fun fromPermission_returnsCorrectReadHealthPermission() {
        assertThat(fromPermissionString("android.permission.health.READ_ACTIVE_CALORIES_BURNED"))
            .isEqualTo(HealthPermission(ACTIVE_CALORIES_BURNED, PermissionsAccessType.READ))
    }

    @Test
    fun fromPermission_returnsCorrectWriteHealthPermission() {
        assertThat(fromPermissionString("android.permission.health.WRITE_BLOOD_GLUCOSE"))
            .isEqualTo(HealthPermission(BLOOD_GLUCOSE, PermissionsAccessType.WRITE))
    }

    @Test
    fun fromPermissionString_canParseAllHealthPermissions() {
        val allPermissions = HealthConnectManager.getHealthPermissions(context)
        allPermissions.forEach { permissionString ->
            assertThat(fromPermissionString(permissionString).toString())
                .isEqualTo(permissionString)
        }
    }

    @Test
    fun fromPermissionString_throwsIllegalArgumentException() {
        assertThrows(IllegalArgumentException::class.java) {
            fromPermissionString("Unsupported_permission")
        }
    }
}
