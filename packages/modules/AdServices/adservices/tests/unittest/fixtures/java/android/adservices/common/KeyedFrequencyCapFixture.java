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

package android.adservices.common;

import com.google.common.collect.ImmutableSet;

import java.time.Duration;

/** Utility class for creating and testing {@link KeyedFrequencyCap} objects. */
public class KeyedFrequencyCapFixture {
    public static final String KEY1 = "key1";
    public static final String KEY2 = "key2";
    public static final String KEY3 = "key3";
    public static final String KEY4 = "key4";
    public static final int VALID_COUNT = 10;
    public static final int FILTER_COUNT = 1;
    public static final int FILTER_EXCEED_COUNT = FILTER_COUNT + 1;
    public static final Duration ONE_DAY_DURATION = Duration.ofDays(1);

    public static final ImmutableSet<KeyedFrequencyCap> VALID_KEYED_FREQUENCY_CAP_SET =
            ImmutableSet.of(
                    getValidKeyedFrequencyCapBuilderOncePerDay(KEY1).build(),
                    getValidKeyedFrequencyCapBuilderOncePerDay(KEY2).build(),
                    getValidKeyedFrequencyCapBuilderOncePerDay(KEY3).build(),
                    getValidKeyedFrequencyCapBuilderOncePerDay(KEY4).build());

    public static KeyedFrequencyCap.Builder getValidKeyedFrequencyCapBuilderOncePerDay(String key) {
        return new KeyedFrequencyCap.Builder()
                .setAdCounterKey(key)
                .setMaxCount(FILTER_COUNT)
                .setInterval(ONE_DAY_DURATION);
    }
}
