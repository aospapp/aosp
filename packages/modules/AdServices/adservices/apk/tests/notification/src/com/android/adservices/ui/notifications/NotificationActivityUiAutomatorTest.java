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

import static org.mockito.Matchers.any;
import static org.mockito.Mockito.doReturn;

import android.content.Context;
import android.content.Intent;

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
import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.PhFlags;
import com.android.adservices.service.common.BackgroundJobsManager;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.ui.util.ApkTestUtil;
import com.android.dx.mockito.inline.extended.ExtendedMockito;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.Spy;
import org.mockito.quality.Strictness;

import java.io.IOException;

@RunWith(AndroidJUnit4.class)
public class NotificationActivityUiAutomatorTest {
    private static final String NOTIFICATION_TEST_PACKAGE =
            "android.test.adservices.ui.NOTIFICATIONS";
    private static final int LAUNCH_TIMEOUT = 5000;
    private static final UiDevice sDevice =
            UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private MockitoSession mStaticMockSession;
    private String mTestName;

    @Mock private ConsentManager mConsentManager;
    @Spy private Context mContext;

    // TODO(b/261216850): Migrate this NotificationActivity to non-mock test
    @Mock private Flags mMockFlags;

    @Before
    public void setup() throws UiObjectNotFoundException, IOException {
        // Skip the test if it runs on unsupported platforms.
        Assume.assumeTrue(ApkTestUtil.isDeviceSupported());

        mContext = InstrumentationRegistry.getInstrumentation().getContext();

        MockitoAnnotations.initMocks(this);
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(PhFlags.class)
                        .spyStatic(BackgroundJobsManager.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(ConsentManager.class)
                        .strictness(Strictness.WARN)
                        .initMocks(this)
                        .startMocking();

        doReturn(false).when(mMockFlags).getEuNotifFlowChangeEnabled();
        doReturn(true).when(mMockFlags).getUIDialogsFeatureEnabled();
        doReturn(true).when(mMockFlags).isUiFeatureTypeLoggingEnabled();
        doReturn(true).when(mMockFlags).getRecordManualInteractionEnabled();
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        ExtendedMockito.doReturn(mConsentManager)
                .when(() -> ConsentManager.getInstance(any(Context.class)));
        ExtendedMockito.doNothing()
                .when(() -> BackgroundJobsManager.scheduleAllBackgroundJobs(any(Context.class)));

        sDevice.pressHome();

        final String launcherPackage = sDevice.getLauncherPackageName();
        assertThat(launcherPackage).isNotNull();
        sDevice.wait(Until.hasObject(By.pkg(launcherPackage).depth(0)), LAUNCH_TIMEOUT);
    }

    @After
    public void teardown() throws Exception {
        if (!ApkTestUtil.isDeviceSupported()) return;

        ApkTestUtil.takeScreenshot(sDevice, getClass().getSimpleName() + "_" + mTestName + "_");

        AdservicesTestHelper.killAdservicesProcess(mContext);

        mStaticMockSession.finishMocking();
    }

    @Test
    public void moreButtonTest() throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        startActivity(true);
        UiObject leftControlButton =
                getElement(R.string.notificationUI_left_control_button_text_eu);
        UiObject rightControlButton =
                getElement(R.string.notificationUI_right_control_button_text_eu);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);
        assertThat(leftControlButton.exists()).isFalse();
        assertThat(rightControlButton.exists()).isFalse();
        assertThat(moreButton.exists()).isTrue();

