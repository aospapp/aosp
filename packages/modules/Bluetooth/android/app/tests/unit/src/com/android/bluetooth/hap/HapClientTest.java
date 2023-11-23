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

import static org.mockito.Mockito.*;

import android.bluetooth.*;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Binder;
import android.os.Looper;
import android.os.ParcelUuid;
import android.os.RemoteException;
import android.util.Log;

import androidx.test.InstrumentationRegistry;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ServiceTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.R;
import com.android.bluetooth.TestUtils;
import com.android.bluetooth.Utils;
import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.btservice.ServiceFactory;
import com.android.bluetooth.btservice.storage.DatabaseManager;
import com.android.bluetooth.csip.CsipSetCoordinatorService;

import org.junit.After;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeoutException;

@MediumTest
@RunWith(AndroidJUnit4.class)
public class HapClientTest {
    private static final int TIMEOUT_MS = 1000;
    @Rule
    public final ServiceTestRule mServiceRule = new ServiceTestRule();
    private BluetoothAdapter mAdapter;
    private BluetoothDevice mDevice;
    private BluetoothDevice mDevice2;
    private BluetoothDevice mDevice3;
    private Context mTargetContext;
    private HapClientService mService;
    private HasIntentReceiver mHasIntentReceiver;
    private HashMap<BluetoothDevice, LinkedBlockingQueue<Intent>> mIntentQueue;

    @Mock
    private AdapterService mAdapterService;
    @Mock
    private DatabaseManager mDatabaseManager;
    @Mock
    private HapClientNativeInterface mNativeInterface;
    @Mock
    private ServiceFactory mServiceFactory;
    @Mock
    private CsipSetCoordinatorService mCsipService;
    @Mock
    private IBluetoothHapClientCallback mCallback;
    @Mock
    private Binder mBinder;

    @Before
    public void setUp() throws Exception {
        mTargetContext = InstrumentationRegistry.getTargetContext();
        // Set up mocks and test assets
        MockitoAnnotations.initMocks(this);

        if (Looper.myLooper() == null) {
            Looper.prepare();
        }

        HapClientStateMachine.sConnectTimeoutMs = TIMEOUT_MS;

        TestUtils.setAdapterService(mAdapterService);
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();
        doReturn(true, false).when(mAdapterService).isStartedProfile(anyString());

        mAdapter = BluetoothAdapter.getDefaultAdapter();

        startService();
        mService.mHapClientNativeInterface = mNativeInterface;
        mService.mFactory = mServiceFactory;
        doReturn(mCsipService).when(mServiceFactory).getCsipSetCoordinatorService();

        // Set up the State Changed receiver
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED);
        filter.addAction(BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE);

        when(mCallback.asBinder()).thenReturn(mBinder);
        mService.mCallbacks.register(mCallback);

        mHasIntentReceiver = new HasIntentReceiver();
        mTargetContext.registerReceiver(mHasIntentReceiver, filter);

        mDevice = TestUtils.getTestDevice(mAdapter, 0);
        when(mNativeInterface.getDevice(getByteAddress(mDevice))).thenReturn(mDevice);
        mDevice2 = TestUtils.getTestDevice(mAdapter, 1);
        when(mNativeInterface.getDevice(getByteAddress(mDevice2))).thenReturn(mDevice2);
        mDevice3 = TestUtils.getTestDevice(mAdapter, 2);
        when(mNativeInterface.getDevice(getByteAddress(mDevice3))).thenReturn(mDevice3);

        /* Prepare CAS groups */
        doReturn(Arrays.asList(0x02, 0x03)).when(mCsipService).getAllGroupIds(BluetoothUuid.CAP);

        int groupId2 = 0x02;
        Map groups2 = new HashMap<Integer, ParcelUuid>();
        groups2.put(groupId2, ParcelUuid.fromString("00001853-0000-1000-8000-00805F9B34FB"));

