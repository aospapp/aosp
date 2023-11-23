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

package com.android.adservices.service.measurement;

import android.annotation.Nullable;

import com.android.adservices.LogUtil;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;


/**
 * POJO for AggregatableAttributionFilterMap.
 */
public class FilterMap {

    private Map<String, List<String>> mAttributionFilterMap;

    FilterMap() {
        mAttributionFilterMap = new HashMap<>();
    }

    @Override
    public boolean equals(Object obj) {
        if (!(obj instanceof FilterMap)) {
            return false;
        }
        FilterMap attributionFilterMap = (FilterMap) obj;
        return Objects.equals(mAttributionFilterMap, attributionFilterMap.mAttributionFilterMap);
    }

    @Override
    public int hashCode() {
        return Objects.hash(mAttributionFilterMap);
    }

    /**
     * Returns the attribution filter map.
     */
    public Map<String, List<String>> getAttributionFilterMap() {
        return mAttributionFilterMap;
    }

    /**
     * Serializes the object into a {@link JSONObject}.
     *
     * @return serialized {@link JSONObject}.
     */
    @Nullable
    public JSONObject serializeAsJson() {
        if (mAttributionFilterMap == null) {
            return null;
        }

        try {
            JSONObject result = new JSONObject();
            for (String key : mAttributionFilterMap.keySet()) {
                result.put(key, new JSONArray(mAttributionFilterMap.get(key)));
            }

            return result;
        } catch (JSONException e) {
            LogUtil.d(e, "Failed to serialize filtermap.");
            return null;
        }
    }

    /**
     * Builder for {@link FilterMap}.
     */
    public static final class Builder {
        private final FilterMap mBuilding;

        public Builder() {
            mBuilding = new FilterMap();
        }

        /**
         * See {@link FilterMap#getAttributionFilterMap()}.
         */
        public Builder setAttributionFilterMap(Map<String, List<String>> attributionFilterMap) {
            mBuilding.mAttributionFilterMap = attributionFilterMap;
            return this;
        }

        /**
         * Builds FilterMap from JSONObject.
         */
        public Builder buildFilterData(JSONObject jsonObject)
                throws JSONException {
            Map<String, List<String>> filterMap = new HashMap<>();
            for (String key : jsonObject.keySet()) {
                JSONArray jsonArray = jsonObject.getJSONArray(key);
                List<String> filterMapList = new ArrayList<>();
                for (int i = 0; i < jsonArray.length(); i++) {
                    filterMapList.add(jsonArray.getString(i));
                }
                filterMap.put(key, filterMapList);
            }
            mBuilding.mAttributionFilterMap = filterMap;
            return this;
        }

        /**
         * Build the {@link FilterMap}.
         */
        public FilterMap build() {
            return mBuilding;
        }
    }
}
