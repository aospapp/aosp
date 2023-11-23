/*
 * Copyright (C) 2020 The Android Open Source Project
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
package com.android.car.ui.pluginsupport;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import android.annotation.TargetApi;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.FocusArea;
import com.android.car.ui.FocusAreaAdapterV1;
import com.android.car.ui.FocusParkingView;
import com.android.car.ui.FocusParkingViewAdapterV1;
import com.android.car.ui.appstyledview.AppStyledViewController;
import com.android.car.ui.appstyledview.AppStyledViewControllerAdapterV1;
import com.android.car.ui.appstyledview.AppStyledViewControllerImpl;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.baselayout.InsetsChangedListener;
import com.android.car.ui.plugin.oemapis.InsetsOEMV1;
import com.android.car.ui.plugin.oemapis.PluginFactoryOEMV1;
import com.android.car.ui.plugin.oemapis.appstyledview.AppStyledViewControllerOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.ToolbarControllerOEMV1;
import com.android.car.ui.recyclerview.CarUiListItem;
import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.toolbar.ToolbarController;
import com.android.car.ui.toolbar.ToolbarControllerAdapterV1;
import com.android.car.ui.widget.CarUiTextView;

import java.util.List;

/**
 * This class is an wrapper around {@link PluginFactoryOEMV1} that implements {@link
 * PluginFactory}, to provide a version-agnostic way of interfacing with the OEM's
 * PluginFactory.
 */
@TargetApi(MIN_TARGET_API)
public final class PluginFactoryAdapterV1 implements PluginFactory {
    @NonNull
    private final PluginFactoryOEMV1 mOem;
    @NonNull
    private final PluginFactoryStub mFactoryStub = new PluginFactoryStub();

    public PluginFactoryAdapterV1(@NonNull PluginFactoryOEMV1 oem) {
        mOem = oem;

        mOem.setRotaryFactories(
                c -> new FocusParkingViewAdapterV1(new FocusParkingView(c)),
                c -> new FocusAreaAdapterV1(new FocusArea(c)));
    }

    @Override
    @Nullable
    public ToolbarController installBaseLayoutAround(
            View contentView,
            InsetsChangedListener insetsChangedListener,
            boolean toolbarEnabled,
            boolean fullscreen) {

        if (!mOem.customizesBaseLayout()) {
            return mFactoryStub.installBaseLayoutAround(contentView,
                    insetsChangedListener, toolbarEnabled, fullscreen);
        }

        ToolbarControllerOEMV1 toolbar = mOem.installBaseLayoutAround(
                contentView.getContext(),
                contentView,
                insets -> insetsChangedListener.onCarUiInsetsChanged(adaptInsets(insets)),
                toolbarEnabled, fullscreen);

        return toolbar != null
                ? new ToolbarControllerAdapterV1(contentView.getContext(), toolbar)
                : null;
    }

    @NonNull
    @Override
    public CarUiTextView createTextView(Context context, AttributeSet attrs) {
        return mFactoryStub.createTextView(context, attrs);
    }


    @Override
    public AppStyledViewController createAppStyledView(Context activityContext) {
        AppStyledViewControllerOEMV1 appStyledViewControllerOEMV1 = mOem.createAppStyledView(
                activityContext);
        return appStyledViewControllerOEMV1 == null ? new AppStyledViewControllerImpl(
                activityContext) : new AppStyledViewControllerAdapterV1(
                appStyledViewControllerOEMV1);
    }

    private Insets adaptInsets(InsetsOEMV1 insetsOEM) {
        return new Insets(insetsOEM.getLeft(), insetsOEM.getTop(),
                insetsOEM.getRight(), insetsOEM.getBottom());
    }

    @Override
    public CarUiRecyclerView createRecyclerView(Context context, AttributeSet attrs) {
        return mFactoryStub.createRecyclerView(context, attrs);
    }

    @Override
    public RecyclerView.Adapter<? extends RecyclerView.ViewHolder> createListItemAdapter(
            List<? extends CarUiListItem> items) {
        return mFactoryStub.createListItemAdapter(items);
    }
}
