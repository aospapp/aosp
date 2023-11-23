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

import junit.framework.TestCase;

import java.nio.ByteOrder;
import java.util.Arrays;

/**
 * Unit tests for {@link Bytes}.
 */
public class BytesTest extends TestCase {

    private static final Bytes.Value VALUE1 =
            new Bytes.Value(new byte[]{1, 2}, ByteOrder.BIG_ENDIAN);
    private static final Bytes.Value VALUE2 =
            new Bytes.Value(new byte[]{1, 2}, ByteOrder.BIG_ENDIAN);
    private static final Bytes.Value VALUE3 =
            new Bytes.Value(new byte[]{1, 3}, ByteOrder.BIG_ENDIAN);

    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testEquals_asExpected()  {
        assertThat(VALUE1.equals(VALUE2)).isTrue();
    }

    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testNotEquals_asExpected()  {
        assertThat(VALUE1.equals(VALUE3)).isFalse();
    }

    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testGetBytes_asExpected()  {
        assertThat(Arrays.equals(VALUE1.getBytes(ByteOrder.BIG_ENDIAN), new byte[]{1, 2})).isTrue();
    }

    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testToString()  {
        assertThat(VALUE1.toString()).isEqualTo("0102");
    }

    @SdkSuppress(minSdkVersion = 32, codeName = "T")
    public void testReverse()  {
        assertThat(VALUE1.reverse(new byte[]{1, 2})).isEqualTo(new byte[]{2, 1});
    }
}
