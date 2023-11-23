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

package com.android.car.audio;

import static com.android.car.audio.CarAudioContext.ALARM;
import static com.android.car.audio.CarAudioContext.CALL;
import static com.android.car.audio.CarAudioContext.CALL_RING;
import static com.android.car.audio.CarAudioContext.EMERGENCY;
import static com.android.car.audio.CarAudioContext.MUSIC;
import static com.android.car.audio.CarAudioContext.NAVIGATION;
import static com.android.car.audio.CarAudioContext.NOTIFICATION;

import static com.google.common.truth.Truth.assertWithMessage;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.testng.Assert.expectThrows;

import android.annotation.UserIdInt;
import android.os.UserHandle;
import android.util.SparseBooleanArray;
import android.util.SparseIntArray;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.Mockito;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.List;

@RunWith(MockitoJUnitRunner.class)
public class CarVolumeGroupUnitTest {
    private static final int ZONE_ID = 0;
    private static final int GROUP_ID = 0;
    private static final int STEP_VALUE = 2;
    private static final int MIN_GAIN = 3;
    private static final int MAX_GAIN = 10;
    private static final int DEFAULT_GAIN = 5;
    private static final int DEFAULT_GAIN_INDEX = (DEFAULT_GAIN - MIN_GAIN) / STEP_VALUE;
    private static final int MIN_GAIN_INDEX = 0;
    private static final int MAX_GAIN_INDEX = (MAX_GAIN - MIN_GAIN) / STEP_VALUE;
    private static final int TEST_GAIN_INDEX = 2;
    private static final int TEST_USER_10 = 10;
    private static final int TEST_USER_11 = 11;
    private static final String MEDIA_DEVICE_ADDRESS = "music";
    private static final String NAVIGATION_DEVICE_ADDRESS = "navigation";
    private static final String OTHER_ADDRESS = "other_address";

    private CarAudioDeviceInfo mMediaDeviceInfo;
    private CarAudioDeviceInfo mNavigationDeviceInfo;

    @Mock
    CarAudioSettings mSettingsMock;

    @Before
    public void setUp() {
        mMediaDeviceInfo = new InfoBuilder(MEDIA_DEVICE_ADDRESS).build();
        mNavigationDeviceInfo = new InfoBuilder(NAVIGATION_DEVICE_ADDRESS).build();
    }

    @Test
    public void setDeviceInfoForContext_associatesDeviceAddresses() {
        CarVolumeGroup.Builder builder = getBuilder();

        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        builder.setDeviceInfoForContext(NAVIGATION, mNavigationDeviceInfo);
        CarVolumeGroup carVolumeGroup = builder.build();

        assertWithMessage("%s and %s", MEDIA_DEVICE_ADDRESS, NAVIGATION_DEVICE_ADDRESS)
                .that(carVolumeGroup.getAddresses()).containsExactly(MEDIA_DEVICE_ADDRESS,
                NAVIGATION_DEVICE_ADDRESS);
    }

    @Test
    public void setDeviceInfoForContext_associatesContexts() {
        CarVolumeGroup.Builder builder = getBuilder();

        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        builder.setDeviceInfoForContext(NAVIGATION, mNavigationDeviceInfo);
        CarVolumeGroup carVolumeGroup = builder.build();

        assertWithMessage("Music[%s] and Navigation[%s] Context", MUSIC, NAVIGATION)
                .that(carVolumeGroup.getContexts()).asList().containsExactly(MUSIC, NAVIGATION);
    }

    @Test
    public void setDeviceInfoForContext_withDifferentStepSize_throws() {
        CarVolumeGroup.Builder builder = getBuilder();
        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        CarAudioDeviceInfo differentStepValueDevice = new InfoBuilder(NAVIGATION_DEVICE_ADDRESS)
                .setStepValue(mMediaDeviceInfo.getStepValue() + 1).build();

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> builder.setDeviceInfoForContext(NAVIGATION,
                        differentStepValueDevice));

