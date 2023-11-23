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
 */

package android.permission3.cts

import android.accessibility.cts.common.InstrumentedAccessibilityService
import android.accessibility.cts.common.InstrumentedAccessibilityServiceTestRule
import android.app.UiAutomation
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.platform.test.annotations.AppModeFull
import android.platform.test.rule.ScreenRecordRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Configurator
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.android.compatibility.common.util.SystemUtil
import com.android.compatibility.common.util.UiAutomatorUtils2.waitFindObjectOrNull
import java.util.regex.Pattern
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assume
import org.junit.Before
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@AppModeFull(reason = "Instant apps cannot be a11y services")
class ReviewAccessibilityServicesTest {

    private val context: Context = InstrumentationRegistry.getInstrumentation().context
    private val uiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())
    private val testService1String = context.getString(R.string.test_accessibility_service)
    private val testService2String = context.getString(R.string.test_accessibility_service_2)

    companion object {
        private const val EXPECTED_TIMEOUT_MS = 500L
    }

    @get:Rule
    val screenRecordRule = ScreenRecordRule()

    @get:Rule
    val accessibilityServiceRule =
        InstrumentedAccessibilityServiceTestRule(AccessibilityTestService1::class.java, false)

    @get:Rule
    val accessibilityServiceRule2 =
        InstrumentedAccessibilityServiceTestRule(AccessibilityTestService2::class.java, false)

    init {
        Configurator.getInstance().uiAutomationFlags =
            UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES
    }

    @Before
    fun assumeNotAutoTvOrWear() {
        Assume.assumeFalse(context.packageManager.hasSystemFeature(PackageManager.FEATURE_LEANBACK))
        Assume.assumeFalse(
            context.packageManager.hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE))
        Assume.assumeFalse(context.packageManager.hasSystemFeature(PackageManager.FEATURE_WATCH))
    }

    @After
    fun cleanUp() {
        uiDevice.pressHome()
    }

    @Test
    @ScreenRecordRule.ScreenRecord
    fun testActivityShowsSingleEnabledAccessibilityService() {
        accessibilityServiceRule.enableService()
        startAccessibilityActivity()
        findTestService(true)
        findTestService2(false)
    }

    @Test
    @ScreenRecordRule.ScreenRecord
    fun testActivityShowsMultipleEnabledAccessibilityServices() {
        accessibilityServiceRule.enableService()
        accessibilityServiceRule2.enableService()
        startAccessibilityActivity()
        findTestService(true)
        findTestService2(true)
    }

    @Test
    @ScreenRecordRule.ScreenRecord
    fun testClickingSettingsGoesToIndividualSettingsWhenOneServiceEnabled() {
        accessibilityServiceRule.enableService()
        startAccessibilityActivity()
        clickSettings()
        waitForSettingsButtonToDisappear()
        findTestService(true)
        findTestService2(false)
    }

    @Test
    @ScreenRecordRule.ScreenRecord
    fun testClickingSettingsGoesToGeneralSettingsWhenMultipleServicesEnabled() {
        accessibilityServiceRule.enableService()
        accessibilityServiceRule2.enableService()
        startAccessibilityActivity()
        clickSettings()
        waitForSettingsButtonToDisappear()
        findTestService(true)
        findTestService2(true)
    }

    @Test
    @ScreenRecordRule.ScreenRecord
    fun testClickingIndividualGoesToIndividualSettingsWhenMultipleServicesEnabled() {
        accessibilityServiceRule.enableService()
        accessibilityServiceRule2.enableService()
        startAccessibilityActivity()
        uiDevice.waitForIdle()
        findTestService2(true)!!.click()
        waitForSettingsButtonToDisappear()
        findTestService2(true)
        findTestService(false)
    }

    private fun startAccessibilityActivity() {
        val automan =
            InstrumentationRegistry.getInstrumentation()
                .getUiAutomation(UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES)
        automan.adoptShellPermissionIdentity()
        try {
            context.startActivity(
                Intent(Intent.ACTION_REVIEW_ACCESSIBILITY_SERVICES)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK))
        } catch (e: Exception) {
            throw RuntimeException("Caught exception", e)
        } finally {
            automan.dropShellPermissionIdentity()
        }
    }

    private fun findTestService(shouldBePresent: Boolean): UiObject2? {
        return findObjectByText(shouldBePresent, testService1String)
    }

    private fun findTestService2(shouldBePresent: Boolean): UiObject2? {
        return findObjectByText(shouldBePresent, testService2String)
    }

    private fun clickSettings() {
        findObjectByText(true, "Settings")?.click()
    }

    private fun waitForSettingsButtonToDisappear() {
        SystemUtil.eventually {
            findObjectByText(false, "Settings")
        }
    }

    private fun findObjectByTextWithoutRetry(shouldBePresent: Boolean, text: String, ): UiObject2? {
        val containsWithoutCaseSelector =
            By.text(Pattern.compile(".*$text.*", Pattern.CASE_INSENSITIVE))
        val view = if (shouldBePresent) {
            waitFindObjectOrNull(containsWithoutCaseSelector)
        } else {
            waitFindObjectOrNull(containsWithoutCaseSelector, EXPECTED_TIMEOUT_MS)
        }

        assertEquals("Expected to find view with text $text: $shouldBePresent",
            shouldBePresent, view != null)
        return view
    }

    private fun findObjectByText(expected: Boolean, text: String): UiObject2? {
        try {
            return findObjectByTextWithoutRetry(expected, text)
        } catch (stale: StaleObjectException) {
            return findObjectByTextWithoutRetry(expected, text)
        }
    }
}

/** Test Accessibility Services */
class AccessibilityTestService1 : InstrumentedAccessibilityService()

class AccessibilityTestService2 : InstrumentedAccessibilityService()
