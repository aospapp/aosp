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

import static android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_ENABLED;
import static android.content.pm.PackageManager.MATCH_ALL;
import static android.content.pm.PackageManager.MATCH_DISABLED_COMPONENTS;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import static java.util.Objects.requireNonNull;

import android.annotation.SuppressLint;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ProviderInfo;
import android.os.Build;
import android.os.Process;
import android.os.Trace;
import android.text.TextUtils;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.annotation.VisibleForTesting;

import com.android.car.ui.R;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;

/**
 * This is a singleton that contains a {@link PluginFactory}. That PluginFactory is used to create
 * UI components that we want to be customizable by the OEM.
 */
@SuppressWarnings("AndroidJdkLibsChecker")
// TODO: (b/200322953)
@SuppressLint("LogConditional")
@RequiresApi(MIN_TARGET_API)
public final class PluginFactorySingleton {
    private enum TestingOverride {
        NOT_SET, ENABLED, DISABLED
    }

    private static final String TAG = "carui";
    private static PluginFactory sInstance;
    private static TestingOverride sTestingOverride = TestingOverride.NOT_SET;

    /**
     * Only has value during testing, null otherwise
     */
    @SuppressLint("StaticFieldLeak")
    @Nullable
    private static Context sPluginContext = null;

    /**
     * Get the {@link PluginFactory}.
     * <p>
     * If this is the first time the method is being called, it will initialize it using reflection
     * to check for the existence of a CarUi plugin, and resolving the appropriate version of the
     * plugin to use.
     */
    public static PluginFactory get(Context context) {
        try {
            Trace.beginSection("car-ui-plugin-load");
            return getImpl(context);
        } finally {
            Trace.endSection();
        }
    }

    private static PluginFactory getImpl(Context context) {
        if (sInstance != null) {
            return sInstance;
        }

        context = context.getApplicationContext();

        boolean isPluginEnabled;
        switch (sTestingOverride) {
            case ENABLED:
                isPluginEnabled = true;
                break;
            case DISABLED:
                isPluginEnabled = false;
                break;
            case NOT_SET:
            default:
                isPluginEnabled = isPluginEnabled(context);
                break;
        }

        if (!isPluginEnabled) {
            sInstance = new PluginFactoryStub();
            return sInstance;
        }

        // Check if the factory is already on the classpath and if so use it. Note: this would
        // only happen if the plugin was added as a library to the apk
        try {
            Class<?> oemApiUtilClass = Class
                    .forName("com.android.car.ui.pluginsupport.OemApiUtil");
            Method getPluginFactoryMethod = oemApiUtilClass.getDeclaredMethod(
                    "getPluginFactory", Context.class, String.class);
            getPluginFactoryMethod.setAccessible(true);
            sInstance = (PluginFactory) getPluginFactoryMethod
                    .invoke(null, context, context.getPackageName());
            return sInstance;
        } catch (ReflectiveOperationException e) {
            // plugin not found in current classpath
        }

        String pluginPackageName = getPluginPackageName(context);

        if (TextUtils.isEmpty(pluginPackageName)) {
            sInstance = new PluginFactoryStub();
            return sInstance;
        }

        final PackageInfo pluginPackageInfo;
        try {
            pluginPackageInfo = context.getPackageManager()
                    .getPackageInfo(pluginPackageName, 0);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not load CarUi plugin, package "
                    + pluginPackageName + " was not found.");
            sInstance = new PluginFactoryStub();
            return sInstance;
        }

        Context applicationContext = context.getApplicationContext();
        if (applicationContext instanceof PluginConfigProvider) {
            Set<PluginSpecifier> deniedPackages =
                    ((PluginConfigProvider) applicationContext).getPluginDenyList();
            if (deniedPackages != null && deniedPackages.stream()
                    .anyMatch(specs -> specs.matches(pluginPackageInfo))) {
                Log.i(TAG, "Package " + context.getPackageName()
                        + " denied loading plugin " + pluginPackageName);
                sInstance = new PluginFactoryStub();
                return sInstance;
            }
        }

