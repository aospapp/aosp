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

package com.android.server.nearby.common.bluetooth.fastpair;

import static com.google.common.truth.Truth.assertThat;

import androidx.test.filters.SdkSuppress;

import com.android.server.nearby.common.bluetooth.BluetoothException;
import com.android.server.nearby.intdefs.FastPairEventIntDefs;

import junit.framework.TestCase;

/**
 * Unit tests for {@link CreateBondException}.
 */
public class CreateBondExceptionTest extends TestCase {

    private static final String FORMAT = "FORMAT";
    private static final int REASON = 0;
    private static final CreateBondException EXCEPTION = new CreateBondException(
            FastPairEventIntDefs.CreateBondErrorCode.INCORRECT_VARIANT, REASON, FORMAT);

    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void test_getter_asExpected() throws BluetoothException {
        assertThat(EXCEPTION.getErrorCode()).isEqualTo(
                FastPairEventIntDefs.CreateBondErrorCode.INCORRECT_VARIANT);
        assertThat(EXCEPTION.getReason()).isSameInstanceAs(REASON);
    }
}
