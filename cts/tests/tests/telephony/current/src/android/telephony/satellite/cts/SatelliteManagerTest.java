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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static org.junit.Assume.assumeTrue;

import android.os.CancellationSignal;
import android.os.OutcomeReceiver;
import android.telephony.satellite.SatelliteCapabilities;
import android.telephony.satellite.SatelliteDatagram;
import android.telephony.satellite.SatelliteManager;
import android.util.Log;

import org.junit.After;
import org.junit.AfterClass;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

public class SatelliteManagerTest extends SatelliteManagerTestBase {
    private static boolean sIsSatelliteSupported = false;
    private static boolean sOriginalIsSatelliteEnabled = false;
    private static boolean sOriginalIsSatelliteProvisioned = false;
    private static boolean sIsProvisionable = false;

    @BeforeClass
    public static void beforeAllTests() throws Exception {
        logd("beforeAllTests");

        if (!shouldTestSatellite()) return;

        beforeAllTestsBase();

        grantSatellitePermission();
        sIsSatelliteSupported = isSatelliteSupported();
        if (sIsSatelliteSupported) {
            sOriginalIsSatelliteProvisioned = isSatelliteProvisioned();

            boolean provisioned = sOriginalIsSatelliteProvisioned;
            if (!sOriginalIsSatelliteProvisioned) {
                SatelliteProvisionStateCallbackTest satelliteProvisionStateCallback =
                        new SatelliteProvisionStateCallbackTest();
                long registerError = sSatelliteManager.registerForSatelliteProvisionStateChanged(
                        getContext().getMainExecutor(), satelliteProvisionStateCallback);
                assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerError);

                provisioned = provisionSatellite();
                if (provisioned) {
                    sIsProvisionable = true;
                    assertTrue(satelliteProvisionStateCallback.waitUntilResult(1));
                    assertTrue(satelliteProvisionStateCallback.isProvisioned);
                }
                sSatelliteManager.unregisterForSatelliteProvisionStateChanged(
                        satelliteProvisionStateCallback);
            }

            sOriginalIsSatelliteEnabled = isSatelliteEnabled();
            if (provisioned && !sOriginalIsSatelliteEnabled) {
                logd("Enable satellite");

                SatelliteStateCallbackTest callback = new SatelliteStateCallbackTest();
                long registerResult = sSatelliteManager.registerForSatelliteModemStateChanged(
                        getContext().getMainExecutor(), callback);
                assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
                assertTrue(callback.waitUntilResult(1));

                requestSatelliteEnabled(true);

                assertTrue(callback.waitUntilResult(1));
                assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE, callback.modemState);
                assertTrue(isSatelliteEnabled());
                sSatelliteManager.unregisterForSatelliteModemStateChanged(callback);
            }
        }
        revokeSatellitePermission();
    }

    @AfterClass
    public static void afterAllTests() {
        logd("afterAllTests");

        if (!shouldTestSatellite()) return;

        grantSatellitePermission();
        if (sIsSatelliteSupported) {
            logd("Restore enabled state");
            if (isSatelliteEnabled() != sOriginalIsSatelliteEnabled) {
                SatelliteStateCallbackTest callback = new SatelliteStateCallbackTest();
                long registerResult = sSatelliteManager.registerForSatelliteModemStateChanged(
                        getContext().getMainExecutor(), callback);
                assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
                assertTrue(callback.waitUntilResult(1));

                requestSatelliteEnabled(sOriginalIsSatelliteEnabled);

                assertTrue(callback.waitUntilResult(1));
                assertEquals(sOriginalIsSatelliteEnabled
                        ? SatelliteManager.SATELLITE_MODEM_STATE_IDLE :
                        SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
                assertTrue(isSatelliteEnabled() == sOriginalIsSatelliteEnabled);
                sSatelliteManager.unregisterForSatelliteModemStateChanged(callback);
            }

            logd("Restore provision state");
            SatelliteProvisionStateCallbackTest satelliteProvisionStateCallback =
                    new SatelliteProvisionStateCallbackTest();
            long registerError = sSatelliteManager.registerForSatelliteProvisionStateChanged(
                    getContext().getMainExecutor(), satelliteProvisionStateCallback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerError);

            if (sOriginalIsSatelliteProvisioned) {
                if (!isSatelliteProvisioned()) {
                    boolean provisioned = provisionSatellite();
                    if (provisioned) {
                        sIsProvisionable = true;
                        assertTrue(satelliteProvisionStateCallback.waitUntilResult(1));
                        assertTrue(satelliteProvisionStateCallback.isProvisioned);
                    }
                }
            } else {
                if (isSatelliteProvisioned()) {
                    assertTrue(deprovisionSatellite());
                    assertTrue(satelliteProvisionStateCallback.waitUntilResult(1));
                    assertFalse(satelliteProvisionStateCallback.isProvisioned);
                }
            }
            sSatelliteManager.unregisterForSatelliteProvisionStateChanged(
                    satelliteProvisionStateCallback);
        }
        revokeSatellitePermission();

        afterAllTestsBase();
    }
    @Before
    public void setUp() throws Exception {
        logd("setUp");

        if (!shouldTestSatellite()) return;
        assumeTrue(sSatelliteManager != null);
    }

    @After
    public void tearDown() {
        logd("tearDown");
    }

    @Test
    public void testSatelliteTransmissionUpdates() throws Exception {
        if (!shouldTestSatellite()) return;

        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        SatelliteTransmissionUpdateCallbackTest callback =
                new SatelliteTransmissionUpdateCallbackTest();

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class,
                () -> sSatelliteManager.startSatelliteTransmissionUpdates(
                        getContext().getMainExecutor(), error::offer, callback));

        grantSatellitePermission();
        sSatelliteManager.startSatelliteTransmissionUpdates(
                getContext().getMainExecutor(), error::offer, callback);
        Integer errorCode = error.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(errorCode);
        if (errorCode == SatelliteManager.SATELLITE_ERROR_NONE) {
            Log.d(TAG, "Successfully started transmission updates.");
        } else {
            Log.d(TAG, "Failed to start transmission updates: " + errorCode);
        }
        getContext().getSystemService(SatelliteManager.class).stopSatelliteTransmissionUpdates(
                callback, getContext().getMainExecutor(), error::offer);
        errorCode = error.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(errorCode);
        Log.d(TAG, "Stop transmission updates: " + errorCode);

        sSatelliteManager.stopSatelliteTransmissionUpdates(
                callback, getContext().getMainExecutor(), error::offer);
        errorCode = error.poll(TIMEOUT, TimeUnit.MILLISECONDS);
        assertNotNull(errorCode);
        assertEquals(SatelliteManager.SATELLITE_INVALID_ARGUMENTS, (long) errorCode);
        revokeSatellitePermission();
    }

    @Test
    public void testProvisionSatelliteService() {
        if (!shouldTestSatellite()) return;

        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);
        String mText = "This is a test provision data.";
        byte[] testProvisionData = mText.getBytes();

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class,
                () -> sSatelliteManager.provisionSatelliteService(
                        "", testProvisionData, null,
                        getContext().getMainExecutor(), error::offer));

        if (!sIsSatelliteSupported) {
            Log.d(TAG, "testProvisionSatelliteService: Satellite is not supported "
                    + "on the device.");
            return;
        }

        grantSatellitePermission();

        SatelliteProvisionStateCallbackTest satelliteProvisionStateCallback =
                new SatelliteProvisionStateCallbackTest();
        long registerError = sSatelliteManager.registerForSatelliteProvisionStateChanged(
                getContext().getMainExecutor(), satelliteProvisionStateCallback);
        assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerError);

        if (isSatelliteProvisioned()) {
            if (!deprovisionSatellite()) {
                Log.d(TAG, "testProvisionSatelliteService: deprovisionSatellite failed");
                return;
            }
            assertTrue(satelliteProvisionStateCallback.waitUntilResult(1));
            assertFalse(satelliteProvisionStateCallback.isProvisioned);
        }

        CancellationSignal cancellationSignal = new CancellationSignal();
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
        Log.d(TAG, "testProvisionSatelliteService: provision result=" + errorCode);

        if (sIsProvisionable) {
            /**
             * Modem might send either 1 or 2 provision state change events.
             */
            satelliteProvisionStateCallback.waitUntilResult(2);
            assertFalse(satelliteProvisionStateCallback.isProvisioned);
            assertFalse(isSatelliteProvisioned());

            assertTrue(provisionSatellite());
            assertTrue(satelliteProvisionStateCallback.waitUntilResult(1));
            assertTrue(satelliteProvisionStateCallback.isProvisioned);
            assertTrue(isSatelliteProvisioned());
        }
        sSatelliteManager.unregisterForSatelliteProvisionStateChanged(
                satelliteProvisionStateCallback);

        revokeSatellitePermission();
    }

    @Test
    public void testDeprovisionSatelliteService() {
        if (!shouldTestSatellite()) return;

        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class,
                () -> sSatelliteManager.deprovisionSatelliteService(
                        "", getContext().getMainExecutor(), error::offer));
    }

    @Test
    public void testRegisterForSatelliteProvisionStateChanged() {
        if (!shouldTestSatellite()) return;

        SatelliteProvisionStateCallbackTest satelliteProvisionStateCallback =
                new SatelliteProvisionStateCallbackTest();

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class,
                () -> sSatelliteManager.registerForSatelliteProvisionStateChanged(
                        getContext().getMainExecutor(), satelliteProvisionStateCallback));
    }

    @Test
    public void testRequestIsSatelliteProvisioned() {
        if (!shouldTestSatellite()) return;

        final AtomicReference<Boolean> provisioned = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Boolean result) {
                        provisioned.set(result);
                    }

                    @Override
                    public void onError(SatelliteManager.SatelliteException exception) {
                        errorCode.set(exception.getErrorCode());
                    }
                };

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class,
                () -> sSatelliteManager.requestIsSatelliteProvisioned(
                        getContext().getMainExecutor(), receiver));
    }

    @Test
    public void testRequestSatelliteEnabled() throws Exception {
        if (!shouldTestSatellite()) return;

        LinkedBlockingQueue<Integer> error = new LinkedBlockingQueue<>(1);

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class, () -> sSatelliteManager.requestSatelliteEnabled(
                true, true, getContext().getMainExecutor(), error::offer));
        assertThrows(SecurityException.class, () -> sSatelliteManager.requestSatelliteEnabled(
                false, true, getContext().getMainExecutor(), error::offer));
    }

    @Test
    public void testRequestIsSatelliteEnabled() {
        if (!shouldTestSatellite()) return;

        final AtomicReference<Boolean> enabled = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Boolean result) {
                        Log.d(TAG, "onResult: result=" + result);
                        enabled.set(result);
                    }

                    @Override
                    public void onError(SatelliteManager.SatelliteException exception) {
                        Log.d(TAG, "onError: onError=" + exception);
                        errorCode.set(exception.getErrorCode());
                    }
                };

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class, () -> sSatelliteManager.requestIsSatelliteEnabled(
                getContext().getMainExecutor(), receiver));
    }

    @Test
    public void testRequestIsSatelliteSupported() throws Exception {
        if (!shouldTestSatellite()) return;

        final AtomicReference<Boolean> supported = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Boolean result) {
                        supported.set(result);
                        latch.countDown();
                    }

                    @Override
                    public void onError(SatelliteManager.SatelliteException exception) {
                        errorCode.set(exception.getErrorCode());
                        latch.countDown();
                    }
                };

        sSatelliteManager.requestIsSatelliteSupported(getContext().getMainExecutor(),
                receiver);
        assertTrue(latch.await(TIMEOUT, TimeUnit.MILLISECONDS));

        Integer error = errorCode.get();
        Boolean isSupported = supported.get();
        if (error == null) {
            assertNotNull(isSupported);
            Log.d(TAG, "testRequestIsSatelliteSupported isSupported=" + isSupported);
        } else {
            assertNull(isSupported);
            Log.d(TAG, "testRequestIsSatelliteSupported error=" + error);
        }
    }

    @Test
    public void testRequestSatelliteCapabilities() {
        if (!shouldTestSatellite()) return;

        final AtomicReference<SatelliteCapabilities> capabilities = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<SatelliteCapabilities, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(SatelliteCapabilities result) {
                        capabilities.set(result);
                    }

                    @Override
                    public void onError(SatelliteManager.SatelliteException exception) {
                        errorCode.set(exception.getErrorCode());
                    }
                };

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class, () -> sSatelliteManager.requestSatelliteCapabilities(
                getContext().getMainExecutor(), receiver));
    }

    @Test
    public void testSatelliteModemStateChanged() {
        if (!shouldTestSatellite()) return;

        SatelliteStateCallbackTest callback = new SatelliteStateCallbackTest();

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class, ()-> sSatelliteManager
                .registerForSatelliteModemStateChanged(getContext().getMainExecutor(), callback));

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class, ()-> sSatelliteManager
                .unregisterForSatelliteModemStateChanged(callback));

        if (!sIsSatelliteSupported) {
            Log.d(TAG, "Satellite is not supported on the device");
            return;
        }

        grantSatellitePermission();

        if (!isSatelliteProvisioned()) {
            Log.d(TAG, "Satellite is not provisioned yet");
            return;
        }

        boolean originalEnabledState = isSatelliteEnabled();
        boolean registerCallback = false;
        if (originalEnabledState) {
            registerCallback = true;

            long registerResult = sSatelliteManager.registerForSatelliteModemStateChanged(
                    getContext().getMainExecutor(), callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));

            requestSatelliteEnabled(false);

            assertTrue(callback.waitUntilResult(1));
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
            assertFalse(isSatelliteEnabled());
        }
        if (!registerCallback) {
            long registerResult = sSatelliteManager
                    .registerForSatelliteModemStateChanged(getContext().getMainExecutor(),
                            callback);
            assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
            assertTrue(callback.waitUntilResult(1));
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback.modemState);
        }

        requestSatelliteEnabled(true);
        assertTrue(callback.waitUntilResult(1));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE, callback.modemState);
        assertTrue(isSatelliteEnabled());

        SatelliteStateCallbackTest callback1 = new SatelliteStateCallbackTest();
        long registerResult = sSatelliteManager
                .registerForSatelliteModemStateChanged(getContext().getMainExecutor(), callback1);
        assertEquals(SatelliteManager.SATELLITE_ERROR_NONE, registerResult);
        assertTrue(callback1.waitUntilResult(1));
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE, callback1.modemState);
        sSatelliteManager.unregisterForSatelliteModemStateChanged(callback);

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);
        String mText = "This is a test datagram message";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());
        callback1.clearModemStates();
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
        Log.d(TAG, "testSatelliteModemStateChanged: sendSatelliteDatagram errorCode="
                + errorCode);

        assertFalse(callback.waitUntilResult(1));
        assertTrue(callback1.waitUntilResult(2));
        assertTrue(callback1.getTotalCountOfModemStates() >= 2);
        assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_DATAGRAM_TRANSFERRING,
                callback1.getModemState(0));
        if (errorCode == SatelliteManager.SATELLITE_ERROR_NONE) {
            /**
             * Modem state should have the following transitions:
             * 1) IDLE to TRANSFERRING.
             * 2) TRANSFERRING to LISTENING.
             * 3) LISTENING to IDLE
             */
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_LISTENING,
                    callback1.getModemState(1));
            /**
             * Satellite will stay at LISTENING mode for 3 minutes by default. Thus, we will skip
             * checking the last state transition.
             */
        } else {
            /**
             * Modem state should have the following transitions:
             * 1) IDLE to TRANSFERRING.
             * 2) TRANSFERRING to IDLE.
             */
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_IDLE,
                    callback1.getModemState(1));
        }

        if (!originalEnabledState) {
            // Restore original modem enabled state.
            requestSatelliteEnabled(false);

            assertFalse(callback.waitUntilResult(1));
            assertTrue(callback1.waitUntilResult(1));
            assertEquals(SatelliteManager.SATELLITE_MODEM_STATE_OFF, callback1.modemState);
            assertFalse(isSatelliteEnabled());
        }
        sSatelliteManager.unregisterForSatelliteModemStateChanged(callback1);

        revokeSatellitePermission();
    }

    @Test
    public void testSatelliteDatagramCallback() throws Exception {
        if (!shouldTestSatellite()) return;

        SatelliteDatagramCallbackTest callback = new SatelliteDatagramCallbackTest();

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class, ()-> sSatelliteManager
                .registerForSatelliteDatagram(getContext().getMainExecutor(), callback));

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class, ()-> sSatelliteManager
                .unregisterForSatelliteDatagram(callback));
    }

    @Test
    public void testPollPendingSatelliteDatagrams() throws Exception {
        if (!shouldTestSatellite()) return;

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class,
                ()-> sSatelliteManager.pollPendingSatelliteDatagrams(
                        getContext().getMainExecutor(), resultListener::offer));
    }

    @Test
    public void testSendSatelliteDatagram() {
        if (!shouldTestSatellite()) return;

        LinkedBlockingQueue<Integer> resultListener = new LinkedBlockingQueue<>(1);

        String mText = "This is a test datagram message";
        SatelliteDatagram datagram = new SatelliteDatagram(mText.getBytes());

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class,
                ()-> sSatelliteManager.sendSatelliteDatagram(
                        SatelliteManager.DATAGRAM_TYPE_SOS_MESSAGE, datagram, true,
                        getContext().getMainExecutor(), resultListener::offer));
        // TODO: add detailed test
    }

    @Test
    public void testRequestIsSatelliteCommunicationAllowedForCurrentLocation() {
        if (!shouldTestSatellite()) return;

        final AtomicReference<Boolean> enabled = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Boolean, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Boolean result) {
                        enabled.set(result);
                    }

                    @Override
                    public void onError(SatelliteManager.SatelliteException exception) {
                        errorCode.set(exception.getErrorCode());
                    }
                };

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class, () -> sSatelliteManager.requestIsSatelliteEnabled(
                getContext().getMainExecutor(), receiver));
    }

    @Test
    public void testRequestTimeForNextSatelliteVisibility() {
        if (!shouldTestSatellite()) return;

        final AtomicReference<Duration> nextVisibilityDuration = new AtomicReference<>();
        final AtomicReference<Integer> errorCode = new AtomicReference<>();
        OutcomeReceiver<Duration, SatelliteManager.SatelliteException> receiver =
                new OutcomeReceiver<>() {
                    @Override
                    public void onResult(Duration result) {
                        nextVisibilityDuration.set(result);
                    }

                    @Override
                    public void onError(SatelliteManager.SatelliteException exception) {
                        errorCode.set(exception.getErrorCode());
                    }
                };

        // Throws SecurityException as we do not have SATELLITE_COMMUNICATION permission.
        assertThrows(SecurityException.class,
                () -> sSatelliteManager.requestTimeForNextSatelliteVisibility(
                        getContext().getMainExecutor(), receiver));
    }
}
