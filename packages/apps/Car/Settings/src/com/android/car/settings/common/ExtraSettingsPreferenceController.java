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

package com.android.car.settings.common;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;

import androidx.annotation.VisibleForTesting;
import androidx.preference.Preference;
import androidx.preference.PreferenceGroup;

import java.util.Map;

/**
 * Injects preferences from other system applications at a placeholder location. The placeholder
 * should be a {@link PreferenceGroup} which sets the controller attribute to the fully qualified
 * name of this class. The preference should contain an intent which will be passed to
 * {@link ExtraSettingsLoader#loadPreferences(Intent)}.
 *
 * <p>For example:
 * <pre>{@code
 * <PreferenceCategory
 *     android:key="@string/pk_system_extra_settings"
 *     android:title="@string/system_extra_settings_title"
 *     settings:controller="com.android.settings.common.ExtraSettingsPreferenceController">
 *     <intent android:action="com.android.settings.action.EXTRA_SETTINGS">
 *         <extra android:name="com.android.settings.category"
 *                android:value="com.android.settings.category.system"/>
 *     </intent>
 * </PreferenceCategory>
 * }</pre>
 *
 * @see ExtraSettingsLoader
 */
// TODO: investigate using SettingsLib Tiles.
public class ExtraSettingsPreferenceController extends PreferenceController<PreferenceGroup> {

    @VisibleForTesting
    static final String META_DATA_DISTRACTION_OPTIMIZED = "distractionOptimized";

    private ExtraSettingsLoader mExtraSettingsLoader;
    private boolean mSettingsLoaded;

    public ExtraSettingsPreferenceController(Context context, String preferenceKey,
            FragmentController fragmentController, CarUxRestrictions restrictionInfo) {
        super(context, preferenceKey, fragmentController, restrictionInfo);
        mExtraSettingsLoader = new ExtraSettingsLoader(context);
    }

    @VisibleForTesting(otherwise = VisibleForTesting.NONE)
    public void setExtraSettingsLoader(ExtraSettingsLoader extraSettingsLoader) {
        mExtraSettingsLoader = extraSettingsLoader;
    }

    @Override
    protected Class<PreferenceGroup> getPreferenceType() {
        return PreferenceGroup.class;
    }

    @Override
    protected void onApplyUxRestrictions(CarUxRestrictions uxRestrictions) {
        super.onApplyUxRestrictions(uxRestrictions);

        // If preference intents into an activity that's not distraction optimized, disable the
        // preference. This will override the UXRE flags config_ignore_ux_restrictions and
        // config_always_ignore_ux_restrictions because navigating to these non distraction
        // optimized activities will cause the blocking activity to come up, which dead ends the
        // user.
        for (int i = 0; i < getPreference().getPreferenceCount(); i++) {
            Preference preference = getPreference().getPreference(i);
            if (uxRestrictions.isRequiresDistractionOptimization()
                    && !preference.getExtras().getBoolean(META_DATA_DISTRACTION_OPTIMIZED)) {
                preference.setEnabled(false);
            } else {
                preference.setEnabled(getAvailabilityStatus() != AVAILABLE_FOR_VIEWING);
            }
        }
    }

    @Override
    protected void updateState(PreferenceGroup preference) {
        Map<Preference, Bundle> preferenceBundleMap = mExtraSettingsLoader.loadPreferences(
                preference.getIntent());
        if (!mSettingsLoaded) {
            addExtraSettings(preferenceBundleMap);
            mSettingsLoaded = true;
        }
        preference.setVisible(preference.getPreferenceCount() > 0);
    }

    /**
     * Adds the extra settings from the system based on the intent that is passed in the preference
     * group. All the preferences that resolve these intents will be added in the preference group.
     *
     * @param preferenceBundleMap a map of {@link Preference} and {@link Bundle} representing
     * settings injected from system apps and their metadata.
     */
    protected void addExtraSettings(Map<Preference, Bundle> preferenceBundleMap) {
        for (Preference setting : preferenceBundleMap.keySet()) {
            Bundle metaData = preferenceBundleMap.get(setting);
            boolean distractionOptimized = false;
            if (metaData.containsKey(META_DATA_DISTRACTION_OPTIMIZED)) {
                distractionOptimized =
                        metaData.getBoolean(META_DATA_DISTRACTION_OPTIMIZED);
            }
            setting.getExtras().putBoolean(META_DATA_DISTRACTION_OPTIMIZED, distractionOptimized);
            getPreference().addPreference(setting);
        }
    }
}
