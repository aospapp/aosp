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

import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothStatusCodes;
import android.bluetooth.BluetoothUtils;
import android.bluetooth.le.DistanceMeasurementMethod;
import android.bluetooth.le.DistanceMeasurementParams;
import android.bluetooth.le.DistanceMeasurementResult;
import android.bluetooth.le.IDistanceMeasurementCallback;
import android.os.HandlerThread;
import android.os.RemoteException;
import android.util.Log;

import com.android.bluetooth.btservice.AdapterService;
import com.android.internal.annotations.VisibleForTesting;

import java.util.HashSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Manages distnace measurement operations and interacts with Gabeldorsche stack.
 *
 * @hide
 */
@VisibleForTesting(visibility = VisibleForTesting.Visibility.PACKAGE)
public class DistanceMeasurementManager {
    private static final boolean DBG = GattServiceConfig.DBG;
    private static final String TAG = "DistanceMeasurementManager";

    private static final int RSSI_FREQUENCY_LOW = 3000;
    private static final int RSSI_FREQUENCY_MEDIUM = 1000;
    private static final int RSSI_FREQUENCY_HIGH = 500;

    private final AdapterService mAdapterService;
    private HandlerThread mHandlerThread;
    DistanceMeasurementNativeInterface mDistanceMeasurementNativeInterface;
    private ConcurrentHashMap<String, HashSet<DistanceMeasurementTracker>> mRssiTrackers =
            new ConcurrentHashMap<>();

    /**
     * Constructor of {@link DistanceMeasurementManager}.
     */
    DistanceMeasurementManager(AdapterService adapterService) {
        mAdapterService = adapterService;
    }

    /**
     * Start a {@link HandlerThread} that handles distnace measurement operations.
     */
    void start() {
        mHandlerThread = new HandlerThread("DistanceMeasurementManager");
        mHandlerThread.start();
        mDistanceMeasurementNativeInterface = DistanceMeasurementNativeInterface.getInstance();
        mDistanceMeasurementNativeInterface.init(this);
    }

    void cleanup() {
        mDistanceMeasurementNativeInterface.cleanup();
    }

    DistanceMeasurementMethod[] getSupportedDistanceMeasurementMethods() {
        DistanceMeasurementMethod rssi = new DistanceMeasurementMethod.Builder(
                DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI).build();
        DistanceMeasurementMethod[] methods = {rssi};
        return methods;
    }


    void startDistanceMeasurement(UUID uuid, DistanceMeasurementParams params,
            IDistanceMeasurementCallback callback) {
        Log.i(TAG, "startDistanceMeasurement device:" + params.getDevice().getAnonymizedAddress()
                + ", method: " + params.getMethodId());
        String identityAddress = mAdapterService.getIdentityAddress(
                params.getDevice().getAddress());
        logd("Get identityAddress: " + params.getDevice().getAnonymizedAddress() + " => "
                + BluetoothUtils.toAnonymizedAddress(identityAddress));

        int frequencyValue = getFrequencyValue(params.getFrequency(), params.getMethodId());
        if (frequencyValue == -1) {
            invokeStartFail(callback, params.getDevice(),
                    BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
            return;
        }

        DistanceMeasurementTracker tracker = new DistanceMeasurementTracker(
                this, params, identityAddress, uuid, frequencyValue, callback);

        switch (params.getMethodId()) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_AUTO:
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                startRssiTracker(tracker);
                break;
            default:
                invokeStartFail(callback, params.getDevice(),
                        BluetoothStatusCodes.ERROR_BAD_PARAMETERS);
        }
    }

    private synchronized void startRssiTracker(DistanceMeasurementTracker tracker) {
        mRssiTrackers.putIfAbsent(tracker.mIdentityAddress,
                new HashSet<DistanceMeasurementTracker>());
        HashSet<DistanceMeasurementTracker> set = mRssiTrackers.get(tracker.mIdentityAddress);
        if (set.contains(tracker)) {
            Log.w(TAG, "Already registered");
            return;
        }
        set.add(tracker);
        mDistanceMeasurementNativeInterface.startDistanceMeasurement(tracker.mIdentityAddress,
                tracker.mFrequency, DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
    }

    int stopDistanceMeasurement(UUID uuid, BluetoothDevice device, int method,
            boolean timeout) {
        Log.i(TAG, "stopDistanceMeasurement device:" + device.getAnonymizedAddress()
                + ", method: " + method + " timeout " + timeout);
        String identityAddress = mAdapterService.getIdentityAddress(device.getAddress());
        logd("Get identityAddress: " + device.getAnonymizedAddress() + " => "
                + BluetoothUtils.toAnonymizedAddress(identityAddress));

        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_AUTO:
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                return stopRssiTracker(uuid, identityAddress, timeout);
            default:
                Log.w(TAG, "stopDistanceMeasurement with invalid method:" + method);
                return BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL;
        }
    }