        while (moreButton.exists()) {
            moreButton.click();
            Thread.sleep(2000);
        }
        assertThat(leftControlButton.exists()).isTrue();
        assertThat(rightControlButton.exists()).isTrue();
        assertThat(moreButton.exists()).isFalse();
    }

    @Test
    public void acceptedConfirmationScreenTest()
            throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(false).when(mMockFlags).getGaUxFeatureEnabled();

        startActivity(true);
        UiObject leftControlButton =
                getElement(R.string.notificationUI_left_control_button_text_eu);
        UiObject rightControlButton =
                getElement(R.string.notificationUI_right_control_button_text_eu);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);
        assertThat(leftControlButton.exists()).isFalse();
        assertThat(rightControlButton.exists()).isFalse();
        assertThat(moreButton.exists()).isTrue();

        while (moreButton.exists()) {
            moreButton.click();
            Thread.sleep(2000);
        }
        assertThat(leftControlButton.exists()).isTrue();
        assertThat(rightControlButton.exists()).isTrue();
        assertThat(moreButton.exists()).isFalse();

        rightControlButton.click();
        UiObject acceptedTitle = getElement(R.string.notificationUI_confirmation_accept_title);
        assertThat(acceptedTitle.exists()).isTrue();
    }

    @Test
    public void notificationEuGaTest() throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(true).when(mMockFlags).getGaUxFeatureEnabled();

        startActivity(true);

        UiObject notificationEuGaTitle = getElement(R.string.notificationUI_header_ga_title_eu);
        assertThat(notificationEuGaTitle.exists()).isTrue();

        UiObject leftControlButton =
                getElement(R.string.notificationUI_left_control_button_text_eu);
        UiObject rightControlButton =
                getElement(R.string.notificationUI_right_control_button_ga_text_eu);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);

        verifyControlsAndMoreButtonAreDisplayed(leftControlButton, rightControlButton, moreButton);
    }

    @Test
    public void notificationRowGaTest() throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(true).when(mMockFlags).getGaUxFeatureEnabled();

        startActivity(false);

        UiObject notificationGaTitle = getElement(R.string.notificationUI_header_ga_title);
        assertThat(notificationGaTitle.exists()).isTrue();

        UiObject leftControlButton = getElement(R.string.notificationUI_left_control_button_text);
        UiObject rightControlButton = getElement(R.string.notificationUI_right_control_button_text);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);
    }

    @Test
    public void acceptedConfirmationScreenGaTest()
            throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(true).when(mMockFlags).getGaUxFeatureEnabled();

        startActivity(true);

        UiObject leftControlButton =
                getElement(R.string.notificationUI_left_control_button_text_eu);
        UiObject rightControlButton =
                getElement(R.string.notificationUI_right_control_button_ga_text_eu);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);

        verifyControlsAndMoreButtonAreDisplayed(leftControlButton, rightControlButton, moreButton);

        rightControlButton.click();

        UiObject acceptedTitle = getElement(R.string.notificationUI_fledge_measurement_title);
        assertThat(acceptedTitle.exists()).isTrue();
        UiObject leftControlButtonOnSecondPage =
                getElement(R.string.notificationUI_confirmation_left_control_button_text);
        UiObject rightControlButtonOnSecondPage =
                getElement(R.string.notificationUI_confirmation_right_control_button_text);
        UiObject moreButtonOnSecondPage = getElement(R.string.notificationUI_more_button_text);
        verifyControlsAndMoreButtonAreDisplayed(
                leftControlButtonOnSecondPage,
                rightControlButtonOnSecondPage,
                moreButtonOnSecondPage);
    }

    @Test
    public void declinedConfirmationScreenGaTest()
            throws UiObjectNotFoundException, InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(true).when(mMockFlags).getGaUxFeatureEnabled();

        startActivity(true);

        UiObject leftControlButton =
                getElement(R.string.notificationUI_left_control_button_text_eu);
        UiObject rightControlButton =
                getElement(R.string.notificationUI_right_control_button_ga_text_eu);
        UiObject moreButton = getElement(R.string.notificationUI_more_button_text);

        verifyControlsAndMoreButtonAreDisplayed(leftControlButton, rightControlButton, moreButton);

        leftControlButton.click();

        UiObject acceptedTitle = getElement(R.string.notificationUI_fledge_measurement_title);
        assertThat(acceptedTitle.exists()).isTrue();
        UiObject leftControlButtonOnSecondPage =
                getElement(R.string.notificationUI_confirmation_left_control_button_text);
        UiObject rightControlButtonOnSecondPage =
                getElement(R.string.notificationUI_confirmation_right_control_button_text);
        UiObject moreButtonOnSecondPage = getElement(R.string.notificationUI_more_button_text);
        verifyControlsAndMoreButtonAreDisplayed(
                leftControlButtonOnSecondPage,
                rightControlButtonOnSecondPage,
                moreButtonOnSecondPage);
    }

    private void verifyControlsAndMoreButtonAreDisplayed(
            UiObject leftControlButton, UiObject rightControlButton, UiObject moreButton)
            throws UiObjectNotFoundException, InterruptedException {
        UiObject scrollView =
                sDevice.findObject(new UiSelector().className("android.widget.ScrollView"));

        if (scrollView.isScrollable()) {
            // there should be a more button
            assertThat(leftControlButton.exists()).isFalse();
            assertThat(rightControlButton.exists()).isFalse();
            assertThat(moreButton.exists()).isTrue();

            while (moreButton.exists()) {
                moreButton.click();
                Thread.sleep(2000);
            }
            assertThat(leftControlButton.exists()).isTrue();
            assertThat(rightControlButton.exists()).isTrue();
            assertThat(moreButton.exists()).isFalse();
        } else {
            assertThat(leftControlButton.exists()).isTrue();
            assertThat(rightControlButton.exists()).isTrue();
            assertThat(moreButton.exists()).isFalse();
        }
    }

    private void startActivity(boolean isEUActivity) {
        Intent intent = new Intent(NOTIFICATION_TEST_PACKAGE);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra("isEUDevice", isEUActivity);
        mContext.startActivity(intent);

        sDevice.wait(Until.hasObject(By.pkg(NOTIFICATION_TEST_PACKAGE).depth(0)), LAUNCH_TIMEOUT);
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
