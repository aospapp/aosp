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
package com.android.car.ui.core;

import android.app.Activity;
import android.content.res.TypedArray;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;

import com.android.car.ui.R;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.baselayout.InsetsChangedListener;
import com.android.car.ui.toolbar.ToolbarController;
import com.android.car.ui.toolbar.ToolbarControllerImpl;
import com.android.car.ui.utils.CarUiUtils;

import java.util.Map;
import java.util.WeakHashMap;

/**
 * BaseLayoutController accepts an {@link Activity} and sets up the base layout inside of it.
 * It also exposes a {@link ToolbarController} to access the toolbar. This may be null if
 * used with a base layout without a Toolbar.
 */
class BaseLayoutController {

    private static final Map<Activity, BaseLayoutController> sBaseLayoutMap = new WeakHashMap<>();

    private InsetsUpdater mInsetsUpdater;

    /**
     * Gets a BaseLayoutController for the given {@link Activity}. Must have called
     * {@link #build(Activity)} with the same activity earlier, otherwise will return null.
     */
    @Nullable
    /* package */ static BaseLayoutController getBaseLayout(Activity activity) {
        return sBaseLayoutMap.get(activity);
    }

    @Nullable
    private ToolbarController mToolbarController;

    private BaseLayoutController(Activity activity) {
        installBaseLayout(activity);
    }

    /**
     * Create a new BaseLayoutController for the given {@link Activity}.
     *
     * <p>You can get a reference to it by calling {@link #getBaseLayout(Activity)}.
     */
    /* package */
    static void build(Activity activity) {
        if (getThemeBoolean(activity, R.attr.carUiBaseLayout)) {
            sBaseLayoutMap.put(activity, new BaseLayoutController(activity));
        }
    }

    /**
     * Destroy the BaseLayoutController for the given {@link Activity}.
     */
    /* package */
    static void destroy(Activity activity) {
        sBaseLayoutMap.remove(activity);
    }

    /**
     * Gets the {@link ToolbarController} for activities created with carUiBaseLayout and
     * carUiToolbar set to true.
     */
    @Nullable
    /* package */ ToolbarController getToolbarController() {
        return mToolbarController;
    }

    /* package */ Insets getInsets() {
        return mInsetsUpdater.getInsets();
    }

    /* package */ void dispatchNewInsets(Insets insets) {
        mInsetsUpdater.dispatchNewInsets(insets);
    }

    /* package */ void replaceInsetsChangedListenerWith(InsetsChangedListener listener) {
        mInsetsUpdater.replaceInsetsChangedListenerWith(listener);
    }

    /**
     * Installs the base layout into an activity, moving its content view under the base layout.
     *
     * <p>This function must be called during the onCreate() of the {@link Activity}.
     *
     * @param activity The {@link Activity} to install a base layout in.
     */
    private void installBaseLayout(Activity activity) {
        boolean toolbarEnabled = getThemeBoolean(activity, R.attr.carUiToolbar);
        boolean legacyToolbar = Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q;
        @LayoutRes final int baseLayoutRes;

        if (toolbarEnabled) {
            baseLayoutRes = legacyToolbar
                    ? R.layout.car_ui_base_layout_toolbar_legacy
                    : R.layout.car_ui_base_layout_toolbar;
        } else {
            baseLayoutRes = R.layout.car_ui_base_layout;
        }

        View baseLayout = LayoutInflater.from(activity)
                .inflate(baseLayoutRes, null, false);

        // Replace windowContentView with baseLayout
        ViewGroup windowContentView = CarUiUtils.findViewByRefId(
                activity.getWindow().getDecorView(), android.R.id.content);
        ViewGroup contentViewParent = (ViewGroup) windowContentView.getParent();
        int contentIndex = contentViewParent.indexOfChild(windowContentView);
        contentViewParent.removeView(windowContentView);
        contentViewParent.addView(baseLayout, contentIndex, windowContentView.getLayoutParams());

        // Add windowContentView to the baseLayout's content view
        FrameLayout contentView = CarUiUtils.requireViewByRefId(baseLayout, R.id.content);
        contentView.addView(windowContentView, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT));

