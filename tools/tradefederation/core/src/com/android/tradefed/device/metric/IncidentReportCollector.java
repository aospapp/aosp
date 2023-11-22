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

import android.os.IncidentProto;

import com.android.tradefed.config.OptionClass;
import com.android.tradefed.result.ByteArrayInputStreamSource;
import com.android.tradefed.result.LogDataType;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;

/**
 * Pulls and processes incident reports that are reported device-side.
 *
 * <p>TODO(b/119418529): Collect an incident report host-side on failure.
 */
@OptionClass(alias = "incident-collector")
public class IncidentReportCollector extends FilePullerLogCollector {
    // Prefix of the keys for all incident files passed from the device.
    private static final String INCIDENT_KEY_MATCHER = "incident-report";
    // Suffix for all of the logs that are processed incident reports.
    private static final String PROCESSED_KEY_SUFFIX = "-processed";

    public IncidentReportCollector() {
        addKeys(INCIDENT_KEY_MATCHER);
    }

    @Override
    protected void postProcessMetricFile(String key, File metricFile, DeviceMetricData runData) {
        // Read and interpret the incident report's bytes.
        IncidentProto processedReport;
        try {
            byte[] output = Files.readAllBytes(metricFile.toPath());
            processedReport = IncidentProto.parser().parseFrom(output);
        } catch (InvalidProtocolBufferException e) {
            throw new RuntimeException(
                    String.format("Failed to parse protobuf: %s", metricFile.toPath().toString()),
                    e);
        } catch (IOException e) {
            throw new RuntimeException(
                    String.format(
                            "Failed to read incident file: %s", metricFile.toPath().toString()),
                    e);
        }
        // Report the newly processed incident report.
        testLog(
                metricFile.getName().concat(PROCESSED_KEY_SUFFIX),
                LogDataType.PB,
                new ByteArrayInputStreamSource(processedReport.toString().getBytes()));
    }
}
