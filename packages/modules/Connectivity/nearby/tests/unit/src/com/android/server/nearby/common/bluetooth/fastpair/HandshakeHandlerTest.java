/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.server.nearby.common.bluetooth.fastpair;

import static com.android.server.nearby.common.bluetooth.fastpair.Constants.BLUETOOTH_ADDRESS_LENGTH;
import static com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.KeyBasedPairingCharacteristic.ActionOverBleFlag.ADDITIONAL_DATA_CHARACTERISTIC;
import static com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.KeyBasedPairingCharacteristic.ActionOverBleFlag.DEVICE_ACTION;
import static com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.KeyBasedPairingCharacteristic.KeyBasedPairingRequestFlag.PROVIDER_INITIATES_BONDING;
import static com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.KeyBasedPairingCharacteristic.KeyBasedPairingRequestFlag.REQUEST_DEVICE_NAME;
import static com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.KeyBasedPairingCharacteristic.KeyBasedPairingRequestFlag.REQUEST_DISCOVERABLE;
import static com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.KeyBasedPairingCharacteristic.KeyBasedPairingRequestFlag.REQUEST_RETROACTIVE_PAIR;
import static com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.KeyBasedPairingCharacteristic.Request.ADDITIONAL_DATA_TYPE_INDEX;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;

import android.platform.test.annotations.Presubmit;

import androidx.annotation.Nullable;
import androidx.core.util.Consumer;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SdkSuppress;
import androidx.test.filters.SmallTest;

import com.android.server.nearby.common.bluetooth.BluetoothException;
import com.android.server.nearby.common.bluetooth.BluetoothGattException;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.AdditionalDataCharacteristic.AdditionalDataType;
import com.android.server.nearby.common.bluetooth.fastpair.Constants.FastPairService.KeyBasedPairingCharacteristic.Request;
import com.android.server.nearby.common.bluetooth.gatt.BluetoothGattConnection;
import com.android.server.nearby.common.bluetooth.testability.android.bluetooth.BluetoothAdapter;
import com.android.server.nearby.common.bluetooth.util.BluetoothOperationExecutor;
import com.android.server.nearby.intdefs.NearbyEventIntDefs;

import com.google.common.collect.ImmutableSet;
import com.google.common.io.BaseEncoding;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.security.GeneralSecurityException;
import java.time.Duration;
import java.util.Arrays;

/**
 * Unit tests for {@link HandshakeHandler}.
 */
@Presubmit
@SmallTest
@RunWith(AndroidJUnit4.class)
public class HandshakeHandlerTest {

    public static final byte[] PUBLIC_KEY =
            BaseEncoding.base64().decode(
                    "d2JTfvfdS6u7LmGfMOmco3C7ra3lW1k17AOly0LrBydDZURacfTY"
                            + "IMmo5K1ejfD9e8b6qHsDTNzselhifi10kQ==");
    private static final String SEEKER_ADDRESS = "A1:A2:A3:A4:A5:A6";
    private static final String PROVIDER_BLE_ADDRESS = "11:22:33:44:55:66";
    /**
     * The random-resolvable private address (RPA) is sometimes used when advertising over BLE, to
     * hide the static public address (otherwise, the Fast Pair device would b
     * identifiable/trackable whenever it's BLE advertising).
     */
    private static final String RANDOM_PRIVATE_ADDRESS = "BB:BB:BB:BB:BB:1E";
    private static final byte[] SHARED_SECRET =
            BaseEncoding.base16().decode("0123456789ABCDEF0123456789ABCDEF");

