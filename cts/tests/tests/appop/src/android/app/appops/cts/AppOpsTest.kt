package android.app.appops.cts

/*
 * Copyright (C) 2014 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License")
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail

import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.MODE_DEFAULT
import android.app.AppOpsManager.MODE_ERRORED
import android.app.AppOpsManager.MODE_IGNORED
import android.app.AppOpsManager.OPSTR_READ_CALENDAR
import android.app.AppOpsManager.OPSTR_RECORD_AUDIO
import android.app.AppOpsManager.OPSTR_WRITE_CALENDAR

import android.app.appops.cts.AppOpsUtils.Companion.allowedOperationLogged
import android.app.appops.cts.AppOpsUtils.Companion.rejectedOperationLogged
import android.app.appops.cts.AppOpsUtils.Companion.setOpMode

import org.mockito.Mockito.mock
import org.mockito.Mockito.reset
import org.mockito.Mockito.timeout
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyZeroInteractions

import android.Manifest.permission
import android.app.AppOpsManager
import android.app.AppOpsManager.OnOpChangedListener
import android.content.Context
import android.os.Process
import androidx.test.runner.AndroidJUnit4
import androidx.test.InstrumentationRegistry

import org.junit.Before
import org.junit.Ignore
import org.junit.Test
import org.junit.runner.RunWith

import java.util.HashMap
import java.util.HashSet

@RunWith(AndroidJUnit4::class)
class AppOpsTest {
    // Notifying OnOpChangedListener callbacks is an async operation, so we define a timeout.
    private val MODE_WATCHER_TIMEOUT_MS = 5000L

    private lateinit var mAppOps: AppOpsManager
    private lateinit var mContext: Context
    private lateinit var mOpPackageName: String

    companion object {
        // These permissions and opStrs must map to the same op codes.
        val permissionToOpStr = HashMap<String, String>()

        init {
            permissionToOpStr[permission.ACCESS_COARSE_LOCATION] =
                    AppOpsManager.OPSTR_COARSE_LOCATION
            permissionToOpStr[permission.ACCESS_FINE_LOCATION] =
                    AppOpsManager.OPSTR_FINE_LOCATION
            permissionToOpStr[permission.READ_CONTACTS] =
                    AppOpsManager.OPSTR_READ_CONTACTS
            permissionToOpStr[permission.WRITE_CONTACTS] =
                    AppOpsManager.OPSTR_WRITE_CONTACTS
            permissionToOpStr[permission.READ_CALL_LOG] =
                    AppOpsManager.OPSTR_READ_CALL_LOG
            permissionToOpStr[permission.WRITE_CALL_LOG] =
                    AppOpsManager.OPSTR_WRITE_CALL_LOG
            permissionToOpStr[permission.READ_CALENDAR] =
                    AppOpsManager.OPSTR_READ_CALENDAR
            permissionToOpStr[permission.WRITE_CALENDAR] =
                    AppOpsManager.OPSTR_WRITE_CALENDAR
            permissionToOpStr[permission.CALL_PHONE] =
                    AppOpsManager.OPSTR_CALL_PHONE
            permissionToOpStr[permission.READ_SMS] =
                    AppOpsManager.OPSTR_READ_SMS
            permissionToOpStr[permission.RECEIVE_SMS] =
                    AppOpsManager.OPSTR_RECEIVE_SMS
            permissionToOpStr[permission.RECEIVE_MMS] =
                    AppOpsManager.OPSTR_RECEIVE_MMS
            permissionToOpStr[permission.RECEIVE_WAP_PUSH] =
                    AppOpsManager.OPSTR_RECEIVE_WAP_PUSH
            permissionToOpStr[permission.SEND_SMS] =
                    AppOpsManager.OPSTR_SEND_SMS
            permissionToOpStr[permission.READ_SMS] =
                    AppOpsManager.OPSTR_READ_SMS
            permissionToOpStr[permission.WRITE_SETTINGS] =
                    AppOpsManager.OPSTR_WRITE_SETTINGS
            permissionToOpStr[permission.SYSTEM_ALERT_WINDOW] =
                    AppOpsManager.OPSTR_SYSTEM_ALERT_WINDOW
            permissionToOpStr[permission.ACCESS_NOTIFICATIONS] =
                    AppOpsManager.OPSTR_ACCESS_NOTIFICATIONS
            permissionToOpStr[permission.CAMERA] =
                    AppOpsManager.OPSTR_CAMERA
            permissionToOpStr[permission.RECORD_AUDIO] =
                    AppOpsManager.OPSTR_RECORD_AUDIO
            permissionToOpStr[permission.READ_PHONE_STATE] =
                    AppOpsManager.OPSTR_READ_PHONE_STATE
            permissionToOpStr[permission.ADD_VOICEMAIL] =
                    AppOpsManager.OPSTR_ADD_VOICEMAIL
            permissionToOpStr[permission.USE_SIP] =
                    AppOpsManager.OPSTR_USE_SIP
            permissionToOpStr[permission.PROCESS_OUTGOING_CALLS] =
                    AppOpsManager.OPSTR_PROCESS_OUTGOING_CALLS
            permissionToOpStr[permission.BODY_SENSORS] =
                    AppOpsManager.OPSTR_BODY_SENSORS
            permissionToOpStr[permission.READ_CELL_BROADCASTS] =
                    AppOpsManager.OPSTR_READ_CELL_BROADCASTS
            permissionToOpStr[permission.READ_EXTERNAL_STORAGE] =
                    AppOpsManager.OPSTR_READ_EXTERNAL_STORAGE
            permissionToOpStr[permission.WRITE_EXTERNAL_STORAGE] =
                    AppOpsManager.OPSTR_WRITE_EXTERNAL_STORAGE
        }
    }

    @Before
    fun setUp() {
        mContext = InstrumentationRegistry.getContext()
        mAppOps = mContext.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        mOpPackageName = mContext.opPackageName
        assertNotNull(mAppOps)
        // Reset app ops state for this test package to the system default.
        AppOpsUtils.reset(mOpPackageName)
    }

    @Test
    fun testNoteOpAndCheckOp() {
        setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_ALLOWED)
        assertEquals(MODE_ALLOWED, mAppOps.noteOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_ALLOWED, mAppOps.noteOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_ALLOWED, mAppOps.unsafeCheckOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_ALLOWED, mAppOps.unsafeCheckOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))

        setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_IGNORED)
        assertEquals(MODE_IGNORED, mAppOps.noteOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_IGNORED, mAppOps.noteOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_IGNORED, mAppOps.unsafeCheckOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_IGNORED, mAppOps.unsafeCheckOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))

        setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_DEFAULT)
        assertEquals(MODE_DEFAULT, mAppOps.noteOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_DEFAULT, mAppOps.noteOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_DEFAULT, mAppOps.unsafeCheckOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_DEFAULT, mAppOps.unsafeCheckOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))

        setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_ERRORED)
        assertEquals(MODE_ERRORED, mAppOps.noteOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_ERRORED, mAppOps.unsafeCheckOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        try {
            mAppOps.noteOp(OPSTR_WRITE_CALENDAR, Process.myUid(), mOpPackageName)
            fail("SecurityException expected")
        } catch (expected: SecurityException) {
        }
        try {
            mAppOps.unsafeCheckOp(OPSTR_WRITE_CALENDAR, Process.myUid(), mOpPackageName)
            fail("SecurityException expected")
        } catch (expected: SecurityException) {
        }
    }

    @Test
    fun testStartOpAndFinishOp() {
        setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_ALLOWED)
        assertEquals(MODE_ALLOWED, mAppOps.startOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        mAppOps.finishOp(OPSTR_WRITE_CALENDAR, Process.myUid(), mOpPackageName)
        assertEquals(MODE_ALLOWED, mAppOps.startOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        mAppOps.finishOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName)

        setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_IGNORED)
        assertEquals(MODE_IGNORED, mAppOps.startOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_IGNORED, mAppOps.startOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))

        setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_DEFAULT)
        assertEquals(MODE_DEFAULT, mAppOps.startOp(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        assertEquals(MODE_DEFAULT, mAppOps.startOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))

        setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_ERRORED)
        assertEquals(MODE_ERRORED, mAppOps.startOpNoThrow(OPSTR_WRITE_CALENDAR,
                Process.myUid(), mOpPackageName))
        try {
            mAppOps.startOp(OPSTR_WRITE_CALENDAR, Process.myUid(), mOpPackageName)
            fail("SecurityException expected")
        } catch (expected: SecurityException) {
        }
    }

    @Test
    fun testCheckPackagePassesCheck() {
        mAppOps.checkPackage(Process.myUid(), mOpPackageName)
        mAppOps.checkPackage(Process.SYSTEM_UID, "android")
    }

    @Test
    fun testCheckPackageDoesntPassCheck() {
        try {
            // Package name doesn't match UID.
            mAppOps.checkPackage(Process.SYSTEM_UID, mOpPackageName)
            fail("SecurityException expected")
        } catch (expected: SecurityException) {
        }

        try {
            // Package name doesn't match UID.
            mAppOps.checkPackage(Process.myUid(), "android")
            fail("SecurityException expected")
        } catch (expected: SecurityException) {
        }

        try {
            // Package name missing
            mAppOps.checkPackage(Process.myUid(), "")
            fail("SecurityException expected")
        } catch (expected: SecurityException) {
        }
    }

    @Test
    fun testWatchingMode() {
        val watcher = mock(OnOpChangedListener::class.java)
        try {
            setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_ALLOWED)

            mAppOps.startWatchingMode(OPSTR_WRITE_CALENDAR, mOpPackageName, watcher)

            // Make a change to the app op's mode.
            reset(watcher)
            setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_ERRORED)
            verify(watcher, timeout(MODE_WATCHER_TIMEOUT_MS))
                    .onOpChanged(OPSTR_WRITE_CALENDAR, mOpPackageName)

            // Make another change to the app op's mode.
            reset(watcher)
            setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_ALLOWED)
            verify(watcher, timeout(MODE_WATCHER_TIMEOUT_MS))
                    .onOpChanged(OPSTR_WRITE_CALENDAR, mOpPackageName)

            // Set mode to the same value as before - expect no call to the listener.
            reset(watcher)
            setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_ALLOWED)
            verifyZeroInteractions(watcher)

            mAppOps.stopWatchingMode(watcher)

            // Make a change to the app op's mode. Since we already stopped watching the mode, the
            // listener shouldn't be called.
            reset(watcher)
            setOpMode(mOpPackageName, OPSTR_WRITE_CALENDAR, MODE_ERRORED)
            verifyZeroInteractions(watcher)
        } finally {
            // Clean up registered watcher.
            mAppOps.stopWatchingMode(watcher)
        }
    }

    @Test
    fun testAllOpsHaveOpString() {
        val opStrs = HashSet<String>()
        for (opStr in AppOpsManager.getOpStrs()) {
            assertNotNull("Each app op must have an operation string defined", opStr)
            opStrs.add(opStr)
        }
        assertEquals("Not all op strings are unique", AppOpsManager.getNumOps(), opStrs.size)
    }

    @Test
    fun testOpCodesUnique() {
        val opStrs = AppOpsManager.getOpStrs()
        val opCodes = HashSet<Int>()
        for (opStr in opStrs) {
            opCodes.add(AppOpsManager.strOpToOp(opStr))
        }
        assertEquals("Not all app op codes are unique", opStrs.size, opCodes.size)
    }

    @Test
    fun testPermissionMapping() {
        for (entry in permissionToOpStr) {
            testPermissionMapping(entry.key, permissionToOpStr[entry.key])
        }
    }

    private fun testPermissionMapping(permission: String, opStr: String?) {
        // Do the public value => internal op code lookups.
        val mappedOpStr = AppOpsManager.permissionToOp(permission)
        assertEquals(mappedOpStr, opStr)
        val mappedOpCode = AppOpsManager.permissionToOpCode(permission)
        val mappedOpCode2 = AppOpsManager.strOpToOp(opStr)
        assertEquals(mappedOpCode, mappedOpCode2)

        // Do the internal op code => public value lookup (reverse lookup).
        val permissionMappedBack = AppOpsManager.opToPermission(mappedOpCode)
        assertEquals(permission, permissionMappedBack)
    }

    /**
     * Test that the app can not change the app op mode for itself.
     */
    @Test
    fun testCantSetModeForSelf() {
        try {
            val writeSmsOp = AppOpsManager.permissionToOpCode("android.permission.WRITE_SMS")
            mAppOps.setMode(writeSmsOp, Process.myUid(), mOpPackageName, AppOpsManager.MODE_ALLOWED)
            fail("Was able to set mode for self")
        } catch (expected: SecurityException) {
        }
    }

    @Test
    fun testGetOpsForPackageOpsAreLogged() {
        // This test checks if operations get logged by the system. It needs to start with a clean
        // slate, i.e. these ops can't have been logged previously for this test package. The reason
        // is that there's no API for clearing the app op logs before a test run. However, the op
        // logs are cleared when this test package is reinstalled between test runs. To make sure
        // that other test methods in this class don't affect this test method, here we use
        // operations that are not used by any other test cases.
        val mustNotBeLogged = "Operation mustn't be logged before the test runs"
        assertFalse(mustNotBeLogged, allowedOperationLogged(mOpPackageName, OPSTR_RECORD_AUDIO))
        assertFalse(mustNotBeLogged, allowedOperationLogged(mOpPackageName, OPSTR_READ_CALENDAR))

        setOpMode(mOpPackageName, OPSTR_RECORD_AUDIO, MODE_ALLOWED)
        setOpMode(mOpPackageName, OPSTR_READ_CALENDAR, MODE_ERRORED)

        // Note an op that's allowed.
        mAppOps.noteOp(OPSTR_RECORD_AUDIO, Process.myUid(), mOpPackageName)
        val mustBeLogged = "Operation must be logged"
        assertTrue(mustBeLogged, allowedOperationLogged(mOpPackageName, OPSTR_RECORD_AUDIO))

        // Note another op that's not allowed.
        mAppOps.noteOpNoThrow(OPSTR_READ_CALENDAR, Process.myUid(), mOpPackageName)
        assertTrue(mustBeLogged, allowedOperationLogged(mOpPackageName, OPSTR_RECORD_AUDIO))
        assertTrue(mustBeLogged, rejectedOperationLogged(mOpPackageName, OPSTR_READ_CALENDAR))
    }

    @Test
    fun testNonHistoricalStatePersistence() {
        // Put a package and uid level data
        runWithShellPermissionIdentity {
            mAppOps.setMode(OPSTR_RECORD_AUDIO, Process.myUid(),
                    mOpPackageName, MODE_IGNORED)
            mAppOps.setUidMode(OPSTR_RECORD_AUDIO, Process.myUid(), MODE_ERRORED)

            // Write the data to disk and read it
            mAppOps.reloadNonHistoricalState()
        }

        // Verify the uid state is preserved
        assertSame(mAppOps.unsafeCheckOpNoThrow(OPSTR_RECORD_AUDIO,
                Process.myUid(), mOpPackageName), MODE_ERRORED)

        runWithShellPermissionIdentity {
            // Clear the uid state
            mAppOps.setUidMode(OPSTR_RECORD_AUDIO, Process.myUid(),
                    AppOpsManager.opToDefaultMode(OPSTR_RECORD_AUDIO))
        }

        // Verify the package state is preserved
        assertSame(mAppOps.unsafeCheckOpNoThrow(OPSTR_RECORD_AUDIO,
                Process.myUid(), mOpPackageName), MODE_IGNORED)

        runWithShellPermissionIdentity {
            // Clear the uid state
            val defaultMode = AppOpsManager.opToDefaultMode(OPSTR_RECORD_AUDIO)
            mAppOps.setUidMode(OPSTR_RECORD_AUDIO, Process.myUid(), defaultMode)
            mAppOps.setMode(OPSTR_RECORD_AUDIO, Process.myUid(),
                    mOpPackageName, defaultMode)
        }
    }

    private fun runWithShellPermissionIdentity(command: () -> Unit) {
        val uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation()
        uiAutomation.adoptShellPermissionIdentity()
        try {
            command.invoke()
        } finally {
            uiAutomation.dropShellPermissionIdentity()
        }
    }
}
