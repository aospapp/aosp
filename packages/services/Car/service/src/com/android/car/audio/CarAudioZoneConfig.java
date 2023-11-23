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

import static android.car.media.CarVolumeGroupEvent.EXTRA_INFO_VOLUME_INDEX_CHANGED_BY_AUDIO_SYSTEM;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.DUMP_INFO;

import android.annotation.Nullable;
import android.car.builtin.media.AudioManagerHelper;
import android.car.builtin.util.Slogf;
import android.car.media.CarAudioZoneConfigInfo;
import android.car.media.CarVolumeGroupEvent;
import android.car.media.CarVolumeGroupInfo;
import android.media.AudioDeviceInfo;
import android.util.ArrayMap;
import android.util.ArraySet;
import android.util.SparseArray;
import android.util.SparseIntArray;

import com.android.car.CarLog;
import com.android.car.audio.hal.HalAudioDeviceInfo;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;
import com.android.car.internal.util.IndentingPrintWriter;
import com.android.internal.util.Preconditions;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * A class encapsulates the configuration of an audio zone in car.
 *
 * An audio zone config can contain multiple {@link CarVolumeGroup}s.
 *
 * See also the unified car_audio_configuration.xml
 */
final class CarAudioZoneConfig {

    private static final int INVALID_GROUP_ID = -1;
    private static final int INVALID_EVENT_TYPE = 0;
    private final int mZoneId;
    private final int mZoneConfigId;
    private final String mName;
    private final boolean mIsDefault;
    private final List<CarVolumeGroup> mVolumeGroups;
    private final List<String> mGroupIdToNames;
    private final Map<String, Integer> mDeviceAddressToGroupId;

    private CarAudioZoneConfig(String name, int zoneId, int zoneConfigId, boolean isDefault,
            List<CarVolumeGroup> volumeGroups, Map<String, Integer> deviceAddressToGroupId,
            List<String> groupIdToNames) {
        mName = name;
        mZoneId = zoneId;
        mZoneConfigId = zoneConfigId;
        mIsDefault = isDefault;
        mVolumeGroups = volumeGroups;
        mDeviceAddressToGroupId = deviceAddressToGroupId;
        mGroupIdToNames = groupIdToNames;
    }

    int getZoneId() {
        return mZoneId;
    }

    int getZoneConfigId() {
        return mZoneConfigId;
    }

    String getName() {
        return mName;
    }

    boolean isDefault() {
        return mIsDefault;
    }

    @Nullable
    CarVolumeGroup getVolumeGroup(String groupName) {
        int groupId = mGroupIdToNames.indexOf(groupName);
        if (groupId < 0) {
            return null;
        }
        return getVolumeGroup(groupId);
    }

    CarVolumeGroup getVolumeGroup(int groupId) {
        Preconditions.checkArgumentInRange(groupId, 0, mVolumeGroups.size() - 1,
                "groupId(" + groupId + ") is out of range");
        return mVolumeGroups.get(groupId);
    }

    /**
     * @return Snapshot of available {@link AudioDeviceInfo}s in List.
     */
    List<AudioDeviceInfo> getAudioDeviceInfos() {
        final List<AudioDeviceInfo> devices = new ArrayList<>();
        for (int index = 0; index < mVolumeGroups.size(); index++) {
            CarVolumeGroup group = mVolumeGroups.get(index);
            List<String> addresses = group.getAddresses();
            for (int addressIndex = 0; addressIndex < addresses.size(); addressIndex++) {
                devices.add(group.getCarAudioDeviceInfoForAddress(addresses.get(addressIndex))
                        .getAudioDeviceInfo());
            }
        }
        return devices;
    }

    List<AudioDeviceInfo> getAudioDeviceInfosSupportingDynamicMix() {
        List<AudioDeviceInfo> devices = new ArrayList<>();
        for (int index = 0; index <  mVolumeGroups.size(); index++) {
            CarVolumeGroup group = mVolumeGroups.get(index);
            List<String> addresses = group.getAddresses();
            for (int addressIndex = 0; addressIndex < addresses.size(); addressIndex++) {
                String address = addresses.get(addressIndex);
                CarAudioDeviceInfo info = group.getCarAudioDeviceInfoForAddress(address);
                if (info.canBeRoutedWithDynamicPolicyMix()) {
                    devices.add(info.getAudioDeviceInfo());
                }
            }
        }
        return devices;
    }

    int getVolumeGroupCount() {
        return mVolumeGroups.size();
    }

