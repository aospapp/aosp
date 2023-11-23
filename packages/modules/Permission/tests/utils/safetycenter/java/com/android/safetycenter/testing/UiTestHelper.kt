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

package com.android.safetycenter.testing

import android.content.Context
import android.os.Build.VERSION_CODES.TIRAMISU
import android.os.SystemClock
import android.safetycenter.SafetySourceData
import android.safetycenter.SafetySourceIssue
import android.safetycenter.config.SafetySourcesGroup
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.test.uiautomator.By
import androidx.test.uiautomator.BySelector
import androidx.test.uiautomator.StaleObjectException
import androidx.test.uiautomator.UiDevice
import androidx.test.uiautomator.UiObject2
import com.android.compatibility.common.util.SystemUtil.runShellCommand
import com.android.compatibility.common.util.UiAutomatorUtils2.getUiDevice
import com.android.compatibility.common.util.UiAutomatorUtils2.waitFindObject
import com.android.compatibility.common.util.UiAutomatorUtils2.waitFindObjectOrNull
import java.time.Duration
import java.util.concurrent.TimeoutException
import java.util.regex.Pattern

/** A class that helps with UI testing. */
object UiTestHelper {

    /** The label of the rescan button. */
    const val RESCAN_BUTTON_LABEL = "Scan device"
    /** The title of collapsible card that controls the visibility of additional issue cards. */
    const val MORE_ISSUES_LABEL = "More alerts"

    private const val DISMISS_ISSUE_LABEL = "Dismiss"
    private val WAIT_TIMEOUT = Duration.ofSeconds(10)
    private val NOT_DISPLAYED_TIMEOUT = Duration.ofMillis(500)

    private val TAG = UiTestHelper::class.java.simpleName

    /**
     * Waits for the given [selector] to be displayed and performs the given [uiObjectAction] on it.
     */
    fun waitDisplayed(selector: BySelector, uiObjectAction: (UiObject2) -> Unit = {}) {
        waitFor("$selector to be displayed", WAIT_TIMEOUT) {
            uiObjectAction(waitFindObject(selector, it.toMillis()))
            true
        }
    }

    /** Waits for all the given [textToFind] to be displayed. */
    fun waitAllTextDisplayed(vararg textToFind: CharSequence?) {
        for (text in textToFind) {
            if (text != null) waitDisplayed(By.text(text.toString()))
        }
    }

    /**
     * Waits for a button with the given [label] to be displayed and performs the given
     * [uiObjectAction] on it.
     */
    fun waitButtonDisplayed(label: CharSequence, uiObjectAction: (UiObject2) -> Unit = {}) =
        waitDisplayed(buttonSelector(label), uiObjectAction)

    /** Waits for the given [selector] not to be displayed. */
    fun waitNotDisplayed(selector: BySelector) {
        waitFor("$selector not to be displayed", NOT_DISPLAYED_TIMEOUT) {
            waitFindObjectOrNull(selector, it.toMillis()) == null
        }
    }

    /** Waits for all the given [textToFind] not to be displayed. */
    fun waitAllTextNotDisplayed(vararg textToFind: CharSequence?) {
        for (text in textToFind) {
            if (text != null) waitNotDisplayed(By.text(text.toString()))
        }
    }

    /** Waits for a button with the given [label] not to be displayed. */
    fun waitButtonNotDisplayed(label: CharSequence) {
        waitNotDisplayed(buttonSelector(label))
    }

    /**
     * Waits for most of the [SafetySourceData] information to be displayed.
     *
     * This includes its UI entry and its issues.
     */
    @RequiresApi(TIRAMISU)
    fun waitSourceDataDisplayed(sourceData: SafetySourceData) {
        waitAllTextDisplayed(sourceData.status?.title, sourceData.status?.summary)

        for (sourceIssue in sourceData.issues) {
            waitSourceIssueDisplayed(sourceIssue)
        }
    }

    /** Waits for most of the [SafetySourceIssue] information to be displayed. */
    @RequiresApi(TIRAMISU)
    fun waitSourceIssueDisplayed(sourceIssue: SafetySourceIssue) {
        waitAllTextDisplayed(sourceIssue.title, sourceIssue.subtitle, sourceIssue.summary)

        for (action in sourceIssue.actions) {
            waitButtonDisplayed(action.label)
        }
    }

    /** Waits for most of the [SafetySourceIssue] information not to be displayed. */
    @RequiresApi(TIRAMISU)
    fun waitSourceIssueNotDisplayed(sourceIssue: SafetySourceIssue) {
        waitAllTextNotDisplayed(sourceIssue.title)
    }

