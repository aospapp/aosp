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

import static org.junit.Assert.assertNotEquals;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.ConnectivityManager.NetworkCallback;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.NetworkInfo;
import android.net.NetworkRequest;
import android.net.wifi.WifiManager;
import android.net.wifi.p2p.WifiP2pManager;
import android.provider.Settings;
import android.platform.test.annotations.AppModeFull;
import android.test.AndroidTestCase;
import android.util.Log;

import com.android.compatibility.common.util.SystemUtil;

import java.util.Arrays;
import java.util.BitSet;
import java.util.LinkedList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@AppModeFull(reason = "Cannot get WifiManager in instant app mode")
public class ConcurrencyTest extends AndroidTestCase {
    private class MySync {
        static final int WIFI_STATE = 0;
        static final int P2P_STATE = 1;
        static final int DISCOVERY_STATE = 2;
        static final int NETWORK_INFO = 3;

        public BitSet pendingSync = new BitSet();

        public int expectedWifiState;
        public int expectedP2pState;
        public int expectedDiscoveryState;
        public NetworkInfo expectedNetworkInfo;
    }

    private class MyResponse {
        public boolean valid = false;

        public boolean success;
        public int p2pState;
        public int discoveryState;
        public NetworkInfo networkInfo;
    }

    private WifiManager mWifiManager;
    private WifiP2pManager mWifiP2pManager;
    private WifiP2pManager.Channel mWifiP2pChannel;
    private MySync mMySync = new MySync();
    private MyResponse mMyResponse = new MyResponse();

