/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.cts.deviceowner;

import static junit.framework.Assert.assertEquals;
import static junit.framework.Assert.assertTrue;

import android.app.ActivityManager;
import android.app.admin.DevicePolicyManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test class that is meant to be driven from the host and can't be run alone, which is required
 * for tests that include rebooting or other connection-breaking steps. For this reason, this class
 * does not override tearDown and setUp just initializes the test state, changing nothing in the
 * device. Therefore, the host is responsible for making sure the tests leave the device in a clean
 * state after running.
 */
@RunWith(AndroidJUnit4.class)
public class LockTaskHostDrivenTest {

    private static final String TAG = LockTaskHostDrivenTest.class.getName();

    private static final int ACTIVITY_RESUMED_TIMEOUT_MILLIS = 20000;  // 20 seconds

    private static final String LOCK_TASK_ACTIVITY
            = LockTaskUtilityActivityIfWhitelisted.class.getName();

    private UiDevice mUiDevice;
    private Context mContext;
    private ActivityManager mActivityManager;
    private DevicePolicyManager mDevicePolicyManager;

    private volatile boolean mIsActivityResumed;
    private final Object mActivityResumedLock = new Object();

    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            Log.d(TAG, "onReceive: " + action);
            if (LockTaskUtilityActivity.RESUME_ACTION.equals(action)) {
                synchronized (mActivityResumedLock) {
                    mIsActivityResumed = true;
                    mActivityResumedLock.notify();
                }
            } else if (LockTaskUtilityActivity.PAUSE_ACTION.equals(action)) {
                synchronized (mActivityResumedLock) {
                    mIsActivityResumed = false;
                    mActivityResumedLock.notify();
                }
            }
        }
    };

    @Before
    public void setUp() {
        mContext = InstrumentationRegistry.getContext();
        mDevicePolicyManager = mContext.getSystemService(DevicePolicyManager.class);
        mActivityManager = mContext.getSystemService(ActivityManager.class);
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        IntentFilter filter = new IntentFilter();
        filter.addAction(LockTaskUtilityActivity.RESUME_ACTION);
        filter.addAction(LockTaskUtilityActivity.PAUSE_ACTION);
        mContext.registerReceiver(mReceiver, filter);
    }

    @After
    public void tearDown() {
        mContext.unregisterReceiver(mReceiver);
    }
  
    @Test
    public void startLockTask() throws Exception {
        Log.d(TAG, "startLockTask on host-driven test (no cleanup)");
        setDefaultHomeIntentReceiver();
        launchLockTaskActivity();
        mUiDevice.waitForIdle();
    }

    /**
     * On low-RAM devices, this test can take too long to finish, so the test runner can incorrectly
     * assume it's finished. Therefore, only use it once in a given test.
     */
    @Test
    public void testLockTaskIsActiveAndCantBeInterrupted() throws Exception {
        mUiDevice.waitForIdle();

        // We need to wait until the LockTaskActivity is ready
        // since com.android.cts.deviceowner can be killed by AMS for reason "start instr".
        synchronized (mActivityResumedLock) {
            if (!mIsActivityResumed) {
                mActivityResumedLock.wait(ACTIVITY_RESUMED_TIMEOUT_MILLIS);
            }
        }
        checkLockedActivityIsRunning();

        mUiDevice.pressBack();
        mUiDevice.waitForIdle();
        checkLockedActivityIsRunning();

        mUiDevice.pressHome();
        mUiDevice.waitForIdle();
        checkLockedActivityIsRunning();

        mUiDevice.pressRecentApps();
        mUiDevice.waitForIdle();
        checkLockedActivityIsRunning();

        mUiDevice.waitForIdle();
    }

    @Test
    public void clearDefaultHomeIntentReceiver() {
        mDevicePolicyManager.clearPackagePersistentPreferredActivities(
                BasicAdminReceiver.getComponentName(mContext),
                mContext.getPackageName());
        mDevicePolicyManager.setLockTaskPackages(BasicAdminReceiver.getComponentName(mContext),
                new String[0]);
    }

    private void checkLockedActivityIsRunning() throws Exception {
        assertTrue(isActivityOnTop());
        assertEquals(ActivityManager.LOCK_TASK_MODE_LOCKED,
                mActivityManager.getLockTaskModeState());
    }

    private boolean isActivityOnTop() {
        return mActivityManager.getAppTasks().get(0).getTaskInfo().topActivity
                .getClassName().equals(LOCK_TASK_ACTIVITY);
    }

    private void launchLockTaskActivity() {
        Intent intent = new Intent(mContext, LockTaskUtilityActivityIfWhitelisted.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(LockTaskUtilityActivity.START_LOCK_TASK, true);
        mContext.startActivity(intent);
    }

    private void setDefaultHomeIntentReceiver() {
        mDevicePolicyManager.setLockTaskPackages(BasicAdminReceiver.getComponentName(mContext),
                new String[]{mContext.getPackageName()});
        IntentFilter intentFilter = new IntentFilter(Intent.ACTION_MAIN);
        intentFilter.addCategory(Intent.CATEGORY_HOME);
        intentFilter.addCategory(Intent.CATEGORY_DEFAULT);
        mDevicePolicyManager.addPersistentPreferredActivity(
                BasicAdminReceiver.getComponentName(mContext), intentFilter,
                new ComponentName(mContext.getPackageName(), LOCK_TASK_ACTIVITY));
    }
}
