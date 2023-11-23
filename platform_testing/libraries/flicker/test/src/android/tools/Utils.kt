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

package android.tools

import android.app.Instrumentation
import android.content.Context
import android.tools.common.CrossPlatform
import android.tools.common.IScenario
import android.tools.common.ScenarioBuilder
import android.tools.common.io.IReader
import android.tools.common.io.ResultArtifactDescriptor
import android.tools.common.io.RunStatus
import android.tools.common.io.WINSCOPE_EXT
import android.tools.common.parsers.events.EventLogParser
import android.tools.device.flicker.datastore.CachedResultWriter
import android.tools.device.flicker.legacy.AbstractFlickerTestData
import android.tools.device.flicker.legacy.FlickerBuilder
import android.tools.device.flicker.legacy.IFlickerTestData
import android.tools.device.traces.TRACE_CONFIG_REQUIRE_CHANGES
import android.tools.device.traces.io.ArtifactBuilder
import android.tools.device.traces.io.InMemoryArtifact
import android.tools.device.traces.io.ParsedTracesReader
import android.tools.device.traces.io.ResultReader
import android.tools.device.traces.io.ResultWriter
import android.tools.device.traces.monitors.ITransitionMonitor
import android.tools.device.traces.monitors.ScreenRecorder
import android.tools.device.traces.monitors.events.EventLogMonitor
import android.tools.device.traces.monitors.surfaceflinger.LayersTraceMonitor
import android.tools.device.traces.monitors.surfaceflinger.TransactionsTraceMonitor
import android.tools.device.traces.monitors.wm.ShellTransitionTraceMonitor
import android.tools.device.traces.monitors.wm.WindowManagerTraceMonitor
import android.tools.device.traces.monitors.wm.WmTransitionTraceMonitor
import android.tools.device.traces.parsers.WindowManagerStateHelper
import android.tools.device.traces.parsers.surfaceflinger.LayersTraceParser
import android.tools.device.traces.parsers.surfaceflinger.TransactionsTraceParser
import android.tools.device.traces.parsers.wm.TransitionTraceParser
import android.tools.device.traces.parsers.wm.WindowManagerDumpParser
import android.tools.device.traces.parsers.wm.WindowManagerTraceParser
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.uiautomator.UiDevice
import com.google.common.io.ByteStreams
import com.google.common.truth.Truth
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.util.zip.ZipInputStream
import kotlin.io.path.createTempDirectory
import org.mockito.Mockito

internal val TEST_SCENARIO = ScenarioBuilder().forClass("test").build()

internal fun outputFileName(status: RunStatus) =
    File("/sdcard/flicker/${status.prefix}__test_ROTATION_0_GESTURAL_NAV.zip")

internal fun newTestResultWriter() =
    ResultWriter()
        .forScenario(TEST_SCENARIO)
        .withOutputDir(createTempDirectory().toFile())
        .setRunComplete()

internal fun newTestCachedResultWriter() =
    CachedResultWriter()
        .forScenario(TEST_SCENARIO)
        .withOutputDir(createTempDirectory().toFile())
        .setRunComplete()

internal fun getWmTraceReaderFromAsset(
    relativePath: String,
    from: Long = Long.MIN_VALUE,
    to: Long = Long.MAX_VALUE,
    addInitialEntry: Boolean = true,
    legacyTrace: Boolean = false,
): IReader {
    return ParsedTracesReader(
        artifact = InMemoryArtifact(relativePath),
        wmTrace =
            WindowManagerTraceParser(legacyTrace)
                .parse(
                    readAsset(relativePath),
                    CrossPlatform.timestamp.from(elapsedNanos = from),
                    CrossPlatform.timestamp.from(elapsedNanos = to),
                    addInitialEntry,
                    clearCache = false
                )
    )
}

internal fun getWmDumpReaderFromAsset(relativePath: String): IReader {
    return ParsedTracesReader(
        artifact = InMemoryArtifact(relativePath),
        wmTrace = WindowManagerDumpParser().parse(readAsset(relativePath), clearCache = false)
    )
}

