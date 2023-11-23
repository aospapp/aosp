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

package com.android.server.wifi;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;

import android.content.Context;
import android.util.LocalLog;

import androidx.test.filters.SmallTest;

import com.android.wifi.resources.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Unit tests for {@link com.android.server.wifi.BssidBlocklistMonitor}.
 */
@SmallTest
public class BssidBlocklistMonitorTest {
    private static final int TEST_NUM_MAX_FIRMWARE_SUPPORT_BSSIDS = 3;
    private static final String TEST_SSID_1 = "TestSSID1";
    private static final String TEST_SSID_2 = "TestSSID2";
    private static final String TEST_SSID_3 = "TestSSID3";
    private static final String TEST_BSSID_1 = "0a:08:5c:67:89:00";
    private static final String TEST_BSSID_2 = "0a:08:5c:67:89:01";
    private static final String TEST_BSSID_3 = "0a:08:5c:67:89:02";
    private static final int TEST_L2_FAILURE = BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION;
    private static final int TEST_DHCP_FAILURE = BssidBlocklistMonitor.REASON_DHCP_FAILURE;
    private static final long BASE_BLOCKLIST_DURATION = TimeUnit.MINUTES.toMillis(5); // 5 minutes
    private static final long BASE_LOW_RSSI_BLOCKLIST_DURATION = TimeUnit.SECONDS.toMillis(30);
    private static final long ABNORMAL_DISCONNECT_TIME_WINDOW_MS = TimeUnit.SECONDS.toMillis(30);
    private static final long ABNORMAL_DISCONNECT_RESET_TIME_MS = TimeUnit.HOURS.toMillis(3);
    private static final int FAILURE_STREAK_CAP = 7;
    private static final int[] FAILURE_COUNT_DISABLE_THRESHOLD = {
            1,  //  threshold for REASON_AP_UNABLE_TO_HANDLE_NEW_STA
            1,  //  threshold for REASON_NETWORK_VALIDATION_FAILURE
            1,  //  threshold for REASON_WRONG_PASSWORD
            1,  //  threshold for REASON_EAP_FAILURE
            3,  //  threshold for REASON_ASSOCIATION_REJECTION
            3,  //  threshold for REASON_ASSOCIATION_TIMEOUT
            3,  //  threshold for REASON_AUTHENTICATION_FAILURE
            3,  //  threshold for REASON_DHCP_FAILURE
            3   //  threshold for REASON_ABNORMAL_DISCONNECT
    };
    private static final int NUM_FAILURES_TO_BLOCKLIST =
            FAILURE_COUNT_DISABLE_THRESHOLD[TEST_L2_FAILURE];


    @Mock private Context mContext;
    @Mock private WifiConnectivityHelper mWifiConnectivityHelper;
    @Mock private WifiLastResortWatchdog mWifiLastResortWatchdog;
    @Mock private Clock mClock;
    @Mock private LocalLog mLocalLog;
    @Mock private WifiScoreCard mWifiScoreCard;

    private MockResources mResources;
    private BssidBlocklistMonitor mBssidBlocklistMonitor;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(this);

