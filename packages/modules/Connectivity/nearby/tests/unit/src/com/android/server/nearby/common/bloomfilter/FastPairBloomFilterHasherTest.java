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

import static com.google.common.truth.Truth.assertThat;

import static java.nio.charset.StandardCharsets.UTF_8;

import org.junit.Test;

import java.nio.charset.Charset;

public class FastPairBloomFilterHasherTest {
    private static final Charset CHARSET = UTF_8;
    private static FastPairBloomFilterHasher sFastPairBloomFilterHasher =
            new FastPairBloomFilterHasher();
    @Test
    public void getHashes() {
        int[] hashe1 = sFastPairBloomFilterHasher.getHashes(element(1).getBytes(CHARSET));
        int[] hashe2 = sFastPairBloomFilterHasher.getHashes(element(1).getBytes(CHARSET));
        int[] hashe3 = sFastPairBloomFilterHasher.getHashes(element(2).getBytes(CHARSET));
        assertThat(hashe1).isEqualTo(hashe2);
        assertThat(hashe1).isNotEqualTo(hashe3);
    }

    private String element(int index) {
        return "ELEMENT_" + index;
    }
}