    private synchronized int stopRssiTracker(UUID uuid, String identityAddress,
            boolean timeout) {
        HashSet<DistanceMeasurementTracker> set = mRssiTrackers.get(identityAddress);
        if (set == null) {
            Log.w(TAG, "Can't find rssi tracker");
            return BluetoothStatusCodes.ERROR_DISTANCE_MEASUREMENT_INTERNAL;
        }

        for (DistanceMeasurementTracker tracker : set) {
            if (tracker.equals(uuid, identityAddress)) {
                int reason = timeout ? BluetoothStatusCodes.ERROR_TIMEOUT :
                        BluetoothStatusCodes.REASON_LOCAL_APP_REQUEST;
                invokeOnStopped(tracker.mCallback, tracker.mDevice, reason);
                tracker.cancelTimer();
                set.remove(tracker);
                break;
            }
        }

        if (set.isEmpty()) {
            logd("no rssi tracker");
            mRssiTrackers.remove(identityAddress);
            mDistanceMeasurementNativeInterface.stopDistanceMeasurement(identityAddress,
                    DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI);
        }
        return BluetoothStatusCodes.SUCCESS;
    }

    private void invokeStartFail(IDistanceMeasurementCallback callback, BluetoothDevice device,
            int reason) {
        try {
            callback.onStartFail(device, reason);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception: " + e);
        }
    }

    private void invokeOnStopped(IDistanceMeasurementCallback callback, BluetoothDevice device,
            int reason) {
        try {
            callback.onStopped(device, reason);
        } catch (RemoteException e) {
            Log.e(TAG, "Exception: " + e);
        }
    }

    /**
     * Convert frequency into value in ms
     */
    private int getFrequencyValue(int frequency, int method) {
        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_AUTO:
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                switch (frequency) {
                    case DistanceMeasurementParams.REPORT_FREQUENCY_LOW:
                        return RSSI_FREQUENCY_LOW;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_MEDIUM:
                        return RSSI_FREQUENCY_MEDIUM;
                    case DistanceMeasurementParams.REPORT_FREQUENCY_HIGH:
                        return RSSI_FREQUENCY_HIGH;
                }
                break;
            default:

        }
        Log.w(TAG, "getFrequencyValue fail frequency:" + frequency + ", method:" + method);
        return -1;
    }

    void onDistanceMeasurementStarted(String address, int method) {
        logd("onDistanceMeasurementStarted address:" + BluetoothUtils.toAnonymizedAddress(address)
                + ", method:" + method);
        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                handleRssiStarted(address);
                break;
            default:
                Log.w(TAG, "onDistanceMeasurementResult: invalid method " + method);
        }
    }

    void handleRssiStarted(String address) {
        HashSet<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find rssi tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            try {
                if (!tracker.mStarted) {
                    tracker.mStarted = true;
                    tracker.mCallback.onStarted(tracker.mDevice);
                    tracker.startTimer(mHandlerThread.getLooper());
                }
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
        }
    }

    void onDistanceMeasurementStartFail(String address, int reason, int method) {
        logd("onDistanceMeasurementStartFail address:" + BluetoothUtils.toAnonymizedAddress(address)
                + ", method:" + method);
        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                handleRssiStartFail(address, reason);
                break;
            default:
                Log.w(TAG, "onDistanceMeasurementStartFail: invalid method " + method);
        }
    }

    void handleRssiStartFail(String address, int reason) {
        HashSet<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find rssi tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            if (!tracker.mStarted) {
                invokeStartFail(tracker.mCallback, tracker.mDevice, reason);
            }
        }
        synchronized (set) {
            set.removeIf(tracker -> !tracker.mStarted);
        }
    }

    void onDistanceMeasurementStopped(String address, int reason, int method) {
        logd("onDistanceMeasurementStopped address:" + BluetoothUtils.toAnonymizedAddress(address)
                + ", reason:" + reason + ", method:" + method);
        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                handleRssiStopped(address, reason);
                break;
            default:
                Log.w(TAG, "onDistanceMeasurementStopped: invalid method " + method);
        }
    }

    void handleRssiStopped(String address, int reason) {
        HashSet<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find rssi tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            if (tracker.mStarted) {
                tracker.cancelTimer();
                invokeOnStopped(tracker.mCallback, tracker.mDevice, reason);
            }
        }
        synchronized (set) {
            set.removeIf(tracker -> tracker.mStarted);
        }
    }

    void onDistanceMeasurementResult(String address, int centimeter, int errorCentimeter,
            int azimuthAngle, int errorAzimuthAngle, int altitudeAngle, int errorAltitudeAngle,
            int method) {
        logd("onDistanceMeasurementResult " + BluetoothUtils.toAnonymizedAddress(address)
                + ", centimeter " + centimeter);
        switch (method) {
            case DistanceMeasurementMethod.DISTANCE_MEASUREMENT_METHOD_RSSI:
                DistanceMeasurementResult result = new DistanceMeasurementResult.Builder(
                        centimeter / 100.0, errorCentimeter / 100.0).build();
                handleRssiResult(address, result);
                break;
            default:
                Log.w(TAG, "onDistanceMeasurementResult: invalid method " + method);
        }
    }

    void handleRssiResult(String address, DistanceMeasurementResult result) {
        HashSet<DistanceMeasurementTracker> set = mRssiTrackers.get(address);
        if (set == null) {
            Log.w(TAG, "Can't find rssi tracker");
            return;
        }
        for (DistanceMeasurementTracker tracker : set) {
            try {
                if (!tracker.mStarted) {
                    continue;
                }
                tracker.mCallback.onResult(tracker.mDevice, result);
            } catch (RemoteException e) {
                Log.e(TAG, "Exception: " + e);
            }
        }
    }

    /** Logs the message in debug ROM. */
    private static void logd(String msg) {
        if (DBG) {
            Log.d(TAG, msg);
        }
    }
}
