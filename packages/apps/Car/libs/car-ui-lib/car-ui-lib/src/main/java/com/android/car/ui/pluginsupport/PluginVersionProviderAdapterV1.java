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

import android.content.Context;

import com.android.car.ui.plugin.oemapis.PluginVersionProviderOEMV1;

/**
 * This class is an wrapper around {@link PluginVersionProviderOEMV1} that implements
 * {@link PluginVersionProvider}, to provide a version-agnostic way of interfacing with
 * the OEM's PluginVersionProvider.
 */
final class PluginVersionProviderAdapterV1 implements PluginVersionProvider {

    private PluginVersionProviderOEMV1 mOemProvider;

    PluginVersionProviderAdapterV1(
            PluginVersionProviderOEMV1 oemVersionProvider) {
        mOemProvider = oemVersionProvider;
    }

    @Override
    public Object getPluginFactory(int maxVersion, Context context, String packageName) {
        return mOemProvider.getPluginFactory(maxVersion, context, packageName);
    }
}
