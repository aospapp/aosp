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
package com.android.tradefed.testtype.bazel;

import com.android.annotations.VisibleForTesting;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.TestInformation;
import com.android.tradefed.invoker.tracing.CloseableTraceScope;
import com.android.tradefed.invoker.tracing.TracePropagatingExecutorService;
import com.android.tradefed.log.ITestLogger;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.result.FailureDescription;
import com.android.tradefed.result.FileInputStreamSource;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.LogDataType;
import com.android.tradefed.result.error.ErrorIdentifier;
import com.android.tradefed.result.error.TestErrorIdentifier;
import com.android.tradefed.result.proto.LogFileProto.LogFileInfo;
import com.android.tradefed.result.proto.ProtoResultParser;
import com.android.tradefed.result.proto.TestRecordProto.ChildReference;
import com.android.tradefed.result.proto.TestRecordProto.FailureStatus;
import com.android.tradefed.result.proto.TestRecordProto.TestRecord;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.util.ZipUtil;
import com.android.tradefed.util.proto.TestRecordProtoUtil;

import com.google.common.collect.HashMultimap;
import com.google.common.collect.ImmutableMap;
import com.google.common.collect.Maps;
import com.google.common.collect.SetMultimap;
import com.google.common.io.CharStreams;
import com.google.common.io.MoreFiles;
import com.google.common.io.Resources;
import com.google.devtools.build.lib.buildeventstream.BuildEventStreamProtos;
import com.google.protobuf.Any;
import com.google.protobuf.InvalidProtocolBufferException;

import java.io.File;
import java.io.IOException;
import java.io.FileOutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.net.URI;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map.Entry;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.zip.ZipFile;

/** Test runner for executing Bazel tests. */
@OptionClass(alias = "bazel-test")
public final class BazelTest implements IRemoteTest {

    public static final String QUERY_ALL_TARGETS = "query_all_targets";
    public static final String QUERY_MAP_MODULES_TO_TARGETS = "query_map_modules_to_targets";
    public static final String RUN_TESTS = "run_tests";

    // Add method excludes to TF's global filters since Bazel doesn't support target-specific
    // arguments. See https://github.com/bazelbuild/rules_go/issues/2784.
    // TODO(b/274787592): Integrate with Bazel's test filtering to filter specific test cases.
    public static final String GLOBAL_EXCLUDE_FILTER_TEMPLATE =
            "--test_arg=--global-filters:exclude-filter=%s";

    private static final Duration BAZEL_QUERY_TIMEOUT = Duration.ofMinutes(5);
    private static final String TEST_NAME = BazelTest.class.getName();
    // Bazel internally calls the test output archive file "test.outputs__outputs.zip", the double
    // underscore is part of this name.
    private static final String TEST_UNDECLARED_OUTPUTS_ARCHIVE_NAME = "test.outputs__outputs.zip";
    private static final String PROTO_RESULTS_FILE_NAME = "proto-results";

    private final List<Path> mTemporaryPaths = new ArrayList<>();
    private final List<Path> mLogFiles = new ArrayList<>();
    private final Properties mProperties;
    private final ProcessStarter mProcessStarter;
    private final Path mTemporaryDirectory;
    private final ExecutorService mExecutor;

    private Path mRunTemporaryDirectory;

    private enum FilterType {
        MODULE,
        TEST_CASE
    };

    @Option(
            name = "bazel-test-command-timeout",
            description = "Timeout for running the Bazel test.")
    private Duration mBazelCommandTimeout = Duration.ofHours(1L);

    @Option(
            name = "bazel-test-suite-root-dir",
            description =
                    "Name of the environment variable set by CtsTestLauncher indicating the"
                            + " location of the root bazel-test-suite dir.")
    private String mSuiteRootDirEnvVar = "BAZEL_SUITE_ROOT";

    @Option(
            name = "bazel-startup-options",
            description = "List of startup options to be passed to Bazel.")
    private final List<String> mBazelStartupOptions = new ArrayList<>();

    @Option(
            name = "bazel-test-extra-args",
            description = "List of extra arguments to be passed to Bazel")
    private final List<String> mBazelTestExtraArgs = new ArrayList<>();