    /**
     * @return Snapshot of available {@link CarVolumeGroup}s in array.
     */
    CarVolumeGroup[] getVolumeGroups() {
        return mVolumeGroups.toArray(new CarVolumeGroup[0]);
    }

    /**
     * Constraints applied here for checking usage of Dynamic Mixes for routing:
     *
     * - One context with same AudioAttributes usage shall not be routed to 2 different devices
     * (Dynamic Mixes supports only match on usage, not on other AudioAttributes fields.
     *
     * - One address shall not appear in 2 groups. CarAudioService cannot establish Dynamic Routing
     * rules that address multiple groups.
     */
    boolean validateCanUseDynamicMixRouting(boolean useCoreAudioRouting) {
        ArraySet<String> addresses = new ArraySet<>();
        SparseArray<CarAudioDeviceInfo> usageToDevice = new SparseArray<>();
        for (int index = 0; index <  mVolumeGroups.size(); index++) {
            CarVolumeGroup group = mVolumeGroups.get(index);

            List<String> groupAddresses = group.getAddresses();
            // Due to AudioPolicy Dynamic Mixing limitation,  rules can be made only on usage and
            // not on audio attributes.
            // When using product strategies, AudioPolicy may not simply route on usage match.
            // Ensure that a given usage can reach a single device address to enable dynamic mix.
            // Otherwise, prevent from establishing rule if supporting Core Routing.
            // Returns false if Core Routing is not supported
            for (int addressIndex = 0; addressIndex < groupAddresses.size(); addressIndex++) {
                String address = groupAddresses.get(addressIndex);
                boolean canUseDynamicMixRoutingForAddress = true;
                CarAudioDeviceInfo info = group.getCarAudioDeviceInfoForAddress(address);
                List<Integer> usagesForAddress = group.getAllSupportedUsagesForAddress(address);

                if (!addresses.add(address)) {
                    if (useCoreAudioRouting) {
                        Slogf.w(CarLog.TAG_AUDIO, "Address %s appears in two groups, prevents"
                                + " from using dynamic policy mixes for routing" , address);
                        canUseDynamicMixRoutingForAddress = false;
                    }
                }
                for (int usageIndex = 0; usageIndex < usagesForAddress.size(); usageIndex++) {
                    int usage = usagesForAddress.get(usageIndex);
                    CarAudioDeviceInfo infoForAttr = usageToDevice.get(usage);
                    if (infoForAttr != null && !infoForAttr.getAddress().equals(address)) {
                        Slogf.e(CarLog.TAG_AUDIO, "Addresses %s and %s can be reached with same"
                                        + " usage %s, prevent from using dynamic policy mixes.",
                                infoForAttr.getAddress(), address,
                                AudioManagerHelper.usageToXsdString(usage));
                        if (useCoreAudioRouting) {
                            canUseDynamicMixRoutingForAddress = false;
                            infoForAttr.resetCanBeRoutedWithDynamicPolicyMix();
                        } else {
                            return false;
                        }
                    } else {
                        usageToDevice.put(usage, info);
                    }
                }
                if (!canUseDynamicMixRoutingForAddress) {
                    info.resetCanBeRoutedWithDynamicPolicyMix();
                }
            }
        }
        return true;
    }