    /**
     * Waits for only one [SafetySourceIssue] to be displayed together with [MORE_ISSUES_LABEL]
     * card, and for all other [SafetySourceIssue]s not to be diplayed.
     */
    fun waitCollapsedIssuesDisplayed(vararg sourceIssues: SafetySourceIssue) {
        waitSourceIssueDisplayed(sourceIssues.first())
        waitAllTextDisplayed(MORE_ISSUES_LABEL)
        sourceIssues.asSequence().drop(1).forEach { waitSourceIssueNotDisplayed(it) }
    }

    /** Waits for all the [SafetySourceIssue] to be displayed with the [MORE_ISSUES_LABEL] card. */
    fun waitExpandedIssuesDisplayed(vararg sourceIssues: SafetySourceIssue) {
        // to make landscape checks less flaky it is important to match their order with visuals
        waitSourceIssueDisplayed(sourceIssues.first())
        waitAllTextDisplayed(MORE_ISSUES_LABEL)
        sourceIssues.asSequence().drop(1).forEach { waitSourceIssueDisplayed(it) }
    }

    /** Waits for the specified screen title to be displayed. */
    fun waitPageTitleDisplayed(title: String) {
        // CollapsingToolbar title can't be found by text, so using description instead.
        waitDisplayed(By.desc(title))
    }

    /** Waits for the specified screen title not to be displayed. */
    fun waitPageTitleNotDisplayed(title: String) {
        // CollapsingToolbar title can't be found by text, so using description instead.
        waitNotDisplayed(By.desc(title))
    }

    /** Waits for the group title and summary to be displayed on the homepage */
    fun waitGroupShownOnHomepage(context: Context, group: SafetySourcesGroup) {
        waitAllTextDisplayed(
            context.getString(group.titleResId),
            context.getString(group.summaryResId)
        )
    }

    /** Dismisses the issue card by clicking the dismiss button. */
    fun clickDismissIssueCard() {
        waitDisplayed(By.desc(DISMISS_ISSUE_LABEL)) { it.click() }
    }

    /** Confirms the dismiss action by clicking on the dialog that pops up. */
    fun clickConfirmDismissal() {
        waitButtonDisplayed(DISMISS_ISSUE_LABEL) { it.click() }
    }

    /** Clicks the brand chip button on a subpage in Safety Center. */
    fun clickSubpageBrandChip() {
        waitButtonDisplayed("Security & privacy") { it.click() }
    }

    /** Opens the subpage by clicking on the group title. */
    fun clickOpenSubpage(context: Context, group: SafetySourcesGroup) {
        waitDisplayed(By.text(context.getString(group.titleResId))) { it.click() }
    }

    /** Clicks the more issues card button to show or hide additional issues. */
    fun clickMoreIssuesCard() {
        waitDisplayed(By.text(MORE_ISSUES_LABEL)) { it.click() }
    }

    /** Enables or disables animations based on [enabled]. */
    fun setAnimationsEnabled(enabled: Boolean) {
        val scale =
            if (enabled) {
                "1"
            } else {
                "0"
            }
        runShellCommand("settings put global window_animation_scale $scale")
        runShellCommand("settings put global transition_animation_scale $scale")
        runShellCommand("settings put global animator_duration_scale $scale")
    }

    fun UiDevice.rotate() {
        unfreezeRotation()
        if (isNaturalOrientation) {
            setOrientationLeft()
        } else {
            setOrientationNatural()
        }
        freezeRotation()
        waitForIdle()
    }

    fun UiDevice.resetRotation() {
        if (!isNaturalOrientation) {
            unfreezeRotation()
            setOrientationNatural()
            freezeRotation()
            waitForIdle()
        }
    }

    private fun buttonSelector(label: CharSequence): BySelector {
        return By.clickable(true).text(Pattern.compile("$label|${label.toString().uppercase()}"))
    }

    private fun waitFor(
        message: String,
        uiAutomatorConditionTimeout: Duration,
        uiAutomatorCondition: (Duration) -> Boolean
    ) {
        val elapsedStartMillis = SystemClock.elapsedRealtime()
        while (true) {
            getUiDevice().waitForIdle()
            val durationSinceStart =
                Duration.ofMillis(SystemClock.elapsedRealtime() - elapsedStartMillis)
            if (durationSinceStart >= WAIT_TIMEOUT) {
                break
            }
            val remainingTime = WAIT_TIMEOUT - durationSinceStart
            val uiAutomatorTimeout = minOf(uiAutomatorConditionTimeout, remainingTime)
            try {
                if (uiAutomatorCondition(uiAutomatorTimeout)) {
                    return
                } else {
                    Log.d(TAG, "Failed condition for $message, will retry if within timeout")
                }
            } catch (e: StaleObjectException) {
                Log.d(TAG, "StaleObjectException for $message, will retry if within timeout", e)
            }
        }

        throw TimeoutException("Timed out waiting for $message")
    }
}
