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

package com.android.server.uwb.pm;

import static com.android.server.uwb.data.UwbConfig.CENTRAL;
import static com.android.server.uwb.data.UwbConfig.CONTROLEE_AND_RESPONDER;
import static com.android.server.uwb.data.UwbConfig.CONTROLLER_AND_INITIATOR;
import static com.android.server.uwb.data.UwbConfig.OOB_TYPE_BLE;
import static com.android.server.uwb.data.UwbConfig.PERIPHERAL;

import static com.google.uwb.support.fira.FiraParams.MULTI_NODE_MODE_ONE_TO_MANY;
import static com.google.uwb.support.fira.FiraParams.RFRAME_CONFIG_SP3;
import static com.google.uwb.support.fira.FiraParams.STS_CONFIG_DYNAMIC;

import com.android.server.uwb.data.UwbConfig;

public class PacsProfile {

    /** PACS Controlee config */
    private static UwbConfig sControlee;

    /** PACS controller config */
    private static UwbConfig sController;

    public static UwbConfig getPacsControleeProfile() {
        if (sControlee == null) {
            UwbConfig.Builder builder = new UwbConfig.Builder();
            sControlee = builder
                    .setUwbRole(CONTROLEE_AND_RESPONDER)
                    .setStsConfig(STS_CONFIG_DYNAMIC)
                    .setMultiNodeMode(MULTI_NODE_MODE_ONE_TO_MANY)
                    .setRframeConfig(RFRAME_CONFIG_SP3)
                    .setTofReport(true)
                    .setOobType(OOB_TYPE_BLE)
                    .setOobBleRole(PERIPHERAL)
                    .build();
        }
        return sControlee;
    }

    /** Used for testing, only PACS controlee profile is supported for applications */
    public static UwbConfig getPacsControllerProfile() {
        if (sController == null) {
            UwbConfig.Builder builder = new UwbConfig.Builder();
            sController = builder
                    .setUwbRole(CONTROLLER_AND_INITIATOR)
                    .setStsConfig(STS_CONFIG_DYNAMIC)
                    .setMultiNodeMode(MULTI_NODE_MODE_ONE_TO_MANY)
                    .setRframeConfig(RFRAME_CONFIG_SP3)
                    .setTofReport(true)
                    .setOobType(OOB_TYPE_BLE)
                    .setOobBleRole(CENTRAL)
                    .build();
        }
        return sController;
    }
}
