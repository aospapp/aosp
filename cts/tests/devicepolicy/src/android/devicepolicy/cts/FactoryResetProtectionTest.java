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

package android.devicepolicy.cts;

import static com.google.common.truth.Truth.assertThat;

import static org.testng.Assert.assertThrows;

import android.app.admin.FactoryResetProtectionPolicy;
import android.service.persistentdata.PersistentDataBlockManager;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.annotations.Postsubmit;
import com.android.bedstead.harrier.annotations.RequireSystemServiceAvailable;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.CannotSetPolicyTest;
import com.android.bedstead.harrier.policies.FactoryResetProtection;
import com.android.compatibility.common.util.ApiTest;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.runner.RunWith;

import java.util.List;

@RunWith(BedsteadJUnit4.class)
@RequireSystemServiceAvailable(PersistentDataBlockManager.class)
public final class FactoryResetProtectionTest {

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    private static final FactoryResetProtectionPolicy FACTORY_RESET_PROTECTION_POLICY =
            new FactoryResetProtectionPolicy.Builder()
                    .setFactoryResetProtectionEnabled(true)
                    .setFactoryResetProtectionAccounts(List.of("test@account.com")).build();

    @CannotSetPolicyTest(policy = FactoryResetProtection.class, includeNonDeviceAdminStates = false)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setFactoryResetProtectionPolicy")
    public void setFactoryResetProtectionPolicy_notPermitted_throwsException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager()
                        .setFactoryResetProtectionPolicy(
                                sDeviceState.dpc().componentName(),
                                FACTORY_RESET_PROTECTION_POLICY));
    }

    @CannotSetPolicyTest(policy = FactoryResetProtection.class)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#getFactoryResetProtectionPolicy")
    public void getFactoryResetProtectionPolicy_notPermitted_throwsException() {
        assertThrows(SecurityException.class,
                () -> sDeviceState.dpc().devicePolicyManager()
                        .getFactoryResetProtectionPolicy(
                                sDeviceState.dpc().componentName()));
    }

    @CanSetPolicyTest(policy = FactoryResetProtection.class)
    @Postsubmit(reason = "New test")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#setFactoryResetProtectionPolicy")
    public void setFactoryResetProtectionPolicy_setsFactoryResetProtectionPolicy() {
        FactoryResetProtectionPolicy originalFrpPolicy = sDeviceState.dpc().devicePolicyManager()
                .getFactoryResetProtectionPolicy(sDeviceState.dpc().componentName());

        try {
            sDeviceState.dpc().devicePolicyManager()
                    .setFactoryResetProtectionPolicy(sDeviceState.dpc().componentName(), FACTORY_RESET_PROTECTION_POLICY);

            assertThat(isEqualToFactoryResetProtectionPolicy(
                    sDeviceState.dpc().devicePolicyManager().getFactoryResetProtectionPolicy(
                    sDeviceState.dpc().componentName()))).isTrue();
        } finally {
            sDeviceState.dpc().devicePolicyManager().setFactoryResetProtectionPolicy(
                    sDeviceState.dpc().componentName(), originalFrpPolicy);
        }
    }

    private boolean isEqualToFactoryResetProtectionPolicy(FactoryResetProtectionPolicy policy) {
        assertThat(policy.isFactoryResetProtectionEnabled())
                .isEqualTo(FACTORY_RESET_PROTECTION_POLICY.isFactoryResetProtectionEnabled());
        assertThat(policy.getFactoryResetProtectionAccounts())
                .hasSize(FACTORY_RESET_PROTECTION_POLICY.getFactoryResetProtectionAccounts().size());
        assertThat(policy.getFactoryResetProtectionAccounts()
                .containsAll(FACTORY_RESET_PROTECTION_POLICY.getFactoryResetProtectionAccounts()))
                .isTrue();
        return true; // TODO: Fix this test
    }
}
