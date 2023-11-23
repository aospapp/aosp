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

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

import com.android.car.admin.ui.ManagedDeviceTextView;
import com.android.car.settings.R;
import com.android.car.settings.common.FragmentController;
import com.android.settingslib.widget.FooterPreference;

/**
 * A preference controller for the disclosure to be shown when the car is managed by an enterprise.
 * Inspired from {@link com.android.settings.accounts.EnterpriseDisclosurePreferenceController}.
 */
public final class EnterpriseDisclosurePreferenceController extends
        BaseEnterprisePreferenceController<FooterPreference> {

    public EnterpriseDisclosurePreferenceController(Context context, String key,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions) {
        super(context, key, fragmentController, uxRestrictions);
    }

    @Override
    protected int getAvailabilityStatus() {
        int superStatus = super.getAvailabilityStatus();
        if (superStatus != AVAILABLE) {
            return superStatus;
        }
        return EnterpriseUtils.hasDeviceOwner(getContext()) ? AVAILABLE : DISABLED_FOR_PROFILE;
    }

    @Override
    protected void updateState(FooterPreference footerPreference) {
        super.updateState(footerPreference);
        CharSequence disclosure = ManagedDeviceTextView.getManagedDeviceText(getContext());
        if (disclosure == null) {
            footerPreference.setVisible(false);
            return;
        }
        footerPreference.setVisible(true);
        footerPreference.setTitle(disclosure);
        footerPreference.setLearnMoreAction(view ->
                getContext().startActivity(new Intent(Settings.ACTION_PRIVACY_SETTINGS))
        );
        String learnMoreContentDescription = getContext().getString(
                R.string.footer_learn_more_content_description, getLabelName());
        footerPreference.setLearnMoreContentDescription(learnMoreContentDescription);
    }

    private String getLabelName() {
        return getContext().getString(R.string.user_add_account_menu);
    }
}
