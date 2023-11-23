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

package android.car.telemetry;

import android.annotation.CallbackExecutor;
import android.annotation.IntDef;
import android.annotation.NonNull;
import android.annotation.RequiresPermission;
import android.car.Car;
import android.car.CarManagerBase;
import android.car.annotation.RequiredFeature;
import android.os.Binder;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Slog;

import com.android.internal.annotations.GuardedBy;

import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.ref.WeakReference;
import java.util.concurrent.Executor;

/**
 * Provides an application interface for interacting with the Car Telemetry Service.
 *
 * @hide
 */
@RequiredFeature(Car.CAR_TELEMETRY_SERVICE)
public final class CarTelemetryManager extends CarManagerBase {

    private static final boolean DEBUG = false;
    private static final String TAG = CarTelemetryManager.class.getSimpleName();
    private static final int METRICS_CONFIG_MAX_SIZE_BYTES = 10 * 1024; // 10 kb

    private final CarTelemetryServiceListener mCarTelemetryServiceListener =
            new CarTelemetryServiceListener(this);
    private final ICarTelemetryService mService;
    private final Object mLock = new Object();

    @GuardedBy("mLock")
    private CarTelemetryResultsListener mResultsListener;
    @GuardedBy("mLock")
    private Executor mExecutor;

    /**
     * Status to indicate that MetricsConfig was added successfully.
     */
    public static final int ERROR_METRICS_CONFIG_NONE = 0;

    /**
     * Status to indicate that add MetricsConfig failed because the same MetricsConfig based on the
     * ManifestKey already exists.
     */
    public static final int ERROR_METRICS_CONFIG_ALREADY_EXISTS = 1;

    /**
     * Status to indicate that add MetricsConfig failed because a newer version of the MetricsConfig
     * exists.
     */
    public static final int ERROR_METRICS_CONFIG_VERSION_TOO_OLD = 2;

    /**
     * Status to indicate that add MetricsConfig failed because CarTelemetryService is unable to
     * parse the given byte array into a MetricsConfig.
     */
    public static final int ERROR_METRICS_CONFIG_PARSE_FAILED = 3;

    /**
     * Status to indicate that add MetricsConfig failed because of failure to verify the signature
     * of the MetricsConfig.
     */
    public static final int ERROR_METRICS_CONFIG_SIGNATURE_VERIFICATION_FAILED = 4;

    /**
     * Status to indicate that add MetricsConfig failed because of a general error in cars.
     */
    public static final int ERROR_METRICS_CONFIG_UNKNOWN = 5;

    /** @hide */
    @IntDef(prefix = {"ERROR_METRICS_CONFIG_"}, value = {
            ERROR_METRICS_CONFIG_NONE,
            ERROR_METRICS_CONFIG_ALREADY_EXISTS,
            ERROR_METRICS_CONFIG_VERSION_TOO_OLD,
            ERROR_METRICS_CONFIG_PARSE_FAILED,
            ERROR_METRICS_CONFIG_SIGNATURE_VERIFICATION_FAILED,
            ERROR_METRICS_CONFIG_UNKNOWN
    })
    @Retention(RetentionPolicy.SOURCE)
    public @interface MetricsConfigError {
    }

    /**
     * Application registers {@link CarTelemetryResultsListener} object to receive data from
     * {@link com.android.car.telemetry.CarTelemetryService}.
     *
     * @hide
     */
    public interface CarTelemetryResultsListener {
        /**
         * Sends script results to the client. Called by {@link CarTelemetryServiceListener}.
         *
         * TODO(b/184964661): Publish the documentation for the format of the results.
         *
         * @param key    the {@link MetricsConfigKey} that the result is associated with.
         * @param result the car telemetry result as serialized bytes.
         */
        void onResult(@NonNull MetricsConfigKey key, @NonNull byte[] result);

        /**
         * Sends script execution errors to the client.
         *
         * @param key   the {@link MetricsConfigKey} that the error is associated with
         * @param error the serialized car telemetry error.
         */
        void onError(@NonNull MetricsConfigKey key, @NonNull byte[] error);

