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

import static java.lang.annotation.RetentionPolicy.SOURCE;

import android.annotation.SuppressLint;
import android.annotation.TargetApi;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.view.View.OnLayoutChangeListener;
import android.view.View.OnTouchListener;
import android.view.ViewGroup;
import android.view.ViewParent;
import android.view.ViewTreeObserver;

import androidx.annotation.IdRes;
import androidx.annotation.IntDef;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;
import androidx.recyclerview.widget.RecyclerView.ItemAnimator;
import androidx.recyclerview.widget.RecyclerView.ItemDecoration;
import androidx.recyclerview.widget.RecyclerView.LayoutManager;
import androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener;
import androidx.recyclerview.widget.RecyclerView.OnFlingListener;
import androidx.recyclerview.widget.RecyclerView.OnScrollListener;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.android.car.ui.pluginsupport.PluginFactorySingleton;

import java.lang.annotation.Retention;

/**
 * A class to access the {@link CarUiRecyclerView} methods. The appearance and layout is
 * customizable by OEM.
 * <p>
 * This is the base class for CarUiRecyclerView implementation.
 * <p>
 * Rendered views will comply with
 * <a href="https://source.android.com/devices/automotive/hmi/car_ui/appendix_b">customization guardrails</a>
 */
@TargetApi(MIN_TARGET_API)
@SuppressLint("Instantiatable")
public interface CarUiRecyclerView {
    int SIZE_SMALL = 0;
    int SIZE_MEDIUM = 1;
    int SIZE_LARGE = 2;

    /**
     * Describes the expected relative size of the {@link CarUiRecyclerView}. The list may be
     * rendered differently for each expected size.
     */
    @Retention(SOURCE)
    @IntDef({SIZE_SMALL, SIZE_MEDIUM, SIZE_LARGE})
    @interface Size {}

    /**
     * The possible values for setScrollBarPosition. The default value is {@link
     * CarUiRecyclerViewLayout#LINEAR}.
     */
    @IntDef({
        CarUiRecyclerViewLayout.LINEAR,
        CarUiRecyclerViewLayout.GRID,
    })
    @Retention(SOURCE)
    @interface CarUiRecyclerViewLayout {
        /**
         * Arranges items either horizontally in a single row or vertically in a single column.
         * This is the default value.
         */
        int LINEAR = 0;

        /**
         * Arranges items in a Grid.
         */
        int GRID = 1;
    }

    /**
     * Interface for a {@link RecyclerView.Adapter} to cap the number of items.
     *
     * <p>NOTE: it is still up to the adapter to use maxItems in {@link
     * RecyclerView.Adapter#getItemCount()}.
     *
     * <p>the recommended way would be with:
     *
     * <pre>{@code
     * {@literal@}Override
     * public int getItemCount() {
     *   return Math.min(super.getItemCount(), mMaxItems);
     * }
     * }</pre>
     */
    interface ItemCap {
        /**
         * Sets the maximum number of items available in the adapter. A value less than '0' means
         * the list should not be capped.
         */
        void setMaxItems(int maxItems);
        /**
         * A value to pass to {@link #setMaxItems(int)} that indicates there should be no limit.
         */
        int UNLIMITED = -1;
    }

    /**
     * The RecyclerView is not currently scrolling.
     *
     * @see #getScrollState()
     */
    int SCROLL_STATE_IDLE = 0;

    /**
     * The RecyclerView is currently being dragged by outside input such as user touch input.
     *
     * @see #getScrollState()
     */
    int SCROLL_STATE_DRAGGING = 1;

    /**
     * The RecyclerView is currently animating to a final position while not under
     * outside control.
     *
     * @see #getScrollState()
     */
    int SCROLL_STATE_SETTLING = 2;

    /**
     * Equivalent of {@link RecyclerView.OnScrollListener}
     */
    interface OnScrollListener {
        void onScrolled(CarUiRecyclerView recyclerView, int dx, int dy);
        void onScrollStateChanged(CarUiRecyclerView recyclerView, int newState);
    }

    /**
     * Adapters that implement this interface will receive the calls
     */
    interface OnAttachListener {
        void onAttachedToCarUiRecyclerView(@NonNull CarUiRecyclerView carUiRecyclerView);
        void onDetachedFromCarUiRecyclerView(@NonNull CarUiRecyclerView carUiRecyclerView);
    }

    /**
     * Use this method to create an instance of CarUiRecyclerView at runtime.
     */
    static CarUiRecyclerView create(Context context) {
        return PluginFactorySingleton.get(context)
            .createRecyclerView(context, null);
    }

    /**
     * Use this method to create an instance of CarUiRecyclerView at runtime.
     */
    static CarUiRecyclerView create(Context context, AttributeSet attributeSet) {
        return PluginFactorySingleton.get(context)
            .createRecyclerView(context, attributeSet);
    }

