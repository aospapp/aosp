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

package com.android.server.nearby.presence;

import static com.google.common.truth.Truth.assertThat;

import android.nearby.BroadcastRequest;
import android.nearby.DataElement;
import android.nearby.PresenceBroadcastRequest;
import android.nearby.PresenceCredential;
import android.nearby.PrivateCredential;
import android.nearby.PublicCredential;

import com.android.server.nearby.util.encryption.CryptorImpIdentityV1;
import com.android.server.nearby.util.encryption.CryptorImpV1;

import org.junit.Before;
import org.junit.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

public class ExtendedAdvertisementTest {
    private static final int IDENTITY_TYPE = PresenceCredential.IDENTITY_TYPE_PRIVATE;
    private static final int DATA_TYPE_MODEL_ID = 7;
    private static final int DATA_TYPE_BLE_ADDRESS = 101;
    private static final int DATA_TYPE_PUBLIC_IDENTITY = 3;
    private static final byte[] MODE_ID_DATA =
            new byte[]{2, 1, 30, 2, 10, -16, 6, 22, 44, -2, -86, -69, -52};
    private static final byte[] BLE_ADDRESS = new byte[]{124, 4, 56, 60, 120, -29, -90};
    private static final DataElement MODE_ID_ADDRESS_ELEMENT =
            new DataElement(DATA_TYPE_MODEL_ID, MODE_ID_DATA);
    private static final DataElement BLE_ADDRESS_ELEMENT =
            new DataElement(DATA_TYPE_BLE_ADDRESS, BLE_ADDRESS);

    private static final byte[] IDENTITY =
            new byte[]{1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4};
    private static final int MEDIUM_TYPE_BLE = 0;
    private static final byte[] SALT = {2, 3};
    private static final int PRESENCE_ACTION_1 = 1;
    private static final int PRESENCE_ACTION_2 = 2;

    private static final byte[] SECRET_ID = new byte[]{1, 2, 3, 4};
    private static final byte[] AUTHENTICITY_KEY =
            new byte[]{-97, 10, 107, -86, 25, 65, -54, -95, -72, 59, 54, 93, 9, 3, -24, -88};
    private static final byte[] PUBLIC_KEY =
            new byte[] {
                    48, 89, 48, 19, 6, 7, 42, -122, 72, -50, 61, 2, 1, 6, 8, 42, -122, 72, -50, 61,
                    66, 0, 4, -56, -39, -92, 69, 0, 52, 23, 67, 83, -14, 75, 52, -14, -5, -41, 48,
                    -83, 31, 42, -39, 102, -13, 22, -73, -73, 86, 30, -96, -84, -13, 4, 122, 104,
                    -65, 64, 91, -109, -45, -35, -56, 55, -79, 47, -85, 27, -96, -119, -82, -80,
                    123, 41, -119, -25, 1, -112, 112
            };
    private static final byte[] ENCRYPTED_METADATA_BYTES =
            new byte[] {
                    -44, -25, -95, -124, -7, 90, 116, -8, 7, -120, -23, -22, -106, -44, -19, 61,
                    -18, 39, 29, 78, 108, -11, -39, 85, -30, 64, -99, 102, 65, 37, -42, 114, -37,
                    88, -112, 8, -75, -53, 23, -16, -104, 67, 49, 48, -53, 73, -109, 44, -23, -11,
                    -118, -61, -37, -104, 60, 105, 115, 1, 56, -89, -107, -45, -116, -1, -25, 84,
                    -19, -128, 81, 11, 92, 77, -58, 82, 122, 123, 31, -87, -57, 70, 23, -81, 7, 2,
                    -114, -83, 74, 124, -68, -98, 47, 91, 9, 48, -67, 41, -7, -97, 78, 66, -65, 58,
                    -4, -46, -30, -85, -50, 100, 46, -66, -128, 7, 66, 9, 88, 95, 12, -13, 81, -91,
            };
    private static final byte[] METADATA_ENCRYPTION_KEY_TAG =
            new byte[] {-126, -104, 1, -1, 26, -46, -68, -86};
    private static final String DEVICE_NAME = "test_device";

    private PresenceBroadcastRequest.Builder mBuilder;
    private PrivateCredential mPrivateCredential;
    private PublicCredential mPublicCredential;

