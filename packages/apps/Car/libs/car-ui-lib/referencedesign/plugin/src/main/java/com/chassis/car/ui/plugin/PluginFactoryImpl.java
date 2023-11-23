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
package com.chassis.car.ui.plugin;

import android.content.Context;
import android.view.View;

import androidx.annotation.Nullable;

import com.android.car.ui.plugin.oemapis.FocusAreaOEMV1;
import com.android.car.ui.plugin.oemapis.FocusParkingViewOEMV1;
import com.android.car.ui.plugin.oemapis.InsetsOEMV1;
import com.android.car.ui.plugin.oemapis.PluginFactoryOEMV2;
import com.android.car.ui.plugin.oemapis.appstyledview.AppStyledViewControllerOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.AdapterOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ListItemOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewAttributesOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ViewHolderOEMV1;
import com.android.car.ui.plugin.oemapis.toolbar.ToolbarControllerOEMV1;

import com.chassis.car.ui.plugin.recyclerview.RecyclerViewImpl;
import com.chassis.car.ui.plugin.toolbar.BaseLayoutInstaller;

import java.util.List;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * An implementation of {@link PluginFactoryImpl} for creating the reference design
 * car-ui-lib components.
 */
@SuppressWarnings("AndroidJdkLibsChecker")
public class PluginFactoryImpl implements PluginFactoryOEMV2 {

    private final Context mPluginContext;
    @Nullable
    private Function<Context, FocusParkingViewOEMV1> mFocusParkingViewFactory;
    @Nullable
    private Function<Context, FocusAreaOEMV1> mFocusAreaFactory;

    public PluginFactoryImpl(Context pluginContext) {
        mPluginContext = pluginContext;
    }

    @Override
    public void setRotaryFactories(
            Function<Context, FocusParkingViewOEMV1> focusParkingViewFactory,
            Function<Context, FocusAreaOEMV1> focusAreaFactory) {
        mFocusParkingViewFactory = focusParkingViewFactory;
        mFocusAreaFactory = focusAreaFactory;
    }

    @Override
    public ToolbarControllerOEMV1 installBaseLayoutAround(
            Context sourceContext,
            View contentView,
            Consumer<InsetsOEMV1> insetsChangedListener,
            boolean toolbarEnabled,
            boolean fullscreen) {

        return BaseLayoutInstaller.installBaseLayoutAround(
                sourceContext,
                mPluginContext,
                contentView,
                insetsChangedListener,
                toolbarEnabled,
                fullscreen,
                mFocusParkingViewFactory,
                mFocusAreaFactory);
    }

    @Override
    public boolean customizesBaseLayout() {
        return true;
    }

    @Override
    public AppStyledViewControllerOEMV1 createAppStyledView(Context sourceContext) {
        //return new AppStyleViewControllerImpl(mPluginContext);
        return null;
    }

    @Override
    public RecyclerViewOEMV1 createRecyclerView(
            Context sourceContext,
            RecyclerViewAttributesOEMV1 attrs) {
        return new RecyclerViewImpl(mPluginContext, attrs);
    }

    @Override
    public AdapterOEMV1<? extends ViewHolderOEMV1> createListItemAdapter(
            List<ListItemOEMV1> items) {
        //return new ListItemAdapter(mPluginContext, items);
        return null;
    }
}
