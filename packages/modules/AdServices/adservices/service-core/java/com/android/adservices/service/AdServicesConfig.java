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

package com.android.adservices.service;

import java.util.concurrent.TimeUnit;

/**
 * Hard Coded Configs for AdServices.
 *
 * <p>For Feature Flags that are backed by PH, please see {@link PhFlags}
 */
public class AdServicesConfig {

    public static long getMeasurementEventMainReportingJobPeriodMs() {
        return FlagsFactory.getFlags().getMeasurementEventMainReportingJobPeriodMs();
    }

    public static long MEASUREMENT_DELETE_EXPIRED_JOB_PERIOD_MS = TimeUnit.HOURS.toMillis(24);

    /**
     * Returns the min time period (in millis) between each expired-record deletion maintenance job
     * run.
     */
    public static long getMeasurementDeleteExpiredJobPeriodMs() {
        return MEASUREMENT_DELETE_EXPIRED_JOB_PERIOD_MS;
    }

    /** Returns the min time period (in millis) between each event fallback reporting job run. */
    public static long getMeasurementEventFallbackReportingJobPeriodMs() {
        return FlagsFactory.getFlags().getMeasurementEventFallbackReportingJobPeriodMs();
    }

    /** Returns the URL for fetching public encryption keys for aggregatable reports. */
    public static String getMeasurementAggregateEncryptionKeyCoordinatorUrl() {
        return FlagsFactory.getFlags().getMeasurementAggregateEncryptionKeyCoordinatorUrl();
    }

    /** Returns the min time period (in millis) between each aggregate main reporting job run. */
    public static long getMeasurementAggregateMainReportingJobPeriodMs() {
        return FlagsFactory.getFlags().getMeasurementAggregateMainReportingJobPeriodMs();
    }

    /**
     * Returns the min time period (in millis) between each aggregate fallback reporting job run.
     */
    public static long getMeasurementAggregateFallbackReportingJobPeriodMs() {
        return FlagsFactory.getFlags().getMeasurementAggregateFallbackReportingJobPeriodMs();
    }

    /**
     * Returns the min time period (in millis) between each uninstalled-record deletion maintenance
     * job run.
     */
    public static long getMeasurementDeleteUninstalledJobPeriodMs() {
        return MEASUREMENT_DELETE_UNINSTALLED_JOB_PERIOD_MS;
    }

    public static long MEASUREMENT_DELETE_UNINSTALLED_JOB_PERIOD_MS = TimeUnit.HOURS.toMillis(24);
}
