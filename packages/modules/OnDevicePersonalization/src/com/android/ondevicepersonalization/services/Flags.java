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

package com.android.ondevicepersonalization.services;

/**
 * OnDevicePersonalization Feature Flags interface. This Flags interface hold the default values
 * of flags. The default values in this class must match with the default values in PH since we
 * will migrate to Flag Codegen in the future. With that migration, the Flags.java file will be
 * generated from the GCL.
 */
public interface Flags {

    boolean ONDEVICEPERSONALIZATION_ENABLED = false;

    default boolean getOnDevicePersonalizationEnabled() {
        return ONDEVICEPERSONALIZATION_ENABLED;
    }

    /**
     * Global OnDevicePersonalization Kill Switch. This overrides all other killswitches.
     * The default value is false which means OnDevicePersonalization is enabled.
     * This flag is used for emergency turning off the whole module.
     */
    boolean GLOBAL_KILL_SWITCH = true;

    default boolean getGlobalKillSwitch() {
        return GLOBAL_KILL_SWITCH;
    }
}