        assertWithMessage("setDeviceInfoForContext failure for different step size")
                .that(thrown).hasMessageThat()
                .contains("Gain controls within one group must have same step value");
    }

    @Test
    public void setDeviceInfoForContext_withSameContext_throws() {
        CarVolumeGroup.Builder builder = getBuilder();
        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> builder.setDeviceInfoForContext(MUSIC,
                        mNavigationDeviceInfo));

        assertWithMessage("setDeviceInfoForSameContext failure for repeated context")
                .that(thrown).hasMessageThat().contains("has already been set to");
    }

    @Test
    public void setDeviceInfoForContext_withFirstCall_setsMinGain() {
        CarVolumeGroup.Builder builder = getBuilder();

        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);

        assertWithMessage("Min Gain from builder")
                .that(builder.mMinGain).isEqualTo(mMediaDeviceInfo.getMinGain());
    }

    @Test
    public void setDeviceInfoForContext_withFirstCall_setsMaxGain() {
        CarVolumeGroup.Builder builder = getBuilder();

        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);

        assertWithMessage("Max Gain from builder")
                .that(builder.mMaxGain).isEqualTo(mMediaDeviceInfo.getMaxGain());
    }

    @Test
    public void setDeviceInfoForContext_withFirstCall_setsDefaultGain() {
        CarVolumeGroup.Builder builder = getBuilder();

        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);

        assertWithMessage("Default Gain from builder")
                .that(builder.mDefaultGain).isEqualTo(mMediaDeviceInfo.getDefaultGain());
    }

    @Test
    public void setDeviceInfoForContext_SecondCallWithSmallerMinGain_updatesMinGain() {
        CarVolumeGroup.Builder builder = getBuilder();
        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        CarAudioDeviceInfo secondInfo = new InfoBuilder(NAVIGATION_DEVICE_ADDRESS)
                .setMinGain(mMediaDeviceInfo.getMinGain() - 1).build();

        builder.setDeviceInfoForContext(NAVIGATION, secondInfo);

        assertWithMessage("Second, smaller min gain from builder")
                .that(builder.mMinGain).isEqualTo(secondInfo.getMinGain());
    }

    @Test
    public void setDeviceInfoForContext_SecondCallWithLargerMinGain_keepsFirstMinGain() {
        CarVolumeGroup.Builder builder = getBuilder();
        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        CarAudioDeviceInfo secondInfo = new InfoBuilder(NAVIGATION_DEVICE_ADDRESS)
                .setMinGain(mMediaDeviceInfo.getMinGain() + 1).build();

        builder.setDeviceInfoForContext(NAVIGATION, secondInfo);

        assertWithMessage("First, smaller min gain from builder")
                .that(builder.mMinGain).isEqualTo(mMediaDeviceInfo.getMinGain());
    }

    @Test
    public void setDeviceInfoForContext_SecondCallWithLargerMaxGain_updatesMaxGain() {
        CarVolumeGroup.Builder builder = getBuilder();
        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        CarAudioDeviceInfo secondInfo = new InfoBuilder(NAVIGATION_DEVICE_ADDRESS)
                .setMaxGain(mMediaDeviceInfo.getMaxGain() + 1).build();

        builder.setDeviceInfoForContext(NAVIGATION, secondInfo);

        assertWithMessage("Second, larger max gain from builder")
                .that(builder.mMaxGain).isEqualTo(secondInfo.getMaxGain());
    }

    @Test
    public void setDeviceInfoForContext_SecondCallWithSmallerMaxGain_keepsFirstMaxGain() {
        CarVolumeGroup.Builder builder = getBuilder();
        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        CarAudioDeviceInfo secondInfo = new InfoBuilder(NAVIGATION_DEVICE_ADDRESS)
                .setMaxGain(mMediaDeviceInfo.getMaxGain() - 1).build();

        builder.setDeviceInfoForContext(NAVIGATION, secondInfo);

        assertWithMessage("First, larger max gain from builder")
                .that(builder.mMaxGain).isEqualTo(mMediaDeviceInfo.getMaxGain());
    }

    @Test
    public void setDeviceInfoForContext_SecondCallWithLargerDefaultGain_updatesDefaultGain() {
        CarVolumeGroup.Builder builder = getBuilder();
        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        CarAudioDeviceInfo secondInfo = new InfoBuilder(NAVIGATION_DEVICE_ADDRESS)
                .setDefaultGain(mMediaDeviceInfo.getDefaultGain() + 1).build();

        builder.setDeviceInfoForContext(NAVIGATION, secondInfo);

        assertWithMessage("Second, larger default gain from builder")
                .that(builder.mDefaultGain).isEqualTo(secondInfo.getDefaultGain());
    }

    @Test
    public void setDeviceInfoForContext_SecondCallWithSmallerDefaultGain_keepsFirstDefaultGain() {
        CarVolumeGroup.Builder builder = getBuilder();
        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        CarAudioDeviceInfo secondInfo = new InfoBuilder(NAVIGATION_DEVICE_ADDRESS)
                .setDefaultGain(mMediaDeviceInfo.getDefaultGain() - 1).build();

        builder.setDeviceInfoForContext(NAVIGATION, secondInfo);

        assertWithMessage("Second, smaller default gain from builder")
                .that(builder.mDefaultGain).isEqualTo(mMediaDeviceInfo.getDefaultGain());
    }

    @Test
    public void builderBuild_withNoCallToSetDeviceInfoForContext_throws() {
        CarVolumeGroup.Builder builder = getBuilder();

        Exception e = expectThrows(IllegalArgumentException.class, builder::build);

        assertWithMessage("Builder build failure").that(e).hasMessageThat()
                .isEqualTo(
                        "setDeviceInfoForContext has to be called at least once before building");
    }

    @Test
    public void builderBuild_withNoStoredGain_usesDefaultGain() {
        CarVolumeGroup.Builder builder = getBuilder().setDeviceInfoForContext(MUSIC,
                mMediaDeviceInfo);
        when(mSettingsMock.getStoredVolumeGainIndexForUser(UserHandle.USER_CURRENT, ZONE_ID,
                GROUP_ID)).thenReturn(-1);


        CarVolumeGroup carVolumeGroup = builder.build();

        assertWithMessage("Current gain index")
                .that(carVolumeGroup.getCurrentGainIndex()).isEqualTo(DEFAULT_GAIN_INDEX);
    }

    @Test
    public void builderBuild_withTooLargeStoredGain_usesDefaultGain() {
        CarVolumeGroup.Builder builder = getBuilder().setDeviceInfoForContext(MUSIC,
                mMediaDeviceInfo);
        when(mSettingsMock.getStoredVolumeGainIndexForUser(UserHandle.USER_CURRENT, ZONE_ID,
                GROUP_ID)).thenReturn(MAX_GAIN_INDEX + 1);

        CarVolumeGroup carVolumeGroup = builder.build();

        assertWithMessage("Current gain index")
                .that(carVolumeGroup.getCurrentGainIndex()).isEqualTo(DEFAULT_GAIN_INDEX);
    }

    @Test
    public void builderBuild_withTooSmallStoredGain_usesDefaultGain() {
        CarVolumeGroup.Builder builder = getBuilder().setDeviceInfoForContext(MUSIC,
                mMediaDeviceInfo);
        when(mSettingsMock.getStoredVolumeGainIndexForUser(UserHandle.USER_CURRENT, ZONE_ID,
                GROUP_ID)).thenReturn(MIN_GAIN_INDEX - 1);

        CarVolumeGroup carVolumeGroup = builder.build();

        assertWithMessage("Current gain index")
                .that(carVolumeGroup.getCurrentGainIndex()).isEqualTo(DEFAULT_GAIN_INDEX);
    }

    @Test
    public void builderBuild_withValidStoredGain_usesStoredGain() {
        CarVolumeGroup.Builder builder = getBuilder().setDeviceInfoForContext(MUSIC,
                mMediaDeviceInfo);
        when(mSettingsMock.getStoredVolumeGainIndexForUser(UserHandle.USER_CURRENT, ZONE_ID,
                GROUP_ID)).thenReturn(MAX_GAIN_INDEX - 1);

        CarVolumeGroup carVolumeGroup = builder.build();

        assertWithMessage("Current gain index")
                .that(carVolumeGroup.getCurrentGainIndex()).isEqualTo(MAX_GAIN_INDEX - 1);
    }

    @Test
    public void getAddressForContext_withSupportedContext_returnsAddress() {
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithMusicBound();

        assertWithMessage("Supported context's address")
                .that(carVolumeGroup.getAddressForContext(MUSIC))
                .isEqualTo(mMediaDeviceInfo.getAddress());
    }

    @Test
    public void getAddressForContext_withUnsupportedContext_returnsNull() {
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithMusicBound();

        assertWithMessage("Unsupported context's address")
                .that(carVolumeGroup.getAddressForContext(NAVIGATION)).isNull();
    }

    @Test
    public void isMuted_whenDefault_returnsFalse() {
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithMusicBound();

        assertWithMessage("Default mute state")
                .that(carVolumeGroup.isMuted()).isFalse();
    }

    @Test
    public void isMuted_afterMuting_returnsTrue() {
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithMusicBound();

        carVolumeGroup.setMute(true);

        assertWithMessage("Set mute state")
                .that(carVolumeGroup.isMuted()).isTrue();
    }

    @Test
    public void isMuted_afterUnMuting_returnsFalse() {
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithMusicBound();

        carVolumeGroup.setMute(false);

        assertWithMessage("Set mute state")
                .that(carVolumeGroup.isMuted()).isFalse();
    }

    @Test
    public void setMute_withMutedState_storesValueToSetting() {
        CarAudioSettings settings = new SettingsBuilder(0, 0)
                .setMuteForUser10(false)
                .setIsPersistVolumeGroupEnabled(true)
                .build();
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithNavigationBound(settings, true);
        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        carVolumeGroup.setMute(true);

        verify(settings)
                .storeVolumeGroupMuteForUser(TEST_USER_10, 0, 0, true);
    }

    @Test
    public void setMute_withUnMutedState_storesValueToSetting() {
        CarAudioSettings settings = new SettingsBuilder(0, 0)
                .setMuteForUser10(false)
                .setIsPersistVolumeGroupEnabled(true)
                .build();
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithNavigationBound(settings, true);
        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        carVolumeGroup.setMute(false);

        verify(settings)
                .storeVolumeGroupMuteForUser(TEST_USER_10, 0, 0, false);
    }

    @Test
    public void getContextsForAddress_returnsContextsBoundToThatAddress() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        List<Integer> contextsList = carVolumeGroup.getContextsForAddress(MEDIA_DEVICE_ADDRESS);

        assertWithMessage("Contexts for bounded address %s", MEDIA_DEVICE_ADDRESS)
                .that(contextsList).containsExactly(MUSIC,
                CALL, CALL_RING);
    }

    @Test
    public void getContextsForAddress_returnsEmptyArrayIfAddressNotBound() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        List<Integer> contextsList = carVolumeGroup.getContextsForAddress(OTHER_ADDRESS);

        assertWithMessage("Contexts for non-bounded address %s", OTHER_ADDRESS)
                .that(contextsList).isEmpty();
    }

    @Test
    public void getCarAudioDeviceInfoForAddress_returnsExpectedDevice() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        CarAudioDeviceInfo actualDevice = carVolumeGroup.getCarAudioDeviceInfoForAddress(
                MEDIA_DEVICE_ADDRESS);

        assertWithMessage("Device information for bounded address %s", MEDIA_DEVICE_ADDRESS)
                .that(actualDevice).isEqualTo(mMediaDeviceInfo);
    }

    @Test
    public void getCarAudioDeviceInfoForAddress_returnsNullIfAddressNotBound() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        CarAudioDeviceInfo actualDevice = carVolumeGroup.getCarAudioDeviceInfoForAddress(
                OTHER_ADDRESS);

        assertWithMessage("Device information for non-bounded address %s", OTHER_ADDRESS)
                .that(actualDevice).isNull();
    }

    @Test
    public void setCurrentGainIndex_setsGainOnAllBoundDevices() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        carVolumeGroup.setCurrentGainIndex(TEST_GAIN_INDEX);

        verify(mMediaDeviceInfo).setCurrentGain(7);
        verify(mNavigationDeviceInfo).setCurrentGain(7);
    }

    @Test
    public void setCurrentGainIndex_updatesCurrentGainIndex() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        carVolumeGroup.setCurrentGainIndex(TEST_GAIN_INDEX);

        assertWithMessage("Updated current gain index")
                .that(carVolumeGroup.getCurrentGainIndex()).isEqualTo(TEST_GAIN_INDEX);
    }

    @Test
    public void setCurrentGainIndex_checksNewGainIsAboveMin() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> carVolumeGroup.setCurrentGainIndex(MIN_GAIN_INDEX - 1));
        assertWithMessage("Set out of bound gain index failure")
                .that(thrown).hasMessageThat()
                .contains("Gain out of range (" + MIN_GAIN + ":" + MAX_GAIN + ")");
    }

    @Test
    public void setCurrentGainIndex_checksNewGainIsBelowMax() {
        CarVolumeGroup carVolumeGroup = testVolumeGroupSetup();

        IllegalArgumentException thrown = expectThrows(IllegalArgumentException.class,
                () -> carVolumeGroup.setCurrentGainIndex(MAX_GAIN_INDEX + 1));
        assertWithMessage("Set out of bound gain index failure")
                .that(thrown).hasMessageThat()
                .contains("Gain out of range (" + MIN_GAIN + ":" + MAX_GAIN + ")");
    }

    @Test
    public void setCurrentGainIndex_setsCurrentGainIndexForUser() {
        CarAudioSettings settings = new SettingsBuilder(0, 0)
                .setGainIndexForUser(TEST_USER_11)
                .build();
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithNavigationBound(settings, false);
        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_11);

        carVolumeGroup.setCurrentGainIndex(MIN_GAIN);

        verify(settings).storeVolumeGainIndexForUser(TEST_USER_11, 0, 0, MIN_GAIN);
    }

    @Test
    public void setCurrentGainIndex_setsCurrentGainIndexForDefaultUser() {
        CarAudioSettings settings = new SettingsBuilder(0, 0)
                .setGainIndexForUser(UserHandle.USER_CURRENT)
                .build();
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithNavigationBound(settings, false);

        carVolumeGroup.setCurrentGainIndex(MIN_GAIN);

        verify(settings)
                .storeVolumeGainIndexForUser(UserHandle.USER_CURRENT, 0, 0, MIN_GAIN);
    }

    @Test
    public void loadVolumesSettingsForUser_withMutedState_loadsMuteStateForUser() {
        CarVolumeGroup carVolumeGroup = getVolumeGroupWithMuteAndNavBound(true, true, true);

        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        assertWithMessage("Saved mute state from settings")
                .that(carVolumeGroup.isMuted()).isTrue();
    }

    @Test
    public void loadVolumesSettingsForUser_withDisabledUseVolumeGroupMute_doesNotLoadMute() {
        CarVolumeGroup carVolumeGroup = getVolumeGroupWithMuteAndNavBound(true, true, false);

        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        assertWithMessage("Default mute state")
                .that(carVolumeGroup.isMuted()).isFalse();
    }

    @Test
    public void loadVolumesSettingsForUser_withUnMutedState_loadsMuteStateForUser() {
        CarVolumeGroup carVolumeGroup = getVolumeGroupWithMuteAndNavBound(false, true, true);

        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        assertWithMessage("Saved mute state from settings")
                .that(carVolumeGroup.isMuted()).isFalse();
    }

    @Test
    public void loadVolumesSettingsForUser_withMutedStateAndNoPersist_returnsDefaultMuteState() {
        CarVolumeGroup carVolumeGroup = getVolumeGroupWithMuteAndNavBound(true, false, true);

        carVolumeGroup.loadVolumesSettingsForUser(TEST_USER_10);

        assertWithMessage("Default mute state")
                .that(carVolumeGroup.isMuted()).isFalse();
    }

    @Test
    public void hasCriticalAudioContexts_withoutCriticalContexts_returnsFalse() {
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithMusicBound();

        assertWithMessage("Group without critical audio context")
                .that(carVolumeGroup.hasCriticalAudioContexts()).isFalse();
    }

    @Test
    public void hasCriticalAudioContexts_withCriticalContexts_returnsTrue() {
        CarVolumeGroup carVolumeGroup = getBuilder()
                .setDeviceInfoForContext(EMERGENCY, mMediaDeviceInfo)
                .build();

        assertWithMessage("Group with critical audio context")
                .that(carVolumeGroup.hasCriticalAudioContexts()).isTrue();
    }

    @Test
    public void getCurrentGainIndex_whileMuted_returnsMinGain() {
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithMusicBound();
        carVolumeGroup.setCurrentGainIndex(TEST_GAIN_INDEX);

        carVolumeGroup.setMute(true);

        assertWithMessage("Muted current gain index")
                .that(carVolumeGroup.getCurrentGainIndex()).isEqualTo(MIN_GAIN_INDEX);
    }

    @Test
    public void getCurrentGainIndex_whileUnMuted_returnsLastSetGain() {
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithMusicBound();
        carVolumeGroup.setCurrentGainIndex(TEST_GAIN_INDEX);

        carVolumeGroup.setMute(false);

        assertWithMessage("Un-muted current gain index")
                .that(carVolumeGroup.getCurrentGainIndex()).isEqualTo(TEST_GAIN_INDEX);
    }

    @Test
    public void setCurrentGainIndex_whileMuted_unMutesVolumeGroup() {
        CarVolumeGroup carVolumeGroup = getCarVolumeGroupWithMusicBound();
        carVolumeGroup.setMute(true);
        carVolumeGroup.setCurrentGainIndex(TEST_GAIN_INDEX);

        assertWithMessage("Mute state after volume change")
                .that(carVolumeGroup.isMuted()).isEqualTo(false);
    }

    private CarVolumeGroup getCarVolumeGroupWithMusicBound() {
        return getBuilder()
                .setDeviceInfoForContext(MUSIC, mMediaDeviceInfo)
                .build();
    }

    private CarVolumeGroup getCarVolumeGroupWithNavigationBound(CarAudioSettings settings,
            boolean useCarVolumeGroupMute) {
        return new CarVolumeGroup.Builder(0, 0, settings, useCarVolumeGroupMute)
                .setDeviceInfoForContext(NAVIGATION, mNavigationDeviceInfo)
                .build();
    }

    CarVolumeGroup getVolumeGroupWithMuteAndNavBound(boolean isMuted, boolean persistMute,
            boolean useCarVolumeGroupMute) {
        CarAudioSettings settings = new SettingsBuilder(0, 0)
                .setMuteForUser10(isMuted)
                .setIsPersistVolumeGroupEnabled(persistMute)
                .build();
        return getCarVolumeGroupWithNavigationBound(settings, useCarVolumeGroupMute);
    }

    private CarVolumeGroup testVolumeGroupSetup() {
        CarVolumeGroup.Builder builder = getBuilder();

        builder.setDeviceInfoForContext(MUSIC, mMediaDeviceInfo);
        builder.setDeviceInfoForContext(CALL, mMediaDeviceInfo);
        builder.setDeviceInfoForContext(CALL_RING, mMediaDeviceInfo);

        builder.setDeviceInfoForContext(NAVIGATION, mNavigationDeviceInfo);
        builder.setDeviceInfoForContext(ALARM, mNavigationDeviceInfo);
        builder.setDeviceInfoForContext(NOTIFICATION, mNavigationDeviceInfo);

        return builder.build();
    }

    CarVolumeGroup.Builder getBuilder() {
        return new CarVolumeGroup.Builder(ZONE_ID, GROUP_ID, mSettingsMock, true);
    }

    private static final class SettingsBuilder {
        private final SparseIntArray mStoredGainIndexes = new SparseIntArray();
        private final SparseBooleanArray mStoreMuteStates = new SparseBooleanArray();
        private final int mZoneId;
        private final int mGroupId;

        private boolean mPersistMute;

        SettingsBuilder(int zoneId, int groupId) {
            mZoneId = zoneId;
            mGroupId = groupId;
        }

        SettingsBuilder setGainIndexForUser(@UserIdInt int userId) {
            mStoredGainIndexes.put(userId, TEST_GAIN_INDEX);
            return this;
        }

        SettingsBuilder setMuteForUser10(boolean mute) {
            mStoreMuteStates.put(CarVolumeGroupUnitTest.TEST_USER_10, mute);
            return this;
        }

        SettingsBuilder setIsPersistVolumeGroupEnabled(boolean persistMute) {
            mPersistMute = persistMute;
            return this;
        }

        CarAudioSettings build() {
            CarAudioSettings settingsMock = Mockito.mock(CarAudioSettings.class);
            for (int storeIndex = 0; storeIndex < mStoredGainIndexes.size(); storeIndex++) {
                int gainUserId = mStoredGainIndexes.keyAt(storeIndex);
                when(settingsMock
                        .getStoredVolumeGainIndexForUser(gainUserId, mZoneId,
                                mGroupId)).thenReturn(
                        mStoredGainIndexes.get(gainUserId, DEFAULT_GAIN));
            }
            for (int muteIndex = 0; muteIndex < mStoreMuteStates.size(); muteIndex++) {
                int muteUserId = mStoreMuteStates.keyAt(muteIndex);
                when(settingsMock.getVolumeGroupMuteForUser(muteUserId, mZoneId, mGroupId))
                        .thenReturn(mStoreMuteStates.get(muteUserId, false));
                when(settingsMock.isPersistVolumeGroupMuteEnabled(muteUserId))
                        .thenReturn(mPersistMute);
            }
            return settingsMock;
        }
    }

    private static final class InfoBuilder {
        private final String mAddress;

        private int mStepValue = STEP_VALUE;
        private int mDefaultGain = DEFAULT_GAIN;
        private int mMinGain = MIN_GAIN;
        private int mMaxGain = MAX_GAIN;

        InfoBuilder(String address) {
            mAddress = address;
        }

        InfoBuilder setStepValue(int stepValue) {
            mStepValue = stepValue;
            return this;
        }

        InfoBuilder setDefaultGain(int defaultGain) {
            mDefaultGain = defaultGain;
            return this;
        }

        InfoBuilder setMinGain(int minGain) {
            mMinGain = minGain;
            return this;
        }

        InfoBuilder setMaxGain(int maxGain) {
            mMaxGain = maxGain;
            return this;
        }

        CarAudioDeviceInfo build() {
            CarAudioDeviceInfo infoMock = Mockito.mock(CarAudioDeviceInfo.class);
            when(infoMock.getStepValue()).thenReturn(mStepValue);
            when(infoMock.getDefaultGain()).thenReturn(mDefaultGain);
            when(infoMock.getMaxGain()).thenReturn(mMaxGain);
            when(infoMock.getMinGain()).thenReturn(mMinGain);
            when(infoMock.getAddress()).thenReturn(mAddress);
            return infoMock;
        }
    }
}