        int groupId3 = 0x03;
        Map groups3 = new HashMap<Integer, ParcelUuid>();
        groups3.put(groupId3,
                ParcelUuid.fromString("00001853-0000-1000-8000-00805F9B34FB"));

        doReturn(Arrays.asList(mDevice, mDevice2)).when(mCsipService)
                        .getGroupDevicesOrdered(groupId2);
        doReturn(groups2).when(mCsipService).getGroupUuidMapByDevice(mDevice);
        doReturn(groups2).when(mCsipService).getGroupUuidMapByDevice(mDevice2);

        doReturn(Arrays.asList(mDevice3)).when(mCsipService).getGroupDevicesOrdered(0x03);
        doReturn(groups3).when(mCsipService).getGroupUuidMapByDevice(mDevice3);

        doReturn(Arrays.asList(mDevice)).when(mCsipService).getGroupDevicesOrdered(0x01);

        doReturn(BluetoothDevice.BOND_BONDED).when(mAdapterService)
                .getBondState(any(BluetoothDevice.class));
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        doReturn(mDatabaseManager).when(mAdapterService).getDatabase();

        mIntentQueue = new HashMap<>();
        mIntentQueue.put(mDevice, new LinkedBlockingQueue<>());
        mIntentQueue.put(mDevice2, new LinkedBlockingQueue<>());
        mIntentQueue.put(mDevice3, new LinkedBlockingQueue<>());
    }

    @After
    public void tearDown() throws Exception {
        mService.mCallbacks.unregister(mCallback);

        stopService();
        mTargetContext.unregisterReceiver(mHasIntentReceiver);

        mAdapter = null;
        TestUtils.clearAdapterService(mAdapterService);
        mIntentQueue.clear();
    }

    private void startService() throws TimeoutException {
        TestUtils.startService(mServiceRule, HapClientService.class);
        mService = HapClientService.getHapClientService();
        Assert.assertNotNull(mService);
    }

    private void stopService() throws TimeoutException {
        TestUtils.stopService(mServiceRule, HapClientService.class);
        mService = HapClientService.getHapClientService();
        Assert.assertNull(mService);
    }

    /**
     * Test getting HA Service Client
     */
    @Test
    public void testGetHapService() {
        Assert.assertEquals(mService, HapClientService.getHapClientService());
    }

    /**
     * Test stop HA Service Client
     */
    @Test
    public void testStopHapService() {
        Assert.assertEquals(mService, HapClientService.getHapClientService());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                Assert.assertTrue(mService.stop());
            }
        });
        InstrumentationRegistry.getInstrumentation().runOnMainSync(new Runnable() {
            public void run() {
                Assert.assertTrue(mService.start());
            }
        });
    }

    /**
     * Test get/set policy for BluetoothDevice
     */
    @Test
    public void testGetSetPolicy() {
        when(mDatabaseManager
                .getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_UNKNOWN);
        Assert.assertEquals("Initial device policy",
                BluetoothProfile.CONNECTION_POLICY_UNKNOWN,
                mService.getConnectionPolicy(mDevice));

        when(mDatabaseManager
                .getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);
        Assert.assertEquals("Setting device policy to POLICY_FORBIDDEN",
                BluetoothProfile.CONNECTION_POLICY_FORBIDDEN,
                mService.getConnectionPolicy(mDevice));

        when(mDatabaseManager
                .getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        Assert.assertEquals("Setting device policy to POLICY_ALLOWED",
                BluetoothProfile.CONNECTION_POLICY_ALLOWED,
                mService.getConnectionPolicy(mDevice));
    }

    /**
     * Test okToConnect method using various test cases
     */
    @Test
    public void testOkToConnect() {
        int badPolicyValue = 1024;
        int badBondState = 42;
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_NONE, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_NONE, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_NONE, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_NONE, badPolicyValue, false);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_BONDING, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_BONDING, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_BONDING, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_BONDING, badPolicyValue, false);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_BONDED, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, true);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_BONDED, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_BONDED, BluetoothProfile.CONNECTION_POLICY_ALLOWED, true);
        testOkToConnectCase(mDevice,
                BluetoothDevice.BOND_BONDED, badPolicyValue, false);
        testOkToConnectCase(mDevice,
                badBondState, BluetoothProfile.CONNECTION_POLICY_UNKNOWN, false);
        testOkToConnectCase(mDevice,
                badBondState, BluetoothProfile.CONNECTION_POLICY_FORBIDDEN, false);
        testOkToConnectCase(mDevice,
                badBondState, BluetoothProfile.CONNECTION_POLICY_ALLOWED, false);
        testOkToConnectCase(mDevice,
                badBondState, badPolicyValue, false);
    }

    /**
     * Test that an outgoing connection to device that does not have HAS UUID is rejected
     */
    @Test
    public void testOutgoingConnectMissingHasUuid() {
        // Update the device policy so okToConnect() returns true
        when(mDatabaseManager
                .getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        // Return No UUID
        doReturn(new ParcelUuid[]{}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertFalse("Connect expected to fail", mService.connect(mDevice));
    }

    /**
     * Test that an outgoing connection to device that have HAS UUID is successful
     */
    @Test
    public void testOutgoingConnectExistingHasUuid() {
        // Update the device policy so okToConnect() returns true
        when(mDatabaseManager
                .getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertTrue("Connect expected to succeed", mService.connect(mDevice));
    }

    /**
     * Test that an outgoing connection to device with POLICY_FORBIDDEN is rejected
     */
    @Test
    public void testOutgoingConnectPolicyForbidden() {
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        // Set the device policy to POLICY_FORBIDDEN so connect() should fail
        when(mDatabaseManager
                .getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_FORBIDDEN);

        // Send a connect request
        Assert.assertFalse("Connect expected to fail", mService.connect(mDevice));
    }

    /**
     * Test that an outgoing connection times out
     */
    @Test
    public void testOutgoingConnectTimeout() {
        // Update the device policy so okToConnect() returns true
        when(mDatabaseManager
                .getProfileConnectionPolicy(mDevice, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));

        // Send a connect request
        Assert.assertTrue("Connect failed", mService.connect(mDevice));

        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, mDevice, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING,
                mService.getConnectionState(mDevice));

        // Verify the connection state broadcast, and that we are in Disconnected state
        verifyConnectionStateIntent(HapClientStateMachine.sConnectTimeoutMs * 2,
                mDevice, BluetoothProfile.STATE_DISCONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_DISCONNECTED,
                mService.getConnectionState(mDevice));
    }

    /**
     * Test that an outgoing connection to two device that have HAS UUID is successful
     */
    @Test
    public void testConnectTwo() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Send a connect request for the 1st device
        testConnectingDevice(mDevice);

        // Send a connect request for the 2nd device
        BluetoothDevice Device2 = TestUtils.getTestDevice(mAdapter, 1);
        testConnectingDevice(Device2);

        List<BluetoothDevice> devices = mService.getConnectedDevices();
        Assert.assertTrue(devices.contains(mDevice));
        Assert.assertTrue(devices.contains(Device2));
        Assert.assertNotEquals(mDevice, Device2);
    }

    /**
     * Test that for the unknown device the API calls are not forwarded down the stack to native.
     */
    @Test
    public void testCallsForNotConnectedDevice() {
        Assert.assertEquals(BluetoothHapClient.PRESET_INDEX_UNAVAILABLE,
                        mService.getActivePresetIndex(mDevice));
    }

    /**
     * Test getting HAS coordinated sets.
     */
    @Test
    public void testGetHapGroupCoordinatedOps() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice);
        testConnectingDevice(mDevice2);
        testConnectingDevice(mDevice3);

        int flags = 0x04;
        mNativeInterface.onFeaturesUpdate(getByteAddress(mDevice), flags);

        int flags3 = 0x04;
        mNativeInterface.onFeaturesUpdate(getByteAddress(mDevice3), flags);

        /* This one has no coordinated operation support but is part of a coordinated set with
         * mDevice, which supports it, thus mDevice will forward the operation to mDevice2.
         * This device should also be rocognised as grouped one.
         */
        int flags2 = 0;
        mNativeInterface.onFeaturesUpdate(getByteAddress(mDevice2), flags2);

        /* Two devices support coordinated operations thus shall report valid group ID */
        Assert.assertEquals(2, mService.getHapGroup(mDevice));
        Assert.assertEquals(3, mService.getHapGroup(mDevice3));

        /* Third one has no coordinated operations support but is part of the group */
        Assert.assertEquals(2, mService.getHapGroup(mDevice2));
    }

    /**
     * Test that selectPreset properly calls the native method.
     */
    @Test
    public void testSelectPresetNative() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice);

        // Verify Native Interface call
        mService.selectPreset(mDevice, 0x00);
        verify(mNativeInterface, times(0))
                .selectActivePreset(eq(mDevice), eq(0x00));
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onPresetSelectionFailed(eq(mDevice),
                    eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mService.selectPreset(mDevice, 0x01);
        verify(mNativeInterface, times(1))
                .selectActivePreset(eq(mDevice), eq(0x01));
    }

    /**
     * Test that groupSelectActivePreset properly calls the native method.
     */
    @Test
    public void testGroupSelectActivePresetNative() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice3);

        int flags = 0x01;
        mNativeInterface.onFeaturesUpdate(getByteAddress(mDevice3), flags);

        // Verify Native Interface call
        mService.selectPresetForGroup(0x03, 0x00);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onPresetSelectionForGroupFailed(
                    eq(0x03), eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mService.selectPresetForGroup(0x03, 0x01);
        verify(mNativeInterface, times(1))
                .groupSelectActivePreset(eq(0x03), eq(0x01));
    }

    /**
     * Test that nextActivePreset properly calls the native method.
     */
    @Test
    public void testSwitchToNextPreset() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice);

        // Verify Native Interface call
        mService.switchToNextPreset(mDevice);
        verify(mNativeInterface, times(1))
                .nextActivePreset(eq(mDevice));
    }

    /**
     * Test that groupNextActivePreset properly calls the native method.
     */
    @Test
    public void testSwitchToNextPresetForGroup() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice3);
        int flags = 0x01;
        mNativeInterface.onFeaturesUpdate(getByteAddress(mDevice3), flags);

        // Verify Native Interface call
        mService.switchToNextPresetForGroup(0x03);
        verify(mNativeInterface, times(1)).groupNextActivePreset(eq(0x03));
    }

    /**
     * Test that previousActivePreset properly calls the native method.
     */
    @Test
    public void testSwitchToPreviousPreset() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice);

        // Verify Native Interface call
        mService.switchToPreviousPreset(mDevice);
        verify(mNativeInterface, times(1))
                .previousActivePreset(eq(mDevice));
    }

    /**
     * Test that groupPreviousActivePreset properly calls the native method.
     */
    @Test
    public void testSwitchToPreviousPresetForGroup() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice);
        testConnectingDevice(mDevice2);

        int flags = 0x01;
        mNativeInterface.onFeaturesUpdate(getByteAddress(mDevice), flags);

        // Verify Native Interface call
        mService.switchToPreviousPresetForGroup(0x02);
        verify(mNativeInterface, times(1)).groupPreviousActivePreset(eq(0x02));
    }

    /**
     * Test that getActivePresetIndex returns cached value.
     */
    @Test
    public void testGetActivePresetIndex() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice);
        testOnPresetSelected(mDevice, 0x01);

        // Verify cached value
        Assert.assertEquals(0x01, mService.getActivePresetIndex(mDevice));
    }

    /**
     * Test that getActivePresetInfo returns cached value for valid parameters.
     */
    @Test
    public void testGetActivePresetInfo() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice2);

        // Check when active preset is not known yet
        Assert.assertEquals(BluetoothHapClient.PRESET_INDEX_UNAVAILABLE,
                mService.getActivePresetIndex(mDevice2));
        Assert.assertEquals(null, mService.getActivePresetInfo(mDevice2));

        // Inject active preset change event
        testOnPresetSelected(mDevice2, 0x01);

        // Check when active preset is known
        Assert.assertEquals(0x01, mService.getActivePresetIndex(mDevice2));
        BluetoothHapPresetInfo info = mService.getActivePresetInfo(mDevice2);
        Assert.assertNotNull(info);
        Assert.assertEquals(0x01, info.getIndex());
    }

    /**
     * Test that setPresetName properly calls the native method for the valid parameters.
     */
    @Test
    public void testSetPresetNameNative() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        testConnectingDevice(mDevice);

        mService.setPresetName(mDevice, 0x00, "ExamplePresetName");
        verify(mNativeInterface, times(0))
                .setPresetName(eq(mDevice), eq(0x00), eq("ExamplePresetName"));
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameFailed(eq(mDevice),
                    eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Verify Native Interface call
        mService.setPresetName(mDevice, 0x01, "ExamplePresetName");
        verify(mNativeInterface, times(1))
                .setPresetName(eq(mDevice), eq(0x01), eq("ExamplePresetName"));
    }

    /**
     * Test that setPresetNameForGroup properly calls the native method for the valid parameters.
     */
    @Test
    public void testSetPresetNameForGroup() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));
        int test_group = 0x02;
        for (BluetoothDevice device : mCsipService.getGroupDevicesOrdered(test_group)) {
            testConnectingDevice(device);
        }

        int flags = 0x21;
        mNativeInterface.onFeaturesUpdate(getByteAddress(mDevice), flags);

        mService.setPresetNameForGroup(test_group, 0x00, "ExamplePresetName");
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameForGroupFailed(eq(test_group),
                    eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        mService.setPresetNameForGroup(-1, 0x01, "ExamplePresetName");
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameForGroupFailed(eq(-1),
                    eq(BluetoothStatusCodes.ERROR_CSIP_INVALID_GROUP_ID));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Verify Native Interface call
        mService.setPresetNameForGroup(test_group, 0x01, "ExamplePresetName");
        verify(mNativeInterface, times(1))
                .groupSetPresetName(eq(test_group), eq(0x01), eq("ExamplePresetName"));
    }

    /**
     * Test that native callback generates proper intent.
     */
    @Test
    public void testStackEventDeviceAvailable() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        doCallRealMethod()
                .when(mNativeInterface)
                .onDeviceAvailable(any(byte[].class), anyInt());
        mNativeInterface.onDeviceAvailable(getByteAddress(mDevice), 0x03);

        Intent intent = TestUtils.waitForIntent(TIMEOUT_MS, mIntentQueue.get(mDevice));
        Assert.assertNotNull(intent);
        Assert.assertEquals(BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE, intent.getAction());
        Assert.assertEquals(mDevice, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(0x03,
                intent.getIntExtra(BluetoothHapClient.EXTRA_HAP_FEATURES, 0x00));
    }

    /**
     * Test that native callback generates proper callback call.
     */
    @Test
    public void testStackEventOnPresetSelected() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        doCallRealMethod()
                .when(mNativeInterface)
                .onActivePresetSelected(any(byte[].class), anyInt());
        mNativeInterface.onActivePresetSelected(getByteAddress(mDevice), 0x01);

        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onPresetSelected(eq(mDevice),
                    eq(0x01), eq(BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }

        // Verify that getting current preset returns a proper value now
        Assert.assertEquals(0x01, mService.getActivePresetIndex(mDevice));
    }

    /**
     * Test that native callback generates proper callback call.
     */
    @Test
    public void testStackEventOnActivePresetSelectError() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));


        doCallRealMethod()
                .when(mNativeInterface)
                .onActivePresetSelectError(any(byte[].class), anyInt());
        /* Send INVALID_PRESET_INDEX error */
        mNativeInterface.onActivePresetSelectError(getByteAddress(mDevice), 0x05);

        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onPresetSelectionFailed(eq(mDevice),
                    eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Test that native callback generates proper callback call.
     */
    @Test
    public void testStackEventOnPresetInfo() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        // Connect and inject initial presets
        testConnectingDevice(mDevice);

        int info_reason = HapClientStackEvent.PRESET_INFO_REASON_PRESET_INFO_UPDATE;
        BluetoothHapPresetInfo[] info =
                {new BluetoothHapPresetInfo.Builder(0x01, "OneChangedToUnavailable")
                        .setWritable(true)
                        .setAvailable(false)
                        .build()};

        doCallRealMethod()
                .when(mNativeInterface)
                .onPresetInfo(any(byte[].class), anyInt(), any());
        mNativeInterface.onPresetInfo(getByteAddress(mDevice), info_reason, info);

        ArgumentCaptor<List<BluetoothHapPresetInfo>> presetsCaptor =
                ArgumentCaptor.forClass(List.class);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onPresetInfoChanged(eq(mDevice),
                    presetsCaptor.capture(), eq(BluetoothStatusCodes.REASON_REMOTE_REQUEST));
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }

        List<BluetoothHapPresetInfo> presets = presetsCaptor.getValue();
        Assert.assertEquals(3, presets.size());

        Optional<BluetoothHapPresetInfo> preset = presetsCaptor.getValue()
                                    .stream()
                                    .filter(p -> 0x01 == p.getIndex())
                                    .findFirst();
        Assert.assertEquals("OneChangedToUnavailable", preset.get().getName());
        Assert.assertFalse(preset.get().isAvailable());
        Assert.assertTrue(preset.get().isWritable());
    }

    /**
     * Test that native callback generates proper callback call.
     */
    @Test
    public void testStackEventOnPresetNameSetError() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        doCallRealMethod()
                .when(mNativeInterface)
                .onPresetNameSetError(any(byte[].class), anyInt(), anyInt());
        /* Not a valid name length */
        mNativeInterface.onPresetNameSetError(getByteAddress(mDevice), 0x01,
                HapClientStackEvent.STATUS_INVALID_PRESET_NAME_LENGTH);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameFailed(eq(mDevice),
                    eq(BluetoothStatusCodes.ERROR_HAP_PRESET_NAME_TOO_LONG));
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }

        /* Invalid preset index provided */
        mNativeInterface.onPresetNameSetError(getByteAddress(mDevice), 0x01,
                HapClientStackEvent.STATUS_INVALID_PRESET_INDEX);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameFailed(eq(mDevice),
                    eq(BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX));
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }

        /* Not allowed on this particular preset */
        mNativeInterface.onPresetNameSetError(getByteAddress(mDevice), 0x01,
                HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameFailed(eq(mDevice),
                    eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeInterface.onPresetNameSetError(getByteAddress(mDevice), 0x01,
                HapClientStackEvent.STATUS_OPERATION_NOT_POSSIBLE);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(2)).onSetPresetNameFailed(eq(mDevice),
                    eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED));
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }

        /* Not allowed on all presets - for example missing characteristic */
        mNativeInterface.onPresetNameSetError(getByteAddress(mDevice), 0x01,
                HapClientStackEvent.STATUS_OPERATION_NOT_SUPPORTED);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameFailed(eq(mDevice),
                    eq(BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED));
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Test that native callback generates proper callback call.
     */
    @Test
    public void testStackEventOnGroupPresetNameSetError() {
        doReturn(new ParcelUuid[]{BluetoothUuid.HAS}).when(mAdapterService)
                .getRemoteUuids(any(BluetoothDevice.class));

        doCallRealMethod()
                .when(mNativeInterface)
                .onGroupPresetNameSetError(anyInt(), anyInt(), anyInt());

        /* Not a valid name length */
        mNativeInterface.onGroupPresetNameSetError(0x01, 0x01,
                HapClientStackEvent.STATUS_INVALID_PRESET_NAME_LENGTH);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameForGroupFailed(0x01,
                    BluetoothStatusCodes.ERROR_HAP_PRESET_NAME_TOO_LONG);
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }

        /* Invalid preset index provided */
        mNativeInterface.onGroupPresetNameSetError(0x01, 0x01,
                HapClientStackEvent.STATUS_INVALID_PRESET_INDEX);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameForGroupFailed(0x01,
                    BluetoothStatusCodes.ERROR_HAP_INVALID_PRESET_INDEX);
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }

        /* Not allowed on this particular preset */
        mNativeInterface.onGroupPresetNameSetError(0x01, 0x01,
                HapClientStackEvent.STATUS_SET_NAME_NOT_ALLOWED);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameForGroupFailed(0x01,
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED);
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }

        /* Not allowed on this particular preset at this time, might be possible later on */
        mNativeInterface.onGroupPresetNameSetError(0x01, 0x01,
                HapClientStackEvent.STATUS_OPERATION_NOT_POSSIBLE);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(2)).onSetPresetNameForGroupFailed(0x01,
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_REJECTED);
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }

        /* Not allowed on all presets - for example if peer is missing optional CP characteristic */
        mNativeInterface.onGroupPresetNameSetError(0x01, 0x01,
                HapClientStackEvent.STATUS_OPERATION_NOT_SUPPORTED);
        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onSetPresetNameForGroupFailed(0x01,
                    BluetoothStatusCodes.ERROR_REMOTE_OPERATION_NOT_SUPPORTED);
        } catch (RemoteException e) {
                throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Helper function to test device connecting
     */
    private void prepareConnectingDevice(BluetoothDevice device) {
        // Prepare intent queue and all the mocks
        mIntentQueue.put(device, new LinkedBlockingQueue<>());
        when(mNativeInterface.getDevice(getByteAddress(device))).thenReturn(device);
        when(mDatabaseManager
                .getProfileConnectionPolicy(device, BluetoothProfile.HAP_CLIENT))
                .thenReturn(BluetoothProfile.CONNECTION_POLICY_ALLOWED);
        doReturn(true).when(mNativeInterface).connectHapClient(any(BluetoothDevice.class));
        doReturn(true).when(mNativeInterface).disconnectHapClient(any(BluetoothDevice.class));
    }

    /**
     * Helper function to test device connecting
     */
    private void testConnectingDevice(BluetoothDevice device) {
        prepareConnectingDevice(device);
        // Send a connect request
        Assert.assertTrue("Connect expected to succeed", mService.connect(device));
        verifyConnectingDevice(device);
    }

    /**
     * Helper function to test device connecting
     */
    private void verifyConnectingDevice(BluetoothDevice device) {
        // Verify the connection state broadcast, and that we are in Connecting state
        verifyConnectionStateIntent(TIMEOUT_MS, device, BluetoothProfile.STATE_CONNECTING,
                BluetoothProfile.STATE_DISCONNECTED);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTING, mService.getConnectionState(device));

        // Send a message to trigger connection completed
        HapClientStackEvent evt =
                new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_CONNECTION_STATE_CHANGED);
        evt.device = device;
        evt.valueInt1 = HapClientStackEvent.CONNECTION_STATE_CONNECTED;
        mService.messageFromNative(evt);

        // Verify the connection state broadcast, and that we are in Connected state
        verifyConnectionStateIntent(TIMEOUT_MS, device, BluetoothProfile.STATE_CONNECTED,
                BluetoothProfile.STATE_CONNECTING);
        Assert.assertEquals(BluetoothProfile.STATE_CONNECTED, mService.getConnectionState(device));

        evt = new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_DEVICE_AVAILABLE);
        evt.device = device;
        evt.valueInt1 = 0x01;   // features
        mService.messageFromNative(evt);

        Intent intent = TestUtils.waitForIntent(TIMEOUT_MS, mIntentQueue.get(device));
        Assert.assertNotNull(intent);
        Assert.assertEquals(BluetoothHapClient.ACTION_HAP_DEVICE_AVAILABLE, intent.getAction());
        Assert.assertEquals(device, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(evt.valueInt1,
                intent.getIntExtra(BluetoothHapClient.EXTRA_HAP_FEATURES, -1));

        evt = new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_DEVICE_FEATURES);
        evt.device = device;
        evt.valueInt1 = 0x01; // features
        mService.messageFromNative(evt);

        // Inject some initial presets
        List<BluetoothHapPresetInfo> presets =
                new ArrayList<BluetoothHapPresetInfo>(Arrays.asList(
                        new BluetoothHapPresetInfo.Builder(0x01, "One")
                                .setAvailable(true)
                                .setWritable(false)
                                .build(),
                        new BluetoothHapPresetInfo.Builder(0x02, "Two")
                                .setAvailable(true)
                                .setWritable(true)
                                .build(),
                        new BluetoothHapPresetInfo.Builder(0x03, "Three")
                                .setAvailable(false)
                                .setWritable(false)
                                .build()));
        mService.updateDevicePresetsCache(device,
                HapClientStackEvent.PRESET_INFO_REASON_ALL_PRESET_INFO, presets);
    }

    private void testOnPresetSelected(BluetoothDevice device, int index) {
        HapClientStackEvent evt =
                new HapClientStackEvent(HapClientStackEvent.EVENT_TYPE_ON_ACTIVE_PRESET_SELECTED);
        evt.device = device;
        evt.valueInt1 = index;
        mService.messageFromNative(evt);

        try {
            verify(mCallback, after(TIMEOUT_MS).times(1)).onPresetSelected(eq(device),
                    eq(evt.valueInt1), eq(BluetoothStatusCodes.REASON_LOCAL_STACK_REQUEST));
        } catch (RemoteException e) {
            throw e.rethrowFromSystemServer();
        }
    }

    /**
     * Helper function to test ConnectionStateIntent() method
     */
    private void verifyConnectionStateIntent(int timeoutMs, BluetoothDevice device,
                                             int newState, int prevState) {
        Intent intent = TestUtils.waitForIntent(timeoutMs, mIntentQueue.get(device));
        Assert.assertNotNull(intent);
        Assert.assertEquals(BluetoothHapClient.ACTION_HAP_CONNECTION_STATE_CHANGED,
                intent.getAction());
        Assert.assertEquals(device, intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE));
        Assert.assertEquals(newState, intent.getIntExtra(BluetoothProfile.EXTRA_STATE, -1));
        Assert.assertEquals(prevState, intent.getIntExtra(BluetoothProfile.EXTRA_PREVIOUS_STATE,
                -1));
    }

    /**
     * Helper function to test okToConnect() method
     */
    private void testOkToConnectCase(BluetoothDevice device, int bondState, int policy,
                                     boolean expected) {
        doReturn(bondState).when(mAdapterService).getBondState(device);
        when(mDatabaseManager.getProfileConnectionPolicy(device, BluetoothProfile.HAP_CLIENT))
                .thenReturn(policy);
        Assert.assertEquals(expected, mService.okToConnect(device));
    }

    /**
     * Helper function to get byte array for a device address
     */
    private byte[] getByteAddress(BluetoothDevice device) {
        if (device == null) {
            return Utils.getBytesFromAddress("00:00:00:00:00:00");
        }
        return Utils.getBytesFromAddress(device.getAddress());
    }

    private class HasIntentReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            try {
                BluetoothDevice device = intent.getParcelableExtra(
                        BluetoothDevice.EXTRA_DEVICE);
                Assert.assertNotNull(device);
                LinkedBlockingQueue<Intent> queue = mIntentQueue.get(device);
                Assert.assertNotNull(queue);
                queue.put(intent);
            } catch (InterruptedException e) {
                Assert.fail("Cannot add Intent to the queue: " + e.getMessage());
            }
        }
    }
}
