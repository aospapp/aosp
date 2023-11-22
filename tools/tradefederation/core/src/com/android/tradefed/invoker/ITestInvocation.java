/*
 * Copyright (C) 2010 The Android Open Source Project
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

package com.android.tradefed.invoker;

import com.android.tradefed.config.IConfiguration;
import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.invoker.shard.IShardHelper;
import com.android.tradefed.result.ITestInvocationListener;
import com.android.tradefed.targetprep.BuildError;
import com.android.tradefed.targetprep.TargetSetupError;

/**
 * Handles one TradeFederation test invocation.
 */
public interface ITestInvocation {

    /**
     * Perform the test invocation.
     *
     * @param metadata the {@link IInvocationContext} to perform tests.
     * @param config the {@link IConfiguration} of this test run.
     * @param rescheduler the {@link IRescheduler}, for rescheduling portions of the invocation for
     *        execution on another resource(s)
     * @param extraListeners {@link ITestInvocationListener}s to notify, in addition to those in
     *        <var>config</var>
     * @throws DeviceNotAvailableException if communication with device was lost
     * @throws Throwable
     */
    public void invoke(IInvocationContext metadata, IConfiguration config,
            IRescheduler rescheduler, ITestInvocationListener... extraListeners)
            throws DeviceNotAvailableException, Throwable;

    /**
     * Execute the build_provider step of the invocation.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param rescheduler the {@link IRescheduler}, for rescheduling portions of the invocation for
     *     execution on another resource(s)
     * @param listener the {@link ITestInvocation} to report build download failures.
     * @return True if we successfully downloaded the build, false otherwise.
     * @throws DeviceNotAvailableException
     */
    public default boolean fetchBuild(
            IInvocationContext context,
            IConfiguration config,
            IRescheduler rescheduler,
            ITestInvocationListener listener)
            throws DeviceNotAvailableException {
        return false;
    }

    /**
     * Execute the build_provider clean up step. Associated with the build fetching.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     */
    public default void cleanUpBuilds(IInvocationContext context, IConfiguration config) {}

    /**
     * Execute the target_preparer and multi_target_preparer setUp step. Does all the devices setup
     * required for the test to run.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param listener the {@link ITestInvocation} to report setup failures.
     * @throws TargetSetupError
     * @throws BuildError
     * @throws DeviceNotAvailableException
     */
    public default void doSetup(
            IInvocationContext context,
            IConfiguration config,
            final ITestInvocationListener listener)
            throws TargetSetupError, BuildError, DeviceNotAvailableException {}

    /**
     * Execute the target_preparer and multi_target_preparer teardown step. Does the devices tear
     * down associated with the setup.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param exception the original exception thrown by the test running.
     * @throws Throwable
     */
    public default void doTeardown(
            IInvocationContext context, IConfiguration config, Throwable exception)
            throws Throwable {}

    /**
     * Execute the target_preparer and multi_target_preparer cleanUp step. Does the devices clean
     * up.
     *
     * @param context the {@link IInvocationContext} of the invocation.
     * @param config the {@link IConfiguration} of this test run.
     * @param exception the original exception thrown by the test running.
     */
    public default void doCleanUp(
            IInvocationContext context, IConfiguration config, Throwable exception) {}

    /**
     * Attempt to shard the configuration into sub-configurations, to be re-scheduled to run on
     * multiple resources in parallel.
     *
     * <p>If a shard count is greater than 1, it will simply create configs for each shard by
     * setting shard indices and reschedule them. If a shard count is not set,it would fallback to
     * {@link IShardHelper#shardConfig}.
     *
     * @param config the current {@link IConfiguration}.
     * @param context the {@link IInvocationContext} holding the info of the tests.
     * @param rescheduler the {@link IRescheduler}
     * @return true if test was sharded. Otherwise return <code>false</code>
     */
    public default boolean shardConfig(
            IConfiguration config, IInvocationContext context, IRescheduler rescheduler) {
        return false;
    }

    /** Notify the {@link TestInvocation} that TradeFed has been requested to stop. */
    public default void notifyInvocationStopped() {}
}
