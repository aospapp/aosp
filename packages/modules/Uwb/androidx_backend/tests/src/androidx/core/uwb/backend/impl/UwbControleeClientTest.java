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

package androidx.core.uwb.backend.impl;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;

import android.os.RemoteException;
import android.platform.test.annotations.Presubmit;
import android.test.suitebuilder.annotation.SmallTest;

import androidx.core.uwb.backend.IRangingSessionCallback;
import androidx.core.uwb.backend.RangingParameters;
import androidx.core.uwb.backend.UwbComplexChannel;
import androidx.core.uwb.backend.impl.internal.RangingControlee;
import androidx.core.uwb.backend.impl.internal.RangingSessionCallback;
import androidx.core.uwb.backend.impl.internal.UwbServiceImpl;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.concurrent.ExecutorService;

@RunWith(AndroidJUnit4.class)
@SmallTest
@Presubmit
public class UwbControleeClientTest {

    @Mock private RangingControlee mRangingControlee;
    @Mock private UwbServiceImpl mUwbService;
    @Mock private IRangingSessionCallback mRangingSessionCallback;
    private UwbControleeClient mUwbControleeClient;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);
        mUwbControleeClient = new UwbControleeClient(mRangingControlee, mUwbService);
    }

    @Test
    public void testStartRanging() throws RemoteException {
        RangingParameters params = new RangingParameters();
        params.complexChannel = new UwbComplexChannel();
        params.complexChannel.channel = 9;
        params.complexChannel.preambleIndex = 9;
        params.peerDevices = new ArrayList<>();
        mUwbControleeClient.startRanging(params, mRangingSessionCallback);
        verify(mRangingControlee).setRangingParameters(any());
        verify(mRangingControlee).startRanging(
                any(RangingSessionCallback.class), any(ExecutorService.class));
    }

    @Test
    public void testStopRanging() throws RemoteException {
        mUwbControleeClient.stopRanging(mRangingSessionCallback);
        verify(mRangingControlee).stopRanging();
    }
}
