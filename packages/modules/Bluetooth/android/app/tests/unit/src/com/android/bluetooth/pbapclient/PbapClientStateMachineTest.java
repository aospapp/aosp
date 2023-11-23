/*
 * Copyright 2023 The Android Open Source Project
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
package com.android.bluetooth.pbapclient;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verify;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.util.Log;

import android.app.BroadcastOptions;
import android.content.Context;
import android.content.Intent;
import android.os.UserManager;
import android.os.Message;

import androidx.test.filters.MediumTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.TestUtils;

import org.junit.After;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.List;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class PbapClientStateMachineTest{
    private static final String TAG = "PbapClientStateMachineTest";

    private PbapClientStateMachine mPbapClientStateMachine = null;

    @Mock
    private PbapClientService mMockPbapClientService;
    @Mock
    private UserManager mMockUserManager;
    @Mock
    private PbapClientConnectionHandler mMockHandler;

    private BluetoothDevice mTestDevice;
    private BluetoothAdapter mAdapter;

    private ArgumentCaptor<Intent> mIntentArgument = ArgumentCaptor.forClass(Intent.class);


    static final int DISCONNECT_TIMEOUT = 3100;

    @Before
    public void setUp() throws Exception {
        MockitoAnnotations.initMocks(this);

        // This line must be called to make sure relevant objects are initialized properly
        mAdapter = BluetoothAdapter.getDefaultAdapter();
        // Get a device for testing
        mTestDevice = mAdapter.getRemoteDevice("00:01:02:03:04:05");
        when(mMockPbapClientService.getSystemServiceName(UserManager.class))
                .thenReturn(Context.USER_SERVICE);
        when(mMockPbapClientService.getSystemService(UserManager.class))
                .thenReturn(mMockUserManager);
        mPbapClientStateMachine = new PbapClientStateMachine(mMockPbapClientService, mTestDevice,
                mMockHandler);
        mPbapClientStateMachine.start();
    }

    @After
    public void tearDown() throws Exception {
        if (mPbapClientStateMachine != null) {
            mPbapClientStateMachine.doQuit();
        }
    }

    /**
     * Test that default state is STATE_CONNECTING
     */
    @Test
    public void testDefaultConnectingState() {
        Log.i(TAG, "in testDefaultConnectingState");
        // it appears that enter and exit can overlap sometimes when calling doQuit()
        // currently solved by waiting for looper to finish task
        TestUtils.waitForLooperToFinishScheduledTask(mPbapClientStateMachine.getHandler()
                .getLooper());
        assertThat(mPbapClientStateMachine.getConnectionState())
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);
    }

    /**
     * Test transition from STATE_CONNECTING to STATE_DISCONNECTING with MSG_DISCONNECT
     */
    @Test
    public void testStateTransitionFromConnectingToDisconnecting() {
        assertThat(mPbapClientStateMachine.getConnectionState())
                .isEqualTo(BluetoothProfile.STATE_CONNECTING);

        mPbapClientStateMachine.disconnect(mTestDevice);

        TestUtils.waitForLooperToFinishScheduledTask(mPbapClientStateMachine.getHandler()
                .getLooper());
        assertThat(mPbapClientStateMachine.getConnectionState())
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTING);
    }

    /**
     * Test transition from STATE_DISCONNECTING to STATE_DISCONNECTED with MSG_DISCONNECT_TIMEOUT
     */
    @Test
    public void testStateTransitionFromDisconnectingToDisconnected_Timeout() {
        testStateTransitionFromConnectingToDisconnecting();

        //wait until timeout occurs
        verify(mMockPbapClientService,
                timeout(DISCONNECT_TIMEOUT).times(3)).sendBroadcastMultiplePermissions(
                mIntentArgument.capture(), any(String[].class),
                any(BroadcastOptions.class));
        assertThat(mPbapClientStateMachine.getConnectionState())
                .isEqualTo(BluetoothProfile.STATE_DISCONNECTED);
    }
}