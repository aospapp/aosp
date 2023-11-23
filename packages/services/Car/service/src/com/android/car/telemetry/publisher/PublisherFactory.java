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

import android.os.Handler;

import com.android.car.CarPropertyService;
import com.android.car.telemetry.TelemetryProto;

import java.io.File;

/**
 * Lazy factory class for Publishers. It's expected to have a single factory instance.
 * Must be called from the telemetry thread.
 *
 * <p>It doesn't instantiate all the publishers right away, as in some cases some publishers are
 * not needed.
 *
 * <p>Methods in this class must be called on telemetry thread unless specified as thread-safe.
 */
public class PublisherFactory {
    private final Object mLock = new Object();
    private final CarPropertyService mCarPropertyService;
    private final File mPublisherDirectory;
    private final Handler mTelemetryHandler;
    private final StatsManagerProxy mStatsManager;
    private VehiclePropertyPublisher mVehiclePropertyPublisher;
    private CarTelemetrydPublisher mCarTelemetrydPublisher;
    private StatsPublisher mStatsPublisher;

    private AbstractPublisher.PublisherFailureListener mFailureListener;

    public PublisherFactory(
            CarPropertyService carPropertyService,
            Handler handler,
            StatsManagerProxy statsManager,
            File publisherDirectory) {
        mCarPropertyService = carPropertyService;
        mTelemetryHandler = handler;
        mStatsManager = statsManager;
        mPublisherDirectory = publisherDirectory;
    }

    /** Returns the publisher by given type. This method is thread-safe. */
    public AbstractPublisher getPublisher(TelemetryProto.Publisher.PublisherCase type) {
        // No need to optimize locks, as this method is infrequently called.
        synchronized (mLock) {
            switch (type.getNumber()) {
                case TelemetryProto.Publisher.VEHICLE_PROPERTY_FIELD_NUMBER:
                    if (mVehiclePropertyPublisher == null) {
                        mVehiclePropertyPublisher = new VehiclePropertyPublisher(
                                mCarPropertyService, mFailureListener, mTelemetryHandler);
                    }
                    return mVehiclePropertyPublisher;
                case TelemetryProto.Publisher.CARTELEMETRYD_FIELD_NUMBER:
                    if (mCarTelemetrydPublisher == null) {
                        mCarTelemetrydPublisher = new CarTelemetrydPublisher(
                                mFailureListener, mTelemetryHandler);
                    }
                    return mCarTelemetrydPublisher;
                case TelemetryProto.Publisher.STATS_FIELD_NUMBER:
                    if (mStatsPublisher == null) {
                        mStatsPublisher = new StatsPublisher(
                                mFailureListener, mStatsManager, mPublisherDirectory,
                                mTelemetryHandler);
                    }
                    return mStatsPublisher;
                default:
                    throw new IllegalArgumentException(
                            "Publisher type " + type + " is not supported");
            }
        }
    }

    /**
     * Removes all {@link com.android.car.telemetry.databroker.DataSubscriber} from all publishers.
     */
    public void removeAllDataSubscribers() {
        if (mVehiclePropertyPublisher != null) {
            mVehiclePropertyPublisher.removeAllDataSubscribers();
        }
        if (mCarTelemetrydPublisher != null) {
            mCarTelemetrydPublisher.removeAllDataSubscribers();
        }
        if (mStatsPublisher != null) {
            mStatsPublisher.removeAllDataSubscribers();
        }
    }

    /**
     * Sets the publisher failure listener for all the publishers. This is expected to be called
     * before {@link #getPublisher} method, because the listener is set after
     * {@code PublisherFactory} initialized. This is not the best approach, but it suits for this
     * case.
     */
    public void setFailureListener(AbstractPublisher.PublisherFailureListener listener) {
        mFailureListener = listener;
    }
}
