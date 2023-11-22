/*
 * Copyright (C) 2019 The Android Open Source Project
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

package android.app.uiautomation.cts;

import android.app.UiAutomation;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;

import org.junit.AssumptionViolatedException;
import org.junit.rules.TestRule;
import org.junit.runner.Description;
import org.junit.runners.model.Statement;

import java.io.IOException;

/**
 * Improves UiAutomationTest logging, dumps log when a test case gets failed.
 *
 *  <ol>
 *    <li>Call {@code dumpsys accessibility}.
 *  </ol>
 */
public final class UiAutomationLogRule implements TestRule {

    private final String mTestName;

    public UiAutomationLogRule(@NonNull String testName) {
        mTestName = testName;
    }

    @Override
    public Statement apply(Statement base, Description description) {
        return new Statement() {
            @Override
            public void evaluate() throws Throwable {
                Throwable throwable = null;
                // First run the test
                try {
                    base.evaluate();
                } catch (Throwable t) {
                    throwable = t;
                }

                // Ignore AssumptionViolatedException. It's not a test fail.
                if (throwable != null && throwable instanceof AssumptionViolatedException) {
                    throwable = null;
                }

                if (throwable != null) {
                    try {
                        Log.e(mTestName, "TEST FAIL");
                        dump();
                    } catch (Throwable t) {
                        Log.e(mTestName, "Dump fail", t);
                    }
                }

                // Finally, throw exception!
                if (throwable == null) return;
                throw throwable;
            }
        };
    }

    private void dump() throws IOException {
        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation(
                UiAutomation.FLAG_DONT_SUPPRESS_ACCESSIBILITY_SERVICES);
        try {
            final String a11yDump = SystemUtil.runShellCommand(
                    uiAutomation, "dumpsys accessibility");
            Log.e(mTestName, "==== dumpsys accessibility ====\n" + a11yDump);
        } finally {
            uiAutomation.destroy();
        }
    }
}
