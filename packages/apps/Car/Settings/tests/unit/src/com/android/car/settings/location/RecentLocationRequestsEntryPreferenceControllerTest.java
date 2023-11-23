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

import static com.android.car.settings.location.RecentLocationRequestsEntryPreferenceController.INTENT_FILTER_LOCATION_MODE_CHANGED;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.location.LocationManager;

import androidx.lifecycle.LifecycleOwner;
import androidx.preference.Preference;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.PreferenceControllerTestUtil;
import com.android.car.settings.testutils.TestLifecycleOwner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class RecentLocationRequestsEntryPreferenceControllerTest {
    private static final long TIMEOUT_MS = 5000;

    private Context mContext = spy(ApplicationProvider.getApplicationContext());
    private LifecycleOwner mLifecycleOwner;
    private Preference mPreference;
    private RecentLocationRequestsEntryPreferenceController mPreferenceController;
    private CarUxRestrictions mCarUxRestrictions;

    @Mock
    private FragmentController mFragmentController;
    @Mock
    private LocationManager mLocationManager;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLifecycleOwner = new TestLifecycleOwner();
        mCarUxRestrictions = new CarUxRestrictions.Builder(/* reqOpt= */ true,
                CarUxRestrictions.UX_RESTRICTIONS_BASELINE, /* timestamp= */ 0).build();

        when(mContext.getSystemService(LocationManager.class)).thenReturn(mLocationManager);

        mPreference = new Preference(mContext);
        mPreferenceController = new RecentLocationRequestsEntryPreferenceController(mContext,
                "key", mFragmentController, mCarUxRestrictions);
        PreferenceControllerTestUtil.assignPreference(mPreferenceController, mPreference);

        mPreferenceController.onCreate(mLifecycleOwner);
    }

    @Test
    public void onStart_registersBroadcastReceiver() {
        mPreferenceController.onStart(mLifecycleOwner);
        verify(mContext).registerReceiver(any(BroadcastReceiver.class),
                eq(INTENT_FILTER_LOCATION_MODE_CHANGED));
    }

    @Test
    public void onStop_unregistersBroadcastReceiver() {
        mPreferenceController.onStart(mLifecycleOwner);
        ArgumentCaptor<BroadcastReceiver> captor = ArgumentCaptor.forClass(
                BroadcastReceiver.class);
        verify(mContext).registerReceiver(captor.capture(),
                eq(INTENT_FILTER_LOCATION_MODE_CHANGED));

        mPreferenceController.onStop(mLifecycleOwner);
        verify(mContext).unregisterReceiver(captor.getValue());
    }

    @Test
    public void refreshUi_locationOn_preferenceIsEnabled() {
        when(mLocationManager.isLocationEnabled()).thenReturn(true);
        mPreferenceController.refreshUi();

        assertThat(mPreference.isEnabled()).isTrue();
    }

    @Test
    public void refreshUi_locationOff_preferenceIsDisabled() {
        when(mLocationManager.isLocationEnabled()).thenReturn(false);
        mPreferenceController.refreshUi();

        assertThat(mPreference.isEnabled()).isFalse();
    }

    @Test
    public void locationModeChangedBroadcastSent_locationOff_preferenceIsDisabled() {
        mPreferenceController.onStart(mLifecycleOwner);
        when(mLocationManager.isLocationEnabled()).thenReturn(true);
        mPreferenceController.refreshUi();
        when(mLocationManager.isLocationEnabled()).thenReturn(false);
        mPreferenceController.mReceiver.onReceive(mContext, new Intent());

        assertThat(mPreference.isEnabled()).isFalse();
    }

    @Test
    public void locationModeChangedBroadcastSent_locationOn_preferenceIsEnabled() {
        mPreferenceController.onStart(mLifecycleOwner);
        when(mLocationManager.isLocationEnabled()).thenReturn(false);
        mPreferenceController.refreshUi();
        when(mLocationManager.isLocationEnabled()).thenReturn(true);
        mPreferenceController.mReceiver.onReceive(mContext, new Intent());

        assertThat(mPreference.isEnabled()).isTrue();
    }
}
