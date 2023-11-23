/*
 * Copyright 2018 The Android Open Source Project
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
package com.android.bluetooth.btservice;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.doReturn;

import androidx.test.filters.MediumTest;
import androidx.test.runner.AndroidJUnit4;

import com.android.bluetooth.btservice.AdapterService;
import com.android.bluetooth.BluetoothMetricsProto.BluetoothLog;
import com.android.bluetooth.BluetoothMetricsProto.ProfileConnectionStats;
import com.android.bluetooth.BluetoothMetricsProto.ProfileId;

import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.HashMap;
import java.util.List;

/**
 * Unit tests for {@link MetricsLogger}
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class MetricsLoggerTest {
    private TestableMetricsLogger mTestableMetricsLogger;
    @Mock
    private AdapterService mMockAdapterService;

    public class TestableMetricsLogger extends MetricsLogger {
        public HashMap<Integer, Long> mTestableCounters = new HashMap<>();

        @Override
        protected void writeCounter(int key, long count) {
            mTestableCounters.put(key, count);
        }

        @Override
        protected void scheduleDrains() {
        }

        @Override
        protected void cancelPendingDrain() {
        }
    }

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        // Dump metrics to clean up internal states
        MetricsLogger.dumpProto(BluetoothLog.newBuilder());
        mTestableMetricsLogger = new TestableMetricsLogger();
        doReturn(null)
                .when(mMockAdapterService).registerReceiver(any(), any());
    }

    @After
    public void tearDown() {
        // Dump metrics to clean up internal states
        MetricsLogger.dumpProto(BluetoothLog.newBuilder());
        mTestableMetricsLogger.close();
    }

    /**
     * Simple test to verify that profile connection event can be logged, dumped, and cleaned
     */
    @Test
    public void testLogProfileConnectionEvent() {
        MetricsLogger.logProfileConnectionEvent(ProfileId.AVRCP);
        BluetoothLog.Builder metricsBuilder = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilder);
        BluetoothLog metricsProto = metricsBuilder.build();
        Assert.assertEquals(1, metricsProto.getProfileConnectionStatsCount());
        ProfileConnectionStats profileUsageStatsAvrcp = metricsProto.getProfileConnectionStats(0);
        Assert.assertEquals(ProfileId.AVRCP, profileUsageStatsAvrcp.getProfileId());
        Assert.assertEquals(1, profileUsageStatsAvrcp.getNumTimesConnected());
        // Verify that MetricsLogger's internal state is cleared after a dump
        BluetoothLog.Builder metricsBuilderAfterDump = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilderAfterDump);
        BluetoothLog metricsProtoAfterDump = metricsBuilderAfterDump.build();
        Assert.assertEquals(0, metricsProtoAfterDump.getProfileConnectionStatsCount());
    }

    /**
     * Test whether multiple profile's connection events can be logged interleaving
     */
    @Test
    public void testLogProfileConnectionEventMultipleProfile() {
        MetricsLogger.logProfileConnectionEvent(ProfileId.AVRCP);
        MetricsLogger.logProfileConnectionEvent(ProfileId.HEADSET);
        MetricsLogger.logProfileConnectionEvent(ProfileId.AVRCP);
        BluetoothLog.Builder metricsBuilder = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilder);
        BluetoothLog metricsProto = metricsBuilder.build();
        Assert.assertEquals(2, metricsProto.getProfileConnectionStatsCount());
        HashMap<ProfileId, ProfileConnectionStats> profileConnectionCountMap =
                getProfileUsageStatsMap(metricsProto.getProfileConnectionStatsList());
        Assert.assertTrue(profileConnectionCountMap.containsKey(ProfileId.AVRCP));
        Assert.assertEquals(2,
                profileConnectionCountMap.get(ProfileId.AVRCP).getNumTimesConnected());
        Assert.assertTrue(profileConnectionCountMap.containsKey(ProfileId.HEADSET));
        Assert.assertEquals(1,
                profileConnectionCountMap.get(ProfileId.HEADSET).getNumTimesConnected());
        // Verify that MetricsLogger's internal state is cleared after a dump
        BluetoothLog.Builder metricsBuilderAfterDump = BluetoothLog.newBuilder();
        MetricsLogger.dumpProto(metricsBuilderAfterDump);
        BluetoothLog metricsProtoAfterDump = metricsBuilderAfterDump.build();
        Assert.assertEquals(0, metricsProtoAfterDump.getProfileConnectionStatsCount());
    }

    private static HashMap<ProfileId, ProfileConnectionStats> getProfileUsageStatsMap(
            List<ProfileConnectionStats> profileUsageStats) {
        HashMap<ProfileId, ProfileConnectionStats> profileUsageStatsMap = new HashMap<>();
        profileUsageStats.forEach(item -> profileUsageStatsMap.put(item.getProfileId(), item));
        return profileUsageStatsMap;
    }

    /**
     * Test add counters and send them to statsd
     */
    @Test
    public void testAddAndSendCountersNormalCases() {
        mTestableMetricsLogger.init(mMockAdapterService);
        mTestableMetricsLogger.count(1, 10);
        mTestableMetricsLogger.count(1, 10);
        mTestableMetricsLogger.count(2, 5);
        mTestableMetricsLogger.drainBufferedCounters();

        Assert.assertEquals(20L, mTestableMetricsLogger.mTestableCounters.get(1).longValue());
        Assert.assertEquals(5L, mTestableMetricsLogger.mTestableCounters.get(2).longValue());

        mTestableMetricsLogger.count(1, 3);
        mTestableMetricsLogger.count(2, 5);
        mTestableMetricsLogger.count(2, 5);
        mTestableMetricsLogger.count(3, 1);
        mTestableMetricsLogger.drainBufferedCounters();
        Assert.assertEquals(
                3L, mTestableMetricsLogger.mTestableCounters.get(1).longValue());
        Assert.assertEquals(
                10L, mTestableMetricsLogger.mTestableCounters.get(2).longValue());
        Assert.assertEquals(
                1L, mTestableMetricsLogger.mTestableCounters.get(3).longValue());
    }

    @Test
    public void testAddAndSendCountersCornerCases() {
        mTestableMetricsLogger.init(mMockAdapterService);
        Assert.assertTrue(mTestableMetricsLogger.isInitialized());
        mTestableMetricsLogger.count(1, -1);
        mTestableMetricsLogger.count(3, 0);
        mTestableMetricsLogger.count(2, 10);
        mTestableMetricsLogger.count(2, Long.MAX_VALUE - 8L);
        mTestableMetricsLogger.drainBufferedCounters();

        Assert.assertFalse(mTestableMetricsLogger.mTestableCounters.containsKey(1));
        Assert.assertFalse(mTestableMetricsLogger.mTestableCounters.containsKey(3));
        Assert.assertEquals(
                Long.MAX_VALUE, mTestableMetricsLogger.mTestableCounters.get(2).longValue());
    }

    @Test
    public void testMetricsLoggerClose() {
        mTestableMetricsLogger.init(mMockAdapterService);
        mTestableMetricsLogger.count(1, 1);
        mTestableMetricsLogger.count(2, 10);
        mTestableMetricsLogger.count(2, Long.MAX_VALUE);
        mTestableMetricsLogger.close();

        Assert.assertEquals(
                1, mTestableMetricsLogger.mTestableCounters.get(1).longValue());
        Assert.assertEquals(
                Long.MAX_VALUE, mTestableMetricsLogger.mTestableCounters.get(2).longValue());
    }

    @Test
    public void testMetricsLoggerNotInit() {
        Assert.assertFalse(mTestableMetricsLogger.count(1, 1));
        mTestableMetricsLogger.drainBufferedCounters();
        Assert.assertFalse(mTestableMetricsLogger.mTestableCounters.containsKey(1));
        Assert.assertFalse(mTestableMetricsLogger.close());
    }

    @Test
    public void testAddAndSendCountersDoubleInit() {
        Assert.assertTrue(mTestableMetricsLogger.init(mMockAdapterService));
        Assert.assertTrue(mTestableMetricsLogger.isInitialized());
        Assert.assertFalse(mTestableMetricsLogger.init(mMockAdapterService));
    }
}
