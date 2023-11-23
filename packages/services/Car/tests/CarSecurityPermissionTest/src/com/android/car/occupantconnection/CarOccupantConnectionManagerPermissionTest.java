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

package com.android.car.occupantconnection;

import static android.car.Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION;
import static android.car.CarOccupantZoneManager.OCCUPANT_TYPE_DRIVER;
import static android.car.VehicleAreaSeat.SEAT_ROW_1_LEFT;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;
import static org.junit.Assume.assumeNotNull;

import android.app.UiAutomation;
import android.car.Car;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.occupantconnection.CarOccupantConnectionManager;
import android.car.occupantconnection.CarOccupantConnectionManager.ConnectionRequestCallback;
import android.car.occupantconnection.Payload;
import android.content.Context;
import android.os.Handler;

import androidx.annotation.NonNull;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.Objects;

/**
 * This class contains security permission tests for {@link CarOccupantConnectionManager}' system
 * APIs.
 */
@RunWith(AndroidJUnit4.class)
public final class CarOccupantConnectionManagerPermissionTest {

    private static final String RECEIVER_ID = "receiver_id";

    private final Context mContext =
            InstrumentationRegistry.getInstrumentation().getTargetContext();
    private final UiAutomation mUiAutomation =
            InstrumentationRegistry.getInstrumentation().getUiAutomation();

    private CarOccupantConnectionManager mCarOccupantConnectionManager;
    private OccupantZoneInfo mReceiverZone;
    private ConnectionRequestCallback mConnectionRequestCallback;

    @Before
    public void setUp() {
        Car car = Objects.requireNonNull(Car.createCar(mContext, (Handler) null));
        mCarOccupantConnectionManager = car.getCarManager(CarOccupantConnectionManager.class);
        // CarOccupantConnectionManager is available on multi-display builds only.
        // TODO(b/265091454): annotate the test with @RequireMultipleUsersOnMultipleDisplays.
        assumeNotNull(
                "Skip the test because CarOccupantConnectionManager is not available on this build",
                mCarOccupantConnectionManager);

        mReceiverZone = new OccupantZoneInfo(/* zoneId= */ 0, OCCUPANT_TYPE_DRIVER,
                SEAT_ROW_1_LEFT);

        mConnectionRequestCallback = new ConnectionRequestCallback() {
            @Override
            public void onConnected(@NonNull OccupantZoneInfo receiverZone) {
            }

            @Override
            public void onFailed(@NonNull OccupantZoneInfo receiverZone,
                    int connectionError) {
            }

            @Override
            public void onDisconnected(@NonNull OccupantZoneInfo receiverZone) {
            }
        };
    }

    @Test
    public void testRegisterReceiver() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarOccupantConnectionManager.registerReceiver(RECEIVER_ID, Runnable::run,
                        (senderZone, payload) -> {}));

        assertThat(e).hasMessageThat().contains(PERMISSION_MANAGE_OCCUPANT_CONNECTION);
    }

    @Test
    public void testUnregisterReceiver() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarOccupantConnectionManager.unregisterReceiver(RECEIVER_ID));

        assertThat(e).hasMessageThat().contains(PERMISSION_MANAGE_OCCUPANT_CONNECTION);
    }

    @Test
    public void testRequestConnection() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarOccupantConnectionManager.requestConnection(mReceiverZone, Runnable::run,
                        mConnectionRequestCallback));

        assertThat(e).hasMessageThat().contains(PERMISSION_MANAGE_OCCUPANT_CONNECTION);
    }

    @Test
    public void testCancelConnection() {
        try {
            mUiAutomation.adoptShellPermissionIdentity(Car.PERMISSION_MANAGE_OCCUPANT_CONNECTION);
            mCarOccupantConnectionManager.requestConnection(mReceiverZone, Runnable::run,
                    mConnectionRequestCallback);
        } finally {
            mUiAutomation.dropShellPermissionIdentity();
        }
        Exception e = assertThrows(SecurityException.class,
                () -> mCarOccupantConnectionManager.cancelConnection(mReceiverZone));

        assertThat(e).hasMessageThat().contains(PERMISSION_MANAGE_OCCUPANT_CONNECTION);
    }

    @Test
    public void testSendPayload() {
        Payload payload = new Payload(new byte[0]);
        Exception e = assertThrows(SecurityException.class,
                () -> mCarOccupantConnectionManager.sendPayload(mReceiverZone, payload));

        assertThat(e).hasMessageThat().contains(PERMISSION_MANAGE_OCCUPANT_CONNECTION);
    }

    @Test
    public void testDisconnect() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarOccupantConnectionManager.disconnect(mReceiverZone));

        assertThat(e).hasMessageThat().contains(PERMISSION_MANAGE_OCCUPANT_CONNECTION);
    }

    @Test
    public void testIsConnected() {
        Exception e = assertThrows(SecurityException.class,
                () -> mCarOccupantConnectionManager.isConnected(mReceiverZone));

        assertThat(e).hasMessageThat().contains(PERMISSION_MANAGE_OCCUPANT_CONNECTION);
    }
}
