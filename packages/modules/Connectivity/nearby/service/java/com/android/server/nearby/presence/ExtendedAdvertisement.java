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

import static com.android.server.nearby.NearbyService.TAG;

import android.annotation.Nullable;
import android.nearby.BroadcastRequest;
import android.nearby.DataElement;
import android.nearby.PresenceBroadcastRequest;
import android.nearby.PresenceCredential;
import android.nearby.PublicCredential;
import android.util.Log;

import com.android.server.nearby.util.encryption.Cryptor;
import com.android.server.nearby.util.encryption.CryptorImpFake;
import com.android.server.nearby.util.encryption.CryptorImpIdentityV1;
import com.android.server.nearby.util.encryption.CryptorImpV1;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;

/**
 * A Nearby Presence advertisement to be advertised on BT5.0 devices.
 *
 * <p>Serializable between Java object and bytes formats. Java object is used at the upper scanning
 * and advertising interface as an abstraction of the actual bytes. Bytes format is used at the
 * underlying BLE and mDNS stacks, which do necessary slicing and merging based on advertising
 * capacities.
 *
 * The extended advertisement is defined in the format below:
 * Header (1 byte) | salt (1+2 bytes) | Identity + filter (2+16 bytes)
 * | repeated DE fields (various bytes)
 * The header contains:
 * version (3 bits) | 5 bit reserved for future use (RFU)
 */
public class ExtendedAdvertisement extends Advertisement{

    public static final int SALT_DATA_LENGTH = 2;

    static final int HEADER_LENGTH = 1;

    static final int IDENTITY_DATA_LENGTH = 16;

    private final List<DataElement> mDataElements;

    private final byte[] mAuthenticityKey;

    // All Data Elements including salt and identity.
    // Each list item (byte array) is a Data Element (with its header).
    private final List<byte[]> mCompleteDataElementsBytes;
    // Signature generated from data elements.
    private final byte[] mHmacTag;

    /**
     * Creates an {@link ExtendedAdvertisement} from a Presence Broadcast Request.
     * @return {@link ExtendedAdvertisement} object. {@code null} when the request is illegal.
     */
    @Nullable
    public static ExtendedAdvertisement createFromRequest(PresenceBroadcastRequest request) {
        if (request.getVersion() != BroadcastRequest.PRESENCE_VERSION_V1) {
            Log.v(TAG, "ExtendedAdvertisement only supports V1 now.");
            return null;
        }

        byte[] salt = request.getSalt();
        if (salt.length != SALT_DATA_LENGTH) {
            Log.v(TAG, "Salt does not match correct length");
            return null;
        }

        byte[] identity = request.getCredential().getMetadataEncryptionKey();
        byte[] authenticityKey = request.getCredential().getAuthenticityKey();
        if (identity.length != IDENTITY_DATA_LENGTH) {
            Log.v(TAG, "Identity does not match correct length");
            return null;
        }

        List<Integer> actions = request.getActions();
        if (actions.isEmpty()) {
            Log.v(TAG, "ExtendedAdvertisement must contain at least one action");
            return null;
        }

        List<DataElement> dataElements = request.getExtendedProperties();
        return new ExtendedAdvertisement(
                request.getCredential().getIdentityType(),
                identity,
                salt,
                authenticityKey,
                actions,
                dataElements);
    }

