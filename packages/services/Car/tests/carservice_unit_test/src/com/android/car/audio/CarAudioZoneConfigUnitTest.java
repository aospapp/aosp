/*
 * Copyright (C) 2023 The Android Open Source Project
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

import static android.car.media.CarVolumeGroupEvent.EVENT_TYPE_ATTENUATION_CHANGED;
import static android.car.media.CarVolumeGroupEvent.EVENT_TYPE_MUTE_CHANGED;
import static android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE;
import static android.media.AudioAttributes.USAGE_ASSISTANT;
import static android.media.AudioAttributes.USAGE_MEDIA;


import static com.android.car.audio.CarAudioContext.AudioContext;

import static org.junit.Assert.assertThrows;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.media.CarAudioManager;
import android.car.media.CarAudioZoneConfigInfo;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupInfo;
import android.car.test.AbstractExpectableTestCase;
import android.hardware.automotive.audiocontrol.AudioGainConfigInfo;
import android.hardware.automotive.audiocontrol.Reasons;
import android.media.AudioAttributes;
import android.media.AudioDeviceInfo;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public final class CarAudioZoneConfigUnitTest extends AbstractExpectableTestCase {
    private static final String MUSIC_ADDRESS = "bus0_music";
    private static final String NAV_ADDRESS = "bus1_nav";
    private static final String VOICE_ADDRESS = "bus3_voice";

    private static final int TEST_ZONE_CONFIG_ID = 1;
    private static final String TEST_ZONE_CONFIG_NAME = "Config 0";

    private static final String TEST_MUSIC_GROUP_NAME = "Music group name";
    private static final String TEST_NAV_GROUP_NAME = "Nav group name";
    private static final String TEST_VOICE_GROUP_NAME = "Voice group name";
    private static final int TEST_ZONE_ID = 0;
    private static final int TEST_MUSIC_GROUP_ID = 0;
    private static final int TEST_NAV_GROUP_ID = 20;
    private static final int TEST_VOICE_GROUP_ID = 30;

    private static final AudioAttributes TEST_MEDIA_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA);
    private static final AudioAttributes TEST_ASSISTANT_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANT);
    private static final AudioAttributes TEST_NAVIGATION_ATTRIBUTE =
            CarAudioContext.getAudioAttributeFromUsage(USAGE_ASSISTANCE_NAVIGATION_GUIDANCE);

    private static final CarAudioContext TEST_CAR_AUDIO_CONTEXT = new CarAudioContext(
            CarAudioContext.getAllContextsInfo(), /* useCoreAudioRouting= */ false);

    @AudioContext
    private static final int TEST_MEDIA_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_MEDIA_ATTRIBUTE);
    @AudioContext
    private static final int TEST_ASSISTANT_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_ASSISTANT_ATTRIBUTE);
    @AudioContext
    private static final int TEST_NAVIGATION_CONTEXT =
            TEST_CAR_AUDIO_CONTEXT.getContextForAudioAttribute(TEST_NAVIGATION_ATTRIBUTE);

    @Mock
    private CarVolumeGroup mMockMusicGroup;
    @Mock
    private CarVolumeGroup mMockNavGroup;
    @Mock
    private CarVolumeGroup mMockVoiceGroup;

    private CarAudioZoneConfig.Builder mTestAudioZoneConfigBuilder;

    @Before
    public void setUp() {
        mTestAudioZoneConfigBuilder = new CarAudioZoneConfig.Builder(TEST_ZONE_CONFIG_NAME,
                CarAudioManager.PRIMARY_AUDIO_ZONE, TEST_ZONE_CONFIG_ID, /* isDefault= */ true);

        mMockMusicGroup = new VolumeGroupBuilder().setName(TEST_MUSIC_GROUP_NAME)
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .setZoneId(TEST_ZONE_ID).setGroupId(TEST_MUSIC_GROUP_ID).build();

        mMockNavGroup = new VolumeGroupBuilder().setName(TEST_NAV_GROUP_NAME)
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, NAV_ADDRESS)
                .setZoneId(TEST_ZONE_ID).setGroupId(TEST_NAV_GROUP_ID).build();

        mMockVoiceGroup = new VolumeGroupBuilder().setName(TEST_VOICE_GROUP_NAME)
                .addDeviceAddressAndContexts(TEST_ASSISTANT_CONTEXT, VOICE_ADDRESS)
                .setZoneId(TEST_ZONE_ID).setGroupId(TEST_VOICE_GROUP_ID).build();
    }

    @Test
    public void getZoneId_fromBuilder() {
        expectWithMessage("Builder zone id").that(mTestAudioZoneConfigBuilder.getZoneId())
                .isEqualTo(CarAudioManager.PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneConfigId_fromBuilder() {
        expectWithMessage("Builder zone configuration id")
                .that(mTestAudioZoneConfigBuilder.getZoneConfigId()).isEqualTo(TEST_ZONE_CONFIG_ID);
    }

    @Test
    public void getZoneId() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.build();

        expectWithMessage("Zone id")
                .that(zoneConfig.getZoneId()).isEqualTo(CarAudioManager.PRIMARY_AUDIO_ZONE);
    }

    @Test
    public void getZoneConfigId() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.build();

        expectWithMessage("Zone configuration id")
                .that(zoneConfig.getZoneConfigId()).isEqualTo(TEST_ZONE_CONFIG_ID);
    }

    @Test
    public void getName() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.build();

        expectWithMessage("Zone config name")
                .that(zoneConfig.getName()).isEqualTo(TEST_ZONE_CONFIG_NAME);
    }

    @Test
    public void getVolumeGroup_withName() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).addVolumeGroup(mMockVoiceGroup).build();

        expectWithMessage("Volume group with name %s", TEST_MUSIC_GROUP_NAME)
                .that(zoneConfig.getVolumeGroup(TEST_MUSIC_GROUP_NAME)).isEqualTo(mMockMusicGroup);
    }

    @Test
    public void getVolumeGroup_withNameNotFound_returnsNull() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).addVolumeGroup(mMockVoiceGroup).build();

        expectWithMessage("Volume group with name not found")
                .that(zoneConfig.getVolumeGroup("Invalid volume group name")).isNull();
    }

    @Test
    public void getVolumeGroup_withGroupId() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).addVolumeGroup(mMockVoiceGroup).build();
        int groupId = 2;

        expectWithMessage("Volume group with id %s", groupId)
                .that(zoneConfig.getVolumeGroup(groupId)).isEqualTo(mMockVoiceGroup);
    }

    @Test
    public void getVolumeGroup_withGroupIdOutOfRange() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).addVolumeGroup(mMockVoiceGroup).build();
        int indexOutOfRange = 4;

        IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class,
                () -> zoneConfig.getVolumeGroup(indexOutOfRange));

        expectWithMessage("Invalid volume group id exception").that(thrown).hasMessageThat()
                .contains("is out of range");
    }

    @Test
    public void isDefault_forDefaultConfig_returnsTrue() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.build();

        expectWithMessage("Default zone configuration").that(zoneConfig.isDefault()).isTrue();
    }

    @Test
    public void isDefault_forNonDefaultConfig_returnsFalse() {
        CarAudioZoneConfig zoneConfig = new CarAudioZoneConfig.Builder(TEST_ZONE_CONFIG_NAME,
                CarAudioManager.PRIMARY_AUDIO_ZONE, TEST_ZONE_CONFIG_ID, /* isDefault= */ false)
                .build();

        expectWithMessage("Non-default zone configuration").that(zoneConfig.isDefault()).isFalse();
    }

    @Test
    public void getVolumeGroupCount() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).addVolumeGroup(mMockVoiceGroup).build();

        expectWithMessage("Volume group count")
                .that(zoneConfig.getVolumeGroupCount()).isEqualTo(3);
    }

    @Test
    public void getCarVolumeGroups() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).addVolumeGroup(mMockVoiceGroup).build();

        CarVolumeGroup[] volumeGroups = zoneConfig.getVolumeGroups();

        expectWithMessage("Car volume groups").that(volumeGroups).asList()
                .containsExactly(mMockMusicGroup, mMockNavGroup, mMockVoiceGroup).inOrder();
    }

    @Test
    public void synchronizeCurrentGainIndex() {
        int musicGroupGainIndex = 1;
        int navGroupGainIndex = 1;
        when(mMockMusicGroup.getCurrentGainIndex()).thenReturn(musicGroupGainIndex);
        when(mMockNavGroup.getCurrentGainIndex()).thenReturn(navGroupGainIndex);
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).build();

        zoneConfig.synchronizeCurrentGainIndex();

        verify(mMockMusicGroup).setCurrentGainIndex(musicGroupGainIndex);
        verify(mMockNavGroup).setCurrentGainIndex(navGroupGainIndex);
    }

    @Test
    public void updateVolumeGroupsSettingsForUser() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).build();
        int userId = 11;

        zoneConfig.updateVolumeGroupsSettingsForUser(userId);

        verify(mMockMusicGroup).loadVolumesSettingsForUser(userId);
        verify(mMockNavGroup).loadVolumesSettingsForUser(userId);
    }

    @Test
    public void validateCanUseDynamicMixRouting_addressSharedAmongGroups_forbidUseDynamicRouting() {
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo).build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo).build();
        CarAudioZoneConfig zoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));

        zoneConfig.validateCanUseDynamicMixRouting(/* useCoreAudioRouting= */ true);

        verify(musicCarAudioDeviceInfo).resetCanBeRoutedWithDynamicPolicyMix();
    }

    @Test
    public void validateZoneWhenUsageSharedAmongContext_usingCoreRouting_forbidUseDynamicRouting() {
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarAudioDeviceInfo navCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        when(musicCarAudioDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);
        when(navCarAudioDeviceInfo.getAddress()).thenReturn(NAV_ADDRESS);
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addDeviceAddressAndUsages(USAGE_MEDIA, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo).build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, NAV_ADDRESS)
                .addDeviceAddressAndUsages(USAGE_MEDIA, NAV_ADDRESS)
                .addCarAudioDeviceInfoMock(navCarAudioDeviceInfo).build();
        CarAudioZoneConfig zoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));

        expectWithMessage("Zone configuration validates when using core routing and usage is "
                + "shared among context")
                .that(zoneConfig.validateCanUseDynamicMixRouting(/* useCoreAudioRouting= */ true))
                .isTrue();
        verify(musicCarAudioDeviceInfo).resetCanBeRoutedWithDynamicPolicyMix();
        verify(navCarAudioDeviceInfo).resetCanBeRoutedWithDynamicPolicyMix();
    }

    @Test
    public void validateZoneWhenUsageSharedAmongContext_notUsingCoreRouting_fails() {
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarAudioDeviceInfo navCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        when(musicCarAudioDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);
        when(navCarAudioDeviceInfo.getAddress()).thenReturn(NAV_ADDRESS);
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addDeviceAddressAndUsages(USAGE_MEDIA, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo).build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, NAV_ADDRESS)
                .addDeviceAddressAndUsages(USAGE_MEDIA, NAV_ADDRESS)
                .addCarAudioDeviceInfoMock(navCarAudioDeviceInfo).build();
        CarAudioZoneConfig zoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));

        expectWithMessage("Zone configuration does not validate when not using core routing and "
                + "usage is shared among contexts")
                .that(zoneConfig.validateCanUseDynamicMixRouting(/* useCoreAudioRouting= */ false))
                .isFalse();
        verify(musicCarAudioDeviceInfo, never()).resetCanBeRoutedWithDynamicPolicyMix();
        verify(navCarAudioDeviceInfo, never()).resetCanBeRoutedWithDynamicPolicyMix();
    }

    @Test
    public void validateVolumeGroups_notUsingCoreAudioRouting_succeeds() {
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS).build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, NAV_ADDRESS).build();
        CarVolumeGroup mockAllOtherContextsGroup = mockContextsExceptMediaAndNavigation();
        CarAudioZoneConfig zoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic, mockAllOtherContextsGroup));

        expectWithMessage("Volume group validates when not using core audio routing")
                .that(zoneConfig.validateVolumeGroups(TEST_CAR_AUDIO_CONTEXT,
                        /* useCoreAudioRouting= */ false)).isTrue();
    }

    @Test
    public void validateVolumeGroups_usingCoreAudioRouting_succeeds() {
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS).build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, NAV_ADDRESS).build();
        CarVolumeGroup mockAllOtherContextsGroup = mockContextsExceptMediaAndNavigation();
        CarAudioZoneConfig zoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic, mockAllOtherContextsGroup));

        expectWithMessage("Volume group validates when using core audio routing")
                .that(zoneConfig.validateVolumeGroups(TEST_CAR_AUDIO_CONTEXT,
                        /* useCoreAudioRouting= */ true)).isTrue();
    }

    @Test
    public void validateZoneConfigs_withAddressSharedAmongGroupNotUsingCoreAudioRouting_fails() {
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS).build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, MUSIC_ADDRESS).build();
        CarVolumeGroup mockAllOtherContextsGroup = mockContextsExceptMediaAndNavigation();
        CarAudioZoneConfig zoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic, mockAllOtherContextsGroup));

        expectWithMessage("Volume group does not validate when not using core audio routing")
                .that(zoneConfig.validateVolumeGroups(TEST_CAR_AUDIO_CONTEXT,
                        /* useCoreAudioRouting= */ false)).isFalse();
    }

    @Test
    public void validateZoneConfigs_withAddressSharedAmongGroupUsingCoreAudioRouting_succeeds() {
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS).build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_NAVIGATION_CONTEXT, MUSIC_ADDRESS).build();
        CarVolumeGroup mockAllOtherContextsGroup = mockContextsExceptMediaAndNavigation();
        CarAudioZoneConfig zoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic, mockAllOtherContextsGroup));

        expectWithMessage("Volume group validates when using core audio routing")
                .that(zoneConfig.validateVolumeGroups(TEST_CAR_AUDIO_CONTEXT,
                        /* useCoreAudioRouting= */ true)).isTrue();
    }

    @Test
    public void getAudioDeviceInfos() {
        AudioDeviceInfo musicAudioDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        AudioDeviceInfo navAudioDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarAudioDeviceInfo navCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        when(musicCarAudioDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);
        when(navCarAudioDeviceInfo.getAddress()).thenReturn(NAV_ADDRESS);
        when(musicCarAudioDeviceInfo.getAudioDeviceInfo()).thenReturn(musicAudioDeviceInfo);
        when(navCarAudioDeviceInfo.getAudioDeviceInfo()).thenReturn(navAudioDeviceInfo);
        when(navCarAudioDeviceInfo.canBeRoutedWithDynamicPolicyMix()).thenReturn(false);
        when(musicCarAudioDeviceInfo.canBeRoutedWithDynamicPolicyMix()).thenReturn(true);
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo).build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, NAV_ADDRESS)
                .addCarAudioDeviceInfoMock(navCarAudioDeviceInfo).build();
        CarAudioZoneConfig zoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));

        expectWithMessage("Zone configuration groups devices")
                .that(zoneConfig.getAudioDeviceInfos())
                .containsExactlyElementsIn(List.of(musicAudioDeviceInfo, navAudioDeviceInfo));
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withNullAudioDeviceInfo_returnsFalse() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup).build();

        expectWithMessage("Null audio device info")
                .that(zoneConfig.isAudioDeviceInfoValidForZone(null)).isFalse();
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withNullDeviceAddress_returnsFalse() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup).build();
        AudioDeviceInfo nullAddressDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(nullAddressDeviceInfo.getAddress()).thenReturn(null);

        expectWithMessage("Invalid audio device info").that(
                zoneConfig.isAudioDeviceInfoValidForZone(nullAddressDeviceInfo)).isFalse();
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withEmptyDeviceAddress_returnsFalse() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup).build();
        AudioDeviceInfo nullAddressDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(nullAddressDeviceInfo.getAddress()).thenReturn("");

        expectWithMessage("Device info with invalid address").that(
                zoneConfig.isAudioDeviceInfoValidForZone(nullAddressDeviceInfo)).isFalse();
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withDeviceAddressNotInZone_returnsFalse() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup).build();
        AudioDeviceInfo nullAddressDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(nullAddressDeviceInfo.getAddress()).thenReturn(VOICE_ADDRESS);

        expectWithMessage("Non zone audio device info").that(
                zoneConfig.isAudioDeviceInfoValidForZone(nullAddressDeviceInfo)).isFalse();
    }

    @Test
    public void isAudioDeviceInfoValidForZone_withDeviceAddressInZone_returnsTrue() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder
                .addVolumeGroup(mMockMusicGroup).build();
        AudioDeviceInfo nullAddressDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        when(nullAddressDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);

        expectWithMessage("Valid audio device info").that(
                zoneConfig.isAudioDeviceInfoValidForZone(nullAddressDeviceInfo)).isTrue();
    }

    @Test
    public void getAudioDeviceInfosSupportingDynamicMix() {
        AudioDeviceInfo musicAudioDeviceInfo = Mockito.mock(AudioDeviceInfo.class);
        CarAudioDeviceInfo musicCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        CarAudioDeviceInfo navCarAudioDeviceInfo = Mockito.mock(CarAudioDeviceInfo.class);
        when(musicCarAudioDeviceInfo.getAddress()).thenReturn(MUSIC_ADDRESS);
        when(navCarAudioDeviceInfo.getAddress()).thenReturn(NAV_ADDRESS);
        when(musicCarAudioDeviceInfo.getAudioDeviceInfo()).thenReturn(musicAudioDeviceInfo);
        when(navCarAudioDeviceInfo.canBeRoutedWithDynamicPolicyMix()).thenReturn(false);
        when(musicCarAudioDeviceInfo.canBeRoutedWithDynamicPolicyMix()).thenReturn(true);
        CarVolumeGroup mockMusicGroup = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, MUSIC_ADDRESS)
                .addCarAudioDeviceInfoMock(musicCarAudioDeviceInfo).build();
        CarVolumeGroup mockNavGroupRoutingOnMusic = new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(TEST_MEDIA_CONTEXT, NAV_ADDRESS)
                .addCarAudioDeviceInfoMock(navCarAudioDeviceInfo).build();
        CarAudioZoneConfig zoneConfig = buildZoneConfig(
                List.of(mockMusicGroup, mockNavGroupRoutingOnMusic));

        expectWithMessage("Dynamic mix ready devices")
                .that(zoneConfig.getAudioDeviceInfosSupportingDynamicMix())
                .containsExactly(musicAudioDeviceInfo);
    }

    @Test
    public void onAudioGainChanged_withDeviceAddressesInZone() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).build();
        List<Integer> reasons = List.of(Reasons.REMOTE_MUTE, Reasons.NAV_DUCKING);
        AudioGainConfigInfo musicGainInfo = new AudioGainConfigInfo();
        musicGainInfo.zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
        musicGainInfo.devicePortAddress = MUSIC_ADDRESS;
        musicGainInfo.volumeIndex = 666;
        CarAudioGainConfigInfo carMusicGainInfo = new CarAudioGainConfigInfo(musicGainInfo);
        when(mMockNavGroup.onAudioGainChanged(any(), any())).thenReturn(EVENT_TYPE_MUTE_CHANGED);
        when(mMockMusicGroup.onAudioGainChanged(any(), any()))
                .thenReturn(EVENT_TYPE_ATTENUATION_CHANGED);
        when(mMockVoiceGroup.onAudioGainChanged(any(), any()))
                .thenReturn(EVENT_TYPE_ATTENUATION_CHANGED);

        List<CarVolumeGroupEvent> events = zoneConfig.onAudioGainChanged(reasons,
                List.of(carMusicGainInfo));

        expectWithMessage("Changed audio gain").that(events.isEmpty()).isFalse();
        verify(mMockMusicGroup).onAudioGainChanged(reasons, carMusicGainInfo);
        verify(mMockNavGroup, never()).onAudioGainChanged(any(), any());
        verify(mMockVoiceGroup, never()).onAudioGainChanged(any(), any());
    }

    @Test
    public void onAudioGainChanged_withoutAnyDeviceAddressInZone() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockVoiceGroup)
                .build();
        List<Integer> reasons = List.of(Reasons.REMOTE_MUTE, Reasons.NAV_DUCKING);

        AudioGainConfigInfo musicGainInfo = new AudioGainConfigInfo();
        musicGainInfo.zoneId = CarAudioManager.PRIMARY_AUDIO_ZONE;
        musicGainInfo.devicePortAddress = MUSIC_ADDRESS;
        musicGainInfo.volumeIndex = 666;
        CarAudioGainConfigInfo carMusicGainInfo = new CarAudioGainConfigInfo(musicGainInfo);
        when(mMockNavGroup.onAudioGainChanged(any(), any())).thenReturn(EVENT_TYPE_MUTE_CHANGED);
        when(mMockMusicGroup.onAudioGainChanged(any(), any()))
                .thenReturn(EVENT_TYPE_ATTENUATION_CHANGED);
        when(mMockVoiceGroup.onAudioGainChanged(any(), any()))
                .thenReturn(EVENT_TYPE_ATTENUATION_CHANGED);

        List<CarVolumeGroupEvent> events = zoneConfig.onAudioGainChanged(reasons,
                List.of(carMusicGainInfo));

        expectWithMessage("Changed audio gain").that(events.isEmpty()).isTrue();
        verify(mMockMusicGroup, never()).onAudioGainChanged(any(), any());
        verify(mMockNavGroup, never()).onAudioGainChanged(any(), any());
        verify(mMockVoiceGroup, never()).onAudioGainChanged(any(), any());
    }

    @Test
    public void getCarVolumeGroupInfos() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.addVolumeGroup(mMockMusicGroup)
                .addVolumeGroup(mMockNavGroup).addVolumeGroup(mMockVoiceGroup).build();

        List<CarVolumeGroupInfo> infos = zoneConfig.getVolumeGroupInfos();

        expectWithMessage("Car volume group infos").that(infos).hasSize(3);
    }

    @Test
    public void getCarAudioZoneConfigInfo() {
        CarAudioZoneConfig zoneConfig = mTestAudioZoneConfigBuilder.build();
        CarAudioZoneConfigInfo zoneConfigInfoExpected = new CarAudioZoneConfigInfo(
                TEST_ZONE_CONFIG_NAME, CarAudioManager.PRIMARY_AUDIO_ZONE, TEST_ZONE_CONFIG_ID);

        CarAudioZoneConfigInfo zoneConfigInfo = zoneConfig.getCarAudioZoneConfigInfo();

        expectWithMessage("Zone configuration info")
                .that(zoneConfigInfo).isEqualTo(zoneConfigInfoExpected);
    }

    private CarAudioZoneConfig buildZoneConfig(List<CarVolumeGroup> volumeGroups) {
        CarAudioZoneConfig.Builder carAudioZoneConfigBuilder =
                new CarAudioZoneConfig.Builder("Primary zone config 0",
                        CarAudioManager.PRIMARY_AUDIO_ZONE, /* zoneConfigId= */ 0,
                        /* isDefault= */ true);
        for (int index = 0; index < volumeGroups.size(); index++) {
            carAudioZoneConfigBuilder =
                    carAudioZoneConfigBuilder.addVolumeGroup(volumeGroups.get(index));
        }
        return carAudioZoneConfigBuilder.build();
    }

    private CarVolumeGroup mockContextsExceptMediaAndNavigation() {
        return new VolumeGroupBuilder()
                .addDeviceAddressAndContexts(CarAudioContext.VOICE_COMMAND, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.CALL_RING, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.CALL, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.ALARM, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.NOTIFICATION, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.SYSTEM_SOUND, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.EMERGENCY, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.SAFETY, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.VEHICLE_STATUS, VOICE_ADDRESS)
                .addDeviceAddressAndContexts(CarAudioContext.ANNOUNCEMENT, VOICE_ADDRESS).build();
    }
}
