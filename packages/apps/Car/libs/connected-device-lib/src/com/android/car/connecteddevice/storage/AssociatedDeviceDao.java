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

import androidx.room.Dao;
import androidx.room.Delete;
import androidx.room.Insert;
import androidx.room.OnConflictStrategy;
import androidx.room.Query;

import java.util.List;

/**
 * Queries for associated device table.
 */
@Dao
public interface AssociatedDeviceDao {

    /** Get an associated device based on device id. */
    @Query("SELECT * FROM associated_devices WHERE id LIKE :deviceId LIMIT 1")
    AssociatedDeviceEntity getAssociatedDevice(String deviceId);

    /** Get all {@link AssociatedDeviceEntity}s associated with a user. */
    @Query("SELECT * FROM associated_devices WHERE userId LIKE :userId")
    List<AssociatedDeviceEntity> getAssociatedDevicesForUser(int userId);

    /**
     * Add a {@link AssociatedDeviceEntity}. Replace if a device already exists with the same
     * device id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addOrReplaceAssociatedDevice(AssociatedDeviceEntity associatedDevice);

    /** Remove a {@link AssociatedDeviceEntity}. */
    @Delete
    void removeAssociatedDevice(AssociatedDeviceEntity connectedDevice);

    /** Get the key associated with a device id. */
    @Query("SELECT * FROM associated_device_keys WHERE id LIKE :deviceId LIMIT 1")
    AssociatedDeviceKeyEntity getAssociatedDeviceKey(String deviceId);

    /**
     * Add a {@link AssociatedDeviceKeyEntity}. Replace if a device key already exists with the
     * same device id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addOrReplaceAssociatedDeviceKey(AssociatedDeviceKeyEntity keyEntity);

    /** Remove a {@link AssociatedDeviceKeyEntity}. */
    @Delete
    void removeAssociatedDeviceKey(AssociatedDeviceKeyEntity keyEntity);

    /** Get the challenge secret associated with a device id. */
    @Query("SELECT * FROM associated_devices_challenge_secrets WHERE id LIKE :deviceId LIMIT 1")
    AssociatedDeviceChallengeSecretEntity getAssociatedDeviceChallengeSecret(String deviceId);

    /**
     * Add a {@link AssociatedDeviceChallengeSecretEntity}. Replace if a secret already exists with
     * the same device id.
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    void addOrReplaceAssociatedDeviceChallengeSecret(
            AssociatedDeviceChallengeSecretEntity challengeSecretEntity);

    /** Remove a {@link AssociatedDeviceChallengeSecretEntity}. */
    @Delete
    void removeAssociatedDeviceChallengeSecret(
            AssociatedDeviceChallengeSecretEntity challengeSecretEntity);
}
