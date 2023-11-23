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

/** Utility class for creating and testing {@link FrequencyCapFilters} objects. */
public class FrequencyCapFiltersFixture {
    public static final FrequencyCapFilters VALID_FREQUENCY_CAP_FILTERS =
            getValidFrequencyCapFiltersBuilder().build();

    public static final FrequencyCapFilters VALID_FREQUENCY_CAP_FILTERS_ONLY_WIN =
            new FrequencyCapFilters.Builder()
                    .setKeyedFrequencyCapsForWinEvents(
                            KeyedFrequencyCapFixture.VALID_KEYED_FREQUENCY_CAP_SET)
                    .build();

    public static final FrequencyCapFilters VALID_FREQUENCY_CAP_FILTERS_ONLY_IMPRESSION =
            new FrequencyCapFilters.Builder()
                    .setKeyedFrequencyCapsForImpressionEvents(
                            KeyedFrequencyCapFixture.VALID_KEYED_FREQUENCY_CAP_SET)
                    .build();

    public static final FrequencyCapFilters VALID_FREQUENCY_CAP_FILTERS_ONLY_VIEW =
            new FrequencyCapFilters.Builder()
                    .setKeyedFrequencyCapsForViewEvents(
                            KeyedFrequencyCapFixture.VALID_KEYED_FREQUENCY_CAP_SET)
                    .build();

    public static final FrequencyCapFilters VALID_FREQUENCY_CAP_FILTERS_ONLY_CLICK =
            new FrequencyCapFilters.Builder()
                    .setKeyedFrequencyCapsForClickEvents(
                            KeyedFrequencyCapFixture.VALID_KEYED_FREQUENCY_CAP_SET)
                    .build();

    public static FrequencyCapFilters.Builder getValidFrequencyCapFiltersBuilder() {
        return new FrequencyCapFilters.Builder()
                .setKeyedFrequencyCapsForWinEvents(
                        KeyedFrequencyCapFixture.VALID_KEYED_FREQUENCY_CAP_SET)
                .setKeyedFrequencyCapsForImpressionEvents(
                        KeyedFrequencyCapFixture.VALID_KEYED_FREQUENCY_CAP_SET)
                .setKeyedFrequencyCapsForViewEvents(
                        KeyedFrequencyCapFixture.VALID_KEYED_FREQUENCY_CAP_SET)
                .setKeyedFrequencyCapsForClickEvents(
                        KeyedFrequencyCapFixture.VALID_KEYED_FREQUENCY_CAP_SET);
    }
}
