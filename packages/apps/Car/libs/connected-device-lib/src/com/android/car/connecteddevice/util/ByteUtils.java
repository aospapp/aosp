/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.connecteddevice.util;

import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.SuppressLint;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Utility classes for manipulating bytes.
 */
public final class ByteUtils {
    // https://developer.android.com/reference/java/util/UUID
    private static final int UUID_LENGTH = 16;

    private ByteUtils() {
    }

    /**
     * Returns a byte buffer corresponding to the passed long argument.
     *
     * @param primitive data to convert format.
     */
    public static byte[] longToBytes(long primitive) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.BYTES);
        buffer.putLong(primitive);
        return buffer.array();
    }

    /**
     * Returns a byte buffer corresponding to the passed long argument.
     *
     * @param array data to convert format.
     */
    public static long bytesToLong(byte[] array) {
        ByteBuffer buffer = ByteBuffer.allocate(Long.SIZE / Byte.SIZE);
        buffer.put(array);
        buffer.flip();
        long value = buffer.getLong();
        return value;
    }

    /**
     * Returns a String in Hex format that is formed from the bytes in the byte array Useful for
     * debugging
     *
     * @param array the byte array
     * @return the Hex string version of the input byte array
     */
    public static String byteArrayToHexString(byte[] array) {
        StringBuilder sb = new StringBuilder(array.length * 2);
        for (byte b : array) {
            sb.append(String.format("%02x", b));
        }
        return sb.toString();
    }

    /**
     * Convert UUID to Big Endian byte array
     *
     * @param uuid UUID to convert
     * @return the byte array representing the UUID
     */
    @NonNull
    public static byte[] uuidToBytes(@NonNull UUID uuid) {

        return ByteBuffer.allocate(UUID_LENGTH)
                .order(ByteOrder.BIG_ENDIAN)
                .putLong(uuid.getMostSignificantBits())
                .putLong(uuid.getLeastSignificantBits())
                .array();
    }

    /**
     * Convert Big Endian byte array to UUID
     *
     * @param bytes byte array to convert
     * @return the UUID representing the byte array, or null if not a valid UUID
     */
    @Nullable
    public static UUID bytesToUUID(@NonNull byte[] bytes) {
        if (bytes.length != UUID_LENGTH) {
            return null;
        }

        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        return new UUID(buffer.getLong(), buffer.getLong());
    }

    /**
     * Generate a random zero-filled string of given length
     *
     * @param length of string
     * @return generated string
     */
    @SuppressLint("DefaultLocale") // Should always have the same format regardless of locale
    public static String generateRandomNumberString(int length) {
        return String.format(
                "%0" + length + "d",
                ThreadLocalRandom.current().nextInt((int) Math.pow(10, length)));
    }

    /**
     * Generate a {@link byte[]} with random bytes.
     *
     * @param size of array to generate.
     * @return generated {@link byte[]}.
     */
    @NonNull
    public static byte[] randomBytes(int size) {
        byte[] array = new byte[size];
        ThreadLocalRandom.current().nextBytes(array);
        return array;
    }

    /**
     * Concatentate the given 2 byte arrays
     *
     * @param a input array 1
     * @param b input array 2
     * @return concatenated array of arrays 1 and 2
     */
    @Nullable
    public static byte[] concatByteArrays(@Nullable byte[] a, @Nullable byte[] b) {
        ByteArrayOutputStream outputStream = new ByteArrayOutputStream();
        try {
            if (a != null) {
                outputStream.write(a);
            }
            if (b != null) {
                outputStream.write(b);
            }
        } catch (IOException e) {
            return null;
        }
        return outputStream.toByteArray();
    }
}
