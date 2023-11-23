/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.bluetooth.gatt;

import android.util.Log;

import com.android.bluetooth.Utils;
/**
 * Factory class for object initialization to help with unit testing
 */
public class GattObjectsFactory {
    private static final String TAG = GattObjectsFactory.class.getSimpleName();
    private static GattObjectsFactory sInstance;
    private static final Object INSTANCE_LOCK = new Object();

    private GattObjectsFactory() {
    }

    /**
     * Get the singleton instance of object factory
     *
     * @return the singleton instance, guaranteed not null
     */
    public static GattObjectsFactory getInstance() {
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new GattObjectsFactory();
            }
        }
        return sInstance;
    }

    /**
     * Allow unit tests to substitute GattObjectsFactory with a test instance
     *
     * @param objectsFactory a test instance of the GattObjectsFactory
     */
    static void setInstanceForTesting(GattObjectsFactory objectsFactory) {
        Utils.enforceInstrumentationTestMode();
        synchronized (INSTANCE_LOCK) {
            Log.d(TAG, "setInstanceForTesting(), set to " + objectsFactory);
            sInstance = objectsFactory;
        }
    }

    public GattNativeInterface getNativeInterface() {
        return GattNativeInterface.getInstance();
    }

    public ScanNativeInterface getScanNativeInterface() {
        return ScanNativeInterface.getInstance();
    }
}
