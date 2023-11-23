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

public class CryptorImpIdentityV1Test {
    private static final String TAG = "CryptorImpIdentityV1Test";
    private static final byte[] SALT = new byte[] {102, 22};
    private static final byte[] DATA =
            new byte[] {107, -102, 101, 107, 20, 62, 2, 73, 113, 59, 8, -14, -58, 122};
    private static final byte[] AUTHENTICITY_KEY =
            new byte[] {-89, 88, -50, -42, -99, 57, 84, -24, 121, 1, -104, -8, -26, -73, -36, 100};

    @Test
    public void test_encrypt_decrypt() {
        Cryptor identityCryptor = CryptorImpIdentityV1.getInstance();
        byte[] encryptedData = identityCryptor.encrypt(DATA, SALT, AUTHENTICITY_KEY);

        assertThat(identityCryptor.decrypt(encryptedData, SALT, AUTHENTICITY_KEY)).isEqualTo(DATA);
    }

    @Test
    public void test_encryption() {
        Cryptor identityCryptor = CryptorImpIdentityV1.getInstance();
        byte[] encryptedData = identityCryptor.encrypt(DATA, SALT, AUTHENTICITY_KEY);

        // for debugging
        Log.d(TAG, "encrypted data is: " + Arrays.toString(encryptedData));

        assertThat(encryptedData).isEqualTo(getEncryptedData());
    }

    @Test
    public void test_decryption() {
        Cryptor identityCryptor = CryptorImpIdentityV1.getInstance();
        byte[] decryptedData =
                identityCryptor.decrypt(getEncryptedData(), SALT, AUTHENTICITY_KEY);
        // for debugging
        Log.d(TAG, "decrypted data is: " + Arrays.toString(decryptedData));

        assertThat(decryptedData).isEqualTo(DATA);
    }

    @Test
    public void generateHmacTag() {
        CryptorImpIdentityV1 identityCryptor = CryptorImpIdentityV1.getInstance();
        byte[] generatedTag = identityCryptor.sign(DATA);
        byte[] expectedTag = new byte[]{50, 116, 95, -87, 63, 123, -79, -43};
        assertThat(generatedTag).isEqualTo(expectedTag);
    }

    private static byte[] getEncryptedData() {
        return new byte[]{6, -31, -32, -123, 43, -92, -47, -110, -65, 126, -15, -51, -19, -43};
    }
}
