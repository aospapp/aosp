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

package android.adservices.test.scenario.adservices.ui;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.platform.test.scenario.annotation.Scenario;
import android.util.Log;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import com.android.adservices.api.R;
import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.compatibility.common.util.ShellUtils;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Crystalball test for Topics API to collect System Heath metrics. */
@Scenario
@RunWith(JUnit4.class)
public class UiSettingsMainPage {
    private static final String TAG = "UiTestLabel";
    private static final String UI_SETTINGS_LATENCY_METRIC = "UI_SETTINGS_LATENCY_METRIC";

    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final int LAUNCH_TIMEOUT = 8000;
    public static final int PRIMITIVE_UI_OBJECTS_LAUNCH_TIMEOUT = 500;
    private static final String PRIVACY_SANDBOX_UI = "android.adservices.ui.SETTINGS";
    private static final String PRIVACY_SANDBOX_TEST_UI = "android.test.adservices.ui.MAIN";
    private static UiDevice sDevice;

    @Before
    public void setup() {
        disableGlobalKillSwitch();
        enableGa();
        // Initialize UiDevice instance
        sDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        // Extra flags need to be set when test is executed on S- for service to run (e.g.
        // to avoid invoking system-server related code).
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.setFlags();
        }
    }

    @After
    public void teardown() {
        if (!SdkLevel.isAtLeastT()) {
            CompatAdServicesTestUtils.resetFlagsToDefault();
        }
    }

    @Test
    public void testSettingsPage() throws Exception {
        String privacySandboxUi;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            privacySandboxUi = PRIVACY_SANDBOX_TEST_UI;
        } else {
            privacySandboxUi = PRIVACY_SANDBOX_UI;
        }
        final long start = System.currentTimeMillis();
        // Launch the setting view.
        Intent intent = new Intent(privacySandboxUi);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        sContext.startActivity(intent);

        // Wait for the view to appear
        sDevice.wait(Until.hasObject(By.pkg(privacySandboxUi).depth(0)), LAUNCH_TIMEOUT);

        scrollAndClickButton(R.string.settingsUI_topics_ga_title);
        scrollAndClickButton(R.string.settingsUI_apps_ga_title);
        scrollAndClickButton(R.string.settingsUI_measurement_view_title);
        final long duration = System.currentTimeMillis() - start;
        Log.i(TAG, "(" + UI_SETTINGS_LATENCY_METRIC + ": " + duration + ")");
    }

    public void scrollAndClickButton(int resId) throws Exception {
        scrollTo(resId);
        UiObject consentPageButton = getElement(resId);
        consentPageButton.waitForExists(PRIMITIVE_UI_OBJECTS_LAUNCH_TIMEOUT);
        assertThat(consentPageButton.exists()).isTrue();
        consentPageButton.click();
        flipConsent();
    }

    public void flipConsent() throws Exception {
        UiObject consentSwitch =
                sDevice.findObject(new UiSelector().className("android.widget.Switch"));
        consentSwitch.waitForExists(PRIMITIVE_UI_OBJECTS_LAUNCH_TIMEOUT);
        assertThat(consentSwitch.exists()).isTrue();
        boolean consentStatus = consentSwitch.isChecked();
        consentSwitch.click();
        Thread.sleep(PRIMITIVE_UI_OBJECTS_LAUNCH_TIMEOUT);
        assertThat(consentSwitch.isChecked()).isEqualTo(!consentStatus);
        sDevice.pressBack();
    }

    // Override global_kill_switch to ignore the effect of actual PH values.
    protected void disableGlobalKillSwitch() {
        ShellUtils.runShellCommand("device_config put adservices global_kill_switch false");
    }

    protected void enableGa() {
        ShellUtils.runShellCommand("device_config put adservices ga_ux_enabled true");
    }

    private UiObject scrollTo(int resId) throws UiObjectNotFoundException {
        UiScrollable scrollView =
                new UiScrollable(
                        new UiSelector().scrollable(true).className("android.widget.ScrollView"));
        UiObject element = getPageElement(resId);
        scrollView.scrollIntoView(element);
        return element;
    }

    public UiObject getPageElement(int resId) {
        return sDevice.findObject(new UiSelector().text(getString(resId)));
    }

    public String getString(int resourceId) {
        return sContext.getResources().getString(resourceId);
    }

    public UiObject getElement(int resId) {
        UiObject obj = sDevice.findObject(new UiSelector().text(getString(resId)));
        if (!obj.exists()) {
            obj = sDevice.findObject(new UiSelector().text(getString(resId).toUpperCase()));
        }
        return obj;
    }
}
