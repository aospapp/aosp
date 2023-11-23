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

import static com.android.adservices.ui.util.ApkTestUtil.getPageElement;
import static com.android.adservices.ui.util.ApkTestUtil.getString;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.adservices.AdServicesManager;
import android.content.Context;

import androidx.core.app.NotificationManagerCompat;
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
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.consent.DeviceRegionProvider;
import com.android.adservices.service.stats.AdServicesLoggerImpl;
import com.android.adservices.service.stats.UiStatsLogger;
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
public class ConsentNotificationTriggerTest {
    private static final String NOTIFICATION_CHANNEL_ID = "PRIVACY_SANDBOX_CHANNEL";
    private static final int LAUNCH_TIMEOUT = 5000;
    private static UiDevice sDevice;

    private AdServicesManager mAdServicesManager;
    private NotificationManager mNotificationManager;
    private MockitoSession mStaticMockSession = null;
    private String mTestName;

    @Mock private AdServicesLoggerImpl mAdServicesLoggerImpl;
    @Mock private NotificationManagerCompat mNotificationManagerCompat;
    @Mock private ConsentManager mConsentManager;
    @Mock Flags mMockFlags;
    @Spy private Context mContext;

    @Before
    public void setUp() {
        // Skip the test if it runs on unsupported platforms.
        Assume.assumeTrue(ApkTestUtil.isDeviceSupported());

        mContext = InstrumentationRegistry.getInstrumentation().getContext();
        // Initialize UiDevice instance
        sDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mNotificationManager = mContext.getSystemService(NotificationManager.class);

        MockitoAnnotations.initMocks(this);
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .spyStatic(ConsentManager.class)
                        .spyStatic(FlagsFactory.class)
                        .spyStatic(NotificationManagerCompat.class)
                        .spyStatic(AdServicesLoggerImpl.class)
                        .spyStatic(UiStatsLogger.class)
                        .spyStatic(DeviceRegionProvider.class)
                        .strictness(Strictness.WARN)
                        .initMocks(this)
                        .startMocking();

        // Mock static method FlagsFactory.getFlags() to return Mock Flags.
        ExtendedMockito.doReturn(mMockFlags).when(FlagsFactory::getFlags);
        ExtendedMockito.doReturn(mAdServicesLoggerImpl).when(AdServicesLoggerImpl::getInstance);
        doReturn(mAdServicesManager).when(mContext).getSystemService(AdServicesManager.class);
        doReturn(mConsentManager).when(() -> ConsentManager.getInstance(any(Context.class)));
        doReturn(true).when(mMockFlags).isEeaDeviceFeatureEnabled();
        doReturn(true).when(mMockFlags).isUiFeatureTypeLoggingEnabled();
        doReturn(true).when(mMockFlags).getNotificationDismissedOnClick();
        doReturn(false).when(mMockFlags).getEuNotifFlowChangeEnabled();
        cancelAllPreviousNotifications();
    }

