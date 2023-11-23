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

package android.safetycenter.hostside.device

import android.content.Context
import android.safetycenter.SafetyCenterManager
import android.safetycenter.SafetyEvent
import android.safetycenter.SafetySourceErrorDetails
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.android.compatibility.common.util.SystemUtil
import com.android.safetycenter.testing.*
import com.android.safetycenter.testing.SafetyCenterApisWithShellPermissions.reportSafetySourceErrorWithPermission
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.SOURCE_ID_1
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.SOURCE_ID_2
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.SOURCE_ID_3
import com.android.safetycenter.testing.SafetySourceIntentHandler.Request
import com.android.safetycenter.testing.SafetySourceIntentHandler.Response
import com.android.safetycenter.testing.SafetySourceReceiver.Companion.refreshSafetySourcesWithReceiverPermissionAndWait
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class SafetySourceStateCollectedLoggingHelperTests {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val safetyCenterTestHelper = SafetyCenterTestHelper(context)
    private val safetySourceTestData = SafetySourceTestData(context)
    private val safetyCenterTestConfigs = SafetyCenterTestConfigs(context)
    private val safetyCenterManager = context.getSystemService(SafetyCenterManager::class.java)!!

    @Before
    fun setUp() {
        safetyCenterTestHelper.setup()
        SafetyCenterFlags.allowStatsdLogging = true
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.multipleSourcesConfig)
    }

    @After
    fun tearDown() {
        safetyCenterTestHelper.reset()
    }

    @Test
    fun triggerStatsPull() {
        val label = 1 // Arbitrary label in [0, 16)
        val state = 3 // START
        val command = "cmd stats log-app-breadcrumb $label $state"
        SystemUtil.runShellCommandOrThrow(command)
    }

    @Test
    fun setSafetySourceData_source1() {
        safetyCenterTestHelper.setData(SOURCE_ID_1, safetySourceTestData.information)
    }

    @Test
    fun reportSafetySourceError_source1() {
        safetyCenterManager.reportSafetySourceErrorWithPermission(
            SOURCE_ID_1,
            SafetySourceErrorDetails(
                SafetyEvent.Builder(SafetyEvent.SAFETY_EVENT_TYPE_SOURCE_STATE_CHANGED).build()
            )
        )
    }

    @Test
    fun refreshAllSources_reasonPageOpen_allSuccessful() {
        simulateRefresh(
            Response.SetData(safetySourceTestData.information),
            Response.SetData(safetySourceTestData.recommendationWithAccountIssue),
            Response.SetData(safetySourceTestData.criticalWithResolvingDeviceIssue)
        )
    }

    @Test
    fun refreshAllSources_twiceSameData_allSuccessful() {
        repeat(2) {
            simulateRefresh(
                Response.SetData(safetySourceTestData.information),
                Response.SetData(safetySourceTestData.recommendationWithAccountIssue),
                Response.SetData(safetySourceTestData.criticalWithResolvingDeviceIssue)
            )
        }
    }

    @Test
    fun refreshAllSources_twiceDifferentData_onlySource1Unchanged() {
        simulateRefresh(
            Response.SetData(safetySourceTestData.information),
            Response.SetData(safetySourceTestData.recommendationWithAccountIssue),
            Response.SetData(safetySourceTestData.criticalWithResolvingDeviceIssue)
        )
        simulateRefresh(
            Response.SetData(safetySourceTestData.information),
            Response.SetData(safetySourceTestData.information),
            Response.SetData(safetySourceTestData.information)
        )
    }

    @Test
    fun refreshAllSources_reasonPageOpen_oneError() {
        simulateRefresh(
            Response.SetData(safetySourceTestData.information),
            Response.SetData(safetySourceTestData.information),
            Response.Error
        )
    }

    @Test
    fun refreshAllSources_reasonPageOpen_oneSuccessOneErrorOneTimeout() {
        SafetyCenterFlags.setAllRefreshTimeoutsTo(Coroutines.TIMEOUT_SHORT)
        simulateRefresh(Response.SetData(safetySourceTestData.information), Response.Error, null)
    }

    @Test
    fun refreshAllSources_reasonButtonClick_oneSuccessOneErrorOneTimeout() {
        SafetyCenterFlags.setAllRefreshTimeoutsTo(Coroutines.TIMEOUT_SHORT)
        simulateRefresh(
            Response.SetData(safetySourceTestData.information),
            Response.Error,
            null,
            refreshReason = SafetyCenterManager.REFRESH_REASON_RESCAN_BUTTON_CLICK
        )
    }

    private fun simulateRefresh(
        source1Response: Response?,
        source2Response: Response?,
        source3Response: Response?,
        refreshReason: Int = SafetyCenterManager.REFRESH_REASON_PAGE_OPEN
    ) {
        if (source1Response != null) {
            SafetySourceReceiver.setResponse(Request.Refresh(SOURCE_ID_1), source1Response)
        }
        if (source2Response != null) {
            SafetySourceReceiver.setResponse(Request.Refresh(SOURCE_ID_2), source2Response)
        }
        if (source3Response != null) {
            SafetySourceReceiver.setResponse(Request.Refresh(SOURCE_ID_3), source3Response)
        }
        safetyCenterManager.refreshSafetySourcesWithReceiverPermissionAndWait(refreshReason)
        // Give time for responses to all sources
        Thread.sleep(Coroutines.TIMEOUT_SHORT.toMillis())
    }
}
