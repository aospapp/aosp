/*
 * Copyright (C) 2018 The Android Open Source Project
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

package com.android.cts.deviceowner;

/**
 * Invocations of {@link android.app.admin.DevicePolicyManager} methods which are either not CTS
 * tested or the CTS tests call too many other methods. Used to verify that the relevant metrics
 * are logged. Note that the metrics verification is done on the host-side.
 */
public class DevicePolicyLoggingTest extends BaseDeviceOwnerTest {
    public void testSetKeyguardDisabledLogged() {
        mDevicePolicyManager.setKeyguardDisabled(getWho(), true);
        mDevicePolicyManager.setKeyguardDisabled(getWho(), false);
    }

    public void testSetStatusBarDisabledLogged() {
        mDevicePolicyManager.setStatusBarDisabled(getWho(), true);
        mDevicePolicyManager.setStatusBarDisabled(getWho(), false);
    }
}
