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
package com.android.car.telemetry;

import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_NONE;
import static android.car.telemetry.CarTelemetryManager.ERROR_METRICS_CONFIG_PARSE_FAILED;

import android.annotation.NonNull;
import android.app.StatsManager;
import android.car.Car;
import android.car.telemetry.ICarTelemetryService;
import android.car.telemetry.ICarTelemetryServiceListener;
import android.car.telemetry.MetricsConfigKey;
import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.IndentingPrintWriter;

import com.android.car.CarLocalServices;
import com.android.car.CarLog;
import com.android.car.CarPropertyService;
import com.android.car.CarServiceBase;
import com.android.car.CarServiceUtils;
import com.android.car.systeminterface.SystemInterface;
import com.android.car.telemetry.databroker.DataBroker;
import com.android.car.telemetry.databroker.DataBrokerController;
import com.android.car.telemetry.databroker.DataBrokerImpl;
import com.android.car.telemetry.publisher.PublisherFactory;
import com.android.car.telemetry.publisher.StatsManagerImpl;
import com.android.car.telemetry.publisher.StatsManagerProxy;
import com.android.car.telemetry.systemmonitor.SystemMonitor;
import com.android.internal.annotations.VisibleForTesting;
import com.android.server.utils.Slogf;

import com.google.protobuf.InvalidProtocolBufferException;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;

/**
 * CarTelemetryService manages OEM telemetry collection, processing and communication
 * with a data upload service.
 */
public class CarTelemetryService extends ICarTelemetryService.Stub implements CarServiceBase {

    private static final boolean DEBUG = false;
    private static final String PUBLISHER_DIR = "publisher";
    public static final String TELEMETRY_DIR = "telemetry";

    private final Context mContext;
    private final CarPropertyService mCarPropertyService;
    private final HandlerThread mTelemetryThread = CarServiceUtils.getHandlerThread(
            CarTelemetryService.class.getSimpleName());
    private final Handler mTelemetryHandler = new Handler(mTelemetryThread.getLooper());

    private ICarTelemetryServiceListener mListener;
    private DataBroker mDataBroker;
    private DataBrokerController mDataBrokerController;
    private MetricsConfigStore mMetricsConfigStore;
    private PublisherFactory mPublisherFactory;
    private ResultStore mResultStore;
    private StatsManagerProxy mStatsManagerProxy;
    private SystemMonitor mSystemMonitor;

    public CarTelemetryService(Context context, CarPropertyService carPropertyService) {
        mContext = context;
        mCarPropertyService = carPropertyService;
    }

    @Override
    public void init() {
        mTelemetryHandler.post(() -> {
            SystemInterface systemInterface = CarLocalServices.getService(SystemInterface.class);
            // full root directory path is /data/system/car/telemetry
            File rootDirectory = new File(systemInterface.getSystemCarDir(), TELEMETRY_DIR);
            File publisherDirectory = new File(rootDirectory, PUBLISHER_DIR);
            publisherDirectory.mkdirs();
            // initialize all necessary components
            mMetricsConfigStore = new MetricsConfigStore(rootDirectory);
            mResultStore = new ResultStore(rootDirectory);
            mStatsManagerProxy = new StatsManagerImpl(
                    mContext.getSystemService(StatsManager.class));
            mPublisherFactory = new PublisherFactory(mCarPropertyService, mTelemetryHandler,
                    mStatsManagerProxy, publisherDirectory);
            mDataBroker = new DataBrokerImpl(mContext, mPublisherFactory, mResultStore);
            mSystemMonitor = SystemMonitor.create(mContext, mTelemetryHandler);
            // controller starts metrics collection after boot complete
            mDataBrokerController = new DataBrokerController(mDataBroker, mTelemetryHandler,
                    mMetricsConfigStore, mSystemMonitor,
                    systemInterface.getSystemStateInterface());
        });
    }

    @Override
    public void release() {
        // TODO(b/197969149): prevent threading issue, block main thread
        mTelemetryHandler.post(() -> mResultStore.flushToDisk());
    }

    @Override
    public void dump(IndentingPrintWriter writer) {
        writer.println("Car Telemetry service");
    }

