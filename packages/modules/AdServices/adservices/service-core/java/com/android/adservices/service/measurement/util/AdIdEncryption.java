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

package com.android.adservices.service.measurement.util;

import com.android.adservices.LogUtil;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

public final class AdIdEncryption {

    private static final String SHA256_DIGEST_ALGORITHM_NAME = "SHA-256";

    private AdIdEncryption() {}

    public static String encryptAdIdAndEnrollmentSha256(String adIdValue, String enrollmentId) {
        StringBuilder adIdSha256 = new StringBuilder();
        String original = adIdValue + enrollmentId;
        try {
            // Get the hash's bytes
            MessageDigest sha256Digest = MessageDigest.getInstance(SHA256_DIGEST_ALGORITHM_NAME);
            byte[] encodedAdId = sha256Digest.digest(original.getBytes());

            // bytes[] has bytes in decimal format;
            // Convert it to hexadecimal format
            for (byte b : encodedAdId) {
                adIdSha256.append(String.format("%02x", b));
            }
        } catch (NoSuchAlgorithmException e) {
            LogUtil.e(e, "Unable to find correct message digest algorithm for AdId encryption.");
            // When catching NoSuchAlgorithmException -> return null.
            return null;
        }
        return adIdSha256.toString();
    }
}