    /**
     * see {@link RecyclerView#addItemDecoration(ItemDecoration)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    void addItemDecoration(@NonNull ItemDecoration decor);

    /**
     * see {@link RecyclerView#addItemDecoration(ItemDecoration, int)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    void addItemDecoration(@NonNull ItemDecoration decor, int index);

    /** see {@link RecyclerView#addOnChildAttachStateChangeListener} */
    void addOnChildAttachStateChangeListener(OnChildAttachStateChangeListener listener);

    /** see {@link View#addOnLayoutChangeListener(OnLayoutChangeListener)} */
    void addOnLayoutChangeListener(OnLayoutChangeListener listener);

    /** see {@link RecyclerView#addOnScrollListener(RecyclerView.OnScrollListener)} */
    void addOnScrollListener(OnScrollListener scrollListener);

    /** {@link RecyclerView#clearOnChildAttachStateChangeListeners()} */
    void clearOnChildAttachStateChangeListeners();

    /** {@link RecyclerView#clearOnScrollListeners()} */
    void clearOnScrollListeners();

    /** see {@link LinearLayoutManager#findFirstCompletelyVisibleItemPosition()} */
    int findFirstCompletelyVisibleItemPosition();

    /** see {@link LinearLayoutManager#findFirstVisibleItemPosition()} */
    int findFirstVisibleItemPosition();

    /** see {@link LinearLayoutManager#findLastCompletelyVisibleItemPosition()} */
    int findLastCompletelyVisibleItemPosition();

    /** see {@link LinearLayoutManager#findLastVisibleItemPosition()} */
    int findLastVisibleItemPosition();

    /**
     * see {@link View#findViewById(int)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    <T extends View> T findViewById(int id);

    /** see {@link RecyclerView#findViewHolderForAdapterPosition(int)} */
    ViewHolder findViewHolderForAdapterPosition(int position);

    /** see {@link RecyclerView#findViewHolderForAdapterPosition(int)} */
    ViewHolder findViewHolderForLayoutPosition(int position);

    /** see {@link ViewGroup#focusableViewAvailable(View)} */
    void focusableViewAvailable(View v);

    /** see {@link RecyclerView#getAdapter()} */
    Adapter<?> getAdapter();

    /** see {@link View#getAlpha()} */
    float getAlpha();

    /**
     * see {@link ViewGroup#getChildAt(int)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    @Nullable
    View getChildAt(int index);

    /**
     * see {@link ViewGroup#getChildCount()}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    int getChildCount();

    /**
     * see {@link RecyclerView#getChildLayoutPosition(View)}
     */
    int getChildLayoutPosition(View child);

    /** see {@link View#getContentDescription()} */
    CharSequence getContentDescription();

    /** see {@link View#getContext()} */
    Context getContext();

    /** see {@link OrientationHelper#getEndAfterPadding()} */
    int getEndAfterPadding();

    /** see {@link View#getHeight()} */
    int getHeight();

    /**
     * see {@link RecyclerView#getItemDecorationAt(int)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    @Nullable
    ItemDecoration getItemDecorationAt(int index);

    /**
     * see {@link RecyclerView#getItemDecorationCount()}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    int getItemDecorationCount();

    /**
     * see {@link RecyclerView#getLayoutManager()}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    @Nullable
    LayoutManager getLayoutManager();

    /** Use this instead of {@link #getLayoutManager} */
    @Nullable
    CarUiLayoutStyle getLayoutStyle();

    /** {@link RecyclerView#hasFixedSize()} */
    boolean hasFixedSize();

    /** see {@link View#getPaddingLeft()} */
    int getPaddingLeft();

    /** see {@link View#getPaddingRight()} */
    int getPaddingRight();

    /** see {@link View#getPaddingBottom()} */
    int getPaddingBottom();

    /** see {@link View#getPaddingEnd()} */
    int getPaddingEnd();

    /** see {@link View#getPaddingStart()} */
    int getPaddingStart();

    /** see {@link View#getPaddingTop()} */
    int getPaddingTop();

    /**
     * see {@link View#getParent()}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    ViewParent getParent();

    /**
     * see {@link LayoutManager#getChildCount()}
     * Prefer this method over {@link #getChildCount()}
     */
    int getRecyclerViewChildCount();

    /**
     * see {@link LayoutManager#getChildAt(int)}
     * Prefer this method over {@link #getChildAt(int)}
     */
    @Nullable
    View getRecyclerViewChildAt(int index);

    /** see {@link RecyclerView#getScrollState()} */
    int getScrollState();

    /** see {@link OrientationHelper#getStartAfterPadding()} */
    int getStartAfterPadding();

