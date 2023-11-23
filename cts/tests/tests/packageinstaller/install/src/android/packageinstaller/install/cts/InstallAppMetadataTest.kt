/*
 * Copyright 2022 The Android Open Source Project
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

import android.app.UiAutomation
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.content.pm.PackageManager.NameNotFoundException
import android.os.PersistableBundle
import android.platform.test.annotations.AppModeFull
import androidx.test.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.google.common.truth.Truth.assertThat
import java.io.File
import java.io.FileNotFoundException
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@AppModeFull(reason = "Instant apps cannot install packages")
class InstallAppMetadataTest : PackageInstallerTestBase() {

    private val TEST_FIELD = "testField"

    private val uiAutomation: UiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation()

    @Test
    fun installViaSession() {
        installTestApp(null)

        uiAutomation.adoptShellPermissionIdentity()
        val data = pm.getAppMetadata(TEST_APK_PACKAGE_NAME)
        uiAutomation.dropShellPermissionIdentity()
        assertThat(data).isNotNull()
        assertThat(data.isEmpty()).isTrue()
    }

    @Test
    fun installViaSessionWithAppMetadata() {
        val data = createAppMetadata()
        installTestApp(data)

        uiAutomation.adoptShellPermissionIdentity()
        assertAppMetadata(data.getString(TEST_FIELD), pm.getAppMetadata(TEST_APK_PACKAGE_NAME))
        uiAutomation.dropShellPermissionIdentity()
    }

    @Test(expected = SecurityException::class)
    fun getAppMetadataWithNoPermission() {
        installTestApp(createAppMetadata())

        pm.getAppMetadata(TEST_APK_PACKAGE_NAME)
    }

    @Test(expected = IllegalArgumentException::class)
    fun installViaSessionWithBadAppMetadata() {
        installTestApp(createAppMetadataExceedSizeLimit())
    }

    @Test(expected = NameNotFoundException::class)
    fun noInstallGetAppMetadata() {
        uiAutomation.adoptShellPermissionIdentity()
        try {
            val data = pm.getAppMetadata(TEST_APK_PACKAGE_NAME)
        } catch (e: Exception) {
            throw e
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }

    @Test
    fun installViaSessionWithOnlyAppMetadata() {
        val data = createAppMetadata()
        val (sessionId, session) = createSession(0, false, null)
        setAppMetadata(session, data)
        commitSession(session, false)
        val result = getInstallSessionResult()
        assertThat(result.status).isEqualTo(PackageInstaller.STATUS_FAILURE_INVALID)
    }

    @Test
    fun resetAppMetadataInSession() {
        val data = createAppMetadata()
        val (sessionId, session) = createSession(0, false, null)
        writeSession(session, TEST_APK_NAME)
        setAppMetadata(session, data)
        assertAppMetadata(data.getString(TEST_FIELD), session.getAppMetadata())
        setAppMetadata(session, null)
        assertThat(session.getAppMetadata().isEmpty()).isTrue()
        commitSession(session)
        clickInstallerUIButton(INSTALL_BUTTON_ID)
        val result = getInstallSessionResult()
        assertThat(result.status).isEqualTo(PackageInstaller.STATUS_SUCCESS)

        uiAutomation.adoptShellPermissionIdentity()
        assertThat(pm.getAppMetadata(TEST_APK_PACKAGE_NAME).isEmpty()).isTrue()
        uiAutomation.dropShellPermissionIdentity()
    }

    @Test
    fun installWithNoAppMetadataDropExisting() {
        val data = createAppMetadata()
        installTestApp(data)

        uiAutomation.adoptShellPermissionIdentity()
        assertAppMetadata(data.getString(TEST_FIELD), pm.getAppMetadata(TEST_APK_PACKAGE_NAME))
        uiAutomation.dropShellPermissionIdentity()

        installTestApp(null)

        uiAutomation.adoptShellPermissionIdentity()
        assertThat(pm.getAppMetadata(TEST_APK_PACKAGE_NAME).isEmpty()).isTrue()
        uiAutomation.dropShellPermissionIdentity()
    }

    @Test(expected = FileNotFoundException::class)
    fun readAppMetadataFileShouldFail() {
        val data = createAppMetadata()
        installTestApp(data)

        val appInfo = pm.getApplicationInfo(TEST_APK_PACKAGE_NAME,
            PackageManager.ApplicationInfoFlags.of(0))
        val file = File(File(appInfo.publicSourceDir).getParentFile(), "app.metadata")
        PersistableBundle.readFromStream(file.inputStream())
    }

    private fun installTestApp(data: PersistableBundle?) {
        val (sessionId, session) = createSession(0, false, null)
        writeSession(session, TEST_APK_NAME)
        if (data != null) {
            setAppMetadata(session, data)
            assertAppMetadata(data.getString(TEST_FIELD), session.getAppMetadata())
        }
        commitSession(session)

        clickInstallerUIButton(INSTALL_BUTTON_ID)

        // Install should have succeeded
        val result = getInstallSessionResult()
        assertThat(result.status).isEqualTo(PackageInstaller.STATUS_SUCCESS)
    }

    private fun setAppMetadata(session: PackageInstaller.Session, data: PersistableBundle?) {
        try {
            session.setAppMetadata(data)
        } catch (e: Exception) {
            session.abandon()
            throw e
        }
    }

    private fun assertAppMetadata(testVal: String?, data: PersistableBundle) {
        assertThat(data).isNotNull()
        assertEquals(data.size(), 1)
        assertThat(data.containsKey(TEST_FIELD)).isTrue()
        assertEquals(data.getString(TEST_FIELD), testVal)
    }

    private fun createAppMetadata(): PersistableBundle {
        val bundle = PersistableBundle()
        bundle.putString(TEST_FIELD, "testValue")
        return bundle
    }

    private fun createAppMetadataExceedSizeLimit(): PersistableBundle {
        val bundle = PersistableBundle()
        // create a bundle that is greater than default size limit of 32KB.
        bundle.putString(TEST_FIELD, "a".repeat(32000))
        return bundle
    }
}
