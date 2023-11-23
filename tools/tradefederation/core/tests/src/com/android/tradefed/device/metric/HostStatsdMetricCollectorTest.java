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
 * limitations under the License
 */
package com.android.tradefed.device.metric;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.MockitoAnnotations.initMocks;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.OptionSetter;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.Mock;
import org.mockito.Spy;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;

/** Unit Tests for {@link HostStatsdMetricCollector}. */
@RunWith(JUnit4.class)
public class HostStatsdMetricCollectorTest {
    private static final String STATSD_CONFIG = "statsd.config";
    private static final String[] DEVICE_SERIALS = new String[] {"device_1", "device_2"};
    private static final long CONFIG_ID = 54321L;

    @Mock private IInvocationContext mContext;
    @Mock private ITestInvocationListener mListener;
    @Spy private HostStatsdMetricCollector mCollector;
    @Rule public TemporaryFolder mFolder = new TemporaryFolder();

    @Before
    public void setUp() throws IOException {
        initMocks(this);
    }

    /** Test that a binary config is pushed and report is dumped from multiple devices. */
    @Test
    public void testMetricCollection_binaryConfig_multiDevice()
            throws IOException, ConfigurationException, DeviceNotAvailableException {
        OptionSetter options = new OptionSetter(mCollector);
        options.setOptionValue(
                "binary-stats-config", mFolder.newFile(STATSD_CONFIG).getAbsolutePath());

        List<ITestDevice> devices = new ArrayList<>();
        for (String serial : DEVICE_SERIALS) {
            devices.add(mockDevice(serial));
        }
        when(mContext.getDevices()).thenReturn(devices);
        doReturn(CONFIG_ID)
                .when(mCollector)
                .pushBinaryStatsConfig(any(ITestDevice.class), any(File.class));

        HashMap<String, Metric> runMetrics = new HashMap<>();
        mCollector.init(mContext, mListener);
        mCollector.testRunStarted("collect-metrics", 1);
        mCollector.testRunEnded(0L, runMetrics);

        for (ITestDevice device : devices) {
            verify(mCollector).pushBinaryStatsConfig(eq(device), any(File.class));
            verify(mCollector).getReportByteStream(eq(device), anyLong());
            verify(mCollector).removeConfig(eq(device), anyLong());
        }
    }

    private ITestDevice mockDevice(String serial) {
        ITestDevice device = mock(ITestDevice.class);
        when(device.getSerialNumber()).thenReturn(serial);
        return device;
    }
}
