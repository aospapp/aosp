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

import android.os.Bundle;
import android.support.test.jank.GfxMonitor;
import android.support.test.jank.JankTest;
import android.support.test.jank.JankTestBase;
import android.support.test.jank.WindowAnimationFrameStatsMonitor;
import android.support.test.uiautomator.UiDevice;

/**
 * Jank tests for keyboard on wear
 */
public class KeyboardJankTests extends JankTestBase {

    private UiDevice mDevice;
    private IMEJankTestsHelper mHelper;

    /*
     * (non-Javadoc)
     * @see junit.framework.TestCase#setUp()
     */
    @Override
    protected void setUp() throws Exception {
        super.setUp();
        mDevice = UiDevice.getInstance(getInstrumentation());
        mHelper = IMEJankTestsHelper.getInstance(mDevice, this.getInstrumentation());
        mDevice.wakeUp();
        mHelper.activateIMEKeyboard();
    }

    public void launchRemoteInputActivity() {
        mHelper.goBackHome();
        mHelper.launchRemoteInputActivity();
    }

    public void launchInputBoxActivity() {
        mHelper.goBackHome();
        mHelper.launchInputBoxActivity();
    }

    // Measure keyboard jank when opened from remote input
    @JankTest(beforeTest = "launchRemoteInputActivity",
            afterLoop = "pressBack",
            afterTest = "goBackHome",
            expectedFrames = IMEJankTestsHelper.WFM_EXPECTED_FRAMES)
    @WindowAnimationFrameStatsMonitor
    public void testOpenKeyboardFromRemoteInput() {
        mHelper.tapIMEButton();
    }

    // Measure keyboard jank when opened from input box
    @JankTest(beforeTest = "launchInputBoxActivity",
            afterLoop = "pressBack",
            afterTest = "goBackHome",
            expectedFrames = IMEJankTestsHelper.WFM_EXPECTED_FRAMES)
    @WindowAnimationFrameStatsMonitor
    public void testOpenKeyboardFromInputBox() {
        mHelper.tapOnScreen();
    }

    // Measure keyboard jank when typing simple sequence of characters
    @JankTest(beforeLoop = "launchInputBoxActivity",
            afterLoop = "pressBack",
            afterTest = "goBackHome",
            expectedFrames = IMEJankTestsHelper.GFX_EXPECTED_FRAMES)
    @GfxMonitor(processName = IMEJankTestsHelper.IME_PACKAGE_NAME)
    public void testKeyboardSimpleSequence() {
        mHelper.tapOnScreen();
        for (int i = 1; i <= 9; i++) {
            mHelper.clickSoftKey(String.format("1_%d", i));
        }
    }

    // Measure keyboard jank when typing complex sequence of characters, del and spaces
    @JankTest(beforeLoop = "launchInputBoxActivity",
            afterLoop = "pressBack",
            afterTest = "goBackHome",
            expectedFrames = IMEJankTestsHelper.GFX_EXPECTED_FRAMES)
    @GfxMonitor(processName = IMEJankTestsHelper.IME_PACKAGE_NAME)
    public void testKeyboardComplexSequence() {
        mHelper.tapOnScreen();
        for (int i = 0; i < 50; i++) {
            mHelper.clickSoftKey(String.format("0_%d", i % 10));
            if (i % 7 == 0) {
                mHelper.clickSoftKey("space");
            }
            if (i % 15 == 7) {
                mHelper.clickSoftKey("del");
                mHelper.clickSoftKey("del");
                mHelper.clickSoftKey("del");
            }
        }
    }

    // Measure keyboard jank when tapping more candidates button
    @JankTest(beforeTest = "prepareToShowMoreCandidates",
            afterTest = "goBackHome",
            expectedFrames = IMEJankTestsHelper.GFX_EXPECTED_FRAMES)
    @GfxMonitor(processName = IMEJankTestsHelper.IME_PACKAGE_NAME)
    public void testKeyboardToggleMoreCandidates() {
        for (int i = 0; i < 6; i++) {
            mHelper.clickSoftKey("show_more_candidates");
        }
    }

    // Measure keyboard jank when typing gesture sequence
    @JankTest(beforeLoop = "launchInputBoxActivity",
            afterLoop = "pressBack",
            afterTest = "goBackHome",
            expectedFrames = IMEJankTestsHelper.GFX_EXPECTED_FRAMES)
    @GfxMonitor(processName = IMEJankTestsHelper.IME_PACKAGE_NAME)
    public void testKeyboardGestureInput() {
        mHelper.tapOnScreen();
        for (int i = 0; i < 2; i++) {
            // Swipe through first row on keyboard
            mHelper.swipeRight(0);
            // Swipe through second row on keyboard
            mHelper.swipeRight(mDevice.getDisplayHeight() / 8);
            // Swipe through third row on keyboard
            mHelper.swipeRight(mDevice.getDisplayHeight() / 4);
        }
    }

    // Make sure more candidates icon shows up
    public void prepareToShowMoreCandidates() {
        launchInputBoxActivity();
        mHelper.tapOnScreen();
        for (int i = 0; i < 5; i++) {
            mHelper.clickSoftKey(String.format("0_%d", i));
        }
    }

    public void pressBack() {
        mHelper.pressBack();
    }

    // Ensuring that we head back to the first screen before launching the app again
    public void goBackHome(Bundle metrics) {
        mHelper.goBackHome();
        super.afterTest(metrics);
    }

}