    @Option(
            name = "bazel-max-idle-timout",
            description = "Max idle timeout in seconds for bazel commands.")
    private Duration mBazelMaxIdleTimeout = Duration.ofSeconds(5L);

    @Option(name = "exclude-filter", description = "Test modules to exclude when running tests.")
    private final List<String> mExcludeTargets = new ArrayList<>();

    @Option(name = "include-filter", description = "Test modules to include when running tests.")
    private final List<String> mIncludeTargets = new ArrayList<>();

    @Option(
            name = "report-cached-test-results",
            description = "Whether or not to report cached test results.")
    private boolean mReportCachedTestResults = true;

    public BazelTest() {
        this(new DefaultProcessStarter(), System.getProperties());
    }

    @VisibleForTesting
    BazelTest(ProcessStarter processStarter, Properties properties) {
        mProcessStarter = processStarter;
        mExecutor = TracePropagatingExecutorService.create(Executors.newCachedThreadPool());
        mProperties = properties;
        mTemporaryDirectory = Paths.get(properties.getProperty("java.io.tmpdir"));
    }

    @Override
    public void run(TestInformation testInfo, ITestInvocationListener listener)
            throws DeviceNotAvailableException {

        List<FailureDescription> runFailures = new ArrayList<>();
        long startTime = System.currentTimeMillis();

        try {
            initialize();
            runTestsAndParseResults(testInfo, listener, runFailures);
        } catch (AbortRunException e) {
            runFailures.add(e.getFailureDescription());
        } catch (IOException | InterruptedException e) {
            runFailures.add(throwableToTestFailureDescription(e));
        }

        listener.testModuleStarted(testInfo.getContext());
        listener.testRunStarted(TEST_NAME, 0);
        reportRunFailures(runFailures, listener);
        listener.testRunEnded(System.currentTimeMillis() - startTime, Collections.emptyMap());
        listener.testModuleEnded();

        addTestLogs(listener);
        cleanup();
    }

    private void initialize() throws IOException {
        mRunTemporaryDirectory = Files.createTempDirectory(mTemporaryDirectory, "bazel-test-");
    }

    private void runTestsAndParseResults(
            TestInformation testInfo,
            ITestInvocationListener listener,
            List<FailureDescription> runFailures)
            throws IOException, InterruptedException {

        Path workspaceDirectory = resolveWorkspacePath();

        Collection<String> testTargets = listTestTargets(workspaceDirectory);
        if (testTargets.isEmpty()) {
            throw new AbortRunException(
                    "No targets found, aborting",
                    FailureStatus.DEPENDENCY_ISSUE,
                    TestErrorIdentifier.TEST_ABORTED);
        }

        Path bepFile = createTemporaryFile("BEP_output");

        Process bazelTestProcess =
                startTests(testInfo, listener, testTargets, workspaceDirectory, bepFile);

        try (BepFileTailer tailer = BepFileTailer.create(bepFile)) {
            bazelTestProcess.onExit().thenRun(() -> tailer.stop());
            reportTestResults(listener, testInfo, runFailures, tailer);
        }

        // Note that if Bazel exits without writing the 'last' BEP message marker we won't get to
        // here since the above reporting code throws.
        waitForProcess(bazelTestProcess, RUN_TESTS);
    }

    void reportTestResults(
            ITestInvocationListener listener,
            TestInformation testInfo,
            List<FailureDescription> runFailures,
            BepFileTailer tailer)
            throws InterruptedException, IOException {

        try (CloseableTraceScope ignored = new CloseableTraceScope("reportTestResults")) {
            reportTestResultsNoTrace(listener, testInfo, runFailures, tailer);
        }
    }

