/*
 * Copyright 2015, The Android Open Source Project
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

package com.android.managedprovisioning;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.view.Window;
import android.view.WindowManager;

import com.android.managedprovisioning.common.Utils;
import com.android.setupwizardlib.GlifLayout;

/**
 * Base class for setting up the layout.
 */
public abstract class SetupLayoutActivity extends Activity {
    protected final Utils mUtils = new Utils();

    protected void initializeLayoutParams(int layoutResourceId, int headerResourceId,
            boolean showProgressBar) {
        setContentView(layoutResourceId);
        GlifLayout layout = (GlifLayout) findViewById(R.id.setup_wizard_layout);
        layout.setHeaderText(headerResourceId);
        if (showProgressBar) {
            layout.setProgressBarShown(true);
        }
    }

    protected void maybeSetLogoAndMainColor(Integer mainColor) {
        // null means the default value
        if (mainColor == null) {
            mainColor = getResources().getColor(R.color.orange);
        }
        // We should always use a value of 255 for the alpha.
        mainColor = Color.argb(255, Color.red(mainColor), Color.green(mainColor),
                Color.blue(mainColor));

        Window window = getWindow();
        window.clearFlags(WindowManager.LayoutParams.FLAG_TRANSLUCENT_STATUS);
        window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
        window.setStatusBarColor(mainColor);
        GlifLayout layout = (GlifLayout) findViewById(R.id.setup_wizard_layout);
        Drawable logo = LogoUtils.getOrganisationLogo(this);
        layout.setIcon(logo);
        layout.setPrimaryColor(ColorStateList.valueOf(mainColor));
    }
}
