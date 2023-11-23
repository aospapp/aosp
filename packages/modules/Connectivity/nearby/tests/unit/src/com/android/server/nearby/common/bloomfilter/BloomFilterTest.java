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

package com.android.server.nearby.common.bloomfilter;

import static com.google.common.io.BaseEncoding.base16;
import static com.google.common.primitives.Bytes.concat;
import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import org.junit.Test;

import java.util.Arrays;

/**
 * Unit-tests for the {@link BloomFilter} class.
 */
public class BloomFilterTest {
    private static final int BYTE_ARRAY_LENGTH = 100;

    private final BloomFilter mBloomFilter =
            new BloomFilter(new byte[BYTE_ARRAY_LENGTH], newHasher());

    public BloomFilter.Hasher newHasher() {
        return new FastPairBloomFilterHasher();
    }

    @Test
    public void emptyFilter_returnsEmptyArray() throws Exception {
        assertThat(mBloomFilter.asBytes()).isEqualTo(new byte[BYTE_ARRAY_LENGTH]);
    }

    @Test
    public void emptyFilter_neverContains() throws Exception {
        assertThat(mBloomFilter.possiblyContains(element(1))).isFalse();
        assertThat(mBloomFilter.possiblyContains(element(2))).isFalse();
        assertThat(mBloomFilter.possiblyContains(element(3))).isFalse();
    }

    @Test
    public void add() throws Exception {
        assertThat(mBloomFilter.possiblyContains(element(1))).isFalse();
        mBloomFilter.add(element(1));
        assertThat(mBloomFilter.possiblyContains(element(1))).isTrue();
    }

    @Test
    public void add_onlyGivenArgAdded() throws Exception {
        mBloomFilter.add(element(1));
        assertThat(mBloomFilter.possiblyContains(element(1))).isTrue();
        assertThat(mBloomFilter.possiblyContains(element(2))).isFalse();
        assertThat(mBloomFilter.possiblyContains(element(3))).isFalse();
    }

    @Test
    public void add_multipleArgs() throws Exception {
        mBloomFilter.add(element(1));
        mBloomFilter.add(element(2));
        assertThat(mBloomFilter.possiblyContains(element(1))).isTrue();
        assertThat(mBloomFilter.possiblyContains(element(2))).isTrue();
        assertThat(mBloomFilter.possiblyContains(element(3))).isFalse();
    }

    /**
     * This test was added because of a bug where the BloomFilter doesn't utilize all bits given.
     * Functionally, the filter still works, but we just have a much higher false positive rate. The
     * bug was caused by confusing bit length and byte length, which made our BloomFilter only set
     * bits on the first byteLength (bitLength / 8) bits rather than the whole bitLength bits.
     *
     * <p>Here, we're verifying that the bits set are somewhat scattered. So instead of something
     * like [ 0, 1, 1, 0, 0, 0, 0, ..., 0 ], we should be getting something like
     * [ 0, 1, 0, 0, 1, 1, 0, 0,0, 1, ..., 1, 0].
     */
    @Test
    public void randomness_noEndBias() throws Exception {
        // Add one element to our BloomFilter.
        mBloomFilter.add(element(1));

        // Record the amount of non-zero bytes and the longest streak of zero bytes in the resulting
        // BloomFilter. This is an approximation of reasonable distribution since we're recording by
        // bytes instead of bits.
        int nonZeroCount = 0;
        int longestZeroStreak = 0;
        int currentZeroStreak = 0;
        for (byte b : mBloomFilter.asBytes()) {
            if (b == 0) {
                currentZeroStreak++;
            } else {
                // Increment the number of non-zero bytes we've seen, update the longest zero
                // streak, and then reset the current zero streak.
                nonZeroCount++;
                longestZeroStreak = Math.max(longestZeroStreak, currentZeroStreak);
                currentZeroStreak = 0;
            }
        }
        // Update the longest zero streak again for the tail case.
        longestZeroStreak = Math.max(longestZeroStreak, currentZeroStreak);

        // Since randomness is hard to measure within one unit test, we instead do a valid check.
        // All non-zero bytes should not be packed into one end of the array.
        //
        // In this case, the size of one end is approximated to be:
        //   BYTE_ARRAY_LENGTH / nonZeroCount.
        // Therefore, the longest zero streak should be less than:
        //   BYTE_ARRAY_LENGTH - one end of the array.
        int longestAcceptableZeroStreak = BYTE_ARRAY_LENGTH - (BYTE_ARRAY_LENGTH / nonZeroCount);
        assertThat(longestZeroStreak).isAtMost(longestAcceptableZeroStreak);
    }