    /** Serialize an {@link ExtendedAdvertisement} object into bytes with {@link DataElement}s */
    @Nullable
    public byte[] toBytes() {
        ByteBuffer buffer = ByteBuffer.allocate(getLength());

        // Header
        buffer.put(ExtendedAdvertisementUtils.constructHeader(getVersion()));

        // Salt
        buffer.put(mCompleteDataElementsBytes.get(0));

        // Identity
        buffer.put(mCompleteDataElementsBytes.get(1));

        List<Byte> rawDataBytes = new ArrayList<>();
        // Data Elements (Already includes salt and identity)
        for (int i = 2; i < mCompleteDataElementsBytes.size(); i++) {
            byte[] dataElementBytes = mCompleteDataElementsBytes.get(i);
            for (Byte b : dataElementBytes) {
                rawDataBytes.add(b);
            }
        }

        byte[] dataElements = new byte[rawDataBytes.size()];
        for (int i = 0; i < rawDataBytes.size(); i++) {
            dataElements[i] = rawDataBytes.get(i);
        }

        buffer.put(
                getCryptor(/* encrypt= */ true).encrypt(dataElements, getSalt(), mAuthenticityKey));

        buffer.put(mHmacTag);

        return buffer.array();
    }

    /** Deserialize from bytes into an {@link ExtendedAdvertisement} object.
     * {@code null} when there is something when parsing.
     */
    @Nullable
    public static ExtendedAdvertisement fromBytes(byte[] bytes, PublicCredential publicCredential) {
        @BroadcastRequest.BroadcastVersion
        int version = ExtendedAdvertisementUtils.getVersion(bytes);
        if (version != PresenceBroadcastRequest.PRESENCE_VERSION_V1) {
            Log.v(TAG, "ExtendedAdvertisement is used in V1 only and version is " + version);
            return null;
        }

        byte[] authenticityKey = publicCredential.getAuthenticityKey();

        int index = HEADER_LENGTH;
        // Salt
        byte[] saltHeaderArray = ExtendedAdvertisementUtils.getDataElementHeader(bytes, index);
        DataElementHeader saltHeader = DataElementHeader.fromBytes(version, saltHeaderArray);
        if (saltHeader == null || saltHeader.getDataType() != DataElement.DataType.SALT) {
            Log.v(TAG, "First data element has to be salt.");
            return null;
        }
        index += saltHeaderArray.length;
        byte[] salt = new byte[saltHeader.getDataLength()];
        for (int i = 0; i < saltHeader.getDataLength(); i++) {
            salt[i] = bytes[index++];
        }

        // Identity
        byte[] identityHeaderArray = ExtendedAdvertisementUtils.getDataElementHeader(bytes, index);
        DataElementHeader identityHeader =
                DataElementHeader.fromBytes(version, identityHeaderArray);
        if (identityHeader == null) {
            Log.v(TAG, "The second element has to be identity.");
            return null;
        }
        index += identityHeaderArray.length;
        @PresenceCredential.IdentityType int identityType =
                toPresenceCredentialIdentityType(identityHeader.getDataType());
        if (identityType == PresenceCredential.IDENTITY_TYPE_UNKNOWN) {
            Log.v(TAG, "The identity type is unknown.");
            return null;
        }
        byte[] encryptedIdentity = new byte[identityHeader.getDataLength()];
        for (int i = 0; i < identityHeader.getDataLength(); i++) {
            encryptedIdentity[i] = bytes[index++];
        }
        byte[] identity =
                CryptorImpIdentityV1
                        .getInstance().decrypt(encryptedIdentity, salt, authenticityKey);

        Cryptor cryptor = getCryptor(/* encrypt= */ true);
        byte[] encryptedDataElements =
                new byte[bytes.length - index - cryptor.getSignatureLength()];
        // Decrypt other data elements
        System.arraycopy(bytes, index, encryptedDataElements, 0, encryptedDataElements.length);
        byte[] decryptedDataElements =
                cryptor.decrypt(encryptedDataElements, salt, authenticityKey);
        if (decryptedDataElements == null) {
            return null;
        }

        // Verify the computed HMAC tag is equal to HMAC tag in advertisement
        if (cryptor.getSignatureLength() > 0) {
            byte[] expectedHmacTag = new byte[cryptor.getSignatureLength()];
            System.arraycopy(
                    bytes, bytes.length - cryptor.getSignatureLength(),
                    expectedHmacTag, 0, cryptor.getSignatureLength());
            if (!cryptor.verify(decryptedDataElements, authenticityKey, expectedHmacTag)) {
                Log.e(TAG, "HMAC tags not match.");
                return null;
            }
        }

        int dataElementArrayIndex = 0;
        // Other Data Elements
        List<Integer> actions = new ArrayList<>();
        List<DataElement> dataElements = new ArrayList<>();
        while (dataElementArrayIndex < decryptedDataElements.length) {
            byte[] deHeaderArray = ExtendedAdvertisementUtils
                    .getDataElementHeader(decryptedDataElements, dataElementArrayIndex);
            DataElementHeader deHeader = DataElementHeader.fromBytes(version, deHeaderArray);
            dataElementArrayIndex += deHeaderArray.length;

            @DataElement.DataType int type = Objects.requireNonNull(deHeader).getDataType();
            if (type == DataElement.DataType.ACTION) {
                if (deHeader.getDataLength() != 1) {
                    Log.v(TAG, "Action id should only 1 byte.");
                    return null;
                }
                actions.add((int) decryptedDataElements[dataElementArrayIndex++]);
            } else {
                if (isSaltOrIdentity(type)) {
                    Log.v(TAG, "Type " + type + " is duplicated. There should be only one salt"
                            + " and one identity in the advertisement.");
                    return null;
                }
                byte[] deData = new byte[deHeader.getDataLength()];
                for (int i = 0; i < deHeader.getDataLength(); i++) {
                    deData[i] = decryptedDataElements[dataElementArrayIndex++];
                }
                dataElements.add(new DataElement(type, deData));
            }
        }

        return new ExtendedAdvertisement(identityType, identity, salt, authenticityKey, actions,
                dataElements);
    }

