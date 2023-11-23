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
package com.android.car.audio;


import static android.car.media.CarAudioManager.PRIMARY_AUDIO_ZONE;
import static android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC;

import android.media.AudioDeviceAttributes;
import android.util.ArraySet;
import android.util.SparseArray;

import com.android.internal.util.Preconditions;

import java.util.List;
import java.util.Set;

final class CarAudioZonesValidator {
    private CarAudioZonesValidator() {
    }

    static void validate(SparseArray<CarAudioZone> carAudioZones, boolean useCoreAudioRouting) {
        validateAtLeastOneZoneDefined(carAudioZones);
        validateZoneConfigsForEachZone(carAudioZones, useCoreAudioRouting);
        if (!useCoreAudioRouting) {
            validateEachAddressAppearsAtMostOnce(carAudioZones);
        }
        validatePrimaryZoneHasInputDevice(carAudioZones);
    }

    private static void validatePrimaryZoneHasInputDevice(SparseArray<CarAudioZone> carAudioZones) {
        CarAudioZone primaryZone = carAudioZones.get(PRIMARY_AUDIO_ZONE);
        List<AudioDeviceAttributes> devices = primaryZone.getInputAudioDevices();
        Preconditions.checkCollectionNotEmpty(devices, "Primary Zone Input Devices");
        for (int index = 0; index < devices.size(); index++) {
            AudioDeviceAttributes device = devices.get(index);
            if (device.getType() == TYPE_BUILTIN_MIC) {
                return;
            }
        }
        throw new RuntimeException("Primary Zone must have at least one microphone input device");
    }

    private static void validateAtLeastOneZoneDefined(SparseArray<CarAudioZone> carAudioZones) {
        if (carAudioZones.size() == 0) {
            throw new RuntimeException("At least one zone should be defined");
        }
    }

    private static void validateZoneConfigsForEachZone(SparseArray<CarAudioZone> carAudioZones,
            boolean useCoreAudioRouting) {
        for (int i = 0; i < carAudioZones.size(); i++) {
            CarAudioZone zone = carAudioZones.valueAt(i);
            if (!zone.validateZoneConfigs(useCoreAudioRouting)) {
                throw new RuntimeException(
                        "Invalid zone configurations for zone " + zone.getId());
            }
            if (!zone.validateCanUseDynamicMixRouting(useCoreAudioRouting)) {
                throw new RuntimeException(
                        "Invalid Configuration to use Dynamic Mix for zone " + zone.getId());
            }
        }
    }

    private static void validateEachAddressAppearsAtMostOnce(
            SparseArray<CarAudioZone> carAudioZones) {
        Set<String> addresses = new ArraySet<>();
        for (int i = 0; i < carAudioZones.size(); i++) {
            List<CarAudioZoneConfig> zoneConfigs =
                    carAudioZones.valueAt(i).getAllCarAudioZoneConfigs();
            for (int configIndex = 0; configIndex < zoneConfigs.size(); configIndex++) {
                for (CarVolumeGroup carVolumeGroup :
                        zoneConfigs.get(configIndex).getVolumeGroups()) {
                    for (String address : carVolumeGroup.getAddresses()) {
                        if (!addresses.add(address)) {
                            throw new RuntimeException("Device with address " + address
                                    + " appears in multiple volume groups or audio zones");
                        }
                    }
                }
            }
        }
    }
}
