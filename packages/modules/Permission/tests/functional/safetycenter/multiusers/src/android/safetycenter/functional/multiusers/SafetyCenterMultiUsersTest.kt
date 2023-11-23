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

package android.safetycenter.functional.multiusers

import android.Manifest.permission.INTERACT_ACROSS_USERS
import android.Manifest.permission.INTERACT_ACROSS_USERS_FULL
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.UserHandle
import android.safetycenter.SafetyCenterData
import android.safetycenter.SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING
import android.safetycenter.SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNKNOWN
import android.safetycenter.SafetyCenterEntry.ENTRY_SEVERITY_LEVEL_UNSPECIFIED
import android.safetycenter.SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION
import android.safetycenter.SafetyCenterEntry.SEVERITY_UNSPECIFIED_ICON_TYPE_PRIVACY
import android.safetycenter.SafetyCenterEntryGroup
import android.safetycenter.SafetyCenterEntryOrGroup
import android.safetycenter.SafetyCenterManager
import android.safetycenter.SafetyCenterStaticEntry
import android.safetycenter.SafetyCenterStaticEntryGroup
import android.safetycenter.SafetyEvent
import android.safetycenter.SafetySourceData
import androidx.test.core.app.ApplicationProvider
import com.android.bedstead.harrier.BedsteadJUnit4
import com.android.bedstead.harrier.DeviceState
import com.android.bedstead.harrier.annotations.EnsureHasAdditionalUser
import com.android.bedstead.harrier.annotations.EnsureHasCloneProfile
import com.android.bedstead.harrier.annotations.EnsureHasNoWorkProfile
import com.android.bedstead.harrier.annotations.EnsureHasWorkProfile
import com.android.bedstead.harrier.annotations.Postsubmit
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasDeviceOwner
import com.android.bedstead.nene.TestApis
import com.android.bedstead.nene.types.OptionalBoolean.TRUE
import com.android.compatibility.common.util.DisableAnimationRule
import com.android.compatibility.common.util.FreezeRotationRule
import com.android.safetycenter.resources.SafetyCenterResourcesContext
import com.android.safetycenter.testing.SafetyCenterActivityLauncher.launchSafetyCenterActivity
import com.android.safetycenter.testing.SafetyCenterApisWithShellPermissions.getSafetyCenterDataWithPermission
import com.android.safetycenter.testing.SafetyCenterApisWithShellPermissions.getSafetySourceDataWithPermission
import com.android.safetycenter.testing.SafetyCenterApisWithShellPermissions.setSafetySourceDataWithPermission
import com.android.safetycenter.testing.SafetyCenterFlags.deviceSupportsSafetyCenter
import com.android.safetycenter.testing.SafetyCenterTestConfigs
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.ACTION_TEST_ACTIVITY
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.DYNAMIC_BAREBONE_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.DYNAMIC_DISABLED_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.DYNAMIC_GROUP_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.DYNAMIC_HIDDEN_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.DYNAMIC_IN_STATELESS_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.ISSUE_ONLY_ALL_OPTIONAL_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.ISSUE_ONLY_ALL_PROFILE_SOURCE_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.ISSUE_ONLY_BAREBONE_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.ISSUE_ONLY_GROUP_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.ISSUE_ONLY_IN_STATELESS_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.MIXED_STATELESS_GROUP_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.SINGLE_SOURCE_ALL_PROFILE_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.SINGLE_SOURCE_GROUP_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.SINGLE_SOURCE_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.STATIC_ALL_OPTIONAL_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.STATIC_BAREBONE_ID
import com.android.safetycenter.testing.SafetyCenterTestConfigs.Companion.STATIC_GROUP_ID
import com.android.safetycenter.testing.SafetyCenterTestData
import com.android.safetycenter.testing.SafetyCenterTestData.Companion.withoutExtras
import com.android.safetycenter.testing.SafetyCenterTestHelper
import com.android.safetycenter.testing.SafetySourceTestData
import com.android.safetycenter.testing.SafetySourceTestData.Companion.EVENT_SOURCE_STATE_CHANGED
import com.android.safetycenter.testing.ShellPermissions.callWithShellPermissionIdentity
import com.android.safetycenter.testing.UiTestHelper.waitAllTextDisplayed
import com.android.safetycenter.testing.UiTestHelper.waitAllTextNotDisplayed
import com.google.common.base.Preconditions.checkState
import com.google.common.truth.Truth.assertThat
import kotlin.test.assertFailsWith
import org.junit.After
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.ClassRule
import org.junit.Ignore
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Functional tests for our APIs and UI on a device with multiple users. e.g. with a managed or
 * secondary user(s).
 */
@RunWith(BedsteadJUnit4::class)
class SafetyCenterMultiUsersTest {

    companion object {
        @JvmField @ClassRule @Rule val deviceState: DeviceState = DeviceState()
    }

    @get:Rule val disableAnimationRule = DisableAnimationRule()

    @get:Rule val freezeRotationRule = FreezeRotationRule()

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val safetyCenterResourcesContext = SafetyCenterResourcesContext.forTests(context)
    private val safetyCenterTestHelper = SafetyCenterTestHelper(context)
    private val safetySourceTestData = SafetySourceTestData(context)
    private val safetyCenterTestData = SafetyCenterTestData(context)
    private val safetyCenterTestConfigs = SafetyCenterTestConfigs(context)
    private val safetyCenterManager = context.getSystemService(SafetyCenterManager::class.java)!!

