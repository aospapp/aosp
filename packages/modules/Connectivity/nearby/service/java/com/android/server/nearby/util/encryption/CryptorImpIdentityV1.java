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

package com.android.server.nearby.util.encryption;

import static com.android.server.nearby.NearbyService.TAG;

import android.security.keystore.KeyProperties;
import android.util.Log;

import androidx.annotation.Nullable;

import com.android.internal.annotations.VisibleForTesting;

import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * {@link android.nearby.BroadcastRequest#PRESENCE_VERSION_V1} for identity
 * encryption and decryption.
 */
public class CryptorImpIdentityV1 extends Cryptor {

    // 3 16 byte arrays known by both the encryptor and decryptor.
    private static final byte[] EK_IV =
            new byte[] {14, -123, -39, 42, 109, 127, 83, 27, 27, 11, 91, -38, 92, 17, -84, 66};
    private static final byte[] ESALT_IV =
            new byte[] {46, 83, -19, 10, -127, -31, -31, 12, 31, 76, 63, -9, 33, -66, 15, -10};
    private static final byte[] KTAG_IV =
            {-22, -83, -6, 67, 16, -99, -13, -9, 8, -3, -16, 37, -75, 47, 1, -56};

    /** Length of encryption key required by AES/GCM encryption. */
    private static final int ENCRYPTION_KEY_SIZE = 32;

    /** Length of salt required by AES/GCM encryption. */
    private static final int AES_CTR_IV_SIZE = 16;

    /** Length HMAC tag */
    public static final int HMAC_TAG_SIZE = 8;

    /**
     * In the form of "algorithm/mode/padding". Must be the same across broadcast and scan devices.
     */
    private static final String CIPHER_ALGORITHM = "AES/CTR/NoPadding";

    @VisibleForTesting
    static final String ENCRYPT_ALGORITHM = KeyProperties.KEY_ALGORITHM_AES;

    // Lazily instantiated when {@link #getInstance()} is called.
    @Nullable private static CryptorImpIdentityV1 sCryptor;

    /** Returns an instance of CryptorImpIdentityV1. */
    public static CryptorImpIdentityV1 getInstance() {
        if (sCryptor == null) {
            sCryptor = new CryptorImpIdentityV1();
        }
        return sCryptor;
    }

    @Nullable
    @Override
    public byte[] encrypt(byte[] data, byte[] salt, byte[] authenticityKey) {
        if (authenticityKey.length != AUTHENTICITY_KEY_BYTE_SIZE) {
            Log.w(TAG, "Illegal authenticity key size");
            return null;
        }

        // Generates a 32 bytes encryption key from authenticity_key
        byte[] encryptionKey = Cryptor.computeHkdf(authenticityKey, EK_IV, ENCRYPTION_KEY_SIZE);
        if (encryptionKey == null) {
            Log.e(TAG, "Failed to generate encryption key.");
            return null;
        }

        // Encrypts the data using the encryption key
        SecretKey secretKey = new SecretKeySpec(encryptionKey, ENCRYPT_ALGORITHM);
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            Log.e(TAG, "Failed to encrypt with secret key.", e);
            return null;
        }
        byte[] esalt = Cryptor.computeHkdf(salt, ESALT_IV, AES_CTR_IV_SIZE);
        if (esalt == null) {
            Log.e(TAG, "Failed to generate salt.");
            return null;
        }
        try {
            cipher.init(Cipher.ENCRYPT_MODE, secretKey, new IvParameterSpec(esalt));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            Log.e(TAG, "Failed to initialize cipher.", e);
            return null;
        }
        try {
            return cipher.doFinal(data);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            Log.e(TAG, "Failed to encrypt with secret key.", e);
            return null;
        }
    }

    @Nullable
    @Override
    public byte[] decrypt(byte[] encryptedData, byte[] salt, byte[] authenticityKey) {
        if (authenticityKey.length != AUTHENTICITY_KEY_BYTE_SIZE) {
            Log.w(TAG, "Illegal authenticity key size");
            return null;
        }

        // Generates a 32 bytes encryption key from authenticity_key
        byte[] encryptionKey = Cryptor.computeHkdf(authenticityKey, EK_IV, ENCRYPTION_KEY_SIZE);
        if (encryptionKey == null) {
            Log.e(TAG, "Failed to generate encryption key.");
            return null;
        }

        // Decrypts the data using the encryption key
        SecretKey secretKey = new SecretKeySpec(encryptionKey, ENCRYPT_ALGORITHM);
        Cipher cipher;
        try {
            cipher = Cipher.getInstance(CIPHER_ALGORITHM);
        } catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
            Log.e(TAG, "Failed to get cipher instance.", e);
            return null;
        }
        byte[] esalt = Cryptor.computeHkdf(salt, ESALT_IV, AES_CTR_IV_SIZE);
        if (esalt == null) {
            return null;
        }
        try {
            cipher.init(Cipher.DECRYPT_MODE, secretKey, new IvParameterSpec(esalt));
        } catch (InvalidKeyException | InvalidAlgorithmParameterException e) {
            Log.e(TAG, "Failed to initialize cipher.", e);
            return null;
        }

        try {
            return cipher.doFinal(encryptedData);
        } catch (IllegalBlockSizeException | BadPaddingException e) {
            Log.e(TAG, "Failed to decrypt bytes with secret key.", e);
            return null;
        }
    }

    /**
     * Generates a digital signature for the data.
     *
     * @return signature {@code null} if failed to sign
     */
    @Nullable
    @Override
    public byte[] sign(byte[] data, byte[] salt) {
        if (data == null) {
            Log.e(TAG, "Not generate HMAC tag because of invalid data input.");
            return null;
        }

        // Generates a 8 bytes HMAC tag
        return Cryptor.computeHkdf(data, salt, HMAC_TAG_SIZE);
    }

    /**
     * Generates a digital signature for the data.
     * Uses KTAG_IV as salt value.
     */
    @Nullable
    public byte[] sign(byte[] data) {
        // Generates a 8 bytes HMAC tag
        return sign(data, KTAG_IV);
    }

    @Override
    public boolean verify(byte[] data, byte[] key, byte[] signature) {
        return Arrays.equals(sign(data, key), signature);
    }

    /**
     * Verifies the signature generated by data and key, with the original signed data. Uses
     * KTAG_IV as salt value.
     */
    public boolean verify(byte[] data, byte[] signature) {
        return verify(data, KTAG_IV, signature);
    }
}
