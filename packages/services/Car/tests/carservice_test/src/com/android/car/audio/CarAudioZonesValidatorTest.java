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

import static org.mockito.Mockito.when;

import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.google.common.collect.Lists;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.ExpectedException;
import org.junit.runner.RunWith;
import org.mockito.Mockito;

@RunWith(AndroidJUnit4.class)
public class CarAudioZonesValidatorTest {
    @Rule
    public final ExpectedException thrown = ExpectedException.none();

    @Test
    public void validate_thereIsAtLeastOneZone() {
        thrown.expect(RuntimeException.class);
        thrown.expectMessage("At least one zone should be defined");

        CarAudioZonesValidator.validate(new CarAudioZone[0]);
    }

    @Test
    public void validate_volumeGroupsForEachZone() {
        CarAudioZone primaryZone = Mockito.mock(CarAudioZone.class);
        when(primaryZone.validateVolumeGroups()).thenReturn(true);
        CarAudioZone zoneOne = Mockito.mock(CarAudioZone.class);
        when(zoneOne.validateVolumeGroups()).thenReturn(false);
        when(zoneOne.getId()).thenReturn(1);

        thrown.expect(RuntimeException.class);
        thrown.expectMessage("Invalid volume groups configuration for zone " + 1);

        CarAudioZonesValidator.validate(new CarAudioZone[]{primaryZone, zoneOne});
    }

    @Test
    public void validate_eachAddressAppearsInOnlyOneZone() {
        CarAudioZone primaryZone = Mockito.mock(CarAudioZone.class);
        CarVolumeGroup mockVolumeGroup = Mockito.mock(CarVolumeGroup.class);
        when(mockVolumeGroup.getAddresses()).thenReturn(Lists.newArrayList("one", "two", "three"));
        when(primaryZone.getVolumeGroups()).thenReturn(new CarVolumeGroup[]{mockVolumeGroup});
        when(primaryZone.validateVolumeGroups()).thenReturn(true);

        CarAudioZone secondaryZone = Mockito.mock(CarAudioZone.class);
        CarVolumeGroup mockSecondaryVolmeGroup = Mockito.mock(CarVolumeGroup.class);
        when(mockSecondaryVolmeGroup.getAddresses()).thenReturn(
                Lists.newArrayList("three", "four", "five"));
        when(secondaryZone.getVolumeGroups()).thenReturn(
                new CarVolumeGroup[]{mockSecondaryVolmeGroup});
        when(secondaryZone.validateVolumeGroups()).thenReturn(true);

        thrown.expect(RuntimeException.class);
        thrown.expectMessage(
                "Device with address three appears in multiple volume groups or audio zones");

        CarAudioZonesValidator.validate(new CarAudioZone[]{primaryZone, secondaryZone});
    }

    @Test
    public void validate_passesWithoutExceptionForValidZoneConfiguration() {
        CarAudioZone primaryZone = Mockito.mock(CarAudioZone.class);
        when(primaryZone.validateVolumeGroups()).thenReturn(true);
        when(primaryZone.getVolumeGroups()).thenReturn(new CarVolumeGroup[0]);

        CarAudioZonesValidator.validate(new CarAudioZone[]{primaryZone});
    }
}
