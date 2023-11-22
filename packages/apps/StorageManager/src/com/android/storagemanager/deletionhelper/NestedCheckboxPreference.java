/*
 * Copyright (C) 2016 The Android Open Source Project
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

package com.android.storagemanager.deletionhelper;

import android.content.Context;
import android.support.v7.preference.CheckBoxPreference;
import android.support.v7.preference.PreferenceViewHolder;
import android.view.View;
import android.widget.CheckBox;

/**
 * NestedCheckboxPreference is a CheckBoxPreference which is nested in to align with a
 * {@link CollapsibleCheckboxPreferenceGroup}.
 */
public class NestedCheckboxPreference extends CheckBoxPreference {
    public NestedCheckboxPreference(Context context) {
        super(context);
        setLayoutResource(com.android.storagemanager.R.layout.preference_nested);
        setWidgetLayoutResource(com.android.storagemanager.R.layout.preference_widget_checkbox);
    }

    @Override
    public void onBindViewHolder(PreferenceViewHolder holder) {
        super.onBindViewHolder(holder);
        CheckBox checkboxWidget =
                (CheckBox) holder.findViewById(com.android.internal.R.id.checkbox);
        checkboxWidget.setVisibility(View.VISIBLE);
    }

}
