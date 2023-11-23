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
package com.android.car.ui;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import android.annotation.TargetApi;
import android.content.Context;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatViewInflater;
import androidx.appcompat.widget.AppCompatTextView;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.pluginsupport.PluginFactorySingleton;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.widget.CarUiTextView;

/**
 * A custom {@link LayoutInflater.Factory2} that will create CarUi components such as {@link
 * CarUiRecyclerView}. It extends AppCompatViewInflater so that it can still let AppCompat
 * components be created correctly.
 */
@TargetApi(MIN_TARGET_API)
public class CarUiLayoutInflaterFactory extends AppCompatViewInflater
        implements LayoutInflater.Factory2 {

    @Nullable
    protected View createView(Context context, String name, AttributeSet attrs) {
        View view = null;

        // Don't use CarUiTextView.class.getSimpleName(), as when proguard obfuscates the class name
        // it will no longer match what's in xml.
        if (CarUiRecyclerView.class.getName().equals(name)) {
            view = PluginFactorySingleton.get(context)
                    .createRecyclerView(context, attrs).getView();
        } else if (name.contentEquals("CarUiTextView")) {
            view = PluginFactorySingleton.get(context).createTextView(context, attrs);
        } else if (("androidx.recyclerview.widget." + "RecyclerView").equals(name)) {
            // Some apps use the old android.support.v7.widget.RecyclerView package name for the
            // RecyclerView. When RROs are applied, they must also use that old package name to
            // instantiate the RecyclerView. So if an RRO is found using the new package name,
            // inflate a RecyclerView using the old package name. We are using the new package name
            // here, but when car-ui-lib is included in one of those apps that uses the old package
            // name, the RecyclerView class is renamed.
            view = new RecyclerView(context, attrs);
        } else if ("TextView".equals(name)) {
            // Replace all TextView occurrences with CarUiTextView to support older RROs that still
            // use TextView where CarUiTextView is now expected. ie. `car_ui_list_item.xml`.
            view = PluginFactorySingleton.get(context).createTextView(context, attrs);
        }

        return view;
    }

    @NonNull
    @Override
    protected AppCompatTextView createTextView(Context context, AttributeSet attrs) {
        // Replace all TextView occurrences with CarUiTextView to support older RROs that still
        // use TextView where CarUiTextView is now expected. ie. `car_ui_list_item.xml`.
        return CarUiTextView.create(context, attrs);
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        // Deprecated, do nothing.
        return null;
    }

    @Override
    public View onCreateView(View parent, String name, Context context,
            AttributeSet attrs) {
        return createView(context, name, attrs);
    }
}
