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

package com.android.car.telemetry.publisher;

import static com.android.car.telemetry.publisher.MemoryPublisher.BUNDLE_KEY_COLLECT_INDEFINITELY;
import static com.android.car.telemetry.publisher.MemoryPublisher.BUNDLE_KEY_NUM_SNAPSHOTS_UNTIL_FINISH;
import static com.android.car.telemetry.publisher.MemoryPublisher.DATA_BUNDLE_KEY_MEMINFO;
import static com.android.car.telemetry.publisher.MemoryPublisher.THROTTLE_MILLIS;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.telemetry.TelemetryProto;
import android.os.Looper;
import android.os.Message;
import android.os.PersistableBundle;
import android.os.SystemClock;

import com.android.car.telemetry.ResultStore;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.car.test.FakeHandlerWrapper;

import com.google.common.collect.Range;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

@RunWith(MockitoJUnitRunner.class)
public class MemoryPublisherTest {
    private static final int TEN_SECONDS = 10;
    private static final String FAKE_MEMINFO = new StringBuilder()
            .append("MemTotal:        7645304 kB\n")
            .append("MemFree:         1927364 kB\n")
            .append("MemAvailable:    5312884 kB\n")
            .append("Buffers:          224380 kB\n")
            .toString();
    private static final TelemetryProto.Publisher MEMORY_PUBLISHER_TEN_SEC =
            TelemetryProto.Publisher.newBuilder()
                    .setMemory(
                            TelemetryProto.MemoryPublisher.newBuilder()
                                    .setReadIntervalSec(TEN_SECONDS)
                                    .setMaxSnapshots(2)
                                    .setMaxPendingTasks(10))
                    .build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_TEN_SEC =
            TelemetryProto.Subscriber.newBuilder()
                    .setHandler("handler_fn_1")
                    .setPublisher(MEMORY_PUBLISHER_TEN_SEC)
                    .build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName("myconfig")
                    .setVersion(1)
                    .addSubscribers(SUBSCRIBER_TEN_SEC)
                    .build();

    private final FakeHandlerWrapper mFakeHandlerWrapper =
            new FakeHandlerWrapper(Looper.getMainLooper(), FakeHandlerWrapper.Mode.QUEUEING);
    private final FakePublisherListener mFakePublisherListener = new FakePublisherListener();

    private MemoryPublisher mPublisher;  // subject
    private File mTempFile;

    @Captor
    private ArgumentCaptor<PersistableBundle> mBundleCaptor;
    @Mock
    private DataSubscriber mMockDataSubscriber;
    @Mock
    private ResultStore mMockResultStore;

    @Before
    public void setUp() throws Exception {
        // set up fake /proc/meminfo file
        File tempDir = Files.createTempDirectory("car_telemetry_test").toFile();
        mTempFile = File.createTempFile("fake_meminfo", "", tempDir);
        Files.write(mTempFile.toPath(), FAKE_MEMINFO.getBytes(StandardCharsets.UTF_8));

        // set up mocks
        when(mMockDataSubscriber.getSubscriber()).thenReturn(SUBSCRIBER_TEN_SEC);
        when(mMockDataSubscriber.getMetricsConfig()).thenReturn(METRICS_CONFIG);
        when(mMockDataSubscriber.getPublisherParam()).thenReturn(SUBSCRIBER_TEN_SEC.getPublisher());

        // create MemoryPublisher
        mPublisher = createPublisher(mTempFile.toPath());
    }

    /**
     * Emulates a restart by creating a new MemoryPublisher. StatsManager and PersistableBundle
     * stays the same.
     */
    private MemoryPublisher createPublisher(Path meminfoPath) {
        return new MemoryPublisher(
                mFakePublisherListener,
                mFakeHandlerWrapper.getMockHandler(),
                mMockResultStore,
                meminfoPath);
    }

