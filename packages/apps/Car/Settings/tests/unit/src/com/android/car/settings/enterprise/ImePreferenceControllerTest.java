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

import static com.android.car.settings.common.PreferenceController.AVAILABLE;
import static com.android.car.settings.common.PreferenceController.CONDITIONALLY_UNAVAILABLE;
import static com.android.car.settings.common.PreferenceController.UNSUPPORTED_ON_DEVICE;

import static org.mockito.Mockito.when;

import androidx.preference.Preference;

import com.android.car.settings.R;

import org.junit.Before;
import org.junit.Test;
import org.mockito.Mock;

public final class ImePreferenceControllerTest extends BasePreferenceControllerTestCase {

    private ImePreferenceController mController;

    @Mock
    private Preference mPreference;

    @Mock
    private EnterprisePrivacyFeatureProvider mProvider;

    @Before
    public void setUp() throws Exception {
        mController = new ImePreferenceController(mSpiedContext, mPreferenceKey,
                mFragmentController, mUxRestrictions, mProvider);
    }

    @Test
    public void testGetgetAvailabilityStatus_noFeature() {
        mockNoDeviceAdminFeature();
        // Cannot use mController because it check for feature on constructor
        ImePreferenceController controller = new ImePreferenceController(mSpiedContext,
                mPreferenceKey, mFragmentController, mUxRestrictions, mProvider);

        assertAvailability(controller.getAvailabilityStatus(), UNSUPPORTED_ON_DEVICE);
    }

    @Test
    public void testGetgetAvailabilityStatus_notSet() {
        mockHasDeviceAdminFeature();
        mockGetImeLabelIfOwnerSet(null);

        assertAvailability(mController.getAvailabilityStatus(), CONDITIONALLY_UNAVAILABLE);
    }

    @Test
    public void testGetgetAvailabilityStatus_set() {
        mockHasDeviceAdminFeature();
        mockGetImeLabelIfOwnerSet("Da Lablue");

        assertAvailability(mController.getAvailabilityStatus(), AVAILABLE);
    }

    @Test
    public void testUpdateState_set() {
        mockGetImeLabelIfOwnerSet("Da Lablue");

        mController.updateState(mPreference);

        String summary = mRealContext.getResources()
                .getString(R.string.enterprise_privacy_input_method_name, "Da Lablue");
        verifyPreferenceTitleNeverSet(mPreference);
        verifyPreferenceSummarySet(mPreference, summary);
        verifyPreferenceIconNeverSet(mPreference);
    }

    private void mockGetImeLabelIfOwnerSet(String label) {
        when(mProvider.getImeLabelIfOwnerSet()).thenReturn(label);
    }
}
