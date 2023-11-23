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

package com.android.bedstead.nene.wifi;

import static com.android.bedstead.nene.permissions.CommonPermissions.ACCESS_WIFI_STATE;

import android.net.wifi.WifiManager;

import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.permissions.PermissionContext;
import com.android.bedstead.nene.utils.Poll;
import com.android.bedstead.nene.utils.ShellCommand;

/** Test APIs related to bluetooth. */
public final class Wifi {
    public static final Wifi sInstance = new Wifi();

    private static final WifiManager sWifiManager = TestApis.context().instrumentedContext()
            .getSystemService(WifiManager.class);

    private Wifi() {

    }

    /**
     * {@code true} if wifi is enabled.
     * @return
     */
    public boolean isEnabled() {
        try (PermissionContext p = TestApis.permissions().withPermission(ACCESS_WIFI_STATE)) {
            return sWifiManager.isWifiEnabled();
        }
    }

    public void setEnabled(boolean enabled) {
        if (isEnabled() == enabled) {
            return;
        }

        ShellCommand.builder("svc wifi")
                .addOperand(enabled ? "enable" : "disable")
                .validate(String::isEmpty)
                .executeOrThrowNeneException("Error switching wifi state");

        Poll.forValue("Wifi enabled", this::isEnabled)
                .toBeEqualTo(enabled)
                .errorOnFail()
                .await();
    }
}
