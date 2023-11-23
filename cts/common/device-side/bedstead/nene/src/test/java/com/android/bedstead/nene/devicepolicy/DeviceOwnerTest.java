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
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasDeviceOwner;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.remotedpc.RemoteDpc;

import org.junit.Before;
import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

@RunWith(BedsteadJUnit4.class)
@EnsureHasDeviceOwner
public class DeviceOwnerTest {

    @ClassRule @Rule
    public static DeviceState sDeviceState = new DeviceState();

    private static final ComponentName DPC_COMPONENT_NAME = RemoteDpc.DPC_COMPONENT_NAME;

    private DeviceOwner mDeviceOwner;

    @Before
    public void setup() {
        mDeviceOwner = TestApis.devicePolicy().getDeviceOwner();
    }

    @Test
    public void user_returnsUser() {
        assertThat(mDeviceOwner.user()).isEqualTo(TestApis.users().system());
    }

    @Test
    public void pkg_returnsPackage() {
        assertThat(mDeviceOwner.pkg().packageName()).isEqualTo(DPC_COMPONENT_NAME.getPackageName());
    }

    @Test
    public void componentName_returnsComponentName() {
        assertThat(mDeviceOwner.componentName()).isEqualTo(DPC_COMPONENT_NAME);
    }

    @Test
    public void remove_removesDeviceOwner() {
        mDeviceOwner.remove();

        assertThat(TestApis.devicePolicy().getDeviceOwner()).isNull();
    }
}
