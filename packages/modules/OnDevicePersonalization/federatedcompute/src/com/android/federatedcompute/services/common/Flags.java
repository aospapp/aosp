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

package com.android.federatedcompute.services.common;

/** FederatedCompute feature flags interface. This Flags interface hold the default values */
public interface Flags {

    /** Flags for {@link FederatedComputeJobManager}. */
    long DEFAULT_SCHEDULING_PERIOD_SECS = 60 * 5; // 5 minutes

    default long getDefaultSchedulingPeriodSecs() {
        return DEFAULT_SCHEDULING_PERIOD_SECS;
    }

    long MIN_SCHEDULING_INTERVAL_SECS_FOR_FEDERATED_COMPUTATION = 1 * 60; // 1 min

    default long getMinSchedulingIntervalSecsForFederatedComputation() {
        return MIN_SCHEDULING_INTERVAL_SECS_FOR_FEDERATED_COMPUTATION;
    }

    long MAX_SCHEDULING_INTERVAL_SECS_FOR_FEDERATED_COMPUTATION =
            6 * 24 * 60 * 60; // 6 days (< default ttl 7d)

    default long getMaxSchedulingIntervalSecsForFederatedComputation() {
        return MAX_SCHEDULING_INTERVAL_SECS_FOR_FEDERATED_COMPUTATION;
    }

    long MAX_SCHEDULING_PERIOD_SECS = 60 * 60 * 24 * 2; // 2 days

    default long getMaxSchedulingPeriodSecs() {
        return MAX_SCHEDULING_PERIOD_SECS;
    }

    long TRAINING_TIME_FOR_LIVE_SECONDS = 7 * 24 * 60 * 60; // one week

    default long getTrainingTimeForLiveSeconds() {
        return TRAINING_TIME_FOR_LIVE_SECONDS;
    }

    long TRAINING_SERVICE_RESULT_CALLBACK_TIMEOUT_SEC =
            60 * 9 + 45; // 9 minutes 45 seconds, leaving ~15 seconds to clean up.

    default long getTrainingServiceResultCallbackTimeoutSecs() {
        return TRAINING_SERVICE_RESULT_CALLBACK_TIMEOUT_SEC;
    }

    float TRANSIENT_ERROR_RETRY_DELAY_JITTER_PERCENT = 0.2f;

    default float getTransientErrorRetryDelayJitterPercent() {
        return TRANSIENT_ERROR_RETRY_DELAY_JITTER_PERCENT;
    }

    long TRANSIENT_ERROR_RETRY_DELAY_SECS = 15 * 60; // 15 minutes

    default long getTransientErrorRetryDelaySecs() {
        return TRANSIENT_ERROR_RETRY_DELAY_SECS;
    }

    /** Flags for {@link FederatedExampleIterator}. */
    long APP_HOSTED_EXAMPLE_STORE_TIMEOUT_SECS = 30;

    default long getAppHostedExampleStoreTimeoutSecs() {
        return APP_HOSTED_EXAMPLE_STORE_TIMEOUT_SECS;
    }

    /** Flags for ResultHandlingService. */
    long RESULT_HANDLING_BIND_SERVICE_TIMEOUT_SECS = 10;

    default long getResultHandlingBindServiceTimeoutSecs() {
        return RESULT_HANDLING_BIND_SERVICE_TIMEOUT_SECS;
    }

    // 9 minutes 45 seconds, leaving ~15 seconds to clean up.
    long RESULT_HANDLING_SERVICE_CALLBACK_TIMEOUT_SECS = 60 * 9 + 45;

    default long getResultHandlingServiceCallbackTimeoutSecs() {
        return RESULT_HANDLING_SERVICE_CALLBACK_TIMEOUT_SECS;
    }
}
