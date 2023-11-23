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

package android.safetycenter.functional.ui

import android.content.Context
import androidx.test.core.app.ApplicationProvider.getApplicationContext
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import com.android.compatibility.common.util.DisableAnimationRule
import com.android.compatibility.common.util.FreezeRotationRule
import com.android.safetycenter.resources.SafetyCenterResourcesContext
import com.android.safetycenter.testing.SafetyCenterActivityLauncher.launchSafetyCenterActivity
import com.android.safetycenter.testing.SafetyCenterFlags.deviceSupportsSafetyCenter
import com.android.safetycenter.testing.SafetyCenterTestConfigs
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.SINGLE_SOURCE_ID
import com.android.safetycenter.testing.SafetyCenterTestData
import com.android.safetycenter.testing.SafetyCenterTestHelper
import com.android.safetycenter.testing.SafetySourceIntentHandler.Request
import com.android.safetycenter.testing.SafetySourceIntentHandler.Response
import com.android.safetycenter.testing.SafetySourceReceiver
import com.android.safetycenter.testing.SafetySourceTestData
import com.android.safetycenter.testing.UiTestHelper.RESCAN_BUTTON_LABEL
import com.android.safetycenter.testing.UiTestHelper.waitAllTextDisplayed
import com.android.safetycenter.testing.UiTestHelper.waitButtonDisplayed
import com.android.safetycenter.testing.UiTestHelper.waitButtonNotDisplayed
import com.android.safetycenter.testing.UiTestHelper.waitDisplayed
import com.android.safetycenter.testing.UiTestHelper.waitNotDisplayed
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Functional tests for the Safety Center Status Card. */
@RunWith(AndroidJUnit4::class)
class SafetyCenterStatusCardTest {

    @get:Rule val disableAnimationRule = DisableAnimationRule()

    @get:Rule val freezeRotationRule = FreezeRotationRule()

    private val context: Context = getApplicationContext()

    private val safetyCenterResourcesContext = SafetyCenterResourcesContext.forTests(context)
    private val safetyCenterTestHelper = SafetyCenterTestHelper(context)
    private val safetySourceTestData = SafetySourceTestData(context)
    private val safetyCenterTestData = SafetyCenterTestData(context)
    private val safetyCenterTestConfigs = SafetyCenterTestConfigs(context)