    @Mock EventLoggerWrapper mEventLoggerWrapper;
    @Mock BluetoothGattConnection mBluetoothGattConnection;
    @Mock BluetoothGattConnection.ChangeObserver mChangeObserver;
    @Mock private Consumer<Integer> mRescueFromError;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void handshakeGattError_noRetryError_failed() throws BluetoothException {
        HandshakeHandler.KeyBasedPairingRequest keyBasedPairingRequest =
                new HandshakeHandler.KeyBasedPairingRequest.Builder()
                        .setVerificationData(BluetoothAddress.decode(PROVIDER_BLE_ADDRESS))
                        .build();
        BluetoothGattException exception =
                new BluetoothGattException("Exception for no retry", 257);
        when(mChangeObserver.waitForUpdate(anyLong())).thenThrow(exception);
        GattConnectionManager gattConnectionManager =
                createGattConnectionManager(Preferences.builder(), () -> {});
        gattConnectionManager.setGattConnection(mBluetoothGattConnection);
        when(mBluetoothGattConnection.enableNotification(any(), any()))
                .thenReturn(mChangeObserver);
        InOrder inOrder = inOrder(mEventLoggerWrapper);

        assertThrows(
                BluetoothGattException.class,
                () ->
                        getHandshakeHandler(gattConnectionManager, address -> address)
                                .doHandshakeWithRetryAndSignalLostCheck(
                                        PUBLIC_KEY,
                                        keyBasedPairingRequest,
                                        mRescueFromError));

        inOrder.verify(mEventLoggerWrapper).setCurrentEvent(
                NearbyEventIntDefs.EventCode.SECRET_HANDSHAKE_GATT_COMMUNICATION);
        inOrder.verify(mEventLoggerWrapper).logCurrentEventFailed(exception);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void handshakeGattError_retryAndNoCount_throwException() throws BluetoothException {
        HandshakeHandler.KeyBasedPairingRequest keyBasedPairingRequest =
                new HandshakeHandler.KeyBasedPairingRequest.Builder()
                        .setVerificationData(BluetoothAddress.decode(PROVIDER_BLE_ADDRESS))
                        .build();
        BluetoothGattException exception = new BluetoothGattException("Exception for retry", 133);
        when(mChangeObserver.waitForUpdate(anyLong())).thenThrow(exception);
        GattConnectionManager gattConnectionManager =
                createGattConnectionManager(Preferences.builder(), () -> {});
        gattConnectionManager.setGattConnection(mBluetoothGattConnection);
        when(mBluetoothGattConnection.enableNotification(any(), any()))
                .thenReturn(mChangeObserver);
        InOrder inOrder = inOrder(mEventLoggerWrapper);

        HandshakeHandler.HandshakeException handshakeException =
                assertThrows(
                        HandshakeHandler.HandshakeException.class,
                        () -> getHandshakeHandler(gattConnectionManager, address -> address)
                                .doHandshakeWithRetryAndSignalLostCheck(
                                        PUBLIC_KEY, keyBasedPairingRequest, mRescueFromError));

        inOrder.verify(mEventLoggerWrapper)
                .setCurrentEvent(NearbyEventIntDefs.EventCode.SECRET_HANDSHAKE_GATT_COMMUNICATION);
        inOrder.verify(mEventLoggerWrapper).logCurrentEventFailed(exception);
        inOrder.verify(mEventLoggerWrapper)
                .setCurrentEvent(NearbyEventIntDefs.EventCode.SECRET_HANDSHAKE_GATT_COMMUNICATION);
        inOrder.verify(mEventLoggerWrapper).logCurrentEventFailed(exception);
        inOrder.verify(mEventLoggerWrapper)
                .setCurrentEvent(NearbyEventIntDefs.EventCode.SECRET_HANDSHAKE_GATT_COMMUNICATION);
        inOrder.verify(mEventLoggerWrapper).logCurrentEventFailed(exception);
        inOrder.verify(mEventLoggerWrapper)
                .setCurrentEvent(NearbyEventIntDefs.EventCode.SECRET_HANDSHAKE_GATT_COMMUNICATION);
        inOrder.verify(mEventLoggerWrapper).logCurrentEventFailed(exception);
        inOrder.verifyNoMoreInteractions();
        assertThat(handshakeException.getOriginalException()).isEqualTo(exception);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void handshakeGattError_noRetryOnTimeout_throwException() throws BluetoothException {
        HandshakeHandler.KeyBasedPairingRequest keyBasedPairingRequest =
                new HandshakeHandler.KeyBasedPairingRequest.Builder()
                        .setVerificationData(BluetoothAddress.decode(PROVIDER_BLE_ADDRESS))
                        .build();
        BluetoothOperationExecutor.BluetoothOperationTimeoutException exception =
                new BluetoothOperationExecutor.BluetoothOperationTimeoutException("Test timeout");
        when(mChangeObserver.waitForUpdate(anyLong())).thenThrow(exception);
        GattConnectionManager gattConnectionManager =
                createGattConnectionManager(Preferences.builder(), () -> {});
        gattConnectionManager.setGattConnection(mBluetoothGattConnection);
        when(mBluetoothGattConnection.enableNotification(any(), any()))
                .thenReturn(mChangeObserver);
        InOrder inOrder = inOrder(mEventLoggerWrapper);

        assertThrows(
                HandshakeHandler.HandshakeException.class,
                () ->
                        new HandshakeHandler(
                                gattConnectionManager,
                                PROVIDER_BLE_ADDRESS,
                                Preferences.builder().setRetrySecretHandshakeTimeout(false).build(),
                                mEventLoggerWrapper,
                                address -> address)
                                .doHandshakeWithRetryAndSignalLostCheck(
                                        PUBLIC_KEY, keyBasedPairingRequest, mRescueFromError));

        inOrder.verify(mEventLoggerWrapper)
                .setCurrentEvent(NearbyEventIntDefs.EventCode.SECRET_HANDSHAKE_GATT_COMMUNICATION);
        inOrder.verify(mEventLoggerWrapper).logCurrentEventFailed(exception);
        inOrder.verifyNoMoreInteractions();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void handshakeGattError_signalLost() throws BluetoothException {
        HandshakeHandler.KeyBasedPairingRequest keyBasedPairingRequest =
                new HandshakeHandler.KeyBasedPairingRequest.Builder()
                        .setVerificationData(BluetoothAddress.decode(PROVIDER_BLE_ADDRESS))
                        .build();
        BluetoothGattException exception = new BluetoothGattException("Exception for retry", 133);
        when(mChangeObserver.waitForUpdate(anyLong())).thenThrow(exception);
        GattConnectionManager gattConnectionManager =
                createGattConnectionManager(Preferences.builder(), () -> {});
        gattConnectionManager.setGattConnection(mBluetoothGattConnection);
        when(mBluetoothGattConnection.enableNotification(any(), any()))
                .thenReturn(mChangeObserver);
        InOrder inOrder = inOrder(mEventLoggerWrapper);

        SignalLostException signalLostException =
                assertThrows(
                        SignalLostException.class,
                        () -> getHandshakeHandler(gattConnectionManager, address -> null)
                                .doHandshakeWithRetryAndSignalLostCheck(
                                        PUBLIC_KEY, keyBasedPairingRequest, mRescueFromError));

        inOrder.verify(mEventLoggerWrapper)
                .setCurrentEvent(NearbyEventIntDefs.EventCode.SECRET_HANDSHAKE_GATT_COMMUNICATION);
        inOrder.verify(mEventLoggerWrapper).logCurrentEventFailed(exception);
        assertThat(signalLostException).hasCauseThat().isEqualTo(exception);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void handshakeGattError_addressRotate() throws BluetoothException {
        HandshakeHandler.KeyBasedPairingRequest keyBasedPairingRequest =
                new HandshakeHandler.KeyBasedPairingRequest.Builder()
                        .setVerificationData(BluetoothAddress.decode(PROVIDER_BLE_ADDRESS))
                        .build();
        BluetoothGattException exception = new BluetoothGattException("Exception for retry", 133);
        when(mChangeObserver.waitForUpdate(anyLong())).thenThrow(exception);
        GattConnectionManager gattConnectionManager =
                createGattConnectionManager(Preferences.builder(), () -> {});
        gattConnectionManager.setGattConnection(mBluetoothGattConnection);
        when(mBluetoothGattConnection.enableNotification(any(), any()))
                .thenReturn(mChangeObserver);
        InOrder inOrder = inOrder(mEventLoggerWrapper);

        SignalRotatedException signalRotatedException =
                assertThrows(
                        SignalRotatedException.class,
                        () -> getHandshakeHandler(
                                gattConnectionManager, address -> "AA:BB:CC:DD:EE:FF")
                                .doHandshakeWithRetryAndSignalLostCheck(
                                        PUBLIC_KEY, keyBasedPairingRequest, mRescueFromError));

        inOrder.verify(mEventLoggerWrapper).setCurrentEvent(
                NearbyEventIntDefs.EventCode.SECRET_HANDSHAKE_GATT_COMMUNICATION);
        inOrder.verify(mEventLoggerWrapper).logCurrentEventFailed(exception);
        assertThat(signalRotatedException.getNewAddress()).isEqualTo("AA:BB:CC:DD:EE:FF");
        assertThat(signalRotatedException).hasCauseThat().isEqualTo(exception);
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void constructBytes_setRetroactiveFlag_decodeCorrectly() throws
            GeneralSecurityException {
        HandshakeHandler.KeyBasedPairingRequest keyBasedPairingRequest =
                new HandshakeHandler.KeyBasedPairingRequest.Builder()
                        .setVerificationData(BluetoothAddress.decode(PROVIDER_BLE_ADDRESS))
                        .addFlag(REQUEST_RETROACTIVE_PAIR)
                        .setSeekerPublicAddress(BluetoothAddress.decode(SEEKER_ADDRESS))
                        .build();

        byte[] encryptedRawMessage =
                AesEcbSingleBlockEncryption.encrypt(
                        SHARED_SECRET, keyBasedPairingRequest.getBytes());
        HandshakeRequest handshakeRequest =
                new HandshakeRequest(SHARED_SECRET, encryptedRawMessage);

        assertThat(handshakeRequest.getType())
                .isEqualTo(HandshakeRequest.Type.KEY_BASED_PAIRING_REQUEST);
        assertThat(handshakeRequest.requestRetroactivePair()).isTrue();
        assertThat(handshakeRequest.getVerificationData())
                .isEqualTo(BluetoothAddress.decode(PROVIDER_BLE_ADDRESS));
        assertThat(handshakeRequest.getSeekerPublicAddress())
                .isEqualTo(BluetoothAddress.decode(SEEKER_ADDRESS));
        assertThat(handshakeRequest.requestDeviceName()).isFalse();
        assertThat(handshakeRequest.requestDiscoverable()).isFalse();
        assertThat(handshakeRequest.requestProviderInitialBonding()).isFalse();
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void getTimeout_notOverShortRetryMaxSpentTime_getShort() {
        Preferences preferences = Preferences.builder().build();

        assertThat(getHandshakeHandler(/* getEnable128BitCustomGattCharacteristicsId= */ true)
                .getTimeoutMs(
                        preferences.getSecretHandshakeShortTimeoutRetryMaxSpentTimeMs()
                                - 1))
                .isEqualTo(preferences.getSecretHandshakeShortTimeoutMs());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void getTimeout_overShortRetryMaxSpentTime_getLong() {
        Preferences preferences = Preferences.builder().build();

        assertThat(getHandshakeHandler(/* getEnable128BitCustomGattCharacteristicsId= */ true)
                .getTimeoutMs(
                        preferences.getSecretHandshakeShortTimeoutRetryMaxSpentTimeMs()
                                + 1))
                .isEqualTo(preferences.getSecretHandshakeLongTimeoutMs());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void getTimeout_retryNotEnabled_getOrigin() {
        Preferences preferences = Preferences.builder().build();

        assertThat(
                new HandshakeHandler(
                        createGattConnectionManager(Preferences.builder(), () -> {}),
                        PROVIDER_BLE_ADDRESS,
                        Preferences.builder()
                                .setRetryGattConnectionAndSecretHandshake(false).build(),
                        mEventLoggerWrapper,
                        /* fastPairSignalChecker= */ null)
                        .getTimeoutMs(0))
                .isEqualTo(Duration.ofSeconds(
                        preferences.getGattOperationTimeoutSeconds()).toMillis());
    }

    @Test
    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void triggersActionOverBle_notCrash() {
        HandshakeHandler.ActionOverBle.Builder actionOverBleBuilder =
                new HandshakeHandler.ActionOverBle.Builder()
                        .addFlag(
                                Constants.FastPairService.KeyBasedPairingCharacteristic
                                        .ActionOverBleFlag.ADDITIONAL_DATA_CHARACTERISTIC)
                        .setVerificationData(BluetoothAddress.decode(RANDOM_PRIVATE_ADDRESS))
                        .setAdditionalDataType(AdditionalDataType.PERSONALIZED_NAME)
                        .setEvent(0, 0)
                        .setEventAdditionalData(new byte[]{1})
                        .getThis();
        HandshakeHandler.ActionOverBle actionOverBle = actionOverBleBuilder.build();
        assertThat(actionOverBle.getBytes().length).isEqualTo(16);
        assertThat(
                Arrays.equals(
                        Arrays.copyOfRange(actionOverBle.getBytes(), 0, 12),
                        new byte[]{
                                (byte) 16, (byte)  -64, (byte) -69, (byte) -69,
                                (byte) -69, (byte)  -69, (byte) -69, (byte) 30,
                                (byte) 0, (byte) 0, (byte) 1, (byte) 1}))
                .isTrue();
    }

    private GattConnectionManager createGattConnectionManager(
            Preferences.Builder prefs, ToggleBluetoothTask toggleBluetooth) {
        return new GattConnectionManager(
                ApplicationProvider.getApplicationContext(),
                prefs.build(),
                new EventLoggerWrapper(null),
                BluetoothAdapter.getDefaultAdapter(),
                toggleBluetooth,
                PROVIDER_BLE_ADDRESS,
                new TimingLogger("GattConnectionManager", prefs.build()),
                /* fastPairSignalChecker= */ null,
                /* setMtu= */ false);
    }

    private HandshakeHandler getHandshakeHandler(
            GattConnectionManager gattConnectionManager,
            @Nullable FastPairConnection.FastPairSignalChecker fastPairSignalChecker) {
        return new HandshakeHandler(
                gattConnectionManager,
                PROVIDER_BLE_ADDRESS,
                Preferences.builder()
                        .setGattConnectionAndSecretHandshakeNoRetryGattError(ImmutableSet.of(257))
                        .setRetrySecretHandshakeTimeout(true)
                        .build(),
                mEventLoggerWrapper,
                fastPairSignalChecker);
    }

    private HandshakeHandler getHandshakeHandler(
            boolean getEnable128BitCustomGattCharacteristicsId) {
        return new HandshakeHandler(
                createGattConnectionManager(Preferences.builder(), () -> {}),
                PROVIDER_BLE_ADDRESS,
                Preferences.builder()
                        .setGattOperationTimeoutSeconds(5)
                        .setEnable128BitCustomGattCharacteristicsId(
                                getEnable128BitCustomGattCharacteristicsId)
                        .build(),
                mEventLoggerWrapper,
                /* fastPairSignalChecker= */ null);
    }

    private static class HandshakeRequest {

        /**
         * 16 bytes data: 1-byte for type, 1-byte for flags, 6-bytes for provider's BLE address, 8
         * bytes optional data.
         *
         * @see {go/fast-pair-spec-handshake-message1}
         */
        private final byte[] mDecryptedMessage;

        HandshakeRequest(byte[] key, byte[] encryptedPairingRequest)
                throws GeneralSecurityException {
            mDecryptedMessage = AesEcbSingleBlockEncryption.decrypt(key, encryptedPairingRequest);
        }

        /**
         * Gets the type of this handshake request. Currently, we have 2 types: 0x00 for Key-based
         * Pairing Request and 0x10 for Action Request.
         */
        public Type getType() {
            return Type.valueOf(mDecryptedMessage[Request.TYPE_INDEX]);
        }

        /**
         * Gets verification data of this handshake request.
         * Currently, we use Provider's BLE address.
         */
        public byte[] getVerificationData() {
            return Arrays.copyOfRange(
                    mDecryptedMessage,
                    Request.VERIFICATION_DATA_INDEX,
                    Request.VERIFICATION_DATA_INDEX + Request.VERIFICATION_DATA_LENGTH);
        }

        /** Gets Seeker's public address of the handshake request. */
        public byte[] getSeekerPublicAddress() {
            return Arrays.copyOfRange(
                    mDecryptedMessage,
                    Request.SEEKER_PUBLIC_ADDRESS_INDEX,
                    Request.SEEKER_PUBLIC_ADDRESS_INDEX + BLUETOOTH_ADDRESS_LENGTH);
        }

        /** Checks whether the Seeker request discoverability from flags byte. */
        public boolean requestDiscoverable() {
            return (getFlags() & REQUEST_DISCOVERABLE) != 0;
        }

        /**
         * Checks whether the Seeker requests that the Provider shall initiate bonding from
         * flags byte.
         */
        public boolean requestProviderInitialBonding() {
            return (getFlags() & PROVIDER_INITIATES_BONDING) != 0;
        }

        /** Checks whether the Seeker requests that the Provider shall notify the existing name. */
        public boolean requestDeviceName() {
            return (getFlags() & REQUEST_DEVICE_NAME) != 0;
        }

        /** Checks whether this is for retroactively writing account key. */
        public boolean requestRetroactivePair() {
            return (getFlags() & REQUEST_RETROACTIVE_PAIR) != 0;
        }

        /** Gets the flags of this handshake request. */
        private byte getFlags() {
            return mDecryptedMessage[Request.FLAGS_INDEX];
        }

        /** Checks whether the Seeker requests a device action. */
        public boolean requestDeviceAction() {
            return (getFlags() & DEVICE_ACTION) != 0;
        }

        /**
         * Checks whether the Seeker requests an action which will be followed by an additional
         * data.
         */
        public boolean requestFollowedByAdditionalData() {
            return (getFlags() & ADDITIONAL_DATA_CHARACTERISTIC) != 0;
        }

        /** Gets the {@link AdditionalDataType} of this handshake request. */
        @AdditionalDataType
        public int getAdditionalDataType() {
            if (!requestFollowedByAdditionalData()
                    || mDecryptedMessage.length <= ADDITIONAL_DATA_TYPE_INDEX) {
                return AdditionalDataType.UNKNOWN;
            }
            return mDecryptedMessage[ADDITIONAL_DATA_TYPE_INDEX];
        }

        /** Enumerates the handshake message types. */
        public enum Type {
            KEY_BASED_PAIRING_REQUEST(Request.TYPE_KEY_BASED_PAIRING_REQUEST),
            ACTION_OVER_BLE(Request.TYPE_ACTION_OVER_BLE),
            UNKNOWN((byte) 0xFF);

            private final byte mValue;

            Type(byte type) {
                mValue = type;
            }

            public static Type valueOf(byte value) {
                for (Type type : Type.values()) {
                    if (type.getValue() == value) {
                        return type;
                    }
                }
                return UNKNOWN;
            }

            public byte getValue() {
                return mValue;
            }
        }
    }
}
