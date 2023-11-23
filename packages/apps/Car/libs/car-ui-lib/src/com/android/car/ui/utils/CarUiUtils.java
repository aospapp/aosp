/*
 * Copyright (C) 2019 The Android Open Source Project
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
package com.android.car.ui.utils;

import android.app.Activity;
import android.content.Context;
import android.content.ContextWrapper;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.drawable.Drawable;
import android.util.TypedValue;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DimenRes;
import androidx.annotation.IdRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StyleRes;
import androidx.annotation.UiThread;
import androidx.core.view.ViewCompat;

/**
 * Collection of utility methods
 */
public final class CarUiUtils {
    /** This is a utility class */
    private CarUiUtils() {
    }

    /**
     * Reads a float value from a dimens resource. This is necessary as {@link Resources#getFloat}
     * is not currently public.
     *
     * @param res {@link Resources} to read values from
     * @param resId Id of the dimens resource to read
     */
    public static float getFloat(Resources res, @DimenRes int resId) {
        TypedValue outValue = new TypedValue();
        res.getValue(resId, outValue, true);
        return outValue.getFloat();
    }

    /** Returns the identifier of the resolved resource assigned to the given attribute. */
    public static int getAttrResourceId(Context context, int attr) {
        return getAttrResourceId(context, /*styleResId=*/ 0, attr);
    }

    /**
     * Returns the identifier of the resolved resource assigned to the given attribute defined in
     * the given style.
     */
    public static int getAttrResourceId(Context context, @StyleRes int styleResId, int attr) {
        TypedArray ta = context.obtainStyledAttributes(styleResId, new int[]{attr});
        int resId = ta.getResourceId(0, 0);
        ta.recycle();
        return resId;
    }

    /**
     * Gets the {@link Activity} for a certain {@link Context}.
     *
     * <p>It is possible the Context is not associated with an Activity, in which case
     * this method will return null.
     */
    @Nullable
    public static Activity getActivity(Context context) {
        while (context instanceof ContextWrapper) {
            if (context instanceof Activity) {
                return (Activity) context;
            }
            context = ((ContextWrapper) context).getBaseContext();
        }
        return null;
    }

    /**
     * Updates the preference view enabled state. If the view is disabled we just disable the child
     * of preference like TextView, ImageView. The preference itself is always enabled to get the
     * click events. Ripple effect in background is also removed by default. If the ripple is
     * needed see
     * {@link IDisabledPreferenceCallback#setShouldShowRippleOnDisabledPreference(boolean)}
     */
    public static Drawable setPreferenceViewEnabled(boolean viewEnabled, View itemView,
            Drawable background, boolean shouldShowRippleOnDisabledPreference) {
        if (viewEnabled) {
            if (background != null) {
                ViewCompat.setBackground(itemView, background);
            }
            setChildViewsEnabled(itemView, true, false);
        } else {
            itemView.setEnabled(true);
            if (background == null) {
                // store the original background.
                background = itemView.getBackground();
            }
            updateRippleStateOnDisabledPreference(false, shouldShowRippleOnDisabledPreference,
                    background, itemView);
            setChildViewsEnabled(itemView, false, true);
        }
        return background;
    }

    /**
     * Sets the enabled state on the views of the preference. If the view is being disabled we want
     * only child views of preference to be disabled.
     */
    private static void setChildViewsEnabled(View view, boolean enabled, boolean isRootView) {
        if (!isRootView) {
            view.setEnabled(enabled);
        }
        if (view instanceof ViewGroup) {
            ViewGroup grp = (ViewGroup) view;
            for (int index = 0; index < grp.getChildCount(); index++) {
                setChildViewsEnabled(grp.getChildAt(index), enabled, false);
            }
        }
    }

    /**
     * Updates the ripple state on the given preference.
     *
     * @param isEnabled whether the preference is enabled or not
     * @param shouldShowRippleOnDisabledPreference should ripple be displayed when the preference is
     * clicked
     * @param background drawable that represents the ripple
     * @param preference preference on which drawable will be applied
     */
    public static void updateRippleStateOnDisabledPreference(boolean isEnabled,
            boolean shouldShowRippleOnDisabledPreference, Drawable background, View preference) {
        if (isEnabled || preference == null) {
            return;
        }
        if (shouldShowRippleOnDisabledPreference && background != null) {
            ViewCompat.setBackground(preference, background);
        } else {
            ViewCompat.setBackground(preference, null);
        }
    }

    /**
     * It behaves similar to @see View#findViewById, except it resolves the ID reference first.
     *
     * @param id the ID to search for
     * @return a view with given ID if found, or {@code null} otherwise
     * @see View#requireViewById(int)
     */
    @Nullable
    @UiThread
    public static <T extends View> T findViewByRefId(@NonNull View root, @IdRes int id) {
        if (id == View.NO_ID) {
            return null;
        }

        TypedValue value = new TypedValue();
        root.getResources().getValue(id, value, true);
        return root.findViewById(value.resourceId);
    }

    /**
     * It behaves similar to @see View#requireViewById, except it resolves the ID reference first.
     *
     * @param id the ID to search for
     * @return a view with given ID
     * @see View#findViewById(int)
     */
    @NonNull
    @UiThread
    public static <T extends View> T requireViewByRefId(@NonNull View root, @IdRes int id) {
        T view = findViewByRefId(root, id);
        if (view == null) {
            throw new IllegalArgumentException("ID "
                    + root.getResources().getResourceName(id)
                    + " does not reference a View inside this View");
        }
        return view;
    }
}
