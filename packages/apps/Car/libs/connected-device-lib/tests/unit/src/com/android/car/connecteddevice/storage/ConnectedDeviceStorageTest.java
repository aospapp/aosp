/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.connecteddevice.storage;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.content.Context;
import android.util.Pair;

import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.connecteddevice.model.AssociatedDevice;
import com.android.car.connecteddevice.util.ByteUtils;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.security.InvalidParameterException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@RunWith(AndroidJUnit4.class)
public final class ConnectedDeviceStorageTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();

    private final int mActiveUserId = 10;

    private ConnectedDeviceStorage mConnectedDeviceStorage;

    private List<Pair<Integer, AssociatedDevice>> mAddedAssociatedDevices;

    @Before
    public void setUp() {
        mConnectedDeviceStorage = new ConnectedDeviceStorage(mContext);
        mAddedAssociatedDevices = new ArrayList<>();
    }

    @After
    public void tearDown() {
        // Clear any associated devices added during tests.
        for (Pair<Integer, AssociatedDevice> device : mAddedAssociatedDevices) {
            mConnectedDeviceStorage.removeAssociatedDevice(device.first,
                    device.second.getDeviceId());
        }
    }

    @Test
    public void getAssociatedDeviceIdsForUser_includesNewlyAddedDevice() {
        AssociatedDevice addedDevice = addRandomAssociatedDevice(mActiveUserId);
        List<String> associatedDevices =
                mConnectedDeviceStorage.getAssociatedDeviceIdsForUser(mActiveUserId);
        assertThat(associatedDevices).containsExactly(addedDevice.getDeviceId());
    }

    @Test
    public void getAssociatedDeviceIdsForUser_excludesDeviceAddedForOtherUser() {
        addRandomAssociatedDevice(mActiveUserId);
        List<String> associatedDevices =
                mConnectedDeviceStorage.getAssociatedDeviceIdsForUser(mActiveUserId + 1);
        assertThat(associatedDevices).isEmpty();
    }

    @Test
    public void getAssociatedDeviceIdsForUser_excludesRemovedDevice() {
        AssociatedDevice addedDevice = addRandomAssociatedDevice(mActiveUserId);
        mConnectedDeviceStorage.removeAssociatedDevice(mActiveUserId, addedDevice.getDeviceId());
        List<String> associatedDevices =
                mConnectedDeviceStorage.getAssociatedDeviceIdsForUser(mActiveUserId);
        assertThat(associatedDevices).isEmpty();
    }

    @Test
    public void getAssociatedDevicesForUser_includesNewlyAddedDevice() {
        AssociatedDevice addedDevice = addRandomAssociatedDevice(mActiveUserId);
        List<AssociatedDevice> associatedDevices =
                mConnectedDeviceStorage.getAssociatedDevicesForUser(mActiveUserId);
        assertThat(associatedDevices).containsExactly(addedDevice);
    }

    @Test
    public void getAssociatedDevicesForUser_excludesDeviceAddedForOtherUser() {
        addRandomAssociatedDevice(mActiveUserId);
        List<String> associatedDevices =
                mConnectedDeviceStorage.getAssociatedDeviceIdsForUser(mActiveUserId + 1);
        assertThat(associatedDevices).isEmpty();
    }

    @Test
    public void getAssociatedDevicesForUser_excludesRemovedDevice() {
        AssociatedDevice addedDevice = addRandomAssociatedDevice(mActiveUserId);
        mConnectedDeviceStorage.removeAssociatedDevice(mActiveUserId, addedDevice.getDeviceId());
        List<AssociatedDevice> associatedDevices =
                mConnectedDeviceStorage.getAssociatedDevicesForUser(mActiveUserId);
        assertThat(associatedDevices).isEmpty();
    }

    @Test
    public void getEncryptionKey_returnsSavedKey() {
        String deviceId = addRandomAssociatedDevice(mActiveUserId).getDeviceId();
        byte[] key = ByteUtils.randomBytes(16);
        mConnectedDeviceStorage.saveEncryptionKey(deviceId, key);
        assertThat(mConnectedDeviceStorage.getEncryptionKey(deviceId)).isEqualTo(key);
    }

    @Test
    public void getEncryptionKey_returnsNullForUnrecognizedDeviceId() {
        String deviceId = addRandomAssociatedDevice(mActiveUserId).getDeviceId();
        mConnectedDeviceStorage.saveEncryptionKey(deviceId, ByteUtils.randomBytes(16));
        assertThat(mConnectedDeviceStorage.getEncryptionKey(UUID.randomUUID().toString())).isNull();
    }

    @Test
    public void saveChallengeSecret_throwsForInvalidLengthSecret() {
        byte[] invalidSecret =
                ByteUtils.randomBytes(ConnectedDeviceStorage.CHALLENGE_SECRET_BYTES - 1);
        assertThrows(InvalidParameterException.class,
                () -> mConnectedDeviceStorage.saveChallengeSecret(UUID.randomUUID().toString(),
                        invalidSecret));
    }

    private AssociatedDevice addRandomAssociatedDevice(int userId) {
        AssociatedDevice device = new AssociatedDevice(UUID.randomUUID().toString(),
                "00:00:00:00:00:00", "Test Device", true);
        addAssociatedDevice(userId, device, ByteUtils.randomBytes(16));
        return device;
    }

    private void addAssociatedDevice(int userId, AssociatedDevice device, byte[] encryptionKey) {
        mConnectedDeviceStorage.addAssociatedDeviceForUser(userId, device);
        mConnectedDeviceStorage.saveEncryptionKey(device.getDeviceId(), encryptionKey);
        mAddedAssociatedDevices.add(new Pair<>(userId, device));
    }
}
