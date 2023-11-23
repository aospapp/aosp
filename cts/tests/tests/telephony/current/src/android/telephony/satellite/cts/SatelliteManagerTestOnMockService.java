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

package android.telephony.satellite.cts;

import static com.android.internal.telephony.satellite.DatagramController.SATELLITE_ALIGN_TIMEOUT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.wifi.WifiManager;
import android.nfc.NfcAdapter;
import android.os.Build;
import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.os.SystemProperties;
import android.provider.Settings;
import android.telephony.TelephonyManager;
import android.telephony.satellite.AntennaDirection;
import android.telephony.satellite.AntennaPosition;
import android.telephony.satellite.PointingInfo;
import android.telephony.satellite.SatelliteCapabilities;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteManager;
import android.telephony.satellite.stub.SatelliteError;
import android.util.Log;
import android.uwb.UwbManager;

import androidx.test.InstrumentationRegistry;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SatelliteManagerTestOnMockService extends SatelliteManagerTestBase {
    private static final String ALLOW_MOCK_MODEM_PROPERTY = "persist.radio.allow_mock_modem";
    private static final boolean DEBUG = !"user".equals(Build.TYPE);
    private static final long TEST_SATELLITE_LISTENING_TIMEOUT_MILLIS = 100;
    private static final long TEST_SATELLITE_LISTENING_FOREVER_TIMEOUT_MILLIS = 60 * 10 * 1000;
    private static final long TEST_SATELLITE_DEVICE_ALIGN_TIMEOUT_MILLIS = 100;
    private static final long TEST_SATELLITE_DEVICE_ALIGN_FOREVER_TIMEOUT_MILLIS = 100000;

    private static MockSatelliteServiceManager sMockSatelliteServiceManager;
    private static boolean sOriginalIsSatelliteEnabled = false;
    private static boolean sOriginalIsSatelliteProvisioned = false;

    /** SatelliteCapabilities constant indicating that the radio technology is proprietary. */
    private static final Set<Integer> SUPPORTED_RADIO_TECHNOLOGIES;
    static {
        SUPPORTED_RADIO_TECHNOLOGIES = new HashSet<>();
        SUPPORTED_RADIO_TECHNOLOGIES.add(SatelliteManager.NT_RADIO_TECHNOLOGY_PROPRIETARY);
    }
    /** SatelliteCapabilities constant indicating that pointing to satellite is required. */
    private static final boolean POINTING_TO_SATELLITE_REQUIRED = true;
    /** SatelliteCapabilities constant indicating the maximum number of characters per datagram. */
    private static final int MAX_BYTES_PER_DATAGRAM = 339;
    /** SatelliteCapabilites constant antenna position map received from satellite modem. */
    private static final Map<Integer, AntennaPosition> ANTENNA_POSITION_MAP;
    static {
        ANTENNA_POSITION_MAP = new HashMap<>();
        ANTENNA_POSITION_MAP.put(SatelliteManager.DISPLAY_MODE_OPENED,
                new AntennaPosition(new AntennaDirection(1,1,1),
                        SatelliteManager.DEVICE_HOLD_POSITION_PORTRAIT));
        ANTENNA_POSITION_MAP.put(SatelliteManager.DISPLAY_MODE_CLOSED,
                new AntennaPosition(new AntennaDirection(2,2,2),
                        SatelliteManager.DEVICE_HOLD_POSITION_LANDSCAPE_LEFT));
    }

    UwbManager mUwbManager = null;
    NfcAdapter mNfcAdapter = null;
    BluetoothAdapter mBluetoothAdapter = null;
    WifiManager mWifiManager = null;
    private String mTestSatelliteModeRadios = null;
    boolean mBTInitState = false;
    boolean mWifiInitState = false;
    boolean mNfcInitState = false;
    boolean mUwbInitState = false;

    @BeforeClass
    public static void beforeAllTests() throws Exception {
        logd("beforeAllTests");

        if (!shouldTestSatelliteWithMockService()) return;

        beforeAllTestsBase();
        enforceMockModemDeveloperSetting();

        sMockSatelliteServiceManager = new MockSatelliteServiceManager(
                InstrumentationRegistry.getInstrumentation());
        assertTrue(sMockSatelliteServiceManager.connectSatelliteService());

        grantSatellitePermission();
        assertTrue(isSatelliteSupported());
        if (!isSatelliteProvisioned()) {
            logd("Provision satellite");

            SatelliteProvisionStateCallbackTest satelliteProvisionStateCallback =
                    new SatelliteProvisionStateCallbackTest();
            long registerError = sSatelliteManager.registerForSatelliteProvisionStateChanged(
                    getContext().getMainExecutor(), satelliteProvisionStateCallback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerError);

            assertTrue(provisionSatellite());

            assertTrue(satelliteProvisionStateCallback.waitUntilResult(1));
            assertTrue(satelliteProvisionStateCallback.isProvisioned);
            sSatelliteManager.unregisterForSatelliteProvisionStateChanged(
                    satelliteProvisionStateCallback);
        }
        revokeSatellitePermission();
    }

    @AfterClass
    public static void afterAllTests() {
        logd("afterAllTests");
        if (!shouldTestSatelliteWithMockService()) return;

        assertTrue(sMockSatelliteServiceManager.restoreSatelliteServicePackageName());
        sMockSatelliteServiceManager = null;
        afterAllTestsBase();
    }

    @Before
    public void setUp() throws Exception {
        logd("setUp");
        if (!shouldTestSatelliteWithMockService()) return;
        assumeTrue(sMockSatelliteServiceManager != null);

        sMockSatelliteServiceManager.setErrorCode(SatelliteError.ERROR_NONE);
        sMockSatelliteServiceManager.setWaitToSend(false);
        sMockSatelliteServiceManager.setShouldRespondTelephony(true);

        // Initialize radio state
        mBTInitState = false;
        mWifiInitState = false;
        mNfcInitState = false;
        mUwbInitState = false;
        mTestSatelliteModeRadios = "";

        SatelliteModeRadiosUpdater satelliteRadiosModeUpdater =
                new SatelliteModeRadiosUpdater(getContext());
        assertTrue(satelliteRadiosModeUpdater.setSatelliteModeRadios(""));

        grantSatellitePermission();
        if (!isSatelliteEnabled()) {
            logd("Enable satellite");

            SatelliteStateCallbackTest callback = new SatelliteStateCallbackTest();
            long registerResult = sSatelliteManager.registerForSatelliteModemStateChanged(
                    getContext().getMainExecutor(), callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));

            requestSatelliteEnabled(true, EXTERNAL_DEPENDENT_TIMEOUT);

            assertTrue(callback.waitUntilResult(1));
            assertTrue(isSatelliteEnabled());
            sSatelliteManager.unregisterForSatelliteModemStateChanged(callback);
        }
        revokeSatellitePermission();
    }

    @After
    public void tearDown() {
        logd("tearDown");
        if (!shouldTestSatelliteWithMockService()) return;
        assumeTrue(sMockSatelliteServiceManager != null);
        sMockSatelliteServiceManager.setErrorCode(SatelliteError.ERROR_NONE);
        sMockSatelliteServiceManager.setWaitToSend(false);
        sMockSatelliteServiceManager.setShouldRespondTelephony(true);

        grantSatellitePermission();
        if (isSatelliteEnabled()) {
            SatelliteStateCallbackTest callback = new SatelliteStateCallbackTest();
            long registerResult = sSatelliteManager.registerForSatelliteModemStateChanged(
                    getContext().getMainExecutor(), callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));

            // Disable satellite modem to clean up all pending resources and reset telephony states.
            requestSatelliteEnabled(false);
            assertTrue(callback.waitUntilModemOff());
            assertFalse(isSatelliteEnabled());

            sSatelliteManager.unregisterForSatelliteModemStateChanged(callback);
        }
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        sMockSatelliteServiceManager.clearMockPointingUiActivityStatusChanges();
        sMockSatelliteServiceManager.clearListeningEnabledList();
        revokeSatellitePermission();
        waitFor(500);
    }

    @Test
    public void testProvisionSatelliteService() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testProvisionSatelliteService: start");
        grantSatellitePermission();

        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        SatelliteProvisionStateCallbackTest satelliteProvisionStateCallback =
                new SatelliteProvisionStateCallbackTest();
        long registerError = sSatelliteManager.registerForSatelliteProvisionStateChanged(
                getContext().getMainExecutor(), satelliteProvisionStateCallback);
        assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerError);

        if (isSatelliteProvisioned()) {
            logd("testProvisionSatelliteService: dreprovision");
            assertTrue(deprovisionSatellite());
            assertTrue(satelliteProvisionStateCallback.waitUntilResult(1));
            assertFalse(satelliteProvisionStateCallback.isProvisioned);
        }

        logd("testProvisionSatelliteService: provision and cancel");
        satelliteProvisionStateCallback.clearProvisionedStates();
        CancellationSignal cancellationSignal = new CancellationSignal();
        String mText = "This is test provision data.";
        byte[] testProvisionData = mText.getBytes();
        sSatelliteManager.provisionSatelliteService(TOKEN, testProvisionData, cancellationSignal,
                getContext().getMainExecutor(), error::offer);
        cancellationSignal.cancel();

        Integer errorCode;
        try {
            errorCode = error.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testProvisionSatelliteService: Got InterruptedException ex=" + ex);
            return;
        }
        assertNotNull(errorCode);
        assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, (long) errorCode);

        // Provision succeeded and then got canceled - deprovisioned
        assertTrue(satelliteProvisionStateCallback.waitUntilResult(2));
        assertEquals(2, satelliteProvisionStateCallback.getTotalCountOfProvisionedStates());
        assertTrue(satelliteProvisionStateCallback.getProvisionedState(0));
        assertFalse(satelliteProvisionStateCallback.getProvisionedState(1));
        assertFalse(satelliteProvisionStateCallback.isProvisioned);
        assertFalse(isSatelliteProvisioned());

        logd("testProvisionSatelliteService: restore provision state");
        assertTrue(provisionSatellite());
        assertTrue(satelliteProvisionStateCallback.waitUntilResult(1));
        assertTrue(satelliteProvisionStateCallback.isProvisioned);
        sSatelliteManager.unregisterForSatelliteProvisionStateChanged(
                satelliteProvisionStateCallback);

        revokeSatellitePermission();
    }

    @Test
    public void testSatelliteModemStateChanged() {
        if (!shouldTestSatelliteWithMockService()) return;

        grantSatellitePermission();

        assertTrue(isSatelliteProvisioned());

        SatelliteStateCallbackTest callback = new SatelliteStateCallbackTest();
        boolean originalEnabledState = isSatelliteEnabled();
        boolean registerCallback = false;
        if (originalEnabledState) {
            registerCallback = true;

            long registerResult = sSatelliteManager.registerForSatelliteModemStateChanged(
                    getContext().getMainExecutor(), callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));

            requestSatelliteEnabled(false);

            assertTrue(callback.waitUntilModemOff());
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
            assertFalse(isSatelliteEnabled());
            callback.clearModemStates();
        }
        if (!registerCallback) {
            long registerResult = sSatelliteManager
                    .registerForSatelliteModemStateChanged(getContext().getMainExecutor(),
                            callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
        }

        assertTrue(sMockSatelliteServiceManager.connectSatelliteGatewayService());
        assertTrue(sMockSatelliteServiceManager.overrideSatellitePointingUiClassName());
        sMockSatelliteServiceManager.clearMockPointingUiActivityStatusChanges();
        requestSatelliteEnabled(true);

        assertTrue(callback.waitUntilResult(1));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE, callback.modemState);
        assertTrue(isSatelliteEnabled());
        assertTrue(sMockSatelliteServiceManager.waitForRemoteSatelliteGatewayServiceConnected(1));
        assertTrue(sMockSatelliteServiceManager.waitForEventMockPointingUiActivityStarted(1));
        assertTrue(sMockSatelliteServiceManager.restoreSatellitePointingUiClassName());

        SatelliteStateCallbackTest
                callback1 = new SatelliteStateCallbackTest();
        long registerResult = sSatelliteManager
                .registerForSatelliteModemStateChanged(getContext().getMainExecutor(), callback1);
        assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
        assertTrue(callback1.waitUntilResult(1));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE, callback1.modemState);
        sSatelliteManager.unregisterForSatelliteModemStateChanged(callback);

        assertTrue(sMockSatelliteServiceManager.setSatelliteListeningTimeoutDuration(
                TEST_SATELLITE_LISTENING_TIMEOUT_MILLIS));

        // Verify state transitions: IDLE -> TRANSFERRING -> LISTENING -> IDLE
        sendSatelliteDatagramWithSuccessfulResult(callback1, true);

        assertTrue(sMockSatelliteServiceManager.setSatelliteListeningTimeoutDuration(
                TEST_SATELLITE_LISTENING_FOREVER_TIMEOUT_MILLIS));

        // Move to LISTENING state
        sendSatelliteDatagramWithSuccessfulResult(callback1, false);

        // Verify state transitions: LISTENING -> TRANSFERRING -> LISTENING
        receiveSatelliteDatagramWithSuccessfulResult(callback1);

        // Verify state transitions: LISTENING -> TRANSFERRING -> IDLE
        sendSatelliteDatagramWithFailedResult(callback1);

        // Move to LISTENING state
        sendSatelliteDatagramWithSuccessfulResult(callback1, false);

        // Verify state transitions: LISTENING -> TRANSFERRING -> IDLE
        receiveSatelliteDatagramWithFailedResult(callback1);

        callback1.clearModemStates();
        requestSatelliteEnabled(false);
        assertFalse(callback.waitUntilResult(1));
        assertTrue(callback1.waitUntilModemOff());
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback1.modemState);
        assertFalse(isSatelliteEnabled());
        assertTrue(
                sMockSatelliteServiceManager.waitForRemoteSatelliteGatewayServiceDisconnected(1));

        sSatelliteManager.unregisterForSatelliteModemStateChanged(callback1);
        assertTrue(sMockSatelliteServiceManager.setSatelliteListeningTimeoutDuration(0));
        assertTrue(sMockSatelliteServiceManager.restoreSatelliteGatewayServicePackageName());

        revokeSatellitePermission();
    }

    @Test
    public void testSatelliteEnableErrorHandling() {
        if (!shouldTestSatelliteWithMockService()) return;
        assumeTrue(sTelephonyManager != null);

        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        SatelliteStateCallbackTest callback = new SatelliteStateCallbackTest();
        boolean originalEnabledState = isSatelliteEnabled();
        boolean registerCallback = false;
        if (originalEnabledState) {
            registerCallback = true;

            long registerResult = sSatelliteManager.registerForSatelliteModemStateChanged(
                    getContext().getMainExecutor(), callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));

            requestSatelliteEnabled(false);

            assertTrue(callback.waitUntilModemOff());
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
            assertFalse(isSatelliteEnabled());
            callback.clearModemStates();
        }
        if (!registerCallback) {
            long registerResult = sSatelliteManager
                    .registerForSatelliteModemStateChanged(getContext().getMainExecutor(),
                            callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
        }

        waitFor(500);
        requestSatelliteEnabled(true, true, SatelliteManager.SATELLITE_ERROR_NONE);
        assertTrue(callback.waitUntilResult(1));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE, callback.modemState);
        assertTrue(isSatelliteEnabled());

        requestSatelliteEnabled(true, true, SatelliteManager.SATELLITE_ERROR_NONE);
        requestSatelliteEnabled(true, false, SatelliteManager.SATELLITE_INVALID_ARGUMENTS);

        turnRadioOff();
        grantSatellitePermission();
        assertTrue(callback.waitUntilResult(1));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
        assertFalse(isSatelliteEnabled());

        requestSatelliteEnabled(true, true, SatelliteManager.SATELLITE_INVALID_MODEM_STATE);
        requestSatelliteEnabled(false);

        turnRadioOn();
        grantSatellitePermission();
        assertFalse(callback.waitUntilResult(1));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
        assertFalse(isSatelliteEnabled());

        requestSatelliteEnabled(true, true, SatelliteManager.SATELLITE_ERROR_NONE);
        assertTrue(callback.waitUntilResult(1));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE, callback.modemState);
        assertTrue(isSatelliteEnabled());

        callback.clearModemStates();
        requestSatelliteEnabled(false);
        assertTrue(callback.waitUntilModemOff());
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
        assertFalse(isSatelliteEnabled());

        sSatelliteManager.unregisterForSatelliteModemStateChanged(callback);
        revokeSatellitePermission();
    }

    @Test
    public void testSatelliteDatagramReceivedAck() {
        if (!shouldTestSatelliteWithMockService()) return;

        grantSatellitePermission();

        assertTrue(isSatelliteProvisioned());

        SatelliteDatagramCallbackTest satelliteDatagramCallback =
                new SatelliteDatagramCallbackTest();
        sSatelliteManager.registerForSatelliteDatagram(
                getContext().getMainExecutor(), satelliteDatagramCallback);

        String receivedText = "This is a test datagram message from satellite";
        android.telephony.satellite.stub.SatelliteDatagram receivedDatagram =
                new android.telephony.satellite.stub.SatelliteDatagram();
        receivedDatagram.data = receivedText.getBytes();
        sMockSatelliteServiceManager.sendOnSatelliteDatagramReceived(receivedDatagram, 0);

        assertTrue(satelliteDatagramCallback.waitUntilResult(1));
        assertArrayEquals(satelliteDatagramCallback.mDatagram.getSatelliteDatagram(),
                receivedText.getBytes());

        sSatelliteManager.unregisterForSatelliteDatagram(satelliteDatagramCallback);
        revokeSatellitePermission();
    }

    @Test
    public void  testRequestSatelliteCapabilities() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testRequestSatelliteCapabilities");
        grantSatellitePermission();

        assertTrue(isSatelliteProvisioned());

        final AtomicReference<SatelliteCapabilities> capabilities = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<SatelliteCapabilities, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(SatelliteCapabilities result) {
                        logd("testRequestSatelliteCapabilities: onResult");
                        capabilities.set(result);

                        assertNotNull(result);
                        assertNotNull(result.getSupportedRadioTechnologies());
                        assertThat(SUPPORTED_RADIO_TECHNOLOGIES)
                                .isEqualTo(result.getSupportedRadioTechnologies());
                        assertThat(POINTING_TO_SATELLITE_REQUIRED)
                                .isEqualTo(result.isPointingRequired());
                        assertThat(MAX_BYTES_PER_DATAGRAM)
                                .isEqualTo(result.getMaxBytesPerOutgoingDatagram());
                        assertNotNull(result.getAntennaPositionMap());
                        assertThat(ANTENNA_POSITION_MAP).isEqualTo(result.getAntennaPositionMap());
                    }

                    @Override
                    public void onError(SatelliteManager.SatelliteException exception) {
                        logd("testRequestSatelliteCapabilities: onError");
                        errorCode.set(exception.getErrorCode());
                    }
                };

        sSatelliteManager.requestSatelliteCapabilities(getContext().getMainExecutor(), receiver);

        revokeSatellitePermission();
    }

    @Test
    public void testSendSatelliteDatagram_success() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testSendSatelliteDatagram_success");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        for (int i = 0; i < 5; i++) {
            logd("testSendSatelliteDatagram_success: moveToSendingState");
            assertTrue(isSatelliteEnabled());
            moveToSendingState();

            logd("testSendSatelliteDatagram_success: Disable satellite");
            SatelliteStateCallbackTest callback = new SatelliteStateCallbackTest();
            long registerResult = sSatelliteManager.registerForSatelliteModemStateChanged(
                    getContext().getMainExecutor(), callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));

            SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                    startTransmissionUpdates();
            requestSatelliteEnabled(false);
            assertTrue(callback.waitUntilModemOff());
            assertFalse(isSatelliteEnabled());
            callback.clearModemStates();

            // Datagram transfer state should change from SENDING to FAILED and then IDLE.
            assertTrue(transmissionUpdateCallback.waitUntilOnSendDatagramStateChanged(2));
            assertThat(transmissionUpdateCallback.getNumOfSendDatagramStateChanges()).isEqualTo(2);
            assertThat(transmissionUpdateCallback.getSendDatagramStateChange(0)).isEqualTo(
                    new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED,
                            1, SatelliteManager.SATELLITE_REQUEST_ABORTED));
            assertThat(transmissionUpdateCallback.getSendDatagramStateChange(1)).isEqualTo(
                    new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                            0, SatelliteManager.SATELLITE_ERROR_NONE));
            stopTransmissionUpdates(transmissionUpdateCallback);
            waitFor(500);

            logd("testSendSatelliteDatagram_success: Enable satellite");
            requestSatelliteEnabled(true);
            assertTrue(callback.waitUntilResult(1));
            assertTrue(isSatelliteEnabled());
            sSatelliteManager.unregisterForSatelliteModemStateChanged(callback);

            logd("testSendSatelliteDatagram_success: sendSatelliteDatagramSuccess");
            sendSatelliteDatagramSuccess();
        }
        revokeSatellitePermission();
    }

    @Test
    public void testSendSatelliteDatagram_failure() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testSendSatelliteDatagram_failure");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest callback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, callback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_failure: Got InterruptedException in waiting"
                    + " for the startSatelliteTransmissionUpdates result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        // Send satellite datagram
        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        callback.clearSendDatagramStateChanges();
        sMockSatelliteServiceManager.setErrorCode(SatelliteError.SATELLITE_ERROR);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener::offer);

        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_failure: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR);

        /**
         * Send datagram transfer state should have the following transitions:
         * 1) SENDING to SENDING_FAILED
         * 2) SENDING_FAILED to IDLE
         */
        int expectedNumOfEvents = 3;
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(expectedNumOfEvents));
        assertThat(callback.getNumOfSendDatagramStateChanges()).isEqualTo(expectedNumOfEvents);
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED,
                        0, SatelliteManager.SATELLITE_ERROR));
        assertThat(callback.getSendDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        callback.clearSendDatagramStateChanges();
        sSatelliteManager.stopSatelliteTransmissionUpdates(callback, getContext().getMainExecutor(),
                resultListener::offer);
        revokeSatellitePermission();
    }

    @Test
    public void testSendMultipleSatelliteDatagrams_success() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testSendMultipleSatelliteDatagrams_success");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest callback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, callback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendMultipleSatelliteDatagrams_success: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        callback.clearSendDatagramStateChanges();
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();

        // Wait to process datagrams so that datagrams are added to pending list.
        sMockSatelliteServiceManager.setWaitToSend(true);

        // Send three datagrams to observe how pendingCount is updated
        // after processing one datagram at a time.
        LinkedBlockingQueue<Integer> resultListener1 = new LinkedBlockingQueue<>(1);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener1::offer);
        LinkedBlockingQueue<Integer> resultListener2 = new LinkedBlockingQueue<>(1);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener2::offer);
        LinkedBlockingQueue<Integer> resultListener3 = new LinkedBlockingQueue<>(1);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener3::offer);

        assertTrue(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));

        // Send first datagram: SENDING to SENDING_SUCCESS
        assertTrue(sMockSatelliteServiceManager.sendSavedDatagram());

        try {
            errorCode = resultListener1.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendMultipleSatelliteDatagrams_success: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        int expectedNumOfEvents = 2;
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(expectedNumOfEvents));
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        // Pending count is 2 as there are 2 datagrams to be sent.
        assertThat(callback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS,
                        2, SatelliteManager.SATELLITE_ERROR_NONE));

        assertTrue(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));

        // Send second datagram: SENDING to SENDING_SUCCESS
        // callback.clearSendDatagramStateChanges();
        assertTrue(sMockSatelliteServiceManager.sendSavedDatagram());

        try {
            errorCode = resultListener2.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendMultipleSatelliteDatagrams_success: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        expectedNumOfEvents = 2;
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(expectedNumOfEvents));
        assertThat(callback.getSendDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        2, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(3)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));

        assertTrue(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));

        // Send third datagram: SENDING - SENDING_SUCCESS - IDLE
        assertTrue(sMockSatelliteServiceManager.sendSavedDatagram());

        try {
            errorCode = resultListener3.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendMultipleSatelliteDatagrams_success: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        expectedNumOfEvents = 3;
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(expectedNumOfEvents));
        assertThat(callback.getSendDatagramStateChange(4)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(5)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(6)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        callback.clearSendDatagramStateChanges();
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        sMockSatelliteServiceManager.setWaitToSend(false);
        sSatelliteManager.stopSatelliteTransmissionUpdates(callback, getContext().getMainExecutor(),
                resultListener::offer);
        revokeSatellitePermission();
    }

    @Test
    public void testSendMultipleSatelliteDatagrams_failure() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testSendMultipleSatelliteDatagrams_failure");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest callback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, callback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendMultipleSatelliteDatagrams_failure: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        callback.clearSendDatagramStateChanges();
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();

        // Wait to process datagrams so that datagrams are added to pending list.
        sMockSatelliteServiceManager.setWaitToSend(true);

        // Send three datagrams to observe how pendingCount is updated
        // after processing one datagram at a time.
        LinkedBlockingQueue<Integer> resultListener1 = new LinkedBlockingQueue<>(1);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener1::offer);
        LinkedBlockingQueue<Integer> resultListener2 = new LinkedBlockingQueue<>(1);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener2::offer);
        LinkedBlockingQueue<Integer> resultListener3 = new LinkedBlockingQueue<>(1);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener3::offer);

        assertTrue(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));

        // Set error and send first datagram: SENDING to SENDING_FAILED
        sMockSatelliteServiceManager.setErrorCode(SatelliteError.SATELLITE_ERROR);
        assertTrue(sMockSatelliteServiceManager.sendSavedDatagram());

        try {
            errorCode = resultListener1.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendMultipleSatelliteDatagrams_success: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR);

        try {
            errorCode = resultListener2.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendMultipleSatelliteDatagrams_success: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_REQUEST_ABORTED);

        try {
            errorCode = resultListener3.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendMultipleSatelliteDatagrams_success: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_REQUEST_ABORTED);

        int expectedNumOfEvents = 3;
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(expectedNumOfEvents));
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        // Pending count is 2 as there are 2 datagrams to be sent.
        assertThat(callback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED,
                        2, SatelliteManager.SATELLITE_ERROR));
        assertThat(callback.getSendDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        callback.clearSendDatagramStateChanges();
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        sMockSatelliteServiceManager.setWaitToSend(false);
        sSatelliteManager.stopSatelliteTransmissionUpdates(callback, getContext().getMainExecutor(),
                resultListener::offer);
        revokeSatellitePermission();
    }


    @Test
    public void testReceiveSatelliteDatagram() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testReceiveSatelliteDatagram");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        for (int i = 0; i < 5; i++) {
            logd("testReceiveSatelliteDatagram: moveToReceivingState");
            assertTrue(isSatelliteEnabled());
            moveToReceivingState();

            logd("testReceiveSatelliteDatagram: Disable satellite");
            SatelliteStateCallbackTest callback = new SatelliteStateCallbackTest();
            long registerResult = sSatelliteManager.registerForSatelliteModemStateChanged(
                    getContext().getMainExecutor(), callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));

            SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                    startTransmissionUpdates();
            requestSatelliteEnabled(false);
            assertTrue(callback.waitUntilModemOff());
            assertFalse(isSatelliteEnabled());
            callback.clearModemStates();

            // Datagram transfer state should change from RECEIVING to IDLE.
            assertTrue(transmissionUpdateCallback
                    .waitUntilOnReceiveDatagramStateChanged(2));
            assertThat(transmissionUpdateCallback.getNumOfReceiveDatagramStateChanges())
                    .isEqualTo(2);
            assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(0)).isEqualTo(
                    new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED,
                            0, SatelliteManager.SATELLITE_REQUEST_ABORTED));
            assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(1)).isEqualTo(
                    new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                            0, SatelliteManager.SATELLITE_ERROR_NONE));
            stopTransmissionUpdates(transmissionUpdateCallback);
            waitFor(500);

            logd("testReceiveSatelliteDatagram: Enable satellite");
            requestSatelliteEnabled(true);
            assertTrue(callback.waitUntilResult(1));
            assertTrue(isSatelliteEnabled());
            sSatelliteManager.unregisterForSatelliteModemStateChanged(callback);

            logd("testReceiveSatelliteDatagram: receiveSatelliteDatagramSuccess");
            receiveSatelliteDatagramSuccess();
        }
        revokeSatellitePermission();
    }

    @Test
    public void testReceiveMultipleSatelliteDatagrams() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testReceiveMultipleSatelliteDatagrams");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, transmissionUpdateCallback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testReceiveMultipleSatelliteDatagrams: Got InterruptedException in waiting"
                    + " for the startSatelliteTransmissionUpdates result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        SatelliteDatagramCallbackTest satelliteDatagramCallback =
                new SatelliteDatagramCallbackTest();
        sSatelliteManager.registerForSatelliteDatagram(
                getContext().getMainExecutor(), satelliteDatagramCallback);

        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        sMockSatelliteServiceManager.sendOnPendingDatagrams();
        assertTrue(sMockSatelliteServiceManager.waitForEventOnPollPendingSatelliteDatagrams(1));

        // Receive first datagram: Datagram state changes from RECEIVING to RECEIVE_SUCCESS
        String receivedText = "This is a test datagram message from satellite";
        android.telephony.satellite.stub.SatelliteDatagram receivedDatagram =
                new android.telephony.satellite.stub.SatelliteDatagram();
        receivedDatagram.data = receivedText.getBytes();
        sMockSatelliteServiceManager.sendOnSatelliteDatagramReceived(receivedDatagram, 2);
        assertTrue(satelliteDatagramCallback.waitUntilResult(1));
        assertArrayEquals(satelliteDatagramCallback.mDatagram.getSatelliteDatagram(),
                receivedText.getBytes());
        int expectedNumOfEvents = 2;
        assertTrue(transmissionUpdateCallback
                .waitUntilOnReceiveDatagramStateChanged(expectedNumOfEvents));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS,
                        2, SatelliteManager.SATELLITE_ERROR_NONE));


        // Receive second datagram: Datagram state changes from RECEIVING to RECEIVE_SUCCESS
        assertTrue(sMockSatelliteServiceManager.waitForEventOnPollPendingSatelliteDatagrams(1));
        sMockSatelliteServiceManager.sendOnSatelliteDatagramReceived(receivedDatagram, 1);
        assertTrue(satelliteDatagramCallback.waitUntilResult(1));
        assertArrayEquals(satelliteDatagramCallback.mDatagram.getSatelliteDatagram(),
                receivedText.getBytes());
        expectedNumOfEvents = 2;
        assertTrue(transmissionUpdateCallback
                .waitUntilOnReceiveDatagramStateChanged(2));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
                        2, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(3)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));

        // Receive third datagram: Datagram state changes from RECEIVING - RECEIVE_SUCCESS - IDLE
        assertTrue(sMockSatelliteServiceManager.waitForEventOnPollPendingSatelliteDatagrams(1));
        sMockSatelliteServiceManager.sendOnSatelliteDatagramReceived(receivedDatagram, 0);
        assertTrue(satelliteDatagramCallback.waitUntilResult(1));
        assertArrayEquals(satelliteDatagramCallback.mDatagram.getSatelliteDatagram(),
                receivedText.getBytes());
        expectedNumOfEvents = 3;
        assertTrue(transmissionUpdateCallback
                .waitUntilOnReceiveDatagramStateChanged(3));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(4)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(5)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(6)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        sSatelliteManager.stopSatelliteTransmissionUpdates(transmissionUpdateCallback,
                getContext().getMainExecutor(), resultListener::offer);
        sSatelliteManager.unregisterForSatelliteDatagram(satelliteDatagramCallback);
        revokeSatellitePermission();
    }

    @Test
    public void testReceiveSatellitePositionUpdate() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testReceiveSatellitePositionUpdate");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, transmissionUpdateCallback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testReceiveSatellitePositionUpdate: Got InterruptedException in waiting"
                    + " for the startSatelliteTransmissionUpdates result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        transmissionUpdateCallback.clearPointingInfo();
        android.telephony.satellite.stub.PointingInfo pointingInfo =
                new android.telephony.satellite.stub.PointingInfo();
        pointingInfo.satelliteAzimuth = 10.5f;
        pointingInfo.satelliteElevation = 30.23f;
        PointingInfo expectedPointingInfo = new PointingInfo(10.5f, 30.23f);
        sMockSatelliteServiceManager.sendOnSatellitePositionChanged(pointingInfo);
        assertTrue(transmissionUpdateCallback.waitUntilOnSatellitePositionChanged(1));
        assertThat(transmissionUpdateCallback.mPointingInfo.getSatelliteAzimuthDegrees())
                .isEqualTo(expectedPointingInfo.getSatelliteAzimuthDegrees());
        assertThat(transmissionUpdateCallback.mPointingInfo.getSatelliteElevationDegrees())
                .isEqualTo(expectedPointingInfo.getSatelliteElevationDegrees());

        transmissionUpdateCallback.clearPointingInfo();
        sSatelliteManager.stopSatelliteTransmissionUpdates(transmissionUpdateCallback,
                getContext().getMainExecutor(), resultListener::offer);
        revokeSatellitePermission();
    }

    @Test
    public void testReceiveMultipleSatellitePositionUpdates() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testReceiveMultipleSatellitePositionUpdates");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, transmissionUpdateCallback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testReceiveMultipleSatellitePositionUpdates: Got InterruptedException in waiting"
                    + " for the startSatelliteTransmissionUpdates result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        // Receive first position update
        transmissionUpdateCallback.clearPointingInfo();
        android.telephony.satellite.stub.PointingInfo pointingInfo =
                new android.telephony.satellite.stub.PointingInfo();
        pointingInfo.satelliteAzimuth = 10.5f;
        pointingInfo.satelliteElevation = 30.23f;
        PointingInfo expectedPointingInfo = new PointingInfo(10.5f, 30.23f);
        sMockSatelliteServiceManager.sendOnSatellitePositionChanged(pointingInfo);
        assertTrue(transmissionUpdateCallback.waitUntilOnSatellitePositionChanged(1));
        assertThat(transmissionUpdateCallback.mPointingInfo.getSatelliteAzimuthDegrees())
                .isEqualTo(expectedPointingInfo.getSatelliteAzimuthDegrees());
        assertThat(transmissionUpdateCallback.mPointingInfo.getSatelliteElevationDegrees())
                .isEqualTo(expectedPointingInfo.getSatelliteElevationDegrees());

        // Receive second position update
        transmissionUpdateCallback.clearPointingInfo();
        pointingInfo.satelliteAzimuth = 100;
        pointingInfo.satelliteElevation = 120;
        expectedPointingInfo = new PointingInfo(100, 120);
        sMockSatelliteServiceManager.sendOnSatellitePositionChanged(pointingInfo);
        assertTrue(transmissionUpdateCallback.waitUntilOnSatellitePositionChanged(1));
        assertThat(transmissionUpdateCallback.mPointingInfo.getSatelliteAzimuthDegrees())
                .isEqualTo(expectedPointingInfo.getSatelliteAzimuthDegrees());
        assertThat(transmissionUpdateCallback.mPointingInfo.getSatelliteElevationDegrees())
                .isEqualTo(expectedPointingInfo.getSatelliteElevationDegrees());

        transmissionUpdateCallback.clearPointingInfo();
        sSatelliteManager.stopSatelliteTransmissionUpdates(transmissionUpdateCallback,
                getContext().getMainExecutor(), resultListener::offer);
        revokeSatellitePermission();
    }

    @Test
    public void testSendAndReceiveSatelliteDatagram_DemoMode_success() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testSendSatelliteDatagram_DemoMode_success");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        String mText = "This is a test datagram message from user";
        for (int i = 0; i < 5; i++) {
            logd("testSendSatelliteDatagram_DemoMode_success: moveToSendingState");
            assertTrue(isSatelliteEnabled());
            moveToSendingState();

            logd("testSendSatelliteDatagram_DemoMode_success: Disable satellite");
            SatelliteStateCallbackTest stateCallback = new SatelliteStateCallbackTest();
            sSatelliteManager.registerForSatelliteModemStateChanged(
                    getContext().getMainExecutor(), stateCallback);
            assertTrue(stateCallback.waitUntilResult(1));

            SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                    startTransmissionUpdates();
            requestSatelliteEnabled(false);
            assertTrue(stateCallback.waitUntilModemOff());
            assertFalse(isSatelliteEnabled());
            stateCallback.clearModemStates();

            // Datagram transfer state should change from SENDING to FAILED and then IDLE.
            assertTrue(transmissionUpdateCallback.waitUntilOnSendDatagramStateChanged(2));
            assertThat(transmissionUpdateCallback.getNumOfSendDatagramStateChanges()).isEqualTo(2);
            assertThat(transmissionUpdateCallback.getSendDatagramStateChange(0)).isEqualTo(
                    new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED,
                            1, SatelliteManager.SATELLITE_REQUEST_ABORTED));
            assertThat(transmissionUpdateCallback.getSendDatagramStateChange(1)).isEqualTo(
                    new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                            0, SatelliteManager.SATELLITE_ERROR_NONE));
            stopTransmissionUpdates(transmissionUpdateCallback);
            waitFor(500);

            logd("testSendSatelliteDatagram_DemoMode_success: Enable satellite");
            stateCallback.clearModemStates();
            requestSatelliteEnabledForDemoMode(true);
            assertTrue(stateCallback.waitUntilResult(1));
            assertTrue(isSatelliteEnabled());
            sSatelliteManager.unregisterForSatelliteModemStateChanged(stateCallback);

            logd("testSendSatelliteDatagram_DemoMode_success: sendSatelliteDatagramSuccess");
            sendSatelliteDatagramDemoModeSuccess(mText);

            // test pollPendingSatelliteDatagram for demo mode
            sSatelliteManager.onDeviceAlignedWithSatellite(true);
            transmissionUpdateCallback = startTransmissionUpdates();
            SatelliteDatagramCallbackTest datagramCallback = new SatelliteDatagramCallbackTest();
            assertTrue(SatelliteManager.SATELLITE_ERROR_NONE
                    == sSatelliteManager.registerForSatelliteDatagram(
                            getContext().getMainExecutor(), datagramCallback));

            LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
            sSatelliteManager.pollPendingSatelliteDatagrams(getContext().getMainExecutor(),
                    resultListener::offer);

            assertTrue(datagramCallback.waitUntilResult(1));

            // Because pending count is 0, datagram transfer state changes from
            // IDLE -> RECEIVING -> RECEIVE_SUCCESS -> IDLE.
            int expectedNumOfEvents = 3;
            assertTrue(transmissionUpdateCallback
                    .waitUntilOnReceiveDatagramStateChanged(expectedNumOfEvents));
            assertThat(transmissionUpdateCallback.getNumOfReceiveDatagramStateChanges())
                    .isEqualTo(expectedNumOfEvents);
            assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(0)).isEqualTo(
                    new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
                            0, SatelliteManager.SATELLITE_ERROR_NONE));
            assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(1)).isEqualTo(
                    new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS,
                            0, SatelliteManager.SATELLITE_ERROR_NONE));
            assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(2)).isEqualTo(
                    new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                            SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                            0, SatelliteManager.SATELLITE_ERROR_NONE));
            transmissionUpdateCallback.clearReceiveDatagramStateChanges();
            stopTransmissionUpdates(transmissionUpdateCallback);

            // Because demo mode is on, the received datagram should be the same as the
            // last sent datagram
            assertTrue(Arrays.equals(
                    datagramCallback.mDatagram.getSatelliteDatagram(), mText.getBytes()));
            sSatelliteManager.unregisterForSatelliteDatagram(datagramCallback);
        }

        sSatelliteManager.onDeviceAlignedWithSatellite(false);
        revokeSatellitePermission();
    }

    @Test
    public void testSendSatelliteDatagram_DemoMode_failure() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testSendSatelliteDatagram_DemoMode_failure");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        SatelliteStateCallbackTest stateCallback = new SatelliteStateCallbackTest();
        sSatelliteManager.registerForSatelliteModemStateChanged(
                getContext().getMainExecutor(), stateCallback);
        assertTrue(stateCallback.waitUntilResult(1));

        // Enable satellite with demo mode on
        if (isSatelliteEnabled()) {
            requestSatelliteEnabled(false);
            assertTrue(stateCallback.waitUntilModemOff());
            stateCallback.clearModemStates();
            waitFor(500);
        }
        requestSatelliteEnabledForDemoMode(true);
        assertTrue(stateCallback.waitUntilResult(1));

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest callback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, callback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_DemoMode_failure: Got InterruptedException in waiting"
                    + " for the startSatelliteTransmissionUpdates result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        // Send satellite datagram
        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        callback.clearSendDatagramStateChanges();
        sMockSatelliteServiceManager.setErrorCode(SatelliteError.SATELLITE_ERROR);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener::offer);

        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_DemoMode_failure: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR);

        /**
         * Send datagram transfer state should have the following transitions:
         * 1) IDLE to SENDING
         * 2) SENDING to SENDING_FAILED
         * 3) SENDING_FAILED to IDLE
         */
        int expectedNumOfEvents = 3;
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(expectedNumOfEvents));
        assertThat(callback.getNumOfSendDatagramStateChanges()).isEqualTo(expectedNumOfEvents);
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED,
                        0, SatelliteManager.SATELLITE_ERROR));
        assertThat(callback.getSendDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        callback.clearSendDatagramStateChanges();
        sSatelliteManager.stopSatelliteTransmissionUpdates(callback, getContext().getMainExecutor(),
                resultListener::offer);
        sSatelliteManager.unregisterForSatelliteModemStateChanged(stateCallback);
        revokeSatellitePermission();
    }

    @Test
    public void testSatelliteModeRadios() {
        if (!shouldTestSatellite()) return;

        logd("testSatelliteModeRadios: start");
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.SATELLITE_COMMUNICATION,
                        Manifest.permission.WRITE_SECURE_SETTINGS);
        assertTrue(isSatelliteProvisioned());

        SatelliteStateCallbackTest stateCallback = new SatelliteStateCallbackTest();
        sSatelliteManager.registerForSatelliteModemStateChanged(
                getContext().getMainExecutor(), stateCallback);
        assertTrue(stateCallback.waitUntilResult(1));

        boolean originalEnabledState = isSatelliteEnabled();
        if (originalEnabledState) {
            requestSatelliteEnabled(false);
            assertTrue(stateCallback.waitUntilModemOff());
            assertSatelliteEnabledInSettings(false);
            stateCallback.clearModemStates();
            waitFor(500);
        }

        // Get satellite mode radios
        String originalSatelliteModeRadios =  Settings.Global.getString(
                getContext().getContentResolver(), Settings.Global.SATELLITE_MODE_RADIOS);
        logd("originalSatelliteModeRadios: " + originalSatelliteModeRadios);
        SatelliteModeRadiosUpdater satelliteRadiosModeUpdater =
                new SatelliteModeRadiosUpdater(getContext());

        try {
            identifyRadiosSensitiveToSatelliteMode();
            mTestSatelliteModeRadios = "";
            logd("test satelliteModeRadios: " + mTestSatelliteModeRadios);
            assertTrue(satelliteRadiosModeUpdater.setSatelliteModeRadios(mTestSatelliteModeRadios));

            // Enable Satellite and check whether all radios are disabled
            requestSatelliteEnabled(true, EXTERNAL_DEPENDENT_TIMEOUT);
            assertTrue(stateCallback.waitUntilResult(1));
            assertSatelliteEnabledInSettings(true);
            assertTrue(areAllRadiosDisabled());

            // Disable satellite and check whether all radios are set to their initial state
            requestSatelliteEnabled(false);
            assertTrue(stateCallback.waitUntilResult(1));
            assertSatelliteEnabledInSettings(false);
            assertTrue(areAllRadiosResetToInitialState());
        } finally {
            // Restore original satellite mode radios
            logd("restore original satellite mode radios");
            assertTrue(satelliteRadiosModeUpdater.setSatelliteModeRadios(
                    originalSatelliteModeRadios));
            sSatelliteManager.unregisterForSatelliteModemStateChanged(stateCallback);
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testSatelliteModeRadios_noRadiosSensitiveToSatelliteMode() {
        if (!shouldTestSatellite()) return;

        logd("testSatelliteModeRadios_noRadiosSensitiveToSatelliteMode: start");
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.SATELLITE_COMMUNICATION,
                        Manifest.permission.WRITE_SECURE_SETTINGS);
        assertTrue(isSatelliteProvisioned());

        SatelliteStateCallbackTest stateCallback = new SatelliteStateCallbackTest();
        sSatelliteManager.registerForSatelliteModemStateChanged(
                getContext().getMainExecutor(), stateCallback);
        assertTrue(stateCallback.waitUntilResult(1));

        boolean originalEnabledState = isSatelliteEnabled();
        if (originalEnabledState) {
            requestSatelliteEnabled(false);
            assertTrue(stateCallback.waitUntilModemOff());
            assertSatelliteEnabledInSettings(false);
            stateCallback.clearModemStates();
            waitFor(500);
        }

        // Get satellite mode radios
        String originalSatelliteModeRadios =  Settings.Global.getString(
                getContext().getContentResolver(), Settings.Global.SATELLITE_MODE_RADIOS);
        logd("originalSatelliteModeRadios: " + originalSatelliteModeRadios);
        SatelliteModeRadiosUpdater satelliteRadiosModeUpdater =
                new SatelliteModeRadiosUpdater(getContext());

        try {
            mTestSatelliteModeRadios = "";
            logd("test satelliteModeRadios: " + mTestSatelliteModeRadios);
            assertTrue(satelliteRadiosModeUpdater.setSatelliteModeRadios(mTestSatelliteModeRadios));

            // Enable Satellite and check whether all radios are disabled
            requestSatelliteEnabled(true, EXTERNAL_DEPENDENT_TIMEOUT);
            assertTrue(stateCallback.waitUntilResult(1));
            assertSatelliteEnabledInSettings(true);
            assertTrue(areAllRadiosDisabled());
            assertTrue(areAllRadiosResetToInitialState());

            // Disable satellite and check whether all radios are set to their initial state
            stateCallback.clearModemStates();
            requestSatelliteEnabled(false);
            assertTrue(stateCallback.waitUntilModemOff());
            assertSatelliteEnabledInSettings(false);
            assertTrue(areAllRadiosResetToInitialState());
            stateCallback.clearModemStates();
        } finally {
            // Restore original satellite mode radios
            logd("restore original satellite mode radios");
            assertTrue(satelliteRadiosModeUpdater.setSatelliteModeRadios(
                    originalSatelliteModeRadios));
            sSatelliteManager.unregisterForSatelliteModemStateChanged(stateCallback);
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testSatelliteModeRadiosWithAirplaneMode() throws Exception {
        if (!shouldTestSatellite()) return;

        logd("testSatelliteModeRadiosWithAirplaneMode: start");
        InstrumentationRegistry.getInstrumentation().getUiAutomation()
                .adoptShellPermissionIdentity(Manifest.permission.SATELLITE_COMMUNICATION,
                        Manifest.permission.WRITE_SECURE_SETTINGS,
                        Manifest.permission.NETWORK_SETTINGS,
                        Manifest.permission.ACCESS_FINE_LOCATION,
                        Manifest.permission.READ_PRIVILEGED_PHONE_STATE);
        assertTrue(isSatelliteProvisioned());

        ServiceStateRadioStateListener callback = new ServiceStateRadioStateListener(
                sTelephonyManager.getServiceState(), sTelephonyManager.getRadioPowerState());
        sTelephonyManager.registerTelephonyCallback(Runnable::run, callback);
        SatelliteStateCallbackTest stateCallback = new SatelliteStateCallbackTest();
        sSatelliteManager.registerForSatelliteModemStateChanged(
                getContext().getMainExecutor(), stateCallback);
        assertTrue(stateCallback.waitUntilResult(1));

        boolean originalEnabledState = isSatelliteEnabled();
        if (originalEnabledState) {
            requestSatelliteEnabled(false);
            assertTrue(stateCallback.waitUntilModemOff());
            assertFalse(isSatelliteEnabled());
            stateCallback.clearModemStates();
            waitFor(500);
        }

        ConnectivityManager connectivityManager =
                getContext().getSystemService(ConnectivityManager.class);

        // Get original satellite mode radios and original airplane mode
        String originalSatelliteModeRadios =  Settings.Global.getString(
                getContext().getContentResolver(), Settings.Global.SATELLITE_MODE_RADIOS);
        logd("originalSatelliteModeRadios: " + originalSatelliteModeRadios);
        boolean originalAirplaneMode = Settings.Global.getInt(
                getContext().getContentResolver(), Settings.Global.AIRPLANE_MODE_ON) != 0;
        SatelliteModeRadiosUpdater satelliteModeRadiosUpdater =
                new SatelliteModeRadiosUpdater(getContext());

        try {
            identifyRadiosSensitiveToSatelliteMode();
            mTestSatelliteModeRadios = "";
            logd("test satelliteModeRadios: " + mTestSatelliteModeRadios);
            assertTrue(satelliteModeRadiosUpdater.setSatelliteModeRadios(mTestSatelliteModeRadios));

            // Enable Satellite and check whether all radios are disabled
            requestSatelliteEnabled(true, EXTERNAL_DEPENDENT_TIMEOUT);
            assertTrue(stateCallback.waitUntilResult(1));
            assertTrue(isSatelliteEnabled());
            assertSatelliteEnabledInSettings(true);
            assertTrue(areAllRadiosDisabled());

            // Enable airplane mode, check whether all radios are disabled and
            // also satellite mode is disabled
            connectivityManager.setAirplaneMode(true);
            // Wait for telephony radio power off
            callback.waitForRadioStateIntent(TelephonyManager.RADIO_POWER_OFF);
            // Wait for satellite mode state changed
            assertTrue(stateCallback.waitUntilResult(1));
            assertFalse(isSatelliteEnabled());
            assertSatelliteEnabledInSettings(false);
            assertTrue(areAllRadiosDisabled());

            // Disable airplane mode, check whether all radios are set to their initial state
            connectivityManager.setAirplaneMode(false);
            callback.waitForRadioStateIntent(TelephonyManager.RADIO_POWER_ON);
            assertTrue(areAllRadiosResetToInitialState());
        } finally {
            // Restore original satellite mode radios
            logd("restore original satellite mode radios and original airplane mode");
            connectivityManager.setAirplaneMode(originalAirplaneMode);
            callback.waitForRadioStateIntent(originalAirplaneMode
                    ? TelephonyManager.RADIO_POWER_OFF : TelephonyManager.RADIO_POWER_ON);
            assertTrue(satelliteModeRadiosUpdater.setSatelliteModeRadios(
                    originalSatelliteModeRadios));
            sTelephonyManager.unregisterTelephonyCallback(callback);
            sSatelliteManager.unregisterForSatelliteModemStateChanged(stateCallback);
            InstrumentationRegistry.getInstrumentation().getUiAutomation()
                    .dropShellPermissionIdentity();
        }
    }

    @Test
    public void testSendSatelliteDatagram_DemoMode_not_Aligned() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testSendSatelliteDatagram_DemoMode_not_Aligned");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        SatelliteStateCallbackTest stateCallback = new SatelliteStateCallbackTest();
        sSatelliteManager.registerForSatelliteModemStateChanged(
                getContext().getMainExecutor(), stateCallback);
        assertTrue(stateCallback.waitUntilResult(1));

        // Enable satellite with demo mode on
        if (isSatelliteEnabled()) {
            requestSatelliteEnabled(false);
            assertTrue(stateCallback.waitUntilModemOff());
            stateCallback.clearModemStates();
            waitFor(500);
        }
        requestSatelliteEnabledForDemoMode(true);
        assertTrue(stateCallback.waitUntilResult(1));

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest callback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, callback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in "
                    + "waiting for the startSatelliteTransmissionUpdates result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        // Send satellite datagram and satellite is not aligned.
        assertTrue(sMockSatelliteServiceManager.setSatelliteDeviceAlignedTimeoutDuration(
                TEST_SATELLITE_DEVICE_ALIGN_TIMEOUT_MILLIS));
        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        callback.clearSendDatagramStateChanges();
        sSatelliteManager.onDeviceAlignedWithSatellite(false);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener::offer);

        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in"
                    + " waiting for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_NOT_REACHABLE);

        /**
         * Send datagram transfer state should have the following transitions:
         * 1) IDLE to SENDING
         * 2) SENDING to SENDING_FAILED
         * 3) SENDING_FAILED to IDLE
         */
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(3));
        assertThat(callback.getNumOfSendDatagramStateChanges()).isEqualTo(3);
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED,
                        0, SatelliteManager.SATELLITE_NOT_REACHABLE));
        assertThat(callback.getSendDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        // Move to sending state and wait for satellite alignment forever
        assertTrue(sMockSatelliteServiceManager.setSatelliteDeviceAlignedTimeoutDuration(
                TEST_SATELLITE_DEVICE_ALIGN_FOREVER_TIMEOUT_MILLIS));
        callback.clearSendDatagramStateChanges();
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener::offer);

        // No response for the request sendSatelliteDatagram received
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in"
                    + " waiting for the sendSatelliteDatagram result code");
            return;
        }
        assertNull(errorCode);

        /**
         * Send datagram transfer state should have the following transitions:
         * 1) IDLE to SENDING
         */
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(1));
        assertThat(callback.getNumOfSendDatagramStateChanges()).isEqualTo(1);
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));

        assertTrue(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));
        callback.clearSendDatagramStateChanges();
        sSatelliteManager.onDeviceAlignedWithSatellite(true);

        // Satellite is aligned now. We should get the response of the request
        // sendSatelliteDatagrams.
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in"
                    + " waiting for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        /**
         * Send datagram transfer state should have the following transitions:
         * 1) SENDING to SEND_SUCCESS
         * 2) SEND_SUCCESS to IDLE
         */
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(2));
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        // Move to sending state and wait for satellite alignment forever again
        sSatelliteManager.onDeviceAlignedWithSatellite(false);
        callback.clearSendDatagramStateChanges();
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener::offer);

        // No response for the request sendSatelliteDatagram received
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in"
                    + " waiting for the sendSatelliteDatagram result code");
            return;
        }
        assertNull(errorCode);

        /**
         * Send datagram transfer state should have the following transitions:
         * 1) IDLE to SENDING
         */
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(1));
        assertThat(callback.getNumOfSendDatagramStateChanges()).isEqualTo(1);
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));

        callback.clearSendDatagramStateChanges();
        stateCallback.clearModemStates();
        requestSatelliteEnabled(false);
        assertTrue(stateCallback.waitUntilModemOff());
        stateCallback.clearModemStates();

        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in"
                    + " waiting for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_REQUEST_ABORTED);

        /**
         * Send datagram transfer state should have the following transitions:
         * 1) SENDING to SENDING_FAILED
         * 2) SENDING_FAILED to IDLE
         */
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(2));
        assertThat(callback.getNumOfSendDatagramStateChanges()).isEqualTo(2);
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_FAILED,
                        1, SatelliteManager.SATELLITE_REQUEST_ABORTED));
        assertThat(callback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        callback.clearSendDatagramStateChanges();
        sSatelliteManager.stopSatelliteTransmissionUpdates(callback, getContext().getMainExecutor(),
                resultListener::offer);

        // Restore satellite device align time out to default value.
        assertTrue(sMockSatelliteServiceManager.setSatelliteDeviceAlignedTimeoutDuration(
                SATELLITE_ALIGN_TIMEOUT));
        sSatelliteManager.unregisterForSatelliteModemStateChanged(stateCallback);
        revokeSatellitePermission();
    }

    @Test
    public void testReceiveSatelliteDatagram_DemoMode_not_Aligned() {
        if (!shouldTestSatelliteWithMockService()) return;

        logd("testReceiveSatelliteDatagram_DemoMode_not_Aligned");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        SatelliteStateCallbackTest stateCallback = new SatelliteStateCallbackTest();
        sSatelliteManager.registerForSatelliteModemStateChanged(
                getContext().getMainExecutor(), stateCallback);
        assertTrue(stateCallback.waitUntilResult(1));
        SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                startTransmissionUpdates();

        // Request enable satellite with demo mode on
        if (isSatelliteEnabled()) {
            requestSatelliteEnabled(false);
            assertTrue(stateCallback.waitUntilModemOff());
            stateCallback.clearModemStates();
            waitFor(500);
        }
        requestSatelliteEnabledForDemoMode(true);
        assertTrue(stateCallback.waitUntilResult(1));

        sSatelliteManager.onDeviceAlignedWithSatellite(true);
        // Send satellite datagram to compare with the received datagram in demo mode
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        String mText = "This is a test datagram message";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        sSatelliteManager.sendSatelliteDatagram(
                SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE, datagram, true,
                getContext().getMainExecutor(), resultListener::offer);

        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testReceiveSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in "
                    + "waiting for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        Log.d(TAG, "testReceiveSatelliteDatagram_DemoMode_not_Aligned: sendSatelliteDatagram "
                + "errorCode=" + errorCode);


        // Test poll pending satellite datagram for demo mode while it is not aligned
        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        sSatelliteManager.onDeviceAlignedWithSatellite(false);
        assertTrue(sMockSatelliteServiceManager.setSatelliteDeviceAlignedTimeoutDuration(
                TEST_SATELLITE_DEVICE_ALIGN_TIMEOUT_MILLIS));

        sSatelliteManager.pollPendingSatelliteDatagrams(getContext().getMainExecutor(),
                resultListener::offer);

        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testReceiveSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in "
                    + "waiting for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_NOT_REACHABLE);

        // Datagram transfer state should change from RECEIVING to IDLE.
        assertTrue(transmissionUpdateCallback
                .waitUntilOnReceiveDatagramStateChanged(3));
        assertThat(transmissionUpdateCallback.getNumOfReceiveDatagramStateChanges())
                .isEqualTo(3);
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED,
                        0, SatelliteManager.SATELLITE_NOT_REACHABLE));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        // Move satellite to receiving state and wait for satellite aligned forever
        assertTrue(sMockSatelliteServiceManager.setSatelliteDeviceAlignedTimeoutDuration(
                TEST_SATELLITE_DEVICE_ALIGN_FOREVER_TIMEOUT_MILLIS));

        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        sSatelliteManager.pollPendingSatelliteDatagrams(getContext().getMainExecutor(),
                resultListener::offer);

        // No response for the request pollPendingSatelliteDatagrams received
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testReceiveSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in "
                    + "waiting for the sendSatelliteDatagram result code");
            return;
        }
        assertNull(errorCode);

        // Datagram transfer state should change from IDLE to RECEIVING.
        assertTrue(transmissionUpdateCallback
                .waitUntilOnReceiveDatagramStateChanged(1));
        assertThat(transmissionUpdateCallback.getNumOfReceiveDatagramStateChanges())
                .isEqualTo(1);
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        stateCallback.clearModemStates();
        requestSatelliteEnabled(false);
        assertTrue(stateCallback.waitUntilModemOff());
        stateCallback.clearModemStates();

        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testReceiveSatelliteDatagram_DemoMode_not_Aligned: Got InterruptedException in "
                    + "waiting for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_REQUEST_ABORTED);

        // Datagram transfer state should change from RECEIVING to IDLE.
        assertTrue(transmissionUpdateCallback
                .waitUntilOnReceiveDatagramStateChanged(2));
        assertThat(transmissionUpdateCallback.getNumOfReceiveDatagramStateChanges())
                .isEqualTo(2);
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_FAILED,
                        0, SatelliteManager.SATELLITE_REQUEST_ABORTED));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        stopTransmissionUpdates(transmissionUpdateCallback);
        assertTrue(sMockSatelliteServiceManager.setSatelliteDeviceAlignedTimeoutDuration(
                SATELLITE_ALIGN_TIMEOUT));
        sSatelliteManager.unregisterForSatelliteModemStateChanged(stateCallback);
        revokeSatellitePermission();
    }

    @Test
    public void testSatelliteModemBusy_modemSendingDatagram_pollingFailure() {
        if (!shouldTestSatellite()) return;

        logd("testSatelliteModemBusy_modemSendingDatagram_pollingFailure");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();

        // Wait to process datagrams so that datagrams are added to pending list
        // and modem is busy sending datagrams
        sMockSatelliteServiceManager.setWaitToSend(true);

        LinkedBlockingQueue<Integer> sendResultListener = new LinkedBlockingQueue<>(1);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                sendResultListener::offer);

        LinkedBlockingQueue<Integer> pollResultListener = new LinkedBlockingQueue<>(1);
        sSatelliteManager.pollPendingSatelliteDatagrams(getContext().getMainExecutor(),
                pollResultListener::offer);

        Integer errorCode;
        try {
            errorCode = pollResultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSatelliteModemBusy_modemSendingDatagram_pollingFailure: Got "
                    + "InterruptedException in waiting for the pollPendingSatelliteDatagrams "
                    + "result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_MODEM_BUSY);

        // Send datagram successfully to bring sending state back to IDLE.
        assertTrue(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));
        assertTrue(sMockSatelliteServiceManager.sendSavedDatagram());
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        sMockSatelliteServiceManager.setWaitToSend(false);
        revokeSatellitePermission();
    }

    @Test
    public void testSatelliteModemBusy_modemPollingDatagrams_pollingFailure() {
        if (!shouldTestSatellite()) return;

        logd("testSatelliteModemBusy_modemPollingDatagrams_pollingFailure");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        LinkedBlockingQueue<Integer> pollResultListener1 = new LinkedBlockingQueue<>(1);
        sSatelliteManager.pollPendingSatelliteDatagrams(getContext().getMainExecutor(),
                pollResultListener1::offer);

        // As we already got one polling request, this second polling request would fail
        LinkedBlockingQueue<Integer> pollResultListener2 = new LinkedBlockingQueue<>(1);
        sSatelliteManager.pollPendingSatelliteDatagrams(getContext().getMainExecutor(),
                pollResultListener2::offer);

        Integer errorCode;
        try {
            errorCode = pollResultListener2.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSatelliteModemBusy_modemSendingDatagram_pollingFailure: Got "
                    + "InterruptedException in waiting for the pollPendingSatelliteDatagrams "
                    + "result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_MODEM_BUSY);

        // Receive one datagram successfully to bring receiving state back to IDLE.
        String receivedText = "This is a test datagram message from satellite";
        android.telephony.satellite.stub.SatelliteDatagram receivedDatagram =
                new android.telephony.satellite.stub.SatelliteDatagram();
        receivedDatagram.data = receivedText.getBytes();
        sMockSatelliteServiceManager.sendOnSatelliteDatagramReceived(receivedDatagram, 0);
        revokeSatellitePermission();
    }

    @Test
    public void testSatelliteModemBusy_modemPollingDatagram_sendingDelayed() {
        if (!shouldTestSatellite()) return;

        logd("testSatelliteModemBusy_modemPollingDatagram_sendingDelayed");
        grantSatellitePermission();
        assertTrue(isSatelliteProvisioned());

        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, transmissionUpdateCallback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSatelliteModemBusy_modemPollingDatagram_sendingDelayed: "
                    + "Got InterruptedException in waiting for the "
                    + "startSatelliteTransmissionUpdates result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        SatelliteDatagramCallbackTest satelliteDatagramCallback =
                new SatelliteDatagramCallbackTest();
        sSatelliteManager.registerForSatelliteDatagram(
                getContext().getMainExecutor(), satelliteDatagramCallback);

        transmissionUpdateCallback.clearSendDatagramStateChanges();
        transmissionUpdateCallback.clearReceiveDatagramStateChanges();

        LinkedBlockingQueue<Integer> pollResultListener = new LinkedBlockingQueue<>(1);
        sSatelliteManager.pollPendingSatelliteDatagrams(getContext().getMainExecutor(),
                pollResultListener::offer);
        // Datagram transfer state changes from IDLE -> RECEIVING.
        assertSingleReceiveDatagramStateChanged(transmissionUpdateCallback,
                SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
                0, SatelliteManager.SATELLITE_ERROR_NONE);

        LinkedBlockingQueue<Integer> sendResultListener = new LinkedBlockingQueue<>(1);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                sendResultListener::offer);
        // Sending datagram will be delayed as modem is in RECEIVING state
        assertFalse(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));

        String receivedText = "This is a test datagram message from satellite";
        android.telephony.satellite.stub.SatelliteDatagram receivedDatagram =
                new android.telephony.satellite.stub.SatelliteDatagram();
        receivedDatagram.data = receivedText.getBytes();
        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        sMockSatelliteServiceManager.sendOnSatelliteDatagramReceived(receivedDatagram, 0);
        // As pending count is 0, datagram transfer state changes from
        // RECEIVING -> RECEIVE_SUCCESS -> IDLE.
        int expectedNumOfEvents = 2;
        assertTrue(transmissionUpdateCallback
                .waitUntilOnReceiveDatagramStateChanged(expectedNumOfEvents));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        // As polling is completed, now modem will start sending datagrams
        expectedNumOfEvents = 3;
        assertTrue(transmissionUpdateCallback.
                waitUntilOnSendDatagramStateChanged(expectedNumOfEvents));
        assertThat(transmissionUpdateCallback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getSendDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        transmissionUpdateCallback.clearSendDatagramStateChanges();
        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        sSatelliteManager.stopSatelliteTransmissionUpdates(transmissionUpdateCallback,
                getContext().getMainExecutor(), resultListener::offer);
        sSatelliteManager.unregisterForSatelliteDatagram(satelliteDatagramCallback);
        revokeSatellitePermission();
    }

    /**
     * Before calling this function, caller need to make sure the modem is in LISTENING or IDLE
     * state.
     */
    private void sendSatelliteDatagramWithSuccessfulResult(
            SatelliteStateCallbackTest callback, boolean verifyListenToIdleTransition) {
        if (callback.modemState != SatelliteManager.SATELLITE_MODEM_STATE_LISTENING
                && callback.modemState != SatelliteManager.SATELLITE_MODEM_STATE_IDLE) {
            fail("sendSatelliteDatagramWithSuccessfulResult: wrong modem state="
                    + callback.modemState);
            return;
        }

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());

        sMockSatelliteServiceManager.clearListeningEnabledList();
        callback.clearModemStates();
        sSatelliteManager.sendSatelliteDatagram(
                SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE, datagram, true,
                getContext().getMainExecutor(), resultListener::offer);

        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSatelliteModemStateChanged: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, (long) errorCode);

        /**
         * Modem state should have the following transitions:
         * 1) IDLE to TRANSFERRING.
         * 2) TRANSFERRING to LISTENING.
         * 3) LISTENING to IDLE.
         *
         * When verifyListenToIdleTransition is true, we expect the above 3 state transitions.
         * Otherwise, we expect only the first 2 transitions since satellite is still in LISTENING
         * state (timeout duration is long when verifyListenToIdleTransition is false).
         */
        int expectedNumberOfEvents = verifyListenToIdleTransition ? 3 : 2;
        assertTrue(callback.waitUntilResult(expectedNumberOfEvents));
        assertEquals(expectedNumberOfEvents, callback.getTotalCountOfModemStates());
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING,
                callback.getModemState(0));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_LISTENING,
                callback.getModemState(1));

        /**
         * On entering LISTENING state, we expect one event of EventOnSatelliteListeningEnabled with
         * value true. On exiting LISTENING state, we expect one event of
         * EventOnSatelliteListeningEnabled with value false.
         *
         * When verifyListenToIdleTransition is true, we expect satellite entering and then exiting
         * LISTENING state. Otherwise, we expect satellite entering and staying at LISTENING state.
         */
        expectedNumberOfEvents = verifyListenToIdleTransition ? 2 : 1;
        assertTrue(sMockSatelliteServiceManager.waitForEventOnSatelliteListeningEnabled(
                expectedNumberOfEvents));
        assertEquals(expectedNumberOfEvents,
                sMockSatelliteServiceManager.getTotalCountOfListeningEnabledList());
        assertTrue(sMockSatelliteServiceManager.getListeningEnabled(0));

        if (verifyListenToIdleTransition) {
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE,
                    callback.getModemState(2));
            assertFalse(sMockSatelliteServiceManager.getListeningEnabled(1));
        }
        sMockSatelliteServiceManager.clearListeningEnabledList();
    }

    /**
     * Before calling this function, caller need to make sure the modem is in LISTENING or IDLE
     * state.
     */
    private void sendSatelliteDatagramWithFailedResult(SatelliteStateCallbackTest callback) {
        if (callback.modemState != SatelliteManager.SATELLITE_MODEM_STATE_LISTENING
                && callback.modemState != SatelliteManager.SATELLITE_MODEM_STATE_IDLE) {
            fail("sendSatelliteDatagramWithFailedResult: wrong modem state=" + callback.modemState);
            return;
        }
        boolean isFirstStateListening =
                (callback.modemState == SatelliteManager.SATELLITE_MODEM_STATE_LISTENING);

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());

        sMockSatelliteServiceManager.setErrorCode(SatelliteError.SATELLITE_ERROR);
        sMockSatelliteServiceManager.clearListeningEnabledList();
        callback.clearModemStates();
        sSatelliteManager.sendSatelliteDatagram(
                SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE, datagram, true,
                getContext().getMainExecutor(), resultListener::offer);

        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSatelliteModemStateChanged: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertEquals(SatelliteManager.SATELLITE_ERROR, (long) errorCode);
        assertTrue(callback.waitUntilResult(2));
        assertEquals(2, callback.getTotalCountOfModemStates());
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING,
                callback.getModemState(0));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE,
                callback.getModemState(1));

        if (isFirstStateListening) {
            assertTrue(sMockSatelliteServiceManager.waitForEventOnSatelliteListeningEnabled(1));
            assertEquals(1, sMockSatelliteServiceManager.getTotalCountOfListeningEnabledList());
            assertFalse(sMockSatelliteServiceManager.getListeningEnabled(0));
        }
        sMockSatelliteServiceManager.clearListeningEnabledList();
        sMockSatelliteServiceManager.setErrorCode(SatelliteError.ERROR_NONE);
    }

    /**
     * Before calling this function, caller need to make sure the modem is in LISTENING state.
     */
    private void receiveSatelliteDatagramWithSuccessfulResult(
            SatelliteStateCallbackTest callback) {
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_LISTENING, callback.modemState);

        // TODO (b/275086547): remove the below registerForSatelliteDatagram command when the bug
        // is resolved.
        SatelliteDatagramCallbackTest satelliteDatagramCallback =
                new SatelliteDatagramCallbackTest();
        sSatelliteManager.registerForSatelliteDatagram(
                getContext().getMainExecutor(), satelliteDatagramCallback);

        sMockSatelliteServiceManager.clearListeningEnabledList();
        callback.clearModemStates();
        sMockSatelliteServiceManager.sendOnPendingDatagrams();
        assertTrue(sMockSatelliteServiceManager.waitForEventOnPollPendingSatelliteDatagrams(1));

        assertTrue(callback.waitUntilResult(1));
        assertEquals(1, callback.getTotalCountOfModemStates());
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING,
                callback.getModemState(0));

        assertTrue(sMockSatelliteServiceManager.waitForEventOnSatelliteListeningEnabled(1));
        assertEquals(1, sMockSatelliteServiceManager.getTotalCountOfListeningEnabledList());
        assertFalse(sMockSatelliteServiceManager.getListeningEnabled(0));

        String receivedText = "This is a test datagram message from satellite";
        android.telephony.satellite.stub.SatelliteDatagram receivedDatagram =
                new android.telephony.satellite.stub.SatelliteDatagram();
        receivedDatagram.data = receivedText.getBytes();

        sMockSatelliteServiceManager.clearListeningEnabledList();
        callback.clearModemStates();
        sMockSatelliteServiceManager.sendOnSatelliteDatagramReceived(receivedDatagram, 0);
        assertTrue(callback.waitUntilResult(1));
        assertEquals(1, callback.getTotalCountOfModemStates());
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_LISTENING,
                callback.getModemState(0));

        assertTrue(sMockSatelliteServiceManager.waitForEventOnSatelliteListeningEnabled(1));
        assertEquals(1, sMockSatelliteServiceManager.getTotalCountOfListeningEnabledList());
        assertTrue(sMockSatelliteServiceManager.getListeningEnabled(0));
        sMockSatelliteServiceManager.clearListeningEnabledList();

        sSatelliteManager.unregisterForSatelliteDatagram(satelliteDatagramCallback);
    }

    /**
     * Before calling this function, caller need to make sure the modem is in LISTENING state.
     */
    private void receiveSatelliteDatagramWithFailedResult(
            SatelliteStateCallbackTest callback) {
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_LISTENING, callback.modemState);

        // TODO (b/275086547): remove the below registerForSatelliteDatagram command when the bug
        // is resolved.
        SatelliteDatagramCallbackTest satelliteDatagramCallback =
                new SatelliteDatagramCallbackTest();
        sSatelliteManager.registerForSatelliteDatagram(
                getContext().getMainExecutor(), satelliteDatagramCallback);

        sMockSatelliteServiceManager.clearListeningEnabledList();
        callback.clearModemStates();
        sMockSatelliteServiceManager.setErrorCode(SatelliteError.SATELLITE_ERROR);
        sMockSatelliteServiceManager.sendOnPendingDatagrams();
        assertTrue(sMockSatelliteServiceManager.waitForEventOnPollPendingSatelliteDatagrams(1));

        assertTrue(callback.waitUntilResult(2));
        assertEquals(2, callback.getTotalCountOfModemStates());
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING,
                callback.getModemState(0));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE,
                callback.getModemState(1));

        /**
         * On entering LISTENING state, we expect one event of EventOnSatelliteListeningEnabled with
         * value true. On exiting LISTENING state, we expect one event of
         * EventOnSatelliteListeningEnabled with value false.
         *
         * At the beginning of this function, satellite is in LISTENING state. It then transitions
         * to TRANSFERRING state. Thus, we expect one event of EventOnSatelliteListeningEnabled with
         * value false.
         */
        assertTrue(sMockSatelliteServiceManager.waitForEventOnSatelliteListeningEnabled(1));
        assertEquals(1, sMockSatelliteServiceManager.getTotalCountOfListeningEnabledList());
        assertFalse(sMockSatelliteServiceManager.getListeningEnabled(0));

        sMockSatelliteServiceManager.clearListeningEnabledList();
        sSatelliteManager.unregisterForSatelliteDatagram(satelliteDatagramCallback);
        sMockSatelliteServiceManager.setErrorCode(SatelliteError.ERROR_NONE);
    }

    private void moveToSendingState() {
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest callback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, callback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("moveToSendingState: Got InterruptedException in waiting"
                    + " for the startSatelliteTransmissionUpdates result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        // Send satellite datagram
        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        callback.clearSendDatagramStateChanges();
        sMockSatelliteServiceManager.setShouldRespondTelephony(false);
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener::offer);

        assertTrue(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));

        // Send datagram transfer state should move from IDLE to SENDING.
        assertSingleSendDatagramStateChanged(callback,
                SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                1, SatelliteManager.SATELLITE_ERROR_NONE);

        sMockSatelliteServiceManager.setShouldRespondTelephony(true);
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        callback.clearSendDatagramStateChanges();
        sSatelliteManager.stopSatelliteTransmissionUpdates(callback, getContext().getMainExecutor(),
                resultListener::offer);
    }

    private void sendSatelliteDatagramSuccess() {
        SatelliteTransmissionUpdateCallbackTest callback = startTransmissionUpdates();

        // Send satellite datagram
        String mText = "This is a test datagram message from user";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        callback.clearSendDatagramStateChanges();
        assertTrue(sMockSatelliteServiceManager.overrideSatellitePointingUiClassName());
        sMockSatelliteServiceManager.clearMockPointingUiActivityStatusChanges();
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener::offer);

        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_success: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);
        assertTrue(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));

        /**
         * Send datagram transfer state should have the following transitions:
         * 1) IDLE to SENDING
         * 2) SENDING to SENDING_SUCCESS
         * 3) SENDING_SUCCESS to IDLE
         */
        int expectedNumOfEvents = 3;
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(expectedNumOfEvents));
        assertThat(callback.getNumOfSendDatagramStateChanges()).isEqualTo(expectedNumOfEvents);
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertTrue(sMockSatelliteServiceManager.waitForEventMockPointingUiActivityStarted(1));
        assertTrue(sMockSatelliteServiceManager.restoreSatellitePointingUiClassName());

        callback.clearSendDatagramStateChanges();
        sSatelliteManager.stopSatelliteTransmissionUpdates(callback, getContext().getMainExecutor(),
                resultListener::offer);
    }

    private void sendSatelliteDatagramDemoModeSuccess(String sampleText) {
        SatelliteTransmissionUpdateCallbackTest callback = startTransmissionUpdates();

        // Send satellite datagram
        SatelliteDatagram datagram = new SatelliteDatagram(sampleText.getBytes());
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        callback.clearSendDatagramStateChanges();
        assertTrue(sMockSatelliteServiceManager.overrideSatellitePointingUiClassName());
        sMockSatelliteServiceManager.clearMockPointingUiActivityStatusChanges();
        sMockSatelliteServiceManager.clearSentSatelliteDatagramInfo();
        sSatelliteManager.onDeviceAlignedWithSatellite(true);
        sSatelliteManager.sendSatelliteDatagram(SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE,
                datagram, true, getContext().getMainExecutor(),
                resultListener::offer);

        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testSendSatelliteDatagram_success: Got InterruptedException in waiting"
                    + " for the sendSatelliteDatagram result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);
        assertTrue(sMockSatelliteServiceManager.waitForEventOnSendSatelliteDatagram(1));

        /**
         * Send datagram transfer state should have the following transitions:
         * 1) IDLE to SENDING
         * 2) SENDING to SENDING_SUCCESS
         * 3) SENDING_SUCCESS to IDLE
         */
        int expectedNumOfEvents = 3;
        assertTrue(callback.waitUntilOnSendDatagramStateChanged(expectedNumOfEvents));
        assertThat(callback.getNumOfSendDatagramStateChanges()).isEqualTo(expectedNumOfEvents);
        assertThat(callback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SENDING,
                        1, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_SEND_SUCCESS,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(callback.getSendDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertTrue(sMockSatelliteServiceManager.waitForEventMockPointingUiActivityStarted(1));
        assertTrue(sMockSatelliteServiceManager.restoreSatellitePointingUiClassName());

        sSatelliteManager.onDeviceAlignedWithSatellite(false);
        callback.clearSendDatagramStateChanges();
        sSatelliteManager.stopSatelliteTransmissionUpdates(callback, getContext().getMainExecutor(),
                resultListener::offer);
    }

    private void moveToReceivingState() {
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, transmissionUpdateCallback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("testReceiveSatelliteDatagram: Got InterruptedException in waiting"
                    + " for the startSatelliteTransmissionUpdates result code");
            return;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);

        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        sMockSatelliteServiceManager.sendOnPendingDatagrams();
        assertTrue(sMockSatelliteServiceManager.waitForEventOnPollPendingSatelliteDatagrams(1));

        // Datagram transfer state changes from IDLE to RECEIVING.
        assertSingleReceiveDatagramStateChanged(transmissionUpdateCallback,
                SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
                0, SatelliteManager.SATELLITE_ERROR_NONE);

        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        stopTransmissionUpdates(transmissionUpdateCallback);
    }

    private void receiveSatelliteDatagramSuccess() {
        SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                startTransmissionUpdates();

        SatelliteDatagramCallbackTest satelliteDatagramCallback =
                new SatelliteDatagramCallbackTest();
        sSatelliteManager.registerForSatelliteDatagram(
                getContext().getMainExecutor(), satelliteDatagramCallback);

        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        sMockSatelliteServiceManager.sendOnPendingDatagrams();
        assertTrue(sMockSatelliteServiceManager.waitForEventOnPollPendingSatelliteDatagrams(1));

        // Receive one datagram
        String receivedText = "This is a test datagram message from satellite";
        android.telephony.satellite.stub.SatelliteDatagram receivedDatagram =
                new android.telephony.satellite.stub.SatelliteDatagram();
        receivedDatagram.data = receivedText.getBytes();
        sMockSatelliteServiceManager.sendOnSatelliteDatagramReceived(receivedDatagram, 0);
        assertTrue(satelliteDatagramCallback.waitUntilResult(1));
        assertArrayEquals(satelliteDatagramCallback.mDatagram.getSatelliteDatagram(),
                receivedText.getBytes());

        // As pending count is 0, datagram transfer state changes from
        // IDLE -> RECEIVING -> RECEIVE_SUCCESS -> IDLE.
        int expectedNumOfEvents = 3;
        assertTrue(transmissionUpdateCallback
                .waitUntilOnReceiveDatagramStateChanged(expectedNumOfEvents));
        assertThat(transmissionUpdateCallback.getNumOfReceiveDatagramStateChanges())
                .isEqualTo(expectedNumOfEvents);
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVING,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(1)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_RECEIVE_SUCCESS,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(2)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        SatelliteManager.SATELLITE_DATAGRAM_TRANSFER_STATE_IDLE,
                        0, SatelliteManager.SATELLITE_ERROR_NONE));

        transmissionUpdateCallback.clearReceiveDatagramStateChanges();
        stopTransmissionUpdates(transmissionUpdateCallback);
        sSatelliteManager.unregisterForSatelliteDatagram(satelliteDatagramCallback);
    }

    private SatelliteTransmissionUpdateCallbackTest startTransmissionUpdates() {
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback =
                new SatelliteTransmissionUpdateCallbackTest();
        sSatelliteManager.startSatelliteTransmissionUpdates(getContext().getMainExecutor(),
                resultListener::offer, transmissionUpdateCallback);
        Integer errorCode;
        try {
            errorCode = resultListener.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            fail("SatelliteTransmissionUpdateCallbackTest: Got InterruptedException in waiting"
                    + " for the startSatelliteTransmissionUpdates result code");
            return null;
        }
        assertNotNull(errorCode);
        assertThat(errorCode).isEqualTo(SatelliteManager.SATELLITE_ERROR_NONE);
        return transmissionUpdateCallback;
    }

    private void stopTransmissionUpdates(
            SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback) {
        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        sSatelliteManager.stopSatelliteTransmissionUpdates(transmissionUpdateCallback,
                getContext().getMainExecutor(), resultListener::offer);
    }

    private void assertSingleSendDatagramStateChanged(
            SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback,
            int expectedTransferState, int expectedPendingCount, int expectedErrorCode) {
        assertTrue(transmissionUpdateCallback.waitUntilOnSendDatagramStateChanged(1));
        assertThat(transmissionUpdateCallback.getNumOfSendDatagramStateChanges()).isEqualTo(1);
        assertThat(transmissionUpdateCallback.getSendDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        expectedTransferState, expectedPendingCount, expectedErrorCode));
    }

    private void assertSingleReceiveDatagramStateChanged(
            SatelliteTransmissionUpdateCallbackTest transmissionUpdateCallback,
            int expectedTransferState, int expectedPendingCount, int expectedErrorCode) {
        assertTrue(transmissionUpdateCallback
                .waitUntilOnReceiveDatagramStateChanged(1));
        assertThat(transmissionUpdateCallback.getNumOfReceiveDatagramStateChanges())
                .isEqualTo(1);
        assertThat(transmissionUpdateCallback.getReceiveDatagramStateChange(0)).isEqualTo(
                new SatelliteTransmissionUpdateCallbackTest.DatagramStateChangeArgument(
                        expectedTransferState, expectedPendingCount, expectedErrorCode));
    }

    private static void enforceMockModemDeveloperSetting() {
        boolean isAllowed = SystemProperties.getBoolean(ALLOW_MOCK_MODEM_PROPERTY, false);
        // Check for developer settings for user build. Always allow for debug builds
        if (!isAllowed && !DEBUG) {
            throw new IllegalStateException(
                    "!! Enable Mock Modem before running this test !! "
                            + "Developer options => Allow Mock Modem");
        }
    }

    private void identifyRadiosSensitiveToSatelliteMode() {
        PackageManager packageManager = getContext().getPackageManager();
        List<String> satelliteModeRadiosList = new ArrayList<>();

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_WIFI)) {
            satelliteModeRadiosList.add(Settings.Global.RADIO_WIFI);
            mWifiManager = getContext().getSystemService(WifiManager.class);
            mWifiInitState = mWifiManager.isWifiEnabled();
        }

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH)) {
            satelliteModeRadiosList.add(Settings.Global.RADIO_BLUETOOTH);
            mBluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            mBTInitState = mBluetoothAdapter.isEnabled();
        }

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_NFC)) {
            satelliteModeRadiosList.add(Settings.Global.RADIO_NFC);
            mNfcAdapter = NfcAdapter.getNfcAdapter(getContext().getApplicationContext());
            mNfcInitState = mNfcAdapter.isEnabled();
        }

        if (packageManager.hasSystemFeature(PackageManager.FEATURE_UWB)) {
            satelliteModeRadiosList.add(Settings.Global.RADIO_UWB);
            mUwbManager = getContext().getSystemService(UwbManager.class);
            mUwbInitState = mUwbManager.isUwbEnabled();
        }

        mTestSatelliteModeRadios = String.join(",", satelliteModeRadiosList);
    }

    private boolean isRadioSatelliteModeSensitive(String radio) {
        return mTestSatelliteModeRadios.contains(radio);
    }

    private boolean areAllRadiosDisabled() {
        logd("areAllRadiosDisabled");
        if (isRadioSatelliteModeSensitive(Settings.Global.RADIO_WIFI)) {
            assertFalse(mWifiManager.isWifiEnabled());
        }

        if (isRadioSatelliteModeSensitive(Settings.Global.RADIO_UWB)) {
            assertFalse(mUwbManager.isUwbEnabled());
        }

        if (isRadioSatelliteModeSensitive(Settings.Global.RADIO_NFC)) {
            assertFalse(mNfcAdapter.isEnabled());
        }

        if (isRadioSatelliteModeSensitive(Settings.Global.RADIO_BLUETOOTH)) {
            assertFalse(mBluetoothAdapter.isEnabled());
        }

        return true;
    }

    private boolean areAllRadiosResetToInitialState() {
        Object lock = new Object();
        synchronized (lock) {
            try {
                lock.wait(TIMEOUT);
            } catch (InterruptedException e) {
                // Ignore
                logd("InterruptedException e:" + e);
            }

            logd("areAllRadiosResetToInitialState");
            if (isRadioSatelliteModeSensitive(Settings.Global.RADIO_WIFI)) {
                if (mWifiInitState) {
                    assertTrue(mWifiManager.isWifiEnabled());
                } else {
                    assertFalse(mWifiManager.isWifiEnabled());
                }
            }

            if (isRadioSatelliteModeSensitive(Settings.Global.RADIO_BLUETOOTH)) {
                if (mBTInitState) {
                    assertTrue(mBluetoothAdapter.isEnabled());
                } else {
                    assertFalse(mBluetoothAdapter.isEnabled());
                }
            }

            if (isRadioSatelliteModeSensitive(Settings.Global.RADIO_NFC)) {
                if (mNfcInitState) {
                    assertTrue(mNfcAdapter.isEnabled());
                } else {
                    assertFalse(mNfcAdapter.isEnabled());
                }
            }

            if (isRadioSatelliteModeSensitive(Settings.Global.RADIO_UWB)) {
                if (mUwbInitState) {
                    assertTrue(mUwbManager.isUwbEnabled());
                } else {
                    assertFalse(mUwbManager.isUwbEnabled());
                }
            }

            return true;
        }
    }

    private void waitFor(long timeoutMillis) {
        Object delayTimeout = new Object();
        synchronized (delayTimeout) {
            try {
                delayTimeout.wait(timeoutMillis);
            } catch (InterruptedException ex) {
                // Ignore the exception
                logd("waitFor: delayTimeout ex=" + ex);
            }
        }
    }
}