    @Before
    public void setUp() {
        mPrivateCredential =
                new PrivateCredential.Builder(SECRET_ID, AUTHENTICITY_KEY, IDENTITY, DEVICE_NAME)
                        .setIdentityType(PresenceCredential.IDENTITY_TYPE_PRIVATE)
                        .build();
        mPublicCredential =
                new PublicCredential.Builder(SECRET_ID, AUTHENTICITY_KEY, PUBLIC_KEY,
                        ENCRYPTED_METADATA_BYTES, METADATA_ENCRYPTION_KEY_TAG)
                        .build();
        mBuilder =
                new PresenceBroadcastRequest.Builder(Collections.singletonList(MEDIUM_TYPE_BLE),
                        SALT, mPrivateCredential)
                        .setVersion(BroadcastRequest.PRESENCE_VERSION_V1)
                        .addAction(PRESENCE_ACTION_1)
                        .addAction(PRESENCE_ACTION_2)
                        .addExtendedProperty(new DataElement(DATA_TYPE_BLE_ADDRESS, BLE_ADDRESS))
                        .addExtendedProperty(new DataElement(DATA_TYPE_MODEL_ID, MODE_ID_DATA));
    }

    @Test
    public void test_createFromRequest() {
        ExtendedAdvertisement originalAdvertisement = ExtendedAdvertisement.createFromRequest(
                mBuilder.build());

        assertThat(originalAdvertisement.getActions())
                .containsExactly(PRESENCE_ACTION_1, PRESENCE_ACTION_2);
        assertThat(originalAdvertisement.getIdentity()).isEqualTo(IDENTITY);
        assertThat(originalAdvertisement.getIdentityType()).isEqualTo(IDENTITY_TYPE);
        assertThat(originalAdvertisement.getLength()).isEqualTo(66);
        assertThat(originalAdvertisement.getVersion()).isEqualTo(
                BroadcastRequest.PRESENCE_VERSION_V1);
        assertThat(originalAdvertisement.getSalt()).isEqualTo(SALT);
        assertThat(originalAdvertisement.getDataElements())
                .containsExactly(MODE_ID_ADDRESS_ELEMENT, BLE_ADDRESS_ELEMENT);
    }

    @Test
    public void test_createFromRequest_encodeAndDecode() {
        ExtendedAdvertisement originalAdvertisement = ExtendedAdvertisement.createFromRequest(
                mBuilder.build());

        byte[] generatedBytes = originalAdvertisement.toBytes();

        ExtendedAdvertisement newAdvertisement =
                ExtendedAdvertisement.fromBytes(generatedBytes, mPublicCredential);

        assertThat(newAdvertisement.getActions())
                .containsExactly(PRESENCE_ACTION_1, PRESENCE_ACTION_2);
        assertThat(newAdvertisement.getIdentity()).isEqualTo(IDENTITY);
        assertThat(newAdvertisement.getIdentityType()).isEqualTo(IDENTITY_TYPE);
        assertThat(newAdvertisement.getLength()).isEqualTo(66);
        assertThat(newAdvertisement.getVersion()).isEqualTo(
                BroadcastRequest.PRESENCE_VERSION_V1);
        assertThat(newAdvertisement.getSalt()).isEqualTo(SALT);
        assertThat(newAdvertisement.getDataElements())
                .containsExactly(MODE_ID_ADDRESS_ELEMENT, BLE_ADDRESS_ELEMENT);
    }

    @Test
    public void test_createFromRequest_invalidParameter() {
        // invalid version
        mBuilder.setVersion(BroadcastRequest.PRESENCE_VERSION_V0);
        assertThat(ExtendedAdvertisement.createFromRequest(mBuilder.build())).isNull();

        // invalid salt
        PresenceBroadcastRequest.Builder builder =
                new PresenceBroadcastRequest.Builder(Collections.singletonList(MEDIUM_TYPE_BLE),
                        new byte[]{1, 2, 3}, mPrivateCredential)
                        .setVersion(BroadcastRequest.PRESENCE_VERSION_V1)
                        .addAction(PRESENCE_ACTION_1);
        assertThat(ExtendedAdvertisement.createFromRequest(builder.build())).isNull();

        // invalid identity
        PrivateCredential privateCredential =
                new PrivateCredential.Builder(SECRET_ID,
                        AUTHENTICITY_KEY, new byte[]{1, 2, 3, 4}, DEVICE_NAME)
                        .setIdentityType(PresenceCredential.IDENTITY_TYPE_PRIVATE)
                        .build();
        PresenceBroadcastRequest.Builder builder2 =
                new PresenceBroadcastRequest.Builder(Collections.singletonList(MEDIUM_TYPE_BLE),
                        new byte[]{1, 2, 3}, privateCredential)
                        .setVersion(BroadcastRequest.PRESENCE_VERSION_V1)
                        .addAction(PRESENCE_ACTION_1);
        assertThat(ExtendedAdvertisement.createFromRequest(builder2.build())).isNull();

        // empty action
        PresenceBroadcastRequest.Builder builder3 =
                new PresenceBroadcastRequest.Builder(Collections.singletonList(MEDIUM_TYPE_BLE),
                        SALT, mPrivateCredential)
                        .setVersion(BroadcastRequest.PRESENCE_VERSION_V1);
        assertThat(ExtendedAdvertisement.createFromRequest(builder3.build())).isNull();
    }

