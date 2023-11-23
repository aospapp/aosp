/*
 * Copyright (C) 2011 The Android Open Source Project
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
package com.android.tradefed.command;

import com.android.tradefed.config.ConfigurationException;
import com.android.tradefed.config.Option;
import com.android.tradefed.config.Option.Importance;
import com.android.tradefed.device.metric.AutoLogCollector;
import com.android.tradefed.config.OptionCopier;
import com.android.tradefed.config.OptionUpdateRule;
import com.android.tradefed.log.LogUtil.CLog;
import com.android.tradefed.util.UniqueMultiMap;

import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Implementation of {@link ICommandOptions}.
 */
public class CommandOptions implements ICommandOptions {

    @Option(name = "help", description =
        "display the help text for the most important/critical options.",
        importance = Importance.ALWAYS)
    private boolean mHelpMode = false;

    @Option(name = "help-all", description = "display the full help text for all options.",
            importance = Importance.ALWAYS)
    private boolean mFullHelpMode = false;

    @Option(name = "json-help", description = "display the full help in json format.")
    private boolean mJsonHelpMode = false;

    public static final String DRY_RUN_OPTION = "dry-run";
    public static final String NOISY_DRY_RUN_OPTION = "noisy-dry-run";

    @Option(
        name = DRY_RUN_OPTION,
        description =
                "build but don't actually run the command.  Intended as a quick check "
                        + "to ensure that a command is runnable.",
        importance = Importance.ALWAYS
    )
    private boolean mDryRunMode = false;

    @Option(
        name = NOISY_DRY_RUN_OPTION,
        description =
                "build but don't actually run the command.  This version prints the "
                        + "command to the console.  Intended for cmdfile debugging.",
        importance = Importance.ALWAYS
    )
    private boolean mNoisyDryRunMode = false;

    @Option(name = "min-loop-time", description =
            "the minimum invocation time in ms when in loop mode.")
    private Long mMinLoopTime = 10L * 60L * 1000L;

    @Option(name = "max-random-loop-time", description =
            "the maximum time to wait between invocation attempts when in loop mode. " +
            "when set, the actual value will be a random number between min-loop-time and this " +
            "number.",
            updateRule = OptionUpdateRule.LEAST)
    private Long mMaxRandomLoopTime = null;

    @Option(name = "test-tag", description = "Identifier for the invocation during reporting.")
    private String mTestTag = "stub";

    @Option(name = "test-tag-suffix", description = "suffix for test-tag. appended to test-tag to "
            + "represents some variants of one test.")
    private String mTestTagSuffix = null;

    @Option(name = "loop", description = "keep running continuously.",
            importance = Importance.ALWAYS)
    private boolean mLoopMode = false;

    @Option(name = "all-devices", description =
            "fork this command to run on all connected devices.")
    private boolean mAllDevices = false;

    @Option(name = "bugreport-on-invocation-ended", description =
            "take a bugreport when the test invocation has ended")
    private boolean mTakeBugreportOnInvocationEnded = false;

    @Option(name = "bugreportz-on-invocation-ended", description = "Attempt to take a bugreportz "
            + "instead of bugreport during the test invocation final bugreport.")
    private boolean mTakeBugreportzOnInvocationEnded = false;

    @Option(name = "invocation-timeout", description =
            "the maximum time to wait for an invocation to terminate before attempting to force"
            + "stop it.", isTimeVal = true)
    private long mInvocationTimeout = 0;

    @Option(name = "shard-count", description =
            "the number of total shards to run. Without --shard-index option, this will cause " +
            "the command to spawn multiple shards in the current TF instance. With --shard-index " +
            "option, it will cause the command to run a single shard of tests only.")
    private Integer mShardCount;

    @Option(name = "shard-index", description =
            "the index of shard to run. Only set if shard-count > 1 and the value is in range " +
            "[0, shard-count)")
    private Integer mShardIndex;

    @Option(
        name = "enable-token-sharding",
        description = "Whether or not to allow sharding with the token support enabled."
    )
    private boolean mTokenSharding = false;

    @Option(
        name = "skip-pre-device-setup",
        description =
                "allow TestInvocation to skip calling device.preInvocationSetup. This is for "
                        + "delaying device setup when the test runs with VersionedTfLauncher."
    )
    private boolean mSkipPreDeviceSetup = false;

