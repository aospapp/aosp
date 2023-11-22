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
package android.device.collectors;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.content.res.AssetManager;
import android.os.Bundle;

import com.android.internal.os.StatsdConfigProto.StatsdConfig;
import com.android.os.StatsLog.ConfigMetricsReportList;
import com.android.os.StatsLog.ConfigMetricsReportList.ConfigKey;
import com.google.common.collect.ImmutableMap;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.Description;
import org.junit.runner.Result;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.file.Paths;
import java.util.Map;

/** Unit tests for {@link StatsdListener}. */
public class StatsdListenerTest {
    private StatsdListener mListener;

    private static final String CONFIG_NAME_1 = "config-1";
    private static final String CONFIG_NAME_2 = "config-2";

    private static final long CONFIG_ID_1 = 1;
    private static final long CONFIG_ID_2 = 2;

    private static final StatsdConfig CONFIG_1 =
            StatsdConfig.newBuilder().setId(CONFIG_ID_1).build();
    private static final StatsdConfig CONFIG_2 =
            StatsdConfig.newBuilder().setId(CONFIG_ID_2).build();

    private static final ConfigMetricsReportList REPORT_1 =
            ConfigMetricsReportList.newBuilder()
                    .setConfigKey(ConfigKey.newBuilder().setUid(0).setId(CONFIG_ID_1))
                    .build();
    private static final ConfigMetricsReportList REPORT_2 =
            ConfigMetricsReportList.newBuilder()
                    .setConfigKey(ConfigKey.newBuilder().setUid(0).setId(CONFIG_ID_2))
                    .build();

    private static final ImmutableMap<String, StatsdConfig> CONFIG_MAP =
            ImmutableMap.of(CONFIG_NAME_1, CONFIG_1, CONFIG_NAME_2, CONFIG_2);

    @Rule public ExpectedException mExpectedException = ExpectedException.none();

    @Before
    public void setUp() throws Exception {
        mListener = spy(new StatsdListener());
        // Stub the report collection to isolate collector from StatsManager.
        doNothing().when(mListener).addStatsConfig(anyLong(), any());
        doReturn(REPORT_1.toByteArray()).when(mListener).getStatsReports(eq(CONFIG_ID_1));
        doReturn(REPORT_2.toByteArray()).when(mListener).getStatsReports(eq(CONFIG_ID_2));
        doNothing().when(mListener).removeStatsConfig(anyLong());
        // Stub calls to permission APIs.
        doNothing().when(mListener).adoptShellPermissionIdentity();
        doNothing().when(mListener).dropShellPermissionIdentity();
        // Stub file I/O.
        doAnswer(invocation -> invocation.getArgument(0)).when(mListener).writeToFile(any(), any());
        // Stub randome UUID generation.
        doReturn(CONFIG_ID_1).when(mListener).getUniqueIdForConfig(eq(CONFIG_1));
        doReturn(CONFIG_ID_2).when(mListener).getUniqueIdForConfig(eq(CONFIG_2));
    }

    /** Test that the collector has correct interactions with statsd for per-run collection. */
    @Test
    public void testPerRunCollection_statsdInteraction() throws Exception {
        doReturn(CONFIG_MAP)
                .when(mListener)
                .getConfigsFromOption(eq(StatsdListener.OPTION_CONFIGS_TEST_RUN));

        DataRecord runData = new DataRecord();
        Description description = Description.createSuiteDescription("TestRun");

        mListener.onTestRunStart(runData, description);
        verify(mListener, times(1)).addStatsConfig(eq(CONFIG_ID_1), eq(CONFIG_1.toByteArray()));
        verify(mListener, times(1)).addStatsConfig(eq(CONFIG_ID_2), eq(CONFIG_2.toByteArray()));

        mListener.onTestRunEnd(runData, new Result());
        verify(mListener, times(1)).getStatsReports(eq(CONFIG_ID_1));
        verify(mListener, times(1)).getStatsReports(eq(CONFIG_ID_2));
        verify(mListener, times(1)).removeStatsConfig(eq(CONFIG_ID_1));
        verify(mListener, times(1)).removeStatsConfig(eq(CONFIG_ID_2));
    }

