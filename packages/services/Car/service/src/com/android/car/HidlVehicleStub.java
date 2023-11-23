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

package com.android.car;

import static com.android.car.CarServiceUtils.subscribeOptionsToHidl;

import android.annotation.Nullable;
import android.car.builtin.util.Slogf;
import android.hardware.automotive.vehicle.SubscribeOptions;
import android.hardware.automotive.vehicle.V2_0.IVehicle;
import android.hardware.automotive.vehicle.V2_0.IVehicleCallback;
import android.hardware.automotive.vehicle.V2_0.StatusCode;
import android.hardware.automotive.vehicle.VehiclePropError;
import android.os.NativeHandle;
import android.os.RemoteException;
import android.os.ServiceSpecificException;
import android.os.SystemProperties;

import com.android.car.hal.HalClientCallback;
import com.android.car.hal.HalPropConfig;
import com.android.car.hal.HalPropValue;
import com.android.car.hal.HalPropValueBuilder;
import com.android.car.hal.HidlHalPropConfig;
import com.android.internal.annotations.VisibleForTesting;

import java.io.FileDescriptor;
import java.util.ArrayList;
import java.util.NoSuchElementException;

final class HidlVehicleStub extends VehicleStub {

    private final IVehicle mHidlVehicle;
    private final HalPropValueBuilder mPropValueBuilder;

    HidlVehicleStub() {
        this(getHidlVehicle());
    }

    @VisibleForTesting
    HidlVehicleStub(IVehicle hidlVehicle) {
        mHidlVehicle = hidlVehicle;
        mPropValueBuilder = new HalPropValueBuilder(/*isAidl=*/false);
    }

    /**
     * Checks whether we are connected to AIDL VHAL: {@code true} or HIDL VHAL: {@code false}.
     */
    @Override
    public boolean isAidlVhal() {
        return false;
    }

    /**
     * Gets a HalPropValueBuilder that could be used to build a HalPropValue.
     *
     * @return a builder to build HalPropValue.
     */
    @Override
    public HalPropValueBuilder getHalPropValueBuilder() {
        return mPropValueBuilder;
    }

    /**
     * Returns whether this vehicle stub is connecting to a valid vehicle HAL.
     *
     * @return Whether this vehicle stub is connecting to a valid vehicle HAL.
     */
    @Override
    public boolean isValid() {
        return mHidlVehicle != null;
    }

    /**
     * Gets the interface descriptor for the connecting vehicle HAL.
     *
     * @return the interface descriptor.
     * @throws IllegalStateException If unable to get the descriptor.
     */
    @Override
    public String getInterfaceDescriptor() throws IllegalStateException {
        try {
            return mHidlVehicle.interfaceDescriptor();
        } catch (RemoteException e) {
            throw new IllegalStateException("Unable to get Vehicle HAL interface descriptor", e);
        }
    }

    /**
     * Registers a death recipient that would be called when vehicle HAL died.
     *
     * @param recipient A death recipient.
     * @throws IllegalStateException If unable to register the death recipient.
     */
    @Override
    public void linkToDeath(IVehicleDeathRecipient recipient) throws IllegalStateException {
        try {
            mHidlVehicle.linkToDeath(recipient, /*flag=*/ 0);
        } catch (RemoteException e) {
            throw new IllegalStateException("Failed to linkToDeath Vehicle HAL");
        }
    }

    /**
     * Unlinks a previously linked death recipient.
     *
     * @param recipient A previously linked death recipient.
     */
    @Override
    public void unlinkToDeath(IVehicleDeathRecipient recipient) {
        try {
            mHidlVehicle.unlinkToDeath(recipient);
        } catch (RemoteException e) {
            // Ignore errors on shutdown path.
        }
    }

    /**
     * Gets all property configs.
     *
     * @return All the property configs.
     */
    @Override
    public HalPropConfig[] getAllPropConfigs() throws RemoteException {
        ArrayList<android.hardware.automotive.vehicle.V2_0.VehiclePropConfig> hidlConfigs =
                mHidlVehicle.getAllPropConfigs();
        int configSize = hidlConfigs.size();
        HalPropConfig[] configs = new HalPropConfig[configSize];
        for (int i = 0; i < configSize; i++) {
            configs[i] = new HidlHalPropConfig(hidlConfigs.get(i));
        }
        return configs;
    }

    /**
     * Gets a new {@code SubscriptionClient} that could be used to subscribe/unsubscribe.
     *
     * @param callback A callback that could be used to receive events.
     * @return a {@code SubscriptionClient} that could be used to subscribe/unsubscribe.
     */
    @Override
    public SubscriptionClient newSubscriptionClient(HalClientCallback callback) {
        return new HidlSubscriptionClient(callback, mPropValueBuilder);
    }

