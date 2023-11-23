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

import android.annotation.NonNull;
import android.hardware.automotive.audiocontrol.AudioGainConfigInfo;

import java.util.Objects;

/**
 * Audio Gain Config Information for a given Device based on its address.
 */
public class CarAudioGainConfigInfo {
    private final int mZoneId;
    private final String mAddress;
    private final int mVolumeIndex;

    private CarAudioGainConfigInfo(int zoneId, @NonNull String address, int volumeIndex) {
        mZoneId = zoneId;
        mAddress = Objects.requireNonNull(address);
        mVolumeIndex = volumeIndex;
    }

    /**
     * Builds the car audio gain info configuration based on the {@link AudioGainConfigInfo}
     * @param audioGainConfig audio gain info
     *
     * @return new car audio gain info
     */
    public static CarAudioGainConfigInfo build(AudioGainConfigInfo audioGainConfig) {
        return new CarAudioGainConfigInfo(audioGainConfig.zoneId,
                audioGainConfig.devicePortAddress, audioGainConfig.volumeIndex);
    }

    /**
     * Creates {@link AudioGainConfigInfo} instance from contents of {@link CarAudioGainConfigInfo}.
     */
    public AudioGainConfigInfo generateAudioGainConfigInfo() {
        AudioGainConfigInfo agci = new AudioGainConfigInfo();
        agci.zoneId = mZoneId;
        agci.devicePortAddress = mAddress;
        agci.volumeIndex = mVolumeIndex;
        return agci;
    }

    public int getZoneId() {
        return mZoneId;
    }

    public String getDeviceAddress() {
        return mAddress;
    }

    public int getVolumeIndex() {
        return mVolumeIndex;
    }
}