    /** see {@link View#getTag()} */
    Object getTag();

    /** see {@link OrientationHelper#getTotalSpace()} */
    int getTotalSpace();

    /**
     * Returns a view that will be attached to the view hierarchy.
     */
    @NonNull
    View getView();

    /** see {@link View#getViewTreeObserver()} */
    ViewTreeObserver getViewTreeObserver();

    /**
     * see {@link RecyclerView#invalidateItemDecorations()}
     * @deprecated this will fail value when there is a oem implementation
     */
    @Deprecated
    void invalidateItemDecorations();

    /** see {@link View#post(Runnable)} */
    boolean post(Runnable runnable);

    /**
     * see {@link RecyclerView#removeItemDecoration(ItemDecoration)}
     * @deprecated this will fail value when there is a oem implementation
     */
    @Deprecated
    void removeItemDecoration(@NonNull ItemDecoration decor);

    /**
     * see {@link RecyclerView#removeItemDecorationAt(int)}
     * @deprecated this will fail value when there is a oem implementation
     */
    @Deprecated
    void removeItemDecorationAt(int index);

    /** see {@link RecyclerView#removeOnChildAttachStateChangeListener} */
    void removeOnChildAttachStateChangeListener(OnChildAttachStateChangeListener listener);

    /** see {@link View#removeOnLayoutChangeListener(OnLayoutChangeListener)} */
    void removeOnLayoutChangeListener(OnLayoutChangeListener listener);

    /** see {@link View#requestLayout()} */
    void requestLayout();

    /** see {@link RecyclerView#removeOnScrollListener(OnScrollListener)} */
    void removeOnScrollListener(OnScrollListener scrollListener);

    /** see {@link View#requireViewById(int)} */
    <T extends View> T requireViewById(int id);

    /** see {@link View#scrollBy(int, int)} */
    void scrollBy(int x, int y);

    /** see {@link RecyclerView#scrollToPosition(int)} */
    void scrollToPosition(int position);

    /** see {@link RecyclerView#setAdapter(Adapter)} */
    void setAdapter(Adapter<?> adapter);

    /** see {@link View#setAlpha(float)} */
    void setAlpha(float alpha);

    /** see {@link RecyclerView#setClipToPadding(boolean)} */
    void setClipToPadding(boolean enabled);

    /** see {@link View#setContentDescription(CharSequence)} */
    void setContentDescription(CharSequence contentDescription);

    /** see {@link RecyclerView#setHasFixedSize(boolean)} */
    void setHasFixedSize(boolean hasFixedSize);

    /**
     * see {@link View#setFadingEdgeLength(int)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    void setFadingEdgeLength(int length);

    /** see {@link RecyclerView#setOnFlingListener(OnFlingListener)} */
    void setOnFlingListener(OnFlingListener listener);

    /** see {@link View#setId(int)}  */
    void setId(@IdRes int id);

    /**
     * see {@link RecyclerView#setItemAnimator(ItemAnimator)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    void setItemAnimator(ItemAnimator itemAnimator);

    /**
     * see {@link RecyclerView#setLayoutManager(LayoutManager)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    default void setLayoutManager(@Nullable LayoutManager layoutManager) {}

    /** Use this instead of {@link #setLayoutManager} */
    void setLayoutStyle(@Nullable CarUiLayoutStyle layoutStyle);

    /** see {@link View#setOnTouchListener(OnTouchListener)} */
    void setOnTouchListener(OnTouchListener listener);

    /** see {@link View#setPadding(int, int, int, int)} */
    void setPadding(int left, int top, int right, int bottom);

    /** see {@link View#setPaddingRelative(int, int, int, int)} */
    void setPaddingRelative(int start, int top, int end, int bottom);

    /** Will get called when {@link SpanSizeLookup} changes at runtime. */
    void setSpanSizeLookup(@NonNull SpanSizeLookup spanSizeLookup);

    /** see {@link View#setTag(Object)} */
    void setTag(Object tag);

    /**
     * see {@link View#setVerticalFadingEdgeEnabled(boolean)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    void setVerticalFadingEdgeEnabled(boolean enabled);

    /**
     * see {@link View#setVerticalScrollBarEnabled(boolean)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    void setVerticalScrollBarEnabled(boolean enabled);

    /**
     * see {@link View#setVerticalScrollbarPosition(int)}
     * @deprecated this will fail when there is a oem implementation
     */
    @Deprecated
    void setVerticalScrollbarPosition(int position);

    /** see {@link View#setVisibility(int)} */
    void setVisibility(int visible);

    /** see {@link RecyclerView#smoothScrollBy(int, int)} */
    void smoothScrollBy(int dx, int dy);

    /** see {@link RecyclerView#smoothScrollToPosition(int) */
    void smoothScrollToPosition(int position);
}