    @Test
    public void randomness_falsePositiveRate() throws Exception {
        // Create a new BloomFilter with a length of only 10 bytes.
        BloomFilter bloomFilter = new BloomFilter(new byte[10], newHasher());

        // Add 5 distinct elements to the BloomFilter.
        for (int i = 0; i < 5; i++) {
            bloomFilter.add(element(i));
        }

        // Now test 100 other elements and record the number of false positives.
        int falsePositives = 0;
        for (int i = 5; i < 105; i++) {
            falsePositives += bloomFilter.possiblyContains(element(i)) ? 1 : 0;
        }

        // We expect the false positive rate to be 3% with 5 elements in a 10 byte filter. Thus,
        // we give a little leeway and verify that the false positive rate is no more than 5%.
        assertWithMessage(
                String.format(
                        "False positive rate too large. Expected <= 5%%, but got %d%%.",
                        falsePositives))
                .that(falsePositives <= 5)
                .isTrue();
        System.out.printf("False positive rate: %d%%%n", falsePositives);
    }


    private String element(int index) {
        return "ELEMENT_" + index;
    }

    @Test
    public void specificBitPattern() throws Exception {
        // Create a new BloomFilter along with a fixed set of elements
        // and bit patterns to verify with.
        BloomFilter bloomFilter = new BloomFilter(new byte[6], newHasher());
        // Combine an account key and mac address.
        byte[] element =
                concat(
                        base16().decode("11223344556677889900AABBCCDDEEFF"),
                        base16().withSeparator(":", 2).decode("84:68:3E:00:02:11"));
        byte[] expectedBitPattern = new byte[] {0x50, 0x00, 0x04, 0x15, 0x08, 0x01};

        // Add the fixed elements to the filter.
        bloomFilter.add(element);

        // Verify that the resulting bytes match the expected one.
        byte[] bloomFilterBytes = bloomFilter.asBytes();
        assertWithMessage(
                "Unexpected bit pattern. Expected %s, but got %s.",
                base16().encode(expectedBitPattern), base16().encode(bloomFilterBytes))
                .that(Arrays.equals(expectedBitPattern, bloomFilterBytes))
                .isTrue();

        // Verify that the expected bit pattern creates a BloomFilter containing all fixed elements.
        bloomFilter = new BloomFilter(expectedBitPattern, newHasher());
        assertThat(bloomFilter.possiblyContains(element)).isTrue();
    }

    // This test case has been on the spec,
    // https://devsite.googleplex.com/nearby/fast-pair/spec#test_cases.
    // Explicitly adds it here, and we can easily change the parameters (e.g. account key, ble
    // address) to clarify test results with partners.
    @Test
    public void specificBitPattern_hasOneAccountKey() {
        BloomFilter bloomFilter1 = new BloomFilter(new byte[4], newHasher());
        BloomFilter bloomFilter2 = new BloomFilter(new byte[4], newHasher());
        byte[] accountKey = base16().decode("11223344556677889900AABBCCDDEEFF");
        byte[] salt1 = base16().decode("C7");
        byte[] salt2 = base16().decode("C7C8");

        // Add the fixed elements to the filter.
        bloomFilter1.add(concat(accountKey, salt1));
        bloomFilter2.add(concat(accountKey, salt2));

        assertThat(bloomFilter1.asBytes()).isEqualTo(base16().decode("0A428810"));
        assertThat(bloomFilter2.asBytes()).isEqualTo(base16().decode("020C802A"));
    }

    // Adds this test case to spec. We can easily change the parameters (e.g. account key, ble
    // address, battery data) to clarify test results with partners.
    @Test
    public void specificBitPattern_hasOneAccountKey_withBatteryData() {
        BloomFilter bloomFilter1 = new BloomFilter(new byte[4], newHasher());
        BloomFilter bloomFilter2 = new BloomFilter(new byte[4], newHasher());
        byte[] accountKey = base16().decode("11223344556677889900AABBCCDDEEFF");
        byte[] salt1 = base16().decode("C7");
        byte[] salt2 = base16().decode("C7C8");
        byte[] batteryData = {
                0b00110011, // length = 3, show UI indication.
                0b01000000, // Left bud: not charging, battery level = 64.
                0b01000000, // Right bud: not charging, battery level = 64.
                0b01000000 // Case: not charging, battery level = 64.
        };

        // Adds battery data to build bloom filter.
        bloomFilter1.add(concat(accountKey, salt1, batteryData));
        bloomFilter2.add(concat(accountKey, salt2, batteryData));

        assertThat(bloomFilter1.asBytes()).isEqualTo(base16().decode("4A00F000"));
        assertThat(bloomFilter2.asBytes()).isEqualTo(base16().decode("0101460A"));
    }

