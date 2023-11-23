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

package com.android.server.adservices;

import static com.android.server.adservices.Flags.ADSERVICES_SYSTEM_SERVICE_ENABLED;
import static com.android.server.adservices.PhFlags.KEY_ADSERVICES_SYSTEM_SERVICE_ENABLED;

import android.provider.DeviceConfig;

import com.android.modules.utils.testing.TestableDeviceConfig;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Rule;
import org.junit.Test;

public class PhFlagsTest {

    @Rule
    public final TestableDeviceConfig.TestableDeviceConfigRule mDeviceConfigRule =
            new TestableDeviceConfig.TestableDeviceConfigRule();

    @Test
    public void testAdServicesSystemServiceEnabled() {
        // Without any overriding, the value is the hard coded constant.
        assertThat(FlagsFactory.getFlags().getAdServicesSystemServiceEnabled())
                .isEqualTo(ADSERVICES_SYSTEM_SERVICE_ENABLED);

        // Now overriding with the value from PH.
        final boolean phOverridingValue = true;

        DeviceConfig.setProperty(
                DeviceConfig.NAMESPACE_ADSERVICES,
                KEY_ADSERVICES_SYSTEM_SERVICE_ENABLED,
                Boolean.toString(phOverridingValue),
                /* makeDefault */ false);

        Flags phFlags = FlagsFactory.getFlags();
        assertThat(phFlags.getAdServicesSystemServiceEnabled()).isEqualTo(phOverridingValue);
    }
}
