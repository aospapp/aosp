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
import android.car.VehiclePropertyIds;
import android.car.builtin.util.Slogf;
import android.car.hardware.CarPropertyConfig;
import android.car.hardware.property.CarPropertyEvent;
import android.car.hardware.property.ICarPropertyEventListener;
import android.car.telemetry.TelemetryProto;
import android.car.telemetry.TelemetryProto.Publisher.PublisherCase;
import android.os.Handler;
import android.os.PersistableBundle;
import android.os.RemoteException;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.CarPropertyService;
import com.android.car.telemetry.databroker.DataSubscriber;
import com.android.car.telemetry.sessioncontroller.SessionAnnotation;
import com.android.internal.util.Preconditions;

import java.util.List;

/**
 * Publisher for Vehicle Property changes, aka {@code CarPropertyService}.
 *
 * <p> When a subscriber is added, it registers a car property change listener for the
 * property id of the subscriber and starts pushing the change events to the subscriber.
 */
public class VehiclePropertyPublisher extends AbstractPublisher {
    private static final boolean DEBUG = false;  // STOPSHIP if true

    /** Bundle key for {@link CarPropertyEvent}. */
    public static final String CAR_PROPERTY_EVENT_KEY = "car_property_event";

    private final CarPropertyService mCarPropertyService;
    private final Handler mTelemetryHandler;

    // The class only reads, no need to synchronize this object.
    // Maps property_id to CarPropertyConfig.
    private final SparseArray<CarPropertyConfig> mCarPropertyList;

    // SparseArray and ArraySet are memory optimized, but they can be bit slower for more
    // than 100 items. We're expecting much less number of subscribers, so these DS are ok.
    // Maps property_id to the set of DataSubscriber.
    private final SparseArray<ArraySet<DataSubscriber>> mCarPropertyToSubscribers =
            new SparseArray<>();

    private final ICarPropertyEventListener mCarPropertyEventListener =
            new ICarPropertyEventListener.Stub() {
                @Override
                public void onEvent(List<CarPropertyEvent> events) throws RemoteException {
                    if (DEBUG) {
                        Slogf.d(CarLog.TAG_TELEMETRY,
                                "Received " + events.size() + " vehicle property events");
                    }
                    for (CarPropertyEvent event : events) {
                        onVehicleEvent(event);
                    }
                }
            };

    public VehiclePropertyPublisher(
            @NonNull CarPropertyService carPropertyService,
            @NonNull PublisherListener listener,
            @NonNull Handler handler) {
        super(listener);
        mCarPropertyService = carPropertyService;
        mTelemetryHandler = handler;
        // Load car property list once, as the list doesn't change runtime.
        List<CarPropertyConfig> propertyList = mCarPropertyService.getPropertyList();
        mCarPropertyList = new SparseArray<>(propertyList.size());
        for (CarPropertyConfig property : propertyList) {
            mCarPropertyList.append(property.getPropertyId(), property);
        }
    }

    @Override
    public void addDataSubscriber(@NonNull DataSubscriber subscriber) {
        TelemetryProto.Publisher publisherParam = subscriber.getPublisherParam();
        Preconditions.checkArgument(
                publisherParam.getPublisherCase()
                        == TelemetryProto.Publisher.PublisherCase.VEHICLE_PROPERTY,
                "Subscribers only with VehicleProperty publisher are supported by this class.");
        int propertyId = publisherParam.getVehicleProperty().getVehiclePropertyId();
        CarPropertyConfig config = mCarPropertyList.get(propertyId);
        Preconditions.checkArgument(
                config != null,
                "Vehicle property " + VehiclePropertyIds.toString(propertyId) + " not found.");
        Preconditions.checkArgument(
                config.getAccess() == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ
                        || config.getAccess()
                        == CarPropertyConfig.VEHICLE_PROPERTY_ACCESS_READ_WRITE,
                "No access. Cannot read " + VehiclePropertyIds.toString(propertyId) + ".");

        ArraySet<DataSubscriber> subscribers = mCarPropertyToSubscribers.get(propertyId);
        if (subscribers == null) {
            subscribers = new ArraySet<>();
            mCarPropertyToSubscribers.put(propertyId, subscribers);
            // Register the listener only once per propertyId.
            mCarPropertyService.registerListener(
                    propertyId,
                    publisherParam.getVehicleProperty().getReadRate(),
                    mCarPropertyEventListener);
        }
        subscribers.add(subscriber);
    }

    @Override
    public void removeDataSubscriber(@NonNull DataSubscriber subscriber) {
        TelemetryProto.Publisher publisherParam = subscriber.getPublisherParam();
        if (publisherParam.getPublisherCase() != PublisherCase.VEHICLE_PROPERTY) {
            Slogf.w(CarLog.TAG_TELEMETRY,
                    "Expected VEHICLE_PROPERTY publisher, but received "
                            + publisherParam.getPublisherCase().name());
            return;
        }
        int propertyId = publisherParam.getVehicleProperty().getVehiclePropertyId();

        ArraySet<DataSubscriber> subscribers = mCarPropertyToSubscribers.get(propertyId);
        if (subscribers == null) {
            return;
        }
        subscribers.remove(subscriber);
        if (subscribers.isEmpty()) {
            mCarPropertyToSubscribers.remove(propertyId);
            // Doesn't throw exception as listener is not null. mCarPropertyService and
            // local mCarPropertyToSubscribers will not get out of sync.
            mCarPropertyService.unregisterListener(propertyId, mCarPropertyEventListener);
        }
    }

    @Override
    public void removeAllDataSubscribers() {
        for (int i = 0; i < mCarPropertyToSubscribers.size(); i++) {
            int propertyId = mCarPropertyToSubscribers.keyAt(i);
            // Doesn't throw exception as listener is not null. mCarPropertyService and
            // local mCarPropertyToSubscribers will not get out of sync.
            mCarPropertyService.unregisterListener(propertyId, mCarPropertyEventListener);
        }
        mCarPropertyToSubscribers.clear();
    }

    @Override
    public boolean hasDataSubscriber(@NonNull DataSubscriber subscriber) {
        TelemetryProto.Publisher publisherParam = subscriber.getPublisherParam();
        if (publisherParam.getPublisherCase() != PublisherCase.VEHICLE_PROPERTY) {
            return false;
        }
        int propertyId = publisherParam.getVehicleProperty().getVehiclePropertyId();
        ArraySet<DataSubscriber> subscribers = mCarPropertyToSubscribers.get(propertyId);
        return subscribers != null && subscribers.contains(subscriber);
    }

    /**
     * Called when publisher receives new event. It's executed on a CarPropertyService's
     * worker thread.
     */
    private void onVehicleEvent(@NonNull CarPropertyEvent event) {
        // move the work from CarPropertyService's worker thread to the telemetry thread
        mTelemetryHandler.post(() -> {
            // TODO(b/197269115): convert CarPropertyEvent into PersistableBundle
            PersistableBundle bundle = new PersistableBundle();
            ArraySet<DataSubscriber> subscribersClone = new ArraySet<>(
                    mCarPropertyToSubscribers.get(event.getCarPropertyValue().getPropertyId()));
            for (DataSubscriber subscriber : subscribersClone) {
                subscriber.push(bundle);
            }
        });
    }

    @Override
    protected void handleSessionStateChange(SessionAnnotation annotation) {}
}
