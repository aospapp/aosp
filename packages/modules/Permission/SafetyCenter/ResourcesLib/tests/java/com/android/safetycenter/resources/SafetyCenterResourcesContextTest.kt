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

package com.android.safetycenter.resources

import android.content.Context
import android.content.pm.PackageManager
import android.content.res.Resources
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetyCenterResourcesContextTest {
    private val context: Context = getApplicationContext()
    private val theme: Resources.Theme? = context.theme

    @Test
    fun validDataWithValidInputs() {
        val resourcesContext =
            SafetyCenterResourcesContext(context, RESOURCES_APK_ACTION, null, CONFIG_NAME, 0, false)

        assertThat(resourcesContext.resourcesApkPkgName).isEqualTo(RESOURCES_APK_PKG_NAME)

        val configContent =
            resourcesContext.safetyCenterConfig?.bufferedReader().use { it?.readText() }

        assertThat(configContent).isEqualTo(CONFIG_CONTENT)
        assertThat(resourcesContext.assets).isNotNull()
        assertThat(resourcesContext.resources).isNotNull()
        assertThat(resourcesContext.theme).isNotNull()
    }

    @Test
    fun nullDataWithWrongAction() {
        val resourcesContext = createNewResourcesContext(resourcesApkAction = "wrong")

        assertThat(resourcesContext.resourcesApkPkgName).isNull()
        assertThat(resourcesContext.safetyCenterConfig).isNull()
        assertThat(resourcesContext.assets).isNull()
        assertThat(resourcesContext.resources).isNull()
        assertThat(resourcesContext.theme).isNull()
    }

    @Test
    fun nullDataWithWrongPath() {
        val resourcesContext =
            createNewResourcesContext(resourcesApkPath = "/apex/com.android.permission")

        assertThat(resourcesContext.resourcesApkPkgName).isNull()
        assertThat(resourcesContext.safetyCenterConfig).isNull()
        assertThat(resourcesContext.assets).isNull()
        assertThat(resourcesContext.resources).isNull()
        assertThat(resourcesContext.theme).isNull()
    }

    @Test
    fun nullDataWithWrongFlag() {
        val resourcesContext = createNewResourcesContext(flags = PackageManager.MATCH_SYSTEM_ONLY)

        assertThat(resourcesContext.resourcesApkPkgName).isNull()
        assertThat(resourcesContext.safetyCenterConfig).isNull()
        assertThat(resourcesContext.assets).isNull()
        assertThat(resourcesContext.resources).isNull()
        assertThat(resourcesContext.theme).isNull()
    }

    @Test
    fun nullConfigWithWrongConfigName() {
        val resourcesContext = createNewResourcesContext(configName = "wrong")

        assertThat(resourcesContext.resourcesApkPkgName).isNotNull()
        assertThat(resourcesContext.safetyCenterConfig).isNull()
        assertThat(resourcesContext.assets).isNotNull()
        assertThat(resourcesContext.resources).isNotNull()
        assertThat(resourcesContext.theme).isNotNull()
    }

    @Test
    fun getStringByName_validString_returnsString() {
        val resourcesContext = createNewResourcesContext()

        assertThat(resourcesContext.getStringByName("valid_string")).isEqualTo("I exist!")
    }

    @Test
    fun getStringByName_invalidStringWithFallback_returnsEmptyString() {
        val resourcesContext = createNewResourcesContext(fallback = true)

        assertThat(resourcesContext.getStringByName("invalid_string")).isEqualTo("")
    }

    @Test
    fun getStringByName_invalidStringWithoutFallback_throws() {
        val resourcesContext = createNewResourcesContext(fallback = false)

        assertFailsWith(Resources.NotFoundException::class) {
            resourcesContext.getStringByName("invalid_string")
        }
    }

    @Test
    fun getOptionalStringByName_validString_returnsString() {
        val resourcesContext = createNewResourcesContext()

        assertThat(resourcesContext.getOptionalStringByName("valid_string")).isEqualTo("I exist!")
    }

    @Test
    fun getOptionalStringByName_invalidStringWithFallback_returnsNull() {
        val resourcesContext = createNewResourcesContext(fallback = true)

        assertThat(resourcesContext.getOptionalStringByName("invalid_string")).isNull()
    }

    @Test
    fun getOptionalStringByName_invalidStringWithoutFallback_returnsNull() {
        val resourcesContext = createNewResourcesContext(fallback = false)

        assertThat(resourcesContext.getOptionalStringByName("invalid_string")).isNull()
    }

    @Test
    fun getDrawableByName_validDrawable_returnsDrawable() {
        val resourcesContext = createNewResourcesContext()

        assertThat(resourcesContext.getDrawableByName("valid_drawable", theme)).isNotNull()
    }

    @Test
    fun getDrawableByName_invalidDrawableWithFallback_returnsNull() {
        val resourcesContext = createNewResourcesContext(fallback = true)

        assertThat(resourcesContext.getDrawableByName("invalid_drawable", theme)).isNull()
    }

    @Test
    fun getDrawableByName_invalidDrawableWithoutFallback_throws() {
        val resourcesContext = createNewResourcesContext(fallback = false)

        assertFailsWith(Resources.NotFoundException::class) {
            resourcesContext.getDrawableByName("invalid_drawable", theme)
        }
    }

    @Test
    fun getIconByDrawableName_validDrawable_returnsIcon() {
        val resourcesContext = createNewResourcesContext()

        assertThat(resourcesContext.getIconByDrawableName("valid_drawable")).isNotNull()
    }

    @Test
    fun getIconByDrawableName_invalidDrawableWithFallback_returnsNull() {
        val resourcesContext = createNewResourcesContext(fallback = true)

        assertThat(resourcesContext.getIconByDrawableName("invalid_drawable")).isNull()
    }

    @Test
    fun getIconByDrawableName_invalidDrawableWithoutFallback_throws() {
        val resourcesContext = createNewResourcesContext(fallback = false)

        assertFailsWith(Resources.NotFoundException::class) {
            resourcesContext.getIconByDrawableName("invalid_drawable")
        }
    }

    @Test
    fun getColorByName_validColor_returnsColor() {
        val resourcesContext = createNewResourcesContext()

        assertThat(resourcesContext.getColorByName("valid_color")).isNotNull()
    }

    @Test
    fun getColorByName_invalidColorWithFallback_returnsNull() {
        val resourcesContext = createNewResourcesContext(fallback = true)

        assertThat(resourcesContext.getColorByName("invalid_color")).isNull()
    }

    @Test
    fun getColorByName_invalidColorWithoutFallback_throws() {
        val resourcesContext = createNewResourcesContext(fallback = false)

        assertFailsWith(Resources.NotFoundException::class) {
            resourcesContext.getColorByName("invalid_color")
        }
    }

    private fun createNewResourcesContext(
        resourcesApkAction: String = RESOURCES_APK_ACTION,
        resourcesApkPath: String? = null,
        configName: String = CONFIG_NAME,
        flags: Int = 0,
        fallback: Boolean = false
    ) =
        SafetyCenterResourcesContext(
            context, resourcesApkAction, resourcesApkPath, configName, flags, fallback)

    companion object {
        const val RESOURCES_APK_ACTION =
            "com.android.safetycenter.tests.intent.action.SAFETY_CENTER_TEST_RESOURCES_APK"
        const val RESOURCES_APK_PKG_NAME =
            "com.android.safetycenter.tests.config.safetycenterresourceslibtestresources"
        const val CONFIG_NAME = "test"
        const val CONFIG_CONTENT = "TEST"
    }
}
