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

package com.android.devicelockcontroller.receivers;

import static androidx.test.core.app.ApplicationProvider.getApplicationContext;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Intent;

import com.android.devicelockcontroller.TestDeviceLockControllerApplication;
import com.android.devicelockcontroller.policy.DevicePolicyController;
import com.android.devicelockcontroller.policy.DeviceStateController;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
public class LockTaskBootCompletedReceiverTest {

    private static final Intent BOOT_COMPLETED_INTENT = new Intent(
            Intent.ACTION_BOOT_COMPLETED);

    private LockTaskBootCompletedReceiver mLockTaskBootCompletedReceiver;
    private DeviceStateController mStateController;
    private DevicePolicyController mPolicyController;
    private TestDeviceLockControllerApplication mTestApplication;

    @Before
    public void setUp() {
        mTestApplication = getApplicationContext();
        mStateController = mTestApplication.getStateController();
        mPolicyController = mTestApplication.getPolicyController();

        when(mStateController.isLocked()).thenReturn(true);
        mLockTaskBootCompletedReceiver = new LockTaskBootCompletedReceiver();
    }

    @Test
    public void onReceive_startLockTaskMode() {
        mLockTaskBootCompletedReceiver.onReceive(mTestApplication, BOOT_COMPLETED_INTENT);

        verify(mPolicyController).enqueueStartLockTaskModeWorker(eq(true));
    }
}