    void reportTestResultsNoTrace(
            ITestInvocationListener listener,
            TestInformation testInfo,
            List<FailureDescription> runFailures,
            BepFileTailer tailer)
            throws InterruptedException, IOException {

        ProtoResultParser resultParser =
                new ProtoResultParser(listener, testInfo.getContext(), false, "tf-test-process-");

        BuildEventStreamProtos.BuildEvent event;
        while ((event = tailer.nextEvent()) != null) {
            if (event.getLastMessage()) {
                return;
            }

            if (!event.hasTestResult()) {
                continue;
            }

            if (!mReportCachedTestResults && isTestResultCached(event.getTestResult())) {
                continue;
            }

            try {
                reportEventsInTestOutputsArchive(event.getTestResult(), resultParser);
            } catch (IOException | InterruptedException | URISyntaxException e) {
                runFailures.add(
                        throwableToInfraFailureDescription(e)
                                .setErrorIdentifier(TestErrorIdentifier.OUTPUT_PARSER_ERROR));
            }
        }

        throw new AbortRunException(
                "Unexpectedly hit end of BEP file without receiving last message",
                FailureStatus.INFRA_FAILURE,
                TestErrorIdentifier.OUTPUT_PARSER_ERROR);
    }

    private static boolean isTestResultCached(BuildEventStreamProtos.TestResult result) {
        return result.getCachedLocally() || result.getExecutionInfo().getCachedRemotely();
    }

    private ProcessBuilder createBazelCommand(Path workspaceDirectory, String tmpDirPrefix)
            throws IOException {

        Path javaTmpDir = createTemporaryDirectory("%s-java-tmp-out".formatted(tmpDirPrefix));
        Path bazelTmpDir = createTemporaryDirectory("%s-bazel-tmp-out".formatted(tmpDirPrefix));

        List<String> command = new ArrayList<>();

        command.add(workspaceDirectory.resolve("bazel.sh").toAbsolutePath().toString());
        command.add(
                "--host_jvm_args=-Djava.io.tmpdir=%s"
                        .formatted(javaTmpDir.toAbsolutePath().toString()));
        command.add("--output_user_root=%s".formatted(bazelTmpDir.toAbsolutePath().toString()));
        command.add("--max_idle_secs=%d".formatted(mBazelMaxIdleTimeout.toSeconds()));

        ProcessBuilder builder = new ProcessBuilder(command);

        builder.directory(workspaceDirectory.toFile());

        return builder;
    }

    private Collection<String> listTestTargets(Path workspaceDirectory)
            throws IOException, InterruptedException {

        try (CloseableTraceScope ignored = new CloseableTraceScope("listTestTargets")) {
            return listTestTargetsNoTrace(workspaceDirectory);
        }
    }

    private Collection<String> listTestTargetsNoTrace(Path workspaceDirectory)
            throws IOException, InterruptedException {

        // We need to query all tests targets first in a separate Bazel query call since 'cquery
        // tests(...)' doesn't work in the Atest Bazel workspace.
        List<String> allTestTargets = queryAllTestTargets(workspaceDirectory);
        CLog.i("Found %d test targets in workspace", allTestTargets.size());

        Map<String, String> moduleToTarget =
                queryModulesToTestTargets(workspaceDirectory, allTestTargets);

        Set<String> moduleExcludes = groupTargetsByType(mExcludeTargets).get(FilterType.MODULE);
        Set<String> moduleIncludes = groupTargetsByType(mIncludeTargets).get(FilterType.MODULE);

        if (!moduleIncludes.isEmpty() && !moduleExcludes.isEmpty()) {
            throw new AbortRunException(
                    "Invalid options: cannot set both module-level include filters and module-level"
                            + " exclude filters.",
                    FailureStatus.DEPENDENCY_ISSUE,
                    TestErrorIdentifier.TEST_ABORTED);
        }

        if (!moduleIncludes.isEmpty()) {
            return Maps.filterKeys(moduleToTarget, s -> moduleIncludes.contains(s)).values();
        }

        if (!moduleExcludes.isEmpty()) {
            return Maps.filterKeys(moduleToTarget, s -> !moduleExcludes.contains(s)).values();
        }

        return moduleToTarget.values();
    }

    private List<String> queryAllTestTargets(Path workspaceDirectory)
            throws IOException, InterruptedException {

        Path logFile = createLogFile("%s-log".formatted(QUERY_ALL_TARGETS));

        ProcessBuilder builder = createBazelCommand(workspaceDirectory, QUERY_ALL_TARGETS);

        builder.command().add("query");
        builder.command().add("tests(...)");
        builder.redirectError(Redirect.appendTo(logFile.toFile()));

        Process queryProcess = startProcess(QUERY_ALL_TARGETS, builder, BAZEL_QUERY_TIMEOUT);
        List<String> queryLines = readProcessLines(queryProcess);

        waitForProcess(queryProcess, QUERY_ALL_TARGETS);

        return queryLines;
    }

