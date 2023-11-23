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

package com.android.car.settings.display;

import static com.android.settingslib.display.BrightnessUtils.GAMMA_SPACE_MAX;
import static com.android.settingslib.display.BrightnessUtils.convertLinearToGamma;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.drivingstate.CarUxRestrictions;
import android.content.ContentResolver;
import android.content.Context;
import android.database.ContentObserver;
import android.os.Handler;
import android.os.Looper;
import android.os.UserHandle;
import android.provider.Settings;

import androidx.lifecycle.LifecycleOwner;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.settings.common.FragmentController;
import com.android.car.settings.common.PreferenceControllerTestUtil;
import com.android.car.settings.common.SeekBarPreference;
import com.android.car.settings.testutils.TestLifecycleOwner;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@RunWith(AndroidJUnit4.class)
public class BrightnessLevelPreferenceControllerTest {
    private static final int WAIT_TIME_SEC = 10; // Time to ensure brightness value has been written

    private Context mContext;
    private LifecycleOwner mLifecycleOwner;
    private BrightnessLevelPreferenceController mController;
    private SeekBarPreference mSeekBarPreference;
    private CountDownLatch mCountDownLatch;
    private int mMin;
    private int mMax;
    private int mMid;

    @Mock
    private FragmentController mFragmentController;

    @Before
    public void setUp() {
        mLifecycleOwner = new TestLifecycleOwner();
        MockitoAnnotations.initMocks(this);

        mCountDownLatch = new CountDownLatch(1);

        mContext = spy(ApplicationProvider.getApplicationContext());
        mMin = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMinimum);
        mMax = mContext.getResources().getInteger(
                com.android.internal.R.integer.config_screenBrightnessSettingMaximum);
        mMid = (mMax + mMin) / 2;

        mSeekBarPreference = new SeekBarPreference(mContext);
        CarUxRestrictions carUxRestrictions = new CarUxRestrictions.Builder(/* reqOpt= */ true,
                CarUxRestrictions.UX_RESTRICTIONS_BASELINE, /* timestamp= */ 0).build();
        mController = new BrightnessLevelPreferenceController(mContext,
                /* preferenceKey= */ "key", mFragmentController, carUxRestrictions);
        PreferenceControllerTestUtil.assignPreference(mController, mSeekBarPreference);

        mController.onCreate(mLifecycleOwner);
    }

    @Test
    public void onStart_registersContentObserver() {
        ContentResolver resolver = mock(ContentResolver.class);
        when(mContext.getContentResolver()).thenReturn(resolver);
        mController.onStart(mLifecycleOwner);
        verify(resolver).registerContentObserver(
                eq(Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS)), eq(false),
                any(ContentObserver.class));
    }

    @Test
    public void onStop_unregistersContentObserver() {
        ContentResolver resolver = mock(ContentResolver.class);
        when(mContext.getContentResolver()).thenReturn(resolver);
        mController.onStart(mLifecycleOwner);
        mController.onStop(mLifecycleOwner);
        verify(resolver).unregisterContentObserver(any(ContentObserver.class));
    }

    @Test
    public void testRefreshUi_maxSet() {
        mController.refreshUi();
        assertThat(mSeekBarPreference.getMax()).isEqualTo(GAMMA_SPACE_MAX);
    }

    @Test
    public void testRefreshUi_minValue() {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, mMin, UserHandle.myUserId());

        mController.refreshUi();
        assertThat(mSeekBarPreference.getValue()).isEqualTo(0);
    }

    @Test
    public void testRefreshUi_maxValue() {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, mMax, UserHandle.myUserId());

        mController.refreshUi();
        assertThat(mSeekBarPreference.getValue()).isEqualTo(GAMMA_SPACE_MAX);
    }

    @Test
    public void testRefreshUi_midValue() {
        Settings.System.putIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, mMid, UserHandle.myUserId());

        mController.refreshUi();
        assertThat(mSeekBarPreference.getValue()).isEqualTo(
                convertLinearToGamma(mMid,
                        mMin, mMax));
    }

    @Test
    public void testHandlePreferenceChanged_minValue()
            throws Settings.SettingNotFoundException, InterruptedException {
        ContentObserver contentObserver = registerContentObserver();
        mSeekBarPreference.callChangeListener(0);
        mCountDownLatch.await(WAIT_TIME_SEC, TimeUnit.SECONDS); // Wait to ensure value is written
        int currentSettingsVal = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, UserHandle.myUserId());
        unregisterContentObserver(contentObserver);
        assertThat(currentSettingsVal).isEqualTo(mMin);
    }

    @Test
    public void testHandlePreferenceChanged_maxValue()
            throws Settings.SettingNotFoundException, InterruptedException {
        ContentObserver contentObserver = registerContentObserver();
        mSeekBarPreference.callChangeListener(GAMMA_SPACE_MAX);
        mCountDownLatch.await(WAIT_TIME_SEC, TimeUnit.SECONDS); // Wait to ensure value is written
        int currentSettingsVal = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, UserHandle.myUserId());
        unregisterContentObserver(contentObserver);
        assertThat(currentSettingsVal).isEqualTo(mMax);
    }

    @Test
    public void testHandlePreferenceChanged_midValue()
            throws Settings.SettingNotFoundException, InterruptedException {
        ContentObserver contentObserver = registerContentObserver();
        mSeekBarPreference.callChangeListener(convertLinearToGamma(mMid, mMin, mMax));
        mCountDownLatch.await(WAIT_TIME_SEC, TimeUnit.SECONDS); // Wait to ensure value is written
        int currentSettingsVal = Settings.System.getIntForUser(mContext.getContentResolver(),
                Settings.System.SCREEN_BRIGHTNESS, UserHandle.myUserId());
        unregisterContentObserver(contentObserver);
        assertThat(currentSettingsVal).isEqualTo(mMid);
    }

    private ContentObserver registerContentObserver() {
        ContentObserver contentObserver = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override
            public void onChange(boolean selfChange) {
                mCountDownLatch.countDown();
            }
        };
        mContext.getContentResolver().registerContentObserver(
                Settings.System.getUriFor(Settings.System.SCREEN_BRIGHTNESS), false,
                contentObserver);

        return contentObserver;
    }

    private void unregisterContentObserver(ContentObserver contentObserver) {
        mContext.getContentResolver().unregisterContentObserver(contentObserver);
    }
}
