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

package com.android.car.audio.hal;

import com.android.car.audio.CarAudioGainConfigInfo;

import java.util.List;

/**
 * Audio Gain Callback interface to abstract away the specific HAL version
 */
public interface HalAudioGainCallback {
    /**
     * Notify of Audio Gain changed for given {@code halReasons} for the given {@code gains}.
     */
    void onAudioDeviceGainsChanged(List<Integer> halReasons, List<CarAudioGainConfigInfo> gains);
}
