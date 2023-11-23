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

package android.platform.test.rule.flicker;

import android.util.Log;

import com.android.server.wm.flicker.traces.windowmanager.WindowManagerTraceSubject;
import com.android.server.wm.traces.common.FlickerComponentName;
import com.android.server.wm.traces.common.windowmanager.WindowManagerTrace;

/**
 * Rule used for validating the common window manager trace based flicker assertions applicable
 * for all the CUJ's
 */
public class WindowManagerFlickerRuleCommon extends WindowManagerFlickerRuleBase {

    private static final String TAG = WindowManagerFlickerRuleCommon.class.getSimpleName();
    private static final FlickerComponentName NAV_BAR_COMPONENT =
            new FlickerComponentName("", "NavigationBar0");
    private static final FlickerComponentName STATUS_BAR_COMPONENT =
            new FlickerComponentName("", "StatusBar");

    protected void validateWMFlickerConditions(WindowManagerTrace wmTrace) {
        // Verify that there’s an non-app window with names NavigationBar, StatusBar above
        // the app window and is visible in all winscope log entries.
        WindowManagerTraceSubject.assertThat(wmTrace)
                .isAboveAppWindowVisible(NAV_BAR_COMPONENT)
                .isAboveAppWindowVisible(STATUS_BAR_COMPONENT)
                .forAllEntries();

        // Verify that all visible windows are visible for more than one consecutive entry
        // in the log entries.
        WindowManagerTraceSubject.assertThat(wmTrace)
                .visibleWindowsShownMoreThanOneConsecutiveEntry()
                .forAllEntries();
        Log.v(TAG, "Successfully verified the window manager flicker conditions.");
    }
}
