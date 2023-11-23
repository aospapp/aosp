/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.Activity.RESULT_CANCELED
import android.app.UiAutomation
import android.content.pm.ApplicationInfo.CATEGORY_MAPS
import android.content.pm.ApplicationInfo.CATEGORY_UNDEFINED
import android.content.pm.PackageInstaller
import android.content.pm.PackageInstaller.STATUS_FAILURE_ABORTED
import android.content.pm.PackageInstaller.STATUS_SUCCESS
import android.content.pm.PackageInstaller.SessionParams.MODE_FULL_INSTALL
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DEFAULT
import android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
import android.platform.test.annotations.AppModeFull
import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.compatibility.common.util.AppOpsUtils
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith

/**
 * This class tests creation of a package installer session with different params.
 */
@AppModeFull(reason = "Instant apps cannot create installer sessions")
@RunWith(AndroidJUnit4::class)
class SessionTest : PackageInstallerTestBase() {

    private val uiAutomation: UiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation()

    /**
     * Check that we can install an app via a package-installer session
     */
    @Test
    fun confirmInstallation() {
        val installation = startInstallationViaSession(needFuture = true)!!
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // Install should have succeeded
        val result = getInstallSessionResult()
        assertEquals(STATUS_SUCCESS, result.status)
        assertEquals(false, result.preapproval)
        assertInstalled()

        // Even when the install succeeds the install confirm dialog returns 'canceled'
        assertEquals(RESULT_CANCELED, installation.get(TIMEOUT, TimeUnit.MILLISECONDS))

        assertTrue(AppOpsUtils.allowedOperationLogged(context.packageName, APP_OP_STR))
    }

    /**
     * Check that we can install an app via a package-installer session
     */
    @Test
    fun confirmMultiPackageInstallation() {
        val installation = startInstallationViaMultiPackageSession(
                installFlags = 0,
                TEST_APK_NAME,
                needFuture = true
        )!!
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // Install should have succeeded
        val result = getInstallSessionResult()
        assertEquals(STATUS_SUCCESS, result.status)
        assertEquals(false, result.preapproval)
        assertInstalled()

        // Even when the install succeeds the install confirm dialog returns 'canceled'
        assertEquals(RESULT_CANCELED, installation.get(TIMEOUT, TimeUnit.MILLISECONDS))

        assertTrue(AppOpsUtils.allowedOperationLogged(context.packageName, APP_OP_STR))
    }

    /**
     * Check that we can set an app category for an app we installed
     */
    @Test
    fun setAppCategory() {
        val installation = startInstallationViaSession()
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // Wait for installation to finish
        getInstallSessionResult()

        assertEquals(CATEGORY_UNDEFINED, pm.getApplicationInfo(TEST_APK_PACKAGE_NAME, 0).category)

        // This app installed the app, hence we can set the category
        pm.setApplicationCategoryHint(TEST_APK_PACKAGE_NAME, CATEGORY_MAPS)

        assertEquals(CATEGORY_MAPS, pm.getApplicationInfo(TEST_APK_PACKAGE_NAME, 0).category)
    }

    /**
     * Check that we can set an app category for an app we installed
     */
    @Test
    fun setApplicationEnabledSettingPersistent() {
        installWithApplicationEnabledSetting()
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT,
                pm.getApplicationEnabledSetting(TEST_APK_PACKAGE_NAME))

        disablePackage()
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED,
                pm.getApplicationEnabledSetting(TEST_APK_PACKAGE_NAME))

        // enabled setting should be reset to default after reinstall
        installWithApplicationEnabledSetting()
        assertEquals(COMPONENT_ENABLED_STATE_DEFAULT,
                pm.getApplicationEnabledSetting(TEST_APK_PACKAGE_NAME))

        disablePackage()
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED,
            pm.getApplicationEnabledSetting(TEST_APK_PACKAGE_NAME))

        // enabled setting should now be persisted after reinstall
        installWithApplicationEnabledSetting(true)
        assertEquals(COMPONENT_ENABLED_STATE_DISABLED,
            pm.getApplicationEnabledSetting(TEST_APK_PACKAGE_NAME))
    }

    /**
     * Install an app via a package-installer session, but then cancel it when the package installer
     * pops open.
     */
    @Test
    fun cancelInstallation() {
        val installation = startInstallationViaSession(needFuture = true)!!
        clickInstallerUIButton(CANCEL_BUTTON_ID)

        // Install should have been aborted
        val result = getInstallSessionResult()
        assertEquals(STATUS_FAILURE_ABORTED, result.status)
        assertEquals(false, result.preapproval)
        assertEquals(RESULT_CANCELED, installation.get(TIMEOUT, TimeUnit.MILLISECONDS))
        assertNotInstalled()
    }

    /**
     * Check that can't install when FRP mode is enabled.
     */
    @Test
    fun confirmFrpInstallationFails() {
        try {
            setSecureFrp(true)

            try {
                val installation = startInstallationViaSession()
                clickInstallerUIButton(CANCEL_BUTTON_ID)

                fail("Package should not be installed")
            } catch (expected: SecurityException) {
            }

            // Install should never have started
            assertNotInstalled()
        } finally {
            setSecureFrp(false)
        }
    }

    /**
     * Check that can't install Instant App when installer don't have proper permission.
     */
    @Test
    fun confirmInstantInstallationFails() {
        try {
            val installation = startInstallationViaSession(INSTALL_INSTANT_APP)
            clickInstallerUIButton(CANCEL_BUTTON_ID)

            fail("Expected security exception on instant install from non-system app")
        } catch (expected: SecurityException) {
            // Expected
        }

        // Install should never have started
        assertNotInstalled()
    }

    private fun installWithApplicationEnabledSetting(setEnabledSettingPersistent: Boolean = false) {
        val sessionParam = PackageInstaller.SessionParams(MODE_FULL_INSTALL)
        if (setEnabledSettingPersistent) {
            sessionParam.setApplicationEnabledSettingPersistent()
        }
        val sessionId = pi.createSession(sessionParam)
        val session = pi.openSession(sessionId)
        assertEquals(setEnabledSettingPersistent, session.isApplicationEnabledSettingPersistent())
        writeSession(session, TEST_APK_NAME)
        commitSession(session)
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // Wait for installation to finish
        getInstallSessionResult()
    }

    private fun disablePackage() {
        uiAutomation.adoptShellPermissionIdentity()
        try {
            pm.setApplicationEnabledSetting(TEST_APK_PACKAGE_NAME,
                COMPONENT_ENABLED_STATE_DISABLED, 0)
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }
}
