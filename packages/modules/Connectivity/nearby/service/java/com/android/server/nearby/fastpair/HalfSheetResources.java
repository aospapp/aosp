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

package com.android.server.nearby.fastpair;

import static com.android.server.nearby.fastpair.Constant.TAG;

import android.annotation.ColorInt;
import android.annotation.ColorRes;
import android.annotation.DrawableRes;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.annotation.StringRes;
import android.content.Context;
import android.content.pm.PackageManager;
import android.content.res.Resources;
import android.graphics.drawable.Drawable;
import android.util.Log;

import com.android.internal.annotations.VisibleForTesting;

/**
 * Utility to obtain the {@link com.android.nearby.halfsheet} {@link Resources}, in the
 * HalfSheetUX APK.
 * @hide
 */
public class HalfSheetResources {
    @NonNull
    private final Context mContext;

    @Nullable
    private Context mResourcesContext = null;

    @Nullable
    private static Context sTestResourcesContext = null;

    public HalfSheetResources(Context context) {
        mContext = context;
    }

    /**
     * Convenience method to mock all resources for the duration of a test.
     *
     * Call with a null context to reset after the test.
     */
    @VisibleForTesting
    public static void setResourcesContextForTest(@Nullable Context testContext) {
        sTestResourcesContext = testContext;
    }

    /**
     * Get the {@link Context} of the resources package.
     */
    @Nullable
    public synchronized Context getResourcesContext() {
        if (sTestResourcesContext != null) {
            return sTestResourcesContext;
        }

        if (mResourcesContext != null) {
            return mResourcesContext;
        }

        String packageName = PackageUtils.getHalfSheetApkPkgName(mContext);
        if (packageName == null) {
            Log.e(TAG, "Resolved package not found");
            return null;
        }
        final Context pkgContext;
        try {
            pkgContext = mContext.createPackageContext(packageName, 0 /* flags */);
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Resolved package not found");
            return null;
        }

        mResourcesContext = pkgContext;
        return pkgContext;
    }

    /**
     * Get the {@link Resources} of the ServiceConnectivityResources APK.
     */
    public Resources get() {
        return getResourcesContext().getResources();
    }

    /**
     * Gets the {@code String} with given resource Id.
     */
    public String getString(@StringRes int id) {
        return get().getString(id);
    }

    /**
     * Gets the {@code String} with given resource Id and formatted arguments.
     */
    public String getString(@StringRes int id, Object... formatArgs) {
        return get().getString(id, formatArgs);
    }

    /**
     * Gets the {@link Drawable} with given resource Id.
     */
    public Drawable getDrawable(@DrawableRes int id) {
        return get().getDrawable(id, getResourcesContext().getTheme());
    }

    /**
     * Gets a themed color integer associated with a particular resource ID.
     */
    @ColorInt
    public int getColor(@ColorRes int id) {
        return get().getColor(id, getResourcesContext().getTheme());
    }
}
