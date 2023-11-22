/*
 * Copyright (C) 2009 The Android Open Source Project
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


import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.TxPacketCountListener;
import android.net.wifi.WifiManager.WifiLock;
import android.net.wifi.hotspot2.PasspointConfiguration;
import android.net.wifi.hotspot2.pps.Credential;
import android.net.wifi.hotspot2.pps.HomeSp;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.platform.test.annotations.AppModeFull;
import android.provider.Settings;
import android.support.test.uiautomator.UiDevice;
import android.test.AndroidTestCase;
import android.text.TextUtils;
import android.util.ArraySet;
import android.util.Log;

import androidx.test.InstrumentationRegistry;

import com.android.compatibility.common.util.SystemUtil;

import java.net.HttpURLConnection;
import java.net.URL;
import java.security.MessageDigest;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

@AppModeFull(reason = "Cannot get WifiManager in instant app mode")
public class WifiManagerTest extends AndroidTestCase {
    private static class MySync {
        int expectedState = STATE_NULL;
    }

    private WifiManager mWifiManager;
    private WifiLock mWifiLock;
    private static MySync mMySync;
    private List<ScanResult> mScanResults = null;
    private NetworkInfo mNetworkInfo;
    private Object mLOHSLock = new Object();
    private UiDevice mUiDevice;

    // Please refer to WifiManager
    private static final int MIN_RSSI = -100;
    private static final int MAX_RSSI = -55;

    private static final int STATE_NULL = 0;
    private static final int STATE_WIFI_CHANGING = 1;
    private static final int STATE_WIFI_ENABLED = 2;
    private static final int STATE_WIFI_DISABLED = 3;
    private static final int STATE_SCANNING = 4;
    private static final int STATE_SCAN_DONE = 5;

    private static final String TAG = "WifiManagerTest";
    private static final String SSID1 = "\"WifiManagerTest\"";
    // A full single scan duration is about 6-7 seconds if country code is set
    // to US. If country code is set to world mode (00), we would expect a scan
    // duration of roughly 8 seconds. So we set scan timeout as 9 seconds here.
    private static final int SCAN_TIMEOUT_MSEC = 9000;
    private static final int TIMEOUT_MSEC = 6000;
    private static final int WAIT_MSEC = 60;
    private static final int DURATION = 10000;
    private static final int DURATION_SCREEN_TOGGLE = 2000;
    private static final int WIFI_SCAN_TEST_INTERVAL_MILLIS = 60 * 1000;
    private static final int WIFI_SCAN_TEST_CACHE_DELAY_MILLIS = 3 * 60 * 1000;
    private static final int WIFI_SCAN_TEST_ITERATIONS = 5;

    private static final int ENFORCED_NUM_NETWORK_SUGGESTIONS_PER_APP = 50;

    private static final String TEST_PAC_URL = "http://www.example.com/proxy.pac";
    private static final String MANAGED_PROVISIONING_PACKAGE_NAME
            = "com.android.managedprovisioning";

    private IntentFilter mIntentFilter;
    private final BroadcastReceiver mReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (action.equals(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION)) {

                synchronized (mMySync) {
                    if (intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false)) {
                        mScanResults = mWifiManager.getScanResults();
                    } else {
                        mScanResults = null;
                    }
                    mMySync.expectedState = STATE_SCAN_DONE;
                    mMySync.notifyAll();
                }
            } else if (action.equals(WifiManager.WIFI_STATE_CHANGED_ACTION)) {
                int newState = intent.getIntExtra(WifiManager.EXTRA_WIFI_STATE,
                        WifiManager.WIFI_STATE_UNKNOWN);
                synchronized (mMySync) {
                    if (newState == WifiManager.WIFI_STATE_ENABLED) {
                        Log.d(TAG, "*** New WiFi state is ENABLED ***");
                        mMySync.expectedState = STATE_WIFI_ENABLED;
                        mMySync.notifyAll();
                    } else if (newState == WifiManager.WIFI_STATE_DISABLED) {
                        Log.d(TAG, "*** New WiFi state is DISABLED ***");
                        mMySync.expectedState = STATE_WIFI_DISABLED;
                        mMySync.notifyAll();
                    }
                }
            } else if (action.equals(WifiManager.NETWORK_STATE_CHANGED_ACTION)) {
                synchronized (mMySync) {
                    mNetworkInfo =
                            (NetworkInfo) intent.getParcelableExtra(WifiManager.EXTRA_NETWORK_INFO);
                    if (mNetworkInfo.getState() == NetworkInfo.State.CONNECTED)
                        mMySync.notifyAll();
                }
            }
        }
    };

    @Override
    protected void setUp() throws Exception {
        super.setUp();
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        mMySync = new MySync();
        mIntentFilter = new IntentFilter();
        mIntentFilter.addAction(WifiManager.NETWORK_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        mIntentFilter.addAction(WifiManager.SUPPLICANT_CONNECTION_CHANGE_ACTION);
        mIntentFilter.addAction(WifiManager.SUPPLICANT_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.WIFI_STATE_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.RSSI_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.NETWORK_IDS_CHANGED_ACTION);
        mIntentFilter.addAction(WifiManager.ACTION_PICK_WIFI_NETWORK);

        mContext.registerReceiver(mReceiver, mIntentFilter);
        mWifiManager = (WifiManager) getContext().getSystemService(Context.WIFI_SERVICE);
        assertNotNull(mWifiManager);
        mWifiLock = mWifiManager.createWifiLock(TAG);
        mWifiLock.acquire();
        if (!mWifiManager.isWifiEnabled())
            setWifiEnabled(true);
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        turnScreenOnNoDelay();
        Thread.sleep(DURATION);
        assertTrue(mWifiManager.isWifiEnabled());
        synchronized (mMySync) {
            mMySync.expectedState = STATE_NULL;
        }
    }

    @Override
    protected void tearDown() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            super.tearDown();
            return;
        }
        if (!mWifiManager.isWifiEnabled())
            setWifiEnabled(true);
        mWifiLock.release();
        mContext.unregisterReceiver(mReceiver);
        Thread.sleep(DURATION);
        super.tearDown();
    }

    private void setWifiEnabled(boolean enable) throws Exception {
        synchronized (mMySync) {
            if (mWifiManager.isWifiEnabled() != enable) {
                // the new state is different, we expect it to change
                mMySync.expectedState = STATE_WIFI_CHANGING;
            } else {
                mMySync.expectedState = (enable ? STATE_WIFI_ENABLED : STATE_WIFI_DISABLED);
            }
            // now trigger the change using shell commands.
            SystemUtil.runShellCommand("svc wifi " + (enable ? "enable" : "disable"));
            waitForExpectedWifiState(enable);
        }
    }

    private void waitForExpectedWifiState(boolean enabled) throws InterruptedException {
        synchronized (mMySync) {
            long timeout = System.currentTimeMillis() + TIMEOUT_MSEC;
            int expected = (enabled ? STATE_WIFI_ENABLED : STATE_WIFI_DISABLED);
            while (System.currentTimeMillis() < timeout
                    && mMySync.expectedState != expected) {
                mMySync.wait(WAIT_MSEC);
            }
        }
    }

    // Get the current scan status from sticky broadcast.
    private boolean isScanCurrentlyAvailable() {
        boolean isAvailable = false;
        IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(WifiManager.WIFI_SCAN_AVAILABLE);
        Intent intent = mContext.registerReceiver(null, intentFilter);
        assertNotNull(intent);
        if (intent.getAction().equals(WifiManager.WIFI_SCAN_AVAILABLE)) {
            int state = intent.getIntExtra(
                    WifiManager.EXTRA_SCAN_AVAILABLE, WifiManager.WIFI_STATE_UNKNOWN);
            if (state == WifiManager.WIFI_STATE_ENABLED) {
                isAvailable = true;
            } else if (state == WifiManager.WIFI_STATE_DISABLED) {
                isAvailable = false;
            }
        }
        return isAvailable;
    }

    private void startScan() throws Exception {
        synchronized (mMySync) {
            mMySync.expectedState = STATE_SCANNING;
            mScanResults = null;
            assertTrue(mWifiManager.startScan());
            long timeout = System.currentTimeMillis() + SCAN_TIMEOUT_MSEC;
            while (System.currentTimeMillis() < timeout && mMySync.expectedState == STATE_SCANNING)
                mMySync.wait(WAIT_MSEC);
        }
    }

    private void connectWifi() throws Exception {
        synchronized (mMySync) {
            if (mNetworkInfo.getState() == NetworkInfo.State.CONNECTED) return;
            long timeout = System.currentTimeMillis() + TIMEOUT_MSEC;
            while (System.currentTimeMillis() < timeout
                    && mNetworkInfo.getState() != NetworkInfo.State.CONNECTED)
                mMySync.wait(WAIT_MSEC);
            assertTrue(mNetworkInfo.getState() == NetworkInfo.State.CONNECTED);
        }
    }

    private boolean existSSID(String ssid) {
        for (final WifiConfiguration w : mWifiManager.getConfiguredNetworks()) {
            if (w.SSID.equals(ssid))
                return true;
        }
        return false;
    }

    private int findConfiguredNetworks(String SSID, List<WifiConfiguration> networks) {
        for (final WifiConfiguration w : networks) {
            if (w.SSID.equals(SSID))
                return networks.indexOf(w);
        }
        return -1;
    }

    /**
     * Test creation of WifiManager Lock.
     */
    public void testWifiManagerLock() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        final String TAG = "Test";
        assertNotNull(mWifiManager.createWifiLock(TAG));
        assertNotNull(mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG));
    }

    /**
     * Test wifi scanning when location scan is turned off.
     */
    public void testWifiManagerScanWhenWifiOffLocationTurnedOn() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        if (!hasLocationFeature()) {
            Log.d(TAG, "Skipping test as location is not supported");
            return;
        }
        if (!isLocationEnabled()) {
            fail("Please enable location for this test - since Marshmallow WiFi scan results are"
                    + " empty when location is disabled!");
        }
        setWifiEnabled(false);
        Thread.sleep(DURATION);
        startScan();
        if (mWifiManager.isScanAlwaysAvailable() && isScanCurrentlyAvailable()) {
            // Make sure at least one AP is found.
            assertNotNull("mScanResult should not be null!", mScanResults);
            assertFalse("empty scan results!", mScanResults.isEmpty());
        } else {
            // Make sure no scan results are available.
            assertNull("mScanResult should be null!", mScanResults);
        }
        final String TAG = "Test";
        assertNotNull(mWifiManager.createWifiLock(TAG));
        assertNotNull(mWifiManager.createWifiLock(WifiManager.WIFI_MODE_FULL, TAG));
    }

    /**
     * test point of wifiManager properties:
     * 1.enable properties
     * 2.DhcpInfo properties
     * 3.wifi state
     * 4.ConnectionInfo
     */
    public void testWifiManagerProperties() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        setWifiEnabled(true);
        assertTrue(mWifiManager.isWifiEnabled());
        assertNotNull(mWifiManager.getDhcpInfo());
        assertEquals(WifiManager.WIFI_STATE_ENABLED, mWifiManager.getWifiState());
        mWifiManager.getConnectionInfo();
        setWifiEnabled(false);
        assertFalse(mWifiManager.isWifiEnabled());
    }

    /**
     * Test WiFi scan timestamp - fails when WiFi scan timestamps are inconsistent with
     * {@link SystemClock#elapsedRealtime()} on device.<p>
     * To run this test in cts-tradefed:
     * run cts --class android.net.wifi.cts.WifiManagerTest --method testWifiScanTimestamp
     */
    public void testWifiScanTimestamp() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            Log.d(TAG, "Skipping test as WiFi is not supported");
            return;
        }
        if (!hasLocationFeature()) {
            Log.d(TAG, "Skipping test as location is not supported");
            return;
        }
        if (!isLocationEnabled()) {
            fail("Please enable location for this test - since Marshmallow WiFi scan results are"
                    + " empty when location is disabled!");
        }
        if (!mWifiManager.isWifiEnabled()) {
            setWifiEnabled(true);
        }
        // Scan multiple times to make sure scan timestamps increase with device timestamp.
        for (int i = 0; i < WIFI_SCAN_TEST_ITERATIONS; ++i) {
            startScan();
            // Make sure at least one AP is found.
            assertTrue("mScanResult should not be null. This may be due to a scan timeout",
                       mScanResults != null);
            assertFalse("empty scan results!", mScanResults.isEmpty());
            long nowMillis = SystemClock.elapsedRealtime();
            // Keep track of how many APs are fresh in one scan.
            int numFreshAps = 0;
            for (ScanResult result : mScanResults) {
                long scanTimeMillis = TimeUnit.MICROSECONDS.toMillis(result.timestamp);
                if (Math.abs(nowMillis - scanTimeMillis)  < WIFI_SCAN_TEST_CACHE_DELAY_MILLIS) {
                    numFreshAps++;
                }
            }
            // At least half of the APs in the scan should be fresh.
            int numTotalAps = mScanResults.size();
            String msg = "Stale AP count: " + (numTotalAps - numFreshAps) + ", fresh AP count: "
                    + numFreshAps;
            assertTrue(msg, numFreshAps * 2 >= mScanResults.size());
            if (i < WIFI_SCAN_TEST_ITERATIONS - 1) {
                // Wait before running next iteration.
                Thread.sleep(WIFI_SCAN_TEST_INTERVAL_MILLIS);
            }
        }
    }

    // Return true if location is enabled.
    private boolean isLocationEnabled() {
        return Settings.Secure.getInt(getContext().getContentResolver(),
                Settings.Secure.LOCATION_MODE, Settings.Secure.LOCATION_MODE_OFF) !=
                Settings.Secure.LOCATION_MODE_OFF;
    }

    // Returns true if the device has location feature.
    private boolean hasLocationFeature() {
        return getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_LOCATION);
    }

    private boolean hasAutomotiveFeature() {
        return getContext().getPackageManager().hasSystemFeature(PackageManager.FEATURE_AUTOMOTIVE);
    }

    public void testSignal() {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        final int numLevels = 9;
        int expectLevel = 0;
        assertEquals(expectLevel, WifiManager.calculateSignalLevel(MIN_RSSI, numLevels));
        assertEquals(numLevels - 1, WifiManager.calculateSignalLevel(MAX_RSSI, numLevels));
        expectLevel = 4;
        assertEquals(expectLevel, WifiManager.calculateSignalLevel((MIN_RSSI + MAX_RSSI) / 2,
                numLevels));
        int rssiA = 4;
        int rssiB = 5;
        assertTrue(WifiManager.compareSignalLevel(rssiA, rssiB) < 0);
        rssiB = 4;
        assertTrue(WifiManager.compareSignalLevel(rssiA, rssiB) == 0);
        rssiA = 5;
        rssiB = 4;
        assertTrue(WifiManager.compareSignalLevel(rssiA, rssiB) > 0);
    }

    private int getTxPacketCount() throws Exception {
        final AtomicInteger ret = new AtomicInteger(-1);

        mWifiManager.getTxPacketCount(new TxPacketCountListener() {
            @Override
            public void onSuccess(int count) {
                ret.set(count);
            }
            @Override
            public void onFailure(int reason) {
                ret.set(0);
            }
        });

        long timeout = System.currentTimeMillis() + TIMEOUT_MSEC;
        while (ret.get() < 0 && System.currentTimeMillis() < timeout)
            Thread.sleep(WAIT_MSEC);
        assertTrue(ret.get() >= 0);
        return ret.get();
    }

    /**
     * The new WiFi watchdog requires kernel/driver to export some packet loss
     * counters. This CTS tests whether those counters are correctly exported.
     * To pass this CTS test, a connected WiFi link is required.
     */
    public void testWifiWatchdog() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        // Make sure WiFi is enabled
        if (!mWifiManager.isWifiEnabled()) {
            setWifiEnabled(true);
            Thread.sleep(DURATION);
        }
        assertTrue(mWifiManager.isWifiEnabled());

        // give the test a chance to autoconnect
        Thread.sleep(DURATION);
        if (mNetworkInfo.getState() != NetworkInfo.State.CONNECTED) {
            // this test requires a connectable network be configured
            fail("This test requires a wifi network connection.");
        }

        // This will generate a distinct stack trace if the initial connection fails.
        connectWifi();

        int i = 0;
        for (; i < 15; i++) {
            // Wait for a WiFi connection
            connectWifi();

            // Read TX packet counter
            int txcount1 = getTxPacketCount();

            // Do some network operations
            HttpURLConnection connection = null;
            try {
                URL url = new URL("http://www.google.com/");
                connection = (HttpURLConnection) url.openConnection();
                connection.setInstanceFollowRedirects(false);
                connection.setConnectTimeout(TIMEOUT_MSEC);
                connection.setReadTimeout(TIMEOUT_MSEC);
                connection.setUseCaches(false);
                connection.getInputStream();
            } catch (Exception e) {
                // ignore
            } finally {
                if (connection != null) connection.disconnect();
            }

            // Read TX packet counter again and make sure it increases
            int txcount2 = getTxPacketCount();

            if (txcount2 > txcount1) {
                break;
            } else {
                Thread.sleep(DURATION);
            }
        }
        assertTrue(i < 15);
    }

    public class TestLocalOnlyHotspotCallback extends WifiManager.LocalOnlyHotspotCallback {
        Object hotspotLock;
        WifiManager.LocalOnlyHotspotReservation reservation = null;
        boolean onStartedCalled = false;
        boolean onStoppedCalled = false;
        boolean onFailedCalled = false;
        int failureReason = -1;

        TestLocalOnlyHotspotCallback(Object lock) {
            hotspotLock = lock;
        }

        @Override
        public void onStarted(WifiManager.LocalOnlyHotspotReservation r) {
            synchronized (hotspotLock) {
                reservation = r;
                onStartedCalled = true;
                hotspotLock.notify();
            }
        }

        @Override
        public void onStopped() {
            synchronized (hotspotLock) {
                onStoppedCalled = true;
                hotspotLock.notify();
            }
        }

        @Override
        public void onFailed(int reason) {
            synchronized (hotspotLock) {
                onFailedCalled = true;
                failureReason = reason;
                hotspotLock.notify();
            }
        }
    }

    private TestLocalOnlyHotspotCallback startLocalOnlyHotspot() {
        // Location mode must be enabled for this test
        if (!isLocationEnabled()) {
            fail("Please enable location for this test");
        }

        TestLocalOnlyHotspotCallback callback = new TestLocalOnlyHotspotCallback(mLOHSLock);
        synchronized (mLOHSLock) {
            try {
                mWifiManager.startLocalOnlyHotspot(callback, null);
                // now wait for callback
                mLOHSLock.wait(DURATION);
            } catch (InterruptedException e) {
            }
            // check if we got the callback
            assertTrue(callback.onStartedCalled);
            assertNotNull(callback.reservation.getWifiConfiguration());
            if (!hasAutomotiveFeature()) {
                assertEquals(
                        WifiConfiguration.AP_BAND_2GHZ,
                        callback.reservation.getWifiConfiguration().apBand);
            }
            assertFalse(callback.onFailedCalled);
            assertFalse(callback.onStoppedCalled);
        }
        return callback;
    }

    private void stopLocalOnlyHotspot(TestLocalOnlyHotspotCallback callback, boolean wifiEnabled) {
       synchronized (mMySync) {
           // we are expecting a new state
           mMySync.expectedState = STATE_WIFI_CHANGING;

           // now shut down LocalOnlyHotspot
           callback.reservation.close();

           try {
               waitForExpectedWifiState(wifiEnabled);
           } catch (InterruptedException e) {}
        }
    }

    /**
     * Verify that calls to startLocalOnlyHotspot succeed with proper permissions.
     *
     * Note: Location mode must be enabled for this test.
     */
    public void testStartLocalOnlyHotspotSuccess() {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        // check that softap mode is supported by the device
        if (!mWifiManager.isPortableHotspotSupported()) {
            return;
        }

        boolean wifiEnabled = mWifiManager.isWifiEnabled();

        TestLocalOnlyHotspotCallback callback = startLocalOnlyHotspot();

        // add sleep to avoid calling stopLocalOnlyHotspot before TetherController initialization.
        // TODO: remove this sleep as soon as b/124330089 is fixed.
        try {
            Log.d(TAG, "Sleep for 2 seconds");
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Log.d(TAG, "Thread InterruptedException!");
        }

        stopLocalOnlyHotspot(callback, wifiEnabled);

        // wifi should either stay on, or come back on
        assertEquals(wifiEnabled, mWifiManager.isWifiEnabled());
    }

    /**
     * Verify calls to deprecated API's all fail for non-settings apps targeting >= Q SDK.
     */
    public void testDeprecatedApis() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        setWifiEnabled(true);
        connectWifi(); // ensures that there is at-least 1 saved network on the device.

        WifiConfiguration wifiConfiguration = new WifiConfiguration();
        wifiConfiguration.SSID = SSID1;
        wifiConfiguration.allowedKeyManagement.set(WifiConfiguration.KeyMgmt.NONE);

        assertEquals(WifiConfiguration.INVALID_NETWORK_ID,
                mWifiManager.addNetwork(wifiConfiguration));
        assertEquals(WifiConfiguration.INVALID_NETWORK_ID,
                mWifiManager.updateNetwork(wifiConfiguration));
        assertFalse(mWifiManager.enableNetwork(0, true));
        assertFalse(mWifiManager.disableNetwork(0));
        assertFalse(mWifiManager.removeNetwork(0));
        assertFalse(mWifiManager.disconnect());
        assertFalse(mWifiManager.reconnect());
        assertFalse(mWifiManager.reassociate());
        assertTrue(mWifiManager.getConfiguredNetworks().isEmpty());

        boolean wifiEnabled = mWifiManager.isWifiEnabled();
        // now we should fail to toggle wifi state.
        assertFalse(mWifiManager.setWifiEnabled(!wifiEnabled));
        Thread.sleep(DURATION);
        assertEquals(wifiEnabled, mWifiManager.isWifiEnabled());
    }

    /**
     * Verify that applications can only have one registered LocalOnlyHotspot request at a time.
     *
     * Note: Location mode must be enabled for this test.
     */
    public void testStartLocalOnlyHotspotSingleRequestByApps() {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        // check that softap mode is supported by the device
        if (!mWifiManager.isPortableHotspotSupported()) {
            return;
        }

        boolean caughtException = false;

        boolean wifiEnabled = mWifiManager.isWifiEnabled();

        TestLocalOnlyHotspotCallback callback = startLocalOnlyHotspot();

        // now make a second request - this should fail.
        TestLocalOnlyHotspotCallback callback2 = new TestLocalOnlyHotspotCallback(mLOHSLock);
        try {
            mWifiManager.startLocalOnlyHotspot(callback2, null);
        } catch (IllegalStateException e) {
            Log.d(TAG, "Caught the IllegalStateException we expected: called startLOHS twice");
            caughtException = true;
        }
        if (!caughtException) {
            // second start did not fail, should clean up the hotspot.
            stopLocalOnlyHotspot(callback2, wifiEnabled);
        }
        assertTrue(caughtException);

        // add sleep to avoid calling stopLocalOnlyHotspot before TetherController initialization.
        // TODO: remove this sleep as soon as b/124330089 is fixed.
        try {
            Log.d(TAG, "Sleep for 2 seconds");
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Log.d(TAG, "Thread InterruptedException!");
        }

        stopLocalOnlyHotspot(callback, wifiEnabled);
    }

    /**
     * Verify that the {@link android.Manifest.permission#NETWORK_STACK} permission is never held by
     * any package.
     * <p>
     * No apps should <em>ever</em> attempt to acquire this permission, since it would give those
     * apps extremely broad access to connectivity functionality.
     */
    public void testNetworkStackPermission() {
        final PackageManager pm = getContext().getPackageManager();

        final List<PackageInfo> holding = pm.getPackagesHoldingPermissions(new String[] {
                android.Manifest.permission.NETWORK_STACK
        }, PackageManager.MATCH_UNINSTALLED_PACKAGES);
        for (PackageInfo pi : holding) {
            fail("The NETWORK_STACK permission must not be held by " + pi.packageName
                    + " and must be revoked for security reasons");
        }
    }

    /**
     * Verify that the {@link android.Manifest.permission#NETWORK_SETTINGS} permission is
     * never held by any package.
     * <p>
     * Only Settings, SysUi, NetworkStack and shell apps should <em>ever</em> attempt to acquire
     * this permission, since it would give those apps extremely broad access to connectivity
     * functionality.  The permission is intended to be granted to only those apps with direct user
     * access and no others.
     */
    public void testNetworkSettingsPermission() {
        final PackageManager pm = getContext().getPackageManager();

        final ArraySet<String> allowedPackages = new ArraySet();
        final ArraySet<Integer> allowedUIDs = new ArraySet();
        // explicitly add allowed UIDs
        allowedUIDs.add(Process.SYSTEM_UID);
        allowedUIDs.add(Process.SHELL_UID);
        allowedUIDs.add(Process.PHONE_UID);
        allowedUIDs.add(Process.NETWORK_STACK_UID);
        allowedUIDs.add(Process.NFC_UID);

        // only quick settings is allowed to bind to the BIND_QUICK_SETTINGS_TILE permission, using
        // this fact to determined allowed package name for sysui. This is a signature permission,
        // so allow any package with this permission.
        final List<PackageInfo> sysuiPackages = pm.getPackagesHoldingPermissions(new String[] {
                android.Manifest.permission.BIND_QUICK_SETTINGS_TILE
        }, PackageManager.MATCH_UNINSTALLED_PACKAGES);
        for (PackageInfo info : sysuiPackages) {
            allowedPackages.add(info.packageName);
        }

        // the captive portal flow also currently holds the NETWORK_SETTINGS permission
        final Intent intent = new Intent(ConnectivityManager.ACTION_CAPTIVE_PORTAL_SIGN_IN);
        final ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DISABLED_COMPONENTS);
        if (ri != null) {
            allowedPackages.add(ri.activityInfo.packageName);
        }

        final List<PackageInfo> holding = pm.getPackagesHoldingPermissions(new String[] {
                android.Manifest.permission.NETWORK_SETTINGS
        }, PackageManager.MATCH_UNINSTALLED_PACKAGES);
        for (PackageInfo pi : holding) {
            String packageName = pi.packageName;

            // this is an explicitly allowed package
            if (allowedPackages.contains(packageName)) continue;

            // now check if the packages are from allowed UIDs
            int uid = -1;
            try {
                uid = pm.getPackageUidAsUser(packageName, UserHandle.USER_SYSTEM);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if (!allowedUIDs.contains(uid)) {
                fail("The NETWORK_SETTINGS permission must not be held by " + packageName
                        + ":" + uid + " and must be revoked for security reasons");
            }
        }
    }

    /**
     * Verify that the {@link android.Manifest.permission#NETWORK_SETUP_WIZARD} permission is
     * only held by the device setup wizard application.
     * <p>
     * Only the SetupWizard app should <em>ever</em> attempt to acquire this
     * permission, since it would give those apps extremely broad access to connectivity
     * functionality.  The permission is intended to be granted to only the device setup wizard.
     */
    public void testNetworkSetupWizardPermission() {
        final ArraySet<String> allowedPackages = new ArraySet();

        final PackageManager pm = getContext().getPackageManager();

        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_SETUP_WIZARD);
        final ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DISABLED_COMPONENTS);
        String validPkg = "";
        if (ri != null) {
            allowedPackages.add(ri.activityInfo.packageName);
            validPkg = ri.activityInfo.packageName;
        }

        final Intent preIntent = new Intent("com.android.setupwizard.OEM_PRE_SETUP");
        preIntent.addCategory(Intent.CATEGORY_DEFAULT);
        final ResolveInfo preRi = pm
            .resolveActivity(preIntent, PackageManager.MATCH_DISABLED_COMPONENTS);
        String prePackageName = "";
        if (null != preRi) {
            prePackageName = preRi.activityInfo.packageName;
        }

        final Intent postIntent = new Intent("com.android.setupwizard.OEM_POST_SETUP");
        postIntent.addCategory(Intent.CATEGORY_DEFAULT);
        final ResolveInfo postRi = pm
            .resolveActivity(postIntent, PackageManager.MATCH_DISABLED_COMPONENTS);
        String postPackageName = "";
        if (null != postRi) {
            postPackageName = postRi.activityInfo.packageName;
        }
        if (!TextUtils.isEmpty(prePackageName) && !TextUtils.isEmpty(postPackageName)
            && prePackageName.equals(postPackageName)) {
            allowedPackages.add(prePackageName);
        }

        final List<PackageInfo> holding = pm.getPackagesHoldingPermissions(new String[]{
            android.Manifest.permission.NETWORK_SETUP_WIZARD
        }, PackageManager.MATCH_UNINSTALLED_PACKAGES);
        for (PackageInfo pi : holding) {
            if (!allowedPackages.contains(pi.packageName)) {
                fail("The NETWORK_SETUP_WIZARD permission must not be held by " + pi.packageName
                    + " and must be revoked for security reasons [" + validPkg + "]");
            }
        }
    }

    /**
     * Verify that the {@link android.Manifest.permission#NETWORK_MANAGED_PROVISIONING} permission
     * is only held by the device managed provisioning application.
     * <p>
     * Only the ManagedProvisioning app should <em>ever</em> attempt to acquire this
     * permission, since it would give those apps extremely broad access to connectivity
     * functionality.  The permission is intended to be granted to only the device managed
     * provisioning.
     */
    public void testNetworkManagedProvisioningPermission() {
        final PackageManager pm = getContext().getPackageManager();

        // TODO(b/115980767): Using hardcoded package name. Need a better mechanism to find the
        // managed provisioning app.
        // Ensure that the package exists.
        final Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.setPackage(MANAGED_PROVISIONING_PACKAGE_NAME);
        final ResolveInfo ri = pm.resolveActivity(intent, PackageManager.MATCH_DISABLED_COMPONENTS);
        String validPkg = "";
        if (ri != null) {
            validPkg = ri.activityInfo.packageName;
        }

        final List<PackageInfo> holding = pm.getPackagesHoldingPermissions(new String[] {
                android.Manifest.permission.NETWORK_MANAGED_PROVISIONING
        }, PackageManager.MATCH_UNINSTALLED_PACKAGES);
        for (PackageInfo pi : holding) {
            if (!Objects.equals(pi.packageName, validPkg)) {
                fail("The NETWORK_MANAGED_PROVISIONING permission must not be held by "
                        + pi.packageName + " and must be revoked for security reasons ["
                        + validPkg +"]");
            }
        }
    }

    /**
     * Verify that the {@link android.Manifest.permission#WIFI_SET_DEVICE_MOBILITY_STATE} permission
     * is held by at most one application.
     */
    public void testWifiSetDeviceMobilityStatePermission() {
        final PackageManager pm = getContext().getPackageManager();

        final List<PackageInfo> holding = pm.getPackagesHoldingPermissions(new String[] {
                android.Manifest.permission.WIFI_SET_DEVICE_MOBILITY_STATE
        }, PackageManager.MATCH_UNINSTALLED_PACKAGES);

        List<String> uniquePackageNames = holding
                .stream()
                .map(pi -> pi.packageName)
                .distinct()
                .collect(Collectors.toList());

        if (uniquePackageNames.size() > 1) {
            fail("The WIFI_SET_DEVICE_MOBILITY_STATE permission must not be held by more than one "
                    + "application, but is held by " + uniquePackageNames.size() + " applications: "
                    + String.join(", ", uniquePackageNames));
        }
    }

    /**
     * Verify that the {@link android.Manifest.permission#NETWORK_CARRIER_PROVISIONING} permission
     * is held by at most one application.
     */
    public void testNetworkCarrierProvisioningPermission() {
        final PackageManager pm = getContext().getPackageManager();

        final List<PackageInfo> holding = pm.getPackagesHoldingPermissions(new String[] {
                android.Manifest.permission.NETWORK_CARRIER_PROVISIONING
        }, PackageManager.MATCH_UNINSTALLED_PACKAGES);

        List<String> uniquePackageNames = holding
                .stream()
                .map(pi -> pi.packageName)
                .distinct()
                .collect(Collectors.toList());

        if (uniquePackageNames.size() > 1) {
            fail("The NETWORK_CARRIER_PROVISIONING permission must not be held by more than one "
                    + "application, but is held by " + uniquePackageNames.size() + " applications: "
                    + String.join(", ", uniquePackageNames));
        }
    }

    /**
     * Verify that the {@link android.Manifest.permission#WIFI_UPDATE_USABILITY_STATS_SCORE}
     * permission is held by at most one application.
     */
    public void testUpdateWifiUsabilityStatsScorePermission() {
        final PackageManager pm = getContext().getPackageManager();

        final List<PackageInfo> holding = pm.getPackagesHoldingPermissions(new String[] {
                android.Manifest.permission.WIFI_UPDATE_USABILITY_STATS_SCORE
        }, PackageManager.MATCH_UNINSTALLED_PACKAGES);

        List<String> uniquePackageNames = holding
                .stream()
                .map(pi -> pi.packageName)
                .distinct()
                .collect(Collectors.toList());

        if (uniquePackageNames.size() > 1) {
            fail("The WIFI_UPDATE_USABILITY_STATS_SCORE permission must not be held by more than "
                + "one application, but is held by " + uniquePackageNames.size() + " applications: "
                + String.join(", ", uniquePackageNames));
        }
    }

    private void turnScreenOnNoDelay() throws Exception {
        mUiDevice.executeShellCommand("input keyevent KEYCODE_WAKEUP");
        mUiDevice.executeShellCommand("wm dismiss-keyguard");
    }

    private void turnScreenOn() throws Exception {
        turnScreenOnNoDelay();
        // Since the screen on/off intent is ordered, they will not be sent right now.
        Thread.sleep(DURATION_SCREEN_TOGGLE);
    }

    private void turnScreenOff() throws Exception {
        mUiDevice.executeShellCommand("input keyevent KEYCODE_SLEEP");
        // Since the screen on/off intent is ordered, they will not be sent right now.
        Thread.sleep(DURATION_SCREEN_TOGGLE);
    }

    /**
     * Verify that Wi-Fi scanning is not turned off when the screen turns off while wifi is disabled
     * but location is on.
     * @throws Exception
     */
    public void testScreenOffDoesNotTurnOffWifiScanningWhenWifiDisabled() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        if (!hasLocationFeature()) {
            // skip the test if location is not supported
            return;
        }
        if (!isLocationEnabled()) {
            fail("Please enable location for this test - since Marshmallow WiFi scan results are"
                    + " empty when location is disabled!");
        }
        if(!mWifiManager.isScanAlwaysAvailable()) {
            fail("Please enable Wi-Fi scanning for this test!");
        }
        setWifiEnabled(false);
        turnScreenOn();
        assertWifiScanningIsOn();
        // Toggle screen and verify Wi-Fi scanning is still on.
        turnScreenOff();
        assertWifiScanningIsOn();
        turnScreenOn();
        assertWifiScanningIsOn();
    }

    /**
     * Verify that Wi-Fi scanning is not turned off when the screen turns off while wifi is enabled.
     * @throws Exception
     */
    public void testScreenOffDoesNotTurnOffWifiScanningWhenWifiEnabled() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        if (!hasLocationFeature()) {
            // skip the test if location is not supported
            return;
        }
        if (!isLocationEnabled()) {
            fail("Please enable location for this test - since Marshmallow WiFi scan results are"
                    + " empty when location is disabled!");
        }
        if(!mWifiManager.isScanAlwaysAvailable()) {
            fail("Please enable Wi-Fi scanning for this test!");
        }
        setWifiEnabled(true);
        turnScreenOn();
        assertWifiScanningIsOn();
        // Toggle screen and verify Wi-Fi scanning is still on.
        turnScreenOff();
        assertWifiScanningIsOn();
        turnScreenOn();
        assertWifiScanningIsOn();
    }

    /**
     * Verify that the platform supports a reasonable number of suggestions per app.
     * @throws Exception
     */
    public void testMaxNumberOfNetworkSuggestionsPerApp() throws Exception {
        if (!WifiFeature.isWifiSupported(getContext())) {
            // skip the test if WiFi is not supported
            return;
        }
        assertTrue(mWifiManager.getMaxNumberOfNetworkSuggestionsPerApp()
                > ENFORCED_NUM_NETWORK_SUGGESTIONS_PER_APP);
    }

    private void assertWifiScanningIsOn() {
        if(!mWifiManager.isScanAlwaysAvailable()) {
            fail("Wi-Fi scanning should be on.");
        }
    }
}
