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
package com.android.car.audio;

import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_MEDIA;

import static com.android.car.audio.CarAudioContext.AudioContext;

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.media.CarAudioManager;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;
import android.media.AudioFormat;
import android.media.AudioSystem;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioPolicy;
import android.util.ArrayMap;
import android.util.SparseArray;

import com.google.common.collect.ImmutableList;
import com.google.common.truth.Expect;

import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioDynamicRoutingTest {
    private static final String MUSIC_ADDRESS = "bus0_music";
    private static final String NAV_ADDRESS = "bus1_nav";

    private static final AudioAttributes TEST_MEDIA_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA);
    private static final AudioAttributes TEST_NAVIGATION_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);

    private static final CarAudioContext TEST_CAR_AUDIO_CONTEXT =
            new CarAudioContext(CarAudioContext.getAllContextsInfo(),
                    /* useCoreAudioRouting= */ false);

    private static final @AudioContext int TEST_MEDIA_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_MEDIA_ATTRIBUTE);
    private static final @AudioContext int TEST_NAVIGATION_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_NAVIGATION_ATTRIBUTE);

    @Rule
    public final Expect expect = Expect.create();

    @Test
    public void setupAudioDynamicRouting() {
        AudioPolicy.Builder mockBuilder = Mockito.mock(AudioPolicy.Builder.class);
        CarAudioDeviceInfo musicCarAudioDeviceInfo = getCarAudioDeviceInfo(MUSIC_ADDRESS,
                /* canBeRoutedWithDynamicPolicyMix= */ true);
        CarAudioDeviceInfo navCarAudioDeviceInfo = getCarAudioDeviceInfo(NAV_ADDRESS,
                /* canBeRoutedWithDynamicPolicyMix= */ false);

        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo)
                .build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, NAV_ADDRESS)
                .addCarAudioDeviceInfoMock(navCarAudioDeviceInfo)
                .build();

        CarAudioZoneConfig carAudioZoneConfig =
                new CarAudioZoneConfig.Builder("Primary zone config 0",
                        CarAudioManager.PRIMARY_AUDIO_ZONE, /* zoneConfigId= */ 0,
                        /* isDefault= */ true)
                        .addVolumeGroup(mockMusicGroup)
                        .addVolumeGroup(mockNavGroupRoutingOnMusic)
                        .build();

        CarAudioZone carAudioZone = new CarAudioZone(TEST_CAR_AUDIO_CONTEXT, "Primary zone",
                CarAudioManager.PRIMARY_AUDIO_ZONE);

        carAudioZone.addZoneConfig(carAudioZoneConfig);
        SparseArray<CarAudioZone> zones = new SparseArray<>();
        zones.put(CarAudioManager.PRIMARY_AUDIO_ZONE, carAudioZone);
        CarAudioDynamicRouting.setupAudioDynamicRouting(mockBuilder, zones, TEST_CAR_AUDIO_CONTEXT);

        ArgumentCaptor<AudioMix> audioMixCaptor = ArgumentCaptor.forClass(AudioMix.class);

        verify(mockBuilder).addMix(audioMixCaptor.capture());
        AudioMix audioMix = audioMixCaptor.getValue();
        expect.withMessage("Music address registered").that(audioMix.getRegistration())
                .isEqualTo(MUSIC_ADDRESS);
        expect.withMessage("Contains Music device")
                .that(audioMix.isRoutedToDevice(AudioSystem.DEVICE_OUT_BUS, MUSIC_ADDRESS))
                .isTrue();
        expect.withMessage("Does not contain Nav device")
                .that(audioMix.isRoutedToDevice(AudioSystem.DEVICE_OUT_BUS, NAV_ADDRESS))
                .isFalse();
        expect.withMessage("Affected media usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_MEDIA))
                .isTrue();
        expect.withMessage("Affected game usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_GAME))
                .isTrue();
        expect.withMessage("Affected unknown usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_UNKNOWN))
                .isTrue();
        expect.withMessage("Non-affected voice comm usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION))
                .isFalse();
        expect.withMessage("Non-affected voice comm signalling usage")
                .that(audioMix.isAffectingUsage(
                        AudioAttributes.USAGE_VOICE_COMMUNICATION_SIGNALLING))
                .isFalse();
        expect.withMessage("Non-affected alarm usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_ALARM))
                .isFalse();
        expect.withMessage("Non-affected notification usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_NOTIFICATION))
                .isFalse();
        expect.withMessage("Non-affected notification ringtone usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_NOTIFICATION_RINGTONE))
                .isFalse();
        expect.withMessage("Non-affected notification event usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_NOTIFICATION_EVENT))
                .isFalse();
        expect.withMessage("Non-affected assistance accessibility usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_ASSISTANCE_ACCESSIBILITY))
                .isFalse();
        expect.withMessage("Non-affected nav guidance usage")
                .that(audioMix.isAffectingUsage(
                        AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE))
                .isFalse();
        expect.withMessage("Non-affected assistance sonification usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_ASSISTANCE_SONIFICATION))
                .isFalse();
        expect.withMessage("Non-affected assistant usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_ASSISTANT))
                .isFalse();
        expect.withMessage("Non-affected call assistant usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_CALL_ASSISTANT))
                .isFalse();
        expect.withMessage("Non-affected emergency usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_EMERGENCY))
                .isFalse();
        expect.withMessage("Non-affected safety usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_SAFETY))
                .isFalse();
        expect.withMessage("Non-affected vehicle status usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_VEHICLE_STATUS))
                .isFalse();
        expect.withMessage("Non-affected announcement usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_ANNOUNCEMENT))
                .isFalse();
    }

    @Test
    public void setupAudioDynamicRoutingForMirrorDevice() {
        AudioPolicy.Builder mockBuilder = Mockito.mock(AudioPolicy.Builder.class);
        CarAudioDeviceInfo carMirrorDeviceInfo = getCarAudioDeviceInfo(MUSIC_ADDRESS,
                /* canBeRoutedWithDynamicPolicyMix= */ true);

        CarAudioDynamicRouting.setupAudioDynamicRoutingForMirrorDevice(mockBuilder,
                List.of(carMirrorDeviceInfo));

        ArgumentCaptor<AudioMix> audioMixCaptor = ArgumentCaptor.forClass(AudioMix.class);

        verify(mockBuilder).addMix(audioMixCaptor.capture());
        AudioMix audioMix = audioMixCaptor.getValue();
        expect.withMessage("Music address registered").that(audioMix.getRegistration())
                .isEqualTo(MUSIC_ADDRESS);
        expect.withMessage("Contains Music device")
                .that(audioMix.isRoutedToDevice(AudioSystem.DEVICE_OUT_BUS, MUSIC_ADDRESS))
                .isTrue();
        expect.withMessage("Affected media usage")
                .that(audioMix.isAffectingUsage(AudioAttributes.USAGE_MEDIA))
                .isTrue();
    }

    private CarAudioDeviceInfo getCarAudioDeviceInfo(String address,
            boolean canBeRoutedWithDynamicPolicyMix) {
        AudioDeviceInfo audioDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(audioDeviceInfo.isSink()).thenReturn(true);
        when(audioDeviceInfo.getType()).thenReturn(AudioDeviceInfo.TYPE_BUS);
        when(audioDeviceInfo.getAddress()).thenReturn(address);
        CarAudioDeviceInfo carAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        when(carAudioDeviceInfo.getAddress()).thenReturn(address);
        when(carAudioDeviceInfo.getAudioDeviceInfo()).thenReturn(audioDeviceInfo);
        when(carAudioDeviceInfo.getEncodingFormat())
                .thenReturn(AudioFormat.ENCODING_PCM_16BIT);
        when(carAudioDeviceInfo.getChannelCount()).thenReturn(2);
        when(carAudioDeviceInfo.canBeRoutedWithDynamicPolicyMix()).thenReturn(
                canBeRoutedWithDynamicPolicyMix);
        return carAudioDeviceInfo;
    }

    private static final class VolumeGroupBuilder {
        private SparseArray<String> mDeviceAddresses = new SparseArray<>();
        private CarAudioDeviceInfo mCarAudioDeviceInfoMock;
        private ArrayMap<String, List<Integer>> mUsagesDeviceAddresses = new ArrayMap<>();

        VolumeGroupBuilder addDeviceAddressAndContexts(@AudioContext int context, String address) {
            mDeviceAddresses.put(context, address);
            return this;
        }

        VolumeGroupBuilder addDeviceAddressAndUsages(int usage, String address) {
            if (!mUsagesDeviceAddresses.containsKey(address)) {
                mUsagesDeviceAddresses.put(address, new ArrayList<>());
            }
            mUsagesDeviceAddresses.get(address).add(usage);
            return this;
        }

        VolumeGroupBuilder addCarAudioDeviceInfoMock(CarAudioDeviceInfo infoMock) {
            mCarAudioDeviceInfoMock = infoMock;
            return this;
        }

        CarVolumeGroup build() {
            CarVolumeGroup carVolumeGroup = mock(CarVolumeGroup.class);
            Map<String, ArrayList<Integer>> addressToContexts = new ArrayMap<>();
            @AudioContext int[] contexts = new int[mDeviceAddresses.size()];

            for (int index = 0; index < mDeviceAddresses.size(); index++) {
                @AudioContext int context = mDeviceAddresses.keyAt(index);
                String address = mDeviceAddresses.get(context);
                when(carVolumeGroup.getAddressForContext(context)).thenReturn(address);
                if (!addressToContexts.containsKey(address)) {
                    addressToContexts.put(address, new ArrayList<>());
                }
                addressToContexts.get(address).add(context);
                contexts[index] = context;
            }

            for (int index = 0; index < mUsagesDeviceAddresses.size(); index++) {
                String address = mUsagesDeviceAddresses.keyAt(index);
                List<Integer> usagesForAddress = mUsagesDeviceAddresses.get(address);
                when(carVolumeGroup.getAllSupportedUsagesForAddress(eq(address)))
                        .thenReturn(usagesForAddress);
            }

            when(carVolumeGroup.getContexts()).thenReturn(contexts);

            for (String address : addressToContexts.keySet()) {
                when(carVolumeGroup.getContextsForAddress(address))
                        .thenReturn(ImmutableList.copyOf(addressToContexts.get(address)));
            }
            when(carVolumeGroup.getAddresses())
                    .thenReturn(ImmutableList.copyOf(addressToContexts.keySet()));

            when(carVolumeGroup.getCarAudioDeviceInfoForAddress(any()))
                    .thenReturn(mCarAudioDeviceInfoMock);

            return carVolumeGroup;
        }
    }
}
