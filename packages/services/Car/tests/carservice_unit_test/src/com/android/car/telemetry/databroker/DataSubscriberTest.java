/*
 * Copyright (C) 2021 The Android Open Source Project
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

package com.android.car.telemetry.databroker;

import static com.google.common.truth.Truth.assertThat;

import com.android.car.telemetry.TelemetryProto;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnitRunner;

@RunWith(MockitoJUnitRunner.class)
public class DataSubscriberTest {

    private static final TelemetryProto.VehiclePropertyPublisher
            VEHICLE_PROPERTY_PUBLISHER_CONFIGURATION =
            TelemetryProto.VehiclePropertyPublisher.newBuilder().setReadRate(
                    1).setVehiclePropertyId(100).build();
    private static final TelemetryProto.Publisher PUBLISHER_CONFIGURATION =
            TelemetryProto.Publisher.newBuilder().setVehicleProperty(
                    VEHICLE_PROPERTY_PUBLISHER_CONFIGURATION).build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_FOO =
            TelemetryProto.Subscriber.newBuilder().setHandler("function_name_foo").setPublisher(
                    PUBLISHER_CONFIGURATION).setPriority(1).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_FOO =
            TelemetryProto.MetricsConfig.newBuilder().setName("Foo").setVersion(
                    1).addSubscribers(SUBSCRIBER_FOO).build();
    private static final TelemetryProto.Subscriber SUBSCRIBER_BAR =
            TelemetryProto.Subscriber.newBuilder().setHandler("function_name_bar").setPublisher(
                    PUBLISHER_CONFIGURATION).setPriority(1).build();
    private static final TelemetryProto.MetricsConfig METRICS_CONFIG_BAR =
            TelemetryProto.MetricsConfig.newBuilder().setName("Bar").setVersion(
                    1).addSubscribers(SUBSCRIBER_BAR).build();

    @Mock
    private DataBroker mMockDataBroker;

    @Test
    public void testEquals_whenSame_shouldBeEqual() {
        DataSubscriber foo = new DataSubscriber(mMockDataBroker, METRICS_CONFIG_FOO,
                SUBSCRIBER_FOO);
        DataSubscriber bar = new DataSubscriber(mMockDataBroker, METRICS_CONFIG_FOO,
                SUBSCRIBER_FOO);

        assertThat(foo).isEqualTo(bar);
    }

    @Test
    public void testEquals_whenDifferent_shouldNotBeEqual() {
        DataSubscriber foo = new DataSubscriber(mMockDataBroker, METRICS_CONFIG_FOO,
                SUBSCRIBER_FOO);
        DataSubscriber bar = new DataSubscriber(mMockDataBroker, METRICS_CONFIG_BAR,
                SUBSCRIBER_BAR);

        assertThat(foo).isNotEqualTo(bar);
    }
}