    private Map<String, String> queryModulesToTestTargets(
            Path workspaceDirectory, List<String> allTestTargets)
            throws IOException, InterruptedException {

        Path cqueryTestTargetsFile = createTemporaryFile("test_targets");
        Files.write(cqueryTestTargetsFile, String.join("+", allTestTargets).getBytes());

        Path cqueryFormatFile = createTemporaryFile("format_module_name_to_test_target");
        try (FileOutputStream os = new FileOutputStream(cqueryFormatFile.toFile())) {
            Resources.copy(
                    Resources.getResource("config/format_module_name_to_test_target.cquery"), os);
        }

        Path logFile = createLogFile("%s-log".formatted(QUERY_MAP_MODULES_TO_TARGETS));
        ProcessBuilder builder =
                createBazelCommand(workspaceDirectory, QUERY_MAP_MODULES_TO_TARGETS);

        builder.command().add("cquery");
        builder.command().add("--query_file=%s".formatted(cqueryTestTargetsFile.toAbsolutePath()));
        builder.command().add("--output=starlark");
        builder.command().add("--starlark:file=%s".formatted(cqueryFormatFile.toAbsolutePath()));
        builder.redirectError(Redirect.appendTo(logFile.toFile()));

        Process process = startProcess(QUERY_MAP_MODULES_TO_TARGETS, builder, BAZEL_QUERY_TIMEOUT);

        List<String> queryLines = readProcessLines(process);

        waitForProcess(process, QUERY_MAP_MODULES_TO_TARGETS);

        return parseModulesToTargets(queryLines);
    }

    private List<String> readProcessLines(Process process) throws IOException {
        return CharStreams.readLines(process.inputReader());
    }

    private Map<String, String> parseModulesToTargets(Collection<String> lines) {
        Map<String, String> moduleToTarget = new HashMap<>();
        StringBuilder errorMessage = new StringBuilder();
        for (String line : lines) {
            // Query output format is: "module_name //bazel/test:target" if a test target is a
            // TF test, "" otherwise, so only count proper targets.
            if (line.isEmpty()) {
                continue;
            }

            String[] splitLine = line.split(" ");

            if (splitLine.length != 2) {
                throw new AbortRunException(
                        String.format(
                                "Unrecognized output from %s command: %s",
                                QUERY_MAP_MODULES_TO_TARGETS, line),
                        FailureStatus.DEPENDENCY_ISSUE,
                        TestErrorIdentifier.TEST_ABORTED);
            }

            String moduleName = splitLine[0];
            String targetName = splitLine[1];

            String duplicateEntry;
            if ((duplicateEntry = moduleToTarget.get(moduleName)) != null) {
                errorMessage.append(
                        "Multiple test targets found for module %s: %s, %s\n"
                                .formatted(moduleName, duplicateEntry, targetName));
            }

            moduleToTarget.put(moduleName, targetName);
        }

        if (errorMessage.length() != 0) {
            throw new AbortRunException(
                    errorMessage.toString(),
                    FailureStatus.DEPENDENCY_ISSUE,
                    TestErrorIdentifier.TEST_ABORTED);
        }
        return ImmutableMap.copyOf(moduleToTarget);
    }

    private Process startTests(
            TestInformation testInfo,
            ITestInvocationListener listener,
            Collection<String> testTargets,
            Path workspaceDirectory,
            Path bepFile)
            throws IOException {

        Path logFile = createLogFile("%s-log".formatted(RUN_TESTS));

        ProcessBuilder builder = createBazelCommand(workspaceDirectory, RUN_TESTS);

        builder.command().addAll(mBazelStartupOptions);
        builder.command().add("test");
        builder.command().addAll(testTargets);

        builder.command().add("--build_event_binary_file=%s".formatted(bepFile.toAbsolutePath()));

        builder.command().addAll(mBazelTestExtraArgs);

        Set<String> testFilters = groupTargetsByType(mExcludeTargets).get(FilterType.TEST_CASE);
        for (String test : testFilters) {
            builder.command().add(GLOBAL_EXCLUDE_FILTER_TEMPLATE.formatted(test));
        }
        builder.redirectErrorStream(true);
        builder.redirectOutput(Redirect.appendTo(logFile.toFile()));

        return startProcess(RUN_TESTS, builder, mBazelCommandTimeout);
    }

