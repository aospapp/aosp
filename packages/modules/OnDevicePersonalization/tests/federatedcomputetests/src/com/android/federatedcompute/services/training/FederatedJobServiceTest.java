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

package com.android.federatedcompute.services.training;

import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.app.job.JobParameters;

import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.federatedcompute.services.common.FederatedComputeExecutors;

import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@RunWith(JUnit4.class)
public final class FederatedJobServiceTest {
    private static final long WAIT_IN_MILLIS = 1_000L;

    private FederatedJobService mSpyService;

    @Before
    public void setUp() {
        mSpyService = spy(new FederatedJobService());
    }

    @Test
    public void testOnStartJob() throws Exception {
        MockitoSession session =
                ExtendedMockito.mockitoSession()
                        .spyStatic(FederatedComputeExecutors.class)
                        .strictness(Strictness.LENIENT)
                        .startMocking();
        try {
            ExtendedMockito.doReturn(MoreExecutors.newDirectExecutorService())
                    .when(FederatedComputeExecutors::getBackgroundExecutor);

            boolean result = mSpyService.onStartJob(mock(JobParameters.class));

            assertTrue(result);
            Thread.sleep(WAIT_IN_MILLIS);

            verify(mSpyService, times(1)).jobFinished(any(), anyBoolean());
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testOnStopJob() {
        MockitoSession session =
                ExtendedMockito.mockitoSession().strictness(Strictness.LENIENT).startMocking();
        try {
            assertTrue(mSpyService.onStopJob(mock(JobParameters.class)));
        } finally {
            session.finishMocking();
        }
    }
}
