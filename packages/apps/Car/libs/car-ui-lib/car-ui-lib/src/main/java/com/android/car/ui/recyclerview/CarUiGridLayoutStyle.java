/*
 * Copyright (C) 2021 The Android Open Source Project
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
package com.android.car.ui.recyclerview;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager.DefaultSpanSizeLookup;
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;

import com.android.car.ui.recyclerview.CarUiRecyclerView.CarUiRecyclerViewLayout;
import com.android.car.ui.recyclerview.CarUiRecyclerView.Size;

/**
 * CarUi proxy class for {@link GridLayoutManager}
 */
public final class CarUiGridLayoutStyle implements CarUiLayoutStyle {

    private int mSpanCount = 1;
    @Orientation
    private int mLayoutOrientation = VERTICAL;
    private boolean mReverseLayout = false;
    @Size
    private int mSize = CarUiRecyclerView.SIZE_LARGE;
    @NonNull
    private SpanSizeLookup mSpanSizeLookup = new DefaultSpanSizeLookup();

    /**
     * @param layoutManager
     * @return instance {@link CarUiLayoutStyle} using the passed {@link LayoutManager}
     */
    @Nullable
    public static CarUiGridLayoutStyle from(@Nullable LayoutManager layoutManager) {
        if (layoutManager == null) return null;
        if (!(layoutManager instanceof GridLayoutManager)) {
            throw new AssertionError("GridLayoutManager required.");
        }

        CarUiGridLayoutStyle layoutStyle = new CarUiGridLayoutStyle();
        layoutStyle.setSpanCount(((GridLayoutManager) layoutManager).getSpanCount());
        layoutStyle.setReverseLayout(((GridLayoutManager) layoutManager).getReverseLayout());
        layoutStyle.setSpanSizeLookup(((GridLayoutManager) layoutManager).getSpanSizeLookup());
        return layoutStyle;
    }

    @Override
    public int getSpanCount() {
        return mSpanCount;
    }

    /** sets number of recyclerview columns */
    public void setSpanCount(int spanCount) {
        if (spanCount <= 0) {
            throw new AssertionError("Span count must be bigger than 0");
        }
        mSpanCount = spanCount;
    }

    @Override
    public int getLayoutType() {
        return CarUiRecyclerViewLayout.GRID;
    }

    @Override
    public int getOrientation() {
        return mLayoutOrientation;
    }

    /** sets layout direction {@link CarUiLayoutStyle.Orientation} */
    public void setOrientation(@Orientation int orientation) {
        mLayoutOrientation = orientation;
    }

    @Override
    public boolean getReverseLayout() {
        return mReverseLayout;
    }

    /** sets if layout is reversed */
    public void setReverseLayout(boolean reverseLayout) {
        mReverseLayout = reverseLayout;
    }

    /** Returns a wrapper {@link androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup} */
    @NonNull
    public SpanSizeLookup getSpanSizeLookup() {
        return mSpanSizeLookup;
    }

    /** Returns a wrapper {@link androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup} */
    public void setSpanSizeLookup(@NonNull SpanSizeLookup spanSizeLookup) {
        mSpanSizeLookup = spanSizeLookup;
    }

    @Override
    public int getSize() {
        return mSize;
    }

    /**
     * @param size CarUiRecyclerView size
     */
    public void setSize(int size) {
        mSize = size;
    }
}