    /**
     * Registers a listener with CarTelemetryService for the service to send data to cloud app.
     */
    @Override
    public void setListener(@NonNull ICarTelemetryServiceListener listener) {
        // TODO(b/184890506): verify that only a hardcoded app can set the listener
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "setListener");
        mTelemetryHandler.post(() -> {
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY, "Setting the listener for car telemetry service");
            }
            mListener = listener;
        });
    }

    /**
     * Clears the listener registered with CarTelemetryService.
     */
    @Override
    public void clearListener() {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "clearListener");
        mTelemetryHandler.post(() -> {
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY, "Clearing the listener for car telemetry service");
            }
            mListener = null;
        });
    }

    /**
     * Send a telemetry metrics config to the service. This method assumes
     * {@link #setListener(ICarTelemetryServiceListener)} is called. Otherwise it does nothing.
     *
     * @param key    the unique key to identify the MetricsConfig.
     * @param config the serialized bytes of a MetricsConfig object.
     */
    @Override
    public void addMetricsConfig(@NonNull MetricsConfigKey key, @NonNull byte[] config) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "addMetricsConfig");
        mTelemetryHandler.post(() -> {
            if (mListener == null) {
                Slogf.w(CarLog.TAG_TELEMETRY, "ICarTelemetryServiceListener is not set");
                return;
            }
            Slogf.d(CarLog.TAG_TELEMETRY, "Adding metrics config: " + key.getName()
                    + " to car telemetry service");
            TelemetryProto.MetricsConfig metricsConfig = null;
            int status;
            try {
                metricsConfig = TelemetryProto.MetricsConfig.parseFrom(config);
                status = mMetricsConfigStore.addMetricsConfig(metricsConfig);
            } catch (InvalidProtocolBufferException e) {
                Slogf.e(CarLog.TAG_TELEMETRY, "Failed to parse MetricsConfig.", e);
                status = ERROR_METRICS_CONFIG_PARSE_FAILED;
            }
            // If no error (config is added to MetricsConfigStore), remove legacy data and add
            // config to data broker for metrics collection
            if (status == ERROR_METRICS_CONFIG_NONE) {
                mResultStore.removeResult(key);
                mDataBroker.removeMetricsConfig(key);
                mDataBroker.addMetricsConfig(key, metricsConfig);
                // TODO(b/199410900): update logic once metrics configs have expiration dates
            }
            try {
                mListener.onAddMetricsConfigStatus(key, status);
            } catch (RemoteException e) {
                Slogf.w(CarLog.TAG_TELEMETRY, "error with ICarTelemetryServiceListener", e);
            }
        });
    }

    /**
     * Removes a metrics config based on the key. This will also remove outputs produced by the
     * MetricsConfig.
     *
     * @param key the unique identifier of a MetricsConfig.
     */
    @Override
    public void removeMetricsConfig(@NonNull MetricsConfigKey key) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "removeMetricsConfig");
        mTelemetryHandler.post(() -> {
            Slogf.d(CarLog.TAG_TELEMETRY, "Removing metrics config " + key.getName()
                    + " from car telemetry service");
            if (mMetricsConfigStore.removeMetricsConfig(key)) {
                mDataBroker.removeMetricsConfig(key);
                mResultStore.removeResult(key);
            }
        });
    }

    /**
     * Removes all MetricsConfigs. This will also remove all MetricsConfig outputs.
     */
    @Override
    public void removeAllMetricsConfigs() {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "removeAllMetricsConfigs");
        mTelemetryHandler.post(() -> {
            Slogf.d(CarLog.TAG_TELEMETRY,
                    "Removing all metrics config from car telemetry service");
            mDataBroker.removeAllMetricsConfigs();
            mMetricsConfigStore.removeAllMetricsConfigs();
            mResultStore.removeAllResults();
        });
    }

    /**
     * Sends script results associated with the given key using the
     * {@link ICarTelemetryServiceListener}. This method assumes listener is set. Otherwise it
     * does nothing.
     *
     * @param key the unique identifier of a MetricsConfig.
     */
    @Override
    public void sendFinishedReports(@NonNull MetricsConfigKey key) {
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "sendFinishedReports");
        mTelemetryHandler.post(() -> {
            if (mListener == null) {
                Slogf.w(CarLog.TAG_TELEMETRY, "ICarTelemetryServiceListener is not set");
                return;
            }
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY,
                        "Flushing reports for metrics config " + key.getName());
            }
            PersistableBundle result = mResultStore.getFinalResult(key.getName(), true);
            TelemetryProto.TelemetryError error = mResultStore.getError(key.getName(), true);
            if (result != null) {
                sendFinalResult(key, result);
            } else if (error != null) {
                sendError(key, error);
            } else {
                Slogf.w(CarLog.TAG_TELEMETRY, "config " + key.getName()
                        + " did not produce any results");
            }
        });
    }

    /**
     * Sends all script results or errors using the {@link ICarTelemetryServiceListener}.
     */
    @Override
    public void sendAllFinishedReports() {
        // TODO(b/184087869): Implement
        mContext.enforceCallingOrSelfPermission(
                Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE, "sendAllFinishedReports");
        if (DEBUG) {
            Slogf.d(CarLog.TAG_TELEMETRY, "Flushing all reports");
        }
    }

    private void sendFinalResult(MetricsConfigKey key, PersistableBundle result) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            result.writeToStream(bos);
            mListener.onResult(key, bos.toByteArray());
        } catch (RemoteException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "error with ICarTelemetryServiceListener", e);
        } catch (IOException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "failed to write bundle to output stream", e);
        }
    }

    private void sendError(MetricsConfigKey key, TelemetryProto.TelemetryError error) {
        try {
            mListener.onError(key, error.toByteArray());
        } catch (RemoteException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "error with ICarTelemetryServiceListener", e);
        }
    }

    @VisibleForTesting
    Handler getTelemetryHandler() {
        return mTelemetryHandler;
    }

    @VisibleForTesting
    ResultStore getResultStore() {
        return mResultStore;
    }

    @VisibleForTesting
    MetricsConfigStore getMetricsConfigStore() {
        return mMetricsConfigStore;
    }
}
