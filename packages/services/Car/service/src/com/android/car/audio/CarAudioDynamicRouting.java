/*
 * Copyright (C) 2018 The Android Open Source Project
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

import static android.media.AudioAttributes.USAGE_MEDIA;

import static com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport.PRIVATE_CONSTRUCTOR;

import android.car.builtin.util.Slogf;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.audiopolicy.AudioMix;
import android.media.audiopolicy.AudioMixingRule;
import android.media.audiopolicy.AudioPolicy;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.CarLog;
import com.android.car.internal.ExcludeFromCodeCoverageGeneratedReport;

import java.util.Arrays;
import java.util.List;

/**
 * Builds dynamic audio routing in a car from audio zone configuration.
 */
final class CarAudioDynamicRouting {
    // For legacy stream type based volume control.
    // Values in STREAM_TYPES and STREAM_TYPE_USAGES should be aligned.
    static final int[] STREAM_TYPES = new int[] {
            AudioManager.STREAM_MUSIC,
            AudioManager.STREAM_ALARM,
            AudioManager.STREAM_RING
    };
    static final int[] STREAM_TYPE_USAGES = new int[] {
            USAGE_MEDIA,
            AudioAttributes.USAGE_ALARM,
            AudioAttributes.USAGE_NOTIFICATION_RINGTONE
    };

    static void setupAudioDynamicRouting(AudioPolicy.Builder builder,
            SparseArray<CarAudioZone> carAudioZones, CarAudioContext carAudioContext) {
        for (int i = 0; i < carAudioZones.size(); i++) {
            List<CarAudioZoneConfig> zoneConfigs =
                    carAudioZones.valueAt(i).getAllCarAudioZoneConfigs();
            for (int configIndex = 0; configIndex < zoneConfigs.size(); configIndex++) {
                setupAudioDynamicRoutingForZoneConfig(builder, zoneConfigs.get(configIndex),
                        carAudioContext);
            }
        }
    }

    private static void setupAudioDynamicRoutingForZoneConfig(AudioPolicy.Builder builder,
            CarAudioZoneConfig zoneConfig, CarAudioContext carAudioContext) {
        CarVolumeGroup[] volumeGroups = zoneConfig.getVolumeGroups();
        for (int index = 0; index < volumeGroups.length; index++) {
            setupAudioDynamicRoutingForGroup(builder, volumeGroups[index], carAudioContext);
        }
    }

    /**
     * Enumerates all physical buses in a given volume group and attach the mixing rules.
     * @param builder {@link AudioPolicy.Builder} to attach the mixing rules
     * @param group {@link CarVolumeGroup} instance to enumerate the buses with
     * @param carAudioContext car audio context
     */
    private static void setupAudioDynamicRoutingForGroup(AudioPolicy.Builder builder,
            CarVolumeGroup group, CarAudioContext carAudioContext) {
        // Note that one can not register audio mix for same bus more than once.
        List<String> addresses = group.getAddresses();
        for (int index = 0; index < addresses.size(); index++) {
            String address = addresses.get(index);
            boolean hasContext = false;
            CarAudioDeviceInfo info = group.getCarAudioDeviceInfoForAddress(address);
            if (!info.canBeRoutedWithDynamicPolicyMix()) {
                if (Slogf.isLoggable(CarLog.TAG_AUDIO, Log.DEBUG)) {
                    Slogf.d(CarLog.TAG_AUDIO, "Address: %s AudioContext: %s cannot be routed with "
                            + "Dynamic Policy Mixing", address, carAudioContext);
                }
                continue;
            }
            AudioFormat mixFormat = createMixFormatFromDevice(info);
            AudioMixingRule.Builder mixingRuleBuilder = new AudioMixingRule.Builder();
            List<Integer> contextIdsForAddress = group.getContextsForAddress(address);
            for (int contextIndex = 0; contextIndex < contextIdsForAddress.size(); contextIndex++) {
                @CarAudioContext.AudioContext int contextId =
                        contextIdsForAddress.get(contextIndex);
                hasContext = true;
                AudioAttributes[] allAudioAttributes =
                        carAudioContext.getAudioAttributesForContext(contextId);
                for (int attrIndex = 0; attrIndex < allAudioAttributes.length; attrIndex++) {
                    AudioAttributes attributes = allAudioAttributes[attrIndex];
                    mixingRuleBuilder.addRule(attributes,
                            AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);
                }
                if (Slogf.isLoggable(CarLog.TAG_AUDIO, Log.DEBUG)) {
                    Slogf.d(CarLog.TAG_AUDIO, "Address: %s AudioContext: %s sampleRate: %d "
                            + "channels: %d attributes: %s", address, carAudioContext,
                            info.getSampleRate(), info.getChannelCount(),
                            Arrays.toString(allAudioAttributes));
                }
            }
            if (hasContext) {
                // It's a valid case that an audio output address is defined in
                // audio_policy_configuration and no context is assigned to it.
                // In such case, do not build a policy mix with zero rules.
                addMix(builder, info, mixFormat, mixingRuleBuilder);
            }
        }
    }

    @ExcludeFromCodeCoverageGeneratedReport(reason = PRIVATE_CONSTRUCTOR)
    private CarAudioDynamicRouting() {
        throw new UnsupportedOperationException("contains only static methods");
    }

    public static void setupAudioDynamicRoutingForMirrorDevice(
            AudioPolicy.Builder mirrorPolicyBuilder, List<CarAudioDeviceInfo> audioDeviceInfos) {
        for (int index = 0; index < audioDeviceInfos.size(); index++) {
            AudioFormat mixFormat = createMixFormatFromDevice(audioDeviceInfos.get(index));
            AudioMixingRule.Builder mixingRuleBuilder = new AudioMixingRule.Builder();
            mixingRuleBuilder.addRule(CarAudioContext.getAudioAttributeFromUsage(USAGE_MEDIA),
                    AudioMixingRule.RULE_MATCH_ATTRIBUTE_USAGE);

            addMix(mirrorPolicyBuilder, audioDeviceInfos.get(index), mixFormat, mixingRuleBuilder);
        }
    }

    private static AudioFormat createMixFormatFromDevice(CarAudioDeviceInfo mirrorDevice) {
        AudioFormat mixFormat = new AudioFormat.Builder()
                .setSampleRate(mirrorDevice.getSampleRate())
                .setEncoding(mirrorDevice.getEncodingFormat())
                .setChannelMask(mirrorDevice.getChannelCount())
                .build();
        return mixFormat;
    }

    private static void addMix(AudioPolicy.Builder mirrorPolicyBuilder,
            CarAudioDeviceInfo mirrorDevice, AudioFormat mixFormat,
            AudioMixingRule.Builder mixingRuleBuilder) {
        AudioMix audioMix = new AudioMix.Builder(mixingRuleBuilder.build())
                .setFormat(mixFormat)
                .setDevice(mirrorDevice.getAudioDeviceInfo())
                .setRouteFlags(AudioMix.ROUTE_FLAG_RENDER)
                .build();
        mirrorPolicyBuilder.addMix(audioMix);
    }
}
