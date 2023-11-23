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

import static com.android.adservices.service.js.JSScriptArgument.arrayArg;
import static com.android.adservices.service.js.JSScriptArgument.numericArg;
import static com.android.adservices.service.js.JSScriptArgument.recordArg;
import static com.android.adservices.service.js.JSScriptArgument.stringArg;
import static com.android.adservices.service.js.JSScriptEngine.JS_SCRIPT_ENGINE_CONNECTION_EXCEPTION_MSG;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.junit.Assume.assumeFalse;
import static org.junit.Assume.assumeTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.util.Log;
import android.webkit.WebView;

import androidx.annotation.NonNull;
import androidx.javascriptengine.IsolateStartupParameters;
import androidx.javascriptengine.JavaScriptIsolate;
import androidx.javascriptengine.JavaScriptSandbox;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.filters.SmallTest;

import com.android.adservices.LogUtil;
import com.android.adservices.service.exception.JSExecutionException;
import com.android.adservices.service.profiling.JSScriptEngineLogConstants;
import com.android.adservices.service.profiling.Profiler;
import com.android.adservices.service.profiling.StopWatch;
import com.android.dx.mockito.inline.extended.ExtendedMockito;
import com.android.modules.utils.build.SdkLevel;

import com.google.common.collect.ImmutableList;
import com.google.common.io.ByteStreams;
import com.google.common.util.concurrent.FluentFuture;
import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;
import com.google.common.util.concurrent.ListeningExecutorService;
import com.google.common.util.concurrent.MoreExecutors;

import org.junit.Assume;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.function.ThrowingRunnable;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.io.IOException;
import java.io.InputStream;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

@SmallTest
public class JSScriptEngineTest {

    /**
     * functions in simple_test_functions.wasm:
     *
     * <p>int increment(int n) { return n+1; }
     *
     * <p>int fib(int n) { if (n<=1) { return n; } else { return fib(n-2) + fib(n-1); } }
     *
     * <p>int fact(int n) { if (n<=1) { return 1; } else { return n * fact(n-1); } }
     *
     * <p>double log_base_2(double n) { return log(n) / log(2.0); }
     */
    public static final String WASM_MODULE = "simple_test_functions.wasm";

    private static final String TAG = JSScriptEngineTest.class.getSimpleName();
    protected static final Context sContext = ApplicationProvider.getApplicationContext();
    private static final Profiler sMockProfiler = mock(Profiler.class);
    private static final StopWatch sSandboxInitWatch = mock(StopWatch.class);
    private static JSScriptEngine sJSScriptEngine;
    private final ExecutorService mExecutorService = Executors.newFixedThreadPool(10);
    private final IsolateSettings mDefaultIsolateSettings =
            IsolateSettings.forMaxHeapSizeEnforcementDisabled();
    @Mock JSScriptEngine.JavaScriptSandboxProvider mMockSandboxProvider;
    @Mock private StopWatch mIsolateCreateWatch;
    @Mock private StopWatch mJavaExecutionWatch;
    @Mock private JavaScriptSandbox mMockedSandbox;
    @Mock private JavaScriptIsolate mMockedIsolate;

    @BeforeClass
    public static void initJavaScriptSandbox() {
        when(sMockProfiler.start(JSScriptEngineLogConstants.SANDBOX_INIT_TIME))
                .thenReturn(sSandboxInitWatch);
        if (JSScriptEngine.AvailabilityChecker.isJSSandboxAvailable()) {
            sJSScriptEngine = JSScriptEngine.getInstanceForTesting(sContext, sMockProfiler);
        }
    }

