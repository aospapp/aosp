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
package com.chassis.car.ui.plugin.recyclerview;

import static androidx.recyclerview.widget.RecyclerView.VERTICAL;

import static com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewAttributesOEMV1.SIZE_LARGE;
import static com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewAttributesOEMV1.SIZE_MEDIUM;
import static com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewAttributesOEMV1.SIZE_SMALL;

import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.view.InputDevice;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;

import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.GridLayoutManager.SpanSizeLookup;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.Adapter;
import androidx.recyclerview.widget.RecyclerView.OnChildAttachStateChangeListener;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.android.car.ui.plugin.oemapis.recyclerview.AdapterOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.LayoutStyleOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.OnChildAttachStateChangeListenerOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.OnScrollListenerOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewAttributesOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.RecyclerViewOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ViewHolderOEMV1;

import com.chassis.car.ui.plugin.R;
import com.chassis.car.ui.plugin.recyclerview.AdapterWrapper.ViewHolderWrapper;
import com.chassis.car.ui.plugin.uxr.CarUxRestrictionsUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Reference OEM implementation for RecyclerView
 */
public final class RecyclerViewImpl extends FrameLayout implements RecyclerViewOEMV1 {

    /**
     * {@link com.android.car.ui.utils.RotaryConstants#ROTARY_CONTAINER}
     */
    private static final String ROTARY_CONTAINER =
            "com.android.car.ui.utils.ROTARY_CONTAINER";
    /**
     * {@link com.android.car.ui.utils.RotaryConstants#ROTARY_HORIZONTALLY_SCROLLABLE}
     */
    private static final String ROTARY_HORIZONTALLY_SCROLLABLE =
            "com.android.car.ui.utils.HORIZONTALLY_SCROLLABLE";
    /**
     * {@link com.android.car.ui.utils.RotaryConstants#ROTARY_VERTICALLY_SCROLLABLE}
     */
    private static final String ROTARY_VERTICALLY_SCROLLABLE =
            "com.android.car.ui.utils.VERTICALLY_SCROLLABLE";

    @NonNull
    private final RecyclerView mRecyclerView;

    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mListener =
            new UxRestrictionChangedListener();
    @NonNull
    private final CarUxRestrictionsUtil mCarUxRestrictionsUtil;

    @Nullable
    private final DefaultScrollBar mScrollBar;

    @NonNull
    private final List<OnScrollListenerOEMV1> mScrollListeners = new ArrayList<>();

