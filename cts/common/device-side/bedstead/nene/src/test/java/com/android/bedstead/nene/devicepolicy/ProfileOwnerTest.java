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

package com.android.bedstead.nene.devicepolicy;

import static com.google.common.truth.Truth.assertThat;

import android.content.ComponentName;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasProfileOwner;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.users.UserReference;
import com.android.bedstead.remotedpc.RemoteDpc;
import com.android.bedstead.testapp.TestApp;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
public class ProfileOwnerTest {

    private static final ComponentName DPC_COMPONENT_NAME = RemoteDpc.DPC_COMPONENT_NAME;
    private static UserReference sProfile;
    private static TestApp sTestApp;
    private static DevicePolicyController sProfileOwner;

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    @Before
    public void setUp() {
        sProfile = TestApis.users().instrumented();
        sTestApp = sDeviceState.dpc().testApp();
        sProfileOwner = sDeviceState.profileOwner().devicePolicyController();
    }

    @Test
    @EnsureHasProfileOwner
    public void user_returnsUser() {
        assertThat(sProfileOwner.user()).isEqualTo(sProfile);
    }

    @Test
    @EnsureHasProfileOwner
    public void pkg_returnsPackage() {
        assertThat(sProfileOwner.pkg()).isEqualTo(sTestApp.pkg());
    }

    @Test
    @EnsureHasProfileOwner
    public void componentName_returnsComponentName() {
        assertThat(sProfileOwner.componentName()).isEqualTo(DPC_COMPONENT_NAME);
    }

    @Test
    @EnsureHasProfileOwner
    public void remove_removesProfileOwner() {
        sProfileOwner.remove();
        try {
            assertThat(TestApis.devicePolicy().getProfileOwner(sProfile)).isNull();
        } finally {
            sProfileOwner = TestApis.devicePolicy().setProfileOwner(sProfile, DPC_COMPONENT_NAME);
        }
    }
}
