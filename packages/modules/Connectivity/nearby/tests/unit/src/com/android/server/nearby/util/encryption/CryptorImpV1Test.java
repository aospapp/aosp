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

import static com.google.common.truth.Truth.assertThat;

import android.util.Log;

import org.junit.Test;

import java.util.Arrays;

/**
 * Unit test for {@link CryptorImpV1}
 */
public final class CryptorImpV1Test {
    private static final String TAG = "CryptorImpV1Test";
    private static final byte[] SALT = new byte[] {102, 22};
    private static final byte[] DATA =
            new byte[] {107, -102, 101, 107, 20, 62, 2, 73, 113, 59, 8, -14, -58, 122};
    private static final byte[] AUTHENTICITY_KEY =
            new byte[] {-89, 88, -50, -42, -99, 57, 84, -24, 121, 1, -104, -8, -26, -73, -36, 100};

    @Test
    public void test_encryption() {
        Cryptor v1Cryptor = CryptorImpV1.getInstance();
        byte[] encryptedData = v1Cryptor.encrypt(DATA, SALT, AUTHENTICITY_KEY);

        // for debugging
        Log.d(TAG, "encrypted data is: " + Arrays.toString(encryptedData));

        assertThat(encryptedData).isEqualTo(getEncryptedData());
    }

    @Test
    public void test_encryption_invalidInput() {
        Cryptor v1Cryptor = CryptorImpV1.getInstance();
        assertThat(v1Cryptor.encrypt(DATA, SALT, new byte[]{1, 2, 3, 4, 6})).isNull();
    }

    @Test
    public void test_decryption() {
        Cryptor v1Cryptor = CryptorImpV1.getInstance();
        byte[] decryptedData =
                v1Cryptor.decrypt(getEncryptedData(), SALT, AUTHENTICITY_KEY);
        // for debugging
        Log.d(TAG, "decrypted data is: " + Arrays.toString(decryptedData));

        assertThat(decryptedData).isEqualTo(DATA);
    }

    @Test
    public void test_decryption_invalidInput() {
        Cryptor v1Cryptor = CryptorImpV1.getInstance();
        assertThat(v1Cryptor.decrypt(getEncryptedData(), SALT, new byte[]{1, 2, 3, 4, 6})).isNull();
    }

    @Test
    public void generateSign() {
        CryptorImpV1 v1Cryptor = CryptorImpV1.getInstance();
        byte[] generatedTag = v1Cryptor.sign(DATA, AUTHENTICITY_KEY);
        byte[] expectedTag = new byte[]{
                100, 88, -104, 80, -66, 107, -38, 95, 34, 40, -56, -23, -90, 90, -87, 12};
        assertThat(generatedTag).isEqualTo(expectedTag);
    }

    @Test
    public void test_verify() {
        CryptorImpV1 v1Cryptor = CryptorImpV1.getInstance();
        byte[] expectedTag = new byte[]{
                100, 88, -104, 80, -66, 107, -38, 95, 34, 40, -56, -23, -90, 90, -87, 12};

        assertThat(v1Cryptor.verify(DATA, AUTHENTICITY_KEY, expectedTag)).isTrue();
        assertThat(v1Cryptor.verify(DATA, AUTHENTICITY_KEY, DATA)).isFalse();
    }

    @Test
    public void test_generateHmacTag_sameResult() {
        CryptorImpV1 v1Cryptor = CryptorImpV1.getInstance();
        byte[] res1 = v1Cryptor.generateHmacTag(DATA, AUTHENTICITY_KEY);
        assertThat(res1)
                .isEqualTo(v1Cryptor.generateHmacTag(DATA, AUTHENTICITY_KEY));
    }

    @Test
    public void test_generateHmacTag_nullData() {
        CryptorImpV1 v1Cryptor = CryptorImpV1.getInstance();
        assertThat(v1Cryptor.generateHmacTag(/* data= */ null, AUTHENTICITY_KEY)).isNull();
    }

    @Test
    public void test_generateHmacTag_nullKey() {
        CryptorImpV1 v1Cryptor = CryptorImpV1.getInstance();
        assertThat(v1Cryptor.generateHmacTag(DATA, /* authenticityKey= */ null)).isNull();
    }

    private static byte[] getEncryptedData() {
        return new byte[]{-92, 94, -99, -97, 81, -48, -7, 119, -64, -22, 45, -49, -50, 92};
    }
}
