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

import static android.os.UserHandle.USER_NULL;

import static com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.spyOn;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.ActivityManager;
import android.app.AlertDialog;
import android.app.IActivityManager;
import android.car.Car;
import android.car.CarOccupantZoneManager;
import android.car.CarOccupantZoneManager.OccupantZoneInfo;
import android.car.app.CarActivityManager;
import android.content.DialogInterface;
import android.os.RemoteException;
import android.os.UserHandle;
import android.test.suitebuilder.annotation.SmallTest;
import android.testing.AndroidTestingRunner;
import android.testing.TestableLooper;
import android.view.Display;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.systemui.SysuiTestCase;
import com.android.systemui.car.CarSystemUiTest;
import com.android.systemui.settings.UserTracker;
import com.android.systemui.tests.R;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@CarSystemUiTest
@RunWith(AndroidTestingRunner.class)
@TestableLooper.RunWithLooper
@SmallTest
public class QCLogoutButtonTest extends SysuiTestCase {
    @Mock
    private AlertDialog mDialog;
    @Mock
    private IActivityManager mIAm;
    @Mock
    private Car mCar;
    @Mock
    private CarActivityManager mCarActivityManager;
    @Mock
    private CarOccupantZoneManager mCarOccupantZoneManager;
    @Mock
    private Display mDisplay;
    @Mock
    private UserTracker mUserTracker;
    @Mock
    private View mView;

    private ViewGroup mLayout;
    private QCLogoutButton mQCLogoutButton;
    private UserHandle mUserHandle;
    private MockitoSession mMockingSession;

    @Before
    public void setUp() {
        mMockingSession = mockitoSession()
                .initMocks(this)
                .spyStatic(ActivityManager.class)
                .strictness(Strictness.WARN)
                .startMocking();

        doReturn(mIAm).when(ActivityManager::getService);
        mContext = spy(mContext);
        mUserHandle = UserHandle.of(1000);
        when(mUserTracker.getUserHandle()).thenReturn(mUserHandle);
        mLayout = (ViewGroup) LayoutInflater.from(mContext)
                .inflate(R.layout.car_quick_controls_panel_test, /* root= */ null, false);
        mQCLogoutButton = mLayout.findViewById(R.id.logout_button);

        spyOn(mQCLogoutButton);
        mQCLogoutButton.setUserTracker(mUserTracker);

        when(mCar.getCarManager(CarOccupantZoneManager.class)).thenReturn(mCarOccupantZoneManager);
        when(mCar.getCarManager(Car.CAR_ACTIVITY_SERVICE)).thenReturn(mCarActivityManager);

        mQCLogoutButton.getCarServiceLifecycleListener().onLifecycleChanged(mCar, true);
    }

    @After
    public void tearDown() {
        if (mMockingSession != null) {
            mMockingSession.finishMocking();
            mMockingSession = null;
        }
    }

    @Test
    public void onLogoutButtonClicked_showLogoutDialog() {
        AlertDialog alertDialog = mQCLogoutButton.createDialog();
        doReturn(alertDialog).when(mQCLogoutButton).createDialog();
        spyOn(alertDialog);

        mQCLogoutButton.getOnClickListener().onClick(mView);

        verify(alertDialog).show();
        alertDialog.dismiss();
    }

    @Test
    public void onLogout_userIsNull_doesNotGetOccupantZone() {
        int userId = USER_NULL;

        when(mUserTracker.getUserId()).thenReturn(userId);

        mQCLogoutButton.getOnDialogClickListener()
                .onClick(mDialog, DialogInterface.BUTTON_POSITIVE);

        verify(mCarOccupantZoneManager, never()).getAllOccupantZones();
    }

    @Test
    public void onLogout_notFoundOccupantZoneInfo_doesNotUnassignOccupantZone() {
        int userId = 99;
        int displayId = 100;
        int otherdisplayId = 101;

        when(mUserTracker.getUserId()).thenReturn(userId);
        when(mContext.getDisplayId()).thenReturn(displayId);
        setOccupantZoneForDisplay(otherdisplayId);

        mQCLogoutButton.getOnDialogClickListener()
                .onClick(mDialog, DialogInterface.BUTTON_POSITIVE);

        verify(mCarOccupantZoneManager, never()).unassignOccupantZone(any());
    }

    @Test
    public void onLogout_successToUnassignUser_logoutUser() throws RemoteException {
        int userId = 99;
        int displayId = 100;

        when(mUserTracker.getUserId()).thenReturn(userId);
        when(mContext.getDisplayId()).thenReturn(displayId);
        setOccupantZoneForDisplay(displayId);
        when(mCarOccupantZoneManager.unassignOccupantZone(any(OccupantZoneInfo.class)))
                .thenReturn(CarOccupantZoneManager.USER_ASSIGNMENT_RESULT_OK);
        doNothing().when(mDialog).dismiss();

        mQCLogoutButton.getOnDialogClickListener()
                .onClick(mDialog, DialogInterface.BUTTON_POSITIVE);

        verify(mIAm).stopUserWithDelayedLocking(
                userId, /* force= */ false, /* callback= */ null);
    }

    @Test
    public void onLogout_failToUnassignUser_logoutUser() throws RemoteException {
        int userId = 99;
        int displayId = 100;

        when(mUserTracker.getUserId()).thenReturn(userId);
        when(mContext.getDisplayId()).thenReturn(displayId);
        setOccupantZoneForDisplay(displayId);
        when(mCarOccupantZoneManager.unassignOccupantZone(any(OccupantZoneInfo.class)))
                .thenReturn(CarOccupantZoneManager.USER_ASSIGNMENT_RESULT_FAIL_ALREADY_ASSIGNED);
        doNothing().when(mDialog).dismiss();

        mQCLogoutButton.getOnDialogClickListener()
                .onClick(mDialog, DialogInterface.BUTTON_POSITIVE);

        verify(mIAm, never()).stopUserWithDelayedLocking(
                userId, /* force= */ false, /* callback= */ null);
    }

    private void setOccupantZoneForDisplay(int displayId) {
        OccupantZoneInfo occupantZoneInfo = new OccupantZoneInfo(displayId,
                CarOccupantZoneManager.OCCUPANT_TYPE_REAR_PASSENGER, 0);
        List<OccupantZoneInfo> occupantZoneInfos =
                new ArrayList<OccupantZoneInfo>(Arrays.asList(occupantZoneInfo));
        when(mCarOccupantZoneManager.getAllOccupantZones()).thenReturn(occupantZoneInfos);
        when(mDisplay.getDisplayId()).thenReturn(displayId);
        List<Display> displays = new ArrayList<Display>(Arrays.asList(mDisplay));
        when(mCarOccupantZoneManager.getAllDisplaysForOccupant(occupantZoneInfo))
                .thenReturn(displays);
    }
}
