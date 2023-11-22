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
package com.android.tradefed.device.metric.target;

import com.android.tradefed.config.Option;
import com.android.tradefed.config.OptionClass;
import com.android.tradefed.device.metric.BaseDeviceMetricCollector;
import com.android.tradefed.util.MultiMap;

import java.util.ArrayList;
import java.util.List;

/**
 * Tradefed object to specify a device collector defined in:
 *
 * <p>platform_testing/libraries/device-collectors. This allows to specify these objects in the
 * Tradefed xml like the host-side ones and to set their options.
 */
@OptionClass(alias = "device-target-collector")
public final class DeviceSideCollectorSpecification {

    @Option(
            name = "collectors-qualified-name",
            description =
                    "The fully qualified name of each device side collector that wants to be added "
                            + "to the instrumentation. Example: --collectors-qualified-name "
                            + "android.device.collectors.ScreenshotListener",
            mandatory = true)
    private List<String> mCollectorQualifiedName = new ArrayList<>();

    @Option(
            name = "collector-options",
            description =
                    "Specify device side collector's options that will be given to the "
                            + "instrumentation. Can be prepended with the alias to target a "
                            + "specific collector. Example: --collector-options "
                            + "screenshot-collector:screenshot-quality 75"
                            + "or --collector-options screenshot-quality 75 (without the alias).")
    private MultiMap<String, String> mInstrumentationArgs = new MultiMap<>();

    /**
     * The filtering options are part of BaseMetricListener and shared by all device side
     * collectors, so we put them on their own to make it clear. Shares its name with the base host
     * side collectors in order to easily target both host and device side groups.
     */
    @Option(
            name = BaseDeviceMetricCollector.TEST_CASE_INCLUDE_GROUP_OPTION,
            description =
                    "The 'include-filter-group' to run the collector only against some methods.")
    private List<String> mIncludeGroupFilters = new ArrayList<>();

    @Option(
            name = BaseDeviceMetricCollector.TEST_CASE_EXCLUDE_GROUP_OPTION,
            description =
                    "The 'exclude-filter-group' to run the collector only against some methods.")
    private List<String> mExcludeGroupFilters = new ArrayList<>();

    /**
     * Returns the {@link MultiMap} of device side collector options to be added to the
     * instrumentation.
     */
    public MultiMap<String, String> getCollectorOptions() {
        return mInstrumentationArgs;
    }

    /** Returns the {@link List} of collectors to be added to the instrumentation. */
    public List<String> getCollectorNames() {
        return mCollectorQualifiedName;
    }

    /** Returns the {@link List} of groups to be included via --include-filter-group. */
    public List<String> getIncludeGroupFilters() {
        return mIncludeGroupFilters;
    }

    /** Returns the {@link List} of groups to be excluded via --exclude-filter-group. */
    public List<String> getExcludeGroupFilters() {
        return mExcludeGroupFilters;
    }
}
