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

import static com.google.common.truth.Truth.assertThat;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import org.junit.Test;
import org.junit.runner.RunWith;

import java.math.BigInteger;

@RunWith(AndroidJUnit4.class)
public class ScanDataAnalyzerTest {
    private static final BigInteger CORRECT_DATA =
            new BigInteger(
                    "02011A14FF4C000100000000000000000000000000200000000000000000000000000000"
                            + "0000000000000000000000000000000000000000000000000000",
                    16);

    private static final BigInteger CORRECT_MASK =
            new BigInteger("00000000000000000000000000200000", 16);

    private static final BigInteger MULTIPLE_BIT_MASK =
            new BigInteger("00000000000000000100000000200000", 16);

    @Test
    public void containsUuidsInOverflow_correctBitFlipped_shouldReturnTrue() {
        assertThat(
                ScanDataAnalyzer.containsUuidsInOverflow(CORRECT_DATA.toByteArray(), CORRECT_MASK))
                .isTrue();
    }

    @Test
    public void containsUuidsInOverflow_bitNotFlipped_shouldReturnFalse() {
        assertThat(
                ScanDataAnalyzer.containsUuidsInOverflow(
                        CORRECT_DATA.negate().toByteArray(), CORRECT_MASK))
                .isFalse();
    }

    @Test
    public void containsUuidsInOverflow_maskWithMultipleBitsIncompleteMatch_shouldReturnTrue() {
        assertThat(
                ScanDataAnalyzer.containsUuidsInOverflow(CORRECT_DATA.toByteArray(),
                        MULTIPLE_BIT_MASK))
                .isTrue();
    }

    @Test
    public void containsUuidsInOverflow_incorrectLengthByte_shouldReturnFalse() {
        // Incorrect length of 0x20
        byte[] data =
                new BigInteger(
                        "02011A20FF4C00010000000000000000000000000020000000000000000000000000000000"
                                + "00000000000000000000000000000000000000000000000000",
                        16)
                        .toByteArray();
        BigInteger mask = new BigInteger("00000000000000000000000000200000", 16);
        assertThat(ScanDataAnalyzer.containsUuidsInOverflow(data, mask)).isFalse();
    }

    @Test
    public void containsUuidsInOverflow_incorrectAdTypeByte_shouldReturnFalse() {
        // Incorrect advertising type of 0xEF
        byte[] data =
                new BigInteger(
                        "02011A14EF4C00010000000000000000000000000020000000000000000000000000000000"
                                + "00000000000000000000000000000000000000000000000000",
                        16)
                        .toByteArray();
        assertThat(ScanDataAnalyzer.containsUuidsInOverflow(data, CORRECT_MASK)).isFalse();
    }

    @Test
    public void containsUuidsInOverflow_incorrectCustomId_shouldReturnFalse() {
        // Incorrect custom id of 0x4C1001
        byte[] data =
                new BigInteger(
                        "02011A14FF4C10010000000000000000000000000020000000000000000000000000000000"
                                + "00000000000000000000000000000000000000000000000000",
                        16)
                        .toByteArray();
        assertThat(ScanDataAnalyzer.containsUuidsInOverflow(data, CORRECT_MASK)).isFalse();
    }

    @Test
    public void containsUuidsInOverflow_incorrectContentLength_shouldReturnFalse() {
        byte[] data = new BigInteger("02011A14FF4C1001000000000000000000000000002", 16)
                .toByteArray();
        assertThat(ScanDataAnalyzer.containsUuidsInOverflow(data, CORRECT_MASK)).isFalse();
    }
}