    /** Test that the collector dumps reports and report them as metrics. */
    @Test
    public void testPerRunCollection_metrics() throws Exception {
        doReturn(CONFIG_MAP)
                .when(mListener)
                .getConfigsFromOption(eq(StatsdListener.OPTION_CONFIGS_TEST_RUN));

        // Mock the DataRecord class as its content is not directly visible.
        DataRecord runData = mock(DataRecord.class);
        Description description = Description.createSuiteDescription("TestRun");

        mListener.onTestRunStart(runData, description);
        mListener.onTestRunEnd(runData, new Result());

        verify(mListener, times(1))
                .writeToFile(
                        getFileNameMatcher(
                                Paths.get(
                                                StatsdListener.REPORT_PATH_ROOT,
                                                StatsdListener.REPORT_PATH_TEST_RUN)
                                        .toString(),
                                CONFIG_NAME_1 + StatsdListener.PROTO_EXTENSION),
                        any());
        verify(runData, times(1))
                .addFileMetric(
                        eq(CONFIG_NAME_1),
                        getFileNameMatcher(
                                Paths.get(
                                                StatsdListener.REPORT_PATH_ROOT,
                                                StatsdListener.REPORT_PATH_TEST_RUN)
                                        .toString(),
                                CONFIG_NAME_1 + StatsdListener.PROTO_EXTENSION));
        verify(mListener, times(1))
                .writeToFile(
                        getFileNameMatcher(
                                Paths.get(
                                                StatsdListener.REPORT_PATH_ROOT,
                                                StatsdListener.REPORT_PATH_TEST_RUN)
                                        .toString(),
                                CONFIG_NAME_2 + StatsdListener.PROTO_EXTENSION),
                        any());
        verify(runData, times(1))
                .addFileMetric(
                        eq(CONFIG_NAME_2),
                        getFileNameMatcher(
                                Paths.get(
                                                StatsdListener.REPORT_PATH_ROOT,
                                                StatsdListener.REPORT_PATH_TEST_RUN)
                                        .toString(),
                                CONFIG_NAME_2 + StatsdListener.PROTO_EXTENSION));
    }

    /** Test that the collector parses the configs from arguments correctly for valid configs. */
    @Test
    public void testParsingConfigFromArguments_validConfig() throws Exception {
        // Stub two configs for testing.
        ByteArrayInputStream config1Stream = new ByteArrayInputStream(CONFIG_1.toByteArray());
        doReturn(config1Stream)
                .when(mListener)
                .openConfigWithAssetManager(any(AssetManager.class), eq(CONFIG_NAME_1));

        ByteArrayInputStream config2Stream = new ByteArrayInputStream(CONFIG_2.toByteArray());
        doReturn(config2Stream)
                .when(mListener)
                .openConfigWithAssetManager(any(AssetManager.class), eq(CONFIG_NAME_2));

        Bundle args = new Bundle();
        args.putString(
                StatsdListener.OPTION_CONFIGS_TEST_RUN,
                String.join(",", CONFIG_NAME_1, CONFIG_NAME_2));
        doReturn(args).when(mListener).getArguments();

        Map<String, StatsdConfig> configs =
                mListener.getConfigsFromOption(StatsdListener.OPTION_CONFIGS_TEST_RUN);
        Assert.assertTrue(configs.containsKey(CONFIG_NAME_1));
        Assert.assertEquals(configs.get(CONFIG_NAME_1).getId(), CONFIG_ID_1);
        Assert.assertTrue(configs.containsKey(CONFIG_NAME_2));
        Assert.assertEquals(configs.get(CONFIG_NAME_2).getId(), CONFIG_ID_2);
        Assert.assertEquals(configs.size(), 2);
    }

    /** Test that the colletor fails and throws the right exception for an invalid config. */
    @Test
    public void testParsingConfigFromArguments_malformedConfig() throws Exception {
        // Set up an invalid config for testing.
        ByteArrayInputStream configStream = new ByteArrayInputStream("not a config".getBytes());
        doReturn(configStream)
                .when(mListener)
                .openConfigWithAssetManager(any(AssetManager.class), eq(CONFIG_NAME_1));

        Bundle args = new Bundle();
        args.putString(StatsdListener.OPTION_CONFIGS_TEST_RUN, CONFIG_NAME_1);
        doReturn(args).when(mListener).getArguments();

        mExpectedException.expectMessage("Cannot parse");
        Map<String, StatsdConfig> configs =
                mListener.getConfigsFromOption(StatsdListener.OPTION_CONFIGS_TEST_RUN);
    }

    /** Test that the collector fails and throws the right exception for a nonexistent config. */
    @Test
    public void testParsingConfigFromArguments_nonexistentConfig() {
        Bundle args = new Bundle();
        args.putString(StatsdListener.OPTION_CONFIGS_TEST_RUN, "nah");
        doReturn(args).when(mListener).getArguments();

        mExpectedException.expectMessage("does not exist");
        Map<String, StatsdConfig> configs =
                mListener.getConfigsFromOption(StatsdListener.OPTION_CONFIGS_TEST_RUN);
    }

    /** Test that the collector has no effect when no config arguments are supplied. */
    @Test
    public void testNoConfigArguments() throws Exception {
        doReturn(new Bundle()).when(mListener).getArguments();

        // Mock the DataRecord class as its content is not directly visible.
        DataRecord runData = mock(DataRecord.class);
        Description description = Description.createSuiteDescription("TestRun");

        mListener.onTestRunStart(runData, description);
        mListener.onTestRunEnd(runData, new Result());

        verify(runData, never()).addFileMetric(any(), any());
        verify(mListener, never()).addStatsConfig(anyLong(), any());
        verify(mListener, never()).getStatsReports(anyLong());
        verify(mListener, never()).removeStatsConfig(anyLong());
    }

    private File getFileNameMatcher(String parentName, String filename) {
        return argThat(f -> f.getParent().contains(parentName) && f.getName().equals(filename));
    }
}
