package com.android.helpers;

import java.util.Map;

/**
 * MetricUtility consist of basic utility methods to construct the metrics
 * reported at the end of the test.
 */
public class MetricUtility {

    private static final String KEY_JOIN = "_";
    private static final String METRIC_SEPARATOR = ",";

    /**
     * Append the given array of string to construct the final key used to track the metrics.
     *
     * @param keys to append using KEY_JOIN
     */
    public static String constructKey(String... keys) {
        return String.join(KEY_JOIN, keys);
    }

    /**
     * Add metric to the result map. If metric key already exist append the new metric.
     *
     * @param metricKey Unique key to track the metric.
     * @param metric metric to track.
     * @param resultMap map of all the metrics.
     */
    public static void addMetric(String metricKey, long metric, Map<String,
            StringBuilder> resultMap) {
        resultMap.compute(metricKey, (key, value) -> (value == null) ?
                new StringBuilder().append(metric) : value.append(METRIC_SEPARATOR).append(metric));
    }

    /**
     * Add metric to the result map. If metric key already exist increment the value by 1.
     *
     * @param metricKey Unique key to track the metric.
     * @param resultMap map of all the metrics.
     */
    public static void addMetric(String metricKey, Map<String,
            Integer> resultMap) {
        resultMap.compute(metricKey, (key, value) -> (value == null) ? 1 : value + 1);
    }

}
