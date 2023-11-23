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
package com.android.adservices.ui.settings.activities;

import android.os.Build;
import android.os.Bundle;

import androidx.annotation.RequiresApi;
import androidx.lifecycle.ViewModelProvider;

import com.android.adservices.api.R;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.stats.UiStatsLogger;
import com.android.adservices.ui.OTAResourcesManager;
import com.android.adservices.ui.settings.activitydelegates.MainActivityActionDelegate;
import com.android.adservices.ui.settings.delegates.MainActionDelegate;
import com.android.adservices.ui.settings.fragments.AdServicesSettingsMainFragment;
import com.android.adservices.ui.settings.viewmodels.MainViewModel;

/**
 * Android application activity for controlling settings related to PP (Privacy Preserving) APIs.
 */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class AdServicesSettingsMainActivity extends AdServicesBaseActivity {
    public static final String FROM_NOTIFICATION_KEY = "FROM_NOTIFICATION";
    private MainActionDelegate mActionDelegate;

    /** @return the action delegate for the activity. */
    public MainActionDelegate getActionDelegate() {
        return mActionDelegate;
    }

    @Override
    public void onBackPressed() {
        // if navigated here from notification, then back button should not go back to notification.
        if (getIntent().getBooleanExtra(FROM_NOTIFICATION_KEY, false)) {
            moveTaskToBack(true);
        } else {
            super.onBackPressed();
        }
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Only for main view, we want to use the most up to date OTA strings on the device to
        // create the ResourcesLoader.
        if (FlagsFactory.getFlags().getUiOtaStringsFeatureEnabled()) {
            OTAResourcesManager.applyOTAResources(getApplicationContext(), true);
        }
        UiStatsLogger.logSettingsPageDisplayed(getApplication());
        super.onCreate(savedInstanceState);
        if (!FlagsFactory.getFlags().getU18UxEnabled()) {
            initMainFragment();
        }
    }

    private void initMainFragment() {
        setContentView(R.layout.adservices_settings_main_activity);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container_view, AdServicesSettingsMainFragment.class, null)
                .setReorderingAllowed(true)
                .commit();
        mActionDelegate =
                new MainActionDelegate(this, new ViewModelProvider(this).get(MainViewModel.class));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (FlagsFactory.getFlags().getU18UxEnabled()) {
            initWithMode(true);
        }
    }

    @Override
    public void initBeta() {
        initMainActivity();
    }

    @Override
    public void initGA() {
        initMainActivity();
    }

    @Override
    public void initU18() {}

    private void initMainActivity() {
        setContentView(R.layout.main_activity);
        // no need to store since no
        new MainActivityActionDelegate(this, new ViewModelProvider(this).get(MainViewModel.class));
    }
}
