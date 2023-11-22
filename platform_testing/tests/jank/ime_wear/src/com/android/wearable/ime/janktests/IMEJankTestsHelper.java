/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.wearable.ime.janktests;

import android.app.Instrumentation;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.util.Log;

import junit.framework.Assert;

import java.io.IOException;

/**
 * Helpers for Wear IME Jank Tests
 */

public class IMEJankTestsHelper {

    public static final int LONG_TIMEOUT = 5000;
    public static final int SHORT_TIMEOUT = 1500;
    public static final int WFM_EXPECTED_FRAMES = 10;
    public static final int GFX_EXPECTED_FRAMES = 10;
    public static final String IME_PACKAGE_NAME = "com.google.android.inputmethod.latin";
    private static final String LOG_TAG = IMEJankTestsHelper.class.getSimpleName();
    private static final String HOME_INDICATOR = "charging_icon";
    private static final String LAUNCHER_VIEW_NAME = "launcher_view";
    private static final String CARD_TITLE_NAME = "title";
    private static final String IME_BUTTON_NAME = "ime_choice";
    private static final String REPLY_BUTTON_TEXT = "Reply";
    private static final String REMOTE_INPUT_TEXT = "Quick reply";
    private static final String REMOTE_INPUT_PACKAGE_NAME =
            "com.google.android.wearable.app";
    private static final String RELOAD_NOTIFICATION_CARD_INTENT = "com.google.android.wearable."
            + "support.wearnotificationgenerator.SHOW_NOTIFICATION";
    private static final String KEYBOARD_ID =
            "com.google.android.inputmethod.latin/com.google.android.apps.inputmethod.wear.WearIME";
    private static final String HANDWRITING_ID = "com.google.android.apps.handwriting.ime/"
            + "com.google.android.wearable.input.handwriting.HandwriterInputMethodService";
    private static final String SET_IME_CMD = "ime set %s";
    private static final String ENABLE_IME_CMD = "ime enable %s";
    private static final String INPUT_BOX_PACKAGE_NAME =
            "com.google.android.wearable.input.latin.activity";
    private static IMEJankTestsHelper mInstance;
    private UiDevice mDevice;
    private Instrumentation mInstrumentation;

    private IMEJankTestsHelper(UiDevice device, Instrumentation instrumentation) {
        mDevice = device;
        mInstrumentation = instrumentation;
    }

    public static IMEJankTestsHelper getInstance(UiDevice device, Instrumentation instrumentation) {
        if (mInstance == null) {
            mInstance = new IMEJankTestsHelper(device, instrumentation);
        }
        return mInstance;
    }

    public void swipeUp() {
        mDevice.swipe(mDevice.getDisplayWidth() / 2, mDevice.getDisplayHeight() / 2 + 50,
                mDevice.getDisplayWidth() / 2, 0, 30); // slow speed
        SystemClock.sleep(SHORT_TIMEOUT);
    }

    public void swipeRight(int heightOffset) {
        mDevice.swipe(50,
                mDevice.getDisplayHeight() / 2 + heightOffset, mDevice.getDisplayWidth() - 25,
                mDevice.getDisplayHeight() / 2 + heightOffset, 30); // slow speed
        SystemClock.sleep(SHORT_TIMEOUT);
    }

    // Helper function to go back to home screen
    public void goBackHome() {
        String launcherPackage = mDevice.getLauncherPackageName();
        UiObject2 homeScreen = mDevice.findObject(By.res(launcherPackage, HOME_INDICATOR));
        int count = 0;
        while (homeScreen == null && count < 5) {
            mDevice.pressBack();
            Log.d(LOG_TAG, "Pressed back");
            homeScreen = mDevice.findObject(By.res(launcherPackage, HOME_INDICATOR));
            count++;
        }

        // TODO (yuanlang@) Delete the following hacky codes after charging icon issue fixed
        // App launcher still draw changing icon. Make sure we're not in the launcher
        homeScreen = mDevice.findObject(By.res(launcherPackage, LAUNCHER_VIEW_NAME));
        if (homeScreen != null) {
            mDevice.pressBack();
        }

        SystemClock.sleep(SHORT_TIMEOUT);
    }