    @Test
    public void test_toBytes() {
        ExtendedAdvertisement adv = ExtendedAdvertisement.createFromRequest(mBuilder.build());
        assertThat(adv.toBytes()).isEqualTo(getExtendedAdvertisementByteArray());
    }

    @Test
    public void test_fromBytes() {
        byte[] originalBytes = getExtendedAdvertisementByteArray();
        ExtendedAdvertisement adv =
                ExtendedAdvertisement.fromBytes(originalBytes, mPublicCredential);

        assertThat(adv.getActions())
                .containsExactly(PRESENCE_ACTION_1, PRESENCE_ACTION_2);
        assertThat(adv.getIdentity()).isEqualTo(IDENTITY);
        assertThat(adv.getIdentityType()).isEqualTo(IDENTITY_TYPE);
        assertThat(adv.getLength()).isEqualTo(66);
        assertThat(adv.getVersion()).isEqualTo(
                BroadcastRequest.PRESENCE_VERSION_V1);
        assertThat(adv.getSalt()).isEqualTo(SALT);
        assertThat(adv.getDataElements())
                .containsExactly(MODE_ID_ADDRESS_ELEMENT, BLE_ADDRESS_ELEMENT);
    }

    @Test
    public void test_toString() {
        ExtendedAdvertisement adv = ExtendedAdvertisement.createFromRequest(mBuilder.build());
        assertThat(adv.toString()).isEqualTo("ExtendedAdvertisement:"
                + "<VERSION: 1, length: 66, dataElementCount: 2, identityType: 1, "
                + "identity: [1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4, 1, 2, 3, 4], salt: [2, 3],"
                + " actions: [1, 2]>");
    }

    @Test
    public void test_getDataElements_accordingToType() {
        ExtendedAdvertisement adv = ExtendedAdvertisement.createFromRequest(mBuilder.build());
        List<DataElement> dataElements = new ArrayList<>();

        dataElements.add(BLE_ADDRESS_ELEMENT);
        assertThat(adv.getDataElements(DATA_TYPE_BLE_ADDRESS)).isEqualTo(dataElements);
        assertThat(adv.getDataElements(DATA_TYPE_PUBLIC_IDENTITY)).isEmpty();
    }

    private static byte[] getExtendedAdvertisementByteArray() {
        ByteBuffer buffer = ByteBuffer.allocate(66);
        buffer.put((byte) 0b00100000); // Header V1
        buffer.put((byte) 0b00100000); // Salt header: length 2, type 0
        // Salt data
        buffer.put(SALT);
        // Identity header: length 16, type 1 (private identity)
        buffer.put(new byte[]{(byte) 0b10010000, (byte) 0b00000001});
        // Identity data
        buffer.put(CryptorImpIdentityV1.getInstance().encrypt(IDENTITY, SALT, AUTHENTICITY_KEY));

        ByteBuffer deBuffer = ByteBuffer.allocate(28);
        // Action1 header: length 1, type 6
        deBuffer.put(new byte[]{(byte) 0b00010110});
        // Action1 data
        deBuffer.put((byte) PRESENCE_ACTION_1);
        // Action2 header: length 1, type 6
        deBuffer.put(new byte[]{(byte) 0b00010110});
        // Action2 data
        deBuffer.put((byte) PRESENCE_ACTION_2);
        // Ble address header: length 7, type 102
        deBuffer.put(new byte[]{(byte) 0b10000111, (byte) 0b01100101});
        // Ble address data
        deBuffer.put(BLE_ADDRESS);
        // model id header: length 13, type 7
        deBuffer.put(new byte[]{(byte) 0b10001101, (byte) 0b00000111});
        // model id data
        deBuffer.put(MODE_ID_DATA);

        byte[] data = deBuffer.array();
        CryptorImpV1 cryptor = CryptorImpV1.getInstance();
        buffer.put(cryptor.encrypt(data, SALT, AUTHENTICITY_KEY));
        buffer.put(cryptor.sign(data, AUTHENTICITY_KEY));

        return buffer.array();
    }
}
