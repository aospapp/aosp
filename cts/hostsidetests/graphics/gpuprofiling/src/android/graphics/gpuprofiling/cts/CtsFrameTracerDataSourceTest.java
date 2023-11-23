/*
 * Copyright (C) 2020 The Android Open Source Project
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

package android.graphics.gpuprofiling.cts;

import com.android.tradefed.testtype.DeviceJUnit4ClassRunner;
import com.android.tradefed.testtype.junit4.BaseHostJUnit4Test;
import com.android.tradefed.util.CommandResult;
import com.android.tradefed.util.CommandStatus;

import perfetto.protos.PerfettoConfig.TracingServiceState;
import perfetto.protos.PerfettoConfig.DataSourceDescriptor;

import java.util.Base64;

import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test that ensures the device registers android.surfaceflinger.frame perfetto producer
 */
@RunWith(DeviceJUnit4ClassRunner.class)
public class CtsFrameTracerDataSourceTest extends BaseHostJUnit4Test {
    public static final String TAG = "GpuProfilingDataDeviceActivity";
    private static final String FRAME_TRACER_SOURCE_NAME = "android.surfaceflinger.frame";

    @Test
    public void testFrameTracerProducerAvailable() throws Exception {
        CommandResult queryResult = getDevice().executeShellV2Command("perfetto --query-raw | base64");
        Assert.assertEquals(CommandStatus.SUCCESS, queryResult.getStatus());
        byte[] decodedBytes = Base64.getMimeDecoder().decode(queryResult.getStdout());
        TracingServiceState state = TracingServiceState.parseFrom(decodedBytes);
        int dataSourcesCount = state.getDataSourcesCount();
        Assert.assertTrue("No sources found", dataSourcesCount > 0);
        boolean sourceFound = false;
        for (int i = 0; i < dataSourcesCount; i++) {
            DataSourceDescriptor descriptor = state.getDataSources(i).getDsDescriptor();
            if (descriptor != null) {
                if (descriptor.getName().equals(FRAME_TRACER_SOURCE_NAME)) {
                    sourceFound = true;
                    break;
                }
            }
        }
        Assert.assertTrue("Producer " + FRAME_TRACER_SOURCE_NAME + " not found", sourceFound);
    }
}
