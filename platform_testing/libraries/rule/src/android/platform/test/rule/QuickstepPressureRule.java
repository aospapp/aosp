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

package android.platform.test.rule;

import org.junit.runner.Description;

/** This rule opens apps then goes to home before a test class. */
public class QuickstepPressureRule extends TestWatcher {
    private final String[] mApplications;
    private final long UI_RESPONSE_TIMEOUT_MSECS = 3000;

    public QuickstepPressureRule(String... applications) {
        if (applications.length == 0) {
            throw new IllegalArgumentException("Must supply an application to open.");
        }
        mApplications = applications;
    }

    @Override
    protected void starting(Description description) {
        // Force start each application in sequence.
        for (String app : mApplications) {
            executeShellCommand(String.format("am start %s", app));
            getUiDevice().waitForWindowUpdate(app, UI_RESPONSE_TIMEOUT_MSECS);
        }

        // Goes to home
        getUiDevice().pressHome();
    }
}
