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

import static org.mockito.Mockito.any;
import static org.mockito.Mockito.eq;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.car.Car;
import android.car.app.CarActivityManager;
import android.content.pm.UserInfo;
import android.graphics.drawable.Drawable;
import android.os.UserHandle;
import android.os.UserManager;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.broadcast.BroadcastDispatcher;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.settings.UserTracker;
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
public class QCUserPickerButtonTest extends SysuiTestCase {
    private final UserInfo mUserInfo =
            new UserInfo(/* id= */ 0, /* name= */ "Test User", /* flags= */ 0);
    @Mock
    private Car mCar;
    @Mock
    private CarActivityManager mCarActivityManager;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private BroadcastDispatcher mBroadcastDispatcher;
    @Mock
    private UserManager mUserManager;
    @Mock
    private View mView;
    @Mock
    private ImageView mUserIconView;

    private UserHandle mUserHandle;
    private ViewGroup mLayout;
    private QCUserPickerButton mQCUserPickerButton;

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        mContext = spy(mContext);
        mUserHandle = UserHandle.of(1000);
        when(mContext.getSystemService(UserManager.class)).thenReturn(mUserManager);
        when(mUserTracker.getUserHandle()).thenReturn(mUserHandle);
        when(mUserTracker.getUserInfo()).thenReturn(mUserInfo);
        mLayout = (ViewGroup) LayoutInflater.from(mContext)
                .inflate(R.layout.car_quick_controls_panel_test, /* root= */ null, false);
        mQCUserPickerButton = mLayout.findViewById(R.id.user_button);
        mQCUserPickerButton.setUserTracker(mUserTracker);
        mQCUserPickerButton.setBroadcastDispatcher(mBroadcastDispatcher);
        when(mCar.getCarManager(Car.CAR_ACTIVITY_SERVICE)).thenReturn(mCarActivityManager);
        mQCUserPickerButton.getCarServiceLifecycleListener().onLifecycleChanged(mCar, true);
    }

    @Test
    public void onUserButtonClicked_startUserPickerActivity() {
        int displayId = 100;
        when(mContext.getDisplayId()).thenReturn(displayId);

        mQCUserPickerButton.getOnClickListener().onClick(mView);

        verify(mCarActivityManager).startUserPickerOnDisplay(eq(displayId));
    }

    @Test
    public void onAttachedToWindow_setUserIconView() {
        spyOn(mQCUserPickerButton);
        when(mQCUserPickerButton.findViewById(R.id.user_icon)).thenReturn(mUserIconView);

        mQCUserPickerButton.onAttachedToWindow();

        verify(mUserIconView).setImageDrawable(any(Drawable.class));
    }

    @Test
    public void onDetachedFromWindow_removeUserNameView() {
        mQCUserPickerButton.onAttachedToWindow();
        spyOn(mQCUserPickerButton.mUserNameViewController);

        mQCUserPickerButton.onDetachedFromWindow();

        verify(mQCUserPickerButton.mUserNameViewController)
                .removeUserNameView(eq(mQCUserPickerButton));
    }
}
