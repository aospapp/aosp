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

package com.android.car.settings.location;

import static com.android.car.settings.common.PreferenceController.AVAILABLE;
import static com.android.car.settings.common.PreferenceController.AVAILABLE_FOR_VIEWING;
import static com.android.car.settings.common.PreferenceController.CONDITIONALLY_UNAVAILABLE;
import static com.android.car.settings.common.PreferenceController.UNSUPPORTED_ON_DEVICE;

import static com.google.common.truth.Truth.assertThat;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.location.LocationManager;

import androidx.test.annotation.UiThreadTest;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.LogicalPreferenceGroup;
import com.android.car.settings.common.PreferenceControllerTestUtil;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class AdasGroupPreferenceControllerTest {
    private Context mContext = ApplicationProvider.getApplicationContext();
    private LogicalPreferenceGroup mPreference;
    private CarUxRestrictions mCarUxRestrictions;
    private AdasGroupPreferenceController mController;
    private LocationManager mLocationManager;

    @Mock
    private FragmentController mFragmentController;

    @Before
    @UiThreadTest
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mCarUxRestrictions = new CarUxRestrictions.Builder(/* reqOpt= */ true,
                CarUxRestrictions.UX_RESTRICTIONS_BASELINE, /* timestamp= */ 0).build();

        mPreference = new LogicalPreferenceGroup(mContext);
        mController = new AdasGroupPreferenceController(mContext,
                /* preferenceKey= */ "key", mFragmentController, mCarUxRestrictions);

        mLocationManager = mContext.getSystemService(LocationManager.class);
    }

    @Test
    public void getAvailabilityStatus_driverAssistanceEnabled_unsupportedOnDevice() {
        mLocationManager.setAdasGnssLocationEnabled(true);

        assertThat(mController.getAvailabilityStatus()).isEqualTo(UNSUPPORTED_ON_DEVICE);
    }

    @Test
    public void getAvailabilityStatus_driverAssistanceEnabled_unsupportedOnDevice_zoneWrite() {
        mController.setAvailabilityStatusForZone("write");
        mLocationManager.setAdasGnssLocationEnabled(true);

        PreferenceControllerTestUtil.assertAvailability(mController.getAvailabilityStatus(),
                UNSUPPORTED_ON_DEVICE);
    }

    @Test
    public void getAvailabilityStatus_driverAssistanceEnabled_unsupportedOnDevice_zoneRead() {
        mController.setAvailabilityStatusForZone("read");
        mLocationManager.setAdasGnssLocationEnabled(true);

        PreferenceControllerTestUtil.assertAvailability(mController.getAvailabilityStatus(),
                UNSUPPORTED_ON_DEVICE);
    }

    @Test
    public void getAvailabilityStatus_driverAssistanceEnabled_unsupportedOnDevice_zoneHidden() {
        mController.setAvailabilityStatusForZone("hidden");
        mLocationManager.setAdasGnssLocationEnabled(true);

        PreferenceControllerTestUtil.assertAvailability(mController.getAvailabilityStatus(),
                UNSUPPORTED_ON_DEVICE);
    }

    @Test
    public void getAvailabilityStatus_driverAssistanceDisabled_available() {
        mLocationManager.setAdasGnssLocationEnabled(false);

        assertThat(mController.getAvailabilityStatus()).isEqualTo(AVAILABLE);
    }

    @Test
    public void getAvailabilityStatus_driverAssistanceDisabled_available_zoneWrite() {
        mController.setAvailabilityStatusForZone("write");
        mLocationManager.setAdasGnssLocationEnabled(false);

        PreferenceControllerTestUtil.assertAvailability(mController.getAvailabilityStatus(),
                AVAILABLE);
    }

    @Test
    public void getAvailabilityStatus_driverAssistanceDisabled_available_zoneRead() {
        mController.setAvailabilityStatusForZone("read");
        mLocationManager.setAdasGnssLocationEnabled(false);

        PreferenceControllerTestUtil.assertAvailability(mController.getAvailabilityStatus(),
                AVAILABLE_FOR_VIEWING);
    }

    @Test
    public void getAvailabilityStatus_driverAssistanceDisabled_available_zoneHidden() {
        mController.setAvailabilityStatusForZone("hidden");
        mLocationManager.setAdasGnssLocationEnabled(false);

        PreferenceControllerTestUtil.assertAvailability(mController.getAvailabilityStatus(),
                CONDITIONALLY_UNAVAILABLE);
    }
}
