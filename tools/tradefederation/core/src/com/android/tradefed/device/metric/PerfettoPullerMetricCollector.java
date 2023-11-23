/*
 * Copyright (C) 2018 The Android Open Source Project
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

import com.android.annotations.Nullable;
import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.DataType;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;
import com.android.tradefed.util.Pair;
import com.android.tradefed.util.RunUtil;

import com.google.common.base.Joiner;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Base implementation of {@link FilePullerDeviceMetricCollector} that allows
 * pulling the perfetto files from the device and collect the metrics from it.
 */
@OptionClass(alias = "perfetto-metric-collector")
public class PerfettoPullerMetricCollector extends FilePullerDeviceMetricCollector {

    private static final String LINE_SEPARATOR = "\\r?\\n";
    private static final char KEY_VALUE_SEPARATOR = ':';
    private static final String EXTRACTOR_STATUS = "trace_extractor_status";
    private static final String EXTRACTOR_SUCCESS = "1";
    private static final String EXTRACTOR_FAILURE = "0";
    private static final String EXTRACTOR_RUNTIME = "trace_extractor_runtime";

    @Option(
            name = "perfetto-binary-path",
            description = "Path to the script files used to analyze the trace files.")
    private List<File> mScriptFiles = new ArrayList<>();

    @Option(
            name = "perfetto-metric-prefix",
            description = "Prefix to be used with the metrics collected from perfetto.")
    private String mMetricPrefix = "perfetto";

    // List of process names passed to perfetto binary.
    @Option(
        name = "process-name",
        description =
                "Process names to be passed in perfetto script."
    )
    private Collection<String> mProcessNames = new ArrayList<String>();

    // Timeout for the script to process the trace files.
    // The default is arbitarily chosen to be 5 mins to prevent the test spending more time in
    // processing the files.
    @Option(
        name = "perfetto-script-timeout",
        description = "Timeout for the perfetto script.",
        isTimeVal = true
    )
    private long mScriptTimeoutMs = TimeUnit.MINUTES.toMillis(5);

    /**
     * Process the perfetto trace file for the additional metrics and add it to final metrics.
     *
     * @param key the option key associated to the file that was pulled from the device.
     * @param metricFile the {@link File} pulled from the device matching the option key.
     * @param data where metrics will be stored.
     */
    @Override
    public void processMetricFile(String key, File metricFile,
            DeviceMetricData data) {
        // Extract the metrics from the trace file.
        for (File scriptFile : mScriptFiles) {
            List<String> commandArgsList = new ArrayList<String>();
            commandArgsList.add(scriptFile.getAbsolutePath());
            commandArgsList.add("-trace_file");
            commandArgsList.add(metricFile.getAbsolutePath());

            if (!mProcessNames.isEmpty()) {
                commandArgsList.add("-process_names");
                commandArgsList.add(Joiner.on(",").join(mProcessNames));
            }

            String traceExtractorStatus = EXTRACTOR_SUCCESS;

            double scriptDuration = 0;
            double scriptStartTime = System.currentTimeMillis();
            CommandResult cr = runHostCommand(commandArgsList.toArray(new String[commandArgsList
                    .size()]));
            scriptDuration = System.currentTimeMillis() - scriptStartTime;

            // Update the script duration metrics.
            Metric.Builder metricDurationBuilder = Metric.newBuilder();
            metricDurationBuilder.getMeasurementsBuilder().setSingleDouble(scriptDuration);
            data.addMetric(
                    String.format("%s_%s", mMetricPrefix, EXTRACTOR_RUNTIME),
                    metricDurationBuilder.setType(DataType.RAW));

            if (CommandStatus.SUCCESS.equals(cr.getStatus())) {
                String[] metrics = cr.getStdout().split(LINE_SEPARATOR);
                for (String metric : metrics) {
                    Pair<String, String> kv = splitKeyValue(metric);

                    if (kv != null) {
                        Metric.Builder metricBuilder = Metric.newBuilder();
                        metricBuilder.getMeasurementsBuilder().setSingleString(kv.second);
                        data.addMetric(
                                String.format("%s_%s", mMetricPrefix, kv.first),
                                metricBuilder.setType(DataType.RAW));
                    } else {
                        CLog.e("Output %s not in the expected format.", metric);
                    }
                }
                CLog.i(cr.getStdout());
            } else {
                traceExtractorStatus = EXTRACTOR_FAILURE;
                CLog.e("Unable to parse the trace file %s due to %s - Status - %s ",
                        metricFile.getName(), cr.getStderr(), cr.getStatus());
            }

            Metric.Builder metricStatusBuilder = Metric.newBuilder();
            metricStatusBuilder.getMeasurementsBuilder().setSingleString(traceExtractorStatus);
            data.addMetric(
                    String.format("%s_%s", mMetricPrefix, EXTRACTOR_STATUS),
                    metricStatusBuilder.setType(DataType.RAW));
        }

        // Upload and delete the host trace file.
        try (InputStreamSource source = new FileInputStreamSource(metricFile, true)) {
            testLog(metricFile.getName(), LogDataType.PB, source);
        }
    }

    @Override
    public void processMetricDirectory(String key, File metricDirectory, DeviceMetricData runData) {
        // Implement if all the files under specific directory have to be post processed.
    }

    /**
     * Run a host command with the given array of command args.
     *
     * @param commandArgs args to be used to construct the host command.
     * @return return the command results.
     */
    @VisibleForTesting
    protected CommandResult runHostCommand(String[] commandArgs) {
        return RunUtil.getDefault().runTimedCmd(mScriptTimeoutMs, commandArgs);
    }

    @VisibleForTesting
    @Nullable
    static Pair<String, String> splitKeyValue(String s) {
        // Expected script test output format.
        // Key1:Value1
        // Key2:Value2
        int separatorIdx = s.lastIndexOf(KEY_VALUE_SEPARATOR);
        if (separatorIdx > 0 && separatorIdx + 1 < s.length()) {
            return new Pair<>(s.substring(0, separatorIdx), s.substring(separatorIdx + 1));
        }
        return null;
    }
}