    @Test
    public void testAddDataSubscriber_pullsMeminfo() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isTrue();
        verify(mMockDataSubscriber).push(mBundleCaptor.capture());
        assertThat(mBundleCaptor.getValue().getString(DATA_BUNDLE_KEY_MEMINFO))
                .isEqualTo(FAKE_MEMINFO);
    }

    @Test
    public void testAddDataSubscriber_whenDataSubscriberAlreadyExists_throwsException() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        assertThrows(IllegalStateException.class,
                () -> mPublisher.addDataSubscriber(mMockDataSubscriber));
    }

    @Test
    public void testAddDataSubscriber_whenConfigHasIllegalFields_throwsException() {
        // read_interval_sec cannot be less than 1
        TelemetryProto.Publisher badReadIntervalPublisher = TelemetryProto.Publisher.newBuilder()
                .setMemory(TelemetryProto.MemoryPublisher.newBuilder().setReadIntervalSec(0))
                .build();
        DataSubscriber mockDataSubscriber1 = mock(DataSubscriber.class);
        when(mockDataSubscriber1.getPublisherParam()).thenReturn(badReadIntervalPublisher);
        // max_pending_tasks cannot be unspecified (or less than 1)
        TelemetryProto.Publisher badThrottleFieldPublisher = TelemetryProto.Publisher.newBuilder()
                .setMemory(TelemetryProto.MemoryPublisher.newBuilder().setReadIntervalSec(1))
                .build();
        DataSubscriber mockDataSubscriber2 = mock(DataSubscriber.class);
        when(mockDataSubscriber2.getPublisherParam()).thenReturn(badThrottleFieldPublisher);

        assertThrows(IllegalArgumentException.class,
                () -> mPublisher.addDataSubscriber(mockDataSubscriber1));
        assertThrows(IllegalArgumentException.class,
                () -> mPublisher.addDataSubscriber(mockDataSubscriber2));
    }

    @Test
    public void testAddDataSubscriber_schedulesNextPullBasedOnReadRate() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        Message message = mFakeHandlerWrapper.getQueuedMessages().get(0);
        assertThatMessageIsScheduledWithGivenDelay(message, TEN_SECONDS * 1000);
    }

    @Test
    public void testAddDataSubscriber_maxSnapshotsReached_removesDataSubscriber() {
        // From MEMORY_PUBLISHER_TEN_SEC, max_snapshots = 2.
        mPublisher.addDataSubscriber(mMockDataSubscriber); // This is the first snapshot

        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1);
        mFakeHandlerWrapper.dispatchQueuedMessages(); // This is the second snapshot
        mFakeHandlerWrapper.dispatchQueuedMessages(); // Remove subscriber, does not publish data

        // verify the MetricsConfig is removed
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(0);
        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isFalse();
        verify(mMockDataSubscriber, times(2)).push(any());
        assertThat(mFakePublisherListener.mFinishedConfig).isEqualTo(METRICS_CONFIG);
    }

    @Test
    public void testAddDataSubscriber_savePublisherState() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        verify(mMockResultStore).putPublisherData(
                eq(MemoryPublisher.class.getSimpleName()), mBundleCaptor.capture());
        // it is equal to 1 because the max_snapshot is set to 2, and it pulled once in
        // addDataSubscriber(), so there is 1 pull remaining
        assertThat(mBundleCaptor.getValue().getInt(BUNDLE_KEY_NUM_SNAPSHOTS_UNTIL_FINISH))
                .isEqualTo(1);
    }

    @Test
    public void testAddDataSubscriber_whenReadMeminfoFailed_shouldNotifyFailure() {
        mPublisher = createPublisher(Paths.get("bad_path"));

        mPublisher.addDataSubscriber(mMockDataSubscriber);

        assertThat(mFakePublisherListener.mFailedConfigs).containsExactly(METRICS_CONFIG);
    }

    @Test
    public void testAddDataSubscriber_invalidConfiguration_throwsException() {
        // read_rate is not allowed to be 0
        TelemetryProto.Publisher badPublisher = MEMORY_PUBLISHER_TEN_SEC.toBuilder().setMemory(
                TelemetryProto.MemoryPublisher.newBuilder().setReadIntervalSec(0)).build();
        TelemetryProto.Subscriber badSubscriber = SUBSCRIBER_TEN_SEC.toBuilder()
                .setPublisher(badPublisher).build();
        DataSubscriber mockSubscriber = mock(DataSubscriber.class);
        when(mockSubscriber.getSubscriber()).thenReturn(badSubscriber);
        when(mockSubscriber.getPublisherParam()).thenReturn(badPublisher);

        assertThrows(IllegalArgumentException.class,
                () -> mPublisher.addDataSubscriber(mockSubscriber));
    }

    @Test
    public void testRemoveDataSubscriber_removesSubscriberAndStopsPullingMeminfo() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        mPublisher.removeDataSubscriber(mMockDataSubscriber);

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isFalse();
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(0);
    }

    @Test
    public void testReadMeminfo_whenPreviousStateExists_shouldContinueFromPrevious() {
        PersistableBundle publisherState = new PersistableBundle();
        publisherState.putInt(BUNDLE_KEY_NUM_SNAPSHOTS_UNTIL_FINISH, 1);
        publisherState.putBoolean(BUNDLE_KEY_COLLECT_INDEFINITELY, false);
        when(mMockResultStore.getPublisherData(any(), anyBoolean())).thenReturn(publisherState);
        mPublisher = createPublisher(mTempFile.toPath());

        // since there is 1 snapshot left, this is the last read and the subscriber will be removed
        mPublisher.addDataSubscriber(mMockDataSubscriber);
        mFakeHandlerWrapper.dispatchQueuedMessages();

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isFalse();
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(0);
        verify(mMockResultStore).removePublisherData(eq(MemoryPublisher.class.getSimpleName()));
        assertThat(mFakePublisherListener.mFinishedConfig).isEqualTo(METRICS_CONFIG);
    }

    @Test
    public void testRemoveDataSubscriber_ifDoesNotMatch_keepsSubscriber() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);
        DataSubscriber differentSubscriber = Mockito.mock(DataSubscriber.class);

        mPublisher.removeDataSubscriber(differentSubscriber);

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isTrue();
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1);
    }

    @Test
    public void testRemoveAllDataSubscriber_removesSubscriberAndStopsPullingMeminfo() {
        mPublisher.addDataSubscriber(mMockDataSubscriber);

        mPublisher.removeAllDataSubscribers();

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isFalse();
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(0);
    }

    @Test
    public void testReadMeminfo_shouldThrottlePublisher() {
        // 100 MemoryPublisher-related tasks pending script execution > the throttle limit
        when(mMockDataSubscriber.push(any())).thenReturn(100);

        mPublisher.addDataSubscriber(mMockDataSubscriber);

        assertThat(mPublisher.hasDataSubscriber(mMockDataSubscriber)).isTrue();
        assertThat(mFakeHandlerWrapper.getQueuedMessages()).hasSize(1);
        Message message = mFakeHandlerWrapper.getQueuedMessages().get(0);
        assertThatMessageIsScheduledWithGivenDelay(message, THROTTLE_MILLIS);
    }

    private static void assertThatMessageIsScheduledWithGivenDelay(Message msg, long delayMillis) {
        long expectedTimeMillis = SystemClock.uptimeMillis() + delayMillis;
        long deltaMillis = 1000;  // +/- 1 seconds is good enough for testing
        assertThat(msg.getWhen()).isIn(Range
                .closed(expectedTimeMillis - deltaMillis, expectedTimeMillis + deltaMillis));
    }
}
