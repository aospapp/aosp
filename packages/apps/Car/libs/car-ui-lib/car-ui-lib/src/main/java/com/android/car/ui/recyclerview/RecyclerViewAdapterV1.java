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

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Rect;
import android.graphics.drawable.Drawable;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;
import androidx.recyclerview.widget.RecyclerView.ItemAnimator;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener;
import androidx.recyclerview.widget.RecyclerView.OnFlingListener;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.android.car.ui.plugin.oemapis.recyclerview.AdapterOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.LayoutStyleOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.OnChildAttachStateChangeListenerOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.OnScrollListenerOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewAttributesOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ViewHolderOEMV1;
import com.android.car.ui.preference.PreferenceFragment.AndroidxRecyclerViewProvider;
import com.android.car.ui.recyclerview.RecyclerViewAdapterAdapterV1.ViewHolderAdapterV1;

import java.util.ArrayList;
import java.util.List;

/**
 * AdapterV1 class for making oem implementation available for UI
 * <p>
 * For CarUi internal usage only.
 */
@TargetApi(MIN_TARGET_API)
public final class RecyclerViewAdapterV1 extends FrameLayout
        implements CarUiRecyclerView, OnScrollListenerOEMV1, AndroidxRecyclerViewProvider,
        OnChildAttachStateChangeListenerOEMV1 {

    @Nullable
    private RecyclerViewOEMV1 mOEMRecyclerView;
    @Nullable
    private AdapterOEMV1 mOEMAdapter;
    @Nullable
    private Adapter<?> mAdapter;

    @NonNull
    private final List<OnScrollListener> mScrollListeners = new ArrayList<>();
    @Nullable
    private ProxyRecyclerView mRecyclerView;
    @Nullable
    private CarUiLayoutStyle mLayoutStyle;
    private int mHeight;
    private int mWidth;
    @NonNull
    private final List<OnChildAttachStateChangeListener> mChildAttachStateChangeListeners =
            new ArrayList<>();

    public RecyclerViewAdapterV1(@NonNull Context context) {
        this(context, null);
    }

    public RecyclerViewAdapterV1(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public RecyclerViewAdapterV1(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyle) {
        super(context, attrs, defStyle, 0);
        // Background and padding to be handled by plugin implementation
        setBackground(null);
        setPadding(0, 0, 0, 0);
    }

    /**
     * Called to pass the oem recyclerview implementation.
     *
     * @param oemRecyclerView plugin implementation of {@link CarUiRecyclerView}
     */
    public void setRecyclerViewOEMV1(@NonNull RecyclerViewOEMV1 oemRecyclerView,
            @Nullable RecyclerViewAttributesOEMV1 oemAttrs) {
        mOEMRecyclerView = oemRecyclerView;

        LayoutStyleOEMV1 oemLayoutStyle = mOEMRecyclerView.getLayoutStyle();
        if (oemLayoutStyle.getLayoutType() == LayoutStyleOEMV1.LAYOUT_TYPE_GRID) {
            CarUiGridLayoutStyle layoutStyle = new CarUiGridLayoutStyle();
            layoutStyle.setReverseLayout(oemLayoutStyle.getReverseLayout());
            layoutStyle.setSpanCount(oemLayoutStyle.getSpanCount());
            layoutStyle.setOrientation(oemLayoutStyle.getOrientation());
            layoutStyle.setSize(oemAttrs == null
                    ? RecyclerViewAttributesOEMV1.SIZE_LARGE : oemAttrs.getSize());
            mLayoutStyle = layoutStyle;
        } else {
            CarUiLinearLayoutStyle layoutStyle = new CarUiLinearLayoutStyle();
            layoutStyle.setReverseLayout(oemLayoutStyle.getReverseLayout());
            layoutStyle.setOrientation(oemLayoutStyle.getOrientation());
            layoutStyle.setSize(oemAttrs == null
                    ? RecyclerViewAttributesOEMV1.SIZE_LARGE : oemAttrs.getSize());
            mLayoutStyle = layoutStyle;
        }

        // Adding this parent so androidx PreferenceFragmentCompat doesn't add the ProxyRecyclerView
        // to the view hierarchy
        ViewGroup parent = new FrameLayout(getContext());
        mRecyclerView = new ProxyRecyclerView(getContext(), this);
        parent.addView(mRecyclerView);

        mOEMRecyclerView.addOnScrollListener(this);
        mOEMRecyclerView.addOnChildAttachStateChangeListener(this);

        ViewGroup.LayoutParams params = new MarginLayoutParams(mWidth, mHeight);
        addView(mOEMRecyclerView.getView(), params);
    }

    @Override
    public void setLayoutManager(@Nullable LayoutManager layoutManager) {
        if (layoutManager instanceof GridLayoutManager) {
            setLayoutStyle(CarUiGridLayoutStyle.from(layoutManager));
        } else {
            setLayoutStyle(CarUiLinearLayoutStyle.from(layoutManager));
        }
    }

    @Override
    public void setLayoutParams(ViewGroup.LayoutParams params) {
        // Margin should be set by plugin implementation only
        Rect marginRect = null;
        if (params instanceof MarginLayoutParams) {
            MarginLayoutParams marginLayoutParams = (MarginLayoutParams) params;
            marginRect = new Rect(
                    Math.max(marginLayoutParams.leftMargin, marginLayoutParams.getMarginStart()),
                    marginLayoutParams.topMargin, marginLayoutParams.rightMargin,
                    Math.max(marginLayoutParams.bottomMargin, marginLayoutParams.getMarginEnd()));
            marginLayoutParams.setMargins(0, 0, 0, 0);
            marginLayoutParams.setMarginStart(0);
            marginLayoutParams.setMarginEnd(0);
        }

        if (mOEMRecyclerView != null) {
            ViewGroup.LayoutParams pluginLayoutParams =
                    mOEMRecyclerView.getView().getLayoutParams();
            // Account for 0dp in ConstraintLayout by swapping to MATCH_PARENT. If intentionally
            // set to size of 0dp, this FrameLayout will have a 0dp size so MATCH_PARENT will
            // still result in correct size
            pluginLayoutParams.width =
                    params.width == 0 ? ViewGroup.LayoutParams.MATCH_PARENT : params.width;
            pluginLayoutParams.height =
                    params.height == 0 ? ViewGroup.LayoutParams.MATCH_PARENT : params.height;

            // Apply requested margins to plugin implementation layout
            if (marginRect != null && pluginLayoutParams instanceof MarginLayoutParams) {
                MarginLayoutParams rvMarginParams = (MarginLayoutParams) pluginLayoutParams;
                rvMarginParams.setMargins(marginRect.left, marginRect.top, marginRect.right,
                        marginRect.bottom);
            }
        }

        mWidth = params.width;
        mHeight = params.height;
        super.setLayoutParams(params);
    }

    @Override
    public void setAlpha(float alpha) {
        if (mOEMRecyclerView != null) {
            mOEMRecyclerView.setAlpha(alpha);
        }
    }

    @Override
    public void setBackground(Drawable background) {
        if (mOEMRecyclerView != null) {
            mOEMRecyclerView.getView().setBackground(background);
        }
    }

    @Override
    public void setBackgroundColor(int color) {
        if (mOEMRecyclerView != null) {
            mOEMRecyclerView.getView().setBackgroundColor(color);
        }
    }

    @Override
    public void setBackgroundResource(int resid) {
        if (mOEMRecyclerView != null) {
            Drawable background = getResources().getDrawable(resid);
            mOEMRecyclerView.getView().setBackground(background);
        }
    }

    @Nullable
    @Override
    public RecyclerView getRecyclerView() {
        return mRecyclerView;
    }

    @Override
    public int getRecyclerViewChildCount() {
        return mOEMRecyclerView.getRecyclerViewChildCount();
    }

    @Override
    public View getRecyclerViewChildAt(int index) {
        return mOEMRecyclerView.getRecyclerViewChildAt(index);
    }

    private static int toInternalScrollState(int state) {
        /* default to RecyclerViewOEMV1.SCROLL_STATE_IDLE */
        int internalState = SCROLL_STATE_IDLE;
        switch (state) {
            case RecyclerViewOEMV1.SCROLL_STATE_DRAGGING:
                internalState = SCROLL_STATE_DRAGGING;
                break;
            case RecyclerViewOEMV1.SCROLL_STATE_SETTLING:
                internalState = SCROLL_STATE_SETTLING;
                break;
        }
        return internalState;
    }

    @Override
    public int getScrollState() {
        return toInternalScrollState(mOEMRecyclerView.getScrollState());
    }

    @Override
    public int getEndAfterPadding() {
        return mOEMRecyclerView.getEndAfterPadding();
    }

    @Override
    public int getStartAfterPadding() {
        return mOEMRecyclerView.getStartAfterPadding();
    }

    @Override
    public int getTotalSpace() {
        return mOEMRecyclerView.getTotalSpace();
    }

    @NonNull
    @Override
    public View getView() {
        return this;
    }

    @Override
    public void invalidateItemDecorations() {
    }

    @Override
    public void removeItemDecoration(@NonNull RecyclerView.ItemDecoration decor) {
    }

    @Override
    public void removeItemDecorationAt(int index) {
    }

    @Nullable
    @Override
    public RecyclerView.ItemDecoration getItemDecorationAt(int index) {
        return null;
    }

    @Override
    public int getItemDecorationCount() {
        return 0;
    }

    @Override
    public void setContentDescription(CharSequence contentDescription) {
        super.setContentDescription(contentDescription);
        mOEMRecyclerView.setContentDescription(contentDescription);
    }

    @Override
    public void setAdapter(RecyclerView.Adapter<?> adapter) {
        if (adapter == null) {
            mAdapter = null;
            mOEMAdapter = null;
            mOEMRecyclerView.setAdapter(null);
        } else {
            if (mAdapter instanceof OnAttachListener) {
                ((OnAttachListener) mAdapter).onDetachedFromCarUiRecyclerView(this);
            }
            mAdapter = adapter;
            mOEMAdapter = new RecyclerViewAdapterAdapterV1(getContext(), adapter);
            mOEMRecyclerView.setAdapter(mOEMAdapter);
            if (adapter instanceof OnAttachListener) {
                ((OnAttachListener) adapter).onAttachedToCarUiRecyclerView(this);
            }
        }
    }

    @Override
    public void addItemDecoration(@NonNull RecyclerView.ItemDecoration decor) {
    }

    @Override
    public void addItemDecoration(@NonNull RecyclerView.ItemDecoration decor, int index) {
    }

    @Override
    public void addOnChildAttachStateChangeListener(OnChildAttachStateChangeListener listener) {
        mChildAttachStateChangeListeners.add(listener);
    }

    @Override
    public void removeOnChildAttachStateChangeListener(OnChildAttachStateChangeListener listener) {
        mChildAttachStateChangeListeners.remove(listener);
    }

    @Override
    public void clearOnChildAttachStateChangeListeners() {
        mChildAttachStateChangeListeners.clear();
        mOEMRecyclerView.clearOnScrollListeners();
    }

    @Override
    public void addOnScrollListener(@NonNull OnScrollListener listener) {
        mScrollListeners.add(listener);
    }

    @Override
    public void removeOnScrollListener(@NonNull OnScrollListener listener) {
        mScrollListeners.remove(listener);
    }

    @Override
    public void clearOnScrollListeners() {
        mScrollListeners.clear();
        mOEMRecyclerView.clearOnScrollListeners();
    }

    @Override
    public void onScrollStateChanged(@NonNull RecyclerViewOEMV1 recyclerView, int newState) {
        for (OnScrollListener listener : mScrollListeners) {
            listener.onScrollStateChanged(this, toInternalScrollState(newState));
        }
    }

    @Override
    public void onScrolled(@NonNull RecyclerViewOEMV1 recyclerView, int dx, int dy) {
        for (OnScrollListener listener : mScrollListeners) {
            listener.onScrolled(this, dx, dy);
        }
    }

    @Override
    public void scrollToPosition(int position) {
        mOEMRecyclerView.scrollToPosition(position);
    }

    @Override
    public void smoothScrollBy(int dx, int dy) {
        mOEMRecyclerView.smoothScrollBy(dx, dy);
    }

    @Override
    public void smoothScrollToPosition(int position) {
        mOEMRecyclerView.smoothScrollToPosition(position);
    }

    @Override
    public ViewHolder findViewHolderForAdapterPosition(int position) {
        ViewHolderOEMV1 viewHolder = mOEMRecyclerView.findViewHolderForAdapterPosition(position);
        if (viewHolder instanceof ViewHolderAdapterV1) {
            return ((ViewHolderAdapterV1) viewHolder).getViewHolder();
        }
        return null;
    }

    @Override
    public ViewHolder findViewHolderForLayoutPosition(int position) {
        ViewHolderOEMV1 viewHolder = mOEMRecyclerView.findViewHolderForLayoutPosition(position);
        if (viewHolder instanceof ViewHolderAdapterV1) {
            return ((ViewHolderAdapterV1) viewHolder).getViewHolder();
        }
        return null;
    }

    @Override
    public Adapter<?> getAdapter() {
        return mAdapter;
    }

    @Override
    public int getChildLayoutPosition(View child) {
        return mOEMRecyclerView.getChildLayoutPosition(child);
    }

    @Override
    public void setHasFixedSize(boolean hasFixedSize) {
        mOEMRecyclerView.setHasFixedSize(hasFixedSize);
    }

    @Override
    public void setOnFlingListener(OnFlingListener listener) {
        // TODO
    }

    @Override
    public boolean hasFixedSize() {
        return mOEMRecyclerView.hasFixedSize();
    }

    /**
     * @return null
     * @deprecated LayoutManager will be implemented by OEMs, use other available APIs to get the
     * required data
     */
    @Nullable
    @Override
    @Deprecated
    public LayoutManager getLayoutManager() {
        return null;
    }

    @Override
    public CarUiLayoutStyle getLayoutStyle() {
        return mLayoutStyle;
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        return mOEMRecyclerView.getView().canScrollHorizontally(direction);
    }

    @Override
    public boolean canScrollVertically(int direction) {
        return mOEMRecyclerView.getView().canScrollVertically(direction);
    }

    @Override
    public void setLayoutStyle(CarUiLayoutStyle layoutStyle) {
        mLayoutStyle = layoutStyle;
        if (layoutStyle == null) {
            mOEMRecyclerView.setLayoutStyle(null);
            return;
        }

        final LayoutStyleOEMV1 oemLayoutStyle = new LayoutStyleOEMV1() {
            @Override
            public int getSpanCount() {
                return layoutStyle.getSpanCount();
            }

            @Override
            public int getLayoutType() {
                return layoutStyle.getLayoutType();
            }

            @Override
            public int getOrientation() {
                return layoutStyle.getOrientation();
            }

            @Override
            public boolean getReverseLayout() {
                return layoutStyle.getReverseLayout();
            }

            @Override
            public int getSpanSize(int position) {
                if (layoutStyle instanceof CarUiGridLayoutStyle) {
                    return ((CarUiGridLayoutStyle) layoutStyle).getSpanSizeLookup()
                        .getSpanSize(position);
                }
                return 1;
            }
        };

        if (mOEMRecyclerView != null) {
            mOEMRecyclerView.setLayoutStyle(oemLayoutStyle);
        }
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (mOEMRecyclerView != null) {
            mOEMRecyclerView.getView().setPadding(left, top, right, bottom);
        }
    }

    @Override
    public int getPaddingLeft() {
        if (mOEMRecyclerView != null) {
            return mOEMRecyclerView.getView().getPaddingLeft();
        }
        return super.getPaddingLeft();
    }

    @Override
    public int getPaddingTop() {
        if (mOEMRecyclerView != null) {
            return mOEMRecyclerView.getView().getPaddingTop();
        }
        return super.getPaddingTop();
    }

    @Override
    public int getPaddingRight() {
        if (mOEMRecyclerView != null) {
            return mOEMRecyclerView.getView().getPaddingRight();
        }
        return super.getPaddingRight();
    }

    @Override
    public int getPaddingBottom() {
        if (mOEMRecyclerView != null) {
            return mOEMRecyclerView.getView().getPaddingBottom();
        }
        return super.getPaddingBottom();
    }

    @Override
    public int getPaddingStart() {
        boolean isLtr = getContext().getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_LTR;
        if (isLtr) {
            return getPaddingLeft();
        } else {
            return getPaddingRight();
        }
    }

    @Override
    public int getPaddingEnd() {
        boolean isLtr = getContext().getResources().getConfiguration().getLayoutDirection()
                == View.LAYOUT_DIRECTION_LTR;
        if (isLtr) {
            return getPaddingRight();
        } else {
            return getPaddingLeft();
        }
    }

    @Override
    public void setPaddingRelative(int start, int top, int end, int bottom) {
        if (mOEMRecyclerView != null) {
            mOEMRecyclerView.getView().setPaddingRelative(start, top, end, bottom);
        }
    }

    @Override
    public void setClipToPadding(boolean clipToPadding) {
        if (mOEMRecyclerView != null) {
            mOEMRecyclerView.setClipToPadding(clipToPadding);
        }
    }

    @Override
    public void setItemAnimator(ItemAnimator itemAnimator) {
    }

    @Override
    public int findFirstCompletelyVisibleItemPosition() {
        return mOEMRecyclerView != null ? mOEMRecyclerView
                .findFirstCompletelyVisibleItemPosition() : 0;
    }

    @Override
    public int findFirstVisibleItemPosition() {
        return mOEMRecyclerView != null ? mOEMRecyclerView
                .findFirstVisibleItemPosition() : 0;
    }

    @Override
    public int findLastCompletelyVisibleItemPosition() {
        return mOEMRecyclerView != null ? mOEMRecyclerView
                .findLastCompletelyVisibleItemPosition() : 0;
    }

    @Override
    public int findLastVisibleItemPosition() {
        return mOEMRecyclerView != null ? mOEMRecyclerView
                .findLastVisibleItemPosition() : 0;
    }

    @Override
    public void setSpanSizeLookup(@NonNull SpanSizeLookup spanSizeLookup) {
        if (mLayoutStyle instanceof CarUiGridLayoutStyle) {
            ((CarUiGridLayoutStyle) mLayoutStyle).setSpanSizeLookup(spanSizeLookup);
            setLayoutStyle(mLayoutStyle);
        }
    }

    @Override
    public void onChildViewAttachedToWindow(@NonNull View view) {
        for (OnChildAttachStateChangeListener listener : mChildAttachStateChangeListeners) {
            listener.onChildViewAttachedToWindow(view);
        }
    }

    @Override
    public void onChildViewDetachedFromWindow(@NonNull View view) {
        for (OnChildAttachStateChangeListener listener : mChildAttachStateChangeListeners) {
            listener.onChildViewDetachedFromWindow(view);
        }
    }
}
