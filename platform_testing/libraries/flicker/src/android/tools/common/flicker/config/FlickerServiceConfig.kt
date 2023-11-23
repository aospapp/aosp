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

package android.tools.common.flicker.config

import android.tools.common.flicker.config.appclose.AppClose
import android.tools.common.flicker.config.applaunch.AppLaunch
import android.tools.common.flicker.config.foldables.Foldables
import android.tools.common.flicker.config.gesturenav.GestureNav
import android.tools.common.flicker.config.ime.Ime
import android.tools.common.flicker.config.launcher.Launcher
import android.tools.common.flicker.config.lockscreen.Lockscreen
import android.tools.common.flicker.config.notification.Notification
import android.tools.common.flicker.config.others.Others
import android.tools.common.flicker.config.pip.Pip
import android.tools.common.flicker.config.settings.Settings
import android.tools.common.flicker.config.splashscreen.Splashscreen
import android.tools.common.flicker.config.splitscreen.SplitScreen
import android.tools.common.flicker.config.suw.Suw
import android.tools.common.flicker.config.taskbar.Taskbar
import android.tools.common.flicker.config.wallpaper.Wallpaper
import android.tools.common.flicker.extractors.IScenarioExtractor

object FlickerServiceConfig {
    private val supportedScenarios =
        listOf(
                AppClose.SCENARIOS,
                AppLaunch.SCENARIOS,
                Foldables.SCENARIOS,
                GestureNav.SCENARIOS,
                Ime.SCENARIOS,
                Launcher.SCENARIOS,
                Lockscreen.SCENARIOS,
                Notification.SCENARIOS,
                Others.SCENARIOS,
                Pip.SCENARIOS,
                Settings.SCENARIOS,
                Splashscreen.SCENARIOS,
                SplitScreen.SCENARIOS,
                Suw.SCENARIOS,
                Taskbar.SCENARIOS,
                Wallpaper.SCENARIOS
            )
            .flatten()

    /** EDIT THIS CONFIG TO ADD SCENARIOS TO FAAS */
    fun getScenarioConfigFor(type: FaasScenarioType): IScenarioConfig =
        supportedScenarios.firstOrNull { it.type == type }
            ?: error("Scenario $type is not supported")

    fun getExtractors(enabledOnly: Boolean = true): List<IScenarioExtractor> {
        val scenarios: Collection<FaasScenarioType> =
            if (enabledOnly) {
                FaasScenarioType.values().filter { getScenarioConfigFor(it).enabled }
            } else {
                FaasScenarioType.values().asList()
            }
        return scenarios.map { getScenarioConfigFor(it).extractor }
    }
}
