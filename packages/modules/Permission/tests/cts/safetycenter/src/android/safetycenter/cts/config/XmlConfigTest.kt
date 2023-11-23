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

package android.safetycenter.cts.config

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager.ResolveInfoFlags
import android.safetycenter.SafetyCenterManager
import android.safetycenter.config.SafetySource.SAFETY_SOURCE_TYPE_ISSUE_ONLY
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.safetycenter.config.SafetyCenterConfigParser
import com.android.safetycenter.resources.SafetyCenterResourcesContext
import com.android.safetycenter.testing.SafetyCenterApisWithShellPermissions.getSafetyCenterConfigWithPermission
import com.android.safetycenter.testing.SafetyCenterFlags.deviceSupportsSafetyCenter
import com.android.safetycenter.testing.SafetyCenterTestHelper
import com.google.common.truth.Truth.assertThat
import com.google.common.truth.Truth.assertWithMessage
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** CTS tests for the Safety Center XML config file. */
@RunWith(AndroidJUnit4::class)
class XmlConfigTest {
    private val context: Context = getApplicationContext()
    private val safetyCenterContext = SafetyCenterResourcesContext.forTests(context)
    private val safetyCenterTestHelper = SafetyCenterTestHelper(context)
    private val safetyCenterManager = context.getSystemService(SafetyCenterManager::class.java)!!
    // JUnit's Assume is not supported in @BeforeClass by the CTS tests runner, so this is used to
    // manually skip the setup and teardown methods.
    private val shouldRunTests = context.deviceSupportsSafetyCenter()

    @Before
    fun assumeDeviceSupportsSafetyCenterToRunTests() {
        assumeTrue(shouldRunTests)
    }

    @Before
    fun enableSafetyCenterBeforeTest() {
        if (!shouldRunTests) {
            return
        }
        safetyCenterTestHelper.setup()
    }

    @After
    fun clearDataAfterTest() {
        if (!shouldRunTests) {
            return
        }
        safetyCenterTestHelper.reset()
    }

    @Test
    fun safetyCenterConfigResource_validConfig() {
        val parsedSafetyCenterConfig = parseXmlConfig()
        val safetyCenterConfig = safetyCenterManager.getSafetyCenterConfigWithPermission()

        assertThat(parsedSafetyCenterConfig).isEqualTo(safetyCenterConfig)
    }

    @Test
    fun safetyCenterConfigResource_privacyControlsIntentResolvesIfInConfig() {
        assertThatIntentResolvesIfInConfig(ADVANCED_PRIVACY_INTENT_STRING)
    }

    @Test
    fun safetyCenterConfigResource_advancedPrivacyIntentResolvesIfInConfig() {
        assertThatIntentResolvesIfInConfig(PRIVACY_CONTROLS_INTENT_STRING)
    }

    private fun assertThatIntentResolvesIfInConfig(intentAction: String) {
        if (isIntentInConfig(intentAction)) {
            assertThatIntentResolves(intentAction)
        }
    }

    private fun assertThatIntentResolves(intentAction: String) {
        val pm = safetyCenterContext.packageManager
        assertWithMessage("Intent '%s' cannot be resolved.", intentAction)
            .that(pm.queryIntentActivities(Intent(intentAction), ResolveInfoFlags.of(0)))
            .isNotEmpty()
    }

    private fun isIntentInConfig(intentAction: String): Boolean {
        val safetyCenterConfig = parseXmlConfig()
        return safetyCenterConfig.safetySourcesGroups
            .flatMap { it.safetySources }
            .filter { it.type != SAFETY_SOURCE_TYPE_ISSUE_ONLY }
            .any { it.intentAction == intentAction }
    }

    private fun parseXmlConfig() =
        SafetyCenterConfigParser.parseXmlResource(
            safetyCenterContext.safetyCenterConfig!!,
            safetyCenterContext.resources!!
        )

    companion object {
        private const val ADVANCED_PRIVACY_INTENT_STRING =
            "android.settings.PRIVACY_ADVANCED_SETTINGS"
        private const val PRIVACY_CONTROLS_INTENT_STRING = "android.settings.PRIVACY_CONTROLS"
    }
}
