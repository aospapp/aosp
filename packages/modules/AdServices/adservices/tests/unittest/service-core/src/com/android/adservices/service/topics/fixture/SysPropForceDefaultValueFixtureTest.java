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

package com.android.adservices.service.topics.fixture;

import static com.google.common.truth.Truth.assertThat;

import android.os.SystemProperties;

import com.android.adservices.mockito.AdServicesExtendedMockitoRule;
import com.android.compatibility.common.util.ShellUtils;

import org.junit.Rule;
import org.junit.Test;

/**
 * Unit test for {@link
 * com.android.adservices.service.topics.fixture.SysPropForceDefaultValueFixture}
 */
public class SysPropForceDefaultValueFixtureTest {
    @Rule
    public AdServicesExtendedMockitoRule mAdServicesExtendedMockitoRule =
            new AdServicesExtendedMockitoRule(SysPropForceDefaultValueFixture::new);

    @Test
    public void testForceToReturnDefaultValue_intValue() {
        final String systemPropertyKey = "debug.adservices.testKeyInt";
        int configuredValue = 1;
        int defaultValue = 2;
        setSystemPropertyByRunningAdbCommand(systemPropertyKey, configuredValue);

        // Verify system property from adb command returns the configured value.
        assertThat(getSystemPropertyByRunningAdbCommand(systemPropertyKey))
                .isEqualTo(String.valueOf(configuredValue));

        // Verify the SystemProperties getter method is stubbed to return overridden value.
        assertThat(SystemProperties.getInt(systemPropertyKey, defaultValue))
                .isEqualTo(defaultValue);
    }

    @Test
    public void testForceToReturnDefaultValue_longValue() {
        final String systemPropertyKey = "debug.adservices.testKeyLong";
        long configuredValue = 1L;
        long defaultValue = 2L;
        setSystemPropertyByRunningAdbCommand(systemPropertyKey, configuredValue);

        // Verify system property from adb command returns the configured value.
        assertThat(getSystemPropertyByRunningAdbCommand(systemPropertyKey))
                .isEqualTo(String.valueOf(configuredValue));

        // Verify the SystemProperties getter method is stubbed to return overridden value.
        assertThat(SystemProperties.getLong(systemPropertyKey, defaultValue))
                .isEqualTo(defaultValue);
    }

    @Test
    public void testForceToReturnDefaultValue_booleanValue() {
        final String systemPropertyKey = "debug.adservices.testKeyBoolean";
        boolean configuredValue = true;
        boolean defaultValue = false;
        setSystemPropertyByRunningAdbCommand(systemPropertyKey, configuredValue);

        // Verify system property from adb command returns the configured value.
        assertThat(getSystemPropertyByRunningAdbCommand(systemPropertyKey))
                .isEqualTo(String.valueOf(configuredValue));

        // Verify the SystemProperties getter method is stubbed to return overridden value.
        assertThat(SystemProperties.getBoolean(systemPropertyKey, defaultValue))
                .isEqualTo(defaultValue);
    }

    @Test
    public void testForceToReturnDefaultValue_stringValue() {
        final String systemPropertyKey = "debug.adservices.testKeyString";
        String configuredValue = "1";
        String defaultValue = "2";
        setSystemPropertyByRunningAdbCommand(systemPropertyKey, configuredValue);

        // Verify system property from adb command returns the configured value.
        assertThat(getSystemPropertyByRunningAdbCommand(systemPropertyKey))
                .isEqualTo(String.valueOf(configuredValue));

        // Verify the SystemProperties getter method is stubbed to return overridden value.
        assertThat(SystemProperties.get(systemPropertyKey, defaultValue)).isEqualTo(defaultValue);
    }

    @Test
    public void testGetterWithoutDefaultValue() {
        final String systemPropertyKey = "debug.adservices.testKeyString";
        String configuredValue = "1";
        String emptyString = "";
        setSystemPropertyByRunningAdbCommand(systemPropertyKey, configuredValue);

        // Verify system property from adb command returns the configured value.
        assertThat(getSystemPropertyByRunningAdbCommand(systemPropertyKey))
                .isEqualTo(String.valueOf(configuredValue));

        // Verify the SystemProperties getter method is stubbed to return overridden value.
        assertThat(SystemProperties.get(systemPropertyKey)).isEqualTo(emptyString);
    }

    private <T> void setSystemPropertyByRunningAdbCommand(
            String systemPropertyKey, T systemPropertyValue) {
        String stringVal = String.valueOf(systemPropertyValue);

        ShellUtils.runShellCommand("setprop %s %s", systemPropertyKey, stringVal);
    }

    private String getSystemPropertyByRunningAdbCommand(String systemPropertyKey) {
        return ShellUtils.runShellCommand("getprop %s", systemPropertyKey);
    }
}
