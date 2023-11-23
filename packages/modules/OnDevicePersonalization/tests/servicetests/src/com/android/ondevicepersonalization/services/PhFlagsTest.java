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

import static com.android.ondevicepersonalization.services.Flags.GLOBAL_KILL_SWITCH;
import static com.android.ondevicepersonalization.services.PhFlags.KEY_GLOBAL_KILL_SWITCH;

import static com.google.common.truth.Truth.assertThat;

import android.provider.DeviceConfig;

import androidx.test.InstrumentationRegistry;
import androidx.test.runner.AndroidJUnit4;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/** Unit tests for {@link com.android.ondevicepersonalization.service.PhFlags} */
@RunWith(AndroidJUnit4.class)
public class PhFlagsTest {
    private static final String WRITE_DEVICE_CONFIG_PERMISSION =
            "android.permission.WRITE_DEVICE_CONFIG";

    private static final String READ_DEVICE_CONFIG_PERMISSION =
            "android.permission.READ_DEVICE_CONFIG";

    private static final String MONITOR_DEVICE_CONFIG_ACCESS =
            "android.permission.MONITOR_DEVICE_CONFIG_ACCESS";

    /**
     * Get necessary permissions to access Setting.Config API and set up context
     */
    @Before
    public void setUpContext() throws Exception {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().adoptShellPermissionIdentity(
                WRITE_DEVICE_CONFIG_PERMISSION, READ_DEVICE_CONFIG_PERMISSION,
                MONITOR_DEVICE_CONFIG_ACCESS);
        // sContext = InstrumentationRegistry.getContext();
        // sContentResolver = sContext.getContentResolver();
    }

    @Test
    public void testGetGlobalKillSwitch() {
        // Without any overriding, the value is the hard coded constant.
        assertThat(FlagsFactory.getFlags().getGlobalKillSwitch()).isEqualTo(GLOBAL_KILL_SWITCH);

        // Now overriding with the value from PH.
        final boolean phOverridingValue = true;
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ON_DEVICE_PERSONALIZATION,
                KEY_GLOBAL_KILL_SWITCH,
                Boolean.toString(phOverridingValue),
                /* makeDefault */ false);

        Flags phFlags = FlagsFactory.getFlags();
        assertThat(phFlags.getGlobalKillSwitch()).isEqualTo(phOverridingValue);
    }

    private void disableGlobalKillSwitch() {
        // Override the global_kill_switch to test other flag values.
        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ON_DEVICE_PERSONALIZATION,
                KEY_GLOBAL_KILL_SWITCH,
                Boolean.toString(false),
                /* makeDefault */ false);
    }
}
