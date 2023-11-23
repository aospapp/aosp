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
package android.platform.test.rule

import android.app.Instrumentation
import android.os.Build
import android.support.test.uiautomator.UiDevice
import androidx.test.InstrumentationRegistry
import com.android.internal.R
import kotlin.annotation.AnnotationRetention.RUNTIME
import kotlin.annotation.AnnotationTarget.ANNOTATION_CLASS
import kotlin.annotation.AnnotationTarget.CLASS
import org.junit.AssumptionViolatedException
import org.junit.rules.TestRule
import org.junit.runner.Description
import org.junit.runners.model.Statement

/**
 * Rule that allow some tests to be executed only on [FoldableOnly] or [LargeScreenOnly] devices.
 */
class DeviceTypeRule : TestRule {

    private val isFoldable = isFoldable()
    private val isLargeScreen = isLargeScreen()

    override fun apply(base: Statement, description: Description): Statement {
        if (description.getAnnotation(LargeScreenOnly::class.java) != null && !isLargeScreen) {
            return createAssumptionViolatedStatement(
                "Skipping test on ${Build.PRODUCT} as it doesn't have a large screen.")
        }

        if (description.getAnnotation(FoldableOnly::class.java) != null && !isFoldable) {
            return createAssumptionViolatedStatement(
                "Skipping test on ${Build.PRODUCT} as it is not a foldable.")
        }

        return base
    }
}

private fun isFoldable(): Boolean {
    return getInstrumentation()
        .targetContext
        .resources
        .getIntArray(R.array.config_foldedDeviceStates)
        .isNotEmpty()
}

private fun isLargeScreen(): Boolean {
    val sizeDp = getUiDevice().displaySizeDp
    return sizeDp.x >= LARGE_SCREEN_DP_THRESHOLD && sizeDp.y >= LARGE_SCREEN_DP_THRESHOLD
}

private fun createAssumptionViolatedStatement(message: String) =
    object : Statement() {
        override fun evaluate() {
            throw AssumptionViolatedException(message)
        }
    }

private fun getInstrumentation(): Instrumentation = InstrumentationRegistry.getInstrumentation()

private fun getUiDevice(): UiDevice = UiDevice.getInstance(getInstrumentation())

private const val LARGE_SCREEN_DP_THRESHOLD = 600

@Retention(RUNTIME) @Target(ANNOTATION_CLASS, CLASS) annotation class LargeScreenOnly

@Retention(RUNTIME) @Target(ANNOTATION_CLASS, CLASS) annotation class FoldableOnly
