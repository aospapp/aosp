/*
 * Copyright (C) 2018 The Android Open Source Project
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
package com.android.tradefed.sandbox;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;

import java.io.File;

/** Class that can receive and provide options to a {@link ISandbox}. */
@OptionClass(alias = "sandbox", global_namespace = true)
public final class SandboxOptions {

    public static final String TF_LOCATION = "tf-location";
    public static final String SANDBOX_BUILD_ID = "sandbox-build-id";
    public static final String USE_PROTO_REPORTER = "use-proto-reporter";
    public static final String CHILD_GLOBAL_CONFIG = "sub-global-config";
    public static final String PARENT_PREPARER_CONFIG = "parent-preparer-config";
    public static final String WAIT_FOR_EVENTS_TIMEOUT = "wait-for-events";

    @Option(
        name = TF_LOCATION,
        description = "The path to the Tradefed binary of the version to use for the sandbox."
    )
    private File mTfVersion = null;

    @Option(
        name = SANDBOX_BUILD_ID,
        description =
                "Provide the build-id to force the sandbox version of Tradefed to be."
                        + "Mutually exclusive with the tf-location option."
    )
    private String mBuildId = null;

    @Option(
        name = USE_PROTO_REPORTER,
        description = "Whether or not to use protobuf format reporting between processes."
    )
    private boolean mUseProtoReporter = true;

    @Option(
            name = CHILD_GLOBAL_CONFIG,
            description =
                    "Force a particular configuration to be used as global configuration for the"
                            + " sandbox.")
    private String mChildGlobalConfig = null;

    @Option(
        name = PARENT_PREPARER_CONFIG,
        description =
                "A configuration which target_preparers will be run in the parent of the sandbox."
    )
    private String mParentPreparerConfig = null;

    @Option(
        name = WAIT_FOR_EVENTS_TIMEOUT,
        isTimeVal = true,
        description =
                "The time we should wait for all events to complete after the "
                        + "sandbox is done running."
    )
    private long mWaitForEventsTimeoutMs = 30000L;

    /**
     * Returns the provided directories containing the Trade Federation version to use for
     * sandboxing the run.
     */
    public File getSandboxTfDirectory() {
        return mTfVersion;
    }

    /** Returns the build-id forced for the sandbox to be used during the run. */
    public String getSandboxBuildId() {
        return mBuildId;
    }

    /** Returns whether or not protobuf reporting should be used. */
    public boolean shouldUseProtoReporter() {
        return mUseProtoReporter;
    }

    /**
     * Returns the configuration to be used for the child sandbox. Or null if the parent one should
     * be used.
     */
    public String getChildGlobalConfig() {
        return mChildGlobalConfig;
    }

    /** Returns the configuration which preparer should run in the parent process of the sandbox. */
    public String getParentPreparerConfig() {
        return mParentPreparerConfig;
    }

    /**
     * Returns the time we should wait for events to be processed after the sandbox is done running.
     */
    public long getWaitForEventsTimeout() {
        return mWaitForEventsTimeoutMs;
    }
}
