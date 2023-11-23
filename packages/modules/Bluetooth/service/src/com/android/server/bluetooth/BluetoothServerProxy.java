/*
 * Copyright 2023 The Android Open Source Project
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

package com.android.server.bluetooth;

import android.content.ContentResolver;
import android.os.HandlerThread;
import android.provider.Settings;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Proxy class for method calls to help with unit testing
 */
public class BluetoothServerProxy {
    private static final String TAG = BluetoothServerProxy.class.getSimpleName();
    private static final Object INSTANCE_LOCK = new Object();
    private static BluetoothServerProxy sInstance;

    private BluetoothServerProxy() {
    }

    /**
     * Get the singleton instance of proxy
     *
     * @return the singleton instance, guaranteed not null
     */
    public static BluetoothServerProxy getInstance() {
        synchronized (INSTANCE_LOCK) {
            if (sInstance == null) {
                sInstance = new BluetoothServerProxy();
            }
        }
        return sInstance;
    }

    /**
     * Allow unit tests to substitute BluetoothPbapMethodCallProxy with a test instance
     *
     * @param proxy a test instance of the BluetoothPbapMethodCallProxy
     */
    @VisibleForTesting
    public static void setInstanceForTesting(BluetoothServerProxy proxy) {
        synchronized (INSTANCE_LOCK) {
            Log.d(TAG, "setInstanceForTesting(), set to " + proxy);
            sInstance = proxy;
        }
    }

    /**
     * Proxies {@link com.android.server.bluetooth.BluetoothManagerService.BluetoothHandler}.
     */
    public BluetoothManagerService.BluetoothHandler createBluetoothHandler(
            BluetoothManagerService.BluetoothHandler bluetoothHandler) {
        return bluetoothHandler;
    }

    /**
     * Proxies {@link com.android.server.bluetooth.BluetoothManagerService.BluetoothHandler}.
     */
    public BluetoothManagerService.BluetoothHandler newBluetoothHandler(
            BluetoothManagerService.BluetoothHandler bluetoothHandler) {
        return bluetoothHandler;
    }

    /**
     * Proxies {@link HandlerThread(String)}.
     */
    public HandlerThread createHandlerThread(String name) {
        return new HandlerThread(name);
    }

    /**
     * Proxies {@link android.provider.Settings.Secure.getString}.
     */
    public String settingsSecureGetString(ContentResolver contentResolver, String name) {
        return Settings.Secure.getString(contentResolver, name);
    }

    /**
     * Proxies
     * {@link com.android.server.bluetooth.BluetoothManagerService.BluetoothHandler.sendMessage}.
     */
    public boolean handlerSendWhatMessage(
            com.android.server.bluetooth.BluetoothManagerService.BluetoothHandler handler,
            int what) {
        return handler.sendMessage(handler.obtainMessage(what));
    }
}