    private static final String TAG = "ConcurrencyTest";
    private static final int TIMEOUT_MSEC = 6000;
    private static final int WAIT_MSEC = 60;
    private static final int DURATION = 10000;
    private IntentFilter mIntentFilter;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                synchronized (mMySync) {
                    mMySync.pendingSync.set(MySync.WIFI_STATE);
                    mMySync.expectedWifiState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                            WifiManager.WIFI_STATE_DISABLED);
                    mMySync.notify();
                }
            } else if(action.equals(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION)) {
                synchronized (mMySync) {
                    mMySync.pendingSync.set(MySync.P2P_STATE);
                    mMySync.expectedP2pState = intent.getIntExtra(WifiP2pManager.EXTRA_WIFI_STATE,
                            WifiP2pManager.WIFI_P2P_STATE_DISABLED);
                    mMySync.notify();
                }
            } else if (action.equals(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION)) {
                synchronized (mMySync) {
                    mMySync.pendingSync.set(MySync.DISCOVERY_STATE);
                    mMySync.expectedDiscoveryState = intent.getIntExtra(
                            WifiP2pManager.EXTRA_DISCOVERY_STATE,
                            WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED);
                    mMySync.notify();
                }
            } else if (action.equals(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION)) {
                synchronized (mMySync) {
                    mMySync.pendingSync.set(MySync.NETWORK_INFO);
                    mMySync.expectedNetworkInfo = (NetworkInfo) intent.getExtra(
                            WifiP2pManager.EXTRA_NETWORK_INFO, null);
                    mMySync.notify();
                }
            }
        }
    };

    @Override
    protected void setUp() throws Exception {
       super.setUp();
       if (!WifiFeature.isWifiSupported(getContext()) &&
                !WifiFeature.isP2pSupported(getContext())) {
            // skip the test if WiFi && p2p are not supported
            return;
        }
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_DISCOVERY_CHANGED_ACTION);
        mIntentFilter.addAction(WifiP2pManager.WIFI_P2P_CONNECTION_CHANGED_ACTION);

        mContext.registerReceiver(mReceiver, mIntentFilter);
        mWifiManager = (WifiManager) getContext().getSystemService(Context.WIFI_SERVICE);
        assertNotNull(mWifiManager);
        if (mWifiManager.isWifiEnabled()) {
            SystemUtil.runShellCommand("svc wifi disable");
            Thread.sleep(DURATION);
        }
        assertTrue(!mWifiManager.isWifiEnabled());
        mMySync.expectedWifiState = WifiManager.WIFI_STATE_DISABLED;
        mMySync.expectedP2pState = WifiP2pManager.WIFI_P2P_STATE_DISABLED;
        mMySync.expectedDiscoveryState = WifiP2pManager.WIFI_P2P_DISCOVERY_STOPPED;
        mMySync.expectedNetworkInfo = null;
    }

    @Override
    protected void tearDown() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext()) &&
                !WifiFeature.isP2pSupported(getContext())) {
            // skip the test if WiFi and p2p are not supported
            super.tearDown();
            return;
        }
        mContext.unregisterReceiver(mReceiver);

        enableWifi();
        super.tearDown();
    }

    private boolean waitForBroadcasts(List<Integer> waitSyncList) {
        synchronized (mMySync) {
            long timeout = System.currentTimeMillis() + TIMEOUT_MSEC;
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
                    mMySync.wait(WAIT_MSEC);
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

    private boolean waitForServiceResponse(MyResponse waitResponse) {
        synchronized (waitResponse) {
            long timeout = System.currentTimeMillis() + TIMEOUT_MSEC;
            while (System.currentTimeMillis() < timeout) {
                try {
                    waitResponse.wait(WAIT_MSEC);
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
            responseObj.valid = false;
            responseObj.networkInfo = null;
        }
    }

    /*
     * Enables Wifi and block until connection is established.
     */
    private void enableWifi() throws InterruptedException {
        if (!mWifiManager.isWifiEnabled()) {
            SystemUtil.runShellCommand("svc wifi enable");
        }

        ConnectivityManager cm =
            (ConnectivityManager) getContext().getSystemService(Context.CONNECTIVITY_SERVICE);
        NetworkRequest request =
            new NetworkRequest.Builder().addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                                        .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                                        .build();
        final CountDownLatch latch = new CountDownLatch(1);
        NetworkCallback networkCallback = new NetworkCallback() {
            @Override
            public void onAvailable(Network network) {
                latch.countDown();
            }
        };
        cm.registerNetworkCallback(request, networkCallback);
        latch.await(DURATION, TimeUnit.MILLISECONDS);

        cm.unregisterNetworkCallback(networkCallback);
    }

    private boolean setupWifiP2p() {
        // Cannot support p2p alone
        if (!WifiFeature.isWifiSupported(getContext())) {
            assertTrue(!WifiFeature.isP2pSupported(getContext()));
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

        long timeout = System.currentTimeMillis() + TIMEOUT_MSEC;
        while (!mWifiManager.isWifiEnabled() && System.currentTimeMillis() < timeout) {
            try {
                enableWifi();
            } catch (InterruptedException e) { }
        }

        assertTrue(mWifiManager.isWifiEnabled());

        assertTrue(waitForBroadcasts(
                new LinkedList<Integer>(
                Arrays.asList(MySync.WIFI_STATE, MySync.P2P_STATE))));

        assertEquals(WifiManager.WIFI_STATE_ENABLED, mMySync.expectedWifiState);
        assertEquals(WifiP2pManager.WIFI_P2P_STATE_ENABLED, mMySync.expectedP2pState);

        mWifiP2pManager =
                (WifiP2pManager) getContext().getSystemService(Context.WIFI_P2P_SERVICE);
        mWifiP2pChannel = mWifiP2pManager.initialize(
                getContext(), getContext().getMainLooper(), null);

        assertNotNull(mWifiP2pManager);
        assertNotNull(mWifiP2pChannel);

        assertTrue(waitForBroadcasts(MySync.NETWORK_INFO));
        // wait for changing to EnabledState
        assertNotNull(mMySync.expectedNetworkInfo);
        assertTrue(mMySync.expectedNetworkInfo.isAvailable());

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

        resetResponse(mMyResponse);
        mWifiP2pManager.discoverPeers(mWifiP2pChannel, new WifiP2pManager.ActionListener() {
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
                    Log.d(TAG, "discoveryPeers failure reason: " + reason);
                    mMyResponse.valid = true;
                    mMyResponse.success = false;
                    mMyResponse.notify();
                }
            }
        });
        assertTrue(waitForServiceResponse(mMyResponse));
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
        mWifiP2pManager.createGroup(mWifiP2pChannel, new WifiP2pManager.ActionListener() {
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
                    Log.d(TAG, "createGroup failure reason: " + reason);
                    mMyResponse.valid = true;
                    mMyResponse.success = false;
                    mMyResponse.notify();
                }
            }
        });
        assertTrue(waitForServiceResponse(mMyResponse));
        assertTrue(mMyResponse.success);
        assertTrue(waitForBroadcasts(MySync.NETWORK_INFO));
        assertNotNull(mMySync.expectedNetworkInfo);
        assertEquals(NetworkInfo.DetailedState.CONNECTED,
                mMySync.expectedNetworkInfo.getDetailedState());

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

        mWifiP2pManager.removeGroup(mWifiP2pChannel, null);
    }

}