    // This test case has been on the spec,
    // https://devsite.googleplex.com/nearby/fast-pair/spec#test_cases.
    // Explicitly adds it here, and we can easily change the parameters (e.g. account key, ble
    // address) to clarify test results with partners.
    @Test
    public void specificBitPattern_hasTwoAccountKeys() {
        BloomFilter bloomFilter1 = new BloomFilter(new byte[5], newHasher());
        BloomFilter bloomFilter2 = new BloomFilter(new byte[5], newHasher());
        byte[] accountKey1 = base16().decode("11223344556677889900AABBCCDDEEFF");
        byte[] accountKey2 = base16().decode("11112222333344445555666677778888");
        byte[] salt1 = base16().decode("C7");
        byte[] salt2 = base16().decode("C7C8");

        // Adds the fixed elements to the filter.
        bloomFilter1.add(concat(accountKey1, salt1));
        bloomFilter1.add(concat(accountKey2, salt1));
        bloomFilter2.add(concat(accountKey1, salt2));
        bloomFilter2.add(concat(accountKey2, salt2));

        assertThat(bloomFilter1.asBytes()).isEqualTo(base16().decode("2FBA064200"));
        assertThat(bloomFilter2.asBytes()).isEqualTo(base16().decode("844A62208B"));
    }

    // Adds this test case to spec. We can easily change the parameters (e.g. account keys, ble
    // address, battery data) to clarify test results with partners.
    @Test
    public void specificBitPattern_hasTwoAccountKeys_withBatteryData() {
        BloomFilter bloomFilter1 = new BloomFilter(new byte[5], newHasher());
        BloomFilter bloomFilter2 = new BloomFilter(new byte[5], newHasher());
        byte[] accountKey1 = base16().decode("11223344556677889900AABBCCDDEEFF");
        byte[] accountKey2 = base16().decode("11112222333344445555666677778888");
        byte[] salt1 = base16().decode("C7");
        byte[] salt2 = base16().decode("C7C8");
        byte[] batteryData = {
                0b00110011, // length = 3, show UI indication.
                0b01000000, // Left bud: not charging, battery level = 64.
                0b01000000, // Right bud: not charging, battery level = 64.
                0b01000000 // Case: not charging, battery level = 64.
        };

        // Adds battery data to build bloom filter.
        bloomFilter1.add(concat(accountKey1, salt1, batteryData));
        bloomFilter1.add(concat(accountKey2, salt1, batteryData));
        bloomFilter2.add(concat(accountKey1, salt2, batteryData));
        bloomFilter2.add(concat(accountKey2, salt2, batteryData));

        assertThat(bloomFilter1.asBytes()).isEqualTo(base16().decode("102256C04D"));
        assertThat(bloomFilter2.asBytes()).isEqualTo(base16().decode("461524D008"));
    }

    // Adds this test case to spec. We can easily change the parameters (e.g. account keys, ble
    // address, battery data and battery remaining time) to clarify test results with partners.
    @Test
    public void specificBitPattern_hasTwoAccountKeys_withBatteryLevelAndRemainingTime() {
        BloomFilter bloomFilter1 = new BloomFilter(new byte[5], newHasher());
        BloomFilter bloomFilter2 = new BloomFilter(new byte[5], newHasher());
        byte[] accountKey1 = base16().decode("11223344556677889900AABBCCDDEEFF");
        byte[] accountKey2 = base16().decode("11112222333344445555666677778888");
        byte[] salt1 = base16().decode("C7");
        byte[] salt2 = base16().decode("C7C8");
        byte[] batteryData = {
                0b00110011, // length = 3, show UI indication.
                0b01000000, // Left bud: not charging, battery level = 64.
                0b01000000, // Right bud: not charging, battery level = 64.
                0b01000000 // Case: not charging, battery level = 64.
        };
        byte[] batteryRemainingTime = {
                0b00010101, // length = 1, type = 0b0101 (remaining battery time).
                0x1E, // remaining battery time (in minutes) =  30 minutes.
        };

        // Adds battery data to build bloom filter.
        bloomFilter1.add(concat(accountKey1, salt1, batteryData, batteryRemainingTime));
        bloomFilter1.add(concat(accountKey2, salt1, batteryData, batteryRemainingTime));
        bloomFilter2.add(concat(accountKey1, salt2, batteryData, batteryRemainingTime));
        bloomFilter2.add(concat(accountKey2, salt2, batteryData, batteryRemainingTime));

        assertThat(bloomFilter1.asBytes()).isEqualTo(base16().decode("32A086B41A"));
        assertThat(bloomFilter2.asBytes()).isEqualTo(base16().decode("C2A042043E"));
    }
}
