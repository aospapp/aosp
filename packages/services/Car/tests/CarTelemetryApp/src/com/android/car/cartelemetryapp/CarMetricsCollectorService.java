/*
 * Copyright (C) 2022 The Android Open Source Project.
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
package com.android.car.cartelemetryapp;

import android.annotation.CallbackExecutor;
import android.annotation.NonNull;
import android.app.Service;
import android.car.Car;
import android.car.telemetry.CarTelemetryManager;
import android.car.telemetry.CarTelemetryManager.AddMetricsConfigCallback;
import android.car.telemetry.TelemetryProto.MetricsConfig;
import android.content.Intent;
import android.os.Binder;
import android.os.IBinder;
import android.util.Log;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executor;
import java.util.concurrent.Executors;

/**
 * Service to interface with CarTelemetryManager.
 */
public class CarMetricsCollectorService extends Service {
    private static final String TAG = CarMetricsCollectorService.class.getSimpleName();
    private static final String ASSETS_METRICS_CONFIG_FOLDER = "metricsconfigs";
    private final Executor mExecutor = Executors.newSingleThreadExecutor();
    private final IBinder mBinder = new ServiceBinder();
    private Car mCar;
    private CarTelemetryManager mCarTelemetryManager;
    private Set<String> mActiveConfigs = new HashSet<>();
    private ConfigParser mConfigParser;
    private Map<String, MetricsConfig> mConfigs;
    private Car.CarServiceLifecycleListener mCarLifecycleListener = (car, ready) -> {
        if (ready) {
            mCarTelemetryManager =
                    (CarTelemetryManager) car.getCarManager(Car.CAR_TELEMETRY_SERVICE);
        }
    };

    @Override
    public void onCreate() {
        super.onCreate();
        mCar = Car.createCar(
                getApplicationContext(),
                /* handler= */ null,
                Car.CAR_WAIT_TIMEOUT_WAIT_FOREVER,
                mCarLifecycleListener);
        mConfigParser = new ConfigParser(this.getApplicationContext());
        mConfigs = mConfigParser.getConfigs();
        addActiveConfigs();
    }

    @Override
    public void onDestroy() {
        mCar.disconnect();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    public class ServiceBinder extends Binder {
        CarMetricsCollectorService getService() {
            return CarMetricsCollectorService.this;
        }
    }

    public String dumpLogs() {
        return mConfigParser.dumpLogs();
    }

    /**
     * Get all the config names that are in the assets folder.
     *
     * They are not necessarily active.
     */
    public String[] getAllConfigNames() {
        return mConfigs.keySet().toArray(new String[0]);
    }

    /**
     * Gets the finished report.
     *
     * @param configName the name of the {@link MetricsConfig} to get the report for.
     * @param executor {@link Executor} to execute the callback in.
     * @param callback function to be called with the finished report.
     */
    public void getFinishedReport(
            @NonNull String configName,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull CarTelemetryManager.MetricsReportCallback callback) {
        mCarTelemetryManager.getFinishedReport(configName, executor, callback);
    }

    /**
     * Sets listener for getting notified when a report is ready.
     *
     * @param executor {@link Executor} to execute the listener on.
     * @param listener the callback to call when report is ready.
     */
    public void setReportReadyListener(
            @CallbackExecutor @NonNull Executor executor,
            @NonNull CarTelemetryManager.ReportReadyListener listener) {
        mCarTelemetryManager.setReportReadyListener(executor, listener);
    }

    /**
     * Adds {@link MetricsConfig} of a specific name.
     *
     * @param configName name of the {@link MetricsConfig} to add.
     * @param executor {@link Executor} to execute the add operation on.
     * @param callback the callback to call with the status of the add operation.
     */
    public void addMetricsConfig(
            @NonNull String configName,
            @CallbackExecutor @NonNull Executor executor,
            @NonNull AddMetricsConfigCallback callback) {
        if (!mConfigs.containsKey(configName)) {
            throw new IllegalArgumentException(
                    "Failed to add metrics config, name does not exist! " + configName);
        }
        mCarTelemetryManager.addMetricsConfig(
                    configName, mConfigs.get(configName).toByteArray(), mExecutor, callback);
    }

    /**
     * Removes the named metrics config.
     */
    public void removeMetricsConfig(@NonNull String configName) {
        mCarTelemetryManager.removeMetricsConfig(configName);
    }

    /**
     * Gets the active configs.
     *
     * @return activated configs.
     */
    public Set<String> getActiveConfigs() {
        return mActiveConfigs;
    }

    private void onAddMetricsConfigStatus(String metricsConfigName, int statusCode) {
        Log.i(TAG, "addMetricsConfig for " + metricsConfigName + " returned status code = "
                + addConfigStatusToString(statusCode));
        if (statusCode == CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_SUCCEEDED
                || statusCode == CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_ALREADY_EXISTS) {
            mActiveConfigs.add(metricsConfigName);
        }
    }

    private void addActiveConfigs() {
        // TODO(b/230664179): specific logic for what configs should be added
        for (String configName : mConfigs.keySet()) {
            addMetricsConfig(configName, mExecutor, this::onAddMetricsConfigStatus);
        }
    }

    private String addConfigStatusToString(int statusCode) {
        switch (statusCode) {
            case CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_SUCCEEDED:
                return "SUCCESS";
            case CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_ALREADY_EXISTS:
                return "ERROR ALREADY_EXISTS";
            case CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_VERSION_TOO_OLD:
                return "ERROR VERSION_TOO_OLD";
            case CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_PARSE_FAILED:
                return "ERROR PARSE_FAILED";
            case CarTelemetryManager.STATUS_ADD_METRICS_CONFIG_SIGNATURE_VERIFICATION_FAILED:
                return "ERROR SIGNATURE_VERIFICATION_FAILED";
            default:
                return "ERROR UNKNOWN";
        }
    }
}