    private static SetMultimap<FilterType, String> groupTargetsByType(List<String> targets) {
        Map<FilterType, List<String>> groupedMap =
                targets.stream()
                        .collect(
                                Collectors.groupingBy(
                                        s ->
                                                s.contains(" ")
                                                        ? FilterType.TEST_CASE
                                                        : FilterType.MODULE));

        SetMultimap<FilterType, String> groupedMultiMap = HashMultimap.create();
        for (Entry<FilterType, List<String>> entry : groupedMap.entrySet()) {
            groupedMultiMap.putAll(entry.getKey(), entry.getValue());
        }

        return groupedMultiMap;
    }

    private Process startAndWaitForProcess(
            String processTag, ProcessBuilder builder, Duration processTimeout)
            throws InterruptedException, IOException {

        Process process = startProcess(processTag, builder, processTimeout);
        waitForProcess(process, processTag);
        return process;
    }

    private Process startProcess(String processTag, ProcessBuilder builder, Duration timeout)
            throws IOException {

        CLog.i("Running command for %s: %s", processTag, new ProcessDebugString(builder));
        String traceTag = "Process:" + processTag;
        Process process = mProcessStarter.start(processTag, builder);

        // We wait for the process in a separate thread so that we can trace its execution time.
        // Another alternative could be to start/stop tracing with explicit calls but these would
        // have to be done on the same thread as required by the tracing facility.
        mExecutor.submit(
                () -> {
                    try (CloseableTraceScope unused = new CloseableTraceScope(traceTag)) {
                        if (waitForProcessUninterruptibly(process, timeout)) {
                            return;
                        }

                        CLog.e("%s command timed out and is being destroyed", processTag);
                        process.destroy();

                        // Give the process a grace period to properly shut down before forcibly
                        // terminating it. We _could_ deduct this time from the total timeout but
                        // it's overkill.
                        if (!waitForProcessUninterruptibly(process, Duration.ofSeconds(5))) {
                            CLog.w(
                                    "%s command did not terminate normally after the grace period"
                                            + " and is being forcibly destroyed",
                                    processTag);
                            process.destroyForcibly();
                        }

                        // We wait for the process as it may take it some time to terminate and
                        // otherwise skew the trace results.
                        waitForProcessUninterruptibly(process);
                        CLog.i("%s command timed out and was destroyed", processTag);
                    }
                });

        return process;
    }

    private void waitForProcess(Process process, String processTag) throws InterruptedException {

        if (process.waitFor() == 0) {
            return;
        }

        throw new AbortRunException(
                String.format("%s command failed. Exit code: %d", processTag, process.exitValue()),
                FailureStatus.DEPENDENCY_ISSUE,
                TestErrorIdentifier.TEST_ABORTED);
    }

    private void reportEventsInTestOutputsArchive(
            BuildEventStreamProtos.TestResult result, ProtoResultParser resultParser)
            throws IOException, InvalidProtocolBufferException, InterruptedException,
                    URISyntaxException {

        try (CloseableTraceScope ignored =
                new CloseableTraceScope("reportEventsInTestOutputsArchive")) {
            reportEventsInTestOutputsArchiveNoTrace(result, resultParser);
        }
    }