internal fun getLayerTraceReaderFromAsset(
    relativePath: String,
    ignoreOrphanLayers: Boolean = true,
    legacyTrace: Boolean = false,
): IReader {
    return ParsedTracesReader(
        artifact = InMemoryArtifact(relativePath),
        layersTrace =
            LayersTraceParser(
                    ignoreLayersStackMatchNoDisplay = false,
                    ignoreLayersInVirtualDisplay = false,
                    legacyTrace = legacyTrace,
                ) {
                    ignoreOrphanLayers
                }
                .parse(readAsset(relativePath))
    )
}

internal fun getTraceReaderFromScenario(scenario: String): IReader {
    val scenarioTraces = getScenarioTraces("AppLaunch")

    return ParsedTracesReader(
        artifact = InMemoryArtifact(scenario),
        wmTrace = WindowManagerTraceParser().parse(scenarioTraces.wmTrace.readBytes()),
        layersTrace = LayersTraceParser().parse(scenarioTraces.layersTrace.readBytes()),
        transitionsTrace =
            TransitionTraceParser()
                .parse(
                    scenarioTraces.wmTransitions.readBytes(),
                    scenarioTraces.shellTransitions.readBytes()
                ),
        transactionsTrace =
            TransactionsTraceParser().parse(scenarioTraces.transactions.readBytes()),
        eventLog = EventLogParser().parse(scenarioTraces.eventLog.readBytes()),
    )
}

@Throws(Exception::class)
internal fun readAsset(relativePath: String): ByteArray {
    val context: Context = InstrumentationRegistry.getInstrumentation().context
    val inputStream = context.resources.assets.open("testdata/$relativePath")
    return ByteStreams.toByteArray(inputStream)
}

@Throws(IOException::class)
fun readAssetAsFile(relativePath: String): File {
    val context: Context = InstrumentationRegistry.getInstrumentation().context
    return File(context.cacheDir, relativePath).also {
        if (!it.exists()) {
            it.outputStream().use { cache ->
                context.assets.open("testdata/$relativePath").use { inputStream ->
                    inputStream.copyTo(cache)
                }
            }
        }
    }
}

/**
 * Runs `r` and asserts that an exception with type `expectedThrowable` is thrown.
 *
 * @param r the [Runnable] which is run and expected to throw.
 * @throws AssertionError if `r` does not throw, or throws a runnable that is not an instance of
 *   `expectedThrowable`.
 */
inline fun <reified ExceptionType> assertThrows(r: () -> Unit): ExceptionType {
    try {
        r()
    } catch (t: Throwable) {
        when {
            ExceptionType::class.java.isInstance(t) -> return t as ExceptionType
            t is Exception ->
                throw AssertionError(
                    "Expected ${ExceptionType::class.java}, but got '${t.javaClass}'",
                    t
                )
            // Re-throw Errors and other non-Exception throwables.
            else -> throw t
        }
    }
    error("Expected exception ${ExceptionType::class.java}, but nothing was thrown")
}

inline fun assertFail(expectedMessage: String, predicate: () -> Any) {
    val error = assertThrows<AssertionError> { predicate() }
    Truth.assertThat(error).hasMessageThat().contains(expectedMessage)
}

fun assertThatErrorContainsDebugInfo(error: Throwable) {
    Truth.assertThat(error).hasMessageThat().contains("What?")
    Truth.assertThat(error).hasMessageThat().contains("Where?")
}

fun assertArchiveContainsFiles(archivePath: File, expectedFiles: List<String>) {
    Truth.assertWithMessage("Expected trace archive `$archivePath` to exist")
        .that(archivePath.exists())
        .isTrue()

    val archiveStream = ZipInputStream(FileInputStream(archivePath))

    val actualFiles = generateSequence { archiveStream.nextEntry }.map { it.name }.toList()

    Truth.assertWithMessage("Trace archive doesn't contain all expected traces")
        .that(actualFiles)
        .containsExactlyElementsIn(expectedFiles)
}

