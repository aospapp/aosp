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
package com.android.adservices.tests.ui.notification;

import static com.android.adservices.tests.ui.libs.UiConstants.AD_ID_DISABLED;
import static com.android.adservices.tests.ui.libs.UiConstants.AD_ID_ENABLED;
import static com.android.adservices.tests.ui.libs.UiConstants.ENTRY_POINT_ENABLED;

import android.adservices.common.AdServicesCommonManager;
import android.content.Context;
import android.os.OutcomeReceiver;

import androidx.concurrent.futures.CallbackToFutureAdapter;
import androidx.test.filters.FlakyTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;
import androidx.test.uiautomator.UiDevice;

import com.android.adservices.common.AdservicesTestHelper;
import com.android.adservices.tests.ui.libs.UiUtils;

import com.google.common.util.concurrent.ListenableFuture;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/** Test for verifying user consent notification trigger behaviors. */
@RunWith(AndroidJUnit4.class)
public class ReconsentNotificationTriggerTest {

    private AdServicesCommonManager mCommonManager;
    private UiDevice mDevice;
    private static final Executor CALLBACK_EXECUTOR = Executors.newCachedThreadPool();

    private static final Context sContext =
            InstrumentationRegistry.getInstrumentation().getContext();

    @Before
    public void setUp() throws Exception {
        // Skip the test if it runs on unsupported platforms.
        Assume.assumeTrue(AdservicesTestHelper.isDeviceSupported());

        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        mCommonManager = AdServicesCommonManager.get(sContext);

        UiUtils.enableNotificationPermission();
        // consent debug mode is turned off for this test class as we only care about the
        // first trigger (API call).
        mDevice.pressHome();
        UiUtils.disableConsentDebugMode();
        UiUtils.disableSchedulingParams();
        UiUtils.setSourceOfTruthToPPAPI();
        UiUtils.clearSavedStatus();
    }

    @After
    public void tearDown() throws Exception {
        if (!AdservicesTestHelper.isDeviceSupported()) return;

        mDevice.pressHome();
        AdservicesTestHelper.killAdservicesProcess(sContext);
    }

    /**
     * Verify that for ROW devices with zeroed-out AdId, EU notification displayed, and GA UX
     * feature enabled, the EU GA UX notification is displayed as part of the re-consent
     * notification feature.
     */
    @Test
    @FlakyTest(bugId = 272946901)
    public void testRowAdIdDisabledGaUxEnabledReConsent() throws Exception {
        UiUtils.setAsRowDevice();
        UiUtils.enableBeta();
        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_DISABLED);
        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ true, /* isEuTest */ true, /* isGa */ false);
        UiUtils.consentConfirmationScreen(sContext, mDevice, true, true);

        mDevice.pressHome();
        UiUtils.restartAdservices();
        UiUtils.enableGa();

        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_DISABLED);

        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ true, /* isEuTest */ true, /* isGa */ true);
    }

    /**
     * Verify that for ROW devices with non zeroed-out AdId, notification displayed, and GA UX
     * feature enabled, the ROW GA UX notification is displayed as part of the re-consent
     * notification feature.
     */
    @Test
    @FlakyTest(bugId = 272946901)
    public void testRowAdIdEnabledGaUxEnabledReConsent() throws Exception {
        UiUtils.setAsRowDevice();
        UiUtils.enableBeta();
        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_ENABLED);
        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ true, /* isEuTest */ false, /* isGa */ false);
        UiUtils.consentConfirmationScreen(sContext, mDevice, false, true);

        mDevice.pressHome();
        UiUtils.restartAdservices();
        UiUtils.enableGa();

        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_ENABLED);

        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ true, /* isEuTest */ false, /* isGa */ true);
    }

    /**
     * Verify that for ROW devices with non zeroed-out AdId, notification displayed, and GA UX
     * feature enabled, the GA UX notification is displayed. and second time call, the notification
     * should not displayed
     */
    @Test
    @FlakyTest(bugId = 272946901)
    public void testRowAdIdEnabledGaUxEnabledReConsentSecondNotDisplayed() throws Exception {
        UiUtils.setAsRowDevice();
        UiUtils.enableBeta();
        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_ENABLED);
        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ true, /* isEuTest */ false, /* isGa */ false);
        UiUtils.consentConfirmationScreen(sContext, mDevice, false, true);

        mDevice.pressHome();
        UiUtils.restartAdservices();
        UiUtils.enableGa();

        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_ENABLED);
        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ true, /* isEuTest */ false, /* isGa */ true);

        mDevice.pressHome();
        UiUtils.restartAdservices();

        // second time call, notification should not displayed
        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_ENABLED);
        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ false, /* isEuTest */ false, /* isGa */ true);
    }

    /**
     * Verify that for ROW devices with non zeroed-out AdId, notification displayed, User opt-out
     * consent, and GA UX feature enabled, the GA UX notification is not displayed.
     */
    @Test
    @FlakyTest(bugId = 272946901)
    public void testRowAdIdEnabledConsentOptoutGaUxEnabledReConsent() throws Exception {
        UiUtils.setAsRowDevice();
        UiUtils.enableBeta();
        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_ENABLED);
        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ true, /* isEuTest */ false, /* isGa */ false);
        UiUtils.consentConfirmationScreen(sContext, mDevice, false, false);

        mDevice.pressHome();
        UiUtils.restartAdservices();
        UiUtils.enableGa();

        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_ENABLED);

        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ false, /* isEuTest */ false, /* isGa */ true);
    }

    /**
     * Verify that for EU devices with non zeroed-out AdId, notification displayed, and then GA UX
     * feature enabled, the EU GA UX notification is displayed as part of the re-consent
     * notification feature.
     */
    @Test
    @FlakyTest(bugId = 272946901)
    public void testEuAdIdEnabledGaUxEnabledReconsent() throws Exception {
        UiUtils.setAsEuDevice();
        UiUtils.enableBeta();
        mCommonManager.setAdServicesEnabled(ENTRY_POINT_ENABLED, AD_ID_ENABLED);
        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ true, /* isEuTest */ true, /* isGa */ false);
        UiUtils.consentConfirmationScreen(sContext, mDevice, false, true);

        mDevice.pressHome();
        UiUtils.restartAdservices();
        UiUtils.enableGa();

        ListenableFuture<Boolean> adServicesStatusResponse = getAdservicesStatus();

        adServicesStatusResponse.get();

        UiUtils.verifyNotification(
                sContext, mDevice, /* isDisplayed */ true, /* isEuTest */ true, /* isGa */ true);
    }

    @Test
    @FlakyTest(bugId = 272946901)
    public void testDeleteStatus() {
        UiUtils.clearSavedStatus();
        AdservicesTestHelper.killAdservicesProcess(sContext);
    }

    private ListenableFuture<Boolean> getAdservicesStatus() {
        return CallbackToFutureAdapter.getFuture(
                completer -> {
                    mCommonManager.isAdServicesEnabled(
                            CALLBACK_EXECUTOR,
                            new OutcomeReceiver<Boolean, Exception>() {
                                @Override
                                public void onResult(Boolean result) {
                                    completer.set(result);
                                }

                                @Override
                                public void onError(Exception error) {
                                    completer.setException(error);
                                }
                            });
                    // This value is used only for debug purposes: it will be used in toString()
                    // of returned future or error cases.
                    return "getStatus";
                });
    }
}
