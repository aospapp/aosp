/*
 * Copyright (C) 2017 The Android Open Source Project
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
package com.android.tradefed.device.metric;

import com.android.tradefed.build.IBuildInfo;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.invoker.IInvocationContext;
import com.android.tradefed.result.ITestInvocationListener;

import java.util.List;

/**
 * This interface will be added as a decorator when reporting tests results in order to collect
 * matching metrics.
 */
public interface IMetricCollector extends ITestInvocationListener {

    /**
     * Initialization of the collector with the current context and where to forward results.
     *
     * @param context the {@link IInvocationContext} for the invocation in progress.
     * @param listener the {@link ITestInvocationListener} where to put results.
     * @return the new listener wrapping the original one.
     */
    public ITestInvocationListener init(
            IInvocationContext context, ITestInvocationListener listener);

    /** Returns the list of devices available in the invocation. */
    public List<ITestDevice> getDevices();

    /** Returns the list of build information available in the invocation. */
    public List<IBuildInfo> getBuildInfos();

    /** Returns the original {@link ITestInvocationListener} where we are forwarding the results. */
    public ITestInvocationListener getInvocationListener();

    /**
     * Callback when a test run is started.
     *
     * @param runData the {@link DeviceMetricData} holding the data for the run.
     */
    public void onTestRunStart(DeviceMetricData runData);

    /**
     * Callback when a test run is ended. This should be the time for clean up.
     *
     * @param runData the {@link DeviceMetricData} holding the data for the run. Will be the same
     *     object as during {@link #onTestRunStart(DeviceMetricData)}.
     */
    public void onTestRunEnd(DeviceMetricData runData);
}