        /**
         * Sends the {@link #addMetricsConfig(MetricsConfigKey, byte[])} status to the client.
         *
         * @param key        the {@link MetricsConfigKey} that the status is associated with
         * @param statusCode See {@link MetricsConfigError}.
         */
        void onAddMetricsConfigStatus(@NonNull MetricsConfigKey key,
                @MetricsConfigError int statusCode);
    }

    /**
     * Class implementing the listener interface
     * {@link com.android.car.ICarTelemetryServiceListener} to receive telemetry results.
     */
    private static final class CarTelemetryServiceListener
            extends ICarTelemetryServiceListener.Stub {
        private WeakReference<CarTelemetryManager> mManager;

        private CarTelemetryServiceListener(CarTelemetryManager manager) {
            mManager = new WeakReference<>(manager);
        }

        @Override
        public void onResult(@NonNull MetricsConfigKey key, @NonNull byte[] result) {
            CarTelemetryManager manager = mManager.get();
            if (manager == null) {
                return;
            }
            manager.onResult(key, result);
        }

        @Override
        public void onError(@NonNull MetricsConfigKey key, @NonNull byte[] error) {
            CarTelemetryManager manager = mManager.get();
            if (manager == null) {
                return;
            }
            manager.onError(key, error);
        }

        @Override
        public void onAddMetricsConfigStatus(@NonNull MetricsConfigKey key,
                @MetricsConfigError int statusCode) {
            CarTelemetryManager manager = mManager.get();
            if (manager == null) {
                return;
            }
            manager.onAddMetricsConfigStatus(key, statusCode);
        }
    }

    private void onResult(MetricsConfigKey key, byte[] result) {
        long token = Binder.clearCallingIdentity();
        Executor executor = getExecutor();
        if (executor == null) {
            return;
        }
        executor.execute(() -> {
            CarTelemetryResultsListener listener = getResultsListener();
            if (listener != null) {
                listener.onResult(key, result);
            }
        });
        Binder.restoreCallingIdentity(token);
    }

    private void onError(MetricsConfigKey key, byte[] error) {
        long token = Binder.clearCallingIdentity();
        Executor executor = getExecutor();
        if (executor == null) {
            return;
        }
        executor.execute(() -> {
            CarTelemetryResultsListener listener = getResultsListener();
            if (listener != null) {
                listener.onError(key, error);
            }
        });
        Binder.restoreCallingIdentity(token);
    }

    private void onAddMetricsConfigStatus(MetricsConfigKey key, int statusCode) {
        long token = Binder.clearCallingIdentity();
        Executor executor = getExecutor();
        if (executor == null) {
            return;
        }
        executor.execute(() -> {
            CarTelemetryResultsListener listener = getResultsListener();
            if (listener != null) {
                listener.onAddMetricsConfigStatus(key, statusCode);
            }
        });
        Binder.restoreCallingIdentity(token);
    }

    /**
     * Gets an instance of CarTelemetryManager.
     *
     * CarTelemetryManager manages {@link com.android.car.telemetry.CarTelemetryService} and
     * provides APIs so the client can use the car telemetry service.
     *
     * There is only one client to this manager, which is OEM's cloud application. It uses the
     * APIs to send config to and receive data from CarTelemetryService.
     *
     * @hide
     */
    public CarTelemetryManager(Car car, IBinder service) {
        super(car);
        mService = ICarTelemetryService.Stub.asInterface(service);
        if (DEBUG) {
            Slog.d(TAG, "starting car telemetry manager");
        }
    }

    /** @hide */
    @Override
    public void onCarDisconnected() {
        synchronized (mLock) {
            mResultsListener = null;
            mExecutor = null;
        }
    }

