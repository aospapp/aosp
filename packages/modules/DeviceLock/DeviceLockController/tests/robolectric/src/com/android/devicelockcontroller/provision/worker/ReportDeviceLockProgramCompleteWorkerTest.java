/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.devicelockcontroller.provision.worker;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.Mockito.when;

import android.content.Context;

import androidx.annotation.NonNull;
import androidx.test.core.app.ApplicationProvider;
import androidx.work.ListenableWorker;
import androidx.work.ListenableWorker.Result;
import androidx.work.WorkerFactory;
import androidx.work.WorkerParameters;
import androidx.work.testing.TestWorkerBuilder;

import com.android.devicelockcontroller.provision.grpc.DeviceFinalizeClient;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.robolectric.RobolectricTestRunner;

import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

import io.grpc.Status;

@RunWith(RobolectricTestRunner.class)
public final class ReportDeviceLockProgramCompleteWorkerTest {
    @Rule
    public final MockitoRule mMocks = MockitoJUnit.rule();
    @Mock
    private DeviceFinalizeClient mClient;
    private ReportDeviceLockProgramCompleteWorker mWorker;

    @Before
    public void setUp() throws Exception {
        final Context context = ApplicationProvider.getApplicationContext();
        final Executor executor = Executors.newSingleThreadExecutor();
        mWorker = TestWorkerBuilder.from(
                        context, ReportDeviceLockProgramCompleteWorker.class, executor)
                .setWorkerFactory(
                        new WorkerFactory() {
                            @Override
                            public ListenableWorker createWorker(
                                    @NonNull Context context, @NonNull String workerClassName,
                                    @NonNull WorkerParameters workerParameters) {
                                return workerClassName.equals(
                                        ReportDeviceLockProgramCompleteWorker.class.getName())
                                        ? new ReportDeviceLockProgramCompleteWorker(context,
                                        workerParameters, mClient)
                                        : null;
                            }
                        }).build();
    }

    @Test
    public void doWork_responseIsSuccessful_returnSuccess() {
        when(mClient.reportDeviceProgramComplete()).thenReturn(
                new DeviceFinalizeClient.ReportDeviceProgramCompleteResponse());

        assertThat(mWorker.doWork()).isEqualTo(Result.success());
    }

    @Test
    public void doWork_responseHasRecoverableError_returnRetry() {
        when(mClient.reportDeviceProgramComplete()).thenReturn(
                new DeviceFinalizeClient.ReportDeviceProgramCompleteResponse(Status.UNAVAILABLE));

        assertThat(mWorker.doWork()).isEqualTo(Result.retry());
    }

    @Test
    public void doWork_responseHasFatalError_returnFailure() {
        when(mClient.reportDeviceProgramComplete()).thenReturn(
                new DeviceFinalizeClient.ReportDeviceProgramCompleteResponse(Status.UNKNOWN));

        assertThat(mWorker.doWork()).isEqualTo(Result.failure());
    }
}