        Context pluginContext;
        try {
            pluginContext = context.createPackageContext(
                    pluginPackageName,
                    Context.CONTEXT_INCLUDE_CODE | Context.CONTEXT_IGNORE_SECURITY);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not load CarUi plugin, package "
                    + pluginPackageName + " was not found.");
            sInstance = new PluginFactoryStub();
            return sInstance;
        }

        AdapterClassLoader adapterClassLoader =
                instantiateClassLoader(context.getApplicationInfo(),
                        requireNonNull(PluginFactorySingleton.class.getClassLoader()),
                        pluginContext.getClassLoader());

        try {
            Class<?> oemApiUtilClass = adapterClassLoader
                    .loadClass("com.android.car.ui.pluginsupport.OemApiUtil");
            Method getPluginFactoryMethod = oemApiUtilClass.getDeclaredMethod(
                    "getPluginFactory", Context.class, String.class);
            getPluginFactoryMethod.setAccessible(true);
            sInstance = (PluginFactory) getPluginFactoryMethod
                    .invoke(null, pluginContext, context.getPackageName());
        } catch (ReflectiveOperationException e) {
            Log.e(TAG, "Could not load CarUi plugin", e);
            sInstance = new PluginFactoryStub();
            return sInstance;
        }

        if (sInstance == null) {
            Log.e(TAG, "Could not load CarUi plugin");
            sInstance = new PluginFactoryStub();
            return sInstance;
        }

        Log.i(TAG, "Loaded plugin " + pluginPackageName
                + " version " + pluginPackageInfo.getLongVersionCode()
                + " for package " + context.getPackageName());

        if (sTestingOverride != TestingOverride.NOT_SET) {
            sPluginContext = pluginContext;
        }

        return sInstance;
    }

    /**
     * This method enables/disables the plugin for testing purposes. It only applies upon the next
     * call to {@link #get}, components that have already been created won't switch between the
     * plugin and regular implementations.
     * <p>
     * This method is @VisibleForTesting so that unit tests can run both with and without the
     * plugin. Apps should not use this method. Instead, apps should use {@link
     * PluginConfigProvider} to control if their plugin is disabled.
     */
    @VisibleForTesting
    public static void setPluginEnabledForTesting(boolean pluginEnabled) {
        if (pluginEnabled) {
            sTestingOverride = TestingOverride.ENABLED;
        } else {
            sTestingOverride = TestingOverride.DISABLED;
        }
        // Cause the next call to get() to reinitialize the plugin
        sInstance = null;
        sPluginContext = null;
    }

    @Nullable
    @VisibleForTesting
    public static Context getPluginContext() {
        return sPluginContext;
    }

    private PluginFactorySingleton() {
    }

    @NonNull
    private static AdapterClassLoader instantiateClassLoader(@NonNull ApplicationInfo appInfo,
            @NonNull ClassLoader parent, @NonNull ClassLoader sharedlibraryClassLoader) {
        // All this apk loading code is copied from another Google app
        List<String> libraryPaths = new ArrayList<>(3);
        if (appInfo.nativeLibraryDir != null) {
            libraryPaths.add(appInfo.nativeLibraryDir);
        }
        if ((appInfo.flags & ApplicationInfo.FLAG_EXTRACT_NATIVE_LIBS) == 0) {
            for (String abi : getSupportedAbisForCurrentRuntime()) {
                libraryPaths.add(appInfo.sourceDir + "!/lib/" + abi);
            }
        }

        String flatLibraryPaths = (libraryPaths.size() == 0
                ? null : TextUtils.join(File.pathSeparator, libraryPaths));

        String apkPaths = appInfo.sourceDir;
        if (appInfo.sharedLibraryFiles != null && appInfo.sharedLibraryFiles.length > 0) {
            // Unless you pass PackageManager.GET_SHARED_LIBRARY_FILES this will always be null
            // HOWEVER, if you running on a device with F5 active, the module's dex files are
            // always listed in ApplicationInfo.sharedLibraryFiles and should be included in
            // the classpath.
            apkPaths +=
                    File.pathSeparator + TextUtils.join(File.pathSeparator,
                            appInfo.sharedLibraryFiles);
        }

        return new AdapterClassLoader(apkPaths, flatLibraryPaths, parent, sharedlibraryClassLoader);
    }

    private static List<String> getSupportedAbisForCurrentRuntime() {
        List<String> abis = new ArrayList<>();
        if (Process.is64Bit()) {
            Collections.addAll(abis, Build.SUPPORTED_64_BIT_ABIS);
        } else {
            Collections.addAll(abis, Build.SUPPORTED_32_BIT_ABIS);
        }
        return abis;
    }

    /**
     * Return the package name for the Car UI plugin implementation.
     */
    @Nullable
    public static String getPluginPackageName(Context context) {
        String authority = context.getString(
                R.string.car_ui_plugin_package_provider_authority_name);
        ProviderInfo providerInfo = context.getPackageManager().resolveContentProvider(authority,
                MATCH_ALL | MATCH_DISABLED_COMPONENTS);
        if (providerInfo == null) {
            return null;
        }
        return providerInfo.packageName;
    }

    /**
     * Return if Car UI components should be loaded from the plugin implementation.
     */
    public static boolean isPluginEnabled(Context context) {
        String authority = context.getString(
                R.string.car_ui_plugin_package_provider_authority_name);
        PackageManager packageManager = context.getPackageManager();
        ProviderInfo providerInfo = packageManager.resolveContentProvider(authority,
                MATCH_ALL | MATCH_DISABLED_COMPONENTS);
        if (providerInfo == null) {
            return false;
        }

        ComponentName componentName = new ComponentName(providerInfo.packageName,
                providerInfo.name);
        int state = packageManager.getComponentEnabledSetting(componentName);
        if (state == PackageManager.COMPONENT_ENABLED_STATE_DEFAULT) {
            return providerInfo.enabled;
        }

        return state == COMPONENT_ENABLED_STATE_ENABLED;
    }
}
