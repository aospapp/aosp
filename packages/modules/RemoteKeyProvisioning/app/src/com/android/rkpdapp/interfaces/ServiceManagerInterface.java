/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.rkpdapp.interfaces;

import android.annotation.TestApi;
import android.hardware.security.keymint.IRemotelyProvisionedComponent;
import android.os.ServiceManager;

import java.util.Arrays;

/**
 * Provides convenience methods for interfacing with ServiceManager class and its static functions.
 */
public class ServiceManagerInterface {
    private static final String TAG = "RkpdSvcManagerInterface";
    private static SystemInterface[] sInstances;

    private ServiceManagerInterface() {
    }

    private static SystemInterface createSystemInterface(String serviceName) {
        IRemotelyProvisionedComponent binder = IRemotelyProvisionedComponent.Stub.asInterface(
                ServiceManager.waitForDeclaredService(serviceName));
        if (binder == null) {
            throw new IllegalArgumentException("Cannot find any implementation for " + serviceName);
        }
        return new SystemInterface(binder, serviceName);
    }

    /**
     * Gets all the instances on this device for IRemotelyProvisionedComponent as an array. The
     * returned values each contain a binder for interacting with the instance.
     *
     * For testing purposes, the instances may be overridden by setInstances
     */
    public static SystemInterface[] getAllInstances() {
        if (sInstances != null) {
            return sInstances;
        }

        String irpcInterface = IRemotelyProvisionedComponent.DESCRIPTOR;
        return Arrays.stream(ServiceManager.getDeclaredInstances(irpcInterface))
                .map(x -> createSystemInterface(irpcInterface + "/" + x))
                .toArray(SystemInterface[]::new);
    }

    /**
     * Get a specific system interface instance for a given IRemotelyProvisionedComponent.
     * If the given serviceName does not map to a known IRemotelyProvisionedComponent, this
     * method throws IllegalArgumentException.
     *
     * For testing purposes, the instances may be overridden by setInstances.
     */
    public static SystemInterface getInstance(String serviceName) {
        if (sInstances != null) {
            for (SystemInterface i : sInstances) {
                if (i.getServiceName().equals(serviceName)) {
                    return i;
                }
            }
            throw new IllegalArgumentException("Cannot find any implementation for " + serviceName);
        }

        return createSystemInterface(serviceName);
    }

    @TestApi
    public static void setInstances(SystemInterface[] instances) {
        sInstances = instances;
    }
}
