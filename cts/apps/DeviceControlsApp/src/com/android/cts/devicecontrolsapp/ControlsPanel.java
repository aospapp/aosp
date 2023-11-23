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

package com.android.cts.devicecontrolsapp;

import android.app.Activity;
import android.os.Bundle;
import android.service.controls.ControlsProviderService;
import android.text.Html;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.Nullable;

public class ControlsPanel extends Activity {
    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        TextView text = new TextView(this);
        setContentView(text);

        FrameLayout.LayoutParams lp = (FrameLayout.LayoutParams) text.getLayoutParams();
        lp.gravity = Gravity.CENTER;
        lp.width = ViewGroup.LayoutParams.WRAP_CONTENT;
        lp.height = ViewGroup.LayoutParams.WRAP_CONTENT;
        text.setLayoutParams(lp);

        boolean setting = getIntent().getBooleanExtra(
                ControlsProviderService.EXTRA_LOCKSCREEN_ALLOW_TRIVIAL_CONTROLS, false);
        text.setText(Html.fromHtml(getString(R.string.panel_content, setting),
                Html.FROM_HTML_MODE_LEGACY));
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, 20);
    }
}
