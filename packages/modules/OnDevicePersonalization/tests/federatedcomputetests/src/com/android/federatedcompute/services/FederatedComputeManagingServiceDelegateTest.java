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

package com.android.federatedcompute.services;

import android.federatedcompute.aidl.IFederatedComputeCallback;
import android.federatedcompute.common.TrainingOptions;
import android.os.IBinder;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(AndroidJUnit4.class)
public final class FederatedComputeManagingServiceDelegateTest {
    private FederatedComputeManagingServiceDelegate mFcpService;

    @Before
    public void setUp() {
        mFcpService = new FederatedComputeManagingServiceDelegate();
    }

    @Test
    public void testSchedule() throws Exception {
        TrainingOptions trainingOptions =
                new TrainingOptions.Builder()
                        .setJobSchedulerJobId(123)
                        .setPopulationName("fake-population")
                        .build();
        mFcpService.scheduleFederatedCompute(
                trainingOptions,
                new IFederatedComputeCallback() {
                    @Override
                    public void onSuccess() {}

                    @Override
                    public void onFailure(int errorCode) {
                        Assert.fail();
                    }

                    @Override
                    public IBinder asBinder() {
                        return null;
                    }
                });
    }
}
