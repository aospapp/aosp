
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

package com.google.android.interactive.steps.home;

import android.content.Context;
import android.content.Intent;

import com.android.bedstead.nene.TestApis;
import com.android.interactive.Automation;
import com.android.interactive.annotations.AutomationFor;

import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.util.Log;

import androidx.test.platform.app.InstrumentationRegistry;

@AutomationFor("com.google.android.interactive.steps.home.WasDeviceCommissionedOrGMSCoreCalledStep")
public final class WasDeviceCommissionedOrGMSCoreCalledStepAutomation implements
        Automation<Boolean> {

    public static final String TAG =
            WasDeviceCommissionedOrGMSCoreCalledStepAutomation.class.getSimpleName();
    private UiDevice mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    private static final String MATTER_COMMISSIONER_TEXT_1 = "Choose an app";
    private static final String MATTER_COMMISSIONER_TEXT_2 = "You may need a Matter-enabled hub";
    private static final String MATTER_COMMISSIONER_GOOGLE = "Google Home";

    @Override
    public Boolean automate() throws Exception {
        // Determines if Google Home was shown as a commissioner. Waits for 3s as there is sometimes
        // a lag in loading the commissioner list
        UiObject2 mUiObject = mUiDevice.wait(
                Until.findObject(By.textContains(MATTER_COMMISSIONER_TEXT_1)), 3000);
        UiObject2 mUiObject2 = mUiDevice.wait(
                Until.findObject(By.textContains(MATTER_COMMISSIONER_TEXT_2)), 3000);
        UiObject2 mUiObject3 = mUiDevice.wait(
                Until.findObject(By.textContains(MATTER_COMMISSIONER_GOOGLE)), 3000);

        if ((mUiObject == null) || (mUiObject2 == null)) {
            Log.d(TAG, "Matter Commissioner app picker screen was not shown");
            return false;
        } else {
            if (mUiObject3 == null) {
                Log.d(TAG, "Google Home was not shown as a Matter Commissioner");
                return false;
            }
        }
        return true;
    }
}