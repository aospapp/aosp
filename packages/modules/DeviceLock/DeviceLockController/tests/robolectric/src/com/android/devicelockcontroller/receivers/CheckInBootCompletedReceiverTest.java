/*
 * Copyright (C) 2022 The Android Open Source Project
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

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;
import androidx.work.Configuration;
import androidx.work.WorkInfo;
import androidx.work.WorkManager;
import androidx.work.testing.SynchronousExecutor;
import androidx.work.testing.WorkManagerTestInitHelper;

import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.provision.worker.DeviceCheckInHelper;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@RunWith(RobolectricTestRunner.class)
public class CheckInBootCompletedReceiverTest {

    private DeviceStateController mDeviceStateController;
    private DeviceCheckInHelper mDeviceCheckInHelper;
    private WorkManager mWorkManager;

    @Before
    public void setUp() {
        final Context context = ApplicationProvider.getApplicationContext();
        mDeviceStateController = mock(DeviceStateController.class);
        mDeviceCheckInHelper = new DeviceCheckInHelper(context);
        WorkManagerTestInitHelper.initializeTestWorkManager(context,
                new Configuration.Builder()
                        .setMinimumLoggingLevel(android.util.Log.DEBUG)
                        .setExecutor(new SynchronousExecutor())
                        .build());
        mWorkManager = WorkManager.getInstance(context);
    }

    @Test
    public void onReceive_checkInIsNeeded_shouldEnqueueCheckInWork()
            throws ExecutionException, InterruptedException, TimeoutException {
        when(mDeviceStateController.isCheckInNeeded()).thenReturn(true);

        CheckInBootCompletedReceiver.checkInIfNeeded(mDeviceStateController, mDeviceCheckInHelper);

        List<WorkInfo> workInfo = mWorkManager.getWorkInfosForUniqueWork(
                DeviceCheckInHelper.CHECK_IN_WORK_NAME).get(500, TimeUnit.MILLISECONDS);
        assertThat(workInfo.size()).isEqualTo(1);
    }

    @Test
    public void onReceive_checkInNotNeeded_shouldNotEnqueueCheckInWork()
            throws ExecutionException, InterruptedException, TimeoutException {
        when(mDeviceStateController.isCheckInNeeded()).thenReturn(false);

        CheckInBootCompletedReceiver.checkInIfNeeded(mDeviceStateController, mDeviceCheckInHelper);

        List<WorkInfo> workInfo = mWorkManager.getWorkInfosForUniqueWork(
                DeviceCheckInHelper.CHECK_IN_WORK_NAME).get(500, TimeUnit.MILLISECONDS);
        assertThat(workInfo).isEmpty();
    }
}