        if (toolbarEnabled) {
            if (legacyToolbar) {
                mToolbarController = CarUiUtils.requireViewByRefId(baseLayout, R.id.car_ui_toolbar);
            } else {
                mToolbarController = new ToolbarControllerImpl(baseLayout);
            }
        }

        mInsetsUpdater = new InsetsUpdater(activity, baseLayout, windowContentView);
        mInsetsUpdater.installListeners();
    }

    /**
     * Gets the boolean value of an Attribute from an {@link Activity Activity's}
     * {@link android.content.res.Resources.Theme}.
     */
    private static boolean getThemeBoolean(Activity activity, int attr) {
        TypedArray a = activity.getTheme().obtainStyledAttributes(new int[]{attr});

        try {
            return a.getBoolean(0, false);
        } finally {
            a.recycle();
        }
    }

    /**
     * InsetsUpdater waits for layout changes, and when there is one, calculates the appropriate
     * insets into the content view.
     *
     * <p>It then calls {@link InsetsChangedListener#onCarUiInsetsChanged(Insets)} on the
     * {@link Activity} and any {@link Fragment Fragments} the Activity might have. If
     * none of the Activity/Fragments implement {@link InsetsChangedListener}, it will set
     * padding on the content view equal to the insets.
     */
    private static class InsetsUpdater implements ViewTreeObserver.OnGlobalLayoutListener {
        // These tags mark views that should overlay the content view in the base layout.
        // OEMs should add them to views in their base layout, ie: android:tag="car_ui_left_inset"
        // Apps will then be able to draw under these views, but will be encouraged to not put
        // any user-interactable content there.
        private static final String LEFT_INSET_TAG = "car_ui_left_inset";
        private static final String RIGHT_INSET_TAG = "car_ui_right_inset";
        private static final String TOP_INSET_TAG = "car_ui_top_inset";
        private static final String BOTTOM_INSET_TAG = "car_ui_bottom_inset";

        private final Activity mActivity;
        private final View mLeftInsetView;
        private final View mRightInsetView;
        private final View mTopInsetView;
        private final View mBottomInsetView;
        private InsetsChangedListener mInsetsChangedListenerDelegate;

        private boolean mInsetsDirty = true;
        @NonNull
        private Insets mInsets = new Insets();

        /**
         * Constructs an InsetsUpdater that calculates and dispatches insets to an {@link Activity}.
         *
         * @param activity    The activity that is using base layouts
         * @param baseLayout  The root view of the base layout
         * @param contentView The android.R.id.content View
         */
        InsetsUpdater(Activity activity, View baseLayout, View contentView) {
            mActivity = activity;

            mLeftInsetView = baseLayout.findViewWithTag(LEFT_INSET_TAG);
            mRightInsetView = baseLayout.findViewWithTag(RIGHT_INSET_TAG);
            mTopInsetView = baseLayout.findViewWithTag(TOP_INSET_TAG);
            mBottomInsetView = baseLayout.findViewWithTag(BOTTOM_INSET_TAG);

            final View.OnLayoutChangeListener layoutChangeListener =
                    (View v, int left, int top, int right, int bottom,
                            int oldLeft, int oldTop, int oldRight, int oldBottom) -> {
                        if (left != oldLeft || top != oldTop
                                || right != oldRight || bottom != oldBottom) {
                            mInsetsDirty = true;
                        }
                    };

            if (mLeftInsetView != null) {
                mLeftInsetView.addOnLayoutChangeListener(layoutChangeListener);
            }
            if (mRightInsetView != null) {
                mRightInsetView.addOnLayoutChangeListener(layoutChangeListener);
            }
            if (mTopInsetView != null) {
                mTopInsetView.addOnLayoutChangeListener(layoutChangeListener);
            }
            if (mBottomInsetView != null) {
                mBottomInsetView.addOnLayoutChangeListener(layoutChangeListener);
            }
            contentView.addOnLayoutChangeListener(layoutChangeListener);
        }

        /**
         * Install a global layout listener, during which the insets will be recalculated and
         * dispatched.
         */
        void installListeners() {
            // The global layout listener will run after all the individual layout change listeners
            // so that we only updateInsets once per layout, even if multiple inset views changed
            mActivity.getWindow().getDecorView().getViewTreeObserver()
                    .addOnGlobalLayoutListener(this);
        }

        @NonNull
        Insets getInsets() {
            return mInsets;
        }

        public void replaceInsetsChangedListenerWith(InsetsChangedListener listener) {
            mInsetsChangedListenerDelegate = listener;
        }

        /**
         * onGlobalLayout() should recalculate the amount of insets we need, and then dispatch them.
         */
        @Override
        public void onGlobalLayout() {
            if (!mInsetsDirty) {
                return;
            }

            View content = CarUiUtils.requireViewByRefId(mActivity.getWindow().getDecorView(),
                    android.R.id.content);

            // Calculate how much each inset view overlays the content view
            int top = 0;
            int left = 0;
            int right = 0;
            int bottom = 0;
            if (mTopInsetView != null) {
                top = Math.max(0, getBottomOfView(mTopInsetView) - getTopOfView(content));
            }
            if (mBottomInsetView != null) {
                bottom = Math.max(0, getBottomOfView(content) - getTopOfView(mBottomInsetView));
            }
            if (mLeftInsetView != null) {
                left = Math.max(0, getRightOfView(mLeftInsetView) - getLeftOfView(content));
            }
            if (mRightInsetView != null) {
                right = Math.max(0, getRightOfView(content) - getLeftOfView(mRightInsetView));
            }
            Insets insets = new Insets(left, top, right, bottom);

            mInsetsDirty = false;
            if (!insets.equals(mInsets)) {
                mInsets = insets;
                dispatchNewInsets(insets);
            }
        }

        /**
         * Dispatch the new {@link Insets} to the {@link Activity} and all of its
         * {@link Fragment Fragments}. If none of those implement {@link InsetsChangedListener},
         * we will set the value of the insets as padding on the content view.
         *
         * @param insets The newly-changed insets.
         */
        /* package */ void dispatchNewInsets(Insets insets) {
            mInsets = insets;

            boolean handled = false;

            if (mInsetsChangedListenerDelegate != null) {
                mInsetsChangedListenerDelegate.onCarUiInsetsChanged(insets);
                handled = true;
            } else {
                // If an explicit InsetsChangedListener is not provided,
                // pass the insets to activities and fragments
                if (mActivity instanceof InsetsChangedListener) {
                    ((InsetsChangedListener) mActivity).onCarUiInsetsChanged(insets);
                    handled = true;
                }

                if (mActivity instanceof FragmentActivity) {
                    for (Fragment fragment : ((FragmentActivity) mActivity)
                            .getSupportFragmentManager().getFragments()) {
                        if (fragment instanceof InsetsChangedListener) {
                            ((InsetsChangedListener) fragment).onCarUiInsetsChanged(insets);
                            handled = true;
                        }
                    }
                }
            }

            if (!handled) {
                CarUiUtils.requireViewByRefId(mActivity.getWindow().getDecorView(),
                        android.R.id.content).setPadding(insets.getLeft(), insets.getTop(),
                        insets.getRight(), insets.getBottom());
            }
        }

        private static int getLeftOfView(View v) {
            int[] position = new int[2];
            v.getLocationOnScreen(position);
            return position[0];
        }

        private static int getRightOfView(View v) {
            int[] position = new int[2];
            v.getLocationOnScreen(position);
            return position[0] + v.getWidth();
        }

        private static int getTopOfView(View v) {
            int[] position = new int[2];
            v.getLocationOnScreen(position);
            return position[1];
        }

        private static int getBottomOfView(View v) {
            int[] position = new int[2];
            v.getLocationOnScreen(position);
            return position[1] + v.getHeight();
        }
    }
}
