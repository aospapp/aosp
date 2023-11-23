/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.car.ui.paintbooth.preferences;

import android.os.Bundle;

import com.android.car.ui.paintbooth.R;
import com.android.car.ui.preference.CarUiPreference;
import com.android.car.ui.preference.CarUiSwitchPreference;
import com.android.car.ui.preference.PreferenceFragment;

/**
 * Fragment to load preferences
 */
public class PreferenceDemoFragment extends PreferenceFragment {

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        // Load the preferences from an XML resource
        setPreferencesFromResource(R.xml.preference_samples, rootKey);
        CarUiPreference preferenceDisabledWithoutRipple = findPreference(
                "preference_disabled_without_ripple");
        preferenceDisabledWithoutRipple.setEnabled(false);
        preferenceDisabledWithoutRipple.setMessageToShowWhenDisabledPreferenceClicked(
                "I am disabled because...");
        preferenceDisabledWithoutRipple.setShouldShowRippleOnDisabledPreference(false);

        CarUiPreference preferenceDisabledWithRipple = findPreference(
                "preference_disabled_with_ripple");
        preferenceDisabledWithRipple.setEnabled(false);
        preferenceDisabledWithRipple.setMessageToShowWhenDisabledPreferenceClicked(
                "I am disabled because...");
        preferenceDisabledWithRipple.setShouldShowRippleOnDisabledPreference(true);

        CarUiSwitchPreference carUiSwitchPreference = findPreference("switch");
        carUiSwitchPreference.setEnabled(false);
        carUiSwitchPreference.setMessageToShowWhenDisabledPreferenceClicked(
                "I am disabled because...");
    }
}