        when(mWifiConnectivityHelper.isFirmwareRoamingSupported()).thenReturn(true);
        when(mWifiConnectivityHelper.getMaxNumBlacklistBssid())
                .thenReturn(TEST_NUM_MAX_FIRMWARE_SUPPORT_BSSIDS);
        mResources = new MockResources();
        mResources.setInteger(R.integer.config_wifiBssidBlocklistMonitorBaseBlockDurationMs,
                (int) BASE_BLOCKLIST_DURATION);
        mResources.setInteger(
                R.integer.config_wifiBssidBlocklistMonitorBaseLowRssiBlockDurationMs,
                (int) BASE_LOW_RSSI_BLOCKLIST_DURATION);
        mResources.setInteger(R.integer.config_wifiBssidBlocklistMonitorFailureStreakCap,
                FAILURE_STREAK_CAP);
        mResources.setInteger(R.integer.config_wifiBssidBlocklistAbnormalDisconnectTimeWindowMs,
                (int) ABNORMAL_DISCONNECT_TIME_WINDOW_MS);
        mResources.setInteger(
                R.integer.config_wifiBssidBlocklistMonitorApUnableToHandleNewStaThreshold,
                FAILURE_COUNT_DISABLE_THRESHOLD[0]);
        mResources.setInteger(
                R.integer.config_wifiBssidBlocklistMonitorNetworkValidationFailureThreshold,
                FAILURE_COUNT_DISABLE_THRESHOLD[1]);
        mResources.setInteger(R.integer.config_wifiBssidBlocklistMonitorWrongPasswordThreshold,
                FAILURE_COUNT_DISABLE_THRESHOLD[2]);
        mResources.setInteger(R.integer.config_wifiBssidBlocklistMonitorEapFailureThreshold,
                FAILURE_COUNT_DISABLE_THRESHOLD[3]);
        mResources.setInteger(
                R.integer.config_wifiBssidBlocklistMonitorAssociationRejectionThreshold,
                FAILURE_COUNT_DISABLE_THRESHOLD[4]);
        mResources.setInteger(
                R.integer.config_wifiBssidBlocklistMonitorAssociationTimeoutThreshold,
                FAILURE_COUNT_DISABLE_THRESHOLD[5]);
        mResources.setInteger(
                R.integer.config_wifiBssidBlocklistMonitorAuthenticationFailureThreshold,
                FAILURE_COUNT_DISABLE_THRESHOLD[6]);
        mResources.setInteger(R.integer.config_wifiBssidBlocklistMonitorDhcpFailureThreshold,
                FAILURE_COUNT_DISABLE_THRESHOLD[7]);
        mResources.setInteger(
                R.integer.config_wifiBssidBlocklistMonitorAbnormalDisconnectThreshold,
                FAILURE_COUNT_DISABLE_THRESHOLD[8]);