    @After
    public void tearDown() throws IOException {
        if (!ApkTestUtil.isDeviceSupported()) return;

        ApkTestUtil.takeScreenshot(sDevice, getClass().getSimpleName() + "_" + mTestName + "_");

        AdservicesTestHelper.killAdservicesProcess(mContext);
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testEuNotification() throws InterruptedException, UiObjectNotFoundException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(true).when(mMockFlags).isEeaDevice();
        doReturn(false).when(mMockFlags).getGaUxFeatureEnabled();

        final String expectedTitle =
                mContext.getString(R.string.notificationUI_notification_title_eu);
        final String expectedContent =
                mContext.getString(R.string.notificationUI_notification_content_eu);

        ConsentNotificationTrigger.showConsentNotification(mContext, true);
        Thread.sleep(1000); // wait 1s to make sure that Notification is displayed.

        verify(() -> UiStatsLogger.logRequestedNotification(mContext));

        verify(mConsentManager, times(2)).getDefaultConsent();
        verify(mConsentManager, times(2)).getDefaultAdIdState();
        verify(mConsentManager).disable(mContext);
        verify(mConsentManager).recordNotificationDisplayed();
        verify(mConsentManager, times(2)).getCurrentPrivacySandboxFeature();
        verifyNoMoreInteractions(mConsentManager);

        assertThat(mNotificationManager.getActiveNotifications()).hasLength(1);
        final Notification notification =
                mNotificationManager.getActiveNotifications()[0].getNotification();
        assertThat(notification.getChannelId()).isEqualTo(NOTIFICATION_CHANNEL_ID);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                .isEqualTo(expectedTitle);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                .isEqualTo(expectedContent);
        assertThat(Notification.FLAG_ONGOING_EVENT & notification.flags).isEqualTo(0);
        assertThat(Notification.FLAG_NO_CLEAR & notification.flags).isEqualTo(0);
        assertThat(Notification.FLAG_AUTO_CANCEL & notification.flags)
                .isEqualTo(Notification.FLAG_AUTO_CANCEL);

        sDevice.openNotification();
        sDevice.wait(Until.hasObject(By.pkg("com.android.systemui")), LAUNCH_TIMEOUT);
        UiObject scroller =
                sDevice.findObject(
                        new UiSelector()
                                .packageName("com.android.systemui")
                                .resourceId("com.android.systemui:id/notification_stack_scroller"));
        assertThat(scroller.exists()).isTrue();
        UiSelector notificationCardSelector =
                new UiSelector().text(getString(R.string.notificationUI_notification_title_eu));
        UiObject notificationCard = scroller.getChild(notificationCardSelector);
        assertThat(notificationCard.exists()).isTrue();

        notificationCard.click();
        Thread.sleep(LAUNCH_TIMEOUT);
        UiObject title = getPageElement(sDevice, R.string.notificationUI_header_title_eu);
        assertThat(title.exists()).isTrue();
    }

    @Test
    public void testEuNotification_gaUxFlagEnabled()
            throws InterruptedException, UiObjectNotFoundException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(true).when(mMockFlags).isEeaDevice();
        doReturn(true).when(mMockFlags).getGaUxFeatureEnabled();

        final String expectedTitle =
                mContext.getString(R.string.notificationUI_notification_ga_title_eu);
        final String expectedContent =
                mContext.getString(R.string.notificationUI_notification_ga_content_eu);

        ConsentNotificationTrigger.showConsentNotification(mContext, true);
        Thread.sleep(1000); // wait 1s to make sure that Notification is displayed.

        verify(() -> UiStatsLogger.logRequestedNotification(mContext));

        verify(mConsentManager, times(2)).getDefaultConsent();
        verify(mConsentManager, times(2)).getDefaultAdIdState();
        verify(mConsentManager).recordTopicsDefaultConsent(false);
        verify(mConsentManager).recordFledgeDefaultConsent(false);
        verify(mConsentManager).recordMeasurementDefaultConsent(false);
        verify(mConsentManager).disable(mContext, AdServicesApiType.FLEDGE);
        verify(mConsentManager).disable(mContext, AdServicesApiType.TOPICS);
        verify(mConsentManager).disable(mContext, AdServicesApiType.MEASUREMENTS);
        verify(mConsentManager).recordNotificationDisplayed();
        verify(mConsentManager).recordGaUxNotificationDisplayed();
        verify(mConsentManager, times(2)).getCurrentPrivacySandboxFeature();
        verifyNoMoreInteractions(mConsentManager);

        assertThat(mNotificationManager.getActiveNotifications()).hasLength(1);
        final Notification notification =
                mNotificationManager.getActiveNotifications()[0].getNotification();
        assertThat(notification.getChannelId()).isEqualTo(NOTIFICATION_CHANNEL_ID);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                .isEqualTo(expectedTitle);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                .isEqualTo(expectedContent);
        assertThat(Notification.FLAG_ONGOING_EVENT & notification.flags)
                .isEqualTo(Notification.FLAG_ONGOING_EVENT);
        assertThat(Notification.FLAG_NO_CLEAR & notification.flags)
                .isEqualTo(Notification.FLAG_NO_CLEAR);
        assertThat(Notification.FLAG_AUTO_CANCEL & notification.flags)
                .isEqualTo(Notification.FLAG_AUTO_CANCEL);

