/*
 * Copyright 2021 HIMSA II K/S - www.himsa.com.
 * Represented by EHIMA - www.ehima.com
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

package com.android.bluetooth.hap;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.Intent;
import android.os.HandlerThread;
import android.test.suitebuilder.annotation.MediumTest;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.R;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.btservice.AdapterService;

import org.hamcrest.core.IsInstanceOf;
import org.junit.*;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class HapClientStateMachineTest {
    private BluetoothAdapter mAdapter;
    private Context mTargetContext;
    private HandlerThread mHandlerThread;
    private HapClientStateMachine mHapClientStateMachine;
    private BluetoothDevice mTestDevice;
    private static final int TIMEOUT_MS = 1000;

    @Mock
    private AdapterService mAdapterService;
    @Mock
    private HapClientService mHapClientService;
    @Mock
    private HapClientNativeInterface mHearingAccessGattClientInterface;

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getTargetContext();
        // Set up mocks and test assets
        MockitoAnnotations.initMocks(this);
        TestUtils.setAdapterService(mAdapterService);

        mAdapter = BluetoothAdapter.getDefaultAdapter();

        // Get a device for testing
        mTestDevice = mAdapter.getRemoteDevice("00:01:02:03:04:05");

        // Set up thread and looper
        mHandlerThread = new HandlerThread("HapClientStateMachineTestHandlerThread");
        mHandlerThread.start();
        mHapClientStateMachine = new HapClientStateMachine(mTestDevice,
                mHapClientService, mHearingAccessGattClientInterface, mHandlerThread.getLooper());
        // Override the timeout value to speed up the test
        HapClientStateMachine.sConnectTimeoutMs = 1000;     // 1s
        mHapClientStateMachine.start();
    }

    @After
    public void tearDown() throws Exception {
        mHapClientStateMachine.doQuit();
        mHandlerThread.quit();
        TestUtils.clearAdapterService(mAdapterService);
    }

    /**
     * Test that default state is disconnected
     */
    @Test
    public void testDefaultDisconnectedState() {
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                mHapClientStateMachine.getConnectionState());
    }

    /**
     * Allow/disallow connection to any device.
     *
     * @param allow if true, connection is allowed
     */
    private void allowConnection(boolean allow) {
        doReturn(allow).when(mHapClientService).okToConnect(any(BluetoothDevice.class));
    }

    /**
     * Test that an incoming connection with policy forbidding connection is rejected
     */
    @Test
    public void testIncomingPolicyReject() {
        allowConnection(false);

        // Inject an event for when incoming connection is requested
        HapClientStackEvent connStCh =
                new HapClientStackEvent(
                        HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mTestDevice;
        connStCh.valueInt1 = HapClientStackEvent.CONNECTION_STATE_CONNECTED;
        mHapClientStateMachine.sendMessage(HapClientStateMachine.STACK_EVENT, connStCh);

        // Verify that no connection state broadcast is executed
        verify(mHapClientService, after(TIMEOUT_MS).never()).sendBroadcast(any(Intent.class),
                anyString());
        // Check that we are in Disconnected state
        Assert.assertThat(mHapClientStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(HapClientStateMachine.Disconnected.class));
    }

    /**
     * Test that an incoming connection with policy allowing connection is accepted
     */
    @Test
    public void testIncomingPolicyAccept() {
        allowConnection(true);

        // Inject an event for when incoming connection is requested
        HapClientStackEvent connStCh =
                new HapClientStackEvent(
                        HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mTestDevice;
        connStCh.valueInt1 = HapClientStackEvent.CONNECTION_STATE_CONNECTING;
        mHapClientStateMachine.sendMessage(HapClientStateMachine.STACK_EVENT, connStCh);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument1 = ArgumentCaptor.forClass(Intent.class);
        verify(mHapClientService, timeout(TIMEOUT_MS).times(1)).sendBroadcast(
                intentArgument1.capture(), anyString());
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                intentArgument1.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1));

        // Check that we are in Connecting state
        Assert.assertThat(mHapClientStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(HapClientStateMachine.Connecting.class));

        // Send a message to trigger connection completed
        HapClientStackEvent connCompletedEvent =
                new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connCompletedEvent.device = mTestDevice;
        connCompletedEvent.valueInt1 = HapClientStackEvent.CONNECTION_STATE_CONNECTED;
        mHapClientStateMachine.sendMessage(HapClientStateMachine.STACK_EVENT,
                connCompletedEvent);

        // Verify that the expected number of broadcasts are executed:
        // - two calls to broadcastConnectionState(): Disconnected -> Connecting -> Connected
        ArgumentCaptor<Intent> intentArgument2 = ArgumentCaptor.forClass(Intent.class);
        verify(mHapClientService, timeout(TIMEOUT_MS).times(2)).sendBroadcast(
                intentArgument2.capture(), anyString());
        // Check that we are in Connected state
        Assert.assertThat(mHapClientStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(HapClientStateMachine.Connected.class));
    }

    /**
     * Test that an outgoing connection times out
     */
    @Test
    public void testOutgoingTimeout() {
        allowConnection(true);
        doReturn(true).when(mHearingAccessGattClientInterface).connectHapClient(any(
                BluetoothDevice.class));
        doReturn(true).when(mHearingAccessGattClientInterface).disconnectHapClient(any(
                BluetoothDevice.class));

        // Send a connect request
        mHapClientStateMachine.sendMessage(HapClientStateMachine.CONNECT, mTestDevice);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument1 = ArgumentCaptor.forClass(Intent.class);
        verify(mHapClientService, timeout(TIMEOUT_MS).times(1)).sendBroadcast(
                intentArgument1.capture(),
                anyString());
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                intentArgument1.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1));

        // Check that we are in Connecting state
        Assert.assertThat(mHapClientStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(HapClientStateMachine.Connecting.class));

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument2 = ArgumentCaptor.forClass(Intent.class);
        verify(mHapClientService, timeout(HapClientStateMachine.sConnectTimeoutMs * 2).times(
                2)).sendBroadcast(intentArgument2.capture(), anyString());
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                intentArgument2.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1));

        // Check that we are in Disconnected state
        Assert.assertThat(mHapClientStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(HapClientStateMachine.Disconnected.class));
        verify(mHearingAccessGattClientInterface).disconnectHapClient(eq(mTestDevice));
    }

    /**
     * Test that an incoming connection times out
     */
    @Test
    public void testIncomingTimeout() {
        allowConnection(true);
        doReturn(true).when(mHearingAccessGattClientInterface).connectHapClient(any(
                BluetoothDevice.class));
        doReturn(true).when(mHearingAccessGattClientInterface).disconnectHapClient(any(
                BluetoothDevice.class));

        // Inject an event for when incoming connection is requested
        HapClientStackEvent connStCh =
                new HapClientStackEvent(
                        HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        connStCh.device = mTestDevice;
        connStCh.valueInt1 = HapClientStackEvent.CONNECTION_STATE_CONNECTING;
        mHapClientStateMachine.sendMessage(HapClientStateMachine.STACK_EVENT, connStCh);

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument1 = ArgumentCaptor.forClass(Intent.class);
        verify(mHapClientService, timeout(TIMEOUT_MS).times(1)).sendBroadcast(
                intentArgument1.capture(),
                anyString());
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                intentArgument1.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1));

        // Check that we are in Connecting state
        Assert.assertThat(mHapClientStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(HapClientStateMachine.Connecting.class));

        // Verify that one connection state broadcast is executed
        ArgumentCaptor<Intent> intentArgument2 = ArgumentCaptor.forClass(Intent.class);
        verify(mHapClientService, timeout(HapClientStateMachine.sConnectTimeoutMs * 2).times(
                2)).sendBroadcast(intentArgument2.capture(), anyString());
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                intentArgument2.getValue().getIntExtra(BluetoothProfile.EXTRA_STATE, -1));

        // Check that we are in Disconnected state
        Assert.assertThat(mHapClientStateMachine.getCurrentState(),
                IsInstanceOf.instanceOf(HapClientStateMachine.Disconnected.class));
        verify(mHearingAccessGattClientInterface).disconnectHapClient(eq(mTestDevice));
    }
}