    /**
     * Constraints applied here:
     * <ul>
     * <li>One context should not appear in two groups if not relying on Core Audio for Volume
     * management. When using core Audio, mutual exclusive contexts may reach same devices,
     * AudioPolicyManager will apply the corresponding gain when the context is active on the common
     * device</li>
     * <li>All contexts are assigned</li>
     * <li>One device should not appear in two groups</li>
     * <li>All gain controllers in the same group have same step value</li>
     * </ul>
     *
     * Note that it is fine that there are devices which do not appear in any group. Those devices
     * may be reserved for other purposes.
     * Step value validation is done in
     * {@link CarVolumeGroup.Builder#setDeviceInfoForContext(int, CarAudioDeviceInfo)}
     */
    boolean validateVolumeGroups(CarAudioContext carAudioContext, boolean useCoreAudioRouting) {
        ArraySet<Integer> contexts = new ArraySet<>();
        ArraySet<String> addresses = new ArraySet<>();
        for (int index = 0; index <  mVolumeGroups.size(); index++) {
            CarVolumeGroup group = mVolumeGroups.get(index);
            // One context should not appear in two groups
            int[] groupContexts = group.getContexts();
            for (int groupIndex = 0; groupIndex < groupContexts.length; groupIndex++) {
                int contextId = groupContexts[groupIndex];
                if (!contexts.add(contextId)) {
                    Slogf.e(CarLog.TAG_AUDIO, "Context %d appears in two groups", contextId);
                    return false;
                }
            }
            // One address should not appear in two groups
            List<String> groupAddresses = group.getAddresses();
            for (int addressIndex = 0; addressIndex < groupAddresses.size(); addressIndex++) {
                String address = groupAddresses.get(addressIndex);
                if (!addresses.add(address)) {
                    if (useCoreAudioRouting) {
                        continue;
                    }
                    Slogf.w(CarLog.TAG_AUDIO, "Address appears in two groups: " + address);
                    return false;
                }
            }
        }

        List<Integer> allContexts = carAudioContext.getAllContextsIds();
        for (int index = 0; index < allContexts.size(); index++) {
            if (!contexts.contains(allContexts.get(index))) {
                Slogf.e(CarLog.TAG_AUDIO, "Audio context %s is not assigned to a group",
                        carAudioContext.toString(allContexts.get(index)));
                return false;
            }
        }

        List<Integer> contextList = new ArrayList<>(contexts);
        // All contexts are assigned
        if (!carAudioContext.validateAllAudioAttributesSupported(contextList)) {
            Slogf.e(CarLog.TAG_AUDIO, "Some audio attributes are not assigned to a group");
            return false;
        }
        return true;
    }

    void synchronizeCurrentGainIndex() {
        for (int index = 0; index < mVolumeGroups.size(); index++) {
            CarVolumeGroup group = mVolumeGroups.get(index);
            // Synchronize the internal state
            group.setCurrentGainIndex(group.getCurrentGainIndex());
        }
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = DUMP_INFO)
    void dump(IndentingPrintWriter writer) {
        writer.printf("CarAudioZoneConfig(%s:%d) of zone %d isDefault? %b\n", mName, mZoneConfigId,
                mZoneId, mIsDefault);
        writer.increaseIndent();
        for (int index = 0; index < mVolumeGroups.size(); index++) {
            mVolumeGroups.get(index).dump(writer);
        }
        writer.decreaseIndent();
    }

    /**
     * Update the volume groups for the new user
     * @param userId user id to update to
     */
    void updateVolumeGroupsSettingsForUser(int userId) {
        for (int index = 0; index < mVolumeGroups.size(); index++) {
            mVolumeGroups.get(index).loadVolumesSettingsForUser(userId);
        }
    }

    boolean isAudioDeviceInfoValidForZone(AudioDeviceInfo info) {
        return info != null
                && info.getAddress() != null
                && !info.getAddress().isEmpty()
                && containsDeviceAddress(info.getAddress());
    }

    private boolean containsDeviceAddress(String deviceAddress) {
        return mDeviceAddressToGroupId.containsKey(deviceAddress);
    }

    List<CarVolumeGroupEvent> onAudioGainChanged(List<Integer> halReasons,
            List<CarAudioGainConfigInfo> gainInfos) {
        // [key, value] -> [groupId, eventType]
        SparseIntArray groupIdsToEventType = new SparseIntArray();
        List<Integer> extraInfos = CarAudioGainMonitor.convertReasonsToExtraInfo(halReasons);

        // update volume-groups
        for (int index = 0; index < gainInfos.size(); index++) {
            CarAudioGainConfigInfo gainInfo = gainInfos.get(index);
            int groupId = mDeviceAddressToGroupId.getOrDefault(gainInfo.getDeviceAddress(),
                    INVALID_GROUP_ID);
            if (groupId == INVALID_GROUP_ID) {
                continue;
            }

            int eventType = mVolumeGroups.get(groupId).onAudioGainChanged(halReasons, gainInfo);
            if (eventType == INVALID_EVENT_TYPE) {
                continue;
            }
            if (groupIdsToEventType.get(groupId, INVALID_GROUP_ID) != INVALID_GROUP_ID) {
                eventType |= groupIdsToEventType.get(groupId);
            }
            groupIdsToEventType.put(groupId, eventType);
        }

        // generate events for updated groups
        List<CarVolumeGroupEvent> events = new ArrayList<>(groupIdsToEventType.size());
        for (int index = 0; index < groupIdsToEventType.size(); index++) {
            CarVolumeGroupEvent.Builder eventBuilder = new CarVolumeGroupEvent.Builder(List.of(
                    mVolumeGroups.get(groupIdsToEventType.keyAt(index)).getCarVolumeGroupInfo()),
                    groupIdsToEventType.valueAt(index));
            // ensure we have valid extra-infos
            if (!extraInfos.isEmpty()) {
                eventBuilder.setExtraInfos(extraInfos);
            }
            events.add(eventBuilder.build());
        }
        return events;
    }

