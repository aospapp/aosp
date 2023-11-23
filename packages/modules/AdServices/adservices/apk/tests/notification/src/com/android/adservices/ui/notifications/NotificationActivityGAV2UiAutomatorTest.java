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
package com.android.adservices.ui.notifications;

import static com.google.common.truth.Truth.assertThat;

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
import androidx.test.uiautomator.UiSelector;
import androidx.test.uiautomator.Until;

import com.android.adservices.api.R;
import com.android.adservices.common.AdservicesTestHelper;
import com.android.adservices.common.CompatAdServicesTestUtils;
import com.android.adservices.ui.util.ApkTestUtil;
import com.android.compatibility.common.util.ShellUtils;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Spy;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class NotificationActivityGAV2UiAutomatorTest {
    private static final String NOTIFICATION_PACKAGE = "android.adservices.ui.NOTIFICATIONS";
    private static final int LAUNCH_TIMEOUT = 5000;
    private static final int SCROLL_WAIT_TIME = 2000;
    private static UiDevice sDevice =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    @Spy private Context mContext = InstrumentationRegistry.getInstrumentation().getContext();
    private String mTestName;

    @BeforeClass
    public static void classSetup() {
        if (!ApkTestUtil.isDeviceSupported()) return;
        AdservicesTestHelper.killAdservicesProcess(ApplicationProvider.getApplicationContext());
    }

    @Before
    public void setup() throws UiObjectNotFoundException, IOException {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            CompatAdServicesTestUtils.setFlags();
        }
        ShellUtils.runShellCommand("device_config put adservices ga_ux_enabled true");
        ShellUtils.runShellCommand(
                "device_config put adservices eu_notif_flow_change_enabled true");

        // Skip the test if it runs on unsupported platforms.
        Assume.assumeTrue(ApkTestUtil.isDeviceSupported());
        sDevice.pressHome();
        final String launcherPackage = sDevice.getLauncherPackageName();
        assertThat(launcherPackage).isNotNull();
        sDevice.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), LAUNCH_TIMEOUT);
    }

    @After
    public void teardown() throws Exception {
        if (!ApkTestUtil.isDeviceSupported()) return;

        ApkTestUtil.takeScreenshot(sDevice, getClass().getSimpleName() + "_" + mTestName + "_");

        AdservicesTestHelper.killAdservicesProcess(ApplicationProvider.getApplicationContext());
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
            CompatAdServicesTestUtils.resetFlagsToDefault();
        }
    }

    @Test
    public void moreButtonTest() throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        startActivity(true);
        UiObject leftControlButton =
                getElement(R.string.notificationUI_confirmation_left_control_button_text);
        UiObject rightControlButton =
                getElement(R.string.notificationUI_confirmation_right_control_button_text);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);
        assertThat(leftControlButton.exists()).isFalse();
        assertThat(rightControlButton.exists()).isFalse();
        assertThat(moreButton.exists()).isTrue();
        while (moreButton.exists()) {
            moreButton.click();
            Thread.sleep(SCROLL_WAIT_TIME);
        }
        assertThat(leftControlButton.exists()).isTrue();
        assertThat(rightControlButton.exists()).isTrue();
        assertThat(moreButton.exists()).isFalse();
    }

    @Test
    public void euAcceptFlowTest() throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        startActivity(true);
        UiObject leftControlButton =
                getElement(R.string.notificationUI_confirmation_left_control_button_text);
        UiObject rightControlButton =
                getElement(R.string.notificationUI_confirmation_right_control_button_text);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);
        assertThat(leftControlButton.exists()).isFalse();
        assertThat(rightControlButton.exists()).isFalse();
        assertThat(moreButton.exists()).isTrue();
        while (moreButton.exists()) {
            moreButton.click();
            Thread.sleep(SCROLL_WAIT_TIME);
        }
        assertThat(leftControlButton.exists()).isTrue();
        assertThat(rightControlButton.exists()).isTrue();
        assertThat(moreButton.exists()).isFalse();
        rightControlButton.click();
        UiObject title2 = getElement(R.string.notificationUI_header_ga_title_eu_v2);
        assertThat(title2.exists()).isTrue();
        leftControlButton = getElement(R.string.notificationUI_left_control_button_text_eu);
        rightControlButton = getElement(R.string.notificationUI_right_control_button_ga_text_eu_v2);
        moreButton = getElement(R.string.notificationUI_more_button_text);
        assertThat(leftControlButton.exists()).isFalse();
        assertThat(rightControlButton.exists()).isFalse();
        assertThat(moreButton.exists()).isTrue();
        while (moreButton.exists()) {
            moreButton.click();
            Thread.sleep(SCROLL_WAIT_TIME);
        }
        assertThat(leftControlButton.exists()).isTrue();
        assertThat(rightControlButton.exists()).isTrue();
        assertThat(moreButton.exists()).isFalse();
        rightControlButton.click();
        assertThat(title2.exists()).isFalse();
    }

    @Test
    public void rowClickGotItTest() throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        startActivity(false);
        UiObject leftControlButton = getElement(R.string.notificationUI_left_control_button_text);
        UiObject rightControlButton = getElement(R.string.notificationUI_right_control_button_text);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);
        assertThat(leftControlButton.exists()).isFalse();
        assertThat(rightControlButton.exists()).isFalse();
        assertThat(moreButton.exists()).isTrue();
        while (moreButton.exists()) {
            moreButton.click();
            Thread.sleep(SCROLL_WAIT_TIME);
        }
        assertThat(leftControlButton.exists()).isTrue();
        assertThat(rightControlButton.exists()).isTrue();
        assertThat(moreButton.exists()).isFalse();
        rightControlButton.click();
        assertThat(leftControlButton.exists()).isFalse();
        assertThat(rightControlButton.exists()).isFalse();
        // verify that the 2nd screen doesn't show up
        UiObject nextPageTitle = getElement(R.string.notificationUI_header_ga_title_eu_v2);
        assertThat(nextPageTitle.exists()).isFalse();
    }

    @Test
    public void rowClickSettingsTest() throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        startActivity(false);
        UiObject leftControlButton = getElement(R.string.notificationUI_left_control_button_text);
        UiObject rightControlButton = getElement(R.string.notificationUI_right_control_button_text);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);
        assertThat(leftControlButton.exists()).isFalse();
        assertThat(rightControlButton.exists()).isFalse();
        assertThat(moreButton.exists()).isTrue();
        while (moreButton.exists()) {
            moreButton.click();
            Thread.sleep(SCROLL_WAIT_TIME);
        }
        assertThat(leftControlButton.exists()).isTrue();
        assertThat(rightControlButton.exists()).isTrue();
        assertThat(moreButton.exists()).isFalse();
        leftControlButton.click();
        UiObject topicsTitle = getElement(R.string.settingsUI_topics_ga_title);
        ApkTestUtil.scrollTo(sDevice, R.string.settingsUI_topics_ga_title);
        assertThat(topicsTitle.exists()).isTrue();
        UiObject appsTitle = getElement(R.string.settingsUI_apps_ga_title);
        ApkTestUtil.scrollTo(sDevice, R.string.settingsUI_apps_ga_title);
        assertThat(appsTitle.exists()).isTrue();
    }

    private void startActivity(boolean isEUActivity) {
        String notificationPackage = NOTIFICATION_PACKAGE;
        Intent intent = new Intent(notificationPackage);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("isEUDevice", isEUActivity);
        ApplicationProvider.getApplicationContext().startActivity(intent);
        sDevice.wait(Until.hasObject(By.pkg(notificationPackage).depth(0)), LAUNCH_TIMEOUT);
    }

    private String getString(int resourceId) {
        return ApplicationProvider.getApplicationContext().getResources().getString(resourceId);
    }

    private UiObject getElement(int resId) {
        return sDevice.findObject(new UiSelector().text(getString(resId)));
    }

    private boolean isDefaultBrowserOpenedAfterClicksOnTheBottomOfSentence(
            String packageNameOfDefaultBrowser, UiObject sentence, int countOfClicks)
            throws Exception {
        int right = sentence.getBounds().right,
                bottom = sentence.getBounds().bottom,
                left = sentence.getBounds().left;
        for (int x = left; x < right; x += (right - left) / countOfClicks) {
            sDevice.click(x, bottom - 2);
            Thread.sleep(200);
        }

        if (!sentence.exists()) {
            sDevice.pressBack();
            ApkTestUtil.killDefaultBrowserPkgName(sDevice, mContext);
            return true;
        }

        return false;
    }
}
