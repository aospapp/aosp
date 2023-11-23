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

package com.android.adservices.service.js;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.junit.Assert.assertThrows;

import android.content.Context;

import androidx.javascriptengine.JavaScriptSandbox;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.adservices.service.profiling.JSScriptEngineLogConstants;
import com.android.adservices.service.profiling.Profiler;
import com.android.adservices.service.profiling.StopWatch;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.dx.mockito.inline.extended.StaticMockitoSession;

import com.google.common.util.concurrent.Futures;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.mockito.Mock;
import org.mockito.Mockito;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@SmallTest
public class JavaScriptSandboxProviderTest {
    private final Context mApplicationContext = ApplicationProvider.getApplicationContext();
    private StaticMockitoSession mStaticMockSession;
    @Mock private StopWatch mSandboxInitWatch;
    @Mock private JavaScriptSandbox mSandbox;
    @Mock private Profiler mProfilerMock;

    private JSScriptEngine.JavaScriptSandboxProvider mJsSandboxProvider;

    @Before
    public void setUp() {
        mStaticMockSession =
                ExtendedMockito.mockitoSession()
                        .mockStatic(JavaScriptSandbox.class)
                        .initMocks(this)
                        .startMocking();
    }

    @After
    public void shutDown() {
        mStaticMockSession.finishMocking();
    }

    @Test
    public void testJsSandboxProviderCreateFails() {
        when(JavaScriptSandbox.isSupported()).thenReturn(false);
        mJsSandboxProvider = new JSScriptEngine.JavaScriptSandboxProvider(mProfilerMock);
        ThrowingRunnable getFutureInstance =
                () -> mJsSandboxProvider.getFutureInstance(mApplicationContext);
        assertThrows(JSSandboxIsNotAvailableException.class, getFutureInstance);
        verify(JavaScriptSandbox::isSupported);
    }

    @Test
    public void testJsSandboxProviderCreatesOnlyOneInstance()
            throws ExecutionException, InterruptedException, TimeoutException {
        when(JavaScriptSandbox.isSupported()).thenReturn(true);
        doReturn(Futures.immediateFuture(mSandbox))
                .when(
                        () -> {
                            return JavaScriptSandbox.createConnectedInstanceAsync(
                                    mApplicationContext);
                        });

        when(mProfilerMock.start(JSScriptEngineLogConstants.SANDBOX_INIT_TIME))
                .thenReturn(mSandboxInitWatch);
        mJsSandboxProvider = new JSScriptEngine.JavaScriptSandboxProvider(mProfilerMock);

        mJsSandboxProvider.getFutureInstance(mApplicationContext).get(5, TimeUnit.SECONDS);
        mJsSandboxProvider.getFutureInstance(mApplicationContext).get(5, TimeUnit.SECONDS);

        verify(() -> JavaScriptSandbox.createConnectedInstanceAsync(mApplicationContext));
        verify(mProfilerMock).start(JSScriptEngineLogConstants.SANDBOX_INIT_TIME);
    }

    @Test
    public void testJsSandboxProviderCreatesNewInstanceAfterFirstIsDestroyed()
            throws ExecutionException, InterruptedException, TimeoutException {
        when(JavaScriptSandbox.isSupported()).thenReturn(true);
        doReturn(Futures.immediateFuture(mSandbox))
                .when(
                        () -> {
                            return JavaScriptSandbox.createConnectedInstanceAsync(
                                    mApplicationContext);
                        });

        when(mProfilerMock.start(JSScriptEngineLogConstants.SANDBOX_INIT_TIME))
                .thenReturn(mSandboxInitWatch);
        mJsSandboxProvider = new JSScriptEngine.JavaScriptSandboxProvider(mProfilerMock);
        mJsSandboxProvider.getFutureInstance(mApplicationContext).get(5, TimeUnit.SECONDS);

        // Waiting for the first instance closure
        mJsSandboxProvider.destroyCurrentInstance().get(4, TimeUnit.SECONDS);

        mJsSandboxProvider.getFutureInstance(mApplicationContext).get(5, TimeUnit.SECONDS);

        verify(
                () -> JavaScriptSandbox.createConnectedInstanceAsync(mApplicationContext),
                Mockito.times(2));

        verify(mProfilerMock, Mockito.times(2)).start(JSScriptEngineLogConstants.SANDBOX_INIT_TIME);
    }
}
