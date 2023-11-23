/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.telemetry;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.telemetry.CarTelemetryManager;
import android.car.telemetry.ICarTelemetryServiceListener;
import android.car.telemetry.MetricsConfigKey;
import android.content.Context;
import android.os.Handler;
import android.os.PersistableBundle;

import androidx.test.filters.SmallTest;

import com.android.car.CarLocalServices;
import com.android.car.CarPropertyService;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.systeminterface.SystemStateInterface;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoJUnitRunner;
import org.mockito.junit.MockitoRule;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.file.Files;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(MockitoJUnitRunner.class)
@SmallTest
public class CarTelemetryServiceTest {
    private static final long TIMEOUT_MS = 15_000L;
    private static final String METRICS_CONFIG_NAME = "my_metrics_config";
    private static final MetricsConfigKey KEY_V1 = new MetricsConfigKey(METRICS_CONFIG_NAME, 1);
    private static final MetricsConfigKey KEY_V2 = new MetricsConfigKey(METRICS_CONFIG_NAME, 2);
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_V1 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName(METRICS_CONFIG_NAME).setVersion(1).setScript("no-op").build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_V2 =
            TelemetryProto.MetricsConfig.newBuilder()
                    .setName(METRICS_CONFIG_NAME).setVersion(2).setScript("no-op").build();

    private CountDownLatch mIdleHandlerLatch = new CountDownLatch(1);
    private CarTelemetryService mService;
    private File mTempSystemCarDir;
    private Handler mTelemetryHandler;
    private MetricsConfigStore mMetricsConfigStore;
    private ResultStore mResultStore;

    @Rule
    public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock
    private CarPropertyService mMockCarPropertyService;
    @Mock
    private Context mContext;
    @Mock
    private ICarTelemetryServiceListener mMockListener;
    @Mock
    private SystemInterface mMockSystemInterface;
    @Mock
    private SystemStateInterface mMockSystemStateInterface;

    @Before
    public void setUp() throws Exception {
        CarLocalServices.removeServiceForTest(SystemInterface.class);
        CarLocalServices.addService(SystemInterface.class, mMockSystemInterface);

        mTempSystemCarDir = Files.createTempDirectory("telemetry_test").toFile();
        when(mMockSystemInterface.getSystemCarDir()).thenReturn(mTempSystemCarDir);
        when(mMockSystemInterface.getSystemStateInterface()).thenReturn(mMockSystemStateInterface);

        mService = new CarTelemetryService(mContext, mMockCarPropertyService);
        mService.init();
        mService.setListener(mMockListener);

        mTelemetryHandler = mService.getTelemetryHandler();
        mTelemetryHandler.getLooper().getQueue().addIdleHandler(() -> {
            mIdleHandlerLatch.countDown();
            return true;
        });
        waitForHandlerThreadToFinish();

        mMetricsConfigStore = mService.getMetricsConfigStore();
        mResultStore = mService.getResultStore();
    }