    @Before
    public void setup() {
        Assume.assumeTrue(JSScriptEngine.AvailabilityChecker.isJSSandboxAvailable());
        MockitoAnnotations.initMocks(this);

        reset(sMockProfiler);
        when(sMockProfiler.start(JSScriptEngineLogConstants.ISOLATE_CREATE_TIME))
                .thenReturn(mIsolateCreateWatch);
        when(sMockProfiler.start(JSScriptEngineLogConstants.JAVA_EXECUTION_TIME))
                .thenReturn(mJavaExecutionWatch);

        FluentFuture<JavaScriptSandbox> futureInstance =
                FluentFuture.from(Futures.immediateFuture(mMockedSandbox));
        when(mMockSandboxProvider.getFutureInstance(sContext)).thenReturn(futureInstance);
    }

    @Test
    public void testProviderFailsIfJSSandboxNotAvailableInWebViewVersion() {
        MockitoSession staticMockSessionLocal = null;

        try {
            staticMockSessionLocal =
                    ExtendedMockito.mockitoSession()
                            .spyStatic(WebView.class)
                            .strictness(Strictness.LENIENT)
                            .initMocks(this)
                            .startMocking();
            ExtendedMockito.doReturn(null).when(WebView::getCurrentWebViewPackage);

            ThrowingRunnable getFutureInstance =
                    () ->
                            new JSScriptEngine.JavaScriptSandboxProvider(sMockProfiler)
                                    .getFutureInstance(sContext);

            assertThrows(JSSandboxIsNotAvailableException.class, getFutureInstance);
        } finally {
            if (staticMockSessionLocal != null) {
                staticMockSessionLocal.finishMocking();
            }
        }
    }

    @Test
    public void testCanRunSimpleScriptWithNoArgs() throws Exception {
        assertThat(
                        callJSEngine(
                                "function test() { return \"hello world\"; }",
                                ImmutableList.of(),
                                "test",
                                mDefaultIsolateSettings))
                .isEqualTo("\"hello world\"");

        verify(sMockProfiler).start(JSScriptEngineLogConstants.ISOLATE_CREATE_TIME);
        verify(sMockProfiler).start(JSScriptEngineLogConstants.JAVA_EXECUTION_TIME);
        verify(sSandboxInitWatch).stop();
        verify(mIsolateCreateWatch).stop();
        verify(mJavaExecutionWatch).stop();
    }

    @Test
    public void testCanRunAScriptWithNoArgs() throws Exception {
        assertThat(
                        callJSEngine(
                                "function helloWorld() { return \"hello world\"; };",
                                ImmutableList.of(),
                                "helloWorld",
                                mDefaultIsolateSettings))
                .isEqualTo("\"hello world\"");
    }

    @Test
    public void testCanRunSimpleScriptWithOneArg() throws Exception {
        assertThat(
                        callJSEngine(
                                "function hello(name) { return \"hello \" + name; };",
                                ImmutableList.of(stringArg("name", "Stefano")),
                                "hello",
                                mDefaultIsolateSettings))
                .isEqualTo("\"hello Stefano\"");
    }

    @Test
    public void testCanRunAScriptWithOneArg() throws Exception {
        assertThat(
                        callJSEngine(
                                "function helloPerson(personName) { return \"hello \" + personName;"
                                        + " };",
                                ImmutableList.of(stringArg("name", "Stefano")),
                                "helloPerson",
                                mDefaultIsolateSettings))
                .isEqualTo("\"hello Stefano\"");
    }

    @Test
    public void testCanUseJSONArguments() throws Exception {
        assertThat(
                        callJSEngine(
                                "function helloPerson(person) {  return \"hello \" + person.name; "
                                        + " };",
                                ImmutableList.of(
                                        recordArg("jsonArg", stringArg("name", "Stefano"))),
                                "helloPerson",
                                mDefaultIsolateSettings))
                .isEqualTo("\"hello Stefano\"");
    }

    @Test
    public void testCanNotReferToScriptArguments() {
        ExecutionException e =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                callJSEngine(
                                        "function helloPerson(person) {  return \"hello \" +"
                                                + " personOuter.name;  };",
                                        ImmutableList.of(
                                                recordArg(
                                                        "personOuter",
                                                        stringArg("name", "Stefano"))),
                                        "helloPerson",
                                        mDefaultIsolateSettings));