        when(mContext.getResources()).thenReturn(mResources);
        mBssidBlocklistMonitor = new BssidBlocklistMonitor(mContext, mWifiConnectivityHelper,
                mWifiLastResortWatchdog, mClock, mLocalLog, mWifiScoreCard);
    }

    private void verifyAddTestBssidToBlocklist() {
        mBssidBlocklistMonitor.handleBssidConnectionFailure(
                TEST_BSSID_1, TEST_SSID_1,
                BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA, false);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    // Verify adding 2 BSSID for SSID_1 and 1 BSSID for SSID_2 to the blocklist.
    private void verifyAddMultipleBssidsToBlocklist() {
        when(mClock.getWallClockMillis()).thenReturn(0L);
        mBssidBlocklistMonitor.handleBssidConnectionFailure(TEST_BSSID_1,
                TEST_SSID_1, BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA, false);
        when(mClock.getWallClockMillis()).thenReturn(1L);
        mBssidBlocklistMonitor.handleBssidConnectionFailure(TEST_BSSID_2,
                TEST_SSID_1, BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA, false);
        mBssidBlocklistMonitor.handleBssidConnectionFailure(TEST_BSSID_3,
                TEST_SSID_2, BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA, false);

        // Verify that we have 3 BSSIDs in the blocklist.
        Set<String> bssidList = mBssidBlocklistMonitor.updateAndGetBssidBlocklist();
        assertEquals(3, bssidList.size());
        assertTrue(bssidList.contains(TEST_BSSID_1));
        assertTrue(bssidList.contains(TEST_BSSID_2));
        assertTrue(bssidList.contains(TEST_BSSID_3));
    }

    private void handleBssidConnectionFailureMultipleTimes(String bssid, int reason, int times) {
        handleBssidConnectionFailureMultipleTimes(bssid, TEST_SSID_1, reason, times);
    }

    private void handleBssidConnectionFailureMultipleTimes(String bssid, String ssid, int reason,
            int times) {
        for (int i = 0; i < times; i++) {
            mBssidBlocklistMonitor.handleBssidConnectionFailure(bssid, ssid, reason, false);
        }
    }

    /**
     * Verify getNumBlockedBssidsForSsid returns the correct number of blocked BSSIDs.
     */
    @Test
    public void testGetNumBlockedBssidsForSsid() {
        verifyAddMultipleBssidsToBlocklist();
        assertEquals(2, mBssidBlocklistMonitor.getNumBlockedBssidsForSsid(TEST_SSID_1));
        assertEquals(1, mBssidBlocklistMonitor.getNumBlockedBssidsForSsid(TEST_SSID_2));
    }

    /**
     * Verify that updateAndGetBssidBlocklist removes expired blocklist entries and clears
     * all failure counters for those networks.
     */
    @Test
    public void testBssidIsRemovedFromBlocklistAfterTimeout() {
        verifyAddTestBssidToBlocklist();
        // Verify TEST_BSSID_1 is not removed from the blocklist until sufficient time have passed.
        when(mClock.getWallClockMillis()).thenReturn(BASE_BLOCKLIST_DURATION);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));

        // Verify that TEST_BSSID_1 is removed from the blocklist after the timeout duration.
        // By default there is no blocklist streak so the timeout duration is simply
        // BASE_BLOCKLIST_DURATION
        when(mClock.getWallClockMillis()).thenReturn(BASE_BLOCKLIST_DURATION + 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verify that a BSSID is blocked for a shorter time if the failure reason is association
     * timeout and the RSSI is low.
     */
    @Test
    public void testAssociationTimeoutAtLowRssi() {
        for (int i = 0; i < NUM_FAILURES_TO_BLOCKLIST; i++) {
            mBssidBlocklistMonitor.handleBssidConnectionFailure(TEST_BSSID_1, TEST_SSID_1,
                    BssidBlocklistMonitor.REASON_ASSOCIATION_TIMEOUT, true);
        }
        when(mClock.getWallClockMillis()).thenReturn(BASE_LOW_RSSI_BLOCKLIST_DURATION);
        assertEquals(1, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
        when(mClock.getWallClockMillis()).thenReturn(BASE_LOW_RSSI_BLOCKLIST_DURATION + 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verify that when adding a AP that had already been failing (therefore has a blocklist
     * streak), we are setting the blocklist duration using an exponential backoff technique.
     */
    @Test
    public void testBssidIsRemoveFromBlocklistAfterTimoutExponentialBackoff() {
        verifyAddTestBssidToBlocklist();
        int multiplier = 2;
        long duration = 0;
        for (int i = 1; i <= FAILURE_STREAK_CAP; i++) {
            when(mWifiScoreCard.getBssidBlocklistStreak(anyString(), anyString(), anyInt()))
                    .thenReturn(i);
            when(mClock.getWallClockMillis()).thenReturn(0L);
            verifyAddTestBssidToBlocklist();

            // calculate the expected blocklist duration then verify that timeout happens
            // exactly after the duration.
            duration = multiplier * BASE_BLOCKLIST_DURATION;
            when(mClock.getWallClockMillis()).thenReturn(duration);
            assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
            when(mClock.getWallClockMillis()).thenReturn(duration + 1);
            assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

            multiplier *= 2;
        }

        // finally verify that the timout is capped by the FAILURE_STREAK_CAP
        when(mWifiScoreCard.getBssidBlocklistStreak(anyString(), anyString(), anyInt()))
                .thenReturn(FAILURE_STREAK_CAP + 1);
        when(mClock.getWallClockMillis()).thenReturn(0L);
        verifyAddTestBssidToBlocklist();
        when(mClock.getWallClockMillis()).thenReturn(duration);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
        when(mClock.getWallClockMillis()).thenReturn(duration + 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verify that consecutive failures will add a BSSID to blocklist.
     */
    @Test
    public void testRepeatedConnectionFailuresAddToBlocklist() {
        // First verify that n-1 failrues does not add the BSSID to blocklist
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Simulate a long time passing to make sure failure counters are not being cleared through
        // some time based check
        when(mClock.getWallClockMillis()).thenReturn(10 * BASE_BLOCKLIST_DURATION);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that 1 more failure will add the BSSID to blacklist.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that only abnormal disconnects that happened in a window of time right after
     * connection gets counted in the BssidBlocklistMonitor.
     */
    @Test
    public void testAbnormalDisconnectRecencyCheck() {
        // does some setup so that 1 failure is enough to add the BSSID to blocklist.
        when(mWifiScoreCard.getBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_ABNORMAL_DISCONNECT)).thenReturn(1);

        // simulate an abnormal disconnect coming in after the allowed window of time
        when(mWifiScoreCard.getBssidConnectionTimestampMs(TEST_SSID_1, TEST_BSSID_1))
                .thenReturn(0L);
        when(mClock.getWallClockMillis()).thenReturn(ABNORMAL_DISCONNECT_TIME_WINDOW_MS + 1);
        assertFalse(mBssidBlocklistMonitor.handleBssidConnectionFailure(TEST_BSSID_1, TEST_SSID_1,
                BssidBlocklistMonitor.REASON_ABNORMAL_DISCONNECT, false));
        verify(mWifiScoreCard, never()).incrementBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_ABNORMAL_DISCONNECT);

        // simulate another abnormal disconnect within the time window and verify the BSSID is
        // added to blocklist.
        when(mClock.getWallClockMillis()).thenReturn(ABNORMAL_DISCONNECT_TIME_WINDOW_MS);
        assertTrue(mBssidBlocklistMonitor.handleBssidConnectionFailure(TEST_BSSID_1, TEST_SSID_1,
                BssidBlocklistMonitor.REASON_ABNORMAL_DISCONNECT, false));
        verify(mWifiScoreCard).incrementBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_ABNORMAL_DISCONNECT);
    }

    /**
     * Verify that when the BSSID blocklist streak is greater or equal to 1, then we block a
     * BSSID on a single failure regardless of failure type.
     */
    @Test
    public void testBlocklistStreakExpeditesAddingToBlocklist() {
        when(mWifiScoreCard.getBssidBlocklistStreak(anyString(), anyString(), anyInt()))
                .thenReturn(1);
        assertTrue(mBssidBlocklistMonitor.handleBssidConnectionFailure(
                TEST_BSSID_1, TEST_SSID_1, TEST_L2_FAILURE, false));
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that onSuccessfulConnection resets L2 related failure counts.
     */
    @Test
    public void testL2FailureCountIsResetAfterSuccessfulConnection() {
        // First simulate getting a particular L2 failure n-1 times
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify that a connection success event will clear the failure count.
        mBssidBlocklistMonitor.handleBssidConnectionSuccess(TEST_BSSID_1, TEST_SSID_1);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify we have not blocklisted anything yet because the failure count was cleared.
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that TEST_BSSID_1 is added to blocklist after 1 more failure.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that handleDhcpProvisioningSuccess resets DHCP failure counts.
     */
    @Test
    public void testL3FailureCountIsResetAfterDhcpConfiguration() {
        // First simulate getting an DHCP failure n-1 times.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify that a dhcp provisioning success event will clear appropirate failure counts.
        mBssidBlocklistMonitor.handleDhcpProvisioningSuccess(TEST_BSSID_1, TEST_SSID_1);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify we have not blocklisted anything yet because the failure count was cleared.
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that TEST_BSSID_1 is added to blocklist after 1 more failure.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that handleBssidConnectionSuccess resets appropriate blocklist streak counts, and
     * notifies WifiScorecard of the successful connection.
     */
    @Test
    public void testNetworkConnectionResetsBlocklistStreak() {
        when(mClock.getWallClockMillis()).thenReturn(ABNORMAL_DISCONNECT_RESET_TIME_MS + 1);
        mBssidBlocklistMonitor.handleBssidConnectionSuccess(TEST_BSSID_1, TEST_SSID_1);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_WRONG_PASSWORD);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_EAP_FAILURE);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_ASSOCIATION_REJECTION);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_ASSOCIATION_TIMEOUT);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_AUTHENTICATION_FAILURE);
        verify(mWifiScoreCard).setBssidConnectionTimestampMs(TEST_SSID_1, TEST_BSSID_1,
                ABNORMAL_DISCONNECT_RESET_TIME_MS + 1);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_ABNORMAL_DISCONNECT);
    }

    /**
     * Verify that the abnormal disconnect streak is not reset if insufficient time has passed.
     */
    @Test
    public void testNetworkConnectionNotResetAbnormalDisconnectStreak() {
        when(mClock.getWallClockMillis()).thenReturn(ABNORMAL_DISCONNECT_RESET_TIME_MS);
        mBssidBlocklistMonitor.handleBssidConnectionSuccess(TEST_BSSID_1, TEST_SSID_1);
        verify(mWifiScoreCard).setBssidConnectionTimestampMs(TEST_SSID_1, TEST_BSSID_1,
                ABNORMAL_DISCONNECT_RESET_TIME_MS);
        verify(mWifiScoreCard, never()).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_ABNORMAL_DISCONNECT);
    }

    /**
     * Verify that handleDhcpProvisioningSuccess resets appropriate blocklist streak counts.
     */
    @Test
    public void testDhcpProvisioningResetsBlocklistStreak() {
        mBssidBlocklistMonitor.handleDhcpProvisioningSuccess(TEST_BSSID_1, TEST_SSID_1);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_DHCP_FAILURE);
    }

    /**
     * Verify that handleNetworkValidationSuccess resets appropriate blocklist streak counts
     * and removes the BSSID from blocklist.
     */
    @Test
    public void testNetworkValidationResetsBlocklistStreak() {
        verifyAddTestBssidToBlocklist();
        mBssidBlocklistMonitor.handleNetworkValidationSuccess(TEST_BSSID_1, TEST_SSID_1);
        verify(mWifiScoreCard).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_NETWORK_VALIDATION_FAILURE);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verify that L3 failure counts are not affected when L2 failure counts are reset.
     */
    @Test
    public void testL3FailureCountIsNotResetByConnectionSuccess() {
        // First simulate getting an L3 failure n-1 times.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that the failure counter is not cleared by |handleBssidConnectionSuccess|.
        mBssidBlocklistMonitor.handleBssidConnectionSuccess(TEST_BSSID_1, TEST_SSID_1);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_DHCP_FAILURE, 1);
        assertEquals(1, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verify that the blocklist streak is incremented after adding a BSSID to blocklist.
     * And then verify the blocklist streak is not reset by a regular timeout.
     */
    @Test
    public void testIncrementingBlocklistStreakCount() {
        verifyAddTestBssidToBlocklist();
        // verify that the blocklist streak is incremented
        verify(mWifiScoreCard).incrementBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);

        // Verify that TEST_BSSID_1 is removed from the blocklist after the timeout duration.
        when(mClock.getWallClockMillis()).thenReturn(BASE_BLOCKLIST_DURATION + 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // But the blacklist streak count is not cleared
        verify(mWifiScoreCard, never()).resetBssidBlocklistStreak(TEST_SSID_1, TEST_BSSID_1,
                BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA);
    }

    /**
     * Verify that when a failure signal is received for a BSSID with different SSID from before,
     * then the failure counts are reset.
     */
    @Test
    public void testFailureCountIsResetIfSsidChanges() {
        // First simulate getting a particular L2 failure n-1 times
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify that when the SSID changes, the failure counts are cleared.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_SSID_2, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST - 1);

        // Verify we have not blocklisted anything yet because the failure count was cleared.
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that TEST_BSSID_1 is added to blocklist after 1 more failure.
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_SSID_2, TEST_L2_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that a BSSID is not added to blocklist as long as
     * mWifiLastResortWatchdog.shouldIgnoreBssidUpdate is returning true, for failure reasons
     * that are also being tracked by the watchdog.
     */
    @Test
    public void testWatchdogIsGivenChanceToTrigger() {
        // Verify that |shouldIgnoreBssidUpdate| can prevent a BSSID from being added to blocklist.
        when(mWifiLastResortWatchdog.shouldIgnoreBssidUpdate(anyString())).thenReturn(true);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE,
                NUM_FAILURES_TO_BLOCKLIST * 2);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that after watchdog is okay with blocking a BSSID, it gets blocked after 1
        // more failure.
        when(mWifiLastResortWatchdog.shouldIgnoreBssidUpdate(anyString())).thenReturn(false);
        handleBssidConnectionFailureMultipleTimes(TEST_BSSID_1, TEST_L2_FAILURE, 1);
        assertTrue(mBssidBlocklistMonitor.updateAndGetBssidBlocklist().contains(TEST_BSSID_1));
    }

    /**
     * Verify that non device related errors, and errors that are not monitored by the watchdog
     * bypasses the watchdog check.
     */
    @Test
    public void testUnrelatedErrorsBypassWatchdogCheck() {
        when(mWifiLastResortWatchdog.shouldIgnoreBssidUpdate(anyString())).thenReturn(true);
        verifyAddTestBssidToBlocklist();
        verify(mWifiLastResortWatchdog, never()).shouldIgnoreBssidUpdate(anyString());
    }

    /**
     * Verify that we are correctly filtering by SSID when sending a blocklist down to firmware.
     */
    @Test
    public void testSendBlocklistToFirmwareFilterBySsid() {
        verifyAddMultipleBssidsToBlocklist();

        // Verify we are sending 2 BSSIDs down to the firmware for SSID_1.
        ArrayList<String> blocklist1 = new ArrayList<>();
        blocklist1.add(TEST_BSSID_2);
        blocklist1.add(TEST_BSSID_1);
        mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_1);
        verify(mWifiConnectivityHelper).setFirmwareRoamingConfiguration(eq(blocklist1),
                eq(new ArrayList<>()));

        // Verify we are sending 1 BSSID down to the firmware for SSID_2.
        ArrayList<String> blocklist2 = new ArrayList<>();
        blocklist2.add(TEST_BSSID_3);
        mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_2);
        verify(mWifiConnectivityHelper).setFirmwareRoamingConfiguration(eq(blocklist2),
                eq(new ArrayList<>()));

        // Verify we are not sending any BSSIDs down to the firmware since there does not
        // exists any BSSIDs for TEST_SSID_3 in the blocklist.
        mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_3);
        verify(mWifiConnectivityHelper).setFirmwareRoamingConfiguration(eq(new ArrayList<>()),
                eq(new ArrayList<>()));
    }

    /**
     * Verify that when sending the blocklist down to firmware, the list is sorted by latest
     * end time first.
     * Also verify that when there are more blocklisted BSSIDs than the allowed limit by the
     * firmware, the list sent down is trimmed.
     */
    @Test
    public void testMostRecentBlocklistEntriesAreSentToFirmware() {
        // Add BSSIDs to blocklist
        String bssid = "0a:08:5c:67:89:0";
        for (int i = 0; i < 10; i++) {
            when(mClock.getWallClockMillis()).thenReturn((long) i);
            mBssidBlocklistMonitor.handleBssidConnectionFailure(bssid + i,
                    TEST_SSID_1, BssidBlocklistMonitor.REASON_AP_UNABLE_TO_HANDLE_NEW_STA, false);

            // This will build a List of BSSIDs starting from the latest added ones that is at
            // most size |TEST_NUM_MAX_FIRMWARE_SUPPORT_BSSIDS|.
            // Then verify that the blocklist is sent down in this sorted order.
            ArrayList<String> blocklist = new ArrayList<>();
            for (int j = i; j > i - TEST_NUM_MAX_FIRMWARE_SUPPORT_BSSIDS; j--) {
                if (j < 0) {
                    break;
                }
                blocklist.add(bssid + j);
            }
            mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_1);
            verify(mWifiConnectivityHelper).setFirmwareRoamingConfiguration(eq(blocklist),
                    eq(new ArrayList<>()));
        }
        assertEquals(10, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }

    /**
     * Verifies that when firmware roaming is disabled, the blocklist does not get plumbed to
     * hardware, but the blocklist should still accessible by the framework.
     */
    @Test
    public void testFirmwareRoamingDisabled() {
        when(mWifiConnectivityHelper.isFirmwareRoamingSupported()).thenReturn(false);
        verifyAddTestBssidToBlocklist();

        mBssidBlocklistMonitor.updateFirmwareRoamingConfiguration(TEST_SSID_1);
        verify(mWifiConnectivityHelper, never()).setFirmwareRoamingConfiguration(any(), any());
    }

    /**
     * Verify that clearBssidBlocklist resets internal state.
     */
    @Test
    public void testClearBssidBlocklist() {
        verifyAddTestBssidToBlocklist();
        mBssidBlocklistMonitor.clearBssidBlocklist();
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

    }

    /**
     * Verify that the BssidStatusHistoryLoggerSize is capped.
     */
    @Test
    public void testBssidStatusHistoryLoggerSize() {
        int bssidStatusHistoryLoggerSize = 30;
        for (int i = 0; i < bssidStatusHistoryLoggerSize; i++) {
            verifyAddTestBssidToBlocklist();
            mBssidBlocklistMonitor.clearBssidBlocklist();
            assertEquals(i + 1, mBssidBlocklistMonitor.getBssidStatusHistoryLoggerSize());
        }
        verifyAddTestBssidToBlocklist();
        mBssidBlocklistMonitor.clearBssidBlocklist();
        assertEquals(bssidStatusHistoryLoggerSize,
                mBssidBlocklistMonitor.getBssidStatusHistoryLoggerSize());
    }

    /**
     * Verify that clearBssidBlocklistForSsid removes all BSSIDs for that network from the
     * blocklist.
     */
    @Test
    public void testClearBssidBlocklistForSsid() {
        verifyAddMultipleBssidsToBlocklist();

        // Clear the blocklist for SSID 1.
        mBssidBlocklistMonitor.clearBssidBlocklistForSsid(TEST_SSID_1);

        // Verify that the blocklist is deleted for SSID 1 and the BSSID for SSID 2 still remains.
        Set<String> bssidList = mBssidBlocklistMonitor.updateAndGetBssidBlocklist();
        assertEquals(1, bssidList.size());
        assertTrue(bssidList.contains(TEST_BSSID_3));
        verify(mWifiScoreCard, never()).resetBssidBlocklistStreakForSsid(TEST_SSID_1);
    }

    /**
     * Verify that handleNetworkRemoved removes all BSSIDs for that network from the blocklist
     * and also reset the blocklist streak count from WifiScoreCard.
     */
    @Test
    public void testHandleNetworkRemovedResetsState() {
        verifyAddMultipleBssidsToBlocklist();

        // Clear the blocklist for SSID 1.
        mBssidBlocklistMonitor.handleNetworkRemoved(TEST_SSID_1);

        // Verify that the blocklist is deleted for SSID 1 and the BSSID for SSID 2 still remains.
        Set<String> bssidList = mBssidBlocklistMonitor.updateAndGetBssidBlocklist();
        assertEquals(1, bssidList.size());
        assertTrue(bssidList.contains(TEST_BSSID_3));
        verify(mWifiScoreCard).resetBssidBlocklistStreakForSsid(TEST_SSID_1);
    }

    /**
     * Verify that |blockBssidForDurationMs| adds a BSSID to blocklist for the specified duration.
     */
    @Test
    public void testBlockBssidForDurationMs() {
        when(mClock.getWallClockMillis()).thenReturn(0L);
        long testDuration = 5500L;
        mBssidBlocklistMonitor.blockBssidForDurationMs(TEST_BSSID_1, TEST_SSID_1, testDuration);
        assertEquals(1, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that the BSSID is removed from blocklist by clearBssidBlocklistForSsid
        mBssidBlocklistMonitor.clearBssidBlocklistForSsid(TEST_SSID_1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
        assertEquals(1, mBssidBlocklistMonitor.getBssidStatusHistoryLoggerSize());

        // Add the BSSID to blocklist again.
        mBssidBlocklistMonitor.blockBssidForDurationMs(TEST_BSSID_1, TEST_SSID_1, testDuration);
        assertEquals(1, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // Verify that the BSSID is removed from blocklist once the specified duration is over.
        when(mClock.getWallClockMillis()).thenReturn(testDuration + 1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
        assertEquals(2, mBssidBlocklistMonitor.getBssidStatusHistoryLoggerSize());
    }

    /**
     * Verify that invalid inputs are handled and result in no-op.
     */
    @Test
    public void testBlockBssidForDurationMsInvalidInputs() {
        // test invalid BSSID
        when(mClock.getWallClockMillis()).thenReturn(0L);
        long testDuration = 5500L;
        mBssidBlocklistMonitor.blockBssidForDurationMs(null, TEST_SSID_1, testDuration);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // test invalid SSID
        mBssidBlocklistMonitor.blockBssidForDurationMs(TEST_BSSID_1, null, testDuration);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());

        // test invalid duration
        mBssidBlocklistMonitor.blockBssidForDurationMs(TEST_BSSID_1, TEST_SSID_1, -1);
        assertEquals(0, mBssidBlocklistMonitor.updateAndGetBssidBlocklist().size());
    }
}
