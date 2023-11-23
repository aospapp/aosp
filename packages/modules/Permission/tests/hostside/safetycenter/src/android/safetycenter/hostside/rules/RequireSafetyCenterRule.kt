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

package android.safetycenter.hostside.rules

import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test
import com.android.tradefed.util.CommandStatus
import java.io.IOException
import org.junit.Assume.assumeTrue
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/** JUnit rule for host side tests that requires Safety Center to be supported and enabled. */
class RequireSafetyCenterRule(private val hostTestClass: BaseHostJUnit4Test) : TestRule {

    private val safetyCenterSupported: Boolean by lazy {
        executeShellCommandOrThrow("cmd safety_center supported").toBoolean()
    }
    private val safetyCenterEnabled: Boolean by lazy {
        executeShellCommandOrThrow("cmd safety_center enabled").toBoolean()
    }

    override fun apply(base: Statement, description: Description): Statement {
        return object : Statement() {
            override fun evaluate() {
                assumeTrue("Test device does not support Safety Center", safetyCenterSupported)
                assumeTrue("Safety Center is not enabled on test device", safetyCenterEnabled)
                base.evaluate()
            }
        }
    }

    /** Returns the package name of Safety Center on the test device. */
    fun getSafetyCenterPackageName(): String =
        executeShellCommandOrThrow("cmd safety_center package-name")

    private fun executeShellCommandOrThrow(command: String): String {
        val result = hostTestClass.device.executeShellV2Command(command)
        if (result.status != CommandStatus.SUCCESS) {
            throw IOException("$command exited with status ${result.exitCode}")
        }
        return result.stdout.trim()
    }
}
