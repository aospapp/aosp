/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.ui.plugin;

import android.content.Context;
import android.content.res.AssetManager;
import android.util.Log;
import android.util.SparseArray;

import com.android.car.ui.plugin.oemapis.PluginVersionProviderOEMV1;

import com.chassis.car.ui.plugin.PluginFactoryImpl;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Builds a Plugin that will delegate to the standard car-ui-lib implementation. The main benefit
 * of this is so that customizations can be applied to the car-ui-lib via a RRO without the need to
 * target each app specifically. Note: it only applies to the components that come through the
 * plugin system.
 */
public class PluginVersionProviderImpl implements PluginVersionProviderOEMV1 {
    public static final String TAG = "PluginVersionProvider";
    public static final String SHARED_LIBRARY_PACKAGE = "com.android.car.ui.sharedlibrary";

    private static final List<String> DENIED_PACKAGES = new ArrayList<>(List.of(
            // TODO(b/260267959) remove.
            "com.android.vending"
    ));

    @Override
    public Object getPluginFactory(int maxVersion, Context context, String packageName) {

        if (DENIED_PACKAGES.contains(packageName)) {
            return null;
        }

        SparseArray<String> r = getAssignedPackageIdentifiers(context.getAssets());
        for (int i = 0, n = r.size(); i < n; i++) {
            final int id = r.keyAt(i);
            // we can skip framework-res (0x01) and anything in the app space (0x7f)
            if (id == 0x01 || id == 0x7f) {
                continue;
            }
            if (SHARED_LIBRARY_PACKAGE.equals(r.valueAt(i))) {
                Log.d(TAG, "PluginVersionProviderImpl : getPluginFactory: rewriting R prefix"
                        + " values for " + SHARED_LIBRARY_PACKAGE + " to: "
                        + Integer.toHexString(id));
                rewriteRValues(context.getClassLoader(), r.valueAt(i), id);
            }
        }

        return new PluginFactoryImpl(new PluginContextWrapper(context));
    }

    /**
     * Finds all the AssignedPackageIdentifiers where the result will have the keys being the
     * package id (first octet of the resources) and the value is the package name.
     * Details in comment on next function on why this is needed.
     */
    private static SparseArray<String> getAssignedPackageIdentifiers(AssetManager am) {
        final Class<? extends AssetManager> rClazz = am.getClass();
        Throwable cause;
        try {
            final Method callback = rClazz.getMethod("getAssignedPackageIdentifiers");
            Object invoke = callback.invoke(am);
            return (SparseArray<String>) invoke;
        } catch (NoSuchMethodException e) {
            // No rewriting to be done.
            return new SparseArray<>();
        } catch (IllegalAccessException e) {
            cause = e;
        } catch (InvocationTargetException e) {
            cause = e.getCause();
        }

        throw new RuntimeException("Failed to find R classes ",
                cause);
    }

    /**
     * The aapt compiler generates a "onResourcesLoaded" method for R classes when the
     * --shared-lib flag is set. It also sets the first octet of the resources to
     * 0x00 with the assumption that when the package is loaded into memory the onResourcesLoaded
     * method will be called to set the octet to the correct value. This normally happens in
     * android.app.LoadedApk but since this plugin gets loaded "manually" thus it must be set here.
     * Currently, shared libraries use 0x02. If this is not done the "constants" in the R files
     * will not match that of what's loaded through the xml parsers.
     */
    private static void rewriteRValues(ClassLoader cl, String packageName, int id) {
        Throwable cause;
        try {
            final Class<?> rClazz = cl.loadClass(packageName + ".R");
            final Method callback = rClazz.getMethod("onResourcesLoaded", int.class);
            callback.invoke(null, id);
            return;
        } catch (ClassNotFoundException e) {
            // This is not necessarily an error, as some packages do not ship with resources
            // (or they do not need rewriting).
            return;
        } catch (NoSuchMethodException e) {
            // No rewriting to be done.
            return;
        } catch (IllegalAccessException e) {
            cause = e;
        } catch (InvocationTargetException e) {
            cause = e.getCause();
        }

        throw new RuntimeException("Failed to rewrite resource references for " + packageName,
                cause);
    }
}
