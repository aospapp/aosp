/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.cts.verifier.wifi.testcase;

import static android.net.NetworkCapabilities.TRANSPORT_WIFI;
import static android.net.wifi.WifiManager.STATUS_NETWORK_SUGGESTIONS_SUCCESS;

import android.annotation.NonNull;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.ConnectivityManager;
import android.net.MacAddress;
import android.net.Network;
import android.net.NetworkRequest;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiNetworkSuggestion;
import android.util.Log;
import android.util.Pair;

import com.android.cts.verifier.R;
import com.android.cts.verifier.wifi.BaseTestCase;
import com.android.cts.verifier.wifi.CallbackUtils;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Test cases for network suggestions {@link WifiNetworkSuggestion} added via
 * {@link WifiManager#addNetworkSuggestions(List)}.
 */
public class NetworkSuggestionTestCase extends BaseTestCase {
    private static final String TAG = "NetworkSuggestionTestCase";
    private static final boolean DBG = true;

    private static final int PERIODIC_SCAN_INTERVAL_MS = 10_000;
    private static final int CALLBACK_TIMEOUT_MS = 40_000;

    private final Object mLock = new Object();
    private final ScheduledExecutorService mExecutorService;
    private final List<WifiNetworkSuggestion> mNetworkSuggestions = new ArrayList<>();

    private ConnectivityManager mConnectivityManager;
    private NetworkRequest mNetworkRequest;
    private CallbackUtils.NetworkCallback mNetworkCallback;
    private BroadcastReceiver mBroadcastReceiver;
    private String mFailureReason;

    private final boolean mSetBssid;
    private final boolean mSetRequiresAppInteraction;

    public NetworkSuggestionTestCase(Context context, boolean setBssid,
                                     boolean setRequiresAppInteraction) {
        super(context);
        mExecutorService = Executors.newSingleThreadScheduledExecutor();
        mSetBssid = setBssid;
        mSetRequiresAppInteraction = setRequiresAppInteraction;
    }

    // Create a network specifier based on the test type.
    private WifiNetworkSuggestion createNetworkSuggestion(@NonNull ScanResult scanResult) {
        WifiNetworkSuggestion.Builder builder = new WifiNetworkSuggestion.Builder();
        builder.setSsid(scanResult.SSID);
        if (mSetBssid) {
            builder.setBssid(MacAddress.fromString(scanResult.BSSID));
        }
        if (mSetRequiresAppInteraction) {
            builder.setIsAppInteractionRequired(true);
        }
        return builder.build();
    }

    private void setFailureReason(String reason) {
        synchronized (mLock) {
            mFailureReason = reason;
        }
    }

    @Override
    protected boolean executeTest() throws InterruptedException {
        // Step 1: Scan and find any open network around.
        if (DBG) Log.v(TAG, "Scan and find an open network");
        ScanResult openNetwork = mTestUtils.startScanAndFindAnyOpenNetworkInResults();
        if (openNetwork == null) {
            setFailureReason(mContext.getString(R.string.wifi_status_scan_failure));
            return false;
        }

        // Step 1.a (Optional): Register for the post connection broadcast.
        final CountDownLatch countDownLatchForPostConnectionBcast = new CountDownLatch(1);
        if (mSetRequiresAppInteraction) {
            IntentFilter intentFilter =
                    new IntentFilter(WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION);
            // Post connection broadcast receiver.
            mBroadcastReceiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context context, Intent intent) {
                    if (DBG) Log.v(TAG, "Broadcast onReceive " + intent);
                    if (!intent.getAction().equals(
                            WifiManager.ACTION_WIFI_NETWORK_SUGGESTION_POST_CONNECTION)) {
                        return;
                    }
                    if (DBG) Log.v(TAG, "Post connection broadcast received");
                    countDownLatchForPostConnectionBcast.countDown();
                }
            };
            // Register the receiver for post connection broadcast.
            mContext.registerReceiver(mBroadcastReceiver, intentFilter);
        }

        // Step 1.b: Register network callback to wait for connection state.
        mNetworkRequest = new NetworkRequest.Builder()
                .addTransportType(TRANSPORT_WIFI)
                .build();
        mNetworkCallback = new CallbackUtils.NetworkCallback(CALLBACK_TIMEOUT_MS);
        mConnectivityManager.registerNetworkCallback(mNetworkRequest, mNetworkCallback);

        // Step 2: Create a suggestion for the chosen open network depending on the type of test.
        WifiNetworkSuggestion networkSuggestion = createNetworkSuggestion(openNetwork);
        mNetworkSuggestions.add(networkSuggestion);

        // Step 4: Add a network suggestions.
        if (DBG) Log.v(TAG, "Adding suggestion");
        mListener.onTestMsgReceived(mContext.getString(R.string.wifi_status_suggestion_add));
        if (mWifiManager.addNetworkSuggestions(mNetworkSuggestions)
                != STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            setFailureReason(mContext.getString(R.string.wifi_status_suggestion_add_failure));
            return false;
        }

        // Step 5: Trigger scans periodically to trigger network selection quicker.
        if (DBG) Log.v(TAG, "Triggering scan periodically");
        mExecutorService.scheduleAtFixedRate(() -> {
            if (!mWifiManager.startScan()) {
                Log.w(TAG, "Failed to trigger scan");
            }
        }, 0, PERIODIC_SCAN_INTERVAL_MS, TimeUnit.MILLISECONDS);

        // Step 6: Wait for connection.
        if (DBG) Log.v(TAG, "Waiting for connection");
        mListener.onTestMsgReceived(mContext.getString(
                R.string.wifi_status_suggestion_wait_for_connect));
        Pair<Boolean, Network> cbStatusForAvailable = mNetworkCallback.waitForAvailable();
        if (!cbStatusForAvailable.first) {
            Log.e(TAG, "Failed to get network available callback");
            setFailureReason(mContext.getString(R.string.wifi_status_network_cb_timeout));
            return false;
        }
        mListener.onTestMsgReceived(
                mContext.getString(R.string.wifi_status_suggestion_connect));

        // Step 7: Ensure that we connected to the suggested network (optionally, the correct
        // BSSID).
        if (!mTestUtils.isConnected("\"" + openNetwork.SSID + "\"",
                // TODO: This might fail if there are other BSSID's for the same network & the
                //  device decided to connect/roam to a different BSSID. We don't turn off roaming
                //  for suggestions.
                mSetBssid ? openNetwork.BSSID : null)) {
            Log.e(TAG, "Failed to connected to a wrong network");
            setFailureReason(mContext.getString(R.string.wifi_status_connected_to_other_network));
            return false;
        }

        if (mSetRequiresAppInteraction) {
            // Step 7 (Optional): Ensure we received the post connect broadcast.
            if (DBG) Log.v(TAG, "Wait for post connection broadcast");
            mListener.onTestMsgReceived(
                    mContext.getString(
                            R.string.wifi_status_suggestion_wait_for_post_connect_bcast));
            if (!countDownLatchForPostConnectionBcast.await(
                    CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                Log.e(TAG, "Failed to get post connection broadcast");
                setFailureReason(mContext.getString(
                        R.string.wifi_status_suggestion_post_connect_bcast_failure));
                return false;
            }
            mListener.onTestMsgReceived(
                    mContext.getString(R.string.wifi_status_suggestion_post_connect_bcast));
        }

        // Step 8: Remove the suggestions from the app.
        if (DBG) Log.v(TAG, "Removing suggestion");
        mListener.onTestMsgReceived(mContext.getString(R.string.wifi_status_suggestion_remove));
        if (mWifiManager.removeNetworkSuggestions(mNetworkSuggestions)
                != STATUS_NETWORK_SUGGESTIONS_SUCCESS) {
            setFailureReason(mContext.getString(R.string.wifi_status_suggestion_remove_failure));
            return false;
        }

        // Step 9: Ensure we don't disconnect immediately on suggestion removal.
        mListener.onTestMsgReceived(
                mContext.getString(R.string.wifi_status_suggestion_wait_for_disconnect));
        if (DBG) Log.v(TAG, "Ensuring we don't disconnect immediately");
        boolean cbStatusForLost = mNetworkCallback.waitForLost();
        if (cbStatusForLost) {
            Log.e(TAG, "Disconnected from the network immediately");
            setFailureReason(mContext.getString(R.string.wifi_status_suggestion_disconnected));
            return false;
        }

        // All done!
        return true;
    }

    @Override
    protected String getFailureReason() {
        synchronized (mLock) {
            return mFailureReason;
        }
    }

    @Override
    protected void setUp() {
        super.setUp();
        mConnectivityManager = ConnectivityManager.from(mContext);
    }

    @Override
    protected void tearDown() {
        mExecutorService.shutdownNow();
        if (mBroadcastReceiver != null) {
            mContext.unregisterReceiver(mBroadcastReceiver);
        }
        mWifiManager.removeNetworkSuggestions(new ArrayList<>());
        super.tearDown();
    }
}
