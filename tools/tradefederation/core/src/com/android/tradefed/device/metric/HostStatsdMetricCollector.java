/*
 * Copyright (C) 2019 The Android Open Source Project
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

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.InputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.util.statsd.ConfigUtil;
import com.android.tradefed.util.statsd.MetricUtil;
import com.google.common.annotations.VisibleForTesting;

import java.io.File;
import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

/**
 * A {@link IMetricCollector} that collects statsd metrics from host side using statsd utility
 * commands. It has basic push metrics and dump report functions. It can be extended by subclasses
 * to process statsd metric report based on the needs.
 */
@OptionClass(alias = "host-statsd-collector")
public class HostStatsdMetricCollector extends BaseDeviceMetricCollector {

    @Option(name = "binary-stats-config", description = "Path to the binary Statsd config file")
    private File mBinaryConfig;

    /** Map from device serial number to config ID on that device */
    private Map<String, Long> mDeviceConfigIds = new HashMap<>();

    @Override
    public void onTestRunStart(DeviceMetricData runData) {
        mDeviceConfigIds.clear();
        for (ITestDevice device : getDevices()) {
            String serialNumber = device.getSerialNumber();
            try {
                long configId = pushBinaryStatsConfig(device, mBinaryConfig);
                CLog.d(
                        "Pushed binary stats config to device %s with config id: %d",
                        serialNumber, configId);
                mDeviceConfigIds.put(serialNumber, configId);
            } catch (IOException | DeviceNotAvailableException e) {
                CLog.e("Failed to push stats config to device %s, error: %s", serialNumber, e);
            }
        }
    }

    @Override
    public void onTestRunEnd(
            DeviceMetricData runData, final Map<String, Metric> currentRunMetrics) {
        for (ITestDevice device : getDevices()) {
            String serialNumber = device.getSerialNumber();
            if (mDeviceConfigIds.containsKey(serialNumber)) {
                long configId = mDeviceConfigIds.get(serialNumber);
                try {
                    InputStreamSource dataStream = getReportByteStream(device, configId);
                    CLog.d(
                            "Retrieved stats report from device %s for config %d",
                            serialNumber, configId);
                    processStatsReport(device, dataStream, runData);
                    testLog(
                            String.format("device_%s_stats_report", serialNumber),
                            LogDataType.PB,
                            dataStream);
                    removeConfig(device, configId);
                } catch (DeviceNotAvailableException e) {
                    CLog.e("Device %s not available: %s", serialNumber, e);
                }
            }
        }
    }

    @VisibleForTesting
    InputStreamSource getReportByteStream(ITestDevice device, long configId)
            throws DeviceNotAvailableException {
        return MetricUtil.getReportByteStream(device, configId);
    }

    @VisibleForTesting
    long pushBinaryStatsConfig(ITestDevice device, File configFile)
            throws IOException, DeviceNotAvailableException {
        return ConfigUtil.pushBinaryStatsConfig(device, configFile);
    }

    @VisibleForTesting
    void removeConfig(ITestDevice device, long configId) throws DeviceNotAvailableException {
        ConfigUtil.removeConfig(device, configId);
    }

    /**
     * Subclasses can implement the method to process Statsd metric report if needed. It is called
     * for metric report from a particular device
     *
     * @param device Test device where the statsd report is coming from
     * @param dataStream Stats report as input stream
     * @param runData The destination where the processed metrics will be stored
     */
    protected void processStatsReport(
            ITestDevice device, InputStreamSource dataStream, DeviceMetricData runData) {}
}
