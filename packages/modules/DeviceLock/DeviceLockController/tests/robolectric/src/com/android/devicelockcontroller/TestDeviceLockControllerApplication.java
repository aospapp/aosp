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

package com.android.devicelockcontroller;

import static org.mockito.Mockito.mock;

import android.app.Application;

import com.android.devicelockcontroller.policy.DevicePolicyController;
import com.android.devicelockcontroller.policy.DeviceStateController;
import com.android.devicelockcontroller.policy.PolicyObjectsInterface;
import com.android.devicelockcontroller.policy.SetupController;
import com.android.devicelockcontroller.storage.GlobalParametersClient;
import com.android.devicelockcontroller.storage.GlobalParametersService;
import com.android.devicelockcontroller.storage.SetupParametersClient;
import com.android.devicelockcontroller.storage.SetupParametersService;

import com.google.common.util.concurrent.testing.TestingExecutors;

import org.robolectric.Robolectric;
import org.robolectric.TestLifecycleApplication;

import java.lang.reflect.Method;

/**
 * Application class that provides mock objects for tests.
 */
public final class TestDeviceLockControllerApplication extends Application implements
        PolicyObjectsInterface, TestLifecycleApplication {

    private DevicePolicyController mPolicyController;
    private DeviceStateController mStateController;
    private SetupController mSetupController;
    private SetupParametersClient mSetupParametersClient;
    private GlobalParametersClient mGlobalParametersClient;

    @Override
    public DeviceStateController getStateController() {
        if (mStateController == null) {
            mStateController = mock(DeviceStateController.class);
        }
        return mStateController;
    }

    @Override
    public DevicePolicyController getPolicyController() {
        if (mPolicyController == null) {
            mPolicyController = mock(DevicePolicyController.class);
        }
        return mPolicyController;
    }

    @Override
    public SetupController getSetupController() {
        if (mSetupController == null) {
            mSetupController = mock(SetupController.class);
        }
        return mSetupController;
    }

    @Override
    public void destroyObjects() {
        mPolicyController = null;
        mStateController = null;
        mSetupController = null;
    }


    @Override
    public void beforeTest(Method method) {
        mSetupParametersClient = SetupParametersClient.getInstance(this,
                TestingExecutors.sameThreadScheduledExecutor());
        mSetupParametersClient.setService(
                Robolectric.setupService(SetupParametersService.class).onBind(/* intent= */ null));

        mGlobalParametersClient = GlobalParametersClient.getInstance(
                this, TestingExecutors.sameThreadScheduledExecutor());
        mGlobalParametersClient.setService(
                Robolectric.setupService(GlobalParametersService.class).onBind(/* intent= */ null));
    }

    @Override
    public void prepareTest(Object test) {
    }

    @Override
    public void afterTest(Method method) {
        GlobalParametersClient.reset();
        SetupParametersClient.reset();
    }
}
