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

package com.android.cts.net.hostside;

import android.net.Network;

import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

public class NetworkCallbackTest extends AbstractRestrictBackgroundNetworkTestCase {

    private boolean mIsDataSaverSupported;
    private Network mNetwork;
    private final TestNetworkCallback mTestNetworkCallback = new TestNetworkCallback();

    enum CallbackState {
        NONE,
        AVAILABLE,
        LOST,
        BLOCKED_STATUS
    }

    private static class CallbackInfo {
        public final CallbackState state;
        public final Network network;
        public final Object arg;

        CallbackInfo(CallbackState s, Network n, Object o) {
            state = s; network = n; arg = o;
        }

        public String toString() {
            return String.format("%s (%s) (%s)", state, network, arg);
        }

        @Override
        public boolean equals(Object o) {
            if (!(o instanceof CallbackInfo)) return false;
            // Ignore timeMs, since it's unpredictable.
            final CallbackInfo other = (CallbackInfo) o;
            return (state == other.state) && Objects.equals(network, other.network)
                    && Objects.equals(arg, other.arg);
        }

        @Override
        public int hashCode() {
            return Objects.hash(state, network, arg);
        }
    }

    private class TestNetworkCallback extends INetworkCallback.Stub {
        private static final int TEST_CALLBACK_TIMEOUT_MS = 200;

        private final LinkedBlockingQueue<CallbackInfo> mCallbacks = new LinkedBlockingQueue<>();

        protected void setLastCallback(CallbackState state, Network network, Object o) {
            mCallbacks.offer(new CallbackInfo(state, network, o));
        }

        CallbackInfo nextCallback(int timeoutMs) {
            CallbackInfo cb = null;
            try {
                cb = mCallbacks.poll(timeoutMs, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
            }
            if (cb == null) {
                fail("Did not receive callback after " + timeoutMs + "ms");
            }
            return cb;
        }

        CallbackInfo expectCallback(CallbackState state, Network expectedNetwork, Object o) {
            final CallbackInfo expected = new CallbackInfo(state, expectedNetwork, o);
            final CallbackInfo actual = nextCallback(TEST_CALLBACK_TIMEOUT_MS);
            assertEquals("Unexpected callback:", expected, actual);
            return actual;
        }

        @Override
        public void onAvailable(Network network) {
            setLastCallback(CallbackState.AVAILABLE, network, null);
        }

        @Override
        public void onLost(Network network) {
            setLastCallback(CallbackState.LOST, network, null);
        }

        @Override
        public void onBlockedStatusChanged(Network network, boolean blocked) {
            setLastCallback(CallbackState.BLOCKED_STATUS, network, blocked);
        }

        public void expectLostCallback(Network expectedNetwork) {
            expectCallback(CallbackState.LOST, expectedNetwork, null);
        }

        public void expectAvailableCallback(Network expectedNetwork) {
            expectCallback(CallbackState.AVAILABLE, expectedNetwork, null);
        }

        public void expectBlockedStatusCallback(Network expectedNetwork, boolean expectBlocked) {
            expectCallback(CallbackState.BLOCKED_STATUS, expectedNetwork,
                    expectBlocked);
        }

        void assertNoCallback() {
            CallbackInfo cb = null;
            try {
                cb = mCallbacks.poll(TEST_CALLBACK_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (InterruptedException e) {
                // Expected.
            }
            if (cb != null) {
                assertNull("Unexpected callback: " + cb, cb);
            }
        }
    }

    @Override
    public void setUp() throws Exception {
        super.setUp();

        mIsDataSaverSupported = isDataSaverSupported();

        mNetwork = mCm.getActiveNetwork();

        // Set initial state.
        setBatterySaverMode(false);
        registerBroadcastReceiver();

        if (!mIsDataSaverSupported) return;
        setRestrictBackground(false);
        removeRestrictBackgroundWhitelist(mUid);
        removeRestrictBackgroundBlacklist(mUid);
        assertRestrictBackgroundChangedReceived(0);
    }

    @Override
    protected void tearDown() throws Exception {
        super.tearDown();

        if (!mIsDataSaverSupported) return;

        try {
            resetMeteredNetwork();
        } finally {
            setRestrictBackground(false);
        }
    }

    public void testOnBlockedStatusChanged_data_saver() throws Exception {
        if (!mIsDataSaverSupported) return;

        // Prepare metered wifi
        if (!setMeteredNetwork()) return;

        // Register callback
        registerNetworkCallback((INetworkCallback.Stub) mTestNetworkCallback);
        mTestNetworkCallback.expectAvailableCallback(mNetwork);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, false);

        // Enable restrict background
        setRestrictBackground(true);
        assertBackgroundNetworkAccess(false);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, true);

        // Add to whitelist
        addRestrictBackgroundWhitelist(mUid);
        assertBackgroundNetworkAccess(true);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, false);

        // Remove from whitelist
        removeRestrictBackgroundWhitelist(mUid);
        assertBackgroundNetworkAccess(false);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, true);

        // Set to non-metered network
        setUnmeteredNetwork();
        assertBackgroundNetworkAccess(true);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, false);

        // Disable restrict background, should not trigger callback
        setRestrictBackground(false);
        assertBackgroundNetworkAccess(true);
        mTestNetworkCallback.assertNoCallback();
    }


    public void testOnBlockedStatusChanged_power_saver() throws Exception {
        // Prepare metered wifi
        if (!setMeteredNetwork()) return;

        // Register callback
        registerNetworkCallback((INetworkCallback.Stub) mTestNetworkCallback);
        mTestNetworkCallback.expectAvailableCallback(mNetwork);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, false);

        // Enable Power Saver
        setBatterySaverMode(true);
        assertBackgroundNetworkAccess(false);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, true);

        // Disable Power Saver
        setBatterySaverMode(false);
        assertBackgroundNetworkAccess(true);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, false);

        // Set to non-metered network
        setUnmeteredNetwork();
        mTestNetworkCallback.assertNoCallback();

        // Enable Power Saver
        setBatterySaverMode(true);
        assertBackgroundNetworkAccess(false);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, true);

        // Disable Power Saver
        setBatterySaverMode(false);
        assertBackgroundNetworkAccess(true);
        mTestNetworkCallback.expectBlockedStatusCallback(mNetwork, false);
    }

    // TODO: 1. test against VPN lockdown.
    //       2. test against multiple networks.
}