    // JUnit's Assume is not supported in @BeforeClass by the CTS tests runner, so this is used to
    // manually skip the setup and teardown methods.
    private val shouldRunTests = context.deviceSupportsSafetyCenter()
    private var inQuietMode = false

    private val primaryProfileOnlyIssues =
        listOf(
            safetyCenterTestData.safetyCenterIssueCritical(
                DYNAMIC_BAREBONE_ID,
                groupId = DYNAMIC_GROUP_ID
            ),
            safetyCenterTestData.safetyCenterIssueCritical(
                ISSUE_ONLY_BAREBONE_ID,
                attributionTitle = null,
                groupId = ISSUE_ONLY_GROUP_ID
            ),
            safetyCenterTestData.safetyCenterIssueRecommendation(
                DYNAMIC_DISABLED_ID,
                groupId = DYNAMIC_GROUP_ID
            ),
            safetyCenterTestData.safetyCenterIssueRecommendation(
                ISSUE_ONLY_ALL_OPTIONAL_ID,
                attributionTitle = null,
                groupId = ISSUE_ONLY_GROUP_ID
            ),
            safetyCenterTestData.safetyCenterIssueInformation(
                DYNAMIC_IN_STATELESS_ID,
                groupId = MIXED_STATELESS_GROUP_ID
            ),
            safetyCenterTestData.safetyCenterIssueInformation(
                ISSUE_ONLY_IN_STATELESS_ID,
                groupId = MIXED_STATELESS_GROUP_ID
            )
        )

    private val dynamicBareboneDefault =
        safetyCenterTestData.safetyCenterEntryDefault(DYNAMIC_BAREBONE_ID)

    private val dynamicBareboneUpdated =
        safetyCenterTestData.safetyCenterEntryCritical(DYNAMIC_BAREBONE_ID)

    private val dynamicDisabledDefault =
        safetyCenterTestData
            .safetyCenterEntryDefaultBuilder(DYNAMIC_DISABLED_ID)
            .setPendingIntent(null)
            .setEnabled(false)
            .build()

    private val dynamicDisabledUpdated =
        safetyCenterTestData.safetyCenterEntryRecommendation(DYNAMIC_DISABLED_ID)

    private val dynamicDisabledForWorkDefaultBuilder
        get() =
            safetyCenterTestData
                .safetyCenterEntryDefaultBuilder(
                    DYNAMIC_DISABLED_ID,
                    userId = deviceState.workProfile().id(),
                    title = "Paste"
                )
                .setPendingIntent(null)
                .setEnabled(false)

    private val dynamicDisabledForWorkDefault
        get() = dynamicDisabledForWorkDefaultBuilder.build()

    private val dynamicDisabledForWorkPaused
        get() =
            dynamicDisabledForWorkDefaultBuilder
                // TODO(b/233188021): This needs to use the Enterprise API to override the "work"
                //  keyword.
                .setSummary(safetyCenterResourcesContext.getStringByName("work_profile_paused"))
                .build()

    private val dynamicDisabledForWorkUpdated
        get() = safetyCenterEntryOkForWork(DYNAMIC_DISABLED_ID, deviceState.workProfile().id())

    private val dynamicHiddenUpdated =
        safetyCenterTestData.safetyCenterEntryUnspecified(DYNAMIC_HIDDEN_ID, pendingIntent = null)

    private val dynamicHiddenForWorkUpdated
        get() = safetyCenterEntryOkForWork(DYNAMIC_HIDDEN_ID, deviceState.workProfile().id())

    private val staticGroupBuilder =
        SafetyCenterEntryGroup.Builder(STATIC_GROUP_ID, "OK")
            .setSeverityLevel(ENTRY_SEVERITY_LEVEL_UNSPECIFIED)
            .setSeverityUnspecifiedIconType(SEVERITY_UNSPECIFIED_ICON_TYPE_PRIVACY)
            .setSummary("OK")

    private val staticBarebone =
        safetyCenterTestData
            .safetyCenterEntryDefaultStaticBuilder(STATIC_BAREBONE_ID)
            .setSummary(null)
            .build()

    private val staticAllOptional =
        safetyCenterTestData.safetyCenterEntryDefaultStaticBuilder(STATIC_ALL_OPTIONAL_ID).build()

    private val staticAllOptionalForWorkBuilder
        get() =
            safetyCenterTestData
                .safetyCenterEntryDefaultStaticBuilder(
                    STATIC_ALL_OPTIONAL_ID,
                    userId = deviceState.workProfile().id(),
                    title = "Paste"
                )
                .setPendingIntent(
                    createTestActivityRedirectPendingIntentForUser(
                        deviceState.workProfile().userHandle()
                    )
                )

    private val staticAllOptionalForWork
        get() = staticAllOptionalForWorkBuilder.build()

    private val staticAllOptionalForWorkPaused
        get() =
            staticAllOptionalForWorkBuilder
                // TODO(b/233188021): This needs to use the Enterprise API to override the "work"
                //  keyword.
                .setSummary(safetyCenterResourcesContext.getStringByName("work_profile_paused"))
                .setEnabled(false)
                .build()