fun getScenarioTraces(scenario: String): FlickerBuilder.TraceFiles {
    lateinit var wmTrace: File
    lateinit var layersTrace: File
    lateinit var transactionsTrace: File
    lateinit var wmTransitionTrace: File
    lateinit var shellTransitionTrace: File
    lateinit var eventLog: File
    val traces =
        mapOf<String, (File) -> Unit>(
            "wm_trace" to { wmTrace = it },
            "layers_trace" to { layersTrace = it },
            "transactions_trace" to { transactionsTrace = it },
            "wm_transition_trace" to { wmTransitionTrace = it },
            "shell_transition_trace" to { shellTransitionTrace = it },
            "eventlog" to { eventLog = it }
        )
    for ((traceName, resultSetter) in traces.entries) {
        val traceBytes = readAsset("scenarios/$scenario/$traceName$WINSCOPE_EXT")
        val traceFile = File.createTempFile(traceName, WINSCOPE_EXT)
        traceFile.writeBytes(traceBytes)
        resultSetter.invoke(traceFile)
    }

    return FlickerBuilder.TraceFiles(
        wmTrace,
        layersTrace,
        transactionsTrace,
        wmTransitionTrace,
        shellTransitionTrace,
        eventLog,
    )
}

fun assertExceptionMessage(error: Throwable?, expectedValue: String) {
    Truth.assertWithMessage("Expected exception")
        .that(error)
        .hasMessageThat()
        .contains(expectedValue)
}

fun createMockedFlicker(
    setup: List<IFlickerTestData.() -> Unit> = emptyList(),
    teardown: List<IFlickerTestData.() -> Unit> = emptyList(),
    transitions: List<IFlickerTestData.() -> Unit> = emptyList(),
    extraMonitor: ITransitionMonitor? = null
): IFlickerTestData {
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val uiDevice: UiDevice = UiDevice.getInstance(instrumentation)
    val mockedFlicker = Mockito.mock(AbstractFlickerTestData::class.java)
    val monitors: MutableList<ITransitionMonitor> =
        mutableListOf(WindowManagerTraceMonitor(), LayersTraceMonitor())
    extraMonitor?.let { monitors.add(it) }
    Mockito.`when`(mockedFlicker.wmHelper).thenReturn(WindowManagerStateHelper())
    Mockito.`when`(mockedFlicker.device).thenReturn(uiDevice)
    Mockito.`when`(mockedFlicker.outputDir).thenReturn(createTempDirectory().toFile())
    Mockito.`when`(mockedFlicker.traceMonitors).thenReturn(monitors)
    Mockito.`when`(mockedFlicker.transitionSetup).thenReturn(setup)
    Mockito.`when`(mockedFlicker.transitionTeardown).thenReturn(teardown)
    Mockito.`when`(mockedFlicker.transitions).thenReturn(transitions)
    return mockedFlicker
}

fun captureTrace(scenario: IScenario, actions: () -> Unit): ResultReader {
    if (scenario.isEmpty) {
        ScenarioBuilder().forClass("UNNAMED_CAPTURE").build()
    }
    val instrumentation: Instrumentation = InstrumentationRegistry.getInstrumentation()
    val writer =
        ResultWriter()
            .forScenario(scenario)
            .withOutputDir(createTempDirectory().toFile())
            .setRunComplete()
    val monitors =
        listOf(
            ScreenRecorder(instrumentation.targetContext),
            EventLogMonitor(),
            TransactionsTraceMonitor(),
            WmTransitionTraceMonitor(),
            ShellTransitionTraceMonitor(),
            WindowManagerTraceMonitor(),
            LayersTraceMonitor()
        )
    try {
        monitors.forEach { it.start() }
        actions.invoke()
    } finally {
        monitors.forEach { it.stop(writer) }
    }
    val result = writer.write()

    return ResultReader(result, TRACE_CONFIG_REQUIRE_CHANGES)
}

fun createDefaultArtifactBuilder(
    status: RunStatus,
    outputDir: File = createTempDirectory().toFile(),
    files: Map<ResultArtifactDescriptor, File> = emptyMap()
) =
    ArtifactBuilder()
        .withScenario(TEST_SCENARIO)
        .withOutputDir(outputDir)
        .withStatus(status)
        .withFiles(files)
