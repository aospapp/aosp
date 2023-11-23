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

import android.car.telemetry.MetricsConfigKey;

import com.android.car.telemetry.TelemetryProto;

/** Interface for the data path. Handles data forwarding from publishers to subscribers */
public interface DataBroker {

    /**
     * Interface for receiving notification that script finished.
     */
    interface ScriptFinishedCallback {
        /**
         * Listens to script finished event.
         *
         * @param key that uniquely identifies the config whose script finished.
         */
        void onScriptFinished(MetricsConfigKey key);
    }

    /**
     * Adds an active {@link com.android.car.telemetry.TelemetryProto.MetricsConfig} that is pending
     * execution. When updating the MetricsConfig to a newer version, the caller must call
     * {@link #removeMetricsConfig(String)} first to clear the old MetricsConfig.
     * TODO(b/191378559): Define behavior when metricsConfig contains invalid config
     *
     * @param key the unique identifier of the MetricsConfig.
     * @param metricsConfig to be added and queued for execution.
     */
    void addMetricsConfig(MetricsConfigKey key, TelemetryProto.MetricsConfig metricsConfig);

    /**
     * Removes a {@link com.android.car.telemetry.TelemetryProto.MetricsConfig} and all its
     * relevant subscriptions.
     *
     * @param key the unique identifier of the MetricsConfig to be removed.
     */
    void removeMetricsConfig(MetricsConfigKey key);

    /**
     * Removes all {@link com.android.car.telemetry.TelemetryProto.MetricsConfig}s and
     * subscriptions.
     */
    void removeAllMetricsConfigs();

    /**
     * Adds a {@link ScriptExecutionTask} to the priority queue. This method will schedule the
     * next task if a task is not currently running.
     */
    void addTaskToQueue(ScriptExecutionTask task);

    /**
     * Checks system health state and executes a task if condition allows.
     */
    void scheduleNextTask();

    /**
     * Sets callback for notifying script finished.
     *
     * @param callback script finished callback.
     */
    void setOnScriptFinishedCallback(ScriptFinishedCallback callback);

    /**
     * Sets the priority which affects which subscribers can consume data. Invoked by controller to
     * indicate system health state and which subscribers can be consumed. If controller does not
     * set the priority, it will be defaulted to 1. A smaller priority number indicates higher
     * priority. Range 0 - 100. A priority of 0 means the script should run regardless of system
     * health conditions.
     */
    void setTaskExecutionPriority(int priority);
}
