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


import java.util.HashSet;
import java.util.Set;

class CarAudioZonesValidator {
    static void validate(CarAudioZone[] carAudioZones) {
        validateAtLeastOneZoneDefined(carAudioZones);
        validateVolumeGroupsForEachZone(carAudioZones);
        validateEachAddressAppearsAtMostOnce(carAudioZones);
    }

    private static void validateAtLeastOneZoneDefined(CarAudioZone[] carAudioZones) {
        if (carAudioZones.length == 0) {
            throw new RuntimeException("At least one zone should be defined");
        }
    }

    private static void validateVolumeGroupsForEachZone(CarAudioZone[] carAudioZones) {
        for (CarAudioZone zone : carAudioZones) {
            if (!zone.validateVolumeGroups()) {
                throw new RuntimeException(
                        "Invalid volume groups configuration for zone " + zone.getId());
            }
        }
    }

    private static void validateEachAddressAppearsAtMostOnce(CarAudioZone[] carAudioZones) {
        Set<String> addresses = new HashSet<>();
        for (CarAudioZone zone : carAudioZones) {
            for (CarVolumeGroup carVolumeGroup : zone.getVolumeGroups()) {
                for (String address : carVolumeGroup.getAddresses()) {
                    if (!addresses.add(address)) {
                        throw new RuntimeException("Device with address "
                                + address + " appears in multiple volume groups or audio zones");
                    }
                }
            }
        }
    }
}
