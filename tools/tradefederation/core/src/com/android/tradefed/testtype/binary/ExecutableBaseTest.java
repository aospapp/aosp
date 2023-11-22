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
package com.android.tradefed.testtype.binary;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.metrics.proto.MetricMeasurement.Metric;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.result.TestDescription;
import com.android.tradefed.testtype.IAbi;
import com.android.tradefed.testtype.IAbiReceiver;
import com.android.tradefed.testtype.IRemoteTest;
import com.android.tradefed.testtype.IRuntimeHintProvider;
import com.android.tradefed.testtype.IShardableTest;
import com.android.tradefed.testtype.ITestCollector;

import java.io.File;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;

/** Base class for executable style of tests. For example: binaries, shell scripts. */
public abstract class ExecutableBaseTest
        implements IRemoteTest, IRuntimeHintProvider, ITestCollector, IShardableTest, IAbiReceiver {

    public static final String NO_BINARY_ERROR = "Binary %s does not exist.";

    @Option(name = "binary", description = "Path to the binary to be run. Can be repeated.")
    private List<String> mBinaryPaths = new ArrayList<>();

    @Option(
        name = "collect-tests-only",
        description = "Only dry-run through the tests, do not actually run them."
    )
    private boolean mCollectTestsOnly = false;

    @Option(
        name = "runtime-hint",
        description = "The hint about the test's runtime.",
        isTimeVal = true
    )
    private long mRuntimeHintMs = 60000L; // 1 minute

    private IAbi mAbi;

    @Override
    public final void run(ITestInvocationListener listener) throws DeviceNotAvailableException {
        for (String binary : mBinaryPaths) {
            String path = findBinary(binary);
            if (path == null) {
                listener.testRunStarted(new File(binary).getName(), 0);
                listener.testRunFailed(String.format(NO_BINARY_ERROR, binary));
                listener.testRunEnded(0L, new HashMap<String, Metric>());
            } else {
                listener.testRunStarted(new File(path).getName(), 1);
                long startTimeMs = System.currentTimeMillis();
                TestDescription description =
                        new TestDescription(new File(path).getName(), new File(path).getName());
                listener.testStarted(description);
                try {
                    if (!mCollectTestsOnly) {
                        // Do not actually run the test if we are dry running it.
                        runBinary(path, listener, description);
                    }
                } finally {
                    listener.testEnded(description, new HashMap<String, Metric>());
                    listener.testRunEnded(
                            System.currentTimeMillis() - startTimeMs,
                            new HashMap<String, Metric>());
                }
            }
        }
    }

    /**
     * Search for the binary to be able to run it.
     *
     * @param binary the path of the binary or simply the binary name.
     * @return The path to the binary, or null if not found.
     */
    public abstract String findBinary(String binary);

    /**
     * Actually run the binary at the given path.
     *
     * @param binaryPath The path of the binary.
     * @param listener The listener where to report the results.
     * @param description The test in progress.
     */
    public abstract void runBinary(
            String binaryPath, ITestInvocationListener listener, TestDescription description)
            throws DeviceNotAvailableException;

    /** {@inheritDoc} */
    @Override
    public final void setCollectTestsOnly(boolean shouldCollectTest) {
        mCollectTestsOnly = shouldCollectTest;
    }

    /** {@inheritDoc} */
    @Override
    public final long getRuntimeHint() {
        return mRuntimeHintMs;
    }

    /** {@inheritDoc} */
    @Override
    public final void setAbi(IAbi abi) {
        mAbi = abi;
    }

    /** {@inheritDoc} */
    @Override
    public IAbi getAbi() {
        return mAbi;
    }

    /** {@inheritDoc} */
    @Override
    public final Collection<IRemoteTest> split() {
        if (mBinaryPaths.size() <= 2) {
            return null;
        }
        Collection<IRemoteTest> tests = new ArrayList<>();
        for (String path : mBinaryPaths) {
            tests.add(getTestShard(path));
        }
        return tests;
    }

    private IRemoteTest getTestShard(String path) {
        ExecutableBaseTest shard = null;
        try {
            shard = this.getClass().newInstance();
            OptionCopier.copyOptionsNoThrow(this, shard);
            // We approximate the runtime of each shard to be equal since we can't know.
            shard.mRuntimeHintMs = mRuntimeHintMs / shard.mBinaryPaths.size();
            // Set one binary per shard
            shard.mBinaryPaths.clear();
            shard.mBinaryPaths.add(path);
        } catch (InstantiationException | IllegalAccessException e) {
            // This cannot happen because the class was already created once at that point.
            throw new RuntimeException(
                    String.format(
                            "%s (%s) when attempting to create shard object",
                            e.getClass().getSimpleName(), e.getMessage()));
        }
        return shard;
    }
}