    // JUnit's Assume is not supported in @BeforeClass by the tests runner, so this is used to
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
    fun withUnknownStatus_displaysScanningOnLoad() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)

        context.launchSafetyCenterActivity {
            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName("scanning_title"),
                safetyCenterResourcesContext.getStringByName("loading_summary")
            )
        }
    }

    @Test
    fun withKnownStatus_displaysStatusOnLoad() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        safetyCenterTestHelper.setData(
            SINGLE_SOURCE_ID,
            safetySourceTestData.informationWithIconAction
        )

        context.launchSafetyCenterActivity {
            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName("overall_severity_level_ok_title"),
                safetyCenterResourcesContext.getStringByName("loading_summary")
            )
        }
    }

    @Test
    fun withUnknownStatusAndNoIssues_hasRescanButton() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        SafetySourceReceiver.setResponse(Request.Refresh(SINGLE_SOURCE_ID), Response.Error)

        context.launchSafetyCenterActivity(withReceiverPermission = true) {
            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName(
                    "overall_severity_level_ok_review_title"
                ),
                safetyCenterResourcesContext.getStringByName(
                    "overall_severity_level_ok_review_summary"
                )
            )
            waitButtonDisplayed(RESCAN_BUTTON_LABEL)
        }
    }

    @Test
    fun withInformationAndNoIssues_hasRescanButton() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        SafetySourceReceiver.setResponse(
            Request.Refresh(SINGLE_SOURCE_ID),
            Response.SetData(safetySourceTestData.information)
        )

        context.launchSafetyCenterActivity(withReceiverPermission = true) {
            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName("overall_severity_level_ok_title"),
                safetyCenterResourcesContext.getStringByName("overall_severity_level_ok_summary")
            )
            waitButtonDisplayed(RESCAN_BUTTON_LABEL)
        }
    }

    @Test
    fun withInformationAndNoIssues_hasContentDescriptions() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        SafetySourceReceiver.setResponse(
            Request.Refresh(SINGLE_SOURCE_ID),
            Response.SetData(safetySourceTestData.information)
        )

        context.launchSafetyCenterActivity(withReceiverPermission = true) {
            waitDisplayed(By.descContains("Security and privacy status"))
            waitNotDisplayed(By.desc("Protected by Android"))
        }
    }

    @Test
    fun withInformationIssue_doesNotHaveRescanButton() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        SafetySourceReceiver.setResponse(
            Request.Refresh(SINGLE_SOURCE_ID),
            Response.SetData(safetySourceTestData.informationWithIssue)
        )

        context.launchSafetyCenterActivity(withReceiverPermission = true) {
            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName("overall_severity_level_ok_title"),
                safetyCenterTestData.getAlertString(1)
            )
            waitButtonNotDisplayed(RESCAN_BUTTON_LABEL)
        }
    }

    @Test
    fun withRecommendationIssue_doesNotHaveRescanButton() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        SafetySourceReceiver.setResponse(
            Request.Refresh(SINGLE_SOURCE_ID),
            Response.SetData(safetySourceTestData.recommendationWithGeneralIssue)
        )

        context.launchSafetyCenterActivity(withReceiverPermission = true) {
            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName(
                    "overall_severity_level_safety_recommendation_title"
                ),
                safetyCenterTestData.getAlertString(1)
            )
            waitButtonNotDisplayed(RESCAN_BUTTON_LABEL)
        }
    }

    @Test
    fun withCriticalWarningIssue_doesNotHaveRescanButton() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        SafetySourceReceiver.setResponse(
            Request.Refresh(SINGLE_SOURCE_ID),
            Response.SetData(safetySourceTestData.criticalWithResolvingGeneralIssue)
        )

        context.launchSafetyCenterActivity(withReceiverPermission = true) {
            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName(
                    "overall_severity_level_critical_safety_warning_title"
                ),
                safetyCenterTestData.getAlertString(1)
            )
            waitButtonNotDisplayed(RESCAN_BUTTON_LABEL)
        }
    }

    @Test
    fun withKnownStatus_displaysScanningOnRescan() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        SafetySourceReceiver.setResponse(
            Request.Refresh(SINGLE_SOURCE_ID),
            Response.SetData(safetySourceTestData.information)
        )

        context.launchSafetyCenterActivity(withReceiverPermission = true) {
            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName("overall_severity_level_ok_title"),
                safetyCenterResourcesContext.getStringByName("overall_severity_level_ok_summary")
            )

            waitButtonDisplayed(RESCAN_BUTTON_LABEL) { it.click() }

            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName("scanning_title"),
                safetyCenterResourcesContext.getStringByName("loading_summary")
            )
        }
    }

    @Test
    fun rescan_updatesDataAfterScanCompletes() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        SafetySourceReceiver.setResponse(
            Request.Refresh(SINGLE_SOURCE_ID),
            Response.SetData(safetySourceTestData.information)
        )
        SafetySourceReceiver.setResponse(
            Request.Rescan(SINGLE_SOURCE_ID),
            Response.SetData(safetySourceTestData.recommendationWithGeneralIssue)
        )

        context.launchSafetyCenterActivity(withReceiverPermission = true) {
            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName("overall_severity_level_ok_title"),
                safetyCenterResourcesContext.getStringByName("overall_severity_level_ok_summary")
            )

            waitButtonDisplayed(RESCAN_BUTTON_LABEL) { it.click() }

            waitAllTextDisplayed(
                safetyCenterResourcesContext.getStringByName(
                    "overall_severity_level_safety_recommendation_title"
                ),
                safetyCenterTestData.getAlertString(1)
            )
        }
    }
}
