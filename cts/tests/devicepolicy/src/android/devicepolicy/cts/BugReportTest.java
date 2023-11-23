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

import static org.testng.Assert.assertThrows;

import com.android.bedstead.harrier.BedsteadJUnit4;
import com.android.bedstead.harrier.DeviceState;
import com.android.bedstead.harrier.UserType;
import com.android.bedstead.harrier.annotations.EnsureHasAdditionalUser;
import com.android.bedstead.harrier.annotations.EnsureHasNoAdditionalUser;
import com.android.bedstead.harrier.annotations.enterprise.CanSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.CannotSetPolicyTest;
import com.android.bedstead.harrier.annotations.enterprise.EnsureHasProfileOwner;
import com.android.bedstead.harrier.policies.RequestBugReport;
import com.android.bedstead.nene.TestApis;
import com.android.bedstead.nene.users.UserReference;
import com.android.compatibility.common.util.ApiTest;

import org.junit.ClassRule;
import org.junit.Rule;
import org.junit.runner.RunWith;

import java.util.HashSet;
import java.util.Set;

@RunWith(BedsteadJUnit4.class)
public final class BugReportTest {

    @ClassRule @Rule
    public static final DeviceState sDeviceState = new DeviceState();

    // TODO: Add (interactive?) tests that bugreports prompt the user etc

    @CannotSetPolicyTest(policy = RequestBugReport.class, includeNonDeviceAdminStates = false)
    @EnsureHasNoAdditionalUser
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#requestBugreport")
    public void requestBugreport_notAllowed_throwsException() {
        assertThrows(SecurityException.class, () -> {
            sDeviceState.dpc().devicePolicyManager().requestBugreport(sDeviceState.dpc().componentName());
        });
    }

    @CanSetPolicyTest(policy = RequestBugReport.class, singleTestOnly = true)
    @EnsureHasAdditionalUser
    @EnsureHasProfileOwner(onUser = UserType.ADDITIONAL_USER, affiliationIds = {})
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#requestBugreport")
    public void requestBugReport_unaffiliatedAdditionalUser_throwsException() {
        assertThrows(SecurityException.class, () -> {
            sDeviceState.dpc().devicePolicyManager().requestBugreport(sDeviceState.dpc().componentName());
        });
    }

    @CanSetPolicyTest(policy = RequestBugReport.class)
    @EnsureHasAdditionalUser
    @EnsureHasProfileOwner(onUser = UserType.ADDITIONAL_USER, affiliationIds = "affiliated")
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#requestBugreport")
    public void requestBugReport_affiliatedAdditionalUser_doesNotThrowException() {
        // TODO(273474964): Move into infra
        TestApis.users().all().stream()
                        .filter(u -> !u.equals(TestApis.users().instrumented())
                                && !u.equals(sDeviceState.additionalUser())
                                && !u.equals(TestApis.users().current()))
                                .forEach(UserReference::remove);

        Set<String> affiliationIds = new HashSet<>(sDeviceState.dpcOnly().devicePolicyManager()
                .getAffiliationIds(sDeviceState.dpcOnly().componentName()));
        affiliationIds.add("affiliated");
        sDeviceState.dpcOnly().devicePolicyManager().setAffiliationIds(
                sDeviceState.dpc().componentName(),
                affiliationIds);

        sDeviceState.dpc().devicePolicyManager()
                .requestBugreport(sDeviceState.dpc().componentName());
    }

    @CanSetPolicyTest(policy = RequestBugReport.class)
    @EnsureHasNoAdditionalUser
    @ApiTest(apis = "android.app.admin.DevicePolicyManager#requestBugreport")
    public void requestBugReport_noAdditionalUser_doesNotThrowException() {
        // TODO(273474964): Move into infra
        TestApis.users().all().stream()
                .filter(u -> !u.equals(TestApis.users().instrumented())
                        && !u.equals(TestApis.users().current()))
                .forEach(UserReference::remove);

        sDeviceState.dpc().devicePolicyManager().requestBugreport(sDeviceState.dpc().componentName());
    }
}
