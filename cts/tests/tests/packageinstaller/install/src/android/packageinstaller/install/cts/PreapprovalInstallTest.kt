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

package android.packageinstaller.install.cts

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageInstaller
import android.icu.util.ULocale
import android.platform.test.annotations.AppModeFull
import android.provider.DeviceConfig
import androidx.test.runner.AndroidJUnit4
import com.android.compatibility.common.util.DeviceConfigStateChangerRule
import java.io.File
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.junit.Assume
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@AppModeFull(reason = "Instant apps cannot create installer sessions")
@RunWith(AndroidJUnit4::class)
class PreapprovalInstallTest : PackageInstallerTestBase() {

    companion object {
        const val TEST_APK_NAME_PL = "CtsEmptyTestApp_pl.apk"
        const val TEST_APK_NAME_V2 = "CtsEmptyTestAppV2.apk"
        const val TEST_APP_LABEL = "Empty Test App"
        const val TEST_APP_LABEL_PL = "Empty Test App Polish"
        const val TEST_APP_LABEL_V2 = "Empty Test App V2"
        const val TEST_FAKE_APP_LABEL = "Fake Test App"
        const val PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE = "is_preapproval_available"
    }

    private val apkFile_pl = File(context.filesDir, TEST_APK_NAME_PL)
    private val apkFile_v2 = File(context.filesDir, TEST_APK_NAME_V2)

    @get:Rule
    val deviceConfigPreApprovalRequestAvailable = DeviceConfigStateChangerRule(
        context, DeviceConfig.NAMESPACE_PACKAGE_MANAGER_SERVICE,
            PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE, true.toString())

    @Before
    fun copyOtherTestApks() {
        File(TEST_APK_LOCATION, TEST_APK_NAME_PL).copyTo(target = apkFile_pl, overwrite = true)
        File(TEST_APK_LOCATION, TEST_APK_NAME_V2).copyTo(target = apkFile_v2, overwrite = true)
    }

    @Before
    fun checkPreconditions() {
        val res = context.getResources()
        val isPreapprovalAvailable = res.getBoolean(
            res.getIdentifier("config_isPreApprovalRequestAvailable", "bool", "android"))
        Assume.assumeTrue("Pre-approval is not available", isPreapprovalAvailable)
    }

    /**
     * Clean up all sessions created by this test.
     */
    @After
    fun cleanUpSessions() {
        pi.mySessions.forEach {
            try {
                pi.abandonSession(it.sessionId)
            } catch (ignored: Exception) {
            }
        }
    }

