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

package com.android.adservices.service.measurement.util;

import android.annotation.NonNull;

import com.android.adservices.service.measurement.FilterMap;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Filtering utilities for measurement. */
public final class Filter {
    private Filter() { }

    /**
     * Checks whether source filter and trigger filter are matched. When a key is only present in
     * source or trigger, ignore that key. When a key is present both in source and trigger, the key
     * matches if the intersection of values is not empty.
     *
     * @param sourceFilter the {@code FilterMap} in attribution source.
     * @param triggerFilters a list of {@code FilterMap}, the trigger filter set.
     * @param isFilter true for filters, false for not_filters.
     * @return return true when all keys shared by source filter and trigger filter are matched.
     */
    public static boolean isFilterMatch(
            FilterMap sourceFilter, List<FilterMap> triggerFilters, boolean isFilter) {
        if (sourceFilter.getAttributionFilterMap().isEmpty() || triggerFilters.isEmpty()) {
            return true;
        }
        for (FilterMap filterMap : triggerFilters) {
            if (isFilterMatch(sourceFilter, filterMap, isFilter)) {
                return true;
            }
        }
        return false;
    }

    private static boolean isFilterMatch(
            FilterMap sourceFilter, FilterMap triggerFilter, boolean isFilter) {
        for (String key : triggerFilter.getAttributionFilterMap().keySet()) {
            if (!sourceFilter.getAttributionFilterMap().containsKey(key)) {
                continue;
            }
            // Finds the intersection of two value lists.
            List<String> sourceValues = sourceFilter.getAttributionFilterMap().get(key);
            List<String> triggerValues = triggerFilter.getAttributionFilterMap().get(key);
            if (!matchFilterValues(sourceValues, triggerValues, isFilter)) {
                return false;
            }
        }
        return true;
    }

    private static boolean matchFilterValues(List<String> sourceValues, List<String> triggerValues,
            boolean isFilter) {
        if (triggerValues.isEmpty()) {
            return isFilter ? sourceValues.isEmpty() : !sourceValues.isEmpty();
        }
        Set<String> intersection = new HashSet<>(sourceValues);
        intersection.retainAll(triggerValues);
        return isFilter ? !intersection.isEmpty() : intersection.isEmpty();
    }

    /**
     * Deserializes the provided {@link JSONArray} of filters into filter set.
     *
     * @param filters serialized filter set
     * @return deserialized filter set
     * @throws JSONException if the deserialization fails
     */
    @NonNull
    public static List<FilterMap> deserializeFilterSet(@NonNull JSONArray filters)
            throws JSONException {
        List<FilterMap> filterSet = new ArrayList<>();
        for (int i = 0; i < filters.length(); i++) {
            FilterMap filterMap =
                    new FilterMap.Builder().buildFilterData(filters.getJSONObject(i)).build();
            filterSet.add(filterMap);
        }
        return filterSet;
    }

    /**
     * Builds {@link JSONArray} our of the list of {@link List<FilterMap>} provided by serializing
     * it recursively.
     *
     * @param filterMaps to be serialized
     * @return serialized filter maps
     */
    @NonNull
    public static JSONArray serializeFilterSet(@NonNull List<FilterMap> filterMaps) {
        JSONArray serializedFilterMaps = new JSONArray();
        for (FilterMap sourceNotFilter : filterMaps) {
            serializedFilterMaps.put(sourceNotFilter.serializeAsJson());
        }
        return serializedFilterMaps;
    }

    /**
     * Filters can be available in either {@link JSONObject} format or {@link JSONArray} format. For
     * consistency across the board, this method wraps the {@link JSONObject} into {@link
     * JSONArray}.
     *
     * @param json json where to look for the filter object
     * @param key key with which the filter object is associated
     * @return wrapped {@link JSONArray}
     * @throws JSONException when creation of {@link JSONArray} fails
     */
    @NonNull
    public static JSONArray maybeWrapFilters(@NonNull JSONObject json, @NonNull String key)
            throws JSONException {
        JSONObject maybeFilterMap = json.optJSONObject(key);
        if (maybeFilterMap != null) {
            JSONArray filterSet = new JSONArray();
            filterSet.put(maybeFilterMap);
            return filterSet;
        }
        return json.getJSONArray(key);
    }
}
