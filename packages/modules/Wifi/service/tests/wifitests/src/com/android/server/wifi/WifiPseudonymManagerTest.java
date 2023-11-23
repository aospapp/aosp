/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static com.android.server.wifi.WifiCarrierInfoManager.ANONYMOUS_IDENTITY;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.net.wifi.WifiConfiguration;
import android.net.wifi.WifiContext;
import android.net.wifi.WifiEnterpriseConfig;
import android.os.Message;
import android.os.test.TestLooper;
import android.test.suitebuilder.annotation.SmallTest;

import com.android.server.wifi.entitlement.CarrierSpecificServiceEntitlement;
import com.android.server.wifi.entitlement.PseudonymInfo;
import com.android.server.wifi.hotspot2.PasspointManager;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;

/**
 * Unit tests for {@link WifiPseudonymManager}.
 */
@SmallTest
public class WifiPseudonymManagerTest extends WifiBaseTest {
    private static final int CARRIER_ID = 1;
    private static final int WRONG_CARRIER_ID = 99;
    private static final String PSEUDONYM = "pseudonym";
    private static final int SUB_ID = 2;
    private static final String IMSI = "imsi";
    private static final String DECORATED_PSEUDONYM = PSEUDONYM + "@";
    private static final String MCCMNC = "mccmnc";
    @Mock
    private WifiContext mWifiContext;
    @Mock
    private WifiInjector mWifiInjector;
    @Mock
    private WifiConfiguration mWifiConfiguration;
    @Mock
    private WifiEnterpriseConfig mEnterpriseConfig;
    @Mock
    private WifiCarrierInfoManager mWifiCarrierInfoManager;
    @Mock
    private WifiConfigManager mWifiConfigManager;
    @Mock
    private NetworkUpdateResult mNetworkUpdateResult;
    @Mock
    private PasspointManager mPasspointManager;
    @Mock
    private WifiNetworkSuggestionsManager mWifiNetworkSuggestionsManager;
    @Mock
    Clock mClock;

    private WifiPseudonymManager mWifiPseudonymManager;
    private TestLooper mTestLooper;

    @Before
    public void setUp() throws Exception {
        mTestLooper = new TestLooper();
        MockitoAnnotations.initMocks(this);
        when(mClock.getWallClockMillis()).thenReturn(Instant.now().toEpochMilli());
        when(mWifiInjector.getWifiCarrierInfoManager()).thenReturn(mWifiCarrierInfoManager);
        when(mWifiInjector.getWifiConfigManager()).thenReturn(mWifiConfigManager);
        when(mWifiInjector.getPasspointManager()).thenReturn(mPasspointManager);
        when(mWifiInjector.getWifiNetworkSuggestionsManager())
                .thenReturn(mWifiNetworkSuggestionsManager);
        when(mWifiCarrierInfoManager.getSimInfo(anyInt())).thenReturn(
                new WifiCarrierInfoManager.SimInfo(IMSI, MCCMNC, CARRIER_ID, CARRIER_ID));
    }

