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

package com.android.car.settings.enterprise;


import static com.android.car.settings.common.PreferenceController.UNSUPPORTED_ON_DEVICE;

import static com.google.common.truth.Truth.assertThat;

import android.car.drivingstate.CarUxRestrictions;

import com.android.car.admin.ui.R;
import com.android.settingslib.widget.FooterPreference;

import org.junit.Before;
import org.junit.Test;

public final class EnterpriseDisclosurePreferenceControllerTest extends
        BasePreferenceControllerTestCase {
    private static final String ORG_NAME = "My Org";

    private EnterpriseDisclosurePreferenceController mEnterpriseDisclosurePreferenceController;
    private CarUxRestrictions mCarUxRestrictions;
    private FooterPreference mPreference;

    @Before
    public void setUp() {
        mCarUxRestrictions = new CarUxRestrictions.Builder(/* reqOpt= */ true,
                CarUxRestrictions.UX_RESTRICTIONS_BASELINE, /* timestamp= */ 0).build();
        mPreference = new FooterPreference(mSpiedContext);
        mEnterpriseDisclosurePreferenceController = new EnterpriseDisclosurePreferenceController(
                mSpiedContext, mPreferenceKey, mFragmentController, mCarUxRestrictions);
    }

    @Test
    public void testDeviceAdminFeatureMissing_noDisclosure() {
        mockNoDeviceAdminFeature();

        mEnterpriseDisclosurePreferenceController.updateState(mPreference);

        assertAvailability(mEnterpriseDisclosurePreferenceController.getAvailabilityStatus(),
                UNSUPPORTED_ON_DEVICE);
    }

    @Test
    public void testNoDeviceOwnerComponent_noDisclosure() {
        mockHasDeviceAdminFeature();
        mockNoDeviceOwner();

        mEnterpriseDisclosurePreferenceController.updateState(mPreference);

        assertAvailability(mEnterpriseDisclosurePreferenceController.getAvailabilityStatus(),
                UNSUPPORTED_ON_DEVICE);
    }

    @Test
    public void testOrganizationNameAbsent_genericDisclosure() {
        mockHasDeviceAdminFeature();
        mockDeviceOwner();
        mockOrganizationName(null);

        mEnterpriseDisclosurePreferenceController.updateState(mPreference);

        assertThat(mPreference.isVisible()).isTrue();
        assertThat(mPreference.getTitle().toString()).isEqualTo(mRealContext.getString(
                R.string.car_admin_ui_managed_device_message_generic));
    }

    @Test
    public void testOrganizationNamePresent_specificDisclosure() {
        mockHasDeviceAdminFeature();
        mockDeviceOwner();
        mockOrganizationName(ORG_NAME);

        mEnterpriseDisclosurePreferenceController.updateState(mPreference);

        assertThat(mPreference.isVisible()).isTrue();
        assertThat(mPreference.getTitle().toString()).isEqualTo(mRealContext.getString(
                R.string.car_admin_ui_managed_device_message_by_org, ORG_NAME));
    }
}
