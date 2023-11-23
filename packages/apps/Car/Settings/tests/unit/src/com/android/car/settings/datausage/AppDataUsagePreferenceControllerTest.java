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

package com.android.car.settings.datausage;

import static android.net.TrafficStats.UID_TETHERING;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.net.NetworkStats;

import androidx.lifecycle.LifecycleOwner;
import androidx.preference.PreferenceManager;
import androidx.preference.PreferenceScreen;
import androidx.test.annotation.UiThreadTest;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.LogicalPreferenceGroup;
import com.android.car.settings.common.PreferenceControllerTestUtil;
import com.android.car.settings.common.ProgressBarPreference;
import com.android.car.settings.testutils.TestLifecycleOwner;
import com.android.settingslib.net.UidDetail;
import com.android.settingslib.net.UidDetailProvider;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@RunWith(AndroidJUnit4.class)
public class AppDataUsagePreferenceControllerTest {

    private Context mContext = ApplicationProvider.getApplicationContext();
    private LifecycleOwner mLifecycleOwner;
    private CarUxRestrictions mCarUxRestrictions;
    private AppDataUsagePreferenceController mPreferenceController;
    private LogicalPreferenceGroup mPreferenceGroup;

    @Mock
    private FragmentController mMockFragmentController;
    @Mock
    private UidDetailProvider mMockUidDetailProvider;
    @Mock
    private UidDetail mMockUidDetail;

    @Before
    @UiThreadTest
    public void setUp() {
        MockitoAnnotations.initMocks(this);
        mLifecycleOwner = new TestLifecycleOwner();

        mCarUxRestrictions = new CarUxRestrictions.Builder(/* reqOpt= */ true,
                CarUxRestrictions.UX_RESTRICTIONS_BASELINE, /* timestamp= */ 0).build();
        mPreferenceController = new AppDataUsagePreferenceController(mContext,
                /* preferenceKey= */ "key", mMockFragmentController,
                mCarUxRestrictions, mMockUidDetailProvider);
        PreferenceManager preferenceManager = new PreferenceManager(mContext);
        PreferenceScreen screen = preferenceManager.createPreferenceScreen(mContext);
        mPreferenceGroup = new LogicalPreferenceGroup(mContext);
        screen.addPreference(mPreferenceGroup);
        PreferenceControllerTestUtil.assignPreference(mPreferenceController, mPreferenceGroup);
        mPreferenceController.onCreate(mLifecycleOwner);
    }

    @Test
    public void defaultInitialize_hasNoPreference() {
        assertThat(mPreferenceGroup.getPreferenceCount()).isEqualTo(0);
    }

    @Test
    public void onDataLoaded_dataNotLoaded_hasNoPreference() {
        mPreferenceController.onDataLoaded(null, new int[0]);

        assertThat(mPreferenceGroup.getPreferenceCount()).isEqualTo(0);
    }

    @Test
    public void onDataLoaded_statsSizeZero_hasNoPreference() {
        NetworkStats networkStats = new NetworkStats(0, 0);

        mPreferenceController.onDataLoaded(networkStats, new int[0]);

        assertThat(mPreferenceGroup.getPreferenceCount()).isEqualTo(0);
    }

    @Test
    public void onDataLoaded_statsLoaded_hasTwoPreference() {
        NetworkStats networkStats = new NetworkStats(0, 0);
        NetworkStats.Entry entry1 = new NetworkStats.Entry();
        entry1.rxBytes = 100;
        NetworkStats.Entry entry2 = new NetworkStats.Entry();
        entry2.uid = UID_TETHERING;
        entry2.rxBytes = 200;

        mPreferenceController.onDataLoaded(networkStats.addEntry(entry1)
                .addEntry(entry2), new int[0]);

        assertThat(mPreferenceGroup.getPreferenceCount()).isEqualTo(2);
    }

    @Test
    public void onDataLoaded_statsLoaded_hasOnePreference() {
        when(mMockUidDetailProvider.getUidDetail(anyInt(), anyBoolean()))
                .thenReturn(mMockUidDetail);
        NetworkStats networkStats = new NetworkStats(0, 0);
        NetworkStats.Entry entry1 = new NetworkStats.Entry();
        entry1.rxBytes = 100;
        NetworkStats.Entry entry2 = new NetworkStats.Entry();
        entry2.uid = UID_TETHERING;
        entry2.rxBytes = 200;

        mPreferenceController.onDataLoaded(networkStats.addEntry(entry1)
                .addEntry(entry2), new int[0]);

        ProgressBarPreference preference1 =
                (ProgressBarPreference) mPreferenceGroup.getPreference(0);
        ProgressBarPreference preference2 =
                (ProgressBarPreference) mPreferenceGroup.getPreference(1);
        assertThat(mPreferenceGroup.getPreferenceCount()).isEqualTo(2);
        assertThat(preference1.getProgress()).isEqualTo(100);
        assertThat(preference2.getProgress()).isEqualTo(50);
    }
}
