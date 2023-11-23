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

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;

import android.car.drivingstate.CarUxRestrictions;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.car.ui.utils.CarUxRestrictionsUtil;
import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.tests.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QCFooterButtonViewTest extends SysuiTestCase {
    @Mock
    private View mView;
    @Mock
    private CarUxRestrictionsUtil mCarUxRestrictionsUtil;

    private ViewGroup mQcButtonView;
    private MockitoSession mMockingSession;
    private QCFooterButtonView mQCFooterButtonView;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .mockStatic(CarUxRestrictionsUtil.class)
                .strictness(Strictness.WARN)
                .startMocking();

        mContext = spy(mContext);
        mQcButtonView = (ViewGroup) LayoutInflater.from(mContext)
                .inflate(R.layout.car_quick_controls_panel_test, /* root= */ null, false);
        doReturn(mCarUxRestrictionsUtil).when(() -> CarUxRestrictionsUtil.getInstance(any()));
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
        }
    }

    @Test
    public void onButtonClicked_showLogoutDialog() {
        mQCFooterButtonView = mQcButtonView.findViewById(R.id.settings_button_enableWhileDriving);

        mQCFooterButtonView.getOnClickListener().onClick(mView);

        verify(mContext).startActivityAsUser(any(), any(), any());
    }

    @Test
    public void onAttachedToWindow_enableWhileDriving_doNotRegisterListener() {
        mQCFooterButtonView = mQcButtonView.findViewById(R.id.settings_button_enableWhileDriving);

        mQCFooterButtonView.onAttachedToWindow();

        verify(mCarUxRestrictionsUtil, never()).register(any());
    }

    @Test
    public void onAttachedToWindow_disableWhileDriving_registerListener() {
        mQCFooterButtonView = mQcButtonView.findViewById(R.id.settings_button_disableWhileDriving);

        mQCFooterButtonView.onAttachedToWindow();

        verify(mCarUxRestrictionsUtil).register(any());
    }

    @Test
    public void onRestrictionsChanged_registeredListener_setQCFooterViewEnabled() {
        mQCFooterButtonView = mQcButtonView.findViewById(R.id.settings_button_disableWhileDriving);
        spyOn(mQCFooterButtonView);
        ArgumentCaptor<CarUxRestrictionsUtil.OnUxRestrictionsChangedListener> captor =
                ArgumentCaptor.forClass(
                CarUxRestrictionsUtil.OnUxRestrictionsChangedListener.class);
        mQCFooterButtonView.onAttachedToWindow();
        verify(mCarUxRestrictionsUtil).register(captor.capture());
        CarUxRestrictionsUtil.OnUxRestrictionsChangedListener listener = captor.getValue();
        CarUxRestrictions carUxRestrictions = mock(CarUxRestrictions.class);

        listener.onRestrictionsChanged(carUxRestrictions);

        verify(mQCFooterButtonView).setEnabled(anyBoolean());
    }

    @Test
    public void onDetachedFromWindow_enableWhileDriving_doNotUnregisterListener() {
        mQCFooterButtonView = mQcButtonView.findViewById(R.id.settings_button_enableWhileDriving);

        mQCFooterButtonView.onDetachedFromWindow();

        verify(mCarUxRestrictionsUtil, never()).unregister(any());
    }

    @Test
    public void onDetachedFromWindow_disableWhileDriving_unregisterListener() {
        mQCFooterButtonView = mQcButtonView.findViewById(R.id.settings_button_disableWhileDriving);

        mQCFooterButtonView.onDetachedFromWindow();

        verify(mCarUxRestrictionsUtil).unregister(any());
    }
}
