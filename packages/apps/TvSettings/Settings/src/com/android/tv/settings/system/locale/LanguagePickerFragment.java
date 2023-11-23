/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.tv.settings.system.locale;

import static com.android.tv.settings.system.locale.LocaleDataViewModel.TRANSLATED_ONLY;

import android.app.ActivityManager;
import android.app.tvsettings.TvSettingsEnums;
import android.content.Context;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.LocaleList;
import android.os.Looper;
import android.util.Log;

import androidx.annotation.Keep;
import androidx.lifecycle.ViewModelProvider;
import androidx.preference.Preference;
import androidx.preference.PreferenceScreen;

import com.android.internal.app.LocaleHelper;
import com.android.internal.app.LocaleStore;
import com.android.tv.settings.R;
import com.android.tv.settings.RadioPreference;
import com.android.tv.settings.SettingsPreferenceFragment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Collectors;

/** Language picker settings screen for locale selection. */
@Keep
public class LanguagePickerFragment extends SettingsPreferenceFragment {
    private static final String TAG = "LanguagePickerFragment";
    private static final String LANGUAGE_PICKER_RADIO_GROUP = "language_picker_group";
    private ArrayList<LocaleStore.LocaleInfo> mLocaleInfos;
    private LocaleDataViewModel mLocaleDataViewModel;
    private static final boolean DEBUG = Build.isDebuggable();
    static final String KEY_LOCALE_INFO = "locale_info";
    private Handler mHandler = new Handler(Looper.getMainLooper());

    public static LanguagePickerFragment newInstance() {
        return new LanguagePickerFragment();
    }

    @Override
    public void onCreatePreferences(Bundle savedInstanceState, String rootKey) {
        mLocaleDataViewModel = new ViewModelProvider(getActivity()).get(LocaleDataViewModel.class);
        final Context themedContext = getPreferenceManager().getContext();
        final PreferenceScreen screen =
                getPreferenceManager().createPreferenceScreen(themedContext);
        screen.setTitle(R.string.system_language);

        Locale currentLocale = LocaleDataViewModel.getCurrentLocale();
        final Set<String> langTagsToIgnore = new HashSet<>();
        mLocaleInfos = new ArrayList<>(LocaleStore.getLevelLocales(
                getContext(), langTagsToIgnore, null, TRANSLATED_ONLY));
        final Locale sortingLocale = Locale.getDefault();
        final LocaleHelper.LocaleInfoComparator comp =
                new LocaleHelper.LocaleInfoComparator(sortingLocale, false);
        mLocaleInfos.sort(comp);
        final Set<LocaleStore.LocaleInfo> notSuggestedLocales =
                mLocaleInfos.stream().filter(
                        localeInfo -> !localeInfo.isSuggested()).collect(Collectors.toSet());
        for (LocaleStore.LocaleInfo localeInfo : mLocaleInfos) {
            mLocaleDataViewModel.addLocaleInfoList(localeInfo, getActivity(), langTagsToIgnore);
            if (localeInfo.isSuggested()
                    && containsSuggestedLocale(notSuggestedLocales, localeInfo)) {
                continue;
            }
            ArrayList<LocaleStore.LocaleInfo> localeInfoWithCountryList = mLocaleDataViewModel
                    .getLocaleInfoList(localeInfo);
            if (localeInfoWithCountryList != null && localeInfoWithCountryList.size() <= 1) {
                RadioPreference preference = new RadioPreference(getContext());
                preference.setTitle(localeInfo.getFullNameNative());
                if (localeInfo.getLocale().equals(currentLocale)) {
                    preference.setChecked(true);
                } else {
                    preference.setChecked(false);
                }
                preference.setRadioGroup(LANGUAGE_PICKER_RADIO_GROUP);
                preference.getExtras().putSerializable(KEY_LOCALE_INFO, localeInfo);
                screen.addPreference(preference);
            } else {
                Preference preference = new Preference(getContext());
                preference.setFragment(CountryPickerFragment.class.getSimpleName());
                preference.setTitle(localeInfo.getFullNameNative());
                preference.getExtras().putAll(CountryPickerFragment.prepareArgs(localeInfo));
                preference.setFragment(CountryPickerFragment.class.getName());
                screen.addPreference(preference);
                preference.getExtras().putSerializable(KEY_LOCALE_INFO, localeInfo);
            }
        }

        setPreferenceScreen(screen);
    }

    private static boolean containsSuggestedLocale(Set<LocaleStore.LocaleInfo> localeInfos,
            LocaleStore.LocaleInfo suggestedLocaleInfo) {
        return localeInfos.stream().anyMatch(localeInfo -> localeInfo.getLocale().getLanguage()
                        .equals(suggestedLocaleInfo.getLocale().getLanguage())
                && localeInfo.getLocale().getScript().equals(
                        suggestedLocaleInfo.getLocale().getScript()));
    }
    @Override
    public void onResume() {
        super.onResume();
        Locale currentLocale = LocaleDataViewModel.getCurrentLocale();
        for (int i = 0; i < getPreferenceScreen().getPreferenceCount(); i++) {
            Preference pref = getPreferenceScreen().getPreference(i);
            LocaleStore.LocaleInfo localeInfo = (LocaleStore.LocaleInfo)
                    pref.getExtras().getSerializable(KEY_LOCALE_INFO);
                if (localeInfo.getLocale() != null
                        && localeInfo.getLocale().getLanguage().equals(
                                currentLocale.getLanguage())) {
                    if (DEBUG) {
                        Log.d(TAG, "Scroll to active locale: " + localeInfo.getLocale());
                    }
                    mHandler.post(() -> scrollToPreference(pref));
                }
            }
    }

    @Override
    public boolean onPreferenceTreeClick(Preference preference) {
        if (DEBUG) {
            Log.d(TAG, "Preference clicked: " + preference.getTitle());
        }
        if (preference instanceof RadioPreference) {
            RadioPreference localePref = (RadioPreference) preference;
            if (!localePref.isChecked()) {
                localePref.setChecked(true);
                return true;
            }
            LocaleStore.LocaleInfo localeInfo = localePref.getExtras()
                    .getSerializable(KEY_LOCALE_INFO, LocaleStore.LocaleInfo.class);
            if (localeInfo != null) {
                getContext().getSystemService(ActivityManager.class)
                        .setDeviceLocales(new LocaleList(localeInfo.getLocale()));
            }
            localePref.clearOtherRadioPreferences(getPreferenceScreen());
            return true;
        }
        return super.onPreferenceTreeClick(preference);
    }

    @Override
    protected int getPageId() {
        return TvSettingsEnums.SYSTEM_LANGUAGE;
    }
}
