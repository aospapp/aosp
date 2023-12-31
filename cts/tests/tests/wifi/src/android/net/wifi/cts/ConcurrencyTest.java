/*
 * Copyright (C) 2012 The Android Open Source Project
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

package android.net.wifi.cts;

import static android.content.Context.RECEIVER_NOT_EXPORTED;
import static android.net.wifi.p2p.WifiP2pConfig.GROUP_CLIENT_IP_PROVISIONING_MODE_IPV6_LINK_LOCAL;
import static android.os.Process.myUid;

import static org.junit.Assert.assertNotEquals;

import android.app.UiAutomation;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.MacAddress;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WpsInfo;
import android.net.wifi.p2p.WifiP2pConfig;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import android.net.wifi.p2p.WifiP2pGroupList;
import android.net.wifi.p2p.WifiP2pInfo;
import android.net.wifi.p2p.WifiP2pManager;
import android.net.wifi.p2p.WifiP2pManager.ExternalApproverRequestListener;
import android.net.wifi.p2p.WifiP2pWfdInfo;
import android.net.wifi.p2p.nsd.WifiP2pServiceInfo;
import android.net.wifi.p2p.nsd.WifiP2pUpnpServiceInfo;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerExecutor;
import android.os.HandlerThread;
import android.os.WorkSource;
import android.platform.test.annotations.AppModeFull;
import android.provider.Settings;
import android.support.test.uiautomator.UiDevice;
import android.util.Log;

import androidx.test.filters.SdkSuppress;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.compatibility.common.util.ApiLevelUtil;
import com.android.compatibility.common.util.PollingCheck;
import com.android.compatibility.common.util.ShellIdentityUtils;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.stream.Collectors;

@AppModeFull(reason = "Cannot get WifiManager in instant app mode")
public class ConcurrencyTest extends WifiJUnit3TestBase {
    private class MySync {
        static final int P2P_STATE = 1;
        static final int DISCOVERY_STATE = 2;
        static final int NETWORK_INFO = 3;
        static final int LISTEN_STATE = 4;

        public BitSet pendingSync = new BitSet();

        public int expectedP2pState = WifiP2pManager.WIFI_P2P_STATE_DISABLED;
        public int expectedDiscoveryState;
        public NetworkInfo expectedNetworkInfo;
        public int expectedListenState;
    }

    private class MyResponse {
        public boolean valid = false;

        public boolean success;
        public int failureReason;
        public int p2pState;
        public int discoveryState;
        public int listenState;
        public NetworkInfo networkInfo;
        public WifiP2pInfo p2pInfo;
        public String deviceName;
        public WifiP2pGroupList persistentGroups;
        public WifiP2pGroup group = new WifiP2pGroup();

        // External approver
        public boolean isAttached;
        public boolean isDetached;
        public int detachReason;
        public MacAddress targetPeer;

        public void reset() {
            valid = false;

            networkInfo = null;
            p2pInfo = null;
            deviceName = null;
            persistentGroups = null;
            group = null;

            isAttached = false;
            isDetached = false;
            targetPeer = null;
        }
    }

    private WifiManager mWifiManager;
    private WifiP2pManager mWifiP2pManager;
    private WifiP2pManager.Channel mWifiP2pChannel;
    private final MySync mMySync = new MySync();
    private final MyResponse mMyResponse = new MyResponse();
    private boolean mWasVerboseLoggingEnabled;
    private WifiP2pConfig mTestWifiP2pPeerConfig;
    private boolean mWasWifiEnabled;
    private UiDevice mUiDevice;
    private TestHelper mTestHelper;
    private boolean mWasScanThrottleEnabled;


    private static final String TAG = "ConcurrencyTest";
    private static final int TIMEOUT_MS = 15000;
    private static final int WAIT_MS = 100;
    private static final int DURATION = 5000;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)) {
                synchronized (mMySync) {
                    mMySync.pendingSync.set(MySync.P2P_STATE);
                    mMySync.expectedP2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                    Log.d(TAG, "Get WIFI_P2P_STATE_CHANGED_ACTION: "
                            + mMySync.expectedP2pState);
                    mMySync.notify();
                }
            } else if (action.equals(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)) {
                synchronized (mMySync) {
                    mMySync.pendingSync.set(MySync.DISCOVERY_STATE);
                    mMySync.expectedDiscoveryState = intent.getIntExtra(
                            WifiP2pManager.EXTRA_DISCOVERY_STATE,
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                    Log.d(TAG, "Get WIFI_P2P_STATE_CHANGED_ACTION: "
                            + mMySync.expectedDiscoveryState);
                    mMySync.notify();
                }
            } else if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                synchronized (mMySync) {
                    mMySync.pendingSync.set(MySync.NETWORK_INFO);
                    mMySync.expectedNetworkInfo = (NetworkInfo) intent.getExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO, null);
                    Log.d(TAG, "Get WIFI_P2P_CONNECTION_CHANGED_ACTION: "
                            + mMySync.expectedNetworkInfo);
                    mMySync.notify();
                }
            } else if (action.equals(WifiP2pManager.ACTION_WIFI_P2P_LISTEN_STATE_CHANGED)) {
                synchronized (mMySync) {
                    mMySync.pendingSync.set(MySync.LISTEN_STATE);
                    mMySync.expectedListenState = intent.getIntExtra(
                            WifiP2pManager.EXTRA_LISTEN_STATE,
                            WifiP2pManager.WIFI_P2P_LISTEN_STOPPED);
                    mMySync.notify();
                }
            }
        }
    };

    private WifiP2pManager.ActionListener mActionListener = new WifiP2pManager.ActionListener() {
        @Override
        public void onSuccess() {
            synchronized (mMyResponse) {
                mMyResponse.valid = true;
                mMyResponse.success = true;
                mMyResponse.notify();
            }
        }

        @Override
        public void onFailure(int reason) {
            synchronized (mMyResponse) {
                Log.d(TAG, "failure reason: " + reason);
                mMyResponse.valid = true;
                mMyResponse.success = false;
                mMyResponse.failureReason = reason;
                mMyResponse.notify();
            }
        }
    };

    private final HandlerThread mHandlerThread = new HandlerThread("WifiP2pConcurrencyTest");
    protected final Executor mExecutor;
    {
        mHandlerThread.start();
        mExecutor = new HandlerExecutor(new Handler(mHandlerThread.getLooper()));
    }

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (!WifiFeature.isWifiSupported(getContext())
                && !WifiFeature.isP2pSupported(getContext())) {
            // skip the test if WiFi && p2p are not supported
            return;
        }

        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mTestHelper = new TestHelper(mContext, mUiDevice);
        mTestHelper.turnScreenOn();

        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);
        intentFilter.addAction(WifiP2pManager.ACTION_WIFI_P2P_LISTEN_STATE_CHANGED);
        intentFilter.setPriority(999);
        if (ApiLevelUtil.isAtLeast(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)) {
            mContext.registerReceiver(mReceiver, intentFilter, RECEIVER_NOT_EXPORTED);
        } else {
            mContext.registerReceiver(mReceiver, intentFilter);
        }
        mWifiManager = (WifiManager) getContext().getSystemService(Context.WIFI_SERVICE);
        assertNotNull(mWifiManager);

        // turn on verbose logging for tests
        mWasVerboseLoggingEnabled = ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.isVerboseLoggingEnabled());
        ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.setVerboseLoggingEnabled(true));
        mWasScanThrottleEnabled = ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.isScanThrottleEnabled());
        ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.setScanThrottleEnabled(false));

        mWasWifiEnabled = mWifiManager.isWifiEnabled();
        if (mWasWifiEnabled) {
            // Clean the possible P2P enabled broadcast from other test case.
            waitForBroadcasts(MySync.P2P_STATE);
            ShellIdentityUtils.invokeWithShellPermissions(() -> mWifiManager.setWifiEnabled(false));
            PollingCheck.check("Wifi not disabled", DURATION, () -> !mWifiManager.isWifiEnabled());
            // Make sure WifiP2P is disabled
            waitForBroadcasts(MySync.P2P_STATE);
            assertEquals(WifiP2pManager.WIFI_P2P_STATE_DISABLED, mMySync.expectedP2pState);
        }

        // Clean all the state
        synchronized (mMySync) {
            mMySync.expectedP2pState = WifiP2pManager.WIFI_P2P_STATE_DISABLED;
            mMySync.expectedDiscoveryState = WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED;
            mMySync.expectedNetworkInfo = null;
            mMySync.expectedListenState = WifiP2pManager.WIFI_P2P_LISTEN_STOPPED;
            mMySync.pendingSync.clear();
            resetResponse(mMyResponse);
        }

        // for general connect command
        mTestWifiP2pPeerConfig = new WifiP2pConfig();
        mTestWifiP2pPeerConfig.deviceAddress = "aa:bb:cc:dd:ee:ff";
    }

    @Override
    protected void tearDown() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext()) &&
                !WifiFeature.isP2pSupported(getContext())) {
            // skip the test if WiFi and p2p are not supported
            super.tearDown();
            return;
        }
        if (null != mWifiP2pManager) {
            removeAllPersistentGroups();
        }
        mContext.unregisterReceiver(mReceiver);
        ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.setVerboseLoggingEnabled(mWasVerboseLoggingEnabled));
        ShellIdentityUtils.invokeWithShellPermissions(
                () -> mWifiManager.setScanThrottleEnabled(mWasScanThrottleEnabled));
        if (mWasWifiEnabled) {
            enableWifi();
        }
        mTestHelper.turnScreenOff();
        super.tearDown();
    }

    private boolean waitForBroadcasts(List<Integer> waitSyncList) {
        synchronized (mMySync) {
            long timeout = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < timeout) {
                List<Integer> handledSyncList = waitSyncList.stream()
                        .filter(w -> mMySync.pendingSync.get(w))
                        .collect(Collectors.toList());
                handledSyncList.forEach(w -> mMySync.pendingSync.clear(w));
                waitSyncList.removeAll(handledSyncList);
                if (waitSyncList.isEmpty()) {
                    break;
                }
                try {
                    mMySync.wait(WAIT_MS);
                } catch (InterruptedException e) { }
            }
            if (!waitSyncList.isEmpty()) {
                Log.i(TAG, "Missing broadcast: " + waitSyncList);
            }
            return waitSyncList.isEmpty();
        }
    }

    private boolean waitForBroadcasts(int waitSingleSync) {
        return waitForBroadcasts(
                new LinkedList<Integer>(Arrays.asList(waitSingleSync)));
    }

    private NetworkInfo.DetailedState waitForNextNetworkState() {
        waitForBroadcasts(MySync.NETWORK_INFO);
        assertNotNull(mMySync.expectedNetworkInfo);
        return mMySync.expectedNetworkInfo.getDetailedState();
    }

    private boolean waitForConnectedNetworkState() {
        // The possible orders of network states are:
        // * IDLE > CONNECTING > CONNECTED for lazy initialization
        // * DISCONNECTED > CONNECTING > CONNECTED for previous group removal
        // * CONNECTING > CONNECTED
        NetworkInfo.DetailedState state = waitForNextNetworkState();
        if (state == NetworkInfo.DetailedState.IDLE
                || state == NetworkInfo.DetailedState.DISCONNECTED) {
            state = waitForNextNetworkState();
        }
        if (ApiLevelUtil.isAtLeast(Build.VERSION_CODES.TIRAMISU)
                && state == NetworkInfo.DetailedState.CONNECTING) {
            state = waitForNextNetworkState();
        }
        return state == NetworkInfo.DetailedState.CONNECTED;
    }

    private boolean waitForServiceResponse(MyResponse waitResponse) {
        synchronized (waitResponse) {
            long timeout = System.currentTimeMillis() + TIMEOUT_MS;
            while (System.currentTimeMillis() < timeout) {
                try {
                    waitResponse.wait(WAIT_MS);
                } catch (InterruptedException e) { }

                if (waitResponse.valid) {
                    return true;
                }
            }
            return false;
        }
    }

    // Return true if location is enabled.
    private boolean isLocationEnabled() {
        return Settings.Secure.getInt(getContext().getContentResolver(),
                Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF)
                != Settings.Secure.LOCATION_MODE_OFF;
    }

    // Returns true if the device has location feature.
    private boolean hasLocationFeature() {
        return getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION);
    }

    private void resetResponse(MyResponse responseObj) {
        synchronized (responseObj) {
            responseObj.reset();
        }
    }

    /*
     * Enables Wifi and block until connection is established.
     */
    private void enableWifi() throws Exception {
        if (!mWifiManager.isWifiEnabled()) {
            ShellIdentityUtils.invokeWithShellPermissions(() -> mWifiManager.setWifiEnabled(true));
            PollingCheck.check("Wifi not enabled", DURATION, () -> mWifiManager.isWifiEnabled());
            ShellIdentityUtils.invokeWithShellPermissions(
                    () -> mWifiManager.startScan(new WorkSource(myUid())));
            PollingCheck.check("Wifi not connected", DURATION,
                    () -> mWifiManager.getConnectionInfo().getNetworkId() != -1);
        }
    }

    private void removeAllPersistentGroups() {
        WifiP2pGroupList persistentGroups = getPersistentGroups();
        assertNotNull(persistentGroups);
        for (WifiP2pGroup group: persistentGroups.getGroupList()) {
            resetResponse(mMyResponse);
            ShellIdentityUtils.invokeWithShellPermissions(() -> {
                mWifiP2pManager.deletePersistentGroup(mWifiP2pChannel,
                        group.getNetworkId(),
                        mActionListener);
                assertTrue(waitForServiceResponse(mMyResponse));
                assertTrue(mMyResponse.success);
            });
        }
        persistentGroups = getPersistentGroups();
        assertNotNull(persistentGroups);
        assertEquals(0, persistentGroups.getGroupList().size());
    }

    private boolean setupWifiP2p() {
        // Cannot support p2p alone
        if (!WifiFeature.isWifiSupported(getContext())) {
            assertFalse(WifiFeature.isP2pSupported(getContext()));
            return false;
        }

        if (!WifiFeature.isP2pSupported(getContext())) {
            // skip the test if p2p is not supported
            return false;
        }

        if (!hasLocationFeature()) {
            Log.d(TAG, "Skipping test as location is not supported");
            return false;
        }
        if (!isLocationEnabled()) {
            fail("Please enable location for this test - since P-release WiFi Direct"
                    + " needs Location enabled.");
        }
        try {
            enableWifi();
        } catch (Exception e) {
            Log.d(TAG, "Enable Wifi got exception:" + e.getMessage());
        }

        assertTrue(mWifiManager.isWifiEnabled());

        mWifiP2pManager =
                (WifiP2pManager) getContext().getSystemService(Context.WIFI_P2P_SERVICE);
        mWifiP2pChannel = mWifiP2pManager.initialize(
                getContext(), getContext().getMainLooper(), null);

        assertNotNull(mWifiP2pManager);
        assertNotNull(mWifiP2pChannel);

        assertTrue(waitForBroadcasts(MySync.P2P_STATE));

        assertEquals(WifiP2pManager.WIFI_P2P_STATE_ENABLED, mMySync.expectedP2pState);
        removeAllPersistentGroups();

        return true;
    }

    public void testConcurrency() {
        if (!setupWifiP2p()) {
            return;
        }

        resetResponse(mMyResponse);
        mWifiP2pManager.requestP2pState(mWifiP2pChannel, new WifiP2pManager.P2pStateListener() {
            @Override
            public void onP2pStateAvailable(int state) {
                synchronized (mMyResponse) {
                    mMyResponse.valid = true;
                    mMyResponse.p2pState = state;
                    mMyResponse.notify();
                }
            }
        });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_STATE_ENABLED, mMyResponse.p2pState);
    }

    public void testRequestDiscoveryState() {
        if (!setupWifiP2p()) {
            return;
        }

        resetResponse(mMyResponse);
        mWifiP2pManager.requestDiscoveryState(
                mWifiP2pChannel, new WifiP2pManager.DiscoveryStateListener() {
                    @Override
                    public void onDiscoveryStateAvailable(int state) {
                        synchronized (mMyResponse) {
                            mMyResponse.valid = true;
                            mMyResponse.discoveryState = state;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED, mMyResponse.discoveryState);

        // If there is any saved network and this device is connecting to this saved network,
        // p2p discovery might be blocked during DHCP provision.
        int retryCount = 3;
        while (retryCount > 0) {
            resetResponse(mMyResponse);
            mWifiP2pManager.discoverPeers(mWifiP2pChannel, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            if (mMyResponse.success
                    || mMyResponse.failureReason != WifiP2pManager.BUSY) {
                break;
            }
            Log.w(TAG, "Discovery is blocked, try again!");
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) {}
            retryCount--;
        }
        assertTrue(mMyResponse.success);
        assertTrue(waitForBroadcasts(MySync.DISCOVERY_STATE));

        resetResponse(mMyResponse);
        mWifiP2pManager.requestDiscoveryState(mWifiP2pChannel,
                new WifiP2pManager.DiscoveryStateListener() {
                    @Override
                    public void onDiscoveryStateAvailable(int state) {
                        synchronized (mMyResponse) {
                            mMyResponse.valid = true;
                            mMyResponse.discoveryState = state;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED, mMyResponse.discoveryState);

        mWifiP2pManager.stopPeerDiscovery(mWifiP2pChannel, null);
    }

    public void testRequestNetworkInfo() {
        if (!setupWifiP2p()) {
            return;
        }

        resetResponse(mMyResponse);
        mWifiP2pManager.requestNetworkInfo(mWifiP2pChannel,
                new WifiP2pManager.NetworkInfoListener() {
                    @Override
                    public void onNetworkInfoAvailable(NetworkInfo info) {
                        synchronized (mMyResponse) {
                            mMyResponse.valid = true;
                            mMyResponse.networkInfo = info;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertNotNull(mMyResponse.networkInfo);
        // The state might be IDLE, DISCONNECTED, FAILED before a connection establishment.
        // Just ensure the state is NOT CONNECTED.
        assertNotEquals(NetworkInfo.DetailedState.CONNECTED,
                mMySync.expectedNetworkInfo.getDetailedState());

        resetResponse(mMyResponse);
        mWifiP2pManager.createGroup(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);

        assertTrue(waitForConnectedNetworkState());

        resetResponse(mMyResponse);
        mWifiP2pManager.requestNetworkInfo(mWifiP2pChannel,
                new WifiP2pManager.NetworkInfoListener() {
                    @Override
                    public void onNetworkInfoAvailable(NetworkInfo info) {
                        synchronized (mMyResponse) {
                            mMyResponse.valid = true;
                            mMyResponse.networkInfo = info;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertNotNull(mMyResponse.networkInfo);
        assertEquals(NetworkInfo.DetailedState.CONNECTED,
                mMyResponse.networkInfo.getDetailedState());

        resetResponse(mMyResponse);
        mWifiP2pManager.requestConnectionInfo(mWifiP2pChannel,
                new WifiP2pManager.ConnectionInfoListener() {
                    @Override
                    public void onConnectionInfoAvailable(WifiP2pInfo info) {
                        synchronized (mMyResponse) {
                            mMyResponse.valid = true;
                            mMyResponse.p2pInfo = new WifiP2pInfo(info);
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertNotNull(mMyResponse.p2pInfo);
        assertTrue(mMyResponse.p2pInfo.groupFormed);
        assertTrue(mMyResponse.p2pInfo.isGroupOwner);

        resetResponse(mMyResponse);
        mWifiP2pManager.requestGroupInfo(mWifiP2pChannel,
                new WifiP2pManager.GroupInfoListener() {
                    @Override
                    public void onGroupInfoAvailable(WifiP2pGroup group) {
                        synchronized (mMyResponse) {
                            mMyResponse.group = new WifiP2pGroup(group);
                            mMyResponse.valid = true;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertNotNull(mMyResponse.group);
        assertNotEquals(0, mMyResponse.group.getFrequency());
        assertTrue(mMyResponse.group.getNetworkId() >= 0);

        resetResponse(mMyResponse);
        mWifiP2pManager.removeGroup(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);
        assertTrue(waitForBroadcasts(MySync.NETWORK_INFO));
        assertNotNull(mMySync.expectedNetworkInfo);
        assertEquals(NetworkInfo.DetailedState.DISCONNECTED,
                mMySync.expectedNetworkInfo.getDetailedState());
    }

    private String getDeviceName() {
        resetResponse(mMyResponse);
        mWifiP2pManager.requestDeviceInfo(mWifiP2pChannel,
                new WifiP2pManager.DeviceInfoListener() {
                    @Override
                    public void onDeviceInfoAvailable(WifiP2pDevice wifiP2pDevice) {
                        synchronized (mMyResponse) {
                            mMyResponse.deviceName = wifiP2pDevice.deviceName;
                            mMyResponse.valid = true;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        return mMyResponse.deviceName;
    }

    public void testSetDeviceName() {
        if (!setupWifiP2p()) {
            return;
        }

        String testDeviceName = "test";
        String originalDeviceName = getDeviceName();
        assertNotNull(originalDeviceName);

        resetResponse(mMyResponse);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            mWifiP2pManager.setDeviceName(
                    mWifiP2pChannel, testDeviceName, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.success);
        });

        String currentDeviceName = getDeviceName();
        assertEquals(testDeviceName, currentDeviceName);

        // restore the device name at the end
        resetResponse(mMyResponse);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            mWifiP2pManager.setDeviceName(
                    mWifiP2pChannel, originalDeviceName, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.success);
        });
    }

    private WifiP2pGroupList getPersistentGroups() {
        resetResponse(mMyResponse);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            mWifiP2pManager.requestPersistentGroupInfo(mWifiP2pChannel,
                    new WifiP2pManager.PersistentGroupInfoListener() {
                        @Override
                        public void onPersistentGroupInfoAvailable(WifiP2pGroupList groups) {
                            synchronized (mMyResponse) {
                                mMyResponse.persistentGroups = groups;
                                mMyResponse.valid = true;
                                mMyResponse.notify();
                            }
                        }
                    });
            assertTrue(waitForServiceResponse(mMyResponse));
        });
        return mMyResponse.persistentGroups;
    }

    public void testPersistentGroupOperation() {
        if (!setupWifiP2p()) {
            return;
        }

        resetResponse(mMyResponse);
        mWifiP2pManager.createGroup(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);

        assertTrue(waitForConnectedNetworkState());

        resetResponse(mMyResponse);
        mWifiP2pManager.removeGroup(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);
        assertTrue(waitForBroadcasts(MySync.NETWORK_INFO));
        assertNotNull(mMySync.expectedNetworkInfo);
        assertEquals(NetworkInfo.DetailedState.DISCONNECTED,
                mMySync.expectedNetworkInfo.getDetailedState());

        WifiP2pGroupList persistentGroups = getPersistentGroups();
        assertNotNull(persistentGroups);
        assertEquals(1, persistentGroups.getGroupList().size());

        resetResponse(mMyResponse);
        final int firstNetworkId = persistentGroups.getGroupList().get(0).getNetworkId();
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            mWifiP2pManager.deletePersistentGroup(mWifiP2pChannel,
                    firstNetworkId,
                    mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.success);
        });

        persistentGroups = getPersistentGroups();
        assertNotNull(persistentGroups);
        assertEquals(0, persistentGroups.getGroupList().size());

        resetResponse(mMyResponse);
        mWifiP2pManager.createGroup(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);
        assertTrue(waitForConnectedNetworkState());

        resetResponse(mMyResponse);
        mWifiP2pManager.removeGroup(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);
        assertTrue(waitForBroadcasts(MySync.NETWORK_INFO));
        assertNotNull(mMySync.expectedNetworkInfo);
        assertEquals(NetworkInfo.DetailedState.DISCONNECTED,
                mMySync.expectedNetworkInfo.getDetailedState());

        resetResponse(mMyResponse);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            mWifiP2pManager.factoryReset(mWifiP2pChannel, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.success);
        });

        persistentGroups = getPersistentGroups();
        assertNotNull(persistentGroups);
        assertEquals(0, persistentGroups.getGroupList().size());
    }

    public void testP2pListening() {
        if (!setupWifiP2p()) {
            return;
        }

        resetResponse(mMyResponse);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            mWifiP2pManager.setWifiP2pChannels(mWifiP2pChannel, 6, 11, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.success);
        });

        resetResponse(mMyResponse);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            mWifiP2pManager.startListening(mWifiP2pChannel, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.success);
        });

        resetResponse(mMyResponse);
        mWifiP2pManager.stopListening(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);
    }

    public void testP2pService() {
        if (!setupWifiP2p()) {
            return;
        }

        // This only store the listener to the WifiP2pManager internal variable, nothing to fail.
        mWifiP2pManager.setServiceResponseListener(mWifiP2pChannel,
                new WifiP2pManager.ServiceResponseListener() {
                    @Override
                    public void onServiceAvailable(
                            int protocolType, byte[] responseData, WifiP2pDevice srcDevice) {
                    }
                });

        resetResponse(mMyResponse);
        List<String> services = new ArrayList<String>();
        services.add("urn:schemas-upnp-org:service:AVTransport:1");
        services.add("urn:schemas-upnp-org:service:ConnectionManager:1");
        WifiP2pServiceInfo rendererService = WifiP2pUpnpServiceInfo.newInstance(
                "6859dede-8574-59ab-9332-123456789011",
                "urn:schemas-upnp-org:device:MediaRenderer:1",
                services);
        mWifiP2pManager.addLocalService(mWifiP2pChannel,
                rendererService,
                mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);

        resetResponse(mMyResponse);
        mWifiP2pManager.removeLocalService(mWifiP2pChannel,
                rendererService,
                mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);

        resetResponse(mMyResponse);
        mWifiP2pManager.clearLocalServices(mWifiP2pChannel,
                mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public void testRemoveClient() {
        if (!setupWifiP2p()) {
            return;
        }

        if (!mWifiP2pManager.isGroupClientRemovalSupported()) return;

        resetResponse(mMyResponse);
        mWifiP2pManager.createGroup(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);

        assertTrue(waitForConnectedNetworkState());

        resetResponse(mMyResponse);
        MacAddress peerMacAddress = MacAddress.fromString(mTestWifiP2pPeerConfig.deviceAddress);
        mWifiP2pManager.removeClient(
                mWifiP2pChannel, peerMacAddress, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public void testDiscoverPeersOnSpecificFreq() {
        if (!setupWifiP2p()) {
            return;
        }

        if (!mWifiP2pManager.isChannelConstrainedDiscoverySupported()) return;

        resetResponse(mMyResponse);
        mWifiP2pManager.requestDiscoveryState(
                mWifiP2pChannel, new WifiP2pManager.DiscoveryStateListener() {
                    @Override
                    public void onDiscoveryStateAvailable(int state) {
                        synchronized (mMyResponse) {
                            mMyResponse.valid = true;
                            mMyResponse.discoveryState = state;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED, mMyResponse.discoveryState);

        // If there is any saved network and this device is connecting to this saved network,
        // p2p discovery might be blocked during DHCP provision.
        int retryCount = 3;
        while (retryCount > 0) {
            resetResponse(mMyResponse);
            mWifiP2pManager.discoverPeersOnSpecificFrequency(mWifiP2pChannel,
                    2412, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            if (mMyResponse.success
                    || mMyResponse.failureReason != WifiP2pManager.BUSY) {
                break;
            }
            Log.w(TAG, "Discovery is blocked, try again!");
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) { }
            retryCount--;
        }
        assertTrue(mMyResponse.success);
        assertTrue(waitForBroadcasts(MySync.DISCOVERY_STATE));

        resetResponse(mMyResponse);
        mWifiP2pManager.requestDiscoveryState(mWifiP2pChannel,
                new WifiP2pManager.DiscoveryStateListener() {
                    @Override
                    public void onDiscoveryStateAvailable(int state) {
                        synchronized (mMyResponse) {
                            mMyResponse.valid = true;
                            mMyResponse.discoveryState = state;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED, mMyResponse.discoveryState);

        mWifiP2pManager.stopPeerDiscovery(mWifiP2pChannel, null);
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public void testDiscoverPeersOnSocialChannelsOnly() {
        if (!setupWifiP2p()) {
            return;
        }

        if (!mWifiP2pManager.isChannelConstrainedDiscoverySupported()) return;

        resetResponse(mMyResponse);
        mWifiP2pManager.requestDiscoveryState(
                mWifiP2pChannel, new WifiP2pManager.DiscoveryStateListener() {
                    @Override
                    public void onDiscoveryStateAvailable(int state) {
                        synchronized (mMyResponse) {
                            mMyResponse.valid = true;
                            mMyResponse.discoveryState = state;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED, mMyResponse.discoveryState);

        // If there is any saved network and this device is connecting to this saved network,
        // p2p discovery might be blocked during DHCP provision.
        int retryCount = 3;
        while (retryCount > 0) {
            resetResponse(mMyResponse);
            mWifiP2pManager.discoverPeersOnSocialChannels(mWifiP2pChannel, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            if (mMyResponse.success
                    || mMyResponse.failureReason != WifiP2pManager.BUSY) {
                break;
            }
            Log.w(TAG, "Discovery is blocked, try again!");
            try {
                Thread.sleep(500);
            } catch (InterruptedException ex) { }
            retryCount--;
        }
        assertTrue(mMyResponse.success);
        assertTrue(waitForBroadcasts(MySync.DISCOVERY_STATE));

        resetResponse(mMyResponse);
        mWifiP2pManager.requestDiscoveryState(mWifiP2pChannel,
                new WifiP2pManager.DiscoveryStateListener() {
                    @Override
                    public void onDiscoveryStateAvailable(int state) {
                        synchronized (mMyResponse) {
                            mMyResponse.valid = true;
                            mMyResponse.discoveryState = state;
                            mMyResponse.notify();
                        }
                    }
                });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_DISCOVERY_STARTED, mMyResponse.discoveryState);

        mWifiP2pManager.stopPeerDiscovery(mWifiP2pChannel, null);
    }

    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public void testP2pConnectDoesNotThrowExceptionWhenGroupOwnerIpv6IsNotProvided() {
        if (!setupWifiP2p()) {
            return;
        }

        if (mWifiP2pManager.isGroupOwnerIPv6LinkLocalAddressProvided()) {
            return;
        }

        resetResponse(mMyResponse);
        WifiP2pConfig config = new WifiP2pConfig.Builder()
                .setDeviceAddress(MacAddress.fromString("aa:bb:cc:dd:ee:ff"))
                .setGroupClientIpProvisioningMode(
                        GROUP_CLIENT_IP_PROVISIONING_MODE_IPV6_LINK_LOCAL)
                .build();
        mWifiP2pManager.connect(mWifiP2pChannel, config, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertFalse(mMyResponse.success);
    }

    public void testP2pSetVendorElements() {
        if (!setupWifiP2p()) {
            return;
        }

        if (!mWifiP2pManager.isSetVendorElementsSupported()) return;

        // Vendor-Specific EID is 221.
        List<ScanResult.InformationElement> ies = new ArrayList<>(Arrays.asList(
                new ScanResult.InformationElement(221, 0,
                        new byte[]{(byte) 1, (byte) 2, (byte) 3, (byte) 4})));
        resetResponse(mMyResponse);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            mWifiP2pManager.setVendorElements(mWifiP2pChannel, ies, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.success);
        });

        resetResponse(mMyResponse);
        mWifiP2pManager.discoverPeers(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
    }

    /** Test IEs whose size is greater than the maximum allowed size. */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public void testP2pSetVendorElementsOverMaximumAllowedSize() {
        if (!setupWifiP2p()) {
            return;
        }

        if (!mWifiP2pManager.isSetVendorElementsSupported()) return;

        List<ScanResult.InformationElement> ies = new ArrayList<>();
        ies.add(new ScanResult.InformationElement(221, 0,
                new byte[WifiP2pManager.getP2pMaxAllowedVendorElementsLengthBytes() + 1]));
        resetResponse(mMyResponse);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            try {
                mWifiP2pManager.setVendorElements(mWifiP2pChannel, ies, mActionListener);
                fail("Should raise IllegalArgumentException");
            } catch (IllegalArgumentException ex) {
                // expected
                return;
            }
        });
    }

    /** Test that external approver APIs. */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.TIRAMISU)
    public void testP2pExternalApprover() {
        final MacAddress peer = MacAddress.fromString("11:22:33:44:55:66");
        if (!setupWifiP2p()) {
            return;
        }

        ExternalApproverRequestListener listener =
                new ExternalApproverRequestListener() {
                    @Override
                    public void onAttached(MacAddress deviceAddress) {
                        synchronized (mMyResponse) {
                            mMyResponse.targetPeer = deviceAddress;
                            mMyResponse.valid = true;
                            mMyResponse.isAttached = true;
                            mMyResponse.notify();
                        }
                    }
                    @Override
                    public void onDetached(MacAddress deviceAddress, int reason) {
                        synchronized (mMyResponse) {
                            mMyResponse.targetPeer = deviceAddress;
                            mMyResponse.detachReason = reason;
                            mMyResponse.valid = true;
                            mMyResponse.isDetached = true;
                            mMyResponse.notify();
                        }
                    }
                    @Override
                    public void onConnectionRequested(int requestType, WifiP2pConfig config,
                            WifiP2pDevice device) {
                    }
                    @Override
                    public void onPinGenerated(MacAddress deviceAddress, String pin) {
                    }
            };

        resetResponse(mMyResponse);

        UiAutomation uiAutomation = InstrumentationRegistry.getInstrumentation().getUiAutomation();
        try {
            uiAutomation.adoptShellPermissionIdentity();
            mWifiP2pManager.addExternalApprover(mWifiP2pChannel, peer, listener);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.isAttached);
            assertFalse(mMyResponse.isDetached);
            assertEquals(peer, mMyResponse.targetPeer);

            // Just ignore the result as there is no real incoming request.
            mWifiP2pManager.setConnectionRequestResult(mWifiP2pChannel, peer,
                    WifiP2pManager.CONNECTION_REQUEST_ACCEPT, null);
            mWifiP2pManager.setConnectionRequestResult(mWifiP2pChannel, peer,
                    WifiP2pManager.CONNECTION_REQUEST_ACCEPT, "12345678", null);

            resetResponse(mMyResponse);
            mWifiP2pManager.removeExternalApprover(mWifiP2pChannel, peer, null);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.isDetached);
            assertFalse(mMyResponse.isAttached);
            assertEquals(peer, mMyResponse.targetPeer);
            assertEquals(ExternalApproverRequestListener.APPROVER_DETACH_REASON_REMOVE,
                    mMyResponse.detachReason);
        } finally {
            uiAutomation.dropShellPermissionIdentity();
        }

    }

    /** Test setWfdInfo() API. */
    @SdkSuppress(minSdkVersion = Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
    public void testP2pSetWfdInfo() {
        if (!setupWifiP2p()) {
            return;
        }

        WifiP2pWfdInfo info = new WifiP2pWfdInfo();
        info.setEnabled(true);
        info.setDeviceType(WifiP2pWfdInfo.DEVICE_TYPE_WFD_SOURCE);
        info.setSessionAvailable(true);
        resetResponse(mMyResponse);
        ShellIdentityUtils.invokeWithShellPermissions(() -> {
            mWifiP2pManager.setWfdInfo(mWifiP2pChannel, info, mActionListener);
            assertTrue(waitForServiceResponse(mMyResponse));
            assertTrue(mMyResponse.success);
        });
    }

    /**
     * Tests {@link WifiP2pManager#getListenState(WifiP2pManager.Channel, Executor, Consumer)}
     */
    public void testGetListenState() {
        if (!setupWifiP2p()) {
            return;
        }

        Consumer<Integer> testListenStateListener = new Consumer<Integer>() {
            @Override
            public void accept(Integer state) {
                synchronized (mMyResponse) {
                    mMyResponse.valid = true;
                    mMyResponse.listenState = state.intValue();
                    mMyResponse.notify();
                }
            }
        };

        mWifiP2pManager.getListenState(mWifiP2pChannel, mExecutor, testListenStateListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_LISTEN_STOPPED, mMyResponse.listenState);

        resetResponse(mMyResponse);
        mWifiP2pManager.startListening(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(waitForBroadcasts(MySync.LISTEN_STATE));

        resetResponse(mMyResponse);
        mWifiP2pManager.getListenState(mWifiP2pChannel, mExecutor, testListenStateListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_LISTEN_STARTED, mMyResponse.listenState);

        resetResponse(mMyResponse);
        mWifiP2pManager.stopListening(mWifiP2pChannel, mActionListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(waitForBroadcasts(MySync.LISTEN_STATE));

        resetResponse(mMyResponse);
        mWifiP2pManager.getListenState(mWifiP2pChannel, mExecutor, testListenStateListener);
        assertTrue(waitForServiceResponse(mMyResponse));
        assertEquals(WifiP2pManager.WIFI_P2P_LISTEN_STOPPED, mMyResponse.listenState);
    }

    public void testWpsInfo() {
        WpsInfo info = new WpsInfo();
        assertEquals(WpsInfo.INVALID, info.setup);
        assertNull(info.BSSID);
        assertNull(info.pin);
        WpsInfo infoCopy = new WpsInfo(info);
        assertEquals(WpsInfo.INVALID, infoCopy.setup);
        assertNull(infoCopy.BSSID);
        assertNull(infoCopy.pin);
    }
}
