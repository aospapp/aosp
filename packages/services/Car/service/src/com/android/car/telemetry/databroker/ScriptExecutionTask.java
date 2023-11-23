/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.telemetry.databroker;

import android.annotation.NonNull;
import android.car.telemetry.TelemetryProto;
import android.os.PersistableBundle;

/**
 * A wrapper class containing all the necessary information to invoke the ScriptExecutor API. It
 * is enqueued into the priority queue where it pends execution by {@link DataBroker}.
 * It implements the {@link Comparable} interface so it has a natural ordering by priority and
 * creation timestamp in the priority queue.
 * The object can be accessed from any thread. See {@link DataSubscriber} for thread-safety.
 */
public class ScriptExecutionTask implements Comparable<ScriptExecutionTask> {
    private final int mPublisherType;
    private final long mTimestampMillis;
    private final DataSubscriber mSubscriber;
    private final PersistableBundle mData;
    private final boolean mIsLargeData;

    ScriptExecutionTask(
            @NonNull DataSubscriber subscriber,
            @NonNull PersistableBundle data,
            long elapsedRealtimeMillis,
            boolean isLargeData,
            int publisherType) {
        mTimestampMillis = elapsedRealtimeMillis;
        mSubscriber = subscriber;
        mData = data;
        mIsLargeData = isLargeData;
        mPublisherType = publisherType;
    }

    public int getPublisherType() {
        return mPublisherType;
    }

    /** Returns the priority of the task. */
    public int getPriority() {
        return mSubscriber.getPriority();
    }

    /** Returns the creation timestamp of the task. */
    public long getCreationTimestampMillis() {
        return mTimestampMillis;
    }

    /** Returns the MetricsConfig associated with this task. */
    @NonNull
    public TelemetryProto.MetricsConfig getMetricsConfig() {
        return mSubscriber.getMetricsConfig();
    }

    /** Returns the handler function name defined in MetricsConfig script. */
    @NonNull
    public String getHandlerName() {
        return mSubscriber.getHandlerName();
    }

    /** Returns the data being sent to the subscriber. */
    @NonNull
    public PersistableBundle getData() {
        return mData;
    }

    /**
     * Indicates whether the task is associated with MetricsConfig specified by its name.
     */
    public boolean isAssociatedWithMetricsConfig(@NonNull String metricsConfigName) {
        return mSubscriber.getMetricsConfig().getName().equals(metricsConfigName);
    }

    /**
     * Returns the script input data size in bytes.
     */
    public boolean isLargeData() {
        return mIsLargeData;
    }

    @Override
    public int compareTo(@NonNull ScriptExecutionTask other) {
        if (getPriority() < other.getPriority()) {
            return -1;
        } else if (getPriority() > other.getPriority()) {
            return 1;
        }
        // if equal priority, compare creation timestamps
        if (getCreationTimestampMillis() < other.getCreationTimestampMillis()) {
            return -1;
        }
        return 1;
    }
}