    @Option(
        name = "dynamic-sharding",
        description =
                "Allow to dynamically move IRemoteTest from one shard to another. Only for local "
                        + "sharding."
    )
    private boolean mDynamicSharding = true;

    public static final String INVOCATION_DATA = "invocation-data";

    @Option(
            name = INVOCATION_DATA,
            description =
                    "A map of values that describe the invocation, these values will be added to the "
                            + "invocation context.")
    private UniqueMultiMap<String, String> mInvocationData = new UniqueMultiMap<>();

    public static final String USE_SANDBOX = "use-sandbox";
    public static final String ENABLE_SANDBOX_TEST_MODE = "sandbox-test-mode";
    public static final String USE_REMOTE_SANDBOX = "use-remote-sandbox";

    @Option(
        name = USE_SANDBOX,
        description = "Set if the invocation should use a sandbox to run or not."
    )
    private boolean mUseSandbox = false;

    @Option(
            name = ENABLE_SANDBOX_TEST_MODE,
            description =
                    "Sandbox test mode where the sandbox will use itself to generate another layer "
                            + "of sandboxing. This is used for the sandbox to validate itself.")
    private boolean mSandboxTestMode = false;

    @Option(
        name = USE_REMOTE_SANDBOX,
        description = "Whether or not to trigger --use-sandbox in the remote invocation."
    )
    private boolean mUseRemoteSandbox = false;

    @Option(
        name = "parallel-remote-setup",
        description =
                "For remote sharded invocation, whether or not to attempt the setup in parallel."
    )
    private boolean mUseParallelRemoteSetup = false;

    @Option(
        name = "auto-collect",
        description =
                "Specify a set of collectors that will be automatically managed by the harness "
                        + "to collect logs."
    )
    private Set<AutoLogCollector> mAutoCollectors = new LinkedHashSet<>();

    @Deprecated
    @Option(
        name = "logcat-on-failure",
        description = "take a logcat snapshot on every test failure."
    )
    private boolean mLogcatOnFailure = false;

    @Deprecated
    @Option(name = "screenshot-on-failure", description = "Take a screenshot on every test failure")
    private boolean mScreenshotOnFailure = false;

    @Option(
        name = "host-log-suffix",
        description = "Suffix to add to Tradefed host_log before logging it."
    )
    private String mHostLogSuffix = null;

