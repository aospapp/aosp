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

package android.adservices.common;

import android.net.Uri;

import com.android.adservices.common.JsonFixture;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableSet;

import org.json.JSONException;

import java.util.List;

/** Utility class supporting ad services API unit tests */
public class AdDataFixture {
    // TODO(b/266837113) Set to true once app install is unhidden
    public static final boolean APP_INSTALL_ENABLED = false;
    // TODO(b/221876775) Set to true once fcap is unhidden
    public static final boolean FCAP_ENABLED = false;
    public static final String VALID_METADATA = "{\"example\": \"metadata\", \"valid\": true}";
    public static final String INVALID_METADATA = "not.{real!metadata} = 1";

    public static ImmutableSet<String> getAdCounterKeys() {
        return ImmutableSet.<String>builder()
                .add(
                        KeyedFrequencyCapFixture.KEY1,
                        KeyedFrequencyCapFixture.KEY2,
                        KeyedFrequencyCapFixture.KEY3,
                        KeyedFrequencyCapFixture.KEY4)
                .build();
    }

    public static Uri getValidRenderUriByBuyer(AdTechIdentifier buyer, int sequence) {
        return CommonFixture.getUri(buyer, "/testing/hello" + sequence);
    }

    public static List<AdData> getValidAdsByBuyer(AdTechIdentifier buyer) {
        return ImmutableList.of(
                getValidAdDataByBuyer(buyer, 1),
                getValidAdDataByBuyer(buyer, 2),
                getValidAdDataByBuyer(buyer, 3),
                getValidAdDataByBuyer(buyer, 4));
    }

    // TODO(b/266837113) Merge with getValidAdsByBuyer once filters are unhidden
    public static List<AdData> getValidFilterAdsByBuyer(AdTechIdentifier buyer) {
        return ImmutableList.of(
                getValidFilterAdDataByBuyer(buyer, 1),
                getValidFilterAdDataByBuyer(buyer, 2),
                getValidAdDataByBuyer(buyer, 3),
                getValidAdDataByBuyer(buyer, 4));
    }

    public static List<AdData> getInvalidAdsByBuyer(AdTechIdentifier buyer) {
        return ImmutableList.of(
                new AdData.Builder()
                        .setRenderUri(getValidRenderUriByBuyer(buyer, 1))
                        .setMetadata(INVALID_METADATA)
                        .build(),
                new AdData.Builder()
                        .setRenderUri(getValidRenderUriByBuyer(buyer, 2))
                        .setMetadata(INVALID_METADATA)
                        .build(),
                new AdData.Builder()
                        .setRenderUri(getValidRenderUriByBuyer(buyer, 3))
                        .setMetadata(INVALID_METADATA)
                        .build(),
                new AdData.Builder()
                        .setRenderUri(getValidRenderUriByBuyer(buyer, 4))
                        .setMetadata(INVALID_METADATA)
                        .build());
    }

    public static AdData.Builder getValidAdDataBuilderByBuyer(
            AdTechIdentifier buyer, int sequenceNumber) {
        String metadata;
        try {
            metadata = JsonFixture.formatAsOrgJsonJSONObjectString(VALID_METADATA);
        } catch (JSONException exception) {
            throw new IllegalStateException("Error parsing valid metadata!", exception);
        }

        return new AdData.Builder()
                .setRenderUri(getValidRenderUriByBuyer(buyer, sequenceNumber))
                .setMetadata(metadata);
    }

    // TODO(b/266837113) Merge with getValidAdDataByBuyer once filters are unhidden
    public static AdData.Builder getValidFilterAdDataBuilderByBuyer(
            AdTechIdentifier buyer, int sequenceNumber) {
        return getValidAdDataBuilderByBuyer(buyer, sequenceNumber)
                .setAdCounterKeys(getAdCounterKeys())
                .setAdFilters(AdFiltersFixture.getValidAdFilters());
    }

    // TODO(b/266837113) Merge with getValidAdDataByBuyer once filters are unhidden
    public static AdData getValidFilterAdDataByBuyer(AdTechIdentifier buyer, int sequenceNumber) {
        return getValidFilterAdDataBuilderByBuyer(buyer, sequenceNumber).build();
    }

    public static AdData getValidAdDataByBuyer(AdTechIdentifier buyer, int sequenceNumber) {
        return getValidAdDataBuilderByBuyer(buyer, sequenceNumber).build();
    }
}