    /**
     * Check that we can request a user pre-approval
     */
    @Test
    fun requestUserPreapproval_userAgree_statusSuccess() {
        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)
        startRequestUserPreapproval(session, preparePreapprovalDetails())
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // request should have succeeded
        val result = getInstallSessionResult()
        assertEquals(PackageInstaller.STATUS_SUCCESS, result.status)
        assertEquals(true, result.preapproval)
    }

    /**
     * Request a user pre-approval, but then cancel it when it prompts.
     */
    @Test
    fun requestUserPreapproval_userCancel_statusFailureAborted() {
        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)
        startRequestUserPreapproval(session, preparePreapprovalDetails())
        clickInstallerUIButton(CANCEL_BUTTON_ID)

        // request should have been aborted
        val result = getInstallSessionResult()
        assertEquals(PackageInstaller.STATUS_FAILURE_ABORTED, result.status)
        assertEquals(true, result.preapproval)
    }

    /**
     * Check that we cannot request a user preapproval with an approved session again.
     */
    @Test
    fun requestUserPreapproval_alreadyApproved_throwException() {
        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)
        val details = preparePreapprovalDetails()
        startRequestUserPreapproval(session, details)
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // request should have succeeded
        getInstallSessionResult()
        try {
            startRequestUserPreapproval(session, details)
            fail("Cannot request user preapproval on an approved/rejected session again")
        } catch (expected: IllegalStateException) {
        }
    }

    /**
     * Check that we cannot commit a session that has been declined by the user when
     * requesting user preapproval.
     */
    @Test
    fun requestUserPreapproval_userCancel_cannotCommitAgain() {
        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)
        startRequestUserPreapproval(session, preparePreapprovalDetails())
        clickInstallerUIButton(CANCEL_BUTTON_ID)

        getInstallSessionResult()
        try {
            commitSession(session)
            fail("Cannot commit an rejected session again")
        } catch (expected: SecurityException) {
        }
    }

    /**
     * Request a user pre-approval, but don't resolve it.
     * Check that we can install via commit this session later.
     */
    @Test
    fun requestUserPreapproval_doNothingAndCommitLater_installSuccessfully() {
        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)

        val dummyReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context, intent: Intent) {
                // Do nothing
            }
        }

        try {
            val action = "PreapprovalInstallTest.install_cb"
            context.registerReceiver(dummyReceiver, IntentFilter(action),
                    Context.RECEIVER_EXPORTED)

            val pendingIntent = PendingIntent.getBroadcast(context, 0 /* requestCode */,
                    Intent(action).setPackage(context.packageName),
                    PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            session.requestUserPreapproval(preparePreapprovalDetails(), pendingIntent.intentSender)

            writeAndCommitSession(TEST_APK_NAME, session)
            clickInstallerUIButton(INSTALL_BUTTON_ID)

            val result = getInstallSessionResult()
            assertEquals(PackageInstaller.STATUS_SUCCESS, result.status)
            assertInstalled()
        } finally {
            context.unregisterReceiver(dummyReceiver)
        }
    }

    /**
     * Check that we can install an app without prompt after getting pre-approval from users.
     */
    @Test
    fun commitPreapprovalSession_success() {
        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)
        startRequestUserPreapproval(session, preparePreapprovalDetails())
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // request should have succeeded
        getInstallSessionResult()

        writeSession(session, TEST_APK_NAME)
        startInstallationViaPreapprovalSession(session)
        // No need to click installer UI here.
        val result = getInstallSessionResult()
        assertEquals(PackageInstaller.STATUS_SUCCESS, result.status)
        assertInstalled()
    }

    /**
     * Check that we can install an app with app label in another locale, and this label is
     * in the split APK file.
     */
    @Test
    fun commitPreapprovalSession_usingAnotherLocaleInSplitApk_success() {
        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)
        startRequestUserPreapproval(session, preparePreapprovalDetailsInPl())
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // request should have succeeded
        getInstallSessionResult()

        writeSession(session, TEST_APK_NAME)
        writeSession(session, TEST_APK_NAME_PL)
        startInstallationViaPreapprovalSession(session)
        // No need to click installer UI here.
        val result = getInstallSessionResult()
        assertEquals(PackageInstaller.STATUS_SUCCESS, result.status)
        assertInstalled()
    }

    /**
     * Check that we can update an app without prompt after getting pre-approval from users.
     */
    @Test
    fun commitPreapprovalSession_update_success() {
        installTestPackage()
        assertInstalled()

        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)
        startRequestUserPreapproval(session, preparePreapprovalDetailsV2())
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // request should have succeeded
        getInstallSessionResult()

        writeSession(session, TEST_APK_NAME_V2)
        startInstallationViaPreapprovalSession(session)
        // No need to click installer UI here.
        val result = getInstallSessionResult()
        assertEquals(PackageInstaller.STATUS_SUCCESS, result.status)
    }

    /**
     * Check that we can update an app with current app label even if the given label is
     * different from the label from the APK file.
     */
    @Test
    fun commitPreapprovalSession_updateUsingCurrentLabel_success() {
        installTestPackage()
        assertInstalled()

        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)
        startRequestUserPreapproval(session, preparePreapprovalDetails())
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // request should have succeeded
        getInstallSessionResult()

        writeSession(session, TEST_APK_NAME_V2)
        startInstallationViaPreapprovalSession(session)
        // No need to click installer UI here.
        val result = getInstallSessionResult()
        assertEquals(PackageInstaller.STATUS_SUCCESS, result.status)
    }

    /**
     * Check that the pre-approval install session fails when the information from
     * PreapprovalDetails doesn't match the APK files.
     */
    @Test
    fun commitPreapprovalSession_notMatchApk_fail() {
        // Using incorrect app label in PreapprovalDetails.
        val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                null /* packageSource */)
        startRequestUserPreapproval(session, prepareWrongPreapprovalDetails())
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // request should have succeeded
        getInstallSessionResult()

        writeSession(session, TEST_APK_NAME)
        startInstallationViaPreapprovalSession(session)
        // No need to click installer UI here.
        val result = getInstallSessionResult()
        assertEquals(PackageInstaller.STATUS_FAILURE, result.status)
        assertNotInstalled()
    }

    /**
     * Check that a parent of multi-package session cannot be requested user pre-approval.
     */
    @Test
    fun requestUserPreapproval_multiPackageSession_throwException() {
        val (sessionId, session) = createSession(0 /* flags */, true /* isMultiPackage */,
                null /* packageSource */)
        try {
            startRequestUserPreapproval(session, preparePreapprovalDetails())
            fail("A parent of multi-package session cannot be requested.")
        } catch (expected: IllegalStateException) {
        }
    }

    /**
     * Check that a child of multi-package session can be requested user pre-approval.
     */
    @Test
    fun requestUserPreapproval_childSession_statusSuccess() {
        val (sessionId, session) = createSession(0 /* flags */, true /* isMultiPackage */,
                null /* packageSource */)
        val (childSessionId, childSession) = createSession(
                0 /* flags */, false /* isMultiPackage */, null /* packageSource */)
        session.addChildSessionId(childSessionId)

        startRequestUserPreapproval(childSession, preparePreapprovalDetails())
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // request should have succeeded
        val result = getInstallSessionResult()
        assertEquals(PackageInstaller.STATUS_SUCCESS, result.status)
        assertEquals(true, result.preapproval)
    }

    /**
     * Check that we can install the multi-package session without prompt after getting
     * pre-approval from users.
     */
    @Test
    fun commitPreapprovalSession_multiPackage_successWithoutUserAction() {
        val (sessionId, session) = createSession(0 /* flags */, true /* isMultiPackage */,
                null /* packageSource */)
        val (childSessionId, childSession) = createSession(
                0 /* flags */, false /* isMultiPackage */, null /* packageSource */)

        startRequestUserPreapproval(childSession, preparePreapprovalDetails())
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // request should have succeeded
        getInstallSessionResult()

        // Add the pre-approved session into a multi-package session, and commit
        writeSession(childSession, TEST_APK_NAME)
        session.addChildSessionId(childSessionId)

        startInstallationViaPreapprovalSession(session)
        // No need to click installer UI here.
        val result = getInstallSessionResult()
        assertEquals(PackageInstaller.STATUS_SUCCESS, result.status)
        assertInstalled()
    }

    /**
     * Check that we receive the expected status when requesting user pre-approval isn't available.
     */
    @Test
    fun requestUserPreapproval_featureDisabled_statusFailureBlocked() {
        val config = getDeviceProperty(PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE)
        setDeviceProperty(PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE, "false")

        try {
            val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                    null /* packageSource */)
            startRequestUserPreapproval(session, preparePreapprovalDetails(),
                    false /* expectedPrompt */)

            val result = getInstallSessionResult()
            assertEquals(PackageInstaller.STATUS_FAILURE_BLOCKED, result.status)
        } finally {
            setDeviceProperty(PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE, config)
        }
    }

    /**
     * Request a user pre-approval, but this feature is currently not available.
     * Check that we can still install via commit this session.
     */
    @Test
    fun requestUserPreapproval_featureDisabled_couldUseCommitInstead() {
        val config = getDeviceProperty(PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE)
        setDeviceProperty(PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE, "false")

        try {
            val (sessionId, session) = createSession(0 /* flags */, false /* isMultiPackage */,
                    null /* packageSource */)
            startRequestUserPreapproval(session, preparePreapprovalDetails(),
                    false /* expectedPrompt */)

            // request should be failed
            getInstallSessionResult()

            writeSession(session, TEST_APK_NAME)
            startInstallationViaPreapprovalSession(session)
            // Since requestUserPreapproval isn't allowed, the installers should be able to use
            // typical install flow instead.
            var result = getInstallSessionResult()
            assertEquals(PackageInstaller.STATUS_PENDING_USER_ACTION, result.status)

            clickInstallerUIButton(INSTALL_BUTTON_ID)

            result = getInstallSessionResult()
            assertEquals(PackageInstaller.STATUS_SUCCESS, result.status)
            assertInstalled()
        } finally {
            setDeviceProperty(PROPERTY_IS_PRE_APPROVAL_REQUEST_AVAILABLE, config)
        }
    }

    private fun preparePreapprovalDetails(): PackageInstaller.PreapprovalDetails {
        return preparePreapprovalDetails(TEST_APP_LABEL, ULocale.US, TEST_APK_PACKAGE_NAME)
    }

    private fun preparePreapprovalDetailsV2(): PackageInstaller.PreapprovalDetails {
        return preparePreapprovalDetails(TEST_APP_LABEL_V2, ULocale.US, TEST_APK_PACKAGE_NAME)
    }

    private fun preparePreapprovalDetailsInPl(): PackageInstaller.PreapprovalDetails {
        return preparePreapprovalDetails(TEST_APP_LABEL_PL, ULocale("pl"), TEST_APK_PACKAGE_NAME)
    }

    private fun prepareWrongPreapprovalDetails(): PackageInstaller.PreapprovalDetails {
        return preparePreapprovalDetails(TEST_FAKE_APP_LABEL, ULocale.US, TEST_APK_PACKAGE_NAME)
    }

    private fun preparePreapprovalDetails(
            appLabel: String,
            locale: ULocale,
            appPackageName: String
    ): PackageInstaller.PreapprovalDetails {
        return PackageInstaller.PreapprovalDetails.Builder()
                .setLabel(appLabel)
                .setLocale(locale)
                .setPackageName(appPackageName)
                .build()
    }
}