    public void launchRemoteInputActivity() {
        String launcherPackage = mDevice.getLauncherPackageName();
        // Swipe up 5 times to bring up demo cards
        // in case there are some real cards on top
        reloadDemoCards();
        int i = 0;
        UiObject2 cardTitle = null;
        while (i < 5) {
            swipeUp();
            cardTitle = mDevice.wait(
                    Until.findObject(By.text("Clockwork Notifications-10")), SHORT_TIMEOUT);
            if (cardTitle != null) {
                cardTitle.click();
                break;
            }
            i ++;
        }
        Assert.assertNotNull("Cannot find demo card with remote input", cardTitle);
        // Click on reply action button
        UiObject2 replyButton = mDevice
                .wait(Until.findObject(By.text(REPLY_BUTTON_TEXT)), LONG_TIMEOUT);
        Assert.assertNotNull(replyButton);
        replyButton.click();

        // Make sure remote input activity launched
        UiObject2 replyText = mDevice
                .wait(Until.findObject(By.text(REMOTE_INPUT_TEXT)), LONG_TIMEOUT);
        Assert.assertNotNull(replyText);
    }

    public void launchInputBoxActivity() {
        Context context = mInstrumentation.getContext();
        Intent intent = context.getPackageManager()
                .getLaunchIntentForPackage(INPUT_BOX_PACKAGE_NAME);
        intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TASK);
        context.startActivity(intent);
        Assert.assertTrue("Input box activity not started",
                mDevice.wait(Until.hasObject(By.pkg(INPUT_BOX_PACKAGE_NAME).depth(0)),
                        LONG_TIMEOUT));
    }

    public void tapIMEButton() {
        UiObject2 imeButton = mDevice.wait(
                Until.findObject(By.res(REMOTE_INPUT_PACKAGE_NAME, IME_BUTTON_NAME)), LONG_TIMEOUT);
        Assert.assertNotNull(imeButton);
        imeButton.click();
        SystemClock.sleep(SHORT_TIMEOUT);
    }

    // This will ensure to reload notification cards by launching NotificationsGeneratorWear app
    // when there are insufficient cards.
    private void reloadDemoCards() {
        Intent intent = new Intent();
        intent.setAction(RELOAD_NOTIFICATION_CARD_INTENT);
        mInstrumentation.getContext().sendBroadcast(intent);
        Log.d(LOG_TAG, "Demo cards have been reloaded");
        SystemClock.sleep(LONG_TIMEOUT);
    }

    public void activateIMEKeyboard() {
        try {
            mDevice.executeShellCommand(String.format(ENABLE_IME_CMD, KEYBOARD_ID));
            mDevice.executeShellCommand(String.format(SET_IME_CMD, KEYBOARD_ID));
        } catch (IOException e) {
            Log.e(LOG_TAG, e.toString());
        }
        Log.d(LOG_TAG, "Keyboard activated");
    }

    public void activateIMEHandwriting() {
        try {
            mDevice.executeShellCommand(String.format(ENABLE_IME_CMD, HANDWRITING_ID));
            mDevice.executeShellCommand(String.format(SET_IME_CMD, HANDWRITING_ID));
        } catch (IOException e) {
            Log.e(LOG_TAG, e.toString());
        }
        Log.d(LOG_TAG, "Handwriting activated");
    }

    public void pressBack() {
        mDevice.pressBack();
        SystemClock.sleep(SHORT_TIMEOUT);
    }

    public void tapOnScreen() {
        mDevice.click(mDevice.getDisplayHeight() / 2, mDevice.getDisplayWidth() / 2);
        SystemClock.sleep(SHORT_TIMEOUT);
    }

    public void clickSoftKey(String softKeyDescription) {
        UiObject2 softKey = mDevice.wait(
                Until.findObject(By.res(IME_PACKAGE_NAME, keyPosIdByDesc(softKeyDescription))),
                SHORT_TIMEOUT);
        Assert.assertNotNull("Soft Key " + softKeyDescription + " not found in UI", softKey);
        softKey.click();
    }

    private String keyPosIdByDesc(String desc) {
        return String.format("key_pos_%s", desc);
    }
}