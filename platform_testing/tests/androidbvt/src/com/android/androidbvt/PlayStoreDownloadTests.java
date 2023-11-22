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

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.os.RemoteException;
import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.By;
import android.support.test.uiautomator.Direction;
import android.support.test.uiautomator.UiDevice;
import android.support.test.uiautomator.UiObject2;
import android.support.test.uiautomator.Until;
import android.test.suitebuilder.annotation.LargeTest;
import android.util.Log;
import android.widget.Toast;
import android.platform.test.helpers.PlayStoreHelperImpl;

import junit.framework.TestCase;

import java.io.IOException;
import java.util.HashSet;
import java.util.List;

/*
 * Basic test for installing apk from playstore
 */
public class PlayStoreDownloadTests extends TestCase {
    private static final String TEST_TAG = "AndroidBVT";
    //Test apk uploaded in PlayStore for testing purpose only
    private static final String TEST_APK_NAME = "w35location1";
    private static final String TEST_PKG_NAME = "com.test.w35location1";
    private static final String PLAYSTORE_PKG = "com.android.vending";
    private AndroidBvtHelper mABvtHelper = null;
    private UiDevice mDevice;
    private Context mContext = null;

    @Override
    public void setUp() throws Exception {
        super.setUp();
        mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mDevice.freezeRotation();
        mContext = InstrumentationRegistry.getTargetContext();
        mABvtHelper = AndroidBvtHelper.getInstance(mDevice, mContext,
                InstrumentationRegistry.getInstrumentation().getUiAutomation());
    }

    @Override
    public void tearDown() throws Exception {
        mDevice.unfreezeRotation();
        mDevice.pressMenu();
        mDevice.waitForIdle();
        super.tearDown();
    }

    @LargeTest
    public void testPlayStoreDownload() throws Exception {
        installFromPlayStore(TEST_APK_NAME);
        PackageAddedBroadcastReceiver pBroadcastReceiver = new PackageAddedBroadcastReceiver();
        assertTrue("The apk has not been installed from playstore", pBroadcastReceiver.isInstallCompleted());
        Thread.sleep(mABvtHelper.LONG_TIMEOUT);
        uninstallFromPlayStore(TEST_PKG_NAME);
    }

    public void installFromPlayStore(String appName) throws Exception {
        PlayStoreHelperImpl mHelper = new PlayStoreHelperImpl(
                InstrumentationRegistry.getInstrumentation());
        mHelper.open();
        mHelper.doSearch(appName);
        mHelper.selectFirstResult();
        mDevice.wait(Until.findObject(By.res(PLAYSTORE_PKG, "buy_button").text("INSTALL")),
                mABvtHelper.LONG_TIMEOUT)
                .clickAndWait(Until.newWindow(), 2 * mABvtHelper.LONG_TIMEOUT);
    }

    public void uninstallFromPlayStore(String pkgName) throws Exception {
        String cmd = " pm uninstall " + pkgName;
        mABvtHelper.executeShellCommand(cmd);
    }
}
