/*
 * Copyright (C) 2020 The Android Open Source Project
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

import static com.android.car.connecteddevice.util.SafeLog.logw;

import android.annotation.NonNull;
import android.bluetooth.le.ScanResult;

import java.math.BigInteger;

/**
 * Analyzer of {@link ScanResult} data to identify an Apple device that is advertising from the
 * background.
 */
public class ScanDataAnalyzer {

    private static final String TAG = "ScanDataAnalyzer";

    private static final byte IOS_OVERFLOW_LENGTH = (byte) 0x14;
    private static final byte IOS_ADVERTISING_TYPE = (byte) 0xff;
    private static final int IOS_ADVERTISING_TYPE_LENGTH = 1;
    private static final long IOS_OVERFLOW_CUSTOM_ID = 0x4c0001;
    private static final int IOS_OVERFLOW_CUSTOM_ID_LENGTH = 3;
    private static final int IOS_OVERFLOW_CONTENT_LENGTH =
            IOS_OVERFLOW_LENGTH - IOS_OVERFLOW_CUSTOM_ID_LENGTH - IOS_ADVERTISING_TYPE_LENGTH;

    private ScanDataAnalyzer() { }

    /**
     * Returns {@code true} if the given bytes from a [ScanResult] contains service UUIDs once the
     * given serviceUuidMask is applied.
     *
     * When an iOS peripheral device goes into a background state, the service UUIDs and other
     * identifying information are removed from the advertising data and replaced with a hashed
     * bit in a special "overflow" area. There is no documentation on the layout of this area,
     * and the below was compiled from experimentation and examples from others who have worked
     * on reverse engineering iOS background peripherals.
     *
     * My best guess is Apple is taking the service UUID and hashing it into a bloom filter. This
     * would allow any device with the same hashing function to filter for all devices that
     * might contain the desired service. Since we do not have access to this hashing function,
     * we must first advertise our service from an iOS device and manually inspect the bit that
     * is flipped. Once known, it can be passed to serviceUuidMask and used as a filter.
     *
     * EXAMPLE
     *
     * Foreground contents:
     * 02011A1107FB349B5F8000008000100000C53A00000709546573746572000000000000000000000000000000000000000000000000000000000000000000
     *
     * Background contents:
     * 02011A14FF4C0001000000000000000000000000002000000000000000000000000000000000000000000000000000000000000000000000000000000000
     *
     * The overflow bytes are comprised of four parts:
     * Length -> 14
     * Advertising type -> FF
     * Id custom to Apple -> 4C0001
     * Contents where hashed values are stored -> 00000000000000000000000000200000
     *
     * Apple's documentation on advertising from the background:
     * https://developer.apple.com/library/archive/documentation/NetworkingInternetWeb/Conceptual/CoreBluetooth_concepts/CoreBluetoothBackgroundProcessingForIOSApps/PerformingTasksWhileYourAppIsInTheBackground.html#//apple_ref/doc/uid/TP40013257-CH7-SW9
     *
     * Other similar reverse engineering:
     * http://www.pagepinner.com/2014/04/how-to-get-ble-overflow-hash-bit-from.html
     */
    public static boolean containsUuidsInOverflow(@NonNull byte[] scanData,
            @NonNull BigInteger serviceUuidMask) {
        byte[] overflowBytes = new byte[IOS_OVERFLOW_CONTENT_LENGTH];
        int overflowPtr = 0;
        int outPtr = 0;
        try {
            while (overflowPtr < scanData.length - IOS_OVERFLOW_LENGTH) {
                byte length = scanData[overflowPtr++];
                if (length == 0) {
                    break;
                } else if (length != IOS_OVERFLOW_LENGTH) {
                    continue;
                }

                if (scanData[overflowPtr++] != IOS_ADVERTISING_TYPE) {
                    return false;
                }

                byte[] idBytes = new byte[IOS_OVERFLOW_CUSTOM_ID_LENGTH];
                for (int i = 0; i < IOS_OVERFLOW_CUSTOM_ID_LENGTH; i++) {
                    idBytes[i] = scanData[overflowPtr++];
                }

                if (!new BigInteger(idBytes).equals(BigInteger.valueOf(IOS_OVERFLOW_CUSTOM_ID))) {
                    return false;
                }

                for (outPtr = 0; outPtr < IOS_OVERFLOW_CONTENT_LENGTH; outPtr++) {
                    overflowBytes[outPtr] = scanData[overflowPtr++];
                }
                break;
            }

            if (outPtr == IOS_OVERFLOW_CONTENT_LENGTH) {
                BigInteger overflowBytesValue = new BigInteger(overflowBytes);
                return overflowBytesValue.and(serviceUuidMask).signum() == 1;
            }

        } catch (ArrayIndexOutOfBoundsException e) {
            logw(TAG, "Inspecting advertisement overflow bytes went out of bounds.");
        }

        return false;
    }
}