    /** Returns the {@link DataElement}s in the advertisement. */
    public List<DataElement> getDataElements() {
        return new ArrayList<>(mDataElements);
    }

    /** Returns the {@link DataElement}s in the advertisement according to the key. */
    public List<DataElement> getDataElements(@DataElement.DataType int key) {
        List<DataElement> res = new ArrayList<>();
        for (DataElement dataElement : mDataElements) {
            if (key == dataElement.getKey()) {
                res.add(dataElement);
            }
        }
        return res;
    }

    @Override
    public String toString() {
        return String.format(
                "ExtendedAdvertisement:"
                        + "<VERSION: %s, length: %s, dataElementCount: %s, identityType: %s,"
                        + " identity: %s, salt: %s, actions: %s>",
                getVersion(),
                getLength(),
                getDataElements().size(),
                getIdentityType(),
                Arrays.toString(getIdentity()),
                Arrays.toString(getSalt()),
                getActions());
    }

    ExtendedAdvertisement(
            @PresenceCredential.IdentityType int identityType,
            byte[] identity,
            byte[] salt,
            byte[] authenticityKey,
            List<Integer> actions,
            List<DataElement> dataElements) {
        this.mVersion = BroadcastRequest.PRESENCE_VERSION_V1;
        this.mIdentityType = identityType;
        this.mIdentity = identity;
        this.mSalt = salt;
        this.mAuthenticityKey = authenticityKey;
        this.mActions = actions;
        this.mDataElements = dataElements;
        this.mCompleteDataElementsBytes = new ArrayList<>();

        int length = HEADER_LENGTH; // header

        // Salt
        DataElement saltElement = new DataElement(DataElement.DataType.SALT, salt);
        byte[] saltByteArray = ExtendedAdvertisementUtils.convertDataElementToBytes(saltElement);
        mCompleteDataElementsBytes.add(saltByteArray);
        length += saltByteArray.length;

        // Identity
        byte[] encryptedIdentity =
                CryptorImpIdentityV1.getInstance().encrypt(identity, salt, authenticityKey);
        DataElement identityElement = new DataElement(toDataType(identityType), encryptedIdentity);
        byte[] identityByteArray =
                ExtendedAdvertisementUtils.convertDataElementToBytes(identityElement);
        mCompleteDataElementsBytes.add(identityByteArray);
        length += identityByteArray.length;

        List<Byte> dataElementBytes = new ArrayList<>();
        // Intents
        for (int action : mActions) {
            DataElement actionElement = new DataElement(DataElement.DataType.ACTION,
                    new byte[] {(byte) action});
            byte[] intentByteArray =
                    ExtendedAdvertisementUtils.convertDataElementToBytes(actionElement);
            mCompleteDataElementsBytes.add(intentByteArray);
            for (Byte b : intentByteArray) {
                dataElementBytes.add(b);
            }
        }

        // Data Elements (Extended properties)
        for (DataElement dataElement : mDataElements) {
            byte[] deByteArray = ExtendedAdvertisementUtils.convertDataElementToBytes(dataElement);
            mCompleteDataElementsBytes.add(deByteArray);
            for (Byte b : deByteArray) {
                dataElementBytes.add(b);
            }
        }

        byte[] data = new byte[dataElementBytes.size()];
        for (int i = 0; i < dataElementBytes.size(); i++) {
            data[i] = dataElementBytes.get(i);
        }
        Cryptor cryptor = getCryptor(/* encrypt= */ true);
        byte[] encryptedDeBytes = cryptor.encrypt(data, salt, authenticityKey);

        length += encryptedDeBytes.length;

        // Signature
        byte[] hmacTag = Objects.requireNonNull(cryptor.sign(data, authenticityKey));
        mHmacTag = hmacTag;
        length += hmacTag.length;

        this.mLength = length;
    }

