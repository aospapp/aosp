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

package android.platform.test.helpers.tests;

import android.app.ActivityManager;
import android.content.Context;
import android.platform.test.helpers.IStandardAppHelper;
import android.support.test.InstrumentationRegistry;
import android.support.test.uiautomator.UiDevice;

import org.junit.After;
import org.junit.Before;

import android.os.RemoteException;

public abstract class BaseHelperTest {
    protected ActivityManager mActivityManager;
    protected UiDevice mDevice;

    @Before
    public void setOrientation() throws RemoteException {
        getDevice().setOrientationNatural();
    }

    @Before
    public void clearAppData() {
        getActivityManager().clearApplicationUserData(getHelper().getPackage(), null);
    }

    @After
    public void unsetOrientation() throws RemoteException {
        getDevice().unfreezeRotation();
    }

    protected ActivityManager getActivityManager() {
        if (mActivityManager == null) {
            Context context = InstrumentationRegistry.getContext();
            mActivityManager = (ActivityManager)context.getSystemService(Context.ACTIVITY_SERVICE);
        }

        return mActivityManager;
    }

    protected UiDevice getDevice() {
        if (mDevice == null) {
            mDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        }

        return mDevice;
    }

    protected abstract IStandardAppHelper getHelper();
}