    private void reportEventsInTestOutputsArchiveNoTrace(
            BuildEventStreamProtos.TestResult result, ProtoResultParser resultParser)
            throws IOException, InvalidProtocolBufferException, InterruptedException,
                    URISyntaxException {

        BuildEventStreamProtos.File outputsFile =
                result.getTestActionOutputList().stream()
                        .filter(file -> file.getName().equals(TEST_UNDECLARED_OUTPUTS_ARCHIVE_NAME))
                        .findAny()
                        .orElseThrow(() -> new IOException("No test output archive found"));

        URI uri = new URI(outputsFile.getUri());

        File zipFile = new File(uri.getPath());
        Path outputFilesDir = Files.createTempDirectory(mRunTemporaryDirectory, "output_zip-");

        try {
            ZipUtil.extractZip(new ZipFile(zipFile), outputFilesDir.toFile());

            File protoResult = outputFilesDir.resolve(PROTO_RESULTS_FILE_NAME).toFile();
            TestRecord record = TestRecordProtoUtil.readFromFile(protoResult);

            TestRecord.Builder recordBuilder = record.toBuilder();
            //recursivelyUpdateArtifactsRootPath(recordBuilder, outputFilesDir);
            //moveRootRecordArtifactsToFirstChild(recordBuilder);
            resultParser.processFinalizedProto(recordBuilder.build());
        } finally {
            MoreFiles.deleteRecursively(outputFilesDir);
        }
    }

    /*private void recursivelyUpdateArtifactsRootPath(TestRecord.Builder recordBuilder, Path newRoot)
            throws InvalidProtocolBufferException {

        Map<String, Any> updatedMap = new HashMap<>();
        for (Entry<String, Any> entry : recordBuilder.getArtifactsMap().entrySet()) {
            LogFileInfo info = entry.getValue().unpack(LogFileInfo.class);

            Path relativePath = findRelativeArtifactPath(Paths.get(info.getPath()));

            LogFileInfo updatedInfo =
                    info.toBuilder()
                            .setPath(newRoot.resolve(relativePath).toAbsolutePath().toString())
                            .build();
            updatedMap.put(entry.getKey(), Any.pack(updatedInfo));
        }

        recordBuilder.putAllArtifacts(updatedMap);

        for (ChildReference.Builder childBuilder : recordBuilder.getChildrenBuilderList()) {
            recursivelyUpdateArtifactsRootPath(childBuilder.getInlineTestRecordBuilder(), newRoot);
        }
    }*/

    private Path findRelativeArtifactPath(Path originalPath) {
        // The log files are stored under
        // ${EXTRACTED_UNDECLARED_OUTPUTS}/stub/-1/stub/inv_xxx/inv_xxx/logfile so the new path is
        // found by trimming down the original path until it starts with "stub/-1/stub" and
        // appending that to our extracted directory.
        // TODO(b/251279690) Create a directory within undeclared outputs which we can more
        // reliably look for to calculate this relative path.
        Path delimiter = Paths.get("stub/-1/stub");

        Path relativePath = originalPath;
        while (!relativePath.startsWith(delimiter)
                && relativePath.getNameCount() > delimiter.getNameCount()) {
            relativePath = relativePath.subpath(1, relativePath.getNameCount());
        }

        if (!relativePath.startsWith(delimiter)) {
            throw new IllegalArgumentException(
                    String.format(
                            "Artifact path '%s' does not contain delimiter '%s' and therefore"
                                    + " cannot be found",
                            originalPath, delimiter));
        }

        return relativePath;
    }

    /*private void moveRootRecordArtifactsToFirstChild(TestRecord.Builder recordBuilder) {
        if (recordBuilder.getChildrenCount() == 0) {
            return;
        }

        TestRecord.Builder childTestRecordBuilder =
                recordBuilder.getChildrenBuilder(0).getInlineTestRecordBuilder();
        for (Entry<String, Any> entry : recordBuilder.getArtifactsMap().entrySet()) {
            childTestRecordBuilder.putArtifacts(entry.getKey(), entry.getValue());
        }

        recordBuilder.clearArtifacts();
    }*/

    private void reportRunFailures(
            List<FailureDescription> runFailures, ITestInvocationListener listener) {

        if (runFailures.isEmpty()) {
            return;
        }

        for (FailureDescription runFailure : runFailures) {
            CLog.e(runFailure.getErrorMessage());
        }

        FailureDescription reportedFailure = runFailures.get(0);
        listener.testRunFailed(
                FailureDescription.create(
                                String.format(
                                        "The run had %d failures, the first of which was: %s\n"
                                                + "See the subprocess-host_log for more details.",
                                        runFailures.size(), reportedFailure.getErrorMessage()),
                                reportedFailure.getFailureStatus())
                        .setErrorIdentifier(reportedFailure.getErrorIdentifier()));
    }

