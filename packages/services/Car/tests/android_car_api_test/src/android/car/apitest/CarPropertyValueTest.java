/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.car.apitest;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.car.VehicleAreaType;
import android.car.hardware.CarPropertyValue;
import android.test.suitebuilder.annotation.MediumTest;

import org.junit.Test;

/**
 * Unit tests for {@link CarPropertyValue}
 */
@MediumTest
public final class CarPropertyValueTest extends CarPropertyTestBase {
    private static final int PROPERTY_ID = 1234;
    private static final int AREA_ID = 5678;
    private static final long TIMESTAMP_NANOS = 9294;
    private static final Float VALUE = 12.0F;
    private static final CarPropertyValue<Float> CAR_PROPERTY_VALUE = new CarPropertyValue<>(
            PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS, VALUE);

    @Test
    public void testSimpleFloatValue() {
        CarPropertyValue<Float> floatValue =
                new CarPropertyValue<>(FLOAT_PROPERTY_ID, WINDOW_DRIVER, 10f);

        writeToParcel(floatValue);

        CarPropertyValue<Float> valueRead = readFromParcel();
        assertThat(valueRead.getValue()).isEqualTo((Object) 10f);
    }

    @Test
    public void testMixedValue() {
        CarPropertyValue<Object> mixedValue =
                new CarPropertyValue<>(MIXED_TYPE_PROPERTY_ID,
                        VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL,
                        new Object[] { "android", 1, 2.0 });
        writeToParcel(mixedValue);
        CarPropertyValue<Object[]> valueRead = readFromParcel();
        assertThat(valueRead.getValue()).asList().containsExactly("android", 1, 2.0).inOrder();
        assertThat(valueRead.getPropertyId()).isEqualTo(MIXED_TYPE_PROPERTY_ID);
        assertThat(valueRead.getAreaId()).isEqualTo(VehicleAreaType.VEHICLE_AREA_TYPE_GLOBAL);
    }

    @Test
    public void hashCode_returnsSameValueForSameInstance() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isEqualTo(CAR_PROPERTY_VALUE.hashCode());
    }

    @Test
    public void hashCode_returnsDifferentValueForDifferentCarPropertyValue() {
        assertThat(CAR_PROPERTY_VALUE.hashCode()).isNotEqualTo(
                new CarPropertyValue<>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS, null).hashCode());
    }

    @Test
    public void equals_returnsTrueForSameInstance() {
        assertThat(CAR_PROPERTY_VALUE.equals(CAR_PROPERTY_VALUE)).isTrue();
    }

    @Test
    public void equals_returnsFalseForNull() {
        assertThat(CAR_PROPERTY_VALUE.equals(null)).isFalse();
    }

    @Test
    public void equals_returnsFalseForNonCarPropertyValue() {
        assertThat(CAR_PROPERTY_VALUE.equals(new Object())).isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentPropertyIds() {
        int differentPropertyId = 4444;
        assertThat(CAR_PROPERTY_VALUE.equals(
                new CarPropertyValue<>(differentPropertyId, AREA_ID, TIMESTAMP_NANOS, VALUE)))
                .isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentAreaIds() {
        int differentAreaId = 222;
        assertThat(CAR_PROPERTY_VALUE.equals(
                new CarPropertyValue<>(PROPERTY_ID, differentAreaId, TIMESTAMP_NANOS, VALUE)))
                .isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentTimestamps() {
        long differentTimestampNanos = 76845;
        assertThat(CAR_PROPERTY_VALUE.equals(
                new CarPropertyValue<>(PROPERTY_ID, AREA_ID, differentTimestampNanos, VALUE)))
                .isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentValues() {
        Integer differentValue = 12;
        assertThat(CAR_PROPERTY_VALUE.equals(
                new CarPropertyValue<>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS, differentValue)))
                .isFalse();
    }

    @Test
    public void equals_returnsFalseForDifferentValueWithNull() {
        assertThat(CAR_PROPERTY_VALUE.equals(
                new CarPropertyValue<>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS, null)))
                .isFalse();
    }

    @Test
    public void equals_returnsTrueWhenEqual() {
        assertThat(CAR_PROPERTY_VALUE.equals(
                new CarPropertyValue<>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS, VALUE)))
                .isTrue();
    }

    @Test
    public void equals_returnsTrueWhenEqualWithNullValues() {
        assertThat(
                new CarPropertyValue<>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS, null).equals(
                        new CarPropertyValue<>(PROPERTY_ID, AREA_ID, TIMESTAMP_NANOS, null)))
                .isTrue();
    }

    @Test
    public void carPropertyvalue_getStatus() {
        assertThat(CAR_PROPERTY_VALUE.getStatus()).isEqualTo(CarPropertyValue.STATUS_AVAILABLE);
    }

    @Test
    public void carPropertyValue_notAvailableStatus() {
        assertThrows(IllegalArgumentException.class, () -> new CarPropertyValue<>(
                PROPERTY_ID, AREA_ID, CarPropertyValue.STATUS_UNAVAILABLE, TIMESTAMP_NANOS, null));
        assertThrows(IllegalArgumentException.class, () -> new CarPropertyValue<>(
                PROPERTY_ID, AREA_ID, CarPropertyValue.STATUS_ERROR, TIMESTAMP_NANOS, null));
    }
}