    /**
     * @return The car volume infos for all the volume groups in the audio zone config
     */
    List<CarVolumeGroupInfo> getVolumeGroupInfos() {
        List<CarVolumeGroupInfo> groupInfos = new ArrayList<>(mVolumeGroups.size());
        for (int index = 0; index < mVolumeGroups.size(); index++) {
            groupInfos.add(mVolumeGroups.get(index).getCarVolumeGroupInfo());
        }

        return groupInfos;
    }

    /**
     * Returns the car audio zone config info
     */
    CarAudioZoneConfigInfo getCarAudioZoneConfigInfo() {
        return new CarAudioZoneConfigInfo(mName, mZoneId, mZoneConfigId);
    }

    /**
     * For the list of {@link HalAudioDeviceInfo}, update respective {@link CarAudioDeviceInfo}.
     * If the volume group has new gains (min/max/default/current), add a
     * {@link CarVolumeGroupEvent}
     */
    List<CarVolumeGroupEvent> onAudioPortsChanged(List<HalAudioDeviceInfo> deviceInfos) {
        List<CarVolumeGroupEvent> events = new ArrayList<>();
        ArraySet<Integer> updatedGroupIds = new ArraySet<>();

        // iterate through the incoming hal device infos and update the respective groups
        // car audio device infos
        for (int index = 0; index < deviceInfos.size(); index++) {
            HalAudioDeviceInfo deviceInfo = deviceInfos.get(index);
            int groupId = mDeviceAddressToGroupId.getOrDefault(deviceInfo.getAddress(),
                    INVALID_GROUP_ID);
            if (groupId == INVALID_GROUP_ID) {
                continue;
            }
            mVolumeGroups.get(groupId).updateAudioDeviceInfo(deviceInfo);
            updatedGroupIds.add(groupId);
        }

        // for the updated groups, recalculate the gain stages. If new gain stage, create
        // an event to callback
        for (int index = 0; index < updatedGroupIds.size(); index++) {
            CarVolumeGroup group = mVolumeGroups.get(updatedGroupIds.valueAt(index));
            int eventType = group.calculateNewGainStageFromDeviceInfos();
            if (eventType != INVALID_EVENT_TYPE) {
                events.add(new CarVolumeGroupEvent.Builder(List.of(group.getCarVolumeGroupInfo()),
                        eventType, List.of(EXTRA_INFO_VOLUME_INDEX_CHANGED_BY_AUDIO_SYSTEM))
                        .build());
            }
        }
        return events;
    }

    static final class Builder {
        private final int mZoneId;
        private final int mZoneConfigId;
        private final String mName;
        private final boolean mIsDefault;
        private final List<CarVolumeGroup> mVolumeGroups = new ArrayList<>();
        private final Map<String, Integer> mDeviceAddressToGroupId = new ArrayMap<>();
        private final List<String> mGroupIdToNames = new ArrayList<>();

        Builder(String name, int zoneId, int zoneConfigId, boolean isDefault) {
            mName = Objects.requireNonNull(name, "Car audio zone config name cannot be null");
            mZoneId = zoneId;
            mZoneConfigId = zoneConfigId;
            mIsDefault = isDefault;
        }

        Builder addVolumeGroup(CarVolumeGroup volumeGroup) {
            mVolumeGroups.add(volumeGroup);
            mGroupIdToNames.add(volumeGroup.getName());
            addGroupAddressesToMap(volumeGroup.getAddresses(), volumeGroup.getId());
            return this;
        }

        int getZoneId() {
            return mZoneId;
        }

        int getZoneConfigId() {
            return mZoneConfigId;
        }

        CarAudioZoneConfig build() {
            return new CarAudioZoneConfig(mName, mZoneId, mZoneConfigId, mIsDefault, mVolumeGroups,
                    mDeviceAddressToGroupId, mGroupIdToNames);
        }

        private void addGroupAddressesToMap(List<String> addresses, int groupId) {
            for (int index = 0; index < addresses.size(); index++) {
                mDeviceAddressToGroupId.put(addresses.get(index), groupId);
            }
        }
    }
}