    private static class GetValueResult {
        public int status;
        public android.hardware.automotive.vehicle.V2_0.VehiclePropValue value;
    }

    /**
     * Gets a property.
     *
     * @param requestedPropValue The property to get.
     * @return The vehicle property value.
     * @throws RemoteException if the remote operation fails.
     * @throws ServiceSpecificException if VHAL returns service specific error.
     */
    @Override
    @Nullable
    public HalPropValue get(HalPropValue requestedPropValue)
            throws RemoteException, ServiceSpecificException {
        android.hardware.automotive.vehicle.V2_0.VehiclePropValue hidlPropValue =
                (android.hardware.automotive.vehicle.V2_0.VehiclePropValue) requestedPropValue
                        .toVehiclePropValue();
        GetValueResult result = new GetValueResult();
        mHidlVehicle.get(
                hidlPropValue,
                (s, p) -> {
                    result.status = s;
                    result.value = p;
                });

        if (result.status != android.hardware.automotive.vehicle.V2_0.StatusCode.OK) {
            throw new ServiceSpecificException(
                    result.status,
                    "failed to get value for property: " + Integer.toString(hidlPropValue.prop));
        }

        if (result.value == null) {
            return null;
        }

        return getHalPropValueBuilder().build(result.value);
    }

    /**
     * Sets a property.
     *
     * @param propValue The property to set.
     * @throws RemoteException if the remote operation fails.
     * @throws ServiceSpecificException if VHAL returns service specific error.
     */
    @Override
    public void set(HalPropValue propValue) throws RemoteException {
        android.hardware.automotive.vehicle.V2_0.VehiclePropValue hidlPropValue =
                (android.hardware.automotive.vehicle.V2_0.VehiclePropValue) propValue
                        .toVehiclePropValue();
        int status = mHidlVehicle.set(hidlPropValue);
        if (status != StatusCode.OK) {
            throw new ServiceSpecificException(status, "failed to set value for property: "
                    + Integer.toString(hidlPropValue.prop));
        }
    }

    @Override
    public void dump(FileDescriptor fd, ArrayList<String> args) throws RemoteException {
        mHidlVehicle.debug(new NativeHandle(fd, /* own= */ false), args);
    }

    @Nullable
    private static IVehicle getHidlVehicle() {
        String instanceName = SystemProperties.get("ro.vehicle.hal", "default");

        try {
            return IVehicle.getService(instanceName);
        } catch (RemoteException e) {
            Slogf.e(CarLog.TAG_SERVICE, "Failed to get IVehicle/" + instanceName + " service", e);
        } catch (NoSuchElementException e) {
            Slogf.e(CarLog.TAG_SERVICE, "IVehicle/" + instanceName + " service not registered yet");
        }
        return null;
    }

    private class HidlSubscriptionClient extends IVehicleCallback.Stub
            implements SubscriptionClient {
        private final HalClientCallback mCallback;
        private final HalPropValueBuilder mBuilder;

        HidlSubscriptionClient(HalClientCallback callback, HalPropValueBuilder builder) {
            mCallback = callback;
            mBuilder = builder;
        }

        @Override
        public void onPropertyEvent(
                ArrayList<android.hardware.automotive.vehicle.V2_0.VehiclePropValue> propValues) {
            ArrayList<HalPropValue> values = new ArrayList<>();
            for (android.hardware.automotive.vehicle.V2_0.VehiclePropValue value : propValues) {
                values.add(mBuilder.build(value));
            }
            mCallback.onPropertyEvent(values);
        }

        @Override
        public void onPropertySet(
                android.hardware.automotive.vehicle.V2_0.VehiclePropValue propValue) {
            // Deprecated, do nothing.
        }

        @Override
        public void onPropertySetError(int errorCode, int propId, int areaId) {
            VehiclePropError error = new VehiclePropError();
            error.propId = propId;
            error.areaId = areaId;
            error.errorCode = errorCode;
            ArrayList<VehiclePropError> errors = new ArrayList<VehiclePropError>();
            errors.add(error);
            mCallback.onPropertySetError(errors);
        }

        @Override
        public void subscribe(SubscribeOptions[] options) throws RemoteException {
            ArrayList<android.hardware.automotive.vehicle.V2_0.SubscribeOptions> hidlOptions =
                    new ArrayList<>();
            for (SubscribeOptions option : options) {
                hidlOptions.add(subscribeOptionsToHidl(option));
            }
            mHidlVehicle.subscribe(this, hidlOptions);
        }

        @Override
        public void unsubscribe(int prop) throws RemoteException {
            mHidlVehicle.unsubscribe(this, prop);
        }
    }
}