    private val staticEntry =
        SafetyCenterStaticEntry.Builder("OK")
            .setSummary("OK")
            .setPendingIntent(safetySourceTestData.testActivityRedirectPendingIntent)
            .build()

    private val staticEntryUpdated =
        SafetyCenterStaticEntry.Builder("Unspecified title")
            .setSummary("Unspecified summary")
            .setPendingIntent(safetySourceTestData.testActivityRedirectPendingIntent)
            .build()

    private val staticEntryForWorkBuilder
        get() =
            SafetyCenterStaticEntry.Builder("Paste")
                .setSummary("OK")
                .setPendingIntent(
                    createTestActivityRedirectPendingIntentForUser(
                        deviceState.workProfile().userHandle()
                    )
                )

    private val staticEntryForWork
        get() = staticEntryForWorkBuilder.build()

    private val staticEntryForWorkPaused
        get() =
            staticEntryForWorkBuilder
                // TODO(b/233188021): This needs to use the Enterprise API to override the "work"
                //  keyword.
                .setSummary(safetyCenterResourcesContext.getStringByName("work_profile_paused"))
                .build()

    private val staticEntryForWorkUpdated =
        SafetyCenterStaticEntry.Builder("Unspecified title for Work")
            .setSummary("Unspecified summary")
            .setPendingIntent(safetySourceTestData.testActivityRedirectPendingIntent)
            .build()

