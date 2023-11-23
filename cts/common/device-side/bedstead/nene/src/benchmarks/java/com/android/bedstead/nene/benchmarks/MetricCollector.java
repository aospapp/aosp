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

package com.android.bedstead.nene.benchmarks;

import android.device.collectors.BaseMetricListener;
import android.device.collectors.DataRecord;
import android.util.Log;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.runner.Description;
import org.junit.runner.Result;
import org.junit.runner.notification.Failure;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** {@link BaseMetricListener} which reports benchmark results as benchmark tests execute. */
public class MetricCollector extends BaseMetricListener {
    private static final String LOG_TAG = MetricCollector.class.getSimpleName();

    private static final String BENCHMARKS_PACKAGE = MetricCollector.class.getPackage().getName();
    private static final String BENCHMARKS_JSON_PATH =
            String.format(
                    "/storage/emulated/0/Android/media/%1$s/%1$s-benchmarkData.json",
                    BENCHMARKS_PACKAGE);

    private static final Pattern BENCHMARK_PATTERN = Pattern.compile("^.*\\[(.+)].*$");

    private final Map<String, BenchmarkResult> mBenchmarkResultsMap = new HashMap<>();

    @Override
    public void onTestRunStart(DataRecord runData, Description description) {
        Log.i(LOG_TAG, "Started test run.");
    }

    @Override
    public void onTestStart(DataRecord testData, Description description) {
        Log.i(
                LOG_TAG,
                String.format(
                        "Test started for benchmark '%s'.",
                        getBenchmarkName(description.getDisplayName())));
    }

    @Override
    public void onTestEnd(DataRecord testData, Description description) {
        processBenchmarks();

        String benchmarkName = getBenchmarkName(description.getDisplayName());
        Log.i(LOG_TAG, String.format("Test finished for benchmark '%s'.", benchmarkName));
        Log.i(LOG_TAG, getOutputForBenchmark(benchmarkName));
    }

    @Override
    public void onTestFail(DataRecord testData, Description description, Failure failure) {
        Log.w(
                LOG_TAG,
                String.format(
                        "Test failed for benchmark '%s'.",
                        getBenchmarkName(description.getDisplayName())));
    }

    @Override
    public void onTestRunEnd(DataRecord runData, Result result) {
        Log.i(LOG_TAG, "Finished test run.");

        StringBuilder output = new StringBuilder();
        output.append("Nene Benchmarks: API Runtime (milliseconds)\n");
        for (String benchmark : mBenchmarkResultsMap.keySet()) {
            output.append(getOutputForBenchmark(benchmark));
        }
        Log.i(LOG_TAG, output.toString());
    }

    private void processBenchmarks() {
        if (!new File(BENCHMARKS_JSON_PATH).exists()) {
            Log.w(LOG_TAG, "Could not find benchmarks JSON file.");
            return;
        }

        String benchmarksJsonString = readFile(BENCHMARKS_JSON_PATH);

        try {
            JSONArray benchmarksArray =
                    new JSONObject(benchmarksJsonString).getJSONArray("benchmarks");

            for (int index = 0; index < benchmarksArray.length(); ++index) {
                JSONObject currentBenchmark = benchmarksArray.getJSONObject(index);

                String name = getBenchmarkName(currentBenchmark.getString("name"));

                if (mBenchmarkResultsMap.containsKey(name)) {
                    continue;
                }

                double[] runs =
                        toMilliseconds(
                                asArray(
                                        currentBenchmark
                                                .getJSONObject("metrics")
                                                .getJSONObject("timeNs")
                                                .getJSONArray("runs")));
                Arrays.sort(runs);

                double minimum = runs[0];
                double maximum = runs[runs.length - 1];
                double median = median(runs);
                double mean = mean(runs);
                double standardDeviation = standardDeviation(runs, mean);

                mBenchmarkResultsMap.put(
                        name,
                        BenchmarkResult.newBuilder()
                                .setMetadata(BenchmarkDefinition.getBenchmark(name).metadata())
                                .setRuntimeMetrics(
                                        BenchmarkResult.RuntimeMetrics.newBuilder()
                                                .setMinimum(minimum)
                                                .setMaximum(maximum)
                                                .setMedian(median)
                                                .setMean(mean)
                                                .setStandardDeviation(standardDeviation))
                                .build());
            }
        } catch (JSONException e) {
            throw new Error(e);
        }
    }

    private String getOutputForBenchmark(String name) {
        BenchmarkResult result = mBenchmarkResultsMap.get(name);

        if (result == null) {
            return String.format("Benchmark '%s' not found", name);
        }

        BenchmarkResult.RuntimeMetrics runtimeMetrics = result.getRuntimeMetrics();

        return String.format(
                "=====\n"
                        + "Benchmark '%s'\n"
                        + "Median: \t\t%17.9f\n"
                        + "Mean: \t\t%17.9f\n"
                        + "Standard deviation: \t%17.9f\n"
                        + "Minimum: \t\t%17.9f\n"
                        + "Maximum: \t\t%17.9f\n",
                name,
                runtimeMetrics.getMedian(),
                runtimeMetrics.getMean(),
                runtimeMetrics.getStandardDeviation(),
                runtimeMetrics.getMinimum(),
                runtimeMetrics.getMaximum());
    }

    private static String getBenchmarkName(String runName) {
        Matcher matcher = BENCHMARK_PATTERN.matcher(runName);

        if (matcher.matches()) {
            return matcher.group(1);
        }

        throw new Error("Benchmark name not found");
    }

    private static double[] asArray(JSONArray array) throws JSONException {
        double[] result = new double[array.length()];
        for (int index = 0; index < result.length; ++index) {
            result[index] = array.getDouble(index);
        }
        return result;
    }

    private static double[] toMilliseconds(double[] nanosecondsArray) {
        double[] output = new double[nanosecondsArray.length];
        for (int index = 0; index < nanosecondsArray.length; ++index) {
            output[index] = nanosecondsArray[index] / 1_000_000.0;
        }
        return output;
    }

    private static double median(double[] sortedArray) {
        if ((sortedArray.length % 2) == 0) {
            int middleRight = sortedArray.length / 2;
            int middleLeft = middleRight - 1;
            return (sortedArray[middleLeft] + sortedArray[middleRight]) / 2.0;
        }

        return sortedArray[sortedArray.length / 2];
    }

    private static double standardDeviation(double[] array, double mean) {
        double standardSum = 0;
        for (double value : array) {
            double meanDifference = value - mean;
            standardSum += meanDifference * meanDifference;
        }
        return Math.sqrt(standardSum / array.length);
    }

    private static double mean(double[] array) {
        return sum(array) / array.length;
    }

    private static double sum(double[] array) {
        double sum = 0;
        for (double value : array) {
            sum += value;
        }
        return sum;
    }

    private static String readFile(String path) {
        try (FileInputStream inputStream = new FileInputStream(path)) {
            return readStreamAsString(inputStream);
        } catch (IOException e) {
            throw new Error(e);
        }
    }

    private static String readStreamAsString(InputStream inputStream) throws IOException {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(inputStream))) {
            StringBuilder stringBuilder = new StringBuilder();

            while (true) {
                String line = reader.readLine();

                if (line == null) {
                    break;
                }

                stringBuilder.append(line);
            }

            return stringBuilder.toString();
        }
    }
}
