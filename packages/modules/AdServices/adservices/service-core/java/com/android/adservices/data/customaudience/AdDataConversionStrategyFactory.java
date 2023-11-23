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

package com.android.adservices.data.customaudience;

import android.adservices.common.AdData;
import android.adservices.common.AdFilters;
import android.net.Uri;

import androidx.annotation.NonNull;

import com.android.adservices.data.common.DBAdData;
import com.android.adservices.data.common.FledgeRoomConverters;
import com.android.adservices.service.common.JsonUtils;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
/** Factory for AdDataConversionStrategys */
public class AdDataConversionStrategyFactory {
    private static final String RENDER_URI_FIELD_NAME = "renderUri";
    private static final String METADATA_FIELD_NAME = "metadata";
    private static final String AD_COUNTER_KEYS_FIELD_NAME = "adCounterKeys";
    private static final String AD_FILTERS_FIELD_NAME = "adFilters";
    private static final String STRING_ERROR_FORMAT = "Index %d in %s is not a String.";

    private static class FilteringEnabledConversionStrategy implements AdDataConversionStrategy {
        /**
         * Serialize {@link DBAdData} to {@link JSONObject} including filter fields.
         *
         * @param adData the {@link DBAdData} object to serialize
         * @return the json serialization of the AdData object
         */
        public JSONObject toJson(DBAdData adData) throws JSONException {
            JSONObject toReturn = new JSONObject();
            toReturn.put(
                    RENDER_URI_FIELD_NAME,
                    FledgeRoomConverters.serializeUri(adData.getRenderUri()));
            toReturn.put(METADATA_FIELD_NAME, adData.getMetadata());
            if (!adData.getAdCounterKeys().isEmpty()) {
                JSONArray jsonCounterKeys = new JSONArray(adData.getAdCounterKeys());
                toReturn.put(AD_COUNTER_KEYS_FIELD_NAME, jsonCounterKeys);
            }
            if (adData.getAdFilters() != null) {
                toReturn.put(AD_FILTERS_FIELD_NAME, adData.getAdFilters().toJson());
            }
            return toReturn;
        }

        /**
         * Deserialize {@link DBAdData} to {@link JSONObject} including filter fields.
         *
         * @param json the {@link JSONObject} object to deserialize
         * @return the {@link DBAdData} deserialized from the json
         */
        public DBAdData fromJson(JSONObject json) throws JSONException {
            String renderUriString = JsonUtils.getStringFromJson(json, RENDER_URI_FIELD_NAME);
            String metadata = json.getString(METADATA_FIELD_NAME);
            Uri renderUri = FledgeRoomConverters.deserializeUri(renderUriString);
            Set<String> adCounterKeys = new HashSet<>();
            if (json.has(AD_COUNTER_KEYS_FIELD_NAME)) {
                JSONArray counterKeys = json.getJSONArray(AD_COUNTER_KEYS_FIELD_NAME);
                for (int i = 0; i < counterKeys.length(); i++) {
                    adCounterKeys.add(
                            JsonUtils.getStringFromJsonArrayAtIndex(
                                    counterKeys,
                                    i,
                                    String.format(
                                            STRING_ERROR_FORMAT, i, AD_COUNTER_KEYS_FIELD_NAME)));
                }
            }
            AdFilters adFilters = null;
            if (json.has(AD_FILTERS_FIELD_NAME)) {
                adFilters = AdFilters.fromJson(json.getJSONObject(AD_FILTERS_FIELD_NAME));
            }
            return new DBAdData(renderUri, metadata, adCounterKeys, adFilters);
        }

        /**
         * Parse parcelable {@link AdData} to storage model {@link DBAdData}.
         *
         * @param parcelable the service model.
         * @return storage model
         */
        @NonNull
        @Override
        public DBAdData fromServiceObject(@NonNull AdData parcelable) {
            return new DBAdData(
                    parcelable.getRenderUri(),
                    parcelable.getMetadata(),
                    parcelable.getAdCounterKeys(),
                    parcelable.getAdFilters());
        }
    }

    private static class FilteringDisabledConversionStrategy implements AdDataConversionStrategy {
        /**
         * Serialize {@link DBAdData} to {@link JSONObject}, but ignore filter fields.
         *
         * @param adData the {@link DBAdData} object to serialize
         * @return the json serialization of the AdData object
         */
        public JSONObject toJson(DBAdData adData) throws JSONException {
            return new org.json.JSONObject()
                    .put(
                            RENDER_URI_FIELD_NAME,
                            FledgeRoomConverters.serializeUri(adData.getRenderUri()))
                    .put(METADATA_FIELD_NAME, adData.getMetadata());
        }

        /**
         * Deserialize {@link DBAdData} to {@link JSONObject} but ignore filter fields.
         *
         * @param json the {@link JSONObject} object to deserialize
         * @return the {@link DBAdData} deserialized from the json
         */
        public DBAdData fromJson(JSONObject json) throws JSONException {
            String renderUriString = json.getString(RENDER_URI_FIELD_NAME);
            String metadata = json.getString(METADATA_FIELD_NAME);
            Uri renderUri = FledgeRoomConverters.deserializeUri(renderUriString);
            return new DBAdData.Builder().setRenderUri(renderUri).setMetadata(metadata).build();
        }

        /**
         * Parse parcelable {@link AdData} to storage model {@link DBAdData}.
         *
         * @param parcelable the service model.
         * @return storage model
         */
        @NonNull
        @Override
        public DBAdData fromServiceObject(@NonNull AdData parcelable) {
            return new DBAdData(
                    parcelable.getRenderUri(),
                    parcelable.getMetadata(),
                    Collections.emptySet(),
                    null);
        }
    }

    /**
     * Returns the appropriate AdDataConversionStrategy based whether filtering is enabled
     *
     * @param filteringEnabled Should be true if filtering is enabled.
     * @return An implementation of AdDataConversionStrategy
     */
    public static AdDataConversionStrategy getAdDataConversionStrategy(boolean filteringEnabled) {
        if (filteringEnabled) {
            return new FilteringEnabledConversionStrategy();
        }
        return new FilteringDisabledConversionStrategy();
    }
}