    private val safetyCenterDataForAdditionalUser
        get() =
            SafetyCenterData(
                safetyCenterTestData.safetyCenterStatusUnknown,
                emptyList(),
                listOf(
                    SafetyCenterEntryOrGroup(
                        safetyCenterTestData.safetyCenterEntryDefault(
                            SINGLE_SOURCE_ALL_PROFILE_ID,
                            deviceState.additionalUser().id(),
                            pendingIntent =
                                createTestActivityRedirectPendingIntentForUser(
                                    deviceState.additionalUser().userHandle()
                                )
                        )
                    )
                ),
                emptyList()
            )

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
        resetQuietMode()
    }

    @Test
    @EnsureHasWorkProfile
    @Ignore
    // Tests that check the UI takes a lot of time and they might get timeout in the postsubmits.
    // TODO(b/242999951): Write this test using the APIs instead of checking the UI.
    fun launchActivity_withProfileOwner_displaysWorkPolicyInfo() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.workPolicyInfoConfig)

        findWorkPolicyInfo()
    }

    @Test
    @EnsureHasDeviceOwner
    @Ignore
    // Tests that check the UI takes a lot of time and they might get timeout in the postsubmits.
    // TODO(b/242999951): Write this test using the APIs instead of checking the UI.
    fun launchActivity_withDeviceOwner_displaysWorkPolicyInfo() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.workPolicyInfoConfig)

        findWorkPolicyInfo()
    }

    @Test
    @EnsureHasWorkProfile
    @Ignore
    // Tests that check the UI takes a lot of time and they might get timeout in the postsubmits.
    // TODO(b/242999951): Write this test using the APIs instead of checking the UI.
    fun launchActivity_withQuietModeEnabled_shouldNotDisplayWorkPolicyInfo() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.workPolicyInfoConfig)

        findWorkPolicyInfo()
        setQuietMode(true)
        context.launchSafetyCenterActivity { waitAllTextNotDisplayed("Your work policy info") }
    }

    @Test
    @Ignore
    // Test involving toggling of quiet mode are flaky.
    // TODO(b/237365018): Re-enable them back once we figure out a way to make them stable.
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun getSafetySourceData_withQuietModeEnabled_dataIsNotCleared() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForWork
        )

        setQuietMode(true)
        val apiSafetySourceDataForWork =
            managedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            )

        assertThat(apiSafetySourceDataForWork).isEqualTo(dataForWork)
    }

    @Test
    @EnsureHasAdditionalUser(installInstrumentedApp = TRUE)
    @Postsubmit(reason = "Test takes too much time to setup")
    fun getSafetySourceData_afterAdditionalUserRemoved_returnsNull() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val additionalUserSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.additionalUser().userHandle())
        val dataForAdditionalUser = safetySourceTestData.information
        additionalUserSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForAdditionalUser
        )
        checkState(
            additionalUserSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            ) == dataForAdditionalUser
        )

        deviceState.additionalUser().remove()

        assertThat(
                additionalUserSafetyCenterManager
                    .getSafetySourceDataWithInteractAcrossUsersPermission(
                        SINGLE_SOURCE_ALL_PROFILE_ID
                    )
            )
            .isNull()
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun getSafetySourceData_withoutInteractAcrossUserPermission_shouldThrowError() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)

        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        assertFailsWith(SecurityException::class) {
            managedSafetyCenterManager.getSafetySourceData(SINGLE_SOURCE_ALL_PROFILE_ID)
        }
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun getSafetySourceData_withoutAppInstalledForWorkProfile_shouldReturnNull() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForWork
        )
        TestApis.packages().find(context.packageName).uninstall(deviceState.workProfile())

        val safetySourceData =
            managedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            )

        assertThat(safetySourceData).isNull()
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun getSafetySourceData_withRemovedProfile_shouldReturnNull() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForWork
        )
        deviceState.workProfile().remove()

        val safetySourceData =
            managedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            )

        assertThat(safetySourceData).isNull()
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    @Ignore
    // Test involving toggling of quiet mode are flaky.
    fun getSafetySourceData_withProfileInQuietMode_shouldReturnData() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForWork
        )
        setQuietMode(true)

        val safetySourceData =
            managedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            )

        assertThat(safetySourceData).isEqualTo(dataForWork)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasNoWorkProfile
    fun getSafetyCenterData_withComplexConfigWithoutWorkProfile_returnsPrimaryDataFromConfig() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.complexAllProfileConfig)

        val apiSafetyCenterData = safetyCenterManager.getSafetyCenterDataWithPermission()

        val safetyCenterDataFromComplexConfig =
            SafetyCenterData(
                safetyCenterTestData.safetyCenterStatusUnknown,
                emptyList(),
                listOf(
                    SafetyCenterEntryOrGroup(
                        SafetyCenterEntryGroup.Builder(DYNAMIC_GROUP_ID, "OK")
                            .setSeverityLevel(ENTRY_SEVERITY_LEVEL_UNKNOWN)
                            .setSummary(
                                safetyCenterResourcesContext.getStringByName(
                                    "group_unknown_summary"
                                )
                            )
                            .setEntries(listOf(dynamicBareboneDefault, dynamicDisabledDefault))
                            .setSeverityUnspecifiedIconType(
                                SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION
                            )
                            .build()
                    ),
                    SafetyCenterEntryOrGroup(
                        staticGroupBuilder
                            .setEntries(listOf(staticBarebone, staticAllOptional))
                            .build()
                    )
                ),
                listOf(SafetyCenterStaticEntryGroup("OK", listOf(staticEntry, staticEntry)))
            )
        assertThat(apiSafetyCenterData.withoutExtras()).isEqualTo(safetyCenterDataFromComplexConfig)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun getSafetyCenterData_withComplexConfigWithoutDataProvided_returnsDataFromConfig() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.complexAllProfileConfig)

        val apiSafetyCenterData = safetyCenterManager.getSafetyCenterDataWithPermission()

        val safetyCenterDataFromComplexConfig =
            SafetyCenterData(
                safetyCenterTestData.safetyCenterStatusUnknown,
                emptyList(),
                listOf(
                    SafetyCenterEntryOrGroup(
                        SafetyCenterEntryGroup.Builder(DYNAMIC_GROUP_ID, "OK")
                            .setSeverityLevel(ENTRY_SEVERITY_LEVEL_UNKNOWN)
                            .setSummary(
                                safetyCenterResourcesContext.getStringByName(
                                    "group_unknown_summary"
                                )
                            )
                            .setEntries(
                                listOf(
                                    dynamicBareboneDefault,
                                    dynamicDisabledDefault,
                                    dynamicDisabledForWorkDefault
                                )
                            )
                            .setSeverityUnspecifiedIconType(
                                SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION
                            )
                            .build()
                    ),
                    SafetyCenterEntryOrGroup(
                        staticGroupBuilder
                            .setEntries(
                                listOf(staticBarebone, staticAllOptional, staticAllOptionalForWork)
                            )
                            .build()
                    )
                ),
                listOf(
                    SafetyCenterStaticEntryGroup(
                        "OK",
                        listOf(staticEntry, staticEntryForWork, staticEntry, staticEntryForWork)
                    )
                )
            )
        assertThat(apiSafetyCenterData.withoutExtras()).isEqualTo(safetyCenterDataFromComplexConfig)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun getSafetyCenterData_withComplexConfigWithPrimaryDataProvided_returnsPrimaryDataProvided() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.complexAllProfileConfig)
        updatePrimaryProfileSources()

        val apiSafetyCenterData = safetyCenterManager.getSafetyCenterDataWithPermission()

        val safetyCenterDataFromComplexConfig =
            SafetyCenterData(
                safetyCenterTestData.safetyCenterStatusCritical(6),
                primaryProfileOnlyIssues,
                listOf(
                    SafetyCenterEntryOrGroup(
                        SafetyCenterEntryGroup.Builder(DYNAMIC_GROUP_ID, "OK")
                            .setSeverityLevel(ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING)
                            .setSummary("Critical summary")
                            .setEntries(
                                listOf(
                                    dynamicBareboneUpdated,
                                    dynamicDisabledUpdated,
                                    dynamicDisabledForWorkDefault,
                                    dynamicHiddenUpdated
                                )
                            )
                            .setSeverityUnspecifiedIconType(
                                SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION
                            )
                            .build()
                    ),
                    SafetyCenterEntryOrGroup(
                        staticGroupBuilder
                            .setEntries(
                                listOf(staticBarebone, staticAllOptional, staticAllOptionalForWork)
                            )
                            .build()
                    )
                ),
                listOf(
                    SafetyCenterStaticEntryGroup(
                        "OK",
                        listOf(
                            staticEntryUpdated,
                            staticEntryForWork,
                            staticEntry,
                            staticEntryForWork
                        )
                    )
                )
            )
        assertThat(apiSafetyCenterData.withoutExtras()).isEqualTo(safetyCenterDataFromComplexConfig)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun getSafetyCenterData_withComplexConfigWithAllDataProvided_returnsAllDataProvided() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.complexAllProfileConfig)
        updatePrimaryProfileSources()
        updateWorkProfileSources()

        val apiSafetyCenterData = safetyCenterManager.getSafetyCenterDataWithPermission()

        val managedUserId = deviceState.workProfile().id()
        val safetyCenterDataFromComplexConfig =
            SafetyCenterData(
                safetyCenterTestData.safetyCenterStatusCritical(11),
                listOf(
                    safetyCenterTestData.safetyCenterIssueCritical(
                        DYNAMIC_BAREBONE_ID,
                        groupId = DYNAMIC_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueCritical(
                        ISSUE_ONLY_BAREBONE_ID,
                        attributionTitle = null,
                        groupId = ISSUE_ONLY_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueRecommendation(
                        DYNAMIC_DISABLED_ID,
                        groupId = DYNAMIC_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueRecommendation(
                        ISSUE_ONLY_ALL_OPTIONAL_ID,
                        attributionTitle = null,
                        groupId = ISSUE_ONLY_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueInformation(
                        DYNAMIC_IN_STATELESS_ID,
                        groupId = MIXED_STATELESS_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueInformation(
                        ISSUE_ONLY_IN_STATELESS_ID,
                        groupId = MIXED_STATELESS_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueInformation(
                        DYNAMIC_DISABLED_ID,
                        managedUserId,
                        groupId = DYNAMIC_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueInformation(
                        DYNAMIC_HIDDEN_ID,
                        managedUserId,
                        groupId = DYNAMIC_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueInformation(
                        ISSUE_ONLY_ALL_OPTIONAL_ID,
                        managedUserId,
                        attributionTitle = null,
                        groupId = ISSUE_ONLY_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueInformation(
                        DYNAMIC_IN_STATELESS_ID,
                        managedUserId,
                        groupId = MIXED_STATELESS_GROUP_ID
                    ),
                    safetyCenterTestData.safetyCenterIssueInformation(
                        ISSUE_ONLY_IN_STATELESS_ID,
                        managedUserId,
                        groupId = MIXED_STATELESS_GROUP_ID
                    )
                ),
                listOf(
                    SafetyCenterEntryOrGroup(
                        SafetyCenterEntryGroup.Builder(DYNAMIC_GROUP_ID, "OK")
                            .setSeverityLevel(ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING)
                            .setSummary("Critical summary")
                            .setEntries(
                                listOf(
                                    dynamicBareboneUpdated,
                                    dynamicDisabledUpdated,
                                    dynamicDisabledForWorkUpdated,
                                    dynamicHiddenUpdated,
                                    dynamicHiddenForWorkUpdated
                                )
                            )
                            .setSeverityUnspecifiedIconType(
                                SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION
                            )
                            .build()
                    ),
                    SafetyCenterEntryOrGroup(
                        staticGroupBuilder
                            .setEntries(
                                listOf(staticBarebone, staticAllOptional, staticAllOptionalForWork)
                            )
                            .build()
                    )
                ),
                listOf(
                    SafetyCenterStaticEntryGroup(
                        "OK",
                        listOf(
                            staticEntryUpdated,
                            staticEntryForWorkUpdated,
                            staticEntry,
                            staticEntryForWork
                        )
                    )
                )
            )
        assertThat(apiSafetyCenterData.withoutExtras()).isEqualTo(safetyCenterDataFromComplexConfig)
    }

    @Test
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    @Ignore
    // Test involving toggling of quiet mode are flaky.
    // TODO(b/237365018): Re-enable them back once we figure out a way to make them stable.
    fun getSafetyCenterData_withQuietMode_shouldHaveWorkProfilePausedSummaryAndNoWorkIssues() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.complexAllProfileConfig)
        updatePrimaryProfileSources()
        updateWorkProfileSources()

        setQuietMode(true)
        val apiSafetyCenterData = safetyCenterManager.getSafetyCenterDataWithPermission()

        val safetyCenterDataFromComplexConfig =
            SafetyCenterData(
                safetyCenterTestData.safetyCenterStatusCritical(6),
                primaryProfileOnlyIssues,
                listOf(
                    SafetyCenterEntryOrGroup(
                        SafetyCenterEntryGroup.Builder(DYNAMIC_GROUP_ID, "OK")
                            .setSeverityLevel(ENTRY_SEVERITY_LEVEL_CRITICAL_WARNING)
                            .setSummary("Critical summary")
                            .setEntries(
                                listOf(
                                    dynamicBareboneUpdated,
                                    dynamicDisabledUpdated,
                                    dynamicDisabledForWorkPaused,
                                    dynamicHiddenUpdated
                                )
                            )
                            .setSeverityUnspecifiedIconType(
                                SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION
                            )
                            .build()
                    ),
                    SafetyCenterEntryOrGroup(
                        staticGroupBuilder
                            .setEntries(
                                listOf(
                                    staticBarebone,
                                    staticAllOptional,
                                    staticAllOptionalForWorkPaused
                                )
                            )
                            .build()
                    )
                ),
                listOf(
                    SafetyCenterStaticEntryGroup(
                        "OK",
                        listOf(
                            staticEntryUpdated,
                            staticEntryForWorkPaused,
                            staticEntry,
                            staticEntryForWorkPaused
                        )
                    )
                )
            )
        assertThat(apiSafetyCenterData.withoutExtras()).isEqualTo(safetyCenterDataFromComplexConfig)
    }

    @Test
    @EnsureHasAdditionalUser(installInstrumentedApp = TRUE)
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    @Postsubmit(reason = "Test takes too much time to setup")
    fun getSafetyCenterData_withDataForDifferentUserProfileGroup_shouldBeUnaffected() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val dataForPrimaryUser = safetySourceTestData.information
        safetyCenterTestHelper.setData(SINGLE_SOURCE_ALL_PROFILE_ID, dataForPrimaryUser)
        val dataForPrimaryUserWorkProfile = safetySourceTestData.informationWithIssueForWork
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForPrimaryUserWorkProfile
        )

        val additionalUserSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.additionalUser().userHandle())
        val apiSafetyCenterDataForAdditionalUser =
            additionalUserSafetyCenterManager.getSafetyCenterDataWithInteractAcrossUsersPermission()

        assertThat(apiSafetyCenterDataForAdditionalUser)
            .isEqualTo(safetyCenterDataForAdditionalUser)
    }

    @Test
    @Ignore // Removing a managed profile causes a refresh, which makes some tests flaky.
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun getSafetyCenterData_afterManagedProfileRemoved_returnsDefaultData() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        val safetyCenterDataWithWorkProfile =
            SafetyCenterData(
                safetyCenterTestData.safetyCenterStatusUnknown,
                emptyList(),
                listOf(
                    SafetyCenterEntryOrGroup(
                        SafetyCenterEntryGroup.Builder(SINGLE_SOURCE_GROUP_ID, "OK")
                            .setSeverityLevel(ENTRY_SEVERITY_LEVEL_UNKNOWN)
                            .setSummary(
                                safetyCenterResourcesContext.getStringByName(
                                    "group_unknown_summary"
                                )
                            )
                            .setEntries(
                                listOf(
                                    safetyCenterTestData.safetyCenterEntryDefault(
                                        SINGLE_SOURCE_ALL_PROFILE_ID
                                    ),
                                    safetyCenterTestData.safetyCenterEntryDefault(
                                        SINGLE_SOURCE_ALL_PROFILE_ID,
                                        deviceState.workProfile().id(),
                                        title = "Paste",
                                        pendingIntent =
                                            createTestActivityRedirectPendingIntentForUser(
                                                deviceState.workProfile().userHandle()
                                            )
                                    )
                                )
                            )
                            .setSeverityUnspecifiedIconType(
                                SEVERITY_UNSPECIFIED_ICON_TYPE_NO_RECOMMENDATION
                            )
                            .build()
                    )
                ),
                emptyList()
            )
        checkState(
            safetyCenterManager.getSafetyCenterDataWithPermission() ==
                safetyCenterDataWithWorkProfile
        )
        checkState(
            managedSafetyCenterManager.getSafetyCenterDataWithInteractAcrossUsersPermission() ==
                safetyCenterDataWithWorkProfile
        )

        deviceState.workProfile().remove()

        val safetyCenterDataForPrimaryUser =
            SafetyCenterData(
                safetyCenterTestData.safetyCenterStatusUnknown,
                emptyList(),
                listOf(
                    SafetyCenterEntryOrGroup(
                        safetyCenterTestData.safetyCenterEntryDefault(SINGLE_SOURCE_ALL_PROFILE_ID)
                    )
                ),
                emptyList()
            )
        assertThat(safetyCenterManager.getSafetyCenterDataWithPermission())
            .isEqualTo(safetyCenterDataForPrimaryUser)
        assertThat(
                managedSafetyCenterManager.getSafetyCenterDataWithInteractAcrossUsersPermission()
            )
            .isEqualTo(SafetyCenterTestData.DEFAULT)
    }

    @Test
    @EnsureHasAdditionalUser(installInstrumentedApp = TRUE)
    @Postsubmit(reason = "Test takes too much time to setup")
    fun getSafetyCenterData_afterAdditionalUserRemoved_returnsDefaultData() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val additionalUserSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.additionalUser().userHandle())
        checkState(
            additionalUserSafetyCenterManager
                .getSafetyCenterDataWithInteractAcrossUsersPermission() ==
                safetyCenterDataForAdditionalUser
        )

        deviceState.additionalUser().remove()

        assertThat(
                additionalUserSafetyCenterManager
                    .getSafetyCenterDataWithInteractAcrossUsersPermission()
            )
            .isEqualTo(SafetyCenterTestData.DEFAULT)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun setSafetySourceData_primaryProfileIssueOnlySource_shouldNotBeAbleToSetDataToWorkProfile() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.issueOnlySourceConfig)
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        val dataForWork =
            SafetySourceTestData.issuesOnly(safetySourceTestData.criticalResolvingGeneralIssue)

        assertFailsWith(IllegalArgumentException::class) {
            managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
                ISSUE_ONLY_ALL_OPTIONAL_ID,
                dataForWork
            )
        }
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun setSafetySourceData_withoutInteractAcrossUserPermission_shouldThrowError() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())

        assertFailsWith(SecurityException::class) {
            managedSafetyCenterManager.setSafetySourceData(
                SINGLE_SOURCE_ALL_PROFILE_ID,
                dataForWork,
                EVENT_SOURCE_STATE_CHANGED
            )
        }
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun setSafetySourceData_withoutAppInstalledForWorkProfile_shouldNoOp() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        TestApis.packages().find(context.packageName).uninstall(deviceState.workProfile())

        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForWork
        )

        val safetySourceData =
            managedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            )
        assertThat(safetySourceData).isNull()
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun setSafetySourceData_withRemovedProfile_shouldNoOp() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        deviceState.workProfile().remove()

        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForWork
        )

        val safetySourceData =
            managedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            )
        assertThat(safetySourceData).isNull()
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasCloneProfile(installInstrumentedApp = TRUE)
    fun setSafetySourceData_withUnsupportedProfile_shouldNoOp() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val dataForClone = safetySourceTestData.informationWithIssueForWork
        val clonedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.cloneProfile().userHandle())

        clonedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForClone
        )

        val safetySourceData =
            clonedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            )
        assertThat(safetySourceData).isNull()
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    @Ignore
    // Test involving toggling of quiet mode are flaky.
    fun setSafetySourceData_withProfileInQuietMode_shouldSetData() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        setQuietMode(true)

        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForWork
        )

        val safetySourceData =
            managedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            )
        assertThat(safetySourceData).isEqualTo(dataForWork)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun setSafetySourceData_issuesOnlySourceWithWorkProfile_shouldBeAbleToSetData() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.issueOnlySourceAllProfileConfig)

        val dataForPrimaryUser =
            SafetySourceTestData.issuesOnly(safetySourceTestData.recommendationGeneralIssue)
        safetyCenterTestHelper.setData(ISSUE_ONLY_ALL_PROFILE_SOURCE_ID, dataForPrimaryUser)
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        val dataForWorkProfile =
            SafetySourceTestData.issuesOnly(safetySourceTestData.criticalResolvingGeneralIssue)
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            ISSUE_ONLY_ALL_PROFILE_SOURCE_ID,
            dataForWorkProfile
        )

        val apiSafetySourceData =
            safetyCenterManager.getSafetySourceDataWithPermission(ISSUE_ONLY_ALL_PROFILE_SOURCE_ID)
        val apiSafetySourceDataForWork =
            managedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                ISSUE_ONLY_ALL_PROFILE_SOURCE_ID
            )
        assertThat(apiSafetySourceData).isEqualTo(dataForPrimaryUser)
        assertThat(apiSafetySourceDataForWork).isEqualTo(dataForWorkProfile)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun setSafetySourceData_primaryProfileSource_shouldNotBeAbleToSetDataToWorkProfile() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)

        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        assertFailsWith(IllegalArgumentException::class) {
            managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ID,
                dataForWork
            )
        }
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasWorkProfile(installInstrumentedApp = TRUE)
    fun setSafetySourceData_sourceWithWorkProfile_shouldBeAbleToSetData() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceAllProfileConfig)

        val dataForPrimaryUser = safetySourceTestData.information
        safetyCenterTestHelper.setData(SINGLE_SOURCE_ALL_PROFILE_ID, dataForPrimaryUser)
        val dataForWork = safetySourceTestData.informationWithIssueForWork
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ALL_PROFILE_ID,
            dataForWork
        )

        val apiSafetySourceData =
            safetyCenterManager.getSafetySourceDataWithPermission(SINGLE_SOURCE_ALL_PROFILE_ID)
        val apiSafetySourceDataForWork =
            managedSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ALL_PROFILE_ID
            )
        assertThat(apiSafetySourceData).isEqualTo(dataForPrimaryUser)
        assertThat(apiSafetySourceDataForWork).isEqualTo(dataForWork)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasAdditionalUser(installInstrumentedApp = TRUE)
    fun setSafetySourceData_forStoppedUser_shouldSetData() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)
        deviceState.additionalUser().stop()

        val dataForPrimaryUser = safetySourceTestData.unspecified
        val additionalUserSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.additionalUser().userHandle())
        additionalUserSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ID,
            dataForPrimaryUser
        )

        val apiSafetySourceData =
            additionalUserSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ID
            )
        assertThat(apiSafetySourceData).isEqualTo(dataForPrimaryUser)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasAdditionalUser(installInstrumentedApp = TRUE)
    fun setSafetySourceData_forBothPrimaryAdditionalUser_shouldSetData() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)

        val dataForPrimaryUser = safetySourceTestData.information
        safetyCenterTestHelper.setData(SINGLE_SOURCE_ID, dataForPrimaryUser)
        val dataForAdditionalUser = safetySourceTestData.unspecified
        val additionalUserSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.additionalUser().userHandle())
        additionalUserSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ID,
            dataForAdditionalUser
        )

        val apiSafetySourceDataForPrimaryUser =
            safetyCenterManager.getSafetySourceDataWithPermission(SINGLE_SOURCE_ID)
        val apiSafetySourceDataForAdditionalUser =
            additionalUserSafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
                SINGLE_SOURCE_ID
            )
        assertThat(apiSafetySourceDataForPrimaryUser).isEqualTo(dataForPrimaryUser)
        assertThat(apiSafetySourceDataForAdditionalUser).isEqualTo(dataForAdditionalUser)
    }

    @Test
    @Postsubmit(reason = "Test takes too much time to setup")
    @EnsureHasAdditionalUser(installInstrumentedApp = TRUE)
    fun setSafetySourceData_forAdditionalUser_shouldNotAffectDataForPrimaryUser() {
        safetyCenterTestHelper.setConfig(safetyCenterTestConfigs.singleSourceConfig)

        val dataForAdditionalUser = safetySourceTestData.unspecified
        val additionalUserSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.additionalUser().userHandle())
        additionalUserSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            SINGLE_SOURCE_ID,
            dataForAdditionalUser
        )

        val apiSafetySourceDataForPrimaryUser =
            safetyCenterManager.getSafetySourceDataWithPermission(SINGLE_SOURCE_ID)
        assertThat(apiSafetySourceDataForPrimaryUser).isEqualTo(null)
    }

    private fun findWorkPolicyInfo() {
        context.launchSafetyCenterActivity {
            // TODO(b/233188021): This needs to use the Enterprise API to override the "work"
            //  keyword.
            waitAllTextDisplayed("Your work policy info", "Settings managed by your IT admin")
        }
    }

    private fun getSafetyCenterManagerForUser(userHandle: UserHandle): SafetyCenterManager {
        val contextForUser = getContextForUser(userHandle)
        return contextForUser.getSystemService(SafetyCenterManager::class.java)!!
    }

    private fun getContextForUser(userHandle: UserHandle): Context {
        return callWithShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL) {
            context.createContextAsUser(userHandle, 0)
        }
    }

    private fun createTestActivityRedirectPendingIntentForUser(user: UserHandle): PendingIntent {
        return callWithShellPermissionIdentity(INTERACT_ACROSS_USERS) {
            SafetySourceTestData.createRedirectPendingIntent(
                getContextForUser(user),
                Intent(ACTION_TEST_ACTIVITY)
            )
        }
    }

    private fun SafetyCenterManager.getSafetySourceDataWithInteractAcrossUsersPermission(
        id: String
    ): SafetySourceData? =
        callWithShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL) {
            getSafetySourceDataWithPermission(id)
        }

    private fun SafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
        id: String,
        dataToSet: SafetySourceData,
        safetyEvent: SafetyEvent = EVENT_SOURCE_STATE_CHANGED
    ) =
        callWithShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL) {
            setSafetySourceDataWithPermission(id, dataToSet, safetyEvent)
        }

    private fun SafetyCenterManager.getSafetyCenterDataWithInteractAcrossUsersPermission():
        SafetyCenterData =
        callWithShellPermissionIdentity(INTERACT_ACROSS_USERS_FULL) {
            getSafetyCenterDataWithPermission()
        }

    private fun setQuietMode(value: Boolean) {
        deviceState.workProfile().setQuietMode(value)
        inQuietMode = value
    }

    private fun resetQuietMode() {
        if (!inQuietMode) {
            return
        }
        setQuietMode(false)
    }

    private fun safetyCenterEntryOkForWork(sourceId: String, managedUserId: Int) =
        safetyCenterTestData
            .safetyCenterEntryOkBuilder(sourceId, managedUserId, title = "Ok title for Work")
            .build()

    private fun updatePrimaryProfileSources() {
        safetyCenterTestHelper.setData(
            DYNAMIC_BAREBONE_ID,
            safetySourceTestData.criticalWithResolvingGeneralIssue
        )
        safetyCenterTestHelper.setData(
            DYNAMIC_DISABLED_ID,
            safetySourceTestData.recommendationWithGeneralIssue
        )
        safetyCenterTestHelper.setData(DYNAMIC_HIDDEN_ID, safetySourceTestData.unspecified)
        safetyCenterTestHelper.setData(
            ISSUE_ONLY_BAREBONE_ID,
            SafetySourceTestData.issuesOnly(safetySourceTestData.criticalResolvingGeneralIssue)
        )
        safetyCenterTestHelper.setData(
            ISSUE_ONLY_ALL_OPTIONAL_ID,
            SafetySourceTestData.issuesOnly(safetySourceTestData.recommendationGeneralIssue)
        )
        safetyCenterTestHelper.setData(
            DYNAMIC_IN_STATELESS_ID,
            safetySourceTestData.unspecifiedWithIssue
        )
        safetyCenterTestHelper.setData(
            ISSUE_ONLY_IN_STATELESS_ID,
            SafetySourceTestData.issuesOnly(safetySourceTestData.informationIssue)
        )
    }

    private fun updateWorkProfileSources() {
        val managedSafetyCenterManager =
            getSafetyCenterManagerForUser(deviceState.workProfile().userHandle())
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            DYNAMIC_DISABLED_ID,
            safetySourceTestData.informationWithIssueForWork
        )
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            DYNAMIC_HIDDEN_ID,
            safetySourceTestData.informationWithIssueForWork
        )
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            ISSUE_ONLY_ALL_OPTIONAL_ID,
            SafetySourceTestData.issuesOnly(safetySourceTestData.informationIssue)
        )
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            DYNAMIC_IN_STATELESS_ID,
            safetySourceTestData.unspecifiedWithIssueForWork
        )
        managedSafetyCenterManager.setSafetySourceDataWithInteractAcrossUsersPermission(
            ISSUE_ONLY_IN_STATELESS_ID,
            SafetySourceTestData.issuesOnly(safetySourceTestData.informationIssue)
        )
    }
}
