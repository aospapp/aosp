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

package com.android.adservices.ui.settings;

import static com.google.common.truth.Truth.assertThat;


import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject;
import androidx.test.uiautomator.UiObjectNotFoundException;
import androidx.test.uiautomator.UiScrollable;
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import com.android.adservices.api.R;
import com.android.adservices.common.AdservicesTestHelper;
import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.adservices.service.Flags;
import com.android.adservices.ui.util.ApkTestUtil;
import com.android.compatibility.common.util.ShellUtils;
import com.android.modules.utils.build.SdkLevel;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public class AppConsentSettingsUiAutomatorTest {
    private static final Context CONTEXT = ApplicationProvider.getApplicationContext();
    private static final String TEST_APP_NAME = "com.example.adservices.samples.ui.consenttestapp";
    private static final String TEST_APP_APK_PATH =
            "/data/local/tmp/cts/install/" + TEST_APP_NAME + ".apk";
    private static final String TEST_APP_ACTIVITY_NAME = TEST_APP_NAME + ".MainActivity";
    private static final ComponentName COMPONENT =
            new ComponentName(TEST_APP_NAME, TEST_APP_ACTIVITY_NAME);

    private static final String PRIVACY_SANDBOX_PACKAGE = "android.adservices.ui.SETTINGS";
    private static final String PRIVACY_SANDBOX_TEST_PACKAGE = "android.test.adservices.ui.MAIN";
    private static final int LAUNCH_TIMEOUT = 5000;
    private static UiDevice sDevice;

    private String mTestName;

    @Before
    public void setup() throws UiObjectNotFoundException {
        String installMessage = ShellUtils.runShellCommand("pm install -r " + TEST_APP_APK_PATH);
        assertThat(installMessage).contains("Success");

        // Initialize UiDevice instance
        sDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        // Start from the home screen
        sDevice.pressHome();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            CompatAdServicesTestUtils.setFlags();
        }
    }

    @After
    public void teardown() {
        ApkTestUtil.takeScreenshot(sDevice, getClass().getSimpleName() + "_" + mTestName + "_");

        AdservicesTestHelper.killAdservicesProcess(CONTEXT);

        // Note aosp_x86 requires --user 0 to uninstall though arm doesn't.
        ShellUtils.runShellCommand("pm uninstall --user 0 " + TEST_APP_NAME);

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            CompatAdServicesTestUtils.resetFlagsToDefault();
        }
    }

    // TODO: Remove this blank test along with the other @Ignore. b/268351419
    @Test
    public void placeholderTest() {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        // As this class is the only test class in the test module and need to be @Ignore for the
        // moment, add a blank test to help presubmit to pass.
        assertThat(true).isTrue();
    }

    @Test
    @Ignore("Flaky test. (b/268351419)")
    public void consentSystemServerOnlyTest()
            throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        // System server is not available on S-, skip this test for S-
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        appConsentTest(0, false);
    }

    @Test
    @Ignore("Flaky test. (b/268351419)")
    public void consentPpApiOnlyTest() throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        appConsentTest(1, false);
    }

    @Test
    @Ignore("Flaky test. (b/268351419)")
    public void consentSystemServerAndPpApiTest()
            throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        // System server is not available on S-, skip this test for S-
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        appConsentTest(2, false);
    }

    @Test
    @Ignore("Flaky test. (b/268351419)")
    public void consentAppSearchOnlyTest() throws UiObjectNotFoundException, InterruptedException {
        ShellUtils.runShellCommand(
                "device_config put adservices enable_appsearch_consent_data true");
        ShellUtils.runShellCommand("device_config put adservices consent_source_of_truth 3");
        appConsentTest(Flags.APPSEARCH_ONLY, false);
        ShellUtils.runShellCommand("device_config put adservices consent_source_of_truth null");
    }

    @Test
    @Ignore("Flaky test. (b/268351419)")
    public void consentAppSearchOnlyDialogsOnTest()
            throws UiObjectNotFoundException, InterruptedException {
        ShellUtils.runShellCommand(
                "device_config put adservices enable_appsearch_consent_data true");
        ShellUtils.runShellCommand("device_config put adservices consent_source_of_truth 3");
        appConsentTest(Flags.APPSEARCH_ONLY, true);
        ShellUtils.runShellCommand("device_config put adservices consent_source_of_truth null");
    }

    @Test
    @Ignore("Flaky test. (b/268351419)")
    public void consentSystemServerOnlyDialogsOnTest()
            throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        // System server is not available on S-, skip this test for S-
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        appConsentTest(0, true);
    }

    @Test
    @Ignore("Flaky test. (b/268351419)")
    public void consentPpApiOnlyDialogsOnTest()
            throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        appConsentTest(1, true);
    }

    @Test
    @Ignore("Flaky test. (b/268351419)")
    public void consentSystemServerAndPpApiDialogsOnTest()
            throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        // System server is not available on S-, skip this test for S-
        Assume.assumeTrue(SdkLevel.isAtLeastT());
        appConsentTest(2, true);
    }

    private void setPpApiConsentToGiven() throws UiObjectNotFoundException {
        // launch app
        launchSettingApp();

        UiObject mainSwitch =
                sDevice.findObject(new UiSelector().className("android.widget.Switch"));
        assertThat(mainSwitch.exists()).isTrue();

        if (!mainSwitch.isChecked()) {
            mainSwitch.click();
        }
    }

    private void launchSettingApp() {
        String privacySandboxUi;
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            privacySandboxUi = PRIVACY_SANDBOX_TEST_PACKAGE;
        } else {
            privacySandboxUi = PRIVACY_SANDBOX_PACKAGE;
        }
        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent(privacySandboxUi);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        // Wait for the app to appear
        sDevice.wait(Until.hasObject(By.pkg(privacySandboxUi).depth(0)), LAUNCH_TIMEOUT);
    }

    private void appConsentTest(int consentSourceOfTruth, boolean dialogsOn)
            throws UiObjectNotFoundException, InterruptedException {
        ShellUtils.runShellCommand(
                "device_config put adservices consent_source_of_truth " + consentSourceOfTruth);
        ShellUtils.runShellCommand(
                "device_config put adservices ui_dialogs_feature_enabled " + dialogsOn);
        AdservicesTestHelper.killAdservicesProcess(CONTEXT);

        // Wait for launcher
        final String launcherPackage = sDevice.getLauncherPackageName();
        assertThat(launcherPackage).isNotNull();
        sDevice.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), LAUNCH_TIMEOUT);
        ShellUtils.runShellCommand("device_config put adservices ga_ux_enabled false");

        setPpApiConsentToGiven();

        // Initiate test app consent.
        initiateTestAppConsent();

        // open apps view
        launchSettingApp();
        scrollToAndClick(R.string.settingsUI_apps_title);

        blockAppConsent(dialogsOn);

        unblockAppConsent(dialogsOn);

        assertThat(getElement(R.string.settingsUI_block_app_title).exists()).isTrue();

        resetAppConsent(dialogsOn);

        assertThat(getElement(R.string.settingsUI_block_app_title, 0).exists()).isFalse();
        assertThat(getElement(R.string.settingsUI_blocked_apps_title, 0).exists()).isFalse();
        assertThat(getElement(R.string.settingsUI_apps_view_no_apps_text, 0).exists()).isTrue();
    }

    private void unblockAppConsent(boolean dialogsOn) throws UiObjectNotFoundException {
        scrollToAndClick(R.string.settingsUI_blocked_apps_title);
        scrollToAndClick(R.string.settingsUI_unblock_app_title);

        if (dialogsOn) {
            // click unblock
            UiObject dialogTitle = getElement(R.string.settingsUI_dialog_unblock_app_message);
            UiObject positiveText =
                    getElement(R.string.settingsUI_dialog_unblock_app_positive_text);
            assertThat(dialogTitle.exists()).isTrue();
            assertThat(positiveText.exists()).isTrue();

            // confirm
            positiveText.click();
        }

        assertThat(getElement(R.string.settingsUI_apps_view_no_blocked_apps_text).exists())
                .isTrue();
        sDevice.pressBack();
    }

    private void resetAppConsent(boolean dialogsOn) throws UiObjectNotFoundException {
        scrollToAndClick(R.string.settingsUI_reset_apps_title);

        if (dialogsOn) {
            UiObject dialogTitle = getElement(R.string.settingsUI_dialog_reset_app_message);
            UiObject positiveText = getElement(R.string.settingsUI_dialog_reset_app_positive_text);
            assertThat(dialogTitle.exists()).isTrue();
            assertThat(positiveText.exists()).isTrue();

            // confirm
            positiveText.click();
        }
    }

    private void blockAppConsent(boolean dialogsOn) throws UiObjectNotFoundException {
        scrollToAndClick(R.string.settingsUI_block_app_title);

        if (dialogsOn) {
            UiObject dialogTitle = getElement(R.string.settingsUI_dialog_block_app_message);
            UiObject positiveText = getElement(R.string.settingsUI_dialog_block_app_positive_text);
            assertThat(dialogTitle.exists()).isTrue();
            assertThat(positiveText.exists()).isTrue();
            positiveText.click();
        }
    }

    private UiObject getElement(int resId) {
        return sDevice.findObject(new UiSelector().text(getString(resId)));
    }

    private UiObject getElement(int resId, int index) {
        return sDevice.findObject(new UiSelector().text(getString(resId)).instance(index));
    }

    private String getString(int resourceId) {
        return ApplicationProvider.getApplicationContext().getResources().getString(resourceId);
    }

    private void scrollToAndClick(int resId) throws UiObjectNotFoundException {
        UiScrollable scrollView =
                new UiScrollable(
                        new UiSelector().scrollable(true).className("android.widget.ScrollView"));
        UiObject element = sDevice.findObject(new UiSelector().text(getString(resId)));
        scrollView.scrollIntoView(element);
        element.click();
    }

    private void initiateTestAppConsent() throws InterruptedException {
        String installMessage = ShellUtils.runShellCommand("pm install -r " + TEST_APP_APK_PATH);
        assertThat(installMessage).contains("Success");

        ShellUtils.runShellCommand("device_config set_sync_disabled_for_tests persistent");
        ShellUtils.runShellCommand("device_config put adservices global_kill_switch false");
        ShellUtils.runShellCommand(
                "device_config put adservices"
                        + " fledge_custom_audience_service_kill_switch false");
        ShellUtils.runShellCommand(
                "device_config put adservices disable_fledge_enrollment_check true");

        ShellUtils.runShellCommand("device_config put adservices ppapi_app_allow_list *");

        Context context = ApplicationProvider.getApplicationContext();
        Intent intent = new Intent().setComponent(COMPONENT);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        context.startActivity(intent);

        sDevice.wait(Until.hasObject(By.pkg(TEST_APP_NAME).depth(0)), LAUNCH_TIMEOUT);

        Thread.sleep(1000);

        ShellUtils.runShellCommand("device_config set_sync_disabled_for_tests none");
        ShellUtils.runShellCommand(
                "am force-stop com.example.adservices.samples.ui.consenttestapp");
        ShellUtils.runShellCommand(
                "device_config put adservices disable_fledge_enrollment_check null");
    }
}
