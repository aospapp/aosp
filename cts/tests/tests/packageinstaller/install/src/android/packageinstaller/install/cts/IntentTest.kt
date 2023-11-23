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
import android.app.Activity.RESULT_OK
import android.app.AppOpsManager.MODE_ALLOWED
import android.content.Intent
import android.net.Uri
import android.platform.test.annotations.AppModeFull
import androidx.test.InstrumentationRegistry
import androidx.test.runner.AndroidJUnit4
import com.android.compatibility.common.util.AppOpsUtils
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val INSTALL_BUTTON_ID = "button1"
private const val CANCEL_BUTTON_ID = "button2"

@RunWith(AndroidJUnit4::class)
@AppModeFull(reason = "Instant apps cannot install packages")
class IntentTest : PackageInstallerTestBase() {
    private val context = InstrumentationRegistry.getTargetContext()

    @Before
    fun allowToInstallPackages() {
        AppOpsUtils.setOpMode(context.packageName, APP_OP_STR, MODE_ALLOWED)
    }

    /**
     * Check that we can install an app via a package-installer intent
     */
    @Test
    fun confirmInstallation() {
        startInstallationViaIntent()
        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // Install should have succeeded
        assertEquals(RESULT_OK, getInstallDialogResult())
        assertInstalled()
    }

    /**
     * Install an app via a package-installer intent, but then cancel it when the package installer
     * pops open.
     */
    @Test
    fun cancelInstallation() {
        startInstallationViaIntent()
        clickInstallerUIButton(CANCEL_BUTTON_ID)

        // Install should have been aborted
        assertEquals(RESULT_CANCELED, getInstallDialogResult())
        assertNotInstalled()

        assertNoMoreInstallResults()
    }

    /**
     * Make sure that an already installed app can be reinstalled via a "package" uri
     */
    @Test
    fun reinstallViaPackageUri() {
        // Regular install
        confirmInstallation()

        // Reinstall
        val intent = Intent(Intent.ACTION_INSTALL_PACKAGE)
        intent.data = Uri.fromParts("package", TEST_APK_PACKAGE_NAME, null)
        intent.putExtra(Intent.EXTRA_RETURN_RESULT, true)
        intent.flags = Intent.FLAG_GRANT_READ_URI_PERMISSION

        installDialogStarter.activity.startActivityForResult(intent, 0)

        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // Install should have succeeded
        assertEquals(RESULT_OK, getInstallDialogResult())
        assertInstalled()
    }
}