    @Test
    public void testAddMetricsConfig_newMetricsConfig_shouldSucceed() throws Exception {
        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_NONE));
    }

    @Test
    public void testAddMetricsConfig_duplicateMetricsConfig_shouldFail() throws Exception {
        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());
        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_NONE));

        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_ALREADY_EXISTS));
    }

    @Test
    public void testAddMetricsConfig_invalidMetricsConfig_shouldFail() throws Exception {
        mService.addMetricsConfig(KEY_V1, "bad config".getBytes());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_PARSE_FAILED));
    }

    @Test
    public void testAddMetricsConfig_olderMetricsConfig_shouldFail() throws Exception {
        mService.addMetricsConfig(KEY_V2, METRICS_CONFIG_V2.toByteArray());
        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V2), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_NONE));

        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V1), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_VERSION_TOO_OLD));
    }

    @Test
    public void testAddMetricsConfig_newerMetricsConfig_shouldReplaceAndDeleteOldResult()
            throws Exception {
        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());
        mResultStore.putInterimResult(KEY_V1.getName(), new PersistableBundle());

        mService.addMetricsConfig(KEY_V2, METRICS_CONFIG_V2.toByteArray());

        waitForHandlerThreadToFinish();
        verify(mMockListener).onAddMetricsConfigStatus(
                eq(KEY_V2), eq(CarTelemetryManager.ERROR_METRICS_CONFIG_NONE));
        assertThat(mMetricsConfigStore.getActiveMetricsConfigs())
                .containsExactly(METRICS_CONFIG_V2);
        assertThat(mResultStore.getInterimResult(KEY_V1.getName())).isNull();
    }

    @Test
    public void testRemoveMetricsConfig_shouldDeleteConfigAndResult() throws Exception {
        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());
        mResultStore.putInterimResult(KEY_V1.getName(), new PersistableBundle());

        mService.removeMetricsConfig(KEY_V1);

        waitForHandlerThreadToFinish();
        assertThat(mMetricsConfigStore.getActiveMetricsConfigs()).isEmpty();
        assertThat(mResultStore.getInterimResult(KEY_V1.getName())).isNull();
    }

    @Test
    public void testRemoveAllMetricsConfigs_shouldRemoveConfigsAndResults() throws Exception {
        MetricsConfigKey key = new MetricsConfigKey("test config", 2);
        TelemetryProto.MetricsConfig config =
                TelemetryProto.MetricsConfig.newBuilder().setName(key.getName()).build();
        mService.addMetricsConfig(key, config.toByteArray());
        mService.addMetricsConfig(KEY_V1, METRICS_CONFIG_V1.toByteArray());
        mResultStore.putInterimResult(KEY_V1.getName(), new PersistableBundle());
        mResultStore.putFinalResult(key.getName(), new PersistableBundle());

        mService.removeAllMetricsConfigs();

        waitForHandlerThreadToFinish();
        assertThat(mMetricsConfigStore.getActiveMetricsConfigs()).isEmpty();
        assertThat(mResultStore.getInterimResult(KEY_V1.getName())).isNull();
        assertThat(mResultStore.getFinalResult(key.getName(), /* deleteResult = */ false)).isNull();
    }

    @Test
    public void testSendFinishedReports_whenNoReport_shouldNotReceiveResponse() throws Exception {
        mService.sendFinishedReports(KEY_V1);

        waitForHandlerThreadToFinish();
        verify(mMockListener, never()).onResult(any(), any());
        verify(mMockListener, never()).onError(any(), any());
    }

    @Test
    public void testSendFinishedReports_whenFinalResult_shouldReceiveResult() throws Exception {
        PersistableBundle finalResult = new PersistableBundle();
        finalResult.putBoolean("finished", true);
        mResultStore.putFinalResult(KEY_V1.getName(), finalResult);

        mService.sendFinishedReports(KEY_V1);

        waitForHandlerThreadToFinish();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        finalResult.writeToStream(bos);
        verify(mMockListener).onResult(eq(KEY_V1), eq(bos.toByteArray()));
        // result should have been deleted
        assertThat(mResultStore.getFinalResult(KEY_V1.getName(), false)).isNull();
    }

    @Test
    public void testSendFinishedReports_whenError_shouldReceiveError() throws Exception {
        TelemetryProto.TelemetryError error = TelemetryProto.TelemetryError.newBuilder()
                .setErrorType(TelemetryProto.TelemetryError.ErrorType.LUA_RUNTIME_ERROR)
                .setMessage("test error")
                .build();
        mResultStore.putError(KEY_V1.getName(), error);

        mService.sendFinishedReports(KEY_V1);

        waitForHandlerThreadToFinish();
        verify(mMockListener).onError(eq(KEY_V1), eq(error.toByteArray()));
        // error should have been deleted
        assertThat(mResultStore.getError(KEY_V1.getName(), false)).isNull();
    }

    @Test
    public void testSendFinishedReports_whenListenerNotSet_shouldDoNothing() throws Exception {
        PersistableBundle finalResult = new PersistableBundle();
        finalResult.putBoolean("finished", true);
        mResultStore.putFinalResult(KEY_V1.getName(), finalResult);
        mService.clearListener(); // no listener = no way to send back results

        mService.sendFinishedReports(KEY_V1);

        waitForHandlerThreadToFinish();
        // if listener is null, nothing should be done, result should still be in result store
        assertThat(mResultStore.getFinalResult(KEY_V1.getName(), false).toString())
                .isEqualTo(finalResult.toString());
    }

    private void waitForHandlerThreadToFinish() throws Exception {
        assertWithMessage("handler not idle in %sms", TIMEOUT_MS)
                .that(mIdleHandlerLatch.await(TIMEOUT_MS, TimeUnit.MILLISECONDS)).isTrue();
        mIdleHandlerLatch = new CountDownLatch(1); // reset idle handler condition
        mTelemetryHandler.runWithScissors(() -> { }, TIMEOUT_MS);
    }
}