    private Path resolveWorkspacePath() {
        String suiteRootPath = mProperties.getProperty(mSuiteRootDirEnvVar);
        if (suiteRootPath == null || suiteRootPath.isEmpty()) {
            throw new AbortRunException(
                    "Bazel Test Suite root directory not set, aborting",
                    FailureStatus.DEPENDENCY_ISSUE,
                    TestErrorIdentifier.TEST_ABORTED);
        }

        // TODO(b/233885171): Remove resolve once workspace archive is updated.
        return Paths.get(suiteRootPath).resolve("android-bazel-suite/out/atest_bazel_workspace");
    }

    private void addTestLogs(ITestLogger logger) {
        for (Path logFile : mLogFiles) {
            try (FileInputStreamSource source = new FileInputStreamSource(logFile.toFile(), true)) {
                logger.testLog(logFile.toFile().getName(), LogDataType.TEXT, source);
            }
        }
    }

    private void cleanup() {
        try {
            MoreFiles.deleteRecursively(mRunTemporaryDirectory);
        } catch (IOException e) {
            CLog.e(e);
        }
    }

    interface ProcessStarter {
        Process start(String processTag, ProcessBuilder builder) throws IOException;
    }

    private static final class DefaultProcessStarter implements ProcessStarter {
        @Override
        public Process start(String processTag, ProcessBuilder builder) throws IOException {
            return builder.start();
        }
    }

    private Path createTemporaryDirectory(String prefix) throws IOException {
        return Files.createTempDirectory(mRunTemporaryDirectory, prefix);
    }

    private Path createTemporaryFile(String prefix) throws IOException {
        return Files.createTempFile(mRunTemporaryDirectory, prefix, "");
    }

    private Path createLogFile(String name) throws IOException {
        Path logFile = Files.createTempFile(mRunTemporaryDirectory, name, ".txt");

        mLogFiles.add(logFile);

        return logFile;
    }

    private static FailureDescription throwableToTestFailureDescription(Throwable t) {
        return FailureDescription.create(t.getMessage())
                .setCause(t)
                .setFailureStatus(FailureStatus.TEST_FAILURE);
    }

    private static FailureDescription throwableToInfraFailureDescription(Exception e) {
        return FailureDescription.create(e.getMessage())
                .setCause(e)
                .setFailureStatus(FailureStatus.INFRA_FAILURE);
    }

    private static boolean waitForProcessUninterruptibly(Process process, Duration timeout) {
        long remainingNanos = timeout.toNanos();
        long end = System.nanoTime() + remainingNanos;
        boolean interrupted = false;

        try {
            while (true) {
                try {
                    return process.waitFor(remainingNanos, TimeUnit.NANOSECONDS);
                } catch (InterruptedException e) {
                    interrupted = true;
                    remainingNanos = end - System.nanoTime();
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static int waitForProcessUninterruptibly(Process process) {
        boolean interrupted = false;

        try {
            while (true) {
                try {
                    return process.waitFor();
                } catch (InterruptedException e) {
                    interrupted = true;
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class AbortRunException extends RuntimeException {
        private final FailureDescription mFailureDescription;

        public AbortRunException(
                String errorMessage, FailureStatus failureStatus, ErrorIdentifier errorIdentifier) {
            this(
                    FailureDescription.create(errorMessage, failureStatus)
                            .setErrorIdentifier(errorIdentifier));
        }

        public AbortRunException(FailureDescription failureDescription) {
            super(failureDescription.getErrorMessage());
            mFailureDescription = failureDescription;
        }

        public FailureDescription getFailureDescription() {
            return mFailureDescription;
        }
    }

    private static final class ProcessDebugString {

        private final ProcessBuilder mBuilder;

        ProcessDebugString(ProcessBuilder builder) {
            mBuilder = builder;
        }

        public String toString() {
            return String.join(" ", mBuilder.command());
        }
    }
}