    @NonNull
    private final RecyclerView.OnScrollListener mOnScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    for (OnScrollListenerOEMV1 listener : mScrollListeners) {
                        listener.onScrolled(RecyclerViewImpl.this, dx, dy);
                    }
                }

                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    for (OnScrollListenerOEMV1 listener : mScrollListeners) {
                        listener.onScrollStateChanged(RecyclerViewImpl.this,
                                toInternalScrollState(newState));
                    }
                }
            };

    @NonNull
    private final List<OnChildAttachStateChangeListenerOEMV1> mOnChildAttachStateChangeListeners =
            new ArrayList<>();

    @NonNull
    private final RecyclerView.OnChildAttachStateChangeListener mOnChildAttachStateChangeListener =
            new OnChildAttachStateChangeListener() {
                @Override
                public void onChildViewAttachedToWindow(@NonNull View view) {
                    for (OnChildAttachStateChangeListenerOEMV1 listener :
                            mOnChildAttachStateChangeListeners) {
                        listener.onChildViewAttachedToWindow(view);
                    }
                }

                @Override
                public void onChildViewDetachedFromWindow(@NonNull View view) {
                    for (OnChildAttachStateChangeListenerOEMV1 listener :
                            mOnChildAttachStateChangeListeners) {
                        listener.onChildViewDetachedFromWindow(view);
                    }
                }
            };

    @Nullable
    private LayoutStyleOEMV1 mLayoutStyle;

    public RecyclerViewImpl(@NonNull Context context) {
        this(context, null);
    }

    public RecyclerViewImpl(@NonNull Context context,
            @Nullable RecyclerViewAttributesOEMV1 attrs) {
        super(context);
        boolean scrollBarEnabled = context.getResources().getBoolean(R.bool.scrollbar_enable);
        @LayoutRes int layout = R.layout.recycler_view_no_scrollbar;
        if (scrollBarEnabled) {
            int size = attrs != null ? attrs.getSize() : SIZE_LARGE;
            switch (size) {
                case SIZE_SMALL:
                    layout = R.layout.recycler_view_small;
                    break;
                case SIZE_MEDIUM:
                    layout = R.layout.recycler_view_medium;
                    break;
                case SIZE_LARGE:
                    layout = R.layout.recycler_view;
            }
        }

        LayoutInflater factory = LayoutInflater.from(context);
        View rootView = factory.inflate(layout, this, true);
        mRecyclerView = rootView.requireViewById(R.id.recycler_view);

        // Set to false so the items below the toolbar are visible.
        mRecyclerView.setClipToPadding(false);

        mCarUxRestrictionsUtil = CarUxRestrictionsUtil.getInstance(context);

        if (attrs != null) {
            setLayoutStyle(attrs.getLayoutStyle());
            setBackground(attrs.getBackground());
            setPadding(attrs.getPaddingLeft(), attrs.getPaddingTop(), attrs.getPaddingRight(),
                    attrs.getPaddingBottom());
            setMinimumHeight(attrs.getMinHeight());
            setMinimumWidth(attrs.geMinWidth());

            LayoutParams params = new LayoutParams(attrs.getLayoutWidth(), attrs.getLayoutHeight());
            params.setMargins(attrs.getMarginLeft(), attrs.getMarginTop(), attrs.getMarginRight(),
                    attrs.getMarginBottom());
            setLayoutParams(params);

            mLayoutStyle = attrs.getLayoutStyle();
        } else {
            mLayoutStyle = new LayoutStyleOEMV1() {
                @Override
                public int getSpanCount() {
                    return 1;
                }

                @Override
                public int getLayoutType() {
                    return LayoutStyleOEMV1.LAYOUT_TYPE_LINEAR;
                }

                @Override
                public int getOrientation() {
                    return LayoutStyleOEMV1.ORIENTATION_VERTICAL;
                }

                @Override
                public boolean getReverseLayout() {
                    return false;
                }
            };
        }

        setLayoutStyle(mLayoutStyle);

        boolean rotaryScrollEnabled = attrs != null && attrs.isRotaryScrollEnabled();
        initRotaryScroll(mRecyclerView, rotaryScrollEnabled, getLayoutStyle().getOrientation());

        if (!scrollBarEnabled) {
            mScrollBar = null;
            return;
        }

        mRecyclerView.setVerticalScrollBarEnabled(false);
        mRecyclerView.setHorizontalScrollBarEnabled(false);

        mScrollBar = new DefaultScrollBar();
        mScrollBar.initialize(context, mRecyclerView, rootView.requireViewById(R.id.scroll_bar));
    }

    @Override
    public <V extends ViewHolderOEMV1> void setAdapter(AdapterOEMV1<V> adapterV1) {
        if (adapterV1 == null) {
            mRecyclerView.setAdapter(null);
        } else {
            mRecyclerView.setAdapter(new AdapterWrapper(adapterV1));
        }
    }

    @Override
    public void addOnScrollListener(OnScrollListenerOEMV1 listener) {
        if (listener == null) {
            return;
        }
        if (mScrollListeners.isEmpty()) {
            mRecyclerView.addOnScrollListener(mOnScrollListener);
        }
        mScrollListeners.add(listener);
    }

    @Override
    public void removeOnScrollListener(OnScrollListenerOEMV1 listener) {
        if (listener == null) {
            return;
        }
        mScrollListeners.remove(listener);
        if (mScrollListeners.isEmpty()) {
            mRecyclerView.removeOnScrollListener(mOnScrollListener);
        }
    }

    @Override
    public void clearOnScrollListeners() {
        if (!mScrollListeners.isEmpty()) {
            mScrollListeners.clear();
            mRecyclerView.clearOnScrollListeners();
        }
    }

    @Override
    public void scrollToPosition(int position) {
        mRecyclerView.scrollToPosition(position);
    }

    @Override
    public void smoothScrollBy(int dx, int dy) {
        mRecyclerView.smoothScrollBy(dx, dy);
    }

    @Override
    public void smoothScrollToPosition(int position) {
        mRecyclerView.smoothScrollToPosition(position);
    }

    @Override
    public void setHasFixedSize(boolean hasFixedSize) {
        mRecyclerView.setHasFixedSize(hasFixedSize);
    }

    @Override
    public boolean hasFixedSize() {
        return mRecyclerView.hasFixedSize();
    }

    @Override
    public void setLayoutStyle(@Nullable LayoutStyleOEMV1 layoutStyle) {
        mLayoutStyle = layoutStyle;

        int orientation = layoutStyle == null ? VERTICAL : layoutStyle.getOrientation();
        boolean reverseLayout = layoutStyle != null && layoutStyle.getReverseLayout();

        if (layoutStyle == null
                || layoutStyle.getLayoutType() == LayoutStyleOEMV1.LAYOUT_TYPE_LINEAR) {
            mRecyclerView.setLayoutManager(new LinearLayoutManager(getContext(),
                    orientation,
                    reverseLayout));
        } else {
            GridLayoutManager glm = new GridLayoutManager(getContext(),
                    layoutStyle.getSpanCount(),
                    orientation,
                    reverseLayout);
            glm.setSpanSizeLookup(new SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    return layoutStyle.getSpanSize(position);
                }
            });
            mRecyclerView.setLayoutManager(glm);
        }
    }

    @Override
    public LayoutStyleOEMV1 getLayoutStyle() {
        return mLayoutStyle;
    }

    public View getView() {
        return this;
    }

    @Override
    public int findFirstCompletelyVisibleItemPosition() {
        RecyclerView.LayoutManager layoutManager = mRecyclerView.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            return ((LinearLayoutManager) layoutManager)
                    .findFirstCompletelyVisibleItemPosition();
        }
        return 0;
    }

    @Override
    public int findFirstVisibleItemPosition() {
        RecyclerView.LayoutManager layoutManager = mRecyclerView.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            return ((LinearLayoutManager) layoutManager)
                    .findFirstVisibleItemPosition();
        }
        return 0;
    }

    @Override
    public int findLastCompletelyVisibleItemPosition() {
        RecyclerView.LayoutManager layoutManager = mRecyclerView.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            return ((LinearLayoutManager) layoutManager)
                    .findLastCompletelyVisibleItemPosition();
        }
        return 0;
    }

    @Override
    public int findLastVisibleItemPosition() {
        RecyclerView.LayoutManager layoutManager = mRecyclerView.getLayoutManager();
        if (layoutManager instanceof LinearLayoutManager) {
            return ((LinearLayoutManager) layoutManager)
                    .findLastVisibleItemPosition();
        }
        return 0;
    }

    private static int toInternalScrollState(int state) {
        /* default to RecyclerView.SCROLL_STATE_IDLE */
        int internalState = RecyclerViewOEMV1.SCROLL_STATE_IDLE;
        switch (state) {
            case RecyclerView.SCROLL_STATE_DRAGGING:
                internalState = RecyclerViewOEMV1.SCROLL_STATE_DRAGGING;
                break;
            case RecyclerView.SCROLL_STATE_SETTLING:
                internalState = RecyclerViewOEMV1.SCROLL_STATE_SETTLING;
                break;
        }
        return internalState;
    }

    @Override
    public int getScrollState() {
        return toInternalScrollState(mRecyclerView.getScrollState());
    }

    @Override
    public void setContentDescription(CharSequence contentDescription) {
        boolean rotaryScrollEnabled = contentDescription != null
                && (ROTARY_HORIZONTALLY_SCROLLABLE.contentEquals(contentDescription)
                || ROTARY_VERTICALLY_SCROLLABLE.contentEquals(contentDescription));
        int orientation = getLayoutStyle() == null ? LinearLayout.VERTICAL
                : getLayoutStyle().getOrientation();
        initRotaryScroll(mRecyclerView, rotaryScrollEnabled, orientation);
        // Only change this view's content description when not related to rotary scroll. Don't
        // change its content description when related to rotary scroll, because the content
        // description should be set on its inner recyclerview in this case.
        if (!rotaryScrollEnabled) {
            super.setContentDescription(contentDescription);
        }
    }

    private OrientationHelper createOrientationHelper() {
        if (mLayoutStyle.getOrientation() == LayoutStyleOEMV1.ORIENTATION_VERTICAL) {
            return OrientationHelper.createVerticalHelper(mRecyclerView.getLayoutManager());
        } else {
            return OrientationHelper.createHorizontalHelper(mRecyclerView.getLayoutManager());
        }
    }

    @Override
    public int getEndAfterPadding() {
        if (mLayoutStyle == null) return 0;
        return createOrientationHelper().getEndAfterPadding();
    }

    @Override
    public int getStartAfterPadding() {
        if (mLayoutStyle == null) return 0;
        return createOrientationHelper().getStartAfterPadding();
    }

    @Override
    public int getTotalSpace() {
        if (mLayoutStyle == null) return 0;
        return createOrientationHelper().getTotalSpace();
    }

    @Override
    public void setPadding(int left, int top, int right, int bottom) {
        if (mScrollBar != null) {
            int currentPosition = findFirstVisibleItemPosition();
            setScrollBarPadding(top, bottom);
            // Maintain same index position after setting padding
            scrollToPosition(currentPosition);
        }
        mRecyclerView.setPadding(mRecyclerView.getPaddingLeft(),
                top, mRecyclerView.getPaddingRight(), bottom);
        super.setPadding(left, 0, right, 0);
    }

    @Override
    public int getPaddingTop() {
        return mRecyclerView.getPaddingTop();
    }

    @Override
    public int getPaddingBottom() {
        return mRecyclerView.getPaddingBottom();
    }

    @Override
    public void setPaddingRelative(int start, int top, int end, int bottom) {
        if (mScrollBar != null) {
            int currentPosition = findFirstVisibleItemPosition();
            setScrollBarPadding(top, bottom);
            // Maintain same index position after setting padding
            scrollToPosition(currentPosition);
        }
        mRecyclerView.setPaddingRelative(0, top, 0, bottom);
        super.setPaddingRelative(start, 0, end, 0);
    }

    private void setScrollBarPadding(int paddingTop, int paddingBottom) {
        if (mScrollBar != null) {
            mScrollBar.setPadding(paddingTop, paddingBottom);
        }
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mCarUxRestrictionsUtil.register(mListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        mCarUxRestrictionsUtil.unregister(mListener);
    }

    @Override
    public int getRecyclerViewChildCount() {
        if (mRecyclerView.getLayoutManager() != null) {
            return mRecyclerView.getLayoutManager().getChildCount();
        } else {
            return 0;
        }
    }

    @Override
    public View getRecyclerViewChildAt(int index) {
        if (mRecyclerView.getLayoutManager() != null) {
            return mRecyclerView.getLayoutManager().getChildAt(index);
        } else {
            return null;
        }
    }

    @Override
    public ViewHolderOEMV1 findViewHolderForAdapterPosition(int position) {
        ViewHolder viewHolder = mRecyclerView.findViewHolderForAdapterPosition(position);
        if (viewHolder instanceof ViewHolderWrapper) {
            return ((ViewHolderWrapper) viewHolder).getViewHolder();
        }
        return null;
    }

    @Override
    public ViewHolderOEMV1 findViewHolderForLayoutPosition(int position) {
        ViewHolder viewHolder = mRecyclerView.findViewHolderForLayoutPosition(position);
        if (viewHolder instanceof ViewHolderWrapper) {
            return ((ViewHolderWrapper) viewHolder).getViewHolder();
        }
        return null;
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        return mRecyclerView.canScrollHorizontally(direction);
    }

    @Override
    public boolean canScrollVertically(int direction) {
        return mRecyclerView.canScrollVertically(direction);
    }

    @Override
    public void addOnChildAttachStateChangeListener(
            OnChildAttachStateChangeListenerOEMV1 listener) {
        if (listener == null) {
            return;
        }
        if (mOnChildAttachStateChangeListeners.isEmpty()) {
            mRecyclerView.addOnChildAttachStateChangeListener(mOnChildAttachStateChangeListener);
        }
        mOnChildAttachStateChangeListeners.add(listener);
    }

    @Override
    public void removeOnChildAttachStateChangeListener(
            OnChildAttachStateChangeListenerOEMV1 listener) {
        if (listener == null) {
            return;
        }
        mOnChildAttachStateChangeListeners.remove(listener);
        if (mOnChildAttachStateChangeListeners.isEmpty()) {
            mRecyclerView.removeOnChildAttachStateChangeListener(mOnChildAttachStateChangeListener);
        }
    }

    @Override
    public void clearOnChildAttachStateChangeListener() {
        if (!mOnChildAttachStateChangeListeners.isEmpty()) {
            mOnChildAttachStateChangeListeners.clear();
            mRecyclerView.clearOnChildAttachStateChangeListeners();
        }
    }

    @Override
    public int getChildLayoutPosition(View child) {
        return mRecyclerView.getChildLayoutPosition(child);
    }

    /**
     * If this view's {@code rotaryScrollEnabled} attribute is set to true, sets the content
     * description so that the {@code RotaryService} will treat it as a scrollable container and
     * initializes this view accordingly.
     */
    private void initRotaryScroll(@NonNull ViewGroup recyclerView,
            boolean rotaryScrollEnabled,
            int orientation) {
        if (rotaryScrollEnabled) {
            setRotaryScrollEnabled(
                    recyclerView, /* isVertical= */ orientation == LinearLayout.VERTICAL);
        }

        // If rotary scrolling is enabled, set a generic motion event listener to convert
        // SOURCE_ROTARY_ENCODER scroll events into SOURCE_MOUSE scroll events that RecyclerView
        // knows how to handle.
        recyclerView.setOnGenericMotionListener(rotaryScrollEnabled ? (v, event) -> {
            if (event.getAction() == MotionEvent.ACTION_SCROLL) {
                if (event.getSource() == InputDevice.SOURCE_ROTARY_ENCODER) {
                    MotionEvent mouseEvent = MotionEvent.obtain(event);
                    mouseEvent.setSource(InputDevice.SOURCE_MOUSE);
                    recyclerView.onGenericMotionEvent(mouseEvent);
                    return true;
                }
            }
            return false;
        } : null);

        // If rotary scrolling is enabled, mark this view as focusable. This view will be focused
        // when no focusable elements are visible.
        recyclerView.setFocusable(rotaryScrollEnabled);

        // Focus this view before descendants so that the RotaryService can focus this view when it
        // wants to.
        recyclerView.setDescendantFocusability(ViewGroup.FOCUS_BEFORE_DESCENDANTS);

        // Disable the default focus highlight. No highlight should appear when this view is
        // focused.
        recyclerView.setDefaultFocusHighlightEnabled(false);

        // If rotary scrolling is enabled, set a focus change listener to highlight the scrollbar
        // thumb when this recycler view is focused, i.e. when no focusable descendant is visible.
        recyclerView.setOnFocusChangeListener(rotaryScrollEnabled ? (v, hasFocus) -> {
            if (mScrollBar != null) mScrollBar.setHighlightThumb(hasFocus);
        } : null);

        // This recyclerView is a rotary container if it's not a scrollable container.
        if (!rotaryScrollEnabled) {
            recyclerView.setContentDescription(ROTARY_CONTAINER);
        }
    }

    private static void setRotaryScrollEnabled(@NonNull View view, boolean isVertical) {
        view.setContentDescription(
                isVertical ? ROTARY_VERTICALLY_SCROLLABLE : ROTARY_HORIZONTALLY_SCROLLABLE);
    }

    private class UxRestrictionChangedListener implements
            CarUxRestrictionsUtil.OnUxRestrictionsChangedListener {

        @Override
        public void onRestrictionsChanged(@NonNull CarUxRestrictions carUxRestrictions) {
            Adapter<?> adapter = mRecyclerView.getAdapter();
            if (adapter == null) return;

            int maxItems = AdapterOEMV1.UNLIMITED;
            if ((carUxRestrictions.getActiveRestrictions()
                    & CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT) != 0) {
                maxItems = carUxRestrictions.getMaxCumulativeContentItems();
            }

            int originalCount = adapter.getItemCount();
            ((AdapterWrapper) adapter).getOEMAdapter().setMaxItems(maxItems);
            int newCount = adapter.getItemCount();

            if (newCount == originalCount) {
                return;
            }

            if (newCount < originalCount) {
                adapter.notifyItemRangeRemoved(newCount, originalCount - newCount);
            } else {
                adapter.notifyItemRangeInserted(originalCount, newCount - originalCount);
            }
        }
    }
}
