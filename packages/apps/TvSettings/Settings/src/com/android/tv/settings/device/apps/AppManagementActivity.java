/*
 * Copyright (C) 2014 The Android Open Source Project
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

package com.android.tv.settings.device.apps;

import android.net.Uri;
import android.os.Bundle;
import android.util.Log;

import androidx.fragment.app.Fragment;

import com.android.tv.settings.TvSettingsActivity;
import com.android.tv.settings.overlay.FlavorUtils;

/**
 * Activity that manages an app.
 */
public class AppManagementActivity extends TvSettingsActivity {

    private static final String TAG = "AppManagementActivity";

    @Override
    protected Fragment createSettingsFragment() {
        final Uri uri = getIntent().getData();
        if (uri == null) {
            Log.wtf(TAG, "No app to inspect (missing data uri in intent)");
            finish();
            return null;
        }

        final String packageName = uri.getSchemeSpecificPart();
        final Bundle args = new Bundle(1);
        AppManagementFragment.prepareArgs(args, packageName);

        return FlavorUtils.getFeatureFactory(this).getSettingsFragmentProvider()
                .newSettingsFragment(AppManagementFragment.class.getName(), args);
    }
}
