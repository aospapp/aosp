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

package com.android.car.telemetry.publisher;

import android.annotation.NonNull;
import android.automotive.telemetry.internal.CarDataInternal;
import android.automotive.telemetry.internal.ICarDataListener;
import android.automotive.telemetry.internal.ICarTelemetryInternal;
import android.car.builtin.os.ServiceManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.telemetry.TelemetryProto;
import android.os.Handler;
import android.os.IBinder;
import android.os.RemoteException;

import com.android.automotive.telemetry.CarDataProto;
import com.android.car.CarLog;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.car.telemetry.sessioncontroller.SessionAnnotation;
import com.android.internal.annotations.VisibleForTesting;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.stream.Collectors;

/**
 * Publisher for cartelemtryd service (aka ICarTelemetry).
 *
 * <p>When a subscriber is added, the publisher binds to ICarTelemetryInternal and starts listening
 * for incoming CarData. The matching CarData will be pushed to the subscriber. It unbinds itself
 * from ICarTelemetryInternal if there are no subscribers.
 *
 * <p>See {@code packages/services/Car/cpp/telemetry/cartelemetryd} to learn more about the service.
 */
public class CarTelemetrydPublisher extends AbstractPublisher {
    private static final boolean DEBUG = false;  // STOPSHIP if true
    private static final String SERVICE_NAME = ICarTelemetryInternal.DESCRIPTOR + "/default";
    private static final int BINDER_FLAGS = 0;

    private ICarTelemetryInternal mCarTelemetryInternal;

    private final ArrayList<DataSubscriber> mSubscribers = new ArrayList<>();

    // All the methods in this class are expected to be called on this handler's thread.
    private final Handler mTelemetryHandler;

    private final ICarDataListener mListener = new ICarDataListener.Stub() {
        @Override
        public void onCarDataReceived(
                @NonNull final CarDataInternal[] dataList) throws RemoteException {
            if (DEBUG) {
                Slogf.d(CarLog.TAG_TELEMETRY,
                        "Received " + dataList.length + " CarData from cartelemetryd");
            }
            // TODO(b/189142577): Create custom Handler and post message to improve performance
            mTelemetryHandler.post(() -> onCarDataListReceived(dataList));
        }
        @Override
        public String getInterfaceHash() {
            return ICarDataListener.HASH;
        }

        @Override
        public int getInterfaceVersion() {
            return ICarDataListener.VERSION;
        }
    };

    CarTelemetrydPublisher(
            @NonNull PublisherListener listener, @NonNull Handler telemetryHandler) {
        super(listener);
        this.mTelemetryHandler = telemetryHandler;
    }

    /** Called when binder for ICarTelemetry service is died. */
    private void onBinderDied() {
        // TODO(b/189142577): Create custom Handler and post message to improve performance
        mTelemetryHandler.post(() -> {
            if (mCarTelemetryInternal != null) {
                mCarTelemetryInternal.asBinder().unlinkToDeath(this::onBinderDied, BINDER_FLAGS);
                mCarTelemetryInternal = null;
            }
            onPublisherFailure(
                    getMetricsConfigs(),
                    new IllegalStateException("ICarTelemetryInternal binder died"));
        });
    }

    /** Connects to ICarTelemetryInternal service and starts listening for CarData. */
    private void connectToCarTelemetryd() {
        if (mCarTelemetryInternal != null) {
            return;
        }
        IBinder binder = ServiceManagerHelper.checkService(SERVICE_NAME);
        if (binder == null) {
            onPublisherFailure(
                    getMetricsConfigs(),
                    new IllegalStateException(
                            "Failed to connect to the ICarTelemetryInternal: service is not "
                                    + "ready"));
            return;
        }
        try {
            binder.linkToDeath(this::onBinderDied, BINDER_FLAGS);
        } catch (RemoteException e) {
            onPublisherFailure(
                    getMetricsConfigs(),
                    new IllegalStateException(
                            "Failed to connect to the ICarTelemetryInternal: linkToDeath failed",
                            e));
            return;
        }
        mCarTelemetryInternal = ICarTelemetryInternal.Stub.asInterface(binder);
        try {
            mCarTelemetryInternal.setListener(mListener);
        } catch (RemoteException e) {
            binder.unlinkToDeath(this::onBinderDied, BINDER_FLAGS);
            mCarTelemetryInternal = null;
            onPublisherFailure(
                    getMetricsConfigs(),
                    new IllegalStateException(
                            "Failed to connect to the ICarTelemetryInternal: Cannot set CarData "
                                    + "listener", e));
        }
    }

    @NonNull
    private ArrayList<TelemetryProto.MetricsConfig> getMetricsConfigs() {
        return new ArrayList<>(mSubscribers.stream().map(DataSubscriber::getMetricsConfig).collect(
                Collectors.toSet()));
    }

    /**
     * Disconnects from ICarTelemetryInternal service.
     *
     * @throws IllegalStateException if fails to clear the listener.
     */
    private void disconnectFromCarTelemetryd() {
        if (mCarTelemetryInternal == null) {
            return;  // already disconnected
        }
        try {
            mCarTelemetryInternal.clearListener();
        } catch (RemoteException e) {
            Slogf.w(CarLog.TAG_TELEMETRY, "Failed to remove ICarTelemetryInternal listener", e);
        }
        mCarTelemetryInternal.asBinder().unlinkToDeath(this::onBinderDied, BINDER_FLAGS);
        mCarTelemetryInternal = null;
    }

    @VisibleForTesting
    boolean isConnectedToCarTelemetryd() {
        return mCarTelemetryInternal != null;
    }

    @Override
    public void addDataSubscriber(@NonNull DataSubscriber subscriber) {
        TelemetryProto.Publisher publisherParam = subscriber.getPublisherParam();
        Preconditions.checkArgument(
                publisherParam.getPublisherCase()
                        == TelemetryProto.Publisher.PublisherCase.CARTELEMETRYD,
                "Subscribers only with CarTelemetryd publisher are supported by this class.");
        int carDataId = publisherParam.getCartelemetryd().getId();
        CarDataProto.CarData.PushedCase carDataCase =
                CarDataProto.CarData.PushedCase.forNumber(carDataId);
        Preconditions.checkArgument(
                carDataCase != null
                        && carDataCase != CarDataProto.CarData.PushedCase.PUSHED_NOT_SET,
                "Invalid CarData ID " + carDataId
                        + ". Please see CarData.proto for the list of available IDs.");

        mSubscribers.add(subscriber);

        connectToCarTelemetryd();

        Slogf.d(CarLog.TAG_TELEMETRY, "Subscribing to CarDat.id=%d", carDataId);
    }

    @Override
    public void removeDataSubscriber(@NonNull DataSubscriber subscriber) {
        mSubscribers.remove(subscriber);
        if (mSubscribers.isEmpty()) {
            disconnectFromCarTelemetryd();
        }
    }

    @Override
    public void removeAllDataSubscribers() {
        mSubscribers.clear();
        disconnectFromCarTelemetryd();
    }

    @Override
    public boolean hasDataSubscriber(@NonNull DataSubscriber subscriber) {
        return mSubscribers.contains(subscriber);
    }

    /**
     * Called when publisher receives new car data list. It's executed on the telemetry thread.
     */
    private void onCarDataListReceived(@NonNull CarDataInternal[] dataList) {
        // TODO(b/189142577): implement
    }

    @Override
    protected void handleSessionStateChange(SessionAnnotation annotation) {}
}
