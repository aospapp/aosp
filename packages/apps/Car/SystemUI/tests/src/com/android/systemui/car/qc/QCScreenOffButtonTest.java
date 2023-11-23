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

package com.android.systemui.car.qc;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.verify;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.hardware.power.CarPowerManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.tests.R;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QCScreenOffButtonTest extends SysuiTestCase {
    private QCScreenOffButton mQCScreenOffButton;

    @Mock
    private Car mCar;
    @Mock
    private CarPowerManager mCarPowerManager;
    @Mock
    private View mView;

    private ViewGroup mLayout;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = spy(mContext);
        mLayout = (ViewGroup) LayoutInflater.from(mContext)
                .inflate(R.layout.car_quick_controls_panel_test, /* root= */ null, false);
        mQCScreenOffButton = mLayout.findViewById(R.id.screen_off_button);

        mQCScreenOffButton = new QCScreenOffButton(mContext);
        spyOn(mQCScreenOffButton);

        when(mCar.getCarManager(Car.POWER_SERVICE)).thenReturn(mCarPowerManager);
        mQCScreenOffButton.getCarServiceLifecycleListener().onLifecycleChanged(mCar, true);
    }

    @Test
    public void onPowerButtonClicked_setDisplayPowerState() {
        mQCScreenOffButton.getOnClickListener().onClick(mView);

        verify(mCarPowerManager).setDisplayPowerState(anyInt(), eq(false));
    }
}
