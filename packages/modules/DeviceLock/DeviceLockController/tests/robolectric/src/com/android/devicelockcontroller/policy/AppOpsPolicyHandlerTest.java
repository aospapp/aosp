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

package com.android.devicelockcontroller.policy;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.AppOpsManager;
import android.content.Context;
import android.os.OutcomeReceiver;

import androidx.test.core.app.ApplicationProvider;

import com.android.devicelockcontroller.SystemDeviceLockManager;
import com.android.devicelockcontroller.policy.DeviceStateController.DeviceState;
import com.android.devicelockcontroller.storage.SetupParametersClientInterface;

import com.google.common.util.concurrent.Futures;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentMatchers;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;
import org.mockito.stubbing.Answer;
import org.robolectric.ParameterizedRobolectricTestRunner;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameter;
import org.robolectric.ParameterizedRobolectricTestRunner.Parameters;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executor;

@RunWith(ParameterizedRobolectricTestRunner.class)
public class AppOpsPolicyHandlerTest {
    private Context mContext;

    @Parameter
    @DeviceState
    public int mState;

    @Parameter(1)
    public boolean mExemptFromStartActivityFromBackgroundRestriction;

    @Parameter(2)
    public boolean mBackgroundRestrictionShouldBeApplied;

    @Parameter(3)
    public boolean mExemptFromHibernationRestriction;

    @Parameter(4)
    public boolean mHibernationRestrictionShouldBeApplied;

    @Parameters(name = "State: {0} is exempt from starting activity from background "
            + "restriction: {1} background restriction should be applied: {2} "
            + "is exempt from hibernation: {3} hibernation restriction should be applied: {4}")
    public static List<Object[]> parameters() {
        return Arrays.asList(new Object[][]{
                // State | exempt from background restrictions | background restriction applied |
                // exempt from hibernation | hibernation restriction applied

                // State that should not change exemptions
                {DeviceState.PSEUDO_LOCKED,     true, false, true, false},
                {DeviceState.PSEUDO_UNLOCKED,   true, false, true, false},
                // Exempt from background activity start restrictions but should not
                // change hibernation restrictions
                {DeviceState.SETUP_IN_PROGRESS, true, true, true, false},
                {DeviceState.SETUP_SUCCEEDED,   true, true, true, false},
                {DeviceState.SETUP_FAILED,      true, true, true, false},
                {DeviceState.KIOSK_SETUP,       true, true, true, false},
                // Exempt from background activity start and hibernation
                {DeviceState.UNLOCKED,          true, true, true, true},
                {DeviceState.LOCKED,            true, true, true, true},
                // Non exempt from background activity start restrictions and hibernation
                {DeviceState.UNPROVISIONED,     false, true, false, true},
                {DeviceState.CLEARED,           false, true, false, true}
        });
    }

    @Rule
    public final MockitoRule mocks = MockitoJUnit.rule();

    @Mock
    private SystemDeviceLockManager mSystemDeviceLockManagerMock;

    @Mock
    private SetupParametersClientInterface mSetupParametersClient;

    @Before
    public void setUp() {
        mContext = ApplicationProvider.getApplicationContext();
    }

    @Test
    public void setPolicyForState_setsExpectedRestrictions() {
        PolicyHandler handler = new AppOpsPolicyHandler(mContext, mSystemDeviceLockManagerMock,
                mContext.getSystemService(AppOpsManager.class), mSetupParametersClient);

        doAnswer((Answer<Boolean>) invocation -> {
            OutcomeReceiver<Void, Exception> callback = invocation.getArgument(2 /* callback */);
            callback.onResult(null /* result */);

            return null;
        }).when(mSystemDeviceLockManagerMock)
                .setExemptFromActivityBackgroundStartRestriction(anyBoolean(),
                        any(Executor.class),
                        ArgumentMatchers.<OutcomeReceiver<Void, Exception>>any());

        when(mSetupParametersClient.getKioskPackage()).thenReturn(Futures.immediateFuture(""));

        doAnswer((Answer<Boolean>) invocation -> {
            OutcomeReceiver<Void, Exception> callback = invocation.getArgument(3 /* callback */);
            callback.onResult(null /* result */);

            return null;
        }).when(mSystemDeviceLockManagerMock)
                .setExemptFromHibernation(anyString(), anyBoolean(),
                        any(Executor.class),
                        ArgumentMatchers.<OutcomeReceiver<Void, Exception>>any());

        assertThat(Futures.getUnchecked(handler.setPolicyForState(mState)))
                .isEqualTo(AppOpsPolicyHandler.SUCCESS);

        final int backgroundRestrictionCount = mBackgroundRestrictionShouldBeApplied ? 1 : 0;
        verify(mSystemDeviceLockManagerMock, times(backgroundRestrictionCount))
                .setExemptFromActivityBackgroundStartRestriction(
                        eq(mExemptFromStartActivityFromBackgroundRestriction),
                        any(Executor.class),
                        ArgumentMatchers.<OutcomeReceiver<Void, Exception>>any());

        final int hibernationCount = mHibernationRestrictionShouldBeApplied ? 1 : 0;
        verify(mSystemDeviceLockManagerMock, times(hibernationCount))
                .setExemptFromHibernation(anyString(),
                        eq(mExemptFromHibernationRestriction),
                        any(Executor.class),
                        ArgumentMatchers.<OutcomeReceiver<Void, Exception>>any());
    }
}