        assertThat(e.getCause()).isInstanceOf(JSExecutionException.class);
    }

    // During tests, look for logcat messages with tag "chromium" to check if any of your scripts
    // have syntax errors. Those messages won't be available on prod builds (need to register
    // a listener to WebChromeClient.onConsoleMessage to receive them if needed).
    @Test
    public void testWillReturnAStringWithContentNullEvaluatingScriptWithErrors() {
        ExecutionException e =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                callJSEngine(
                                        "function test() { return \"hello world\"; }",
                                        ImmutableList.of(),
                                        "undefinedFunction",
                                        mDefaultIsolateSettings));

        assertThat(e.getCause()).isInstanceOf(JSExecutionException.class);
    }

    @Test
    public void testParallelCallsToTheScriptEngineDoNotInterfere() throws Exception {
        CountDownLatch resultsLatch = new CountDownLatch(2);

        final ImmutableList<JSScriptArgument> arguments =
                ImmutableList.of(recordArg("jsonArg", stringArg("name", "Stefano")));

        ListenableFuture<String> firstCallResult =
                callJSEngineAsync(
                        "function helloPerson(person) {  return \"hello \" + person.name; " + " };",
                        arguments,
                        "helloPerson",
                        resultsLatch,
                        mDefaultIsolateSettings);

        // The previous call reset the status, we can redefine the function and use the same
        // argument
        ListenableFuture<String> secondCallResult =
                callJSEngineAsync(
                        "function helloPerson(person) {  return \"hello again \" + person.name; "
                                + " };",
                        arguments,
                        "helloPerson",
                        resultsLatch,
                        mDefaultIsolateSettings);

        resultsLatch.await();

        assertThat(firstCallResult.get()).isEqualTo("\"hello Stefano\"");

        assertThat(secondCallResult.get()).isEqualTo("\"hello again Stefano\"");
    }

    @Test
    public void testCanHandleFailuresFromWebView() {
        // The binder can transfer at most 1MB, this is larger than needed since, once
        // converted into a JS array initialization script will be way over the limits.
        List<JSScriptNumericArgument<Integer>> tooBigForBinder =
                Arrays.stream(new int[1024 * 1024])
                        .boxed()
                        .map(value -> numericArg("_", value))
                        .collect(Collectors.toList());

        ExecutionException outerException =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                callJSEngine(
                                        "function helloBigArray(array) {\n"
                                                + " return array.length;\n"
                                                + "}",
                                        ImmutableList.of(arrayArg("array", tooBigForBinder)),
                                        "test",
                                        mDefaultIsolateSettings));
        assertThat(outerException.getCause()).isInstanceOf(JSExecutionException.class);
    }

    @Test
    public void testCanCloseAndThenWorkWithSameInstance() throws Exception {
        assertThat(
                        callJSEngine(
                                "function test() { return \"hello world\"; }",
                                ImmutableList.of(),
                                "test",
                                mDefaultIsolateSettings))
                .isEqualTo("\"hello world\"");

        sJSScriptEngine.shutdown().get(3, TimeUnit.SECONDS);

        when(sMockProfiler.start(JSScriptEngineLogConstants.SANDBOX_INIT_TIME))
                .thenReturn(sSandboxInitWatch);

        assertThat(
                        callJSEngine(
                                "function test() { return \"hello world\"; }",
                                ImmutableList.of(),
                                "test",
                                mDefaultIsolateSettings))
                .isEqualTo("\"hello world\"");

        // Engine is re-initialized
        verify(sMockProfiler, atLeastOnce()).start(JSScriptEngineLogConstants.SANDBOX_INIT_TIME);
        verify(sSandboxInitWatch, atLeastOnce()).stop();
    }

    @Test
    public void testConnectionIsResetIfJSProcessIsTerminated() {
        when(mMockedSandbox.createIsolate())
                .thenThrow(
                        new IllegalStateException(
                                "simulating a failure caused by JavaScriptSandbox being"
                                        + " disconnected"));

        ExecutionException executionException =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                callJSEngine(
                                        JSScriptEngine.createNewInstanceForTesting(
                                                ApplicationProvider.getApplicationContext(),
                                                mMockSandboxProvider,
                                                sMockProfiler),
                                        "function test() { return \"hello world\"; }",
                                        ImmutableList.of(),
                                        "test",
                                        mDefaultIsolateSettings));

        assertThat(executionException.getCause())
                .isInstanceOf(JSScriptEngineConnectionException.class);
        assertThat(executionException)
                .hasMessageThat()
                .contains(JS_SCRIPT_ENGINE_CONNECTION_EXCEPTION_MSG);
        verify(sMockProfiler).start(JSScriptEngineLogConstants.ISOLATE_CREATE_TIME);
    }

    @Test
    public void testEnforceHeapMemorySizeFailureAtCreateIsolate() {
        when(mMockedSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE))
                .thenReturn(true);
        when(mMockedSandbox.createIsolate(Mockito.any(IsolateStartupParameters.class)))
                .thenThrow(
                        new IllegalStateException(
                                "simulating a failure caused by JavaScriptSandbox not"
                                        + " supporting max heap size"));
        IsolateSettings enforcedHeapIsolateSettings =
                IsolateSettings.forMaxHeapSizeEnforcementEnabled(1000);

        ExecutionException executionException =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                callJSEngine(
                                        JSScriptEngine.createNewInstanceForTesting(
                                                ApplicationProvider.getApplicationContext(),
                                                mMockSandboxProvider,
                                                sMockProfiler),
                                        "function test() { return \"hello world\"; }",
                                        ImmutableList.of(),
                                        "test",
                                        enforcedHeapIsolateSettings));

        assertThat(executionException.getCause())
                .isInstanceOf(JSScriptEngineConnectionException.class);
        assertThat(executionException)
                .hasMessageThat()
                .contains(JS_SCRIPT_ENGINE_CONNECTION_EXCEPTION_MSG);
        verify(sMockProfiler).start(JSScriptEngineLogConstants.ISOLATE_CREATE_TIME);
        verify(mMockedSandbox)
                .isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE);
    }

    @Test
    public void testEnforceHeapMemorySizeUnsupportedBySandbox() {
        when(mMockedSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE))
                .thenReturn(false);
        IsolateSettings enforcedHeapIsolateSettings =
                IsolateSettings.forMaxHeapSizeEnforcementEnabled(1000);

        ExecutionException executionException =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                callJSEngine(
                                        JSScriptEngine.createNewInstanceForTesting(
                                                ApplicationProvider.getApplicationContext(),
                                                mMockSandboxProvider,
                                                sMockProfiler),
                                        "function test() { return \"hello world\"; }",
                                        ImmutableList.of(),
                                        "test",
                                        enforcedHeapIsolateSettings));
        assertThat(executionException.getCause())
                .isInstanceOf(JSScriptEngineConnectionException.class);
        assertThat(executionException)
                .hasMessageThat()
                .contains(JS_SCRIPT_ENGINE_CONNECTION_EXCEPTION_MSG);
        verify(sMockProfiler).start(JSScriptEngineLogConstants.ISOLATE_CREATE_TIME);
    }

    @Test
    public void testLenientHeapMemorySize() throws Exception {
        // This exception though wired to be thrown will not be thrown
        when(mMockedSandbox.createIsolate(Mockito.any(IsolateStartupParameters.class)))
                .thenThrow(
                        new IllegalStateException(
                                "simulating a failure caused by JavaScriptSandbox not"
                                        + " supporting max heap size"));
        IsolateSettings lenientHeapIsolateSettings =
                IsolateSettings.forMaxHeapSizeEnforcementDisabled();

        assertThat(
                        callJSEngine(
                                "function test() { return \"hello world\"; }",
                                ImmutableList.of(),
                                "test",
                                lenientHeapIsolateSettings))
                .isEqualTo("\"hello world\"");
    }

    @Test
    public void testSuccessAtCreateIsolateUnboundedMaxHeapMemory() throws Exception {
        when(mMockedSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE))
                .thenReturn(true);
        IsolateSettings enforcedHeapIsolateSettings =
                IsolateSettings.forMaxHeapSizeEnforcementEnabled(0);
        when(mMockedSandbox.createIsolate()).thenReturn(mMockedIsolate);

        when(mMockedIsolate.evaluateJavaScriptAsync(anyString()))
                .thenReturn(Futures.immediateFuture("\"hello world\""));

        assertThat(
                        callJSEngine(
                                JSScriptEngine.createNewInstanceForTesting(
                                        ApplicationProvider.getApplicationContext(),
                                        mMockSandboxProvider,
                                        sMockProfiler),
                                "function test() { return \"hello world\"; }",
                                ImmutableList.of(),
                                "test",
                                enforcedHeapIsolateSettings))
                .isEqualTo("\"hello world\"");

        verify(sMockProfiler).start(JSScriptEngineLogConstants.ISOLATE_CREATE_TIME);
        verify(mMockedSandbox)
                .isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE);
    }

    @Test
    public void testSuccessAtCreateIsolateBoundedMaxHeapMemory() throws Exception {
        when(mMockedSandbox.isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE))
                .thenReturn(true);
        IsolateSettings enforcedHeapIsolateSettings =
                IsolateSettings.forMaxHeapSizeEnforcementEnabled(1000);
        when(mMockedSandbox.createIsolate(Mockito.any(IsolateStartupParameters.class)))
                .thenReturn(mMockedIsolate);

        when(mMockedIsolate.evaluateJavaScriptAsync(anyString()))
                .thenReturn(Futures.immediateFuture("\"hello world\""));

        assertThat(
                        callJSEngine(
                                JSScriptEngine.createNewInstanceForTesting(
                                        ApplicationProvider.getApplicationContext(),
                                        mMockSandboxProvider,
                                        sMockProfiler),
                                "function test() { return \"hello world\"; }",
                                ImmutableList.of(),
                                "test",
                                enforcedHeapIsolateSettings))
                .isEqualTo("\"hello world\"");

        verify(sMockProfiler).start(JSScriptEngineLogConstants.ISOLATE_CREATE_TIME);
        verify(mMockedSandbox)
                .isFeatureSupported(JavaScriptSandbox.JS_FEATURE_ISOLATE_MAX_HEAP_SIZE);
    }

    // Troubles between google-java-format and checkstyle
    // CHECKSTYLE:OFF IndentationCheck
    @Test
    public void testIsolateIsClosedWhenEvaluationCompletes() throws Exception {
        when(mMockedSandbox.createIsolate()).thenReturn(mMockedIsolate);
        when(mMockedIsolate.evaluateJavaScriptAsync(anyString()))
                .thenReturn(Futures.immediateFuture("hello world"));

        AtomicBoolean isolateHasBeenClosed = new AtomicBoolean(false);
        CountDownLatch isolateIsClosedLatch = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            isolateHasBeenClosed.set(true);
                            isolateIsClosedLatch.countDown();
                            return null;
                        })
                .when(mMockedIsolate)
                .close();

        callJSEngine(
                JSScriptEngine.createNewInstanceForTesting(
                        ApplicationProvider.getApplicationContext(),
                        mMockSandboxProvider,
                        sMockProfiler),
                "function test() { return \"hello world\"; }",
                ImmutableList.of(),
                "test",
                mDefaultIsolateSettings);

        isolateIsClosedLatch.await(1, TimeUnit.SECONDS);
        // Using Mockito.verify made the test unstable (mockito call registration was in a
        // race condition with the verify call)
        assertTrue(isolateHasBeenClosed.get());
    }

    @Test
    public void testIsolateIsClosedWhenEvaluationFails() throws Exception {
        when(mMockedSandbox.createIsolate()).thenReturn(mMockedIsolate);
        when(mMockedIsolate.evaluateJavaScriptAsync(anyString()))
                .thenReturn(
                        Futures.immediateFailedFuture(new RuntimeException("JS execution failed")));

        AtomicBoolean isolateHasBeenClosed = new AtomicBoolean(false);
        CountDownLatch isolateIsClosedLatch = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            isolateHasBeenClosed.set(true);
                            isolateIsClosedLatch.countDown();
                            return null;
                        })
                .when(mMockedIsolate)
                .close();

        assertThrows(
                ExecutionException.class,
                () ->
                        callJSEngine(
                                JSScriptEngine.createNewInstanceForTesting(
                                        ApplicationProvider.getApplicationContext(),
                                        mMockSandboxProvider,
                                        sMockProfiler),
                                "function test() { return \"hello world\"; }",
                                ImmutableList.of(),
                                "test",
                                mDefaultIsolateSettings));

        isolateIsClosedLatch.await(1, TimeUnit.SECONDS);
        // Using Mockito.verify made the test unstable (mockito call registration was in a
        // race condition with the verify call)
        assertTrue(isolateHasBeenClosed.get());
    }

    // TODO(240857630) Solve flakiness in this test
    @Ignore
    @Test
    public void testIsolateIsClosedWhenEvaluationIsCancelled() throws Exception {
        when(mMockedSandbox.createIsolate()).thenReturn(mMockedIsolate);

        CountDownLatch jsEvaluationStartedLatch = new CountDownLatch(1);
        CountDownLatch completeJsEvaluationLatch = new CountDownLatch(1);
        ListeningExecutorService callbackExecutor =
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        when(mMockedIsolate.evaluateJavaScriptAsync(anyString()))
                .thenReturn(
                        callbackExecutor.submit(
                                () -> {
                                    jsEvaluationStartedLatch.countDown();
                                    LogUtil.i("Waiting before reporting JS completion");
                                    try {
                                        completeJsEvaluationLatch.await();
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                    }
                                    LogUtil.i("Reporting JS completion");
                                    return "hello world";
                                }));
        AtomicBoolean isolateHasBeenClosed = new AtomicBoolean(false);
        CountDownLatch isolateIsClosedLatch = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            isolateHasBeenClosed.set(true);
                            isolateIsClosedLatch.countDown();
                            return null;
                        })
                .when(mMockedIsolate)
                .close();

        JSScriptEngine engine =
                JSScriptEngine.createNewInstanceForTesting(
                        ApplicationProvider.getApplicationContext(),
                        mMockSandboxProvider,
                        sMockProfiler);
        ListenableFuture<String> jsExecutionFuture =
                engine.evaluate(
                        "function test() { return \"hello world\"; }",
                        ImmutableList.of(),
                        "test",
                        mDefaultIsolateSettings);

        // cancelling only after the processing started and the sandbox has been created
        jsEvaluationStartedLatch.await(1, TimeUnit.SECONDS);
        LogUtil.i("Cancelling JS future");
        jsExecutionFuture.cancel(true);
        LogUtil.i("Waiting for isolate to close");
        isolateIsClosedLatch.await(1, TimeUnit.SECONDS);
        LogUtil.i("Checking");
        // Using Mockito.verify made the test unstable (mockito call registration was in a
        // race condition with the verify call)
        assertTrue(isolateHasBeenClosed.get());
    }

    @Test
    public void testIsolateIsClosedWhenEvaluationTimesOut() throws Exception {
        when(mMockedSandbox.createIsolate()).thenReturn(mMockedIsolate);
        CountDownLatch completeJsEvaluationLatch = new CountDownLatch(1);
        CountDownLatch jsEvaluationStartedLatch = new CountDownLatch(1);
        ListeningExecutorService callbackExecutor =
                MoreExecutors.listeningDecorator(Executors.newSingleThreadExecutor());
        when(mMockedIsolate.evaluateJavaScriptAsync(anyString()))
                .thenReturn(
                        callbackExecutor.submit(
                                () -> {
                                    jsEvaluationStartedLatch.countDown();
                                    LogUtil.i("Waiting before reporting JS completion");
                                    try {
                                        completeJsEvaluationLatch.await();
                                    } catch (InterruptedException ignored) {
                                        Thread.currentThread().interrupt();
                                    }
                                    LogUtil.i("Reporting JS completion");
                                    return "hello world";
                                }));

        AtomicBoolean isolateHasBeenClosed = new AtomicBoolean(false);
        CountDownLatch isolateIsClosedLatch = new CountDownLatch(1);
        doAnswer(
                        invocation -> {
                            isolateHasBeenClosed.set(true);
                            LogUtil.i("Mock isolate has been closed");
                            isolateIsClosedLatch.countDown();
                            return null;
                        })
                .when(mMockedIsolate)
                .close();

        JSScriptEngine engine =
                JSScriptEngine.createNewInstanceForTesting(
                        ApplicationProvider.getApplicationContext(),
                        mMockSandboxProvider,
                        sMockProfiler);
        ExecutionException timeoutException =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                FluentFuture.from(
                                                engine.evaluate(
                                                        "function test() { return \"hello world\";"
                                                                + " }",
                                                        ImmutableList.of(),
                                                        "test",
                                                        mDefaultIsolateSettings))
                                        .withTimeout(
                                                500,
                                                TimeUnit.MILLISECONDS,
                                                new ScheduledThreadPoolExecutor(1))
                                        .get());

        // cancelling only after the processing started and the sandbox has been created
        jsEvaluationStartedLatch.await(1, TimeUnit.SECONDS);
        isolateIsClosedLatch.await(1, TimeUnit.SECONDS);
        // Using Mockito.verify made the test unstable (mockito call registration was in a
        // race condition with the verify call)
        assertTrue(isolateHasBeenClosed.get());
        assertThat(timeoutException.getCause()).isInstanceOf(TimeoutException.class);
    }
    // CHECKSTYLE:ON IndentationCheck

    @Test
    public void testThrowsExceptionAndRecreateSandboxIfIsolateCreationFails() throws Exception {
        doThrow(new RuntimeException("Simulating isolate creation failure"))
                .when(mMockedSandbox)
                .createIsolate();

        JSScriptEngine engine =
                JSScriptEngine.createNewInstanceForTesting(
                        ApplicationProvider.getApplicationContext(),
                        mMockSandboxProvider,
                        sMockProfiler);

        assertThrows(
                ExecutionException.class,
                () ->
                        callJSEngine(
                                engine,
                                "function test() { return \"hello world\";" + " }",
                                ImmutableList.of(),
                                "test",
                                mDefaultIsolateSettings));
        verify(mMockSandboxProvider).destroyCurrentInstance();
    }

    @Test
    public void testCanUseWasmModuleInScript() throws Exception {
        assumeTrue(sJSScriptEngine.isWasmSupported().get(4, TimeUnit.SECONDS));

        String jsUsingWasmModule =
                "\"use strict\";\n"
                        + "\n"
                        + "function callWasm(input, wasmModule) {\n"
                        + "  const instance = new WebAssembly.Instance(wasmModule);\n"
                        + "\n"
                        + "  return instance.exports._fact(input);\n"
                        + "\n"
                        + "}";

        String result =
                callJSEngine(
                        jsUsingWasmModule,
                        readBinaryAsset(WASM_MODULE),
                        ImmutableList.of(numericArg("input", 3)),
                        "callWasm",
                        mDefaultIsolateSettings);

        assertThat(result).isEqualTo("6");
    }

    @Test
    public void testCanNotUseWasmModuleInScriptIfWebViewDoesNotSupportWasm() throws Exception {
        assumeFalse(sJSScriptEngine.isWasmSupported().get(4, TimeUnit.SECONDS));

        String jsUsingWasmModule =
                "\"use strict\";\n"
                        + "\n"
                        + "function callWasm(input, wasmModule) {\n"
                        + "  const instance = new WebAssembly.Instance(wasmModule);\n"
                        + "\n"
                        + "  return instance.exports._fact(input);\n"
                        + "\n"
                        + "}";

        ExecutionException outer =
                assertThrows(
                        ExecutionException.class,
                        () ->
                                callJSEngine(
                                        jsUsingWasmModule,
                                        readBinaryAsset(WASM_MODULE),
                                        ImmutableList.of(numericArg("input", 3)),
                                        "callWasm",
                                        mDefaultIsolateSettings));

        assertThat(outer.getCause()).isInstanceOf(IllegalStateException.class);
    }

    private String callJSEngine(
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull IsolateSettings isolateSettings)
            throws Exception {
        return callJSEngine(sJSScriptEngine, jsScript, args, functionName, isolateSettings);
    }

    private String callJSEngine(
            @NonNull String jsScript,
            @NonNull byte[] wasmBytes,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull IsolateSettings isolateSettings)
            throws Exception {
        return callJSEngine(
                sJSScriptEngine, jsScript, wasmBytes, args, functionName, isolateSettings);
    }

    private String callJSEngine(
            @NonNull JSScriptEngine jsScriptEngine,
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull IsolateSettings isolateSettings)
            throws Exception {
        CountDownLatch resultLatch = new CountDownLatch(1);
        ListenableFuture<String> futureResult =
                callJSEngineAsync(
                        jsScriptEngine, jsScript, args, functionName, resultLatch, isolateSettings);
        resultLatch.await();
        return futureResult.get();
    }

    private String callJSEngine(
            @NonNull JSScriptEngine jsScriptEngine,
            @NonNull String jsScript,
            @NonNull byte[] wasmBytes,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull IsolateSettings isolateSettings)
            throws Exception {
        CountDownLatch resultLatch = new CountDownLatch(1);
        ListenableFuture<String> futureResult =
                callJSEngineAsync(
                        jsScriptEngine,
                        jsScript,
                        wasmBytes,
                        args,
                        functionName,
                        resultLatch,
                        isolateSettings);
        resultLatch.await();
        return futureResult.get();
    }

    private ListenableFuture<String> callJSEngineAsync(
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull CountDownLatch resultLatch,
            @NonNull IsolateSettings isolateSettings) {
        return callJSEngineAsync(
                sJSScriptEngine, jsScript, args, functionName, resultLatch, isolateSettings);
    }

    private ListenableFuture<String> callJSEngineAsync(
            @NonNull JSScriptEngine engine,
            @NonNull String jsScript,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull CountDownLatch resultLatch,
            @NonNull IsolateSettings isolateSettings) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(resultLatch);
        Log.i(TAG, "Calling WebVew");
        ListenableFuture<String> result =
                engine.evaluate(jsScript, args, functionName, isolateSettings);
        result.addListener(resultLatch::countDown, mExecutorService);
        return result;
    }

    private ListenableFuture<String> callJSEngineAsync(
            @NonNull JSScriptEngine engine,
            @NonNull String jsScript,
            @NonNull byte[] wasmBytes,
            @NonNull List<JSScriptArgument> args,
            @NonNull String functionName,
            @NonNull CountDownLatch resultLatch,
            @NonNull IsolateSettings isolateSettings) {
        Objects.requireNonNull(engine);
        Objects.requireNonNull(resultLatch);
        Log.i(TAG, "Calling WebVew");
        ListenableFuture<String> result =
                engine.evaluate(jsScript, wasmBytes, args, functionName, isolateSettings);
        result.addListener(resultLatch::countDown, mExecutorService);
        return result;
    }

    private byte[] readBinaryAsset(@NonNull String assetName) throws IOException {
        InputStream inputStream = sContext.getAssets().open(assetName);
        return SdkLevel.isAtLeastT()
                ? inputStream.readAllBytes()
                : ByteStreams.toByteArray(inputStream);
    }
}