    @PresenceCredential.IdentityType
    private static int toPresenceCredentialIdentityType(@DataElement.DataType int type) {
        switch (type) {
            case DataElement.DataType.PRIVATE_IDENTITY:
                return PresenceCredential.IDENTITY_TYPE_PRIVATE;
            case DataElement.DataType.PROVISIONED_IDENTITY:
                return PresenceCredential.IDENTITY_TYPE_PROVISIONED;
            case DataElement.DataType.TRUSTED_IDENTITY:
                return PresenceCredential.IDENTITY_TYPE_TRUSTED;
            case DataElement.DataType.PUBLIC_IDENTITY:
            default:
                return PresenceCredential.IDENTITY_TYPE_UNKNOWN;
        }
    }

    @DataElement.DataType
    private static int toDataType(@PresenceCredential.IdentityType int identityType) {
        switch (identityType) {
            case PresenceCredential.IDENTITY_TYPE_PRIVATE:
                return DataElement.DataType.PRIVATE_IDENTITY;
            case PresenceCredential.IDENTITY_TYPE_PROVISIONED:
                return DataElement.DataType.PROVISIONED_IDENTITY;
            case PresenceCredential.IDENTITY_TYPE_TRUSTED:
                return DataElement.DataType.TRUSTED_IDENTITY;
            case PresenceCredential.IDENTITY_TYPE_UNKNOWN:
            default:
                return DataElement.DataType.PUBLIC_IDENTITY;
        }
    }

    /**
     * Returns {@code true} if the given {@link DataElement.DataType} is salt, or one of the
     * identities. Identities should be able to convert to {@link PresenceCredential.IdentityType}s.
     */
    private static boolean isSaltOrIdentity(@DataElement.DataType int type) {
        return type == DataElement.DataType.SALT || type == DataElement.DataType.PRIVATE_IDENTITY
                || type == DataElement.DataType.TRUSTED_IDENTITY
                || type == DataElement.DataType.PROVISIONED_IDENTITY
                || type == DataElement.DataType.PUBLIC_IDENTITY;
    }

    private static Cryptor getCryptor(boolean encrypt) {
        if (encrypt) {
            Log.d(TAG, "get V1 Cryptor");
            return CryptorImpV1.getInstance();
        }
        Log.d(TAG, "get fake Cryptor");
        return CryptorImpFake.getInstance();
    }
}
