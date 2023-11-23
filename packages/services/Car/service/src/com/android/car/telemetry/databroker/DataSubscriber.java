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

import android.os.PersistableBundle;
import android.os.SystemClock;

import com.android.car.telemetry.TelemetryProto;

import java.util.Objects;

/**
 * Subscriber class that receives published data and schedules tasks for execution.
 * All methods of this class must be accessed on telemetry thread.
 */
public class DataSubscriber {

    private final DataBroker mDataBroker;
    private final TelemetryProto.MetricsConfig mMetricsConfig;
    private final TelemetryProto.Subscriber mSubscriber;

    public DataSubscriber(
            DataBroker dataBroker,
            TelemetryProto.MetricsConfig metricsConfig,
            TelemetryProto.Subscriber subscriber) {
        mDataBroker = dataBroker;
        mMetricsConfig = metricsConfig;
        mSubscriber = subscriber;
    }

    /** Returns the handler function name for this subscriber. */
    public String getHandlerName() {
        return mSubscriber.getHandler();
    }

    /**
     * Returns the publisher param {@link TelemetryProto.Publisher} that
     * contains the data source and the config.
     */
    public TelemetryProto.Publisher getPublisherParam() {
        return mSubscriber.getPublisher();
    }

    /**
     * Creates a {@link ScriptExecutionTask} and pushes it to the priority queue where the task
     * will be pending execution. Flag isLargeData indicates whether data is large.
     */
    public void push(PersistableBundle data, boolean isLargeData) {
        ScriptExecutionTask task = new ScriptExecutionTask(
                this, data, SystemClock.elapsedRealtime(), isLargeData);
        mDataBroker.addTaskToQueue(task);
    }

    /**
     * Creates a {@link ScriptExecutionTask} and pushes it to the priority queue where the task
     * will be pending execution. Defaults isLargeData flag to false.
     */
    public void push(PersistableBundle data) {
        push(data, false);
    }

    /** Returns the {@link TelemetryProto.MetricsConfig}. */
    public TelemetryProto.MetricsConfig getMetricsConfig() {
        return mMetricsConfig;
    }

    /** Returns the {@link TelemetryProto.Subscriber}. */
    public TelemetryProto.Subscriber getSubscriber() {
        return mSubscriber;
    }

    /** Returns the priority of subscriber. */
    public int getPriority() {
        return mSubscriber.getPriority();
    }

    @Override
    public boolean equals(Object o) {
        if (!(o instanceof DataSubscriber)) {
            return false;
        }
        DataSubscriber other = (DataSubscriber) o;
        return mMetricsConfig.getName().equals(other.getMetricsConfig().getName())
                && mMetricsConfig.getVersion() == other.getMetricsConfig().getVersion()
                && mSubscriber.getHandler().equals(other.getSubscriber().getHandler());
    }

    @Override
    public int hashCode() {
        return Objects.hash(mMetricsConfig.getName(), mMetricsConfig.getVersion(),
                mSubscriber.getHandler());
    }
}