    @Test
    public void getValidPseudonymInfoEmpty() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        assertTrue(mWifiPseudonymManager.getValidPseudonymInfo(CARRIER_ID).isEmpty());
    }

    @Test
    public void getValidPseudonymInfoEmptyExpired() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        setAnExpiredPseudonym(mWifiPseudonymManager);
        assertTrue(mWifiPseudonymManager.getValidPseudonymInfo(WRONG_CARRIER_ID).isEmpty());
    }

    @Test
    public void getValidPseudonymInfoEmptyWrongCarrierId() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        setAValidPseudonym(mWifiPseudonymManager);
        assertTrue(mWifiPseudonymManager.getValidPseudonymInfo(WRONG_CARRIER_ID).isEmpty());
    }

    @Test
    public void getValidPseudonymInfoPresent() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        setAValidPseudonym(mWifiPseudonymManager);
        assertTrue(mWifiPseudonymManager.getValidPseudonymInfo(CARRIER_ID).isPresent());
    }

    @Test
    public void retrievePseudonymOnFailureTimeoutExpiredSchedule() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        long nowTime = Instant.now().toEpochMilli();
        when(mClock.getWallClockMillis()).thenReturn(nowTime);
        when(mWifiCarrierInfoManager.isOobPseudonymFeatureEnabled(CARRIER_ID)).thenReturn(true);
        mWifiPseudonymManager.mLastFailureTimestampArray.put(CARRIER_ID,
                nowTime - Duration.ofDays(7).toMillis());
        mWifiPseudonymManager.retrievePseudonymOnFailureTimeoutExpired(CARRIER_ID);
        assertTrue(mTestLooper.isIdle());
        Message message = mTestLooper.nextMessage();
        assertEquals(CARRIER_ID, message.what);
        assertEquals(CARRIER_ID,
                ((WifiPseudonymManager.RetrieveRunnable) message.getCallback()).mCarrierId);
    }

    @Test
    public void retrievePseudonymOnFailureTimeoutExpiredNotSchedule() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        when(mWifiCarrierInfoManager.isOobPseudonymFeatureEnabled(CARRIER_ID)).thenReturn(true);
        mWifiPseudonymManager.retrievePseudonymOnFailureTimeoutExpired(CARRIER_ID);
        assertFalse(mTestLooper.isIdle());
    }

    @Test
    public void setInBandPseudonymInfoAbsent() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());

        mWifiPseudonymManager.setInBandPseudonym(CARRIER_ID, PSEUDONYM);
        Optional<PseudonymInfo> pseudonymInfoOptional =
                mWifiPseudonymManager.getValidPseudonymInfo(CARRIER_ID);
        assertTrue(pseudonymInfoOptional.isEmpty());
    }

    @Test
    public void setInBandPseudonymInfoPresent() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        setAnExpiredPseudonym(mWifiPseudonymManager);

        mWifiPseudonymManager.setInBandPseudonym(CARRIER_ID, PSEUDONYM);
        Optional<PseudonymInfo> pseudonymInfoOptional =
                mWifiPseudonymManager.getValidPseudonymInfo(CARRIER_ID);
        assertTrue(pseudonymInfoOptional.isPresent());
        assertEquals(PSEUDONYM, pseudonymInfoOptional.get().getPseudonym());
    }

    @Test
    public void updateWifiConfigurationWithExpiredPseudonym() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        mWifiConfiguration.enterpriseConfig = mEnterpriseConfig;
        mWifiConfiguration.carrierId = CARRIER_ID;
        when(mWifiCarrierInfoManager.isOobPseudonymFeatureEnabled(CARRIER_ID)).thenReturn(true);
        when(mEnterpriseConfig.isAuthenticationSimBased()).thenReturn(true);
        setAnExpiredPseudonym(mWifiPseudonymManager);

        mWifiPseudonymManager.updateWifiConfiguration(mWifiConfiguration);

        verify(mWifiCarrierInfoManager, never()).decoratePseudonymWith3GppRealm(any(), anyString());
    }

    @Test
    public void updateWifiConfigurationPasspointWithValidPseudonym() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        mWifiConfiguration.enterpriseConfig = mEnterpriseConfig;
        mWifiConfiguration.carrierId = CARRIER_ID;
        when(mWifiCarrierInfoManager.isOobPseudonymFeatureEnabled(CARRIER_ID)).thenReturn(true);
        when(mEnterpriseConfig.isAuthenticationSimBased()).thenReturn(true);
        when(mWifiConfiguration.isPasspoint()).thenReturn(true);
        // Make sure that there is one PseudonymInfo in WifiPseudonymManager
        mWifiPseudonymManager.setPseudonymAndScheduleRefresh(CARRIER_ID,
                new PseudonymInfo(PSEUDONYM, IMSI));
        when(mWifiCarrierInfoManager.decoratePseudonymWith3GppRealm(mWifiConfiguration, PSEUDONYM))
                .thenReturn(DECORATED_PSEUDONYM);
        when(mWifiConfigManager.addOrUpdateNetwork(any(), anyInt()))
                .thenReturn(mNetworkUpdateResult);
        when(mEnterpriseConfig.getAnonymousIdentity()).thenReturn(ANONYMOUS_IDENTITY + "@");

        mWifiPseudonymManager.updateWifiConfiguration(
                mWifiConfiguration);

        verify(mEnterpriseConfig).setAnonymousIdentity(DECORATED_PSEUDONYM);
        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt());
        verify(mPasspointManager).setAnonymousIdentity(any());
        verify(mWifiNetworkSuggestionsManager, never()).setAnonymousIdentity(any());
    }

    @Test
    public void updateWifiConfigurationNonPasspointNetworkSuggesetionWithValidPseudonym() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        mWifiConfiguration.enterpriseConfig = mEnterpriseConfig;
        mWifiConfiguration.carrierId = CARRIER_ID;
        mWifiConfiguration.fromWifiNetworkSuggestion = true;
        when(mWifiConfiguration.isPasspoint()).thenReturn(false);
        // Make sure that there is one PseudonymInfo in WifiPseudonymManager
        mWifiPseudonymManager.setPseudonymAndScheduleRefresh(CARRIER_ID,
                new PseudonymInfo(PSEUDONYM, IMSI));
        when(mWifiCarrierInfoManager.isOobPseudonymFeatureEnabled(CARRIER_ID)).thenReturn(true);
        when(mEnterpriseConfig.isAuthenticationSimBased()).thenReturn(true);
        when(mWifiCarrierInfoManager.decoratePseudonymWith3GppRealm(mWifiConfiguration, PSEUDONYM))
                .thenReturn(DECORATED_PSEUDONYM);
        when(mWifiConfigManager.addOrUpdateNetwork(any(), anyInt()))
                .thenReturn(mNetworkUpdateResult);
        when(mEnterpriseConfig.getAnonymousIdentity()).thenReturn(ANONYMOUS_IDENTITY + "@");
        mWifiPseudonymManager.updateWifiConfiguration(
                mWifiConfiguration);

        verify(mEnterpriseConfig).setAnonymousIdentity(DECORATED_PSEUDONYM);
        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt());
        verify(mPasspointManager, never()).setAnonymousIdentity(any());
        verify(mWifiNetworkSuggestionsManager).setAnonymousIdentity(any());
    }

    @Test
    public void updateWifiConfigurationNonPasspointNotNetworkSuggestion() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());

        mWifiConfiguration.enterpriseConfig = mEnterpriseConfig;
        mWifiConfiguration.carrierId = CARRIER_ID;
        mWifiConfiguration.fromWifiNetworkSuggestion = false;
        when(mWifiConfiguration.isPasspoint()).thenReturn(false);

        // Make sure that there is one PseudonymInfo in WifiPseudonymManager
        mWifiPseudonymManager.setPseudonymAndScheduleRefresh(CARRIER_ID,
                new PseudonymInfo(PSEUDONYM, IMSI));
        when(mWifiCarrierInfoManager.isOobPseudonymFeatureEnabled(CARRIER_ID)).thenReturn(true);
        when(mEnterpriseConfig.isAuthenticationSimBased()).thenReturn(true);
        when(mWifiCarrierInfoManager.decoratePseudonymWith3GppRealm(mWifiConfiguration, PSEUDONYM))
                .thenReturn(DECORATED_PSEUDONYM);
        when(mWifiConfigManager.addOrUpdateNetwork(any(), anyInt()))
                .thenReturn(mNetworkUpdateResult);
        when(mEnterpriseConfig.getAnonymousIdentity()).thenReturn(ANONYMOUS_IDENTITY + "@");

        mWifiPseudonymManager.updateWifiConfiguration(
                mWifiConfiguration);

        verify(mEnterpriseConfig).setAnonymousIdentity(DECORATED_PSEUDONYM);
        verify(mWifiConfigManager).addOrUpdateNetwork(any(), anyInt());
        verify(mPasspointManager, never()).setAnonymousIdentity(any());
        verify(mWifiNetworkSuggestionsManager, never()).setAnonymousIdentity(any());
    }

    @Test
    public void updateWifiConfigurationNoNeedToUpdate() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());

        mWifiConfiguration.enterpriseConfig = mEnterpriseConfig;
        mWifiConfiguration.carrierId = CARRIER_ID;

        // Make sure that there is one PseudonymInfo in WifiPseudonymManager
        mWifiPseudonymManager.setPseudonymAndScheduleRefresh(CARRIER_ID,
                new PseudonymInfo(PSEUDONYM, IMSI));
        when(mWifiCarrierInfoManager.isOobPseudonymFeatureEnabled(CARRIER_ID)).thenReturn(true);
        when(mEnterpriseConfig.isAuthenticationSimBased()).thenReturn(true);
        when(mWifiCarrierInfoManager.decoratePseudonymWith3GppRealm(mWifiConfiguration, PSEUDONYM))
                .thenReturn(DECORATED_PSEUDONYM);
        when(mWifiConfigManager.addOrUpdateNetwork(any(), anyInt()))
                .thenReturn(mNetworkUpdateResult);
        when(mEnterpriseConfig.getAnonymousIdentity()).thenReturn(DECORATED_PSEUDONYM);

        mWifiPseudonymManager.updateWifiConfiguration(
                mWifiConfiguration);

        verify(mEnterpriseConfig, never()).setAnonymousIdentity(any());
    }

    @Test
    public void setPseudonymAndScheduleRefreshSchedule() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        mWifiPseudonymManager.setPseudonymAndScheduleRefresh(CARRIER_ID,
                new PseudonymInfo(PSEUDONYM, IMSI));
        assertFalse(mTestLooper.isIdle());
        mTestLooper.moveTimeForward(Duration.ofHours(48).toMillis());
        assertTrue(mTestLooper.isIdle());
        Message message = mTestLooper.nextMessage();
        assertEquals(CARRIER_ID, message.what);
        assertEquals(CARRIER_ID,
                ((WifiPseudonymManager.RetrieveRunnable) message.getCallback()).mCarrierId);
        assertTrue(mWifiPseudonymManager.getValidPseudonymInfo(CARRIER_ID).isPresent());
    }

    @Test
    public void retrieveOobPseudonymIfNeededEmptySchedule() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());

        mWifiPseudonymManager.retrieveOobPseudonymIfNeeded(CARRIER_ID);
        assertTrue(mTestLooper.isIdle());
        Message message = mTestLooper.nextMessage();
        assertEquals(CARRIER_ID, message.what);
        assertEquals(CARRIER_ID,
                ((WifiPseudonymManager.RetrieveRunnable) message.getCallback()).mCarrierId);
    }

    @Test
    public void retrieveOobPseudonymIfNeededPresentNoScheduleWithFreshPseudonym() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());

        when(mWifiCarrierInfoManager.getMatchingSubId(CARRIER_ID)).thenReturn(SUB_ID);
        mWifiPseudonymManager.setPseudonymAndScheduleRefresh(CARRIER_ID,
                new PseudonymInfo(PSEUDONYM, IMSI));
        assertFalse(mTestLooper.isIdle());

        when(mWifiCarrierInfoManager.getMatchingSubId(CARRIER_ID)).thenReturn(SUB_ID);
        mWifiPseudonymManager.retrieveOobPseudonymIfNeeded(CARRIER_ID);
        assertFalse(mTestLooper.isIdle());
    }

    @Test
    public void retrieveOobPseudonymIfNeededScheduleWithExpiredPseudonym() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());

        when(mWifiCarrierInfoManager.getMatchingSubId(CARRIER_ID)).thenReturn(SUB_ID);
        setAnExpiredPseudonym(mWifiPseudonymManager);
        when(mWifiCarrierInfoManager.getMatchingSubId(CARRIER_ID)).thenReturn(SUB_ID);
        mWifiPseudonymManager.retrieveOobPseudonymIfNeeded(CARRIER_ID);
        assertTrue(mTestLooper.isIdle());
    }

    @Test
    public void retrieveOobPseudonymWithRateLimitScheduleWithExpiredPseudonym() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        setAnExpiredPseudonym(mWifiPseudonymManager);
        mWifiPseudonymManager.retrieveOobPseudonymWithRateLimit(CARRIER_ID);
        assertFalse(mTestLooper.isIdle());
        mTestLooper.moveTimeForward(Duration.ofSeconds(10).toMillis());
        assertTrue(mTestLooper.isIdle());
        Message message = mTestLooper.nextMessage();
        assertEquals(CARRIER_ID, message.what);
        assertEquals(CARRIER_ID,
                ((WifiPseudonymManager.RetrieveRunnable) message.getCallback()).mCarrierId);
    }

    @Test
    public void retrieveOobPseudonymWithRateLimitNoScheduleWithEmptyPseudonym() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        mWifiPseudonymManager.retrieveOobPseudonymWithRateLimit(CARRIER_ID);
        mTestLooper.moveTimeForward(Duration.ofSeconds(10).toMillis());
        assertFalse(mTestLooper.isIdle());
    }
    @Test
    public void retrieveOobPseudonymWithRateLimitNoScheduleWithFreshPseudonym() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        mWifiPseudonymManager.setPseudonymAndScheduleRefresh(CARRIER_ID,
                new PseudonymInfo(PSEUDONYM, IMSI));
        mWifiPseudonymManager.retrieveOobPseudonymWithRateLimit(CARRIER_ID);
        mTestLooper.moveTimeForward(Duration.ofSeconds(10).toMillis());
        assertFalse(mTestLooper.isIdle());
    }

    @Test
    public void testRetrieveCallbackFailureByConnectionError() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        Message message;
        String errorDescription = "HTTPS connection error";
        for (int retryCount = 0;
                retryCount < WifiPseudonymManager.RETRY_INTERVALS_FOR_CONNECTION_ERROR.length;
                retryCount++) {
            mWifiPseudonymManager.mRetrieveCallback.onFailure(CARRIER_ID,
                    CarrierSpecificServiceEntitlement.REASON_HTTPS_CONNECTION_FAILURE,
                    errorDescription);
            mTestLooper.moveTimeForward(
                    WifiPseudonymManager.RETRY_INTERVALS_FOR_CONNECTION_ERROR[retryCount]);
            assertTrue(mTestLooper.isIdle());

            message = mTestLooper.nextMessage();
            assertEquals(CARRIER_ID, message.what);
            assertEquals(CARRIER_ID,
                    ((WifiPseudonymManager.RetrieveRunnable) message.getCallback()).mCarrierId);
            assertNull(mTestLooper.nextMessage());
        }

        mWifiPseudonymManager.mRetrieveCallback.onFailure(CARRIER_ID,
                CarrierSpecificServiceEntitlement.REASON_HTTPS_CONNECTION_FAILURE,
                errorDescription);
        mTestLooper.moveTimeForward(Duration.ofDays(100).toMillis()); // Move forward long enough
        assertFalse(mTestLooper.isIdle());
        assertNull(mTestLooper.nextMessage());
    }

    @Test
    public void testRetrieveCallbackFailureTransient() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        Message message;
        String errorDescription = "Server error";
        for (int retryCount = 0;
                retryCount < WifiPseudonymManager.RETRY_INTERVALS_FOR_SERVER_ERROR.length;
                retryCount++) {
            mWifiPseudonymManager.mRetrieveCallback.onFailure(CARRIER_ID,
                    CarrierSpecificServiceEntitlement.REASON_TRANSIENT_FAILURE, errorDescription);
            mTestLooper.moveTimeForward(
                    WifiPseudonymManager.RETRY_INTERVALS_FOR_SERVER_ERROR[retryCount]);
            assertTrue(mTestLooper.isIdle());

            message = mTestLooper.nextMessage();
            assertEquals(CARRIER_ID, message.what);
            assertEquals(CARRIER_ID,
                    ((WifiPseudonymManager.RetrieveRunnable) message.getCallback()).mCarrierId);
            assertNull(mTestLooper.nextMessage());
        }

        mWifiPseudonymManager.mRetrieveCallback.onFailure(CARRIER_ID,
                CarrierSpecificServiceEntitlement.REASON_TRANSIENT_FAILURE, errorDescription);
        mTestLooper.moveTimeForward(Duration.ofDays(100).toMillis()); // Move forward long enough
        assertFalse(mTestLooper.isIdle());
        assertNull(mTestLooper.nextMessage());
    }

    @Test
    public void testRetrieveCallbackFailureNonTransient() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        String errorDescription = "Authentication failed";
        mWifiPseudonymManager.mRetrieveCallback.onFailure(CARRIER_ID,
                CarrierSpecificServiceEntitlement.REASON_NON_TRANSIENT_FAILURE, errorDescription);
        mTestLooper.moveTimeForward(Duration.ofDays(100).toMillis()); // Move forward long enough
        assertFalse(mTestLooper.isIdle());
    }

    @Test
    public void testRetrieveCallbackSuccess() {
        mWifiPseudonymManager = new WifiPseudonymManager(mWifiContext, mWifiInjector, mClock,
                mTestLooper.getLooper());
        Message message;
        PseudonymInfo pseudonymInfo = new PseudonymInfo(PSEUDONYM, IMSI,
                Duration.ofDays(2).toMillis(), Instant.now().toEpochMilli());
        mWifiPseudonymManager.mRetrieveCallback.onSuccess(CARRIER_ID, pseudonymInfo);
        assertFalse(mTestLooper.isIdle());
        mTestLooper.moveTimeForward(Duration.ofDays(2).toMillis());
        assertTrue(mTestLooper.isIdle());
        message = mTestLooper.nextMessage();
        assertEquals(CARRIER_ID, message.what);
        assertEquals(CARRIER_ID,
                ((WifiPseudonymManager.RetrieveRunnable) message.getCallback()).mCarrierId);
        assertNull(mTestLooper.nextMessage());
    }
    private void setAnExpiredPseudonym(WifiPseudonymManager wifiPseudonymManager) {
        long ttl = Duration.ofDays(2).toMillis();
        PseudonymInfo pseudonymInfo = new PseudonymInfo(PSEUDONYM, IMSI, ttl,
                Instant.now().toEpochMilli() - ttl);
        wifiPseudonymManager.setPseudonymAndScheduleRefresh(CARRIER_ID, pseudonymInfo);
    }

    private void setAValidPseudonym(WifiPseudonymManager wifiPseudonymManager) {
        long ttl = Duration.ofDays(2).toMillis();
        PseudonymInfo pseudonymInfo = new PseudonymInfo(PSEUDONYM, IMSI, ttl,
                Instant.now().toEpochMilli());
        wifiPseudonymManager.setPseudonymAndScheduleRefresh(CARRIER_ID, pseudonymInfo);
    }
}
