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

import androidx.preference.Preference;

import com.android.car.settings.common.FragmentController;

/**
 * Base class for controllers in the Enterprise Privacy / Managed Device Info screen.
 */
abstract class BaseEnterprisePrivacyPreferenceController<P extends Preference>
        extends BaseEnterprisePreferenceController<P> {

    protected final EnterprisePrivacyFeatureProvider mEnterprisePrivacyFeatureProvider;

    protected BaseEnterprisePrivacyPreferenceController(Context context,
            String preferenceKey, FragmentController fragmentController,
            CarUxRestrictions uxRestrictions) {
        super(context, preferenceKey, fragmentController, uxRestrictions);

        mEnterprisePrivacyFeatureProvider =
                new EnterprisePrivacyFeatureProviderImpl(context, mDpm, mPm);
    }

    protected BaseEnterprisePrivacyPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions uxRestrictions,
            EnterprisePrivacyFeatureProvider provider) {
        super(context, preferenceKey, fragmentController, uxRestrictions);

        mEnterprisePrivacyFeatureProvider = provider;
    }
}
