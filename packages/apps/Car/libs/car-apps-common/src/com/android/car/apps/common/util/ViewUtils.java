/*
 * Copyright 2019 The Android Open Source Project
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

package com.android.car.apps.common.util;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.view.View;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.StringRes;

import java.util.ArrayList;
import java.util.List;

/**
 * Utility methods to operate over views.
 */
public class ViewUtils {
    /**
     * Hides a view using a fade-out animation
     *
     * @param view     {@link View} to be hidden
     * @param duration animation duration in milliseconds.
     */
    public static void hideViewAnimated(@NonNull View view, int duration) {
        // Cancel existing animation to avoid race condition
        // if show and hide are called at the same time
        view.animate().cancel();

        if (!view.isLaidOut()) {
            // If the view hasn't been displayed yet, just adjust visibility without animation
            view.setVisibility(View.GONE);
            return;
        }

        view.animate()
                .setDuration(duration)
                .setListener(hideViewAfterAnimation(view))
                .alpha(0f);
    }

    /** Returns an AnimatorListener that hides the view at the end. */
    public static Animator.AnimatorListener hideViewAfterAnimation(View view) {
        return new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) {
                view.setVisibility(View.GONE);
            }
        };
    }

    /**
     * Hides views using a fade-out animation
     *
     * @param views    {@link View}s to be hidden
     * @param duration animation duration in milliseconds.
     */
    public static void hideViewsAnimated(@Nullable List<View> views, int duration) {
        for (View view : views) {
            if (view != null) {
                hideViewAnimated(view, duration);
            }
        }
    }

    /**
     * Shows a view using a fade-in animation
     *
     * @param view     {@link View} to be shown
     * @param duration animation duration in milliseconds.
     */
    public static void showViewAnimated(@NonNull View view, int duration) {
        // Cancel existing animation to avoid race condition
        // if show and hide are called at the same time
        view.animate().cancel();

        // Do the animation even if the view isn't laid out which is often the case for a view
        // that isn't shown (otherwise the view just pops onto the screen...

        view.animate()
                .setDuration(duration)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationStart(Animator animation) {
                        view.setVisibility(View.VISIBLE);
                    }
                })
                .alpha(1f);
    }

    /**
     * Shows views using a fade-out animation
     *
     * @param views    {@link View}s to be shown.
     * @param duration animation duration in milliseconds.
     */
    public static void showViewsAnimated(@Nullable List<View> views, int duration) {
        for (View view : views) {
            if (view != null) {
                showViewAnimated(view, duration);
            }
        }
    }

    /** Sets the visibility of the (optional) view to {@link View#VISIBLE} or {@link View#GONE}. */
    public static void setVisible(@Nullable View view, boolean visible) {
        if (view != null) {
            view.setVisibility(visible ? View.VISIBLE : View.GONE);
        }
    }

    /** Sets the visibility of the views to {@link View#VISIBLE} or {@link View#GONE}. */
    public static void setVisible(@Nullable List<View> views, boolean visible) {
        for (View view : views) {
            setVisible(view, visible);
        }
    }

    /**
     * Sets the visibility of the (optional) view to {@link View#INVISIBLE} or {@link View#VISIBLE}.
     */
    public static void setInvisible(@Nullable View view, boolean invisible) {
        if (view != null) {
            view.setVisibility(invisible ? View.INVISIBLE : View.VISIBLE);
        }
    }

    /** Sets the text to the (optional) {@link TextView}. */
    public static void setText(@Nullable TextView view, @StringRes int resId) {
        if (view != null) {
            view.setText(resId);
        }
    }

    /** Sets the text to the (optional) {@link TextView}. */
    public static void setText(@Nullable TextView view, CharSequence text) {
        if (view != null) {
            view.setText(text);
        }
    }

    /** Sets the enabled state of the (optional) view. */
    public static void setEnabled(@Nullable View view, boolean enabled) {
        if (view != null) {
            view.setEnabled(enabled);
        }
    }

    /** Sets the activated state of the (optional) view. */
    public static void setActivated(@Nullable View view, boolean activated) {
        if (view != null) {
            view.setActivated(activated);
        }
    }

    /** Sets onClickListener for the (optional) view. */
    public static void setOnClickListener(@Nullable View view, @Nullable View.OnClickListener l) {
        if (view != null) {
            view.setOnClickListener(l);
        }
    }

    /** Helper interface for {@link #getViewsById(View, Resources, int, Filter)} getViewsById}. */
    public interface Filter {
        /** Returns whether a view should be added to the returned List. */
        boolean isValid(View view);
    }

    /** Get views from typed array. */
    public static List<View> getViewsById(@NonNull View root, @NonNull Resources res, int arrayId,
            @Nullable Filter filter) {
        TypedArray viewIds = res.obtainTypedArray(arrayId);
        List<View> views = new ArrayList<>(viewIds.length());
        for (int i = 0; i < viewIds.length(); i++) {
            int viewId = viewIds.getResourceId(i, 0);
            if (viewId != 0) {
                View view = root.findViewById(viewId);
                if (view != null && (filter == null || filter.isValid(view))) {
                    views.add(view);
                }
            }
        }
        viewIds.recycle();
        return views;
    }
}
