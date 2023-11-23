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

package android.safetycenter.cts

import android.content.Context
import android.safetycenter.SafetyCenterManager
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.SystemUtil
import com.android.safetycenter.testing.SafetyCenterApisWithShellPermissions.isSafetyCenterEnabledWithPermission
import com.android.safetycenter.testing.SafetyCenterFlags.deviceSupportsSafetyCenter
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith

/** CTS tests for Safety Center's shell commands. */
@RunWith(AndroidJUnit4::class)
class SafetyCenterShellCommandsTest {
    private val context: Context = getApplicationContext()

    @Test
    fun enabled_printsEnabledValue() {
        val enabled = executeShellCommand("cmd safety_center enabled").toBoolean()

        val safetyCenterManager = context.getSystemService(SafetyCenterManager::class.java)!!
        assertThat(enabled).isEqualTo(safetyCenterManager.isSafetyCenterEnabledWithPermission())
    }

    @Test
    fun supported_printsSupportedValue() {
        val supported = executeShellCommand("cmd safety_center supported").toBoolean()

        assertThat(supported).isEqualTo(context.deviceSupportsSafetyCenter())
    }

    @Test
    fun packageName_printsPackageName() {
        val packageName = executeShellCommand("cmd safety_center package-name")

        assertThat(packageName).isEqualTo(context.packageManager.permissionControllerPackageName)
    }

    private fun executeShellCommand(command: String): String =
        SystemUtil.runShellCommand(command).trim()
}
