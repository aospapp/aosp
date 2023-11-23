/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.car.power;

import static com.google.common.truth.Truth.assertThat;
import static com.google.common.truth.Truth.assertWithMessage;

import android.car.hardware.power.CarPowerPolicy;
import android.car.hardware.power.CarPowerPolicyFilter;
import android.car.hardware.power.PowerComponent;
import android.car.hardware.power.PowerComponentUtil;

import org.junit.Test;

import java.lang.reflect.Field;
import java.util.Arrays;

public final class PowerComponentUtilUnitTest {
    private final Field[] mPowerComponentFields = (Field[])
            Arrays.stream(PowerComponent.class.getFields()).filter(field -> {
                try {
                    return field.getInt(null) < PowerComponent.MINIMUM_CUSTOM_COMPONENT_VALUE;
                } catch (IllegalAccessException e) {
                    throw new RuntimeException(e);
                }
            }).toArray(Field[]::new);

    @Test
    public void isValidPowerComponent() throws Exception {
        int wrongPowerComponent = 9999; // this ID belongs to Custom power component

        for (int i = 0; i < mPowerComponentFields.length; i++) {
            int component = mPowerComponentFields[i].getInt(null);

            assertWithMessage("%s is a valid power component", component)
                    .that(PowerComponentUtil.isValidPowerComponent(component))
                    .isTrue();
        }
        assertWithMessage("%s is not a valid power component", wrongPowerComponent)
                .that(PowerComponentUtil.isValidPowerComponent(wrongPowerComponent))
                .isFalse();
    }

    @Test
    public void testLastComponent() {
        assertWithMessage("LAST_POWER_COMPONENT should be %s", mPowerComponentFields.length)
                .that(PowerComponentUtil.LAST_POWER_COMPONENT)
                .isEqualTo(mPowerComponentFields.length);
    }

    @Test
    public void testHasComponents() {
        CarPowerPolicy policy = new CarPowerPolicy("testPolicy", new int[]{PowerComponent.AUDIO},
                new int[]{PowerComponent.WIFI, PowerComponent.NFC});
        CarPowerPolicyFilter filterAudio = new CarPowerPolicyFilter.Builder()
                .setComponents(PowerComponent.AUDIO).build();
        CarPowerPolicyFilter filterWifi = new CarPowerPolicyFilter.Builder()
                .setComponents(PowerComponent.WIFI).build();
        CarPowerPolicyFilter filterLocationNfc = new CarPowerPolicyFilter.Builder()
                .setComponents(PowerComponent.LOCATION, PowerComponent.NFC).build();
        CarPowerPolicyFilter filterMedia = new CarPowerPolicyFilter.Builder()
                .setComponents(PowerComponent.MEDIA).build();

        assertThat(PowerComponentUtil.hasComponents(policy, filterAudio)).isTrue();
        assertThat(PowerComponentUtil.hasComponents(policy, filterWifi)).isTrue();
        assertThat(PowerComponentUtil.hasComponents(policy, filterLocationNfc)).isTrue();
        assertThat(PowerComponentUtil.hasComponents(policy, filterMedia)).isFalse();
    }

    @Test
    public void testToPowerComponent() throws Exception {
        for (int i = 0; i < mPowerComponentFields.length; i++) {
            String componentName = mPowerComponentFields[i].getName();
            int expectedValue = mPowerComponentFields[i].getInt(null);
            assertWithMessage("%s should be convected to %s", componentName, expectedValue)
                    .that(PowerComponentUtil.toPowerComponent(componentName, false))
                    .isEqualTo(expectedValue);
        }
        for (int i = 0; i < mPowerComponentFields.length; i++) {
            String componentName = "POWER_COMPONENT_" + mPowerComponentFields[i].getName();
            int expectedValue = mPowerComponentFields[i].getInt(null);
            assertWithMessage("%s should be convected to %s", componentName, expectedValue)
                    .that(PowerComponentUtil.toPowerComponent(componentName, true))
                    .isEqualTo(mPowerComponentFields[i].getInt(null));
        }
    }

    @Test
    public void testPowerComponentToString() throws Exception {
        for (int i = 0; i < mPowerComponentFields.length; i++) {
            int component = mPowerComponentFields[i].getInt(null);
            String expectedName = mPowerComponentFields[i].getName();
            assertWithMessage("%s should be converted to %s", component, expectedName)
                    .that(PowerComponentUtil.powerComponentToString(component))
                    .isEqualTo(expectedName);
        }
    }
}
