/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.permission3.cts

import android.content.pm.PermissionInfo
import androidx.test.runner.AndroidJUnit4
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class PermissionEscalationTest : BasePermissionTest() {
    companion object {
        const val APP_APK_PATH_NON_RUNTIME =
            "$APK_DIRECTORY/CtsPermissionEscalationAppNonRuntime.apk"
        const val APP_APK_PATH_RUNTIME = "$APK_DIRECTORY/CtsPermissionEscalationAppRuntime.apk"
        const val APP_PACKAGE_NAME = "android.permission3.cts.permissionescalation"
    }

    @Before
    @After
    fun uninstallApp() {
        uninstallPackage(APP_PACKAGE_NAME, requireSuccess = false)
    }

    @Test
    fun testCannotEscalateNonRuntimePermissionsToRuntime() {
        installPackage(APP_APK_PATH_NON_RUNTIME)
        installPackage(APP_APK_PATH_RUNTIME, reinstall = true)

        // Ensure normal permission cannot be made dangerous
        val permissionInfo1 = packageManager.getPermissionInfo("$APP_PACKAGE_NAME.STEAL_AUDIO1", 0)
        assertEquals(
            "Shouldn't be able to change normal permission to dangerous",
            PermissionInfo.PROTECTION_NORMAL, permissionInfo1.protection
        )

        // Ensure signature permission cannot be made dangerous
        val permissionInfo2 = packageManager.getPermissionInfo("$APP_PACKAGE_NAME.STEAL_AUDIO2", 0)
        assertEquals(
            "Shouldn't be able to change signature permission to dangerous",
            PermissionInfo.PROTECTION_SIGNATURE, permissionInfo2.protection
        )
    }
}
