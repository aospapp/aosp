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

package com.android.androidbvt;

import android.app.UiAutomation;
import android.content.Context;
import android.os.PowerManager;
import android.os.RemoteException;
import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.Direction;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.test.suitebuilder.annotation.LargeTest;
import android.view.KeyEvent;

import junit.framework.TestCase;

import java.io.File;

/**
 * Basic tests for Power On/Off
 */
public class SysPowerTests extends TestCase {
    private static final int SLEEP_TIMEOUT = 30000;
    private UiAutomation mUiAutomation = null;
    private UiDevice mDevice;
    private Context mContext = null;
    private PowerManager mPowerManager;
    private AndroidBvtHelper mABvtHelper = null;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mDevice.setOrientationNatural();
        mContext = InstrumentationRegistry.getTargetContext();
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mPowerManager = (PowerManager) mContext.getSystemService(mContext.POWER_SERVICE);
        mABvtHelper = AndroidBvtHelper.getInstance(mDevice, mContext, mUiAutomation);
        mDevice.pressMenu();
        mDevice.pressHome();
    }

    @Override
    public void tearDown() throws Exception {
        mDevice.pressHome();
        mDevice.unfreezeRotation();
        super.tearDown();
    }

    public void testPowerOnOff() throws InterruptedException, RemoteException {
        mDevice.pressKeyCode(KeyEvent.KEYCODE_POWER);
        Thread.sleep(SLEEP_TIMEOUT);
        assertFalse("Screen is still on", mPowerManager.isInteractive());
        Thread.sleep(SLEEP_TIMEOUT);
        assertFalse("Screen is still on", mPowerManager.isInteractive());
        mDevice.pressKeyCode(KeyEvent.KEYCODE_POWER);
        mDevice.pressMenu();
        mDevice.pressHome();
        UiObject2 hotseat = mDevice.findObject(By.res(mDevice.getLauncherPackageName(), "hotseat"));
        assertNotNull("Not on home screen", hotseat);
    }
}
