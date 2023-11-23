/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.car.hardware.property;

import android.car.hardware.CarPropertyConfig;
import android.car.hardware.CarPropertyValue;
import android.car.hardware.property.ICarPropertyEventListener;

import com.android.car.internal.property.AsyncPropertyServiceRequest;
import com.android.car.internal.property.IAsyncPropertyResultCallback;
import com.android.car.internal.property.CarPropertyConfigList;
import com.android.car.internal.property.AsyncPropertyServiceRequestList;

/**
 * @hide
 */
interface ICarProperty {

    void registerListener(int propId, float rate, in ICarPropertyEventListener callback) = 0;

    void unregisterListener(int propId, in ICarPropertyEventListener callback) = 1;

    CarPropertyConfigList getPropertyList() = 2;

    CarPropertyValue getProperty(int prop, int zone) = 3;

    void setProperty(in CarPropertyValue prop, in ICarPropertyEventListener callback) = 4;

    String getReadPermission(int propId) = 5;

    String getWritePermission(int propId) = 6;

    CarPropertyConfigList getPropertyConfigList(in int[] propIds) = 7;

    /**
     * Gets CarPropertyValues asynchronously.
     */
    void getPropertiesAsync(in AsyncPropertyServiceRequestList asyncPropertyServiceRequests,
                in IAsyncPropertyResultCallback asyncPropertyResultCallback,
                long timeoutInMs) = 8;

    /**
     * Cancel on-going async requests.
     *
     * @param serviceRequestIds A list of async get/set property request IDs.
     */
    void cancelRequests(in int[] serviceRequestIds) = 9;

    /**
     * Sets CarPropertyValues asynchronously.
     */
    void setPropertiesAsync(in AsyncPropertyServiceRequestList asyncPropertyServiceRequests,
                in IAsyncPropertyResultCallback asyncPropertyResultCallback,
                long timeoutInMs) = 10;
}
