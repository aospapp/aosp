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

package com.android.car.dialer.framework;

import android.bluetooth.BluetoothDevice;

import androidx.lifecycle.MutableLiveData;

import com.android.car.dialer.framework.testdata.CallLogDataHandler;
import com.android.car.dialer.framework.testdata.ContactDataHandler;
import com.android.car.dialer.framework.testdata.TestData;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.inject.Inject;
import javax.inject.Singleton;

/**
 * Manager for mocked bluetooth devices.
 */
@Singleton
public class FakeHfpManager {

    private Map<String, SimulatedBluetoothDevice> mDeviceMap = new HashMap<>();
    private List<BluetoothDevice> mDeviceList;
    private MutableLiveData<Integer> mBluetoothStateLiveData;
    private MutableLiveData<Set<BluetoothDevice>> mBluetoothPairListLiveData;
    private MutableLiveData<List<BluetoothDevice>> mHfpDeviceListLiveData;

    private final CallLogDataHandler mCallLogDataHandler;
    private final ContactDataHandler mContactDataHandler;

    @Inject
    public FakeHfpManager(CallLogDataHandler callLogDataHandler,
            ContactDataHandler contactDataHandler) {
        mCallLogDataHandler = callLogDataHandler;
        mContactDataHandler = contactDataHandler;

        mDeviceList = new ArrayList<>();
        mBluetoothStateLiveData = new MutableLiveData<>(/* enabled */2);
        mBluetoothPairListLiveData = new MutableLiveData<>(Collections.emptySet());
        mHfpDeviceListLiveData = new MutableLiveData<>(mDeviceList);
    }

    /**
     * Connects a mocked bluetooth device.
     */
    public SimulatedBluetoothDevice connectHfpDevice(boolean withMockData) {
        int newDeviceId = TestData.newDeviceId();
        SimulatedBluetoothDevice device = prepareNewDevice(newDeviceId);
        device.connect(withMockData);
        mDeviceMap.put(String.valueOf(newDeviceId), device);
        mDeviceList.add(0, device.getBluetoothDevice());
        mHfpDeviceListLiveData.postValue(mDeviceList);
        return device;
    }

    /**
     * Disconnects a mocked bluetooth device.
     */
    public void disconnectHfpDevice(String id) {
        SimulatedBluetoothDevice simulatedBluetoothDevice = mDeviceMap.remove(id);
        if (simulatedBluetoothDevice == null) {
            return;
        }
        mDeviceList.remove(simulatedBluetoothDevice.getBluetoothDevice());
        simulatedBluetoothDevice.disconnect();
        mHfpDeviceListLiveData.postValue(mDeviceList);
    }

    private SimulatedBluetoothDevice prepareNewDevice(int id) {
        String contactDataFile = TestData.getContactDataFile(id);
        String callLogDataFile = TestData.getCallLogDataFile(id);
        SimulatedBluetoothDevice simulatedBluetoothDevice = new SimulatedBluetoothDevice(
                id, mContactDataHandler, mCallLogDataHandler, contactDataFile, callLogDataFile);

        return simulatedBluetoothDevice;
    }

    public MutableLiveData<Integer> getBluetoothStateLiveData() {
        return mBluetoothStateLiveData;
    }

    public MutableLiveData<Set<BluetoothDevice>> getBluetoothPairListLiveData() {
        return mBluetoothPairListLiveData;
    }

    public MutableLiveData<List<BluetoothDevice>> getHfpDeviceListLiveData() {
        return mHfpDeviceListLiveData;
    }
}
