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

package android.healthconnect.cts.logging;

import android.cts.statsdatom.lib.DeviceUtils;

import com.android.tradefed.device.ITestDevice;

class HostSideTestsUtils {

    private static final String FEATURE_TV = "android.hardware.type.television";
    private static final String FEATURE_EMBEDDED = "android.hardware.type.embedded";
    private static final String FEATURE_WATCH = "android.hardware.type.watch";
    private static final String FEATURE_LEANBACK = "android.software.leanback";
    private static final String FEATURE_AUTOMOTIVE = "android.hardware.type.automotive";

    public static boolean isHardwareSupported(ITestDevice device) {
        // These UI tests are not optimised for Watches, TVs, Auto;
        // IoT devices do not have a UI to run these UI tests
        try {
            return !DeviceUtils.hasFeature(device, FEATURE_TV)
                    && !DeviceUtils.hasFeature(device, FEATURE_EMBEDDED)
                    && !DeviceUtils.hasFeature(device, FEATURE_WATCH)
                    && !DeviceUtils.hasFeature(device, FEATURE_LEANBACK)
                    && !DeviceUtils.hasFeature(device, FEATURE_AUTOMOTIVE);
        } catch (Exception e) {
            return false;
        }
    }
}
