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

package com.android.car.internal.property;

import android.annotation.SuppressLint;
import android.car.VehiclePropertyIds;
import android.car.hardware.property.VehicleHalStatusCode.VehicleHalStatusCodeInt;
import android.util.Log;
import android.util.SparseArray;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.Collection;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Helper class for CarPropertyService/CarPropertyManager.
 *
 * @hide
 */
public final class CarPropertyHelper {
    private static final String TAG = CarPropertyHelper.class.getSimpleName();

    /**
     * Status indicating no error.
     *
     * <p>This is not exposed to the client as this will be used only for deciding
     * {@link GetPropertyCallback#onSuccess} or {@link GetPropertyCallback#onFailure} is called.
     */
    public static final int STATUS_OK = 0;

    /**
     * Error indicating that too many sync operation is ongoing, caller should try again after
     * some time.
     */
    public static final int SYNC_OP_LIMIT_TRY_AGAIN = -1;

    // These are the same values as defined in VHAL interface.
    private static final int VEHICLE_PROPERTY_GROUP_MASK = 0xf0000000;
    private static final int VEHICLE_PROPERTY_GROUP_VENDOR = 0x20000000;

    private static final int SYSTEM_ERROR_CODE_MASK = 0xffff;
    private static final int VENDOR_ERROR_CODE_SHIFT = 16;

    /*
     * Used to cache the mapping of property Id integer values into property name strings. This
     * will be initialized during the first usage.
     */
    private static final AtomicReference<SparseArray<String>> sPropertyIdToPropertyNameHolder =
            new AtomicReference<>();

    /**
     * CarPropertyHelper only contains static fields and methods and must never be instantiated.
     */
    private CarPropertyHelper() {
        throw new IllegalArgumentException("Must never be called");
    }

    /**
     * Returns whether the property ID is supported by the current Car Service version.
     */
    public static boolean isSupported(int propertyId) {
        return isSystemProperty(propertyId) || isVendorProperty(propertyId);
    }

    /**
     * Gets a user-friendly representation of a property.
     */
    public static String toString(int propertyId) {
        String name = cachePropertyIdsToNameMapping().get(propertyId);
        return name != null ? name : "0x" + Integer.toHexString(propertyId);
    }

    /**
     * Gets a user-friendly representation of a list of properties.
     */
    public static String propertyIdsToString(Collection<Integer> propertyIds) {
        String names = "[";
        boolean first = true;
        for (int propertyId : propertyIds) {
            if (first) {
                first = false;
            } else {
                names += ", ";
            }
            names += toString(propertyId);
        }
        return names + "]";
    }

    /**
     * Returns the system error code contained in the error code returned from VHAL.
     */
    @SuppressLint("WrongConstant")
    public static @VehicleHalStatusCodeInt int getVhalSystemErrorCode(int vhalErrorCode) {
        return vhalErrorCode & SYSTEM_ERROR_CODE_MASK;
    }

    /**
     * Returns the vendor error code contained in the error code returned from VHAL.
     */
    public static int getVhalVendorErrorCode(int vhalErrorCode) {
        return vhalErrorCode >>> VENDOR_ERROR_CODE_SHIFT;
    }

    private static SparseArray<String> cachePropertyIdsToNameMapping() {
        SparseArray<String> propertyIdsToNameMapping = sPropertyIdToPropertyNameHolder.get();
        if (propertyIdsToNameMapping == null) {
            propertyIdsToNameMapping = getPropertyIdsToNameMapping();
            sPropertyIdToPropertyNameHolder.compareAndSet(null, propertyIdsToNameMapping);
        }
        return propertyIdsToNameMapping;
    }

    /**
     * Creates a SparseArray mapping property Ids to their String representations
     * directly from this class.
     */
    private static SparseArray<String> getPropertyIdsToNameMapping() {
        Field[] classFields = VehiclePropertyIds.class.getDeclaredFields();
        SparseArray<String> propertyIdsToNameMapping = new SparseArray<>(classFields.length);
        for (int i = 0; i < classFields.length; i++) {
            Field candidateField = classFields[i];
            try {
                if (isPropertyId(candidateField)) {
                    propertyIdsToNameMapping
                            .put(candidateField.getInt(null), candidateField.getName());
                }
            } catch (IllegalAccessException e) {
                Log.wtf(TAG, "Failed trying to find value for " + candidateField.getName(), e);
            }
        }
        return propertyIdsToNameMapping;
    }

    private static boolean isPropertyId(Field field) {
        // We only want public static final int values
        return field.getType() == int.class
            && field.getModifiers() == (Modifier.STATIC | Modifier.FINAL | Modifier.PUBLIC);
    }

    /**
     * Returns whether the property ID is defined as a system property.
     */
    private static boolean isSystemProperty(int propertyId) {
        return propertyId != VehiclePropertyIds.INVALID
                && cachePropertyIdsToNameMapping().contains(propertyId);
    }

    /**
     * Returns whether the property ID is defined as a vendor property.
     */
    private static boolean isVendorProperty(int propertyId) {
        return (propertyId & VEHICLE_PROPERTY_GROUP_MASK) == VEHICLE_PROPERTY_GROUP_VENDOR;
    }
}
