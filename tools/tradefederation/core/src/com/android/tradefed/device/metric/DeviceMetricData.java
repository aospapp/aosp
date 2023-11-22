/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.device.metric;

import java.io.Serializable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Object to hold all the data collected by metric collectors. TODO: Add the data holding and
 * receiving of data methods.
 */
public class DeviceMetricData implements Serializable {
    private static final long serialVersionUID = 1;

    // TODO: expend type supports to more complex type: Object, File, etc.
    private LinkedHashMap<String, String> mCurrentStringMetrics = new LinkedHashMap<>();

    public void addStringMetric(String key, String value) {
        mCurrentStringMetrics.put(key, value);
    }

    /**
     * Push all the data received so far to the map of metrics that will be reported. This should
     * also clean up the resources after pushing them.
     *
     * @param metrics The metrics currently available.
     */
    public void addToMetrics(Map<String, String> metrics) {
        // TODO: dump all the metrics collected to the map of metrics to be reported.
        metrics.putAll(mCurrentStringMetrics);
    }
}