    /**
     * Registers a listener with {@link com.android.car.telemetry.CarTelemetryService} for client
     * to receive script execution results. The listener must be set before invoking other APIs in
     * this class.
     *
     * @param listener to received data from {@link com.android.car.telemetry.CarTelemetryService}.
     * @throws IllegalStateException if the listener is already set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void setListener(@NonNull @CallbackExecutor Executor executor,
            @NonNull CarTelemetryResultsListener listener) {
        synchronized (mLock) {
            if (mResultsListener != null) {
                throw new IllegalStateException(
                        "Attempting to set a listener that is already set.");
            }
            mExecutor = executor;
            mResultsListener = listener;
        }
        try {
            mService.setListener(mCarTelemetryServiceListener);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Unregisters the listener from {@link com.android.car.telemetry.CarTelemetryService}.
     *
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void clearListener() {
        synchronized (mLock) {
            mResultsListener = null;
            mExecutor = null;
        }
        try {
            mService.clearListener();
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Sends a telemetry MetricsConfig to CarTelemetryService. The size of the MetricsConfig cannot
     * exceed a predefined size, otherwise an exception is thrown.
     * The {@link MetricsConfigKey} is used to uniquely identify a MetricsConfig. If a MetricsConfig
     * of the same name already exists in {@link com.android.car.telemetry.CarTelemetryService},
     * the config version will be compared. If the version is strictly higher, the existing
     * MetricsConfig will be replaced by the new one. All legacy data will be cleared if replaced.
     * Client should use {@link #sendFinishedReports(MetricsConfigKey)} to get the result before
     * replacing a MetricsConfig.
     * The status of this API is sent back asynchronously via {@link CarTelemetryResultsListener}.
     *
     * @param key           the unique key to identify the MetricsConfig.
     * @param metricsConfig the serialized bytes of a MetricsConfig object.
     * @throws IllegalArgumentException if the MetricsConfig size exceeds limit.
     * @throws IllegalStateException    if the listener is not set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void addMetricsConfig(@NonNull MetricsConfigKey key, @NonNull byte[] metricsConfig) {
        if (getResultsListener() == null) {
            throw new IllegalStateException("Listener must be set.");
        }
        if (metricsConfig.length > METRICS_CONFIG_MAX_SIZE_BYTES) {
            throw new IllegalArgumentException("MetricsConfig size exceeds limit.");
        }
        try {
            mService.addMetricsConfig(key, metricsConfig);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Removes a MetricsConfig from {@link com.android.car.telemetry.CarTelemetryService}. This
     * will also remove outputs produced by the MetricsConfig. If the MetricsConfig does not exist,
     * nothing will be removed.
     *
     * @param key the unique key to identify the MetricsConfig. Name and version must be exact.
     * @throws IllegalStateException if the listener is not set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void removeMetricsConfig(@NonNull MetricsConfigKey key) {
        if (getResultsListener() == null) {
            throw new IllegalStateException("Listener must be set.");
        }
        try {
            mService.removeMetricsConfig(key);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Removes all MetricsConfigs from {@link com.android.car.telemetry.CarTelemetryService}. This
     * will also remove all MetricsConfig outputs.
     *
     * @throws IllegalStateException if the listener is not set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void removeAllMetricsConfigs() {
        if (getResultsListener() == null) {
            throw new IllegalStateException("Listener must be set.");
        }
        try {
            mService.removeAllMetricsConfigs();
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Gets script execution results of a MetricsConfig as from the
     * {@link com.android.car.telemetry.CarTelemetryService}. This API is asynchronous and the
     * result is sent back asynchronously via the {@link CarTelemetryResultsListener}.
     * This call is destructive. The returned results will be deleted from CarTelemetryService.
     *
     * @param key the unique key to identify the MetricsConfig.
     * @throws IllegalStateException if the listener is not set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void sendFinishedReports(@NonNull MetricsConfigKey key) {
        if (getResultsListener() == null) {
            throw new IllegalStateException("Listener must be set.");
        }
        try {
            mService.sendFinishedReports(key);
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    /**
     * Gets all script execution results from {@link com.android.car.telemetry.CarTelemetryService}
     * asynchronously via the {@link CarTelemetryResultsListener}.
     * This call is destructive. The returned results will be deleted from CarTelemetryService.
     *
     * @throws IllegalStateException if the listener is not set.
     * @hide
     */
    @RequiresPermission(Car.PERMISSION_USE_CAR_TELEMETRY_SERVICE)
    public void sendAllFinishedReports() {
        if (getResultsListener() == null) {
            throw new IllegalStateException("Listener must be set.");
        }
        try {
            mService.sendAllFinishedReports();
        } catch (RemoteException e) {
            handleRemoteExceptionFromCarService(e);
        }
    }

    private CarTelemetryResultsListener getResultsListener() {
        synchronized (mLock) {
            return mResultsListener;
        }
    }

    private Executor getExecutor() {
        synchronized (mLock) {
            return mExecutor;
        }
    }
}