    /**
     * Set the help mode for the config.
     * <p/>
     * Exposed for testing.
     */
    void setHelpMode(boolean helpMode) {
        mHelpMode = helpMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isHelpMode() {
        return mHelpMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isFullHelpMode() {
        return mFullHelpMode;
    }

    /**
     * Set the json help mode for the config.
     * <p/>
     * Exposed for testing.
     */
    void setJsonHelpMode(boolean jsonHelpMode) {
        mJsonHelpMode = jsonHelpMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isJsonHelpMode() {
        return mJsonHelpMode;
    }

    /**
     * Set the dry run mode for the config.
     * <p/>
     * Exposed for testing.
     */
    void setDryRunMode(boolean dryRunMode) {
        mDryRunMode = dryRunMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isDryRunMode() {
        return mDryRunMode || mNoisyDryRunMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isNoisyDryRunMode() {
        return mNoisyDryRunMode;
    }

    /**
     * Set the loop mode for the config.
     */
    @Override
    public void setLoopMode(boolean loopMode) {
        mLoopMode = loopMode;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean isLoopMode() {
        return mLoopMode;
    }

    /**
     * Set the min loop time for the config.
     * <p/>
     * Exposed for testing.
     */
    void setMinLoopTime(long loopTime) {
        mMinLoopTime = loopTime;
    }

    /**
     * {@inheritDoc}
     * @deprecated use {@link #getLoopTime()} instead
     */
    @Deprecated
    @Override
    public long getMinLoopTime() {
        return mMinLoopTime;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getLoopTime() {
        if (mMaxRandomLoopTime != null) {
            long randomizedValue = mMaxRandomLoopTime - mMinLoopTime;
            if (randomizedValue > 0) {
                return mMinLoopTime + Math.round(randomizedValue * Math.random());
            } else {
                CLog.e("max loop time %d is less than min loop time %d", mMaxRandomLoopTime,
                        mMinLoopTime);
            }
        }
        return mMinLoopTime;
    }


    @Override
    public ICommandOptions clone() {
        CommandOptions clone = new CommandOptions();
        try {
            OptionCopier.copyOptions(this, clone);
        } catch (ConfigurationException e) {
            CLog.e("failed to clone command options: %s", e.getMessage());
        }
        return clone;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean runOnAllDevices() {
        return mAllDevices;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean takeBugreportOnInvocationEnded() {
        return mTakeBugreportOnInvocationEnded;
    }

    /** {@inheritDoc} */
    @Override
    public void setBugreportOnInvocationEnded(boolean takeBugreport) {
        mTakeBugreportOnInvocationEnded = takeBugreport;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public boolean takeBugreportzOnInvocationEnded() {
        return mTakeBugreportzOnInvocationEnded;
    }

    /** {@inheritDoc} */
    @Override
    public void setBugreportzOnInvocationEnded(boolean takeBugreportz) {
        mTakeBugreportzOnInvocationEnded = takeBugreportz;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public long getInvocationTimeout() {
        return mInvocationTimeout;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setInvocationTimeout(Long invocationTimeout) {
        mInvocationTimeout = invocationTimeout;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Integer getShardCount() {
        return mShardCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setShardCount(Integer shardCount) {
        mShardCount = shardCount;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public Integer getShardIndex() {
        return mShardIndex;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setShardIndex(Integer shardIndex) {
        mShardIndex = shardIndex;
    }

    /** {@inheritDoc} */
    @Override
    public boolean shouldUseTokenSharding() {
        return mTokenSharding;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void setTestTag(String testTag) {
       mTestTag = testTag;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTestTag() {
        return mTestTag;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public String getTestTagSuffix() {
        return mTestTagSuffix;
    }

    /** {@inheritDoc} */
    @Override

    public boolean shouldSkipPreDeviceSetup() {
        return mSkipPreDeviceSetup;
    }

    /** {@inheritDoc} */
    @Override
    public boolean shouldUseDynamicSharding() {
        return mDynamicSharding;
    }

    /** {@inheritDoc} */
    @Override
    public UniqueMultiMap<String, String> getInvocationData() {
        return mInvocationData;
    }

    /** {@inheritDoc} */
    @Override
    public boolean shouldUseSandboxing() {
        return mUseSandbox;
    }

    /** {@inheritDoc} */
    @Override
    public void setShouldUseSandboxing(boolean use) {
        mUseSandbox = use;
    }

    /** {@inheritDoc} */
    @Override
    public boolean shouldUseSandboxTestMode() {
        return mSandboxTestMode;
    }

    /** {@inheritDoc} */
    @Override
    public void setUseSandboxTestMode(boolean use) {
        mSandboxTestMode = use;
    }

    /** {@inheritDoc} */
    @Override
    public boolean shouldUseRemoteSandboxMode() {
        return mUseRemoteSandbox;
    }

    /** {@inheritDoc} */
    @Override
    public Set<AutoLogCollector> getAutoLogCollectors() {
        return mAutoCollectors;
    }

    /** {@inheritDoc} */
    @Override
    public void setAutoLogCollectors(Set<AutoLogCollector> autoLogCollectors) {
        mAutoCollectors = autoLogCollectors;
    }

    /** {@inheritDoc} */
    @Override
    public boolean captureScreenshotOnFailure() {
        return mScreenshotOnFailure;
    }

    /** {@inheritDoc} */
    @Override
    public boolean captureLogcatOnFailure() {
        return mLogcatOnFailure;
    }

    /** {@inheritDoc} */
    @Override
    public String getHostLogSuffix() {
        return mHostLogSuffix;
    }

    /** {@inheritDoc} */
    @Override
    public void setHostLogSuffix(String suffix) {
        mHostLogSuffix = suffix;
    }

    /** {@inheritDoc} */
    @Override
    public boolean shouldUseParallelRemoteSetup() {
        return mUseParallelRemoteSetup;
    }
}
