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
import android.os.Environment;
import android.provider.Settings;
import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.test.suitebuilder.annotation.LargeTest;

import junit.framework.TestCase;

import java.util.ArrayList;
import java.util.List;

/*
 * Basic test for bug reports:
 * Take Bug report from developer options
 * Verify bug report exists *
 */
public class SysBugReportTests extends TestCase{
    private static final String BUGREPORTS_DIR
        = "./data/user_de/0/com.android.shell/files/bugreports/";
    private static final String BUGREPORT_BUTTON = "Take bug report";
    private static final String BUGREPORT_CHECK
        = " dumpsys activity service BugreportProgressService";
    private static final String DEVELOPER_OPTION_PAGE
        = android.provider.Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS;
    private static final String SYSUI_PKG = "com.android.systemui";
    private Context mContext = null;
    private UiAutomation mUiAutomation = null;
    private UiDevice mDevice = null;
    private AndroidBvtHelper mABvtHelper = null;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mDevice.setOrientationNatural();
        mContext = InstrumentationRegistry.getTargetContext();
        mUiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        mABvtHelper = AndroidBvtHelper.getInstance(mDevice, mContext, mUiAutomation);
        mDevice.wakeUp();
        mDevice.pressMenu();
    }

    @Override
    public void tearDown() throws Exception {
        mDevice.unfreezeRotation();
        super.tearDown();
    }

    @LargeTest
    public void testBugReportFromDeveloperOptions() throws Exception {
        DismissNotifications();
        Thread.sleep(mABvtHelper.LONG_TIMEOUT);
        mDevice.pressBack();
        mABvtHelper.removeDir(BUGREPORTS_DIR);
        mABvtHelper.launchIntent(DEVELOPER_OPTION_PAGE);
        Thread.sleep(mABvtHelper.LONG_TIMEOUT);
        UiObject2 bugReportBtn = mDevice
                .wait(Until.findObject(By.text(BUGREPORT_BUTTON)), mABvtHelper.LONG_TIMEOUT);
        assertNotNull("Can not find bug report button!", bugReportBtn);
        bugReportBtn.click();
        mDevice.wait(Until.findObject(By.res("android", "button1")), mABvtHelper.LONG_TIMEOUT)
                .click();
        //sleep 1 minute as bug report will take at least 60 seconds generally.
        Thread.sleep(60000);
        assertTrue("Bug report has not been generated!", isBugReportGenerated());
    }

    private boolean isBugReportGenerated() throws Exception {
        int counter = 10;
        List<String> results = null;
        while (--counter > 0) {
            results = mABvtHelper.executeShellCommand(BUGREPORT_CHECK);
            if (findBugReport(results)) {
                return true;
            }
            Thread.sleep(mABvtHelper.LONG_TIMEOUT);
        }
        return false;
    }

    private boolean findBugReport(List<String> results) {
        List<String> bugResults = new ArrayList<String>();
        for(String s:results){
            if (s.indexOf("finished")> 0){
                bugResults.add(s);
            }
        }
        if (bugResults.size()>0 && bugResults.get(bugResults.size()-1).indexOf("true")>0){
            return true;
        }
        return false;
    }

    private void DismissNotifications(){
        mDevice.openNotification();
        UiObject2 clearAllBtn = mDevice.wait(
                Until.findObject(By.res(SYSUI_PKG, "dismiss_text")),
                mABvtHelper.LONG_TIMEOUT);
        if (clearAllBtn!=null){
            clearAllBtn.click();
        }
    }
}