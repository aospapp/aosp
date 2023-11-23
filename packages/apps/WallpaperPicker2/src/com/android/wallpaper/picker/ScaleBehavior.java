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
package com.android.wallpaper.picker;

import android.content.Context;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.coordinatorlayout.widget.CoordinatorLayout;

import com.android.wallpaper.R;

/**
 * A {@link CoordinatorLayout.Behavior<View>} which can resize the child view
 * when the height of the dependency view is changed.
 */
public class ScaleBehavior extends CoordinatorLayout.Behavior<View> {

    private Context mContext;

    public ScaleBehavior(Context context, AttributeSet attrs) {
        super(context, attrs);
        mContext = context;
    }

    @Override
    public boolean layoutDependsOn(@NonNull CoordinatorLayout parent,
                                   @NonNull View child, @NonNull View dependency) {
        return dependency.getId() == R.id.scalable_content_container;
    }

    @Override
    public boolean onDependentViewChanged(@NonNull CoordinatorLayout parent,
                                          @NonNull View child, @NonNull View dependency) {
        float expectedChildHeight = dependency.getY() - getToolbarHeight();
        float originalChildHeight = child.getMeasuredHeight();
        float scale = expectedChildHeight / originalChildHeight;
        float heightDiff = expectedChildHeight - originalChildHeight;
        child.setScaleX(scale);
        child.setScaleY(scale);
        child.setTranslationY(heightDiff / 2);
        return true;
    }

    @Override
    public boolean onLayoutChild(@NonNull CoordinatorLayout parent,
                                 @NonNull View child, int layoutDirection) {
        int top = getToolbarHeight();
        child.layout(0, top, child.getMeasuredWidth(), top + child.getMeasuredHeight());
        return true;
    }

    @Override
    public boolean onMeasureChild(@NonNull CoordinatorLayout parent,
                                  @NonNull View child, int parentWidthMeasureSpec, int widthUsed,
                                  int parentHeightMeasureSpec, int heightUsed) {
        int availableWidth = View.MeasureSpec.getSize(parentWidthMeasureSpec);
        int availableHeight = View.MeasureSpec.getSize(parentHeightMeasureSpec);
        int contentContainerMinimumHeight = mContext.getResources()
                .getDimensionPixelOffset(R.dimen.content_container_minimum_height);
        int childWidth = availableWidth;
        int childHeight = availableHeight - contentContainerMinimumHeight - getToolbarHeight();
        int widthMeasureSpec =
                View.MeasureSpec.makeMeasureSpec(childWidth, View.MeasureSpec.EXACTLY);
        int heightMeasureSpec =
                View.MeasureSpec.makeMeasureSpec(childHeight, View.MeasureSpec.EXACTLY);

        child.measure(widthMeasureSpec, heightMeasureSpec);

        return true;
    }

    private int getToolbarHeight() {
        TypedValue typedValue = new TypedValue();
        if (mContext.getTheme().resolveAttribute(android.R.attr.actionBarSize, typedValue, true)) {
            return TypedValue.complexToDimensionPixelSize(
                    typedValue.data, mContext.getResources().getDisplayMetrics());
        }
        return 0;
    }
}
