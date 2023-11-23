/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.cts.crossprofileappstest;

import static junit.framework.Assert.assertNotNull;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

import android.content.ComponentName;
import android.content.Context;
import android.content.pm.CrossProfileApps;
import android.os.Bundle;
import android.os.UserHandle;
import android.os.UserManager;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.ShellIdentityUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.concurrent.TimeUnit;

/**
 * Tests the {@link CrossProfileApps#startActivity(ComponentName, UserHandle)} API.
 */
@RunWith(AndroidJUnit4.class)
public class CrossProfileAppsStartActivityTest {
    private static final String PARAM_TARGET_USER = "TARGET_USER";
    private static final String ID_USER_TEXTVIEW =
            "com.android.cts.crossprofileappstest:id/user_textview";
    private static final String ID_USER_TEXTVIEW2 =
            "com.android.cts.crossprofileappstest:id/user_textview2";
    private static final long TIMEOUT_WAIT_UI = TimeUnit.SECONDS.toMillis(10);

    private CrossProfileApps mCrossProfileApps;
    private UserHandle mTargetUser;
    private Context mContext;
    private UiDevice mDevice;
    private long mUserSerialNumber;

    @Before
    public void setupCrossProfileApps() {
        mContext = InstrumentationRegistry.getContext();
        mCrossProfileApps = mContext.getSystemService(CrossProfileApps.class);
    }

    @Before
    public void wakeupDeviceAndPressHome() throws Exception {
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mDevice.wakeUp();
        mDevice.pressMenu();
        mDevice.pressHome();
    }

    @Before
    public void readTargetUser() {
        Context context = InstrumentationRegistry.getContext();
        Bundle arguments = InstrumentationRegistry.getArguments();
        UserManager userManager = context.getSystemService(UserManager.class);
        mUserSerialNumber = Long.parseLong(arguments.getString(PARAM_TARGET_USER));
        mTargetUser = userManager.getUserForSerialNumber(mUserSerialNumber);
        assertNotNull(mTargetUser);
    }

    @After
    public void pressHome() {
        mDevice.pressHome();
    }

    @Test
    public void testCanStartMainActivity() {
        try {
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mCrossProfileApps,
                    crossProfileApps -> mCrossProfileApps.startActivity(
                            MainActivity.getComponentName(mContext), mTargetUser));

            // Look for the text view to verify that MainActivity is started.
            UiObject2 textView = mDevice.wait(Until.findObject(By.res(ID_USER_TEXTVIEW)),
                    TIMEOUT_WAIT_UI);
            assertNotNull("Failed to start main activity in target user", textView);
            assertEquals("Main Activity is started in wrong user",
                    String.valueOf(mUserSerialNumber), textView.getText());
        } catch (Exception e) {
            fail("unable to start main activity via CrossProfileApps#startActivity: " + e);
        }
    }

    @Test
    public void testCanStartNonMainActivity() {
        try {
            ShellIdentityUtils.invokeMethodWithShellPermissionsNoReturn(mCrossProfileApps,
                    crossProfileApps -> mCrossProfileApps.startActivity(
                            NonMainActivity.getComponentName(mContext), mTargetUser));

            // Look for the text view to verify that NonMainActivity is started.
            UiObject2 textView = mDevice.wait(Until.findObject(By.res(ID_USER_TEXTVIEW2)),
                    TIMEOUT_WAIT_UI);
            assertNotNull("Failed to start non-main activity in target user", textView);
            assertEquals("Non-Main Activity is started in wrong user",
                    String.valueOf(mUserSerialNumber), textView.getText());
        } catch (Exception e) {
            fail("unable to start non-main activity via CrossProfileApps#startActivity: " + e);
        }
    }

    @Test(expected = SecurityException.class)
    public void testCannotStartNotExportedActivity() throws Exception {
        mCrossProfileApps.startActivity(
                NonExportedActivity.getComponentName(mContext), mTargetUser);
    }

    @Test(expected = SecurityException.class)
    public void testCannotStartActivityInOtherPackage() throws Exception {
        mCrossProfileApps.startMainActivity(new ComponentName(
                "com.android.cts.launcherapps.simpleapp",
                "com.android.cts.launcherapps.simpleapp.SimpleActivity"),
                mTargetUser
        );
    }
}
