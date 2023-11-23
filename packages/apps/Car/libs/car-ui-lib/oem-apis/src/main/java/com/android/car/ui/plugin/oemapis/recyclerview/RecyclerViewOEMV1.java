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
package com.android.car.ui.plugin.oemapis.recyclerview;

import android.view.View;

/**
 * {@link androidx.recyclerview.widget.RecyclerView}
 */
public interface RecyclerViewOEMV1 {

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

    /** {@link RecyclerView#setAdapter(Adapter)} */
    <V extends ViewHolderOEMV1> void setAdapter(AdapterOEMV1<V> adapter);

    /** {@link RecyclerView#addOnScrollListener} */
    void addOnScrollListener(OnScrollListenerOEMV1 listener);

    /** {@link RecyclerView#removeOnScrollListener} */
    void removeOnScrollListener(OnScrollListenerOEMV1 listener);

    /** {@link RecyclerView#clearOnScrollListeners()} */
    void clearOnScrollListeners();

    /** {@link RecyclerView#scrollToPosition(int)} */
    void scrollToPosition(int position);

    /** {@link RecyclerView#smoothScrollBy(int, int)} */
    void smoothScrollBy(int dx, int dy);

    /** {@link RecyclerView#smoothScrollToPosition(int)} */
    void smoothScrollToPosition(int position);

    /** {@link RecyclerView#setHasFixedSize(boolean)} */
    void setHasFixedSize(boolean hasFixedSize);

    /** {@link RecyclerView#hasFixedSize()} */
    boolean hasFixedSize();

    /**
     * set {@link LayoutStyleOEMV1}. This is the replacement for
     * {@link androidx.recyclerview.widget.RecyclerView.LayoutManager}
     */
    void setLayoutStyle(LayoutStyleOEMV1 layoutStyle);

    /**
     * set {@link LayoutStyleOEMV1}. This is the replacement for
     * {@link androidx.recyclerview.widget.RecyclerView.LayoutManager}
     */
    LayoutStyleOEMV1 getLayoutStyle();

    /**
     * Returns the view that will be displayed on the screen.
     */
    View getView();

    /** {@link android.view.View#setPadding(int, int, int, int)} */
    void setPadding(int left, int top, int right, int bottom);

    /** {@link android.view.View#setPaddingRelative(int, int, int, int)} */
    void setPaddingRelative(int start, int top, int end, int bottom);

    /** {@link androidx.recyclerview.widget.RecyclerView#setClipToPadding(boolean)} */
    void setClipToPadding(boolean clipToPadding);

    /** see {@link LinearLayoutManager#findFirstCompletelyVisibleItemPosition()} */
    int findFirstCompletelyVisibleItemPosition();

    /** see {@link LinearLayoutManager#findFirstVisibleItemPosition()} */
    int findFirstVisibleItemPosition();

    /** see {@link LinearLayoutManager#findLastCompletelyVisibleItemPosition()} */
    int findLastCompletelyVisibleItemPosition();

    /** see {@link LinearLayoutManager#findLastVisibleItemPosition()} */
    int findLastVisibleItemPosition();

    /** see {@link RecyclerView#getScrollState()} */
    int getScrollState();

    /** see {@link View#setContentDescription(CharSequence)} */
    void setContentDescription(CharSequence contentDescription);

    /** see {@link View#setAlpha(float)} */
    void setAlpha(float alpha);

    /** see {@link OrientationHelper#getEndAfterPadding()} */
    int getEndAfterPadding();

    /** see {@link OrientationHelper#getStartAfterPadding()} */
    int getStartAfterPadding();

    /** see {@link OrientationHelper#getTotalSpace()} */
    int getTotalSpace();

    /**
     * see {@link LayoutManager#getChildCount()}
     * Prefer this method over {@link View#getChildCount()}
     */
    int getRecyclerViewChildCount();

    /**
     * see {@link LayoutManager#getChildAt(int)}
     * Prefer this method over {@link View#getChildAt(int)}
     */
    View getRecyclerViewChildAt(int index);

    /**
     * see {@link RecyclerView#findViewHolderForAdapterPosition(int)}
     */
    ViewHolderOEMV1 findViewHolderForAdapterPosition(int position);

    /**
     * see {@link RecyclerView#findViewHolderForLayoutPosition(int)}
     */
    ViewHolderOEMV1 findViewHolderForLayoutPosition(int position);

    /** {@link RecyclerView#addOnChildAttachStateChangeListener} */
    void addOnChildAttachStateChangeListener(OnChildAttachStateChangeListenerOEMV1 listener);

    /** {@link RecyclerView#removeOnChildAttachStateChangeListener} */
    void removeOnChildAttachStateChangeListener(OnChildAttachStateChangeListenerOEMV1 listener);

    /** {@link RecyclerView#clearOnChildAttachStateChangeListener()} */
    void clearOnChildAttachStateChangeListener();

    /** {@link RecyclerView#getChildLayoutPosition} */
    int getChildLayoutPosition(View child);
}
