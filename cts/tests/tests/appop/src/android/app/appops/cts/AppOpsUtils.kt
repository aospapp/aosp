/*
 * Copyright (C) 2018 Google Inc.
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

package android.app.appops.cts

import androidx.test.InstrumentationRegistry
import com.android.compatibility.common.util.SystemUtil

import android.app.AppOpsManager.MODE_ALLOWED
import android.app.AppOpsManager.MODE_DEFAULT
import android.app.AppOpsManager.MODE_ERRORED
import android.app.AppOpsManager.MODE_IGNORED
import com.android.compatibility.common.util.ThrowingRunnable

/**
 * Utilities for controlling App Ops settings, and testing whether ops are logged.
 */
class AppOpsUtils {
    companion object {
        /**
         * Resets a package's app ops configuration to the device default. See AppOpsManager for the
         * default op settings.
         *
         * <p>
         * It's recommended to call this in setUp() and tearDown() of your test so the test starts and
         * ends with a reproducible default state, and so doesn't affect other tests.
         *
         * <p>
         * Some app ops are configured to be non-resettable, which means that the state of these will
         * not be reset even when calling this method.
         */
        fun reset(packageName: String): String {
            return runCommand("appops reset $packageName")
        }

        /**
         * Sets the app op mode (e.g. allowed, denied) for a single package and operation.
         */
        fun setOpMode(packageName: String, opStr: String, mode: Int) : String {
            val modeStr: String
            when (mode) {
                MODE_ALLOWED -> modeStr = "allow"
                MODE_ERRORED -> modeStr = "deny"
                MODE_IGNORED -> modeStr = "ignore"
                MODE_DEFAULT -> modeStr = "default"
                else -> throw IllegalArgumentException("Unexpected app op type")
            }
            val command = "appops set $packageName $opStr $modeStr"
            return runCommand(command)
        }

        /**
         * Get the app op mode (e.g. MODE_ALLOWED, MODE_DEFAULT) for a single package and operation.
         */
        fun getOpMode(packageName: String, opStr: String) : Int {
            val opState = getOpState(packageName, opStr)
            when {
                opState.contains(" allow") -> return MODE_ALLOWED
                opState.contains(" deny") -> return MODE_ERRORED
                opState.contains(" ignore") -> return MODE_IGNORED
                opState.contains(" default") -> return MODE_DEFAULT
                else -> throw IllegalStateException ("Unexpected app op mode returned $opState")
            }
        }

        /**
         * Returns whether an allowed operation has been logged by the AppOpsManager for a
         * package. Operations are noted when the app attempts to perform them and calls e.g.
         * {@link AppOpsManager#noteOperation}.
         *
         * @param opStr The public string constant of the operation (e.g. OPSTR_READ_SMS).
         */
        fun allowedOperationLogged(packageName: String, opStr: String): Boolean {
            return getOpState(packageName, opStr).contains(" time=")
        }

        /**
         * Returns whether a rejected operation has been logged by the AppOpsManager for a
         * package. Operations are noted when the app attempts to perform them and calls e.g.
         * {@link AppOpsManager#noteOperation}.
         *
         * @param opStr The public string constant of the operation (e.g. OPSTR_READ_SMS).
         */
        fun rejectedOperationLogged(packageName: String, opStr: String) : Boolean {
            return getOpState(packageName, opStr).contains(" rejectTime=")
        }

        /**
         * Runs a [ThrowingRunnable] adopting Shell's permissions.
         */
        fun runWithShellPermissionIdentity(runnable: ThrowingRunnable) {
            val uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation()
            uiAutomation.adoptShellPermissionIdentity()
            try {
                runnable.run()
            } catch (e: Exception) {
                throw RuntimeException("Caught exception", e)
            } finally {
                uiAutomation.dropShellPermissionIdentity()
            }
        }

        /**
         * Returns the app op state for a package. Includes information on when the operation
         * was last attempted to be performed by the package.
         *
         * Format: "SEND_SMS: allow; time=+23h12m54s980ms ago; rejectTime=+1h10m23s180ms"
         */
        private fun getOpState(packageName: String, opStr: String) : String {
            return runCommand("appops get $packageName $opStr")
        }

        private fun runCommand(command: String ) : String {
            return SystemUtil.runShellCommand(InstrumentationRegistry.getInstrumentation(), command)
        }
    }
}
