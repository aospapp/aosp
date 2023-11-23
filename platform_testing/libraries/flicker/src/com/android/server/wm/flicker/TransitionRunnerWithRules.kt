/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.server.wm.flicker

import android.platform.test.rule.NavigationModeRule
import android.platform.test.rule.PressHomeRule
import android.platform.test.rule.UnlockScreenRule
import com.android.server.wm.flicker.helpers.SampleAppHelper
import com.android.server.wm.flicker.rules.ChangeDisplayOrientationRule
import com.android.server.wm.flicker.rules.LaunchAppRule
import com.android.server.wm.flicker.rules.RemoveAllTasksButHomeRule
import org.junit.rules.RuleChain
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Execute the transitions of a flicker test using JUnit rules and statements.
 *
 * Allow for easier reuse of test rules
 */
class TransitionRunnerWithRules(private val testConfig: Map<String, Any?>) : TransitionRunner() {
    private var result: FlickerResult? = null

    /**
     * Create the default flicker test setup rules. In order:
     *   - unlock device
     *   - change orientation
     *   - change navigation mode
     *   - launch an app
     *   - remove all apps
     *   - go to home screen
     *
     * (b/186740751) An app should be launched because, after changing the navigation mode,
     * the first app launch is handled as a screen size change (similar to a rotation), this
     * causes different problems during testing (e.g. IME now shown on app launch)
     */
    private fun buildDefaultSetupRules(flicker: Flicker): RuleChain {
        return RuleChain.outerRule(UnlockScreenRule())
            .around(NavigationModeRule(testConfig.navBarMode))
            .around(LaunchAppRule(SampleAppHelper(flicker.instrumentation)))
            .around(RemoveAllTasksButHomeRule())
            .around(ChangeDisplayOrientationRule(testConfig.startRotation))
            .around(PressHomeRule())
    }

    private fun buildTransitionRule(flicker: Flicker): Statement {
        return object : Statement() {
                override fun evaluate() {
                    result = runTransition(flicker)
                }
            }
    }

    private fun runTransition(flicker: Flicker): FlickerResult {
        return super.run(flicker)
    }

    private fun buildTransitionChain(flicker: Flicker): Statement {
        val setupRules = buildDefaultSetupRules(flicker)
        val transitionRule = buildTransitionRule(flicker)
        return setupRules.apply(transitionRule, Description.EMPTY)
    }

    override fun cleanUp() {
        super.cleanUp()
        result = null
    }

    /**
     * Runs the actual setup, transitions and teardown defined in [flicker]
     *
     * @param flicker test specification
     */
    override fun run(flicker: Flicker): FlickerResult {
        try {
            val transitionChain = buildTransitionChain(flicker)
            transitionChain.evaluate()
            return result ?: error("Transition did not run")
        } finally {
            cleanUp()
        }
    }
}