        sDevice.openNotification();
        sDevice.wait(Until.hasObject(By.pkg("com.android.systemui")), LAUNCH_TIMEOUT);

        UiObject scroller =
                sDevice.findObject(
                        new UiSelector()
                                .packageName("com.android.systemui")
                                .resourceId("com.android.systemui:id/notification_stack_scroller"));
        assertThat(scroller.exists()).isTrue();
        UiObject notificationCard =
                scroller.getChild(
                        new UiSelector()
                                .text(getString(R.string.notificationUI_notification_ga_title_eu)));
        assertThat(notificationCard.exists()).isTrue();

        notificationCard.click();
        Thread.sleep(LAUNCH_TIMEOUT);
        assertThat(mNotificationManager.getActiveNotifications()).hasLength(0);
    }

    @Test
    public void testNonEuNotifications() throws InterruptedException, UiObjectNotFoundException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(false).when(mMockFlags).isEeaDevice();
        doReturn(false).when(mMockFlags).getGaUxFeatureEnabled();

        final String expectedTitle = mContext.getString(R.string.notificationUI_notification_title);
        final String expectedContent =
                mContext.getString(R.string.notificationUI_notification_content);

        ConsentNotificationTrigger.showConsentNotification(mContext, false);
        Thread.sleep(1000); // wait 1s to make sure that Notification is displayed.

        verify(() -> UiStatsLogger.logRequestedNotification(mContext));

        verify(mConsentManager, times(2)).getDefaultConsent();
        verify(mConsentManager, times(2)).getDefaultAdIdState();
        verify(mConsentManager).enable(mContext);
        verify(mConsentManager).recordNotificationDisplayed();
        verify(mConsentManager, times(2)).getCurrentPrivacySandboxFeature();
        verifyNoMoreInteractions(mConsentManager);

        assertThat(mNotificationManager.getActiveNotifications()).hasLength(1);
        final Notification notification =
                mNotificationManager.getActiveNotifications()[0].getNotification();
        assertThat(notification.getChannelId()).isEqualTo(NOTIFICATION_CHANNEL_ID);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                .isEqualTo(expectedTitle);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                .isEqualTo(expectedContent);
        assertThat(Notification.FLAG_ONGOING_EVENT & notification.flags).isEqualTo(0);
        assertThat(Notification.FLAG_NO_CLEAR & notification.flags).isEqualTo(0);
        assertThat(Notification.FLAG_AUTO_CANCEL & notification.flags)
                .isEqualTo(Notification.FLAG_AUTO_CANCEL);

        sDevice.openNotification();
        sDevice.wait(Until.hasObject(By.pkg("com.android.systemui")), LAUNCH_TIMEOUT);

        UiObject scroller =
                sDevice.findObject(
                        new UiSelector()
                                .packageName("com.android.systemui")
                                .resourceId("com.android.systemui:id/notification_stack_scroller"));
        assertThat(scroller.exists()).isTrue();
        UiObject notificationCard =
                scroller.getChild(
                        new UiSelector()
                                .text(getString(R.string.notificationUI_notification_title)));
        assertThat(notificationCard.exists()).isTrue();

        notificationCard.click();
        Thread.sleep(LAUNCH_TIMEOUT);
        UiObject title = getPageElement(sDevice, R.string.notificationUI_header_title);
        assertThat(title.exists()).isTrue();
    }

    @Test
    public void testNonEuNotifications_gaUxEnabled() throws InterruptedException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(false).when(mMockFlags).isEeaDevice();
        doReturn(true).when(mMockFlags).getGaUxFeatureEnabled();

        final String expectedTitle =
                mContext.getString(R.string.notificationUI_notification_ga_title);
        final String expectedContent =
                mContext.getString(R.string.notificationUI_notification_ga_content);

        ConsentNotificationTrigger.showConsentNotification(mContext, false);
        Thread.sleep(1000); // wait 1s to make sure that Notification is displayed.

        verify(() -> UiStatsLogger.logRequestedNotification(mContext));

        verify(mConsentManager).enable(mContext, AdServicesApiType.TOPICS);
        verify(mConsentManager).enable(mContext, AdServicesApiType.FLEDGE);
        verify(mConsentManager).enable(mContext, AdServicesApiType.MEASUREMENTS);

        verify(mConsentManager, times(2)).getDefaultConsent();
        verify(mConsentManager, times(2)).getDefaultAdIdState();
        verify(mConsentManager).recordTopicsDefaultConsent(true);
        verify(mConsentManager).recordFledgeDefaultConsent(true);
        verify(mConsentManager).recordMeasurementDefaultConsent(true);
        verify(mConsentManager).recordGaUxNotificationDisplayed();
        verify(mConsentManager).recordNotificationDisplayed();
        verify(mConsentManager, times(2)).getCurrentPrivacySandboxFeature();
        verifyNoMoreInteractions(mConsentManager);

        assertThat(mNotificationManager.getActiveNotifications()).hasLength(1);
        final Notification notification =
                mNotificationManager.getActiveNotifications()[0].getNotification();
        assertThat(notification.getChannelId()).isEqualTo(NOTIFICATION_CHANNEL_ID);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                .isEqualTo(expectedTitle);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                .isEqualTo(expectedContent);
        assertThat(Notification.FLAG_ONGOING_EVENT & notification.flags).isEqualTo(0);
        assertThat(Notification.FLAG_NO_CLEAR & notification.flags).isEqualTo(0);
        assertThat(Notification.FLAG_AUTO_CANCEL & notification.flags)
                .isEqualTo(Notification.FLAG_AUTO_CANCEL);
        assertThat(notification.actions).isNull();
    }

    @Test
    public void testEuNotifications_gaUxEnabled_nonDismissable()
            throws InterruptedException, UiObjectNotFoundException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(true).when(mMockFlags).isEeaDevice();
        doReturn(true).when(mMockFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mMockFlags).getNotificationDismissedOnClick();

        final String expectedTitle =
                mContext.getString(R.string.notificationUI_notification_ga_title_eu);
        final String expectedContent =
                mContext.getString(R.string.notificationUI_notification_ga_content_eu);

        ConsentNotificationTrigger.showConsentNotification(mContext, true);
        Thread.sleep(1000); // wait 1s to make sure that Notification is displayed.

        verify(() -> UiStatsLogger.logRequestedNotification(mContext));

        verify(mConsentManager, times(2)).getDefaultConsent();
        verify(mConsentManager, times(2)).getDefaultAdIdState();
        verify(mConsentManager).recordTopicsDefaultConsent(false);
        verify(mConsentManager).recordFledgeDefaultConsent(false);
        verify(mConsentManager).recordMeasurementDefaultConsent(false);
        verify(mConsentManager).disable(mContext, AdServicesApiType.FLEDGE);
        verify(mConsentManager).disable(mContext, AdServicesApiType.TOPICS);
        verify(mConsentManager).disable(mContext, AdServicesApiType.MEASUREMENTS);
        verify(mConsentManager).recordNotificationDisplayed();
        verify(mConsentManager).recordGaUxNotificationDisplayed();
        verify(mConsentManager, times(2)).getCurrentPrivacySandboxFeature();
        verifyNoMoreInteractions(mConsentManager);

        assertThat(mNotificationManager.getActiveNotifications()).hasLength(1);
        final Notification notification =
                mNotificationManager.getActiveNotifications()[0].getNotification();
        assertThat(notification.getChannelId()).isEqualTo(NOTIFICATION_CHANNEL_ID);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                .isEqualTo(expectedTitle);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                .isEqualTo(expectedContent);
        assertThat(Notification.FLAG_ONGOING_EVENT & notification.flags)
                .isEqualTo(Notification.FLAG_ONGOING_EVENT);
        assertThat(Notification.FLAG_NO_CLEAR & notification.flags)
                .isEqualTo(Notification.FLAG_NO_CLEAR);
        assertThat(Notification.FLAG_AUTO_CANCEL & notification.flags).isEqualTo(0);
        assertThat(notification.actions).isNull();

        sDevice.openNotification();
        sDevice.wait(Until.hasObject(By.pkg("com.android.systemui")), LAUNCH_TIMEOUT);

        UiObject scroller =
                sDevice.findObject(
                        new UiSelector()
                                .packageName("com.android.systemui")
                                .resourceId("com.android.systemui:id/notification_stack_scroller"));

        // there might be only one notification and no scroller exists.
        UiObject notificationCard;
        // notification card title might be cut off, so check for first portion of title
        UiSelector notificationCardSelector =
                new UiSelector()
                        .textContains(
                                getString(R.string.notificationUI_notification_ga_title_eu)
                                        .substring(0, 15));
        if (scroller.exists()) {
            notificationCard = scroller.getChild(notificationCardSelector);
        } else {
            notificationCard = sDevice.findObject(notificationCardSelector);
        }
        notificationCard.waitForExists(LAUNCH_TIMEOUT);
        assertThat(notificationCard.exists()).isTrue();

        notificationCard.click();
        Thread.sleep(LAUNCH_TIMEOUT);
        assertThat(mNotificationManager.getActiveNotifications()).hasLength(1);
    }

    @Test
    public void testEuNotifications_gaUxEnabled_nonDismissable_dismissedOnConfirmationPage()
            throws InterruptedException, UiObjectNotFoundException {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(true).when(mMockFlags).isEeaDevice();
        doReturn(true).when(mMockFlags).getGaUxFeatureEnabled();
        doReturn(false).when(mMockFlags).getNotificationDismissedOnClick();

        final String expectedTitle =
                mContext.getString(R.string.notificationUI_notification_ga_title_eu);
        final String expectedContent =
                mContext.getString(R.string.notificationUI_notification_ga_content_eu);

        ConsentNotificationTrigger.showConsentNotification(mContext, true);
        Thread.sleep(1000); // wait 1s to make sure that Notification is displayed.

        verify(() -> UiStatsLogger.logRequestedNotification(mContext));

        verify(mConsentManager, times(2)).getDefaultConsent();
        verify(mConsentManager, times(2)).getDefaultAdIdState();
        verify(mConsentManager).recordTopicsDefaultConsent(false);
        verify(mConsentManager).recordFledgeDefaultConsent(false);
        verify(mConsentManager).recordMeasurementDefaultConsent(false);
        verify(mConsentManager).disable(mContext, AdServicesApiType.FLEDGE);
        verify(mConsentManager).disable(mContext, AdServicesApiType.TOPICS);
        verify(mConsentManager).disable(mContext, AdServicesApiType.MEASUREMENTS);
        verify(mConsentManager).recordNotificationDisplayed();
        verify(mConsentManager).recordGaUxNotificationDisplayed();
        verify(mConsentManager, times(2)).getCurrentPrivacySandboxFeature();
        verifyNoMoreInteractions(mConsentManager);

        assertThat(mNotificationManager.getActiveNotifications()).hasLength(1);
        final Notification notification =
                mNotificationManager.getActiveNotifications()[0].getNotification();
        assertThat(notification.getChannelId()).isEqualTo(NOTIFICATION_CHANNEL_ID);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TITLE).toString())
                .isEqualTo(expectedTitle);
        assertThat(notification.extras.getCharSequence(Notification.EXTRA_TEXT).toString())
                .isEqualTo(expectedContent);
        assertThat(Notification.FLAG_ONGOING_EVENT & notification.flags)
                .isEqualTo(Notification.FLAG_ONGOING_EVENT);
        assertThat(Notification.FLAG_NO_CLEAR & notification.flags)
                .isEqualTo(Notification.FLAG_NO_CLEAR);
        assertThat(Notification.FLAG_AUTO_CANCEL & notification.flags).isEqualTo(0);
        assertThat(notification.actions).isNull();

        // verify that notification was displayed
        sDevice.openNotification();
        sDevice.wait(Until.hasObject(By.pkg("com.android.systemui")), LAUNCH_TIMEOUT);
        UiObject scroller =
                sDevice.findObject(
                        new UiSelector()
                                .packageName("com.android.systemui")
                                .resourceId("com.android.systemui:id/notification_stack_scroller"));
        assertThat(scroller.exists()).isTrue();

        // there might be only one notification and no scroller exists.
        UiObject notificationCard;
        // notification card title might be cut off, so check for first portion of title
        UiSelector notificationCardSelector =
                new UiSelector()
                        .textContains(
                                getString(R.string.notificationUI_notification_ga_title_eu)
                                        .substring(0, 15));
        if (scroller.exists()) {
            notificationCard = scroller.getChild(notificationCardSelector);
        } else {
            notificationCard = sDevice.findObject(notificationCardSelector);
        }
        notificationCard.waitForExists(LAUNCH_TIMEOUT);
        assertThat(notificationCard.exists()).isTrue();

        // click the notification and verify that notification still exists (wasn't dismissed)
        notificationCard.click();
        Thread.sleep(LAUNCH_TIMEOUT);
        assertThat(mNotificationManager.getActiveNotifications()).hasLength(1);

        // go to confirmation page and verify that notification was dismissed
        UiObject leftControlButton =
                getPageElement(sDevice, R.string.notificationUI_left_control_button_text_eu);
        UiObject rightControlButton =
                getPageElement(sDevice, R.string.notificationUI_right_control_button_ga_text_eu);
        UiObject moreButton = getPageElement(sDevice, R.string.notificationUI_more_button_text);
        verifyControlsAndMoreButtonAreDisplayed(leftControlButton, rightControlButton, moreButton);
        Thread.sleep(LAUNCH_TIMEOUT);
        rightControlButton.click();
        Thread.sleep(LAUNCH_TIMEOUT);
        assertThat(mNotificationManager.getActiveNotifications()).hasLength(0);
    }

    @Test
    public void testNotificationsDisabled() {
        mTestName = new Object() {}.getClass().getEnclosingMethod().getName();

        doReturn(false).when(mMockFlags).getGaUxFeatureEnabled();

        ExtendedMockito.doReturn(mNotificationManagerCompat)
                .when(() -> NotificationManagerCompat.from(mContext));
        doReturn(false).when(mNotificationManagerCompat).areNotificationsEnabled();

        ConsentNotificationTrigger.showConsentNotification(mContext, true);

        verify(() -> UiStatsLogger.logRequestedNotification(mContext));
        verify(() -> UiStatsLogger.logNotificationDisabled(mContext));

        verify(mConsentManager, times(2)).getDefaultConsent();
        verify(mConsentManager, times(2)).getDefaultAdIdState();
        verify(mConsentManager).recordNotificationDisplayed();
        verify(mConsentManager, times(2)).getCurrentPrivacySandboxFeature();
        verifyNoMoreInteractions(mConsentManager);
    }

    private void verifyControlsAndMoreButtonAreDisplayed(
            UiObject leftControlButton, UiObject rightControlButton, UiObject moreButton)
            throws UiObjectNotFoundException, InterruptedException {
        UiObject scrollView =
                sDevice.findObject(new UiSelector().className("android.widget.ScrollView"));

        if (scrollView.isScrollable()) {
            assertThat(leftControlButton.exists()).isFalse();
            assertThat(rightControlButton.exists()).isFalse();
            assertThat(moreButton.exists()).isTrue();

            while (moreButton.exists()) {
                moreButton.click();
                Thread.sleep(2000);
            }
        }
    }

    private void cancelAllPreviousNotifications() {
        if (mNotificationManager.getActiveNotifications().length > 0) {
            mNotificationManager.cancelAll();
        }
    }
}
