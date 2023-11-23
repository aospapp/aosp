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
package com.android.car.ui.recyclerview;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;
import static com.android.car.ui.utils.CarUiUtils.requireViewByRefId;
import static com.android.car.ui.utils.RotaryConstants.ROTARY_CONTAINER;
import static com.android.car.ui.utils.RotaryConstants.ROTARY_HORIZONTALLY_SCROLLABLE;
import static com.android.car.ui.utils.RotaryConstants.ROTARY_VERTICALLY_SCROLLABLE;
import static com.android.car.ui.utils.ViewUtils.LazyLayoutView;
import static com.android.car.ui.utils.ViewUtils.setRotaryScrollEnabled;

import android.annotation.SuppressLint;
import android.car.drivingstate.CarUxRestrictions;
import android.content.Context;
import android.content.res.TypedArray;
import android.text.TextUtils;
import android.util.AttributeSet;
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
import androidx.annotation.RequiresApi;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.GridLayoutManager;
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
import androidx.recyclerview.widget.RecyclerView.ViewHolder;

import com.android.car.ui.R;
import com.android.car.ui.preference.PreferenceFragment.AndroidxRecyclerViewProvider;
import com.android.car.ui.recyclerview.decorations.grid.GridDividerItemDecoration;
import com.android.car.ui.recyclerview.decorations.linear.LinearDividerItemDecoration;
import com.android.car.ui.utils.CarUxRestrictionsUtil;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * View that extends a {@link RecyclerView} and wraps itself into a {@link LinearLayout} which could
 * potentially include a scrollbar that has page up and down arrows. Interaction with this view is
 * similar to a {@code RecyclerView} as it takes the same adapter and the layout manager.
 */
@SuppressLint("CustomViewStyleable")
@RequiresApi(MIN_TARGET_API)
public final class CarUiRecyclerViewImpl extends FrameLayout
        implements CarUiRecyclerView, LazyLayoutView, AndroidxRecyclerViewProvider {

    private static final String TAG = "CarUiRecyclerView";
    /**
     * exact copy of {@link Recyclerview#LAYOUT_MANAGER_CONSTRUCTOR_SIGNATURE}
     */
    private static final Class<?>[] LAYOUT_MANAGER_CONSTRUCTOR_SIGNATURE =
            new Class<?>[]{Context.class, AttributeSet.class, int.class, int.class};

    @Nullable
    private Adapter<?> mAdapter;
    @NonNull
    private RecyclerView mRecyclerView;

    private final CarUxRestrictionsUtil.OnUxRestrictionsChangedListener mListener =
            new UxRestrictionChangedListener();

    @NonNull
    private final CarUxRestrictionsUtil mCarUxRestrictionsUtil;
    private boolean mScrollBarEnabled;
    @Nullable
    private String mScrollBarClass;
    private int mScrollBarPaddingTop;
    private int mScrollBarPaddingBottom;
    @Nullable
    private ScrollBar mScrollBar;
    @Nullable
    private GridDividerItemDecoration mDividerItemDecorationGrid;
    @Nullable
    private ItemDecoration mDividerItemDecorationLinear;
    private int mNumOfColumns;
    @Size
    private int mSize;

    // Set to true when when styled attributes are read and initialized.
    private boolean mIsInitialized;
    private boolean mEnableDividers;

    @NonNull
    private final Set<Runnable> mOnLayoutCompletedListeners = new HashSet<>();

    @Nullable
    private CarUiLayoutStyle mLayoutStyle;

    @NonNull
    private final List<OnScrollListener> mScrollListeners = new ArrayList<>();

    @NonNull
    private final RecyclerView.OnScrollListener mOnScrollListener =
            new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView, int newState) {
                    for (OnScrollListener listener : mScrollListeners) {
                        listener.onScrollStateChanged(CarUiRecyclerViewImpl.this,
                                toInternalScrollState(newState));
                    }
                }

                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx, int dy) {
                    for (OnScrollListener listener : mScrollListeners) {
                        listener.onScrolled(CarUiRecyclerViewImpl.this, dx, dy);
                    }
                }
            };

    public CarUiRecyclerViewImpl(@NonNull Context context) {
        this(context, null);
    }

    public CarUiRecyclerViewImpl(@NonNull Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public CarUiRecyclerViewImpl(@NonNull Context context, @Nullable AttributeSet attrs,
            int defStyle) {
        super(context, attrs, defStyle);
        mCarUxRestrictionsUtil = CarUxRestrictionsUtil.getInstance(context);
        init(context, attrs, defStyle);
    }

    @Override
    public boolean canScrollHorizontally(int direction) {
        return mRecyclerView.canScrollHorizontally(direction);
    }

    @Override
    public boolean canScrollVertically(int direction) {
        return mRecyclerView.canScrollVertically(direction);
    }

    private void init(Context context, AttributeSet attrs, int defStyleAttr) {
        TypedArray a = context.obtainStyledAttributes(
                attrs,
                R.styleable.CarUiRecyclerView,
                defStyleAttr,
                0);

        mScrollBarEnabled = context.getResources().getBoolean(R.bool.car_ui_scrollbar_enable);
        @LayoutRes int layout = R.layout.car_ui_recycler_view_no_scrollbar;

        mSize = a.getInt(R.styleable.CarUiRecyclerView_carUiSize, SIZE_LARGE);
        if (mScrollBarEnabled) {
            switch (mSize) {
                case SIZE_SMALL:
                    layout = R.layout.car_ui_recycler_view_small;
                    break;
                case SIZE_MEDIUM:
                    layout = R.layout.car_ui_recycler_view_medium;
                    break;
                case SIZE_LARGE:
                    layout = R.layout.car_ui_recycler_view;
            }
        }

        LayoutInflater factory = LayoutInflater.from(context);
        View rootView = factory.inflate(layout, this, true);
        ViewGroup recyclerViewContainer = requireViewByRefId(rootView, R.id.car_ui_recycler_view);
        if (recyclerViewContainer instanceof CarUiRecyclerViewContainer) {
            // To keep backwards compatibility CarUiRecyclerViewContainer is a FrameLayout
            // that has a RecyclerView at index 0
            mRecyclerView = (RecyclerView) recyclerViewContainer.getChildAt(0);
        } else {
            mRecyclerView = (RecyclerView) recyclerViewContainer;
        }

        boolean rotaryScrollEnabled = a.getBoolean(
                R.styleable.CarUiRecyclerView_rotaryScrollEnabled, /* defValue=*/ false);
        int orientation = a.getInt(R.styleable.CarUiRecyclerView_android_orientation,
                LinearLayout.VERTICAL);
        initRotaryScroll(mRecyclerView, rotaryScrollEnabled, orientation);

        mScrollBarPaddingTop = context.getResources()
                .getDimensionPixelSize(R.dimen.car_ui_scrollbar_padding_top);
        mScrollBarPaddingBottom = context.getResources()
                .getDimensionPixelSize(R.dimen.car_ui_scrollbar_padding_bottom);

        @CarUiRecyclerViewLayout int carUiRecyclerViewLayout =
                a.getInt(R.styleable.CarUiRecyclerView_layoutStyle, CarUiRecyclerViewLayout.LINEAR);
        mNumOfColumns = a.getInt(R.styleable.CarUiRecyclerView_numOfColumns, /* defValue= */ 2);
        mEnableDividers =
                a.getBoolean(R.styleable.CarUiRecyclerView_enableDivider, /* defValue= */ false);

        mDividerItemDecorationLinear = new LinearDividerItemDecoration(
                ContextCompat.getDrawable(context, R.drawable.car_ui_recyclerview_divider));

        mDividerItemDecorationGrid =
                new GridDividerItemDecoration(
                        ContextCompat.getDrawable(context, R.drawable.car_ui_divider),
                        ContextCompat.getDrawable(context, R.drawable.car_ui_divider),
                        mNumOfColumns);

        mIsInitialized = true;

        // Set to false so the items below the toolbar are visible.
        mRecyclerView.setClipToPadding(false);
        // Check if a layout manager has already been set via XML
        String layoutManagerInXml = a.getString(R.styleable.CarUiRecyclerView_layoutManager);
        if (!TextUtils.isEmpty(layoutManagerInXml)) {
            createLayoutManager(context, layoutManagerInXml, attrs, defStyleAttr, 0);
        } else if (carUiRecyclerViewLayout == CarUiRecyclerViewLayout.GRID) {
            setLayoutManager(new GridLayoutManager(getContext(), mNumOfColumns));
        } else {
            // carUiRecyclerViewLayout == CarUiRecyclerViewLayout.LINEAR
            // Also the default case
            setLayoutManager(new LinearLayoutManager(getContext()));
        }

        a.recycle();

        if (!mScrollBarEnabled) {
            return;
        }

        mRecyclerView.setVerticalScrollBarEnabled(false);
        mRecyclerView.setHorizontalScrollBarEnabled(false);

        mScrollBarClass = context.getResources().getString(R.string.car_ui_scrollbar_component);
        createScrollBarFromConfig(context, requireViewByRefId(rootView, R.id.car_ui_scroll_bar));
    }

    @Override
    public void setLayoutManager(@Nullable LayoutManager layoutManager) {
        if (layoutManager instanceof GridLayoutManager) {
            setLayoutStyle(CarUiGridLayoutStyle.from(layoutManager));
        } else {
            setLayoutStyle(CarUiLinearLayoutStyle.from(layoutManager));
        }
    }

    @Nullable
    @Override
    public LayoutManager getLayoutManager() {
        return mRecyclerView.getLayoutManager();
    }

    @Override
    public CarUiLayoutStyle getLayoutStyle() {
        return mLayoutStyle;
    }

    @Override
    public boolean hasFixedSize() {
        return false;
    }

    @Override
    public void setLayoutStyle(CarUiLayoutStyle layoutStyle) {
        mLayoutStyle = layoutStyle;
        if (layoutStyle == null) {
            mRecyclerView.setLayoutManager(null);
            return;
        }

        LayoutManager layoutManager;
        if (layoutStyle.getLayoutType() == CarUiRecyclerViewLayout.LINEAR) {
            layoutManager = new LinearLayoutManager(getContext(),
                    layoutStyle.getOrientation(),
                    layoutStyle.getReverseLayout()) {
                @Override
                public void onLayoutCompleted(RecyclerView.State state) {
                    super.onLayoutCompleted(state);
                    // Iterate through a copied set instead of the original set because the original
                    // set might be modified during iteration.
                    Set<Runnable> onLayoutCompletedListeners =
                            new HashSet<>(mOnLayoutCompletedListeners);
                    for (Runnable runnable : onLayoutCompletedListeners) {
                        runnable.run();
                    }
                }
            };
        } else {
            layoutManager = new GridLayoutManager(getContext(),
                    layoutStyle.getSpanCount(),
                    layoutStyle.getOrientation(),
                    layoutStyle.getReverseLayout()) {
                @Override
                public void onLayoutCompleted(RecyclerView.State state) {
                    super.onLayoutCompleted(state);
                    // Iterate through a copied set instead of the original set because the original
                    // set might be modified during iteration.
                    Set<Runnable> onLayoutCompletedListeners =
                            new HashSet<>(mOnLayoutCompletedListeners);
                    for (Runnable runnable : onLayoutCompletedListeners) {
                        runnable.run();
                    }
                }
            };
            // TODO(b/190444037): revisit usage of LayoutStyles and their casting
            if (layoutStyle instanceof CarUiGridLayoutStyle) {
                ((GridLayoutManager) layoutManager).setSpanSizeLookup(
                        ((CarUiGridLayoutStyle) layoutStyle).getSpanSizeLookup());
            }
        }

        // Cannot setup item decorations before stylized attributes have been read.
        if (mIsInitialized) {
            addItemDecorations(layoutManager);
        }
        mRecyclerView.setLayoutManager(layoutManager);
    }

    @NonNull
    @Override
    public View getView() {
        return this;
    }

    @Override
    public void invalidateItemDecorations() {
        mRecyclerView.invalidateItemDecorations();
    }

    /**
     * {@inheritDoc}
     * <p>
     * Note that this method will never return true if this view has no items in it's adapter. This
     * is fine since an RecyclerView with empty items is not able to restore focus inside it.
     */
    @Override
    public boolean isLayoutCompleted() {
        RecyclerView.Adapter adapter = getAdapter();
        return adapter != null && adapter.getItemCount() > 0 && !mRecyclerView.isComputingLayout();
    }

    @Override
    public void addOnLayoutCompleteListener(@Nullable Runnable runnable) {
        if (runnable != null) {
            mOnLayoutCompletedListeners.add(runnable);
        }
    }

    @Override
    public void removeOnLayoutCompleteListener(@Nullable Runnable runnable) {
        if (runnable != null) {
            mOnLayoutCompletedListeners.remove(runnable);
        }
    }

    @Override
    public ViewHolder findViewHolderForAdapterPosition(int position) {
        return mRecyclerView.findViewHolderForAdapterPosition(position);
    }

    @Override
    public ViewHolder findViewHolderForLayoutPosition(int position) {
        return mRecyclerView.findViewHolderForLayoutPosition(position);
    }

    @Override
    public Adapter<?> getAdapter() {
        return mRecyclerView.getAdapter();
    }

    @Override
    public int getChildLayoutPosition(View child) {
        return mRecyclerView.getChildLayoutPosition(child);
    }

    @Override
    public RecyclerView getRecyclerView() {
        return mRecyclerView;
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

    private static int toInternalScrollState(int state) {
        /* default to RecyclerView.SCROLL_STATE_IDLE */
        int internalState = SCROLL_STATE_IDLE;
        switch (state) {
            case RecyclerView.SCROLL_STATE_DRAGGING:
                internalState = SCROLL_STATE_DRAGGING;
                break;
            case RecyclerView.SCROLL_STATE_SETTLING:
                internalState = SCROLL_STATE_SETTLING;
                break;
        }
        return internalState;
    }

    @Override
    public int getScrollState() {
        return toInternalScrollState(mRecyclerView.getScrollState());
    }

    @Override
    public void addOnScrollListener(OnScrollListener scrollListener) {
        if (mScrollListeners.isEmpty()) {
            mRecyclerView.addOnScrollListener(mOnScrollListener);
        }
        mScrollListeners.add(scrollListener);
    }

    @Override
    public void clearOnChildAttachStateChangeListeners() {
        mRecyclerView.clearOnChildAttachStateChangeListeners();
    }

    @Override
    public void clearOnScrollListeners() {
        mScrollListeners.clear();
        mRecyclerView.removeOnScrollListener(mOnScrollListener);
    }

    @Override
    public void addItemDecoration(
            @NonNull RecyclerView.ItemDecoration decor) {
        mRecyclerView.addItemDecoration(decor);
    }

    @Override
    public void addItemDecoration(
            @NonNull RecyclerView.ItemDecoration decor, int index) {
        mRecyclerView.addItemDecoration(decor, index);
    }

    @Override
    public void addOnChildAttachStateChangeListener(OnChildAttachStateChangeListener listener) {
        mRecyclerView.addOnChildAttachStateChangeListener(listener);
    }

    @NonNull
    @Override
    public ItemDecoration getItemDecorationAt(int index) {
        return mRecyclerView.getItemDecorationAt(index);
    }

    @Override
    public int getItemDecorationCount() {
        return mRecyclerView.getItemDecorationCount();
    }

    @Override
    public void removeItemDecorationAt(int index) {
        mRecyclerView.removeItemDecorationAt(index);
    }

    @Override
    public void removeOnChildAttachStateChangeListener(OnChildAttachStateChangeListener listener) {
        mRecyclerView.removeOnChildAttachStateChangeListener(listener);
    }

    @Override
    public void removeItemDecoration(
            @NonNull RecyclerView.ItemDecoration decor) {
        mRecyclerView.removeItemDecoration(decor);
    }

    @Override
    public int findFirstCompletelyVisibleItemPosition() {
        return ((LinearLayoutManager) Objects.requireNonNull(mRecyclerView.getLayoutManager()))
                .findFirstCompletelyVisibleItemPosition();
    }

    @Override
    public int findFirstVisibleItemPosition() {
        return ((LinearLayoutManager) Objects.requireNonNull(mRecyclerView.getLayoutManager()))
                .findFirstVisibleItemPosition();
    }

    @Override
    public int findLastCompletelyVisibleItemPosition() {
        return ((LinearLayoutManager) Objects.requireNonNull(mRecyclerView.getLayoutManager()))
                .findLastCompletelyVisibleItemPosition();
    }

    @Override
    public int findLastVisibleItemPosition() {
        return ((LinearLayoutManager) Objects.requireNonNull(mRecyclerView.getLayoutManager()))
                .findLastVisibleItemPosition();
    }

    @Override
    public void setSpanSizeLookup(@NonNull SpanSizeLookup spanSizeLookup) {
        if (mRecyclerView.getLayoutManager() instanceof GridLayoutManager) {
            ((GridLayoutManager) mRecyclerView.getLayoutManager())
                    .setSpanSizeLookup(spanSizeLookup);
        }
    }

    // This method should not be invoked before item decorations are initialized by the #init()
    // method.
    private void addItemDecorations(LayoutManager layoutManager) {
        // remove existing Item decorations.
        mRecyclerView.removeItemDecoration(Objects.requireNonNull(mDividerItemDecorationGrid));
        mRecyclerView.removeItemDecoration(Objects.requireNonNull(mDividerItemDecorationLinear));

        if (layoutManager instanceof GridLayoutManager) {
            if (mEnableDividers) {
                mRecyclerView.addItemDecoration(
                        Objects.requireNonNull(mDividerItemDecorationGrid));
            }
            setNumOfColumns(((GridLayoutManager) layoutManager).getSpanCount());
        } else {
            if (mEnableDividers) {
                mRecyclerView.addItemDecoration(
                        Objects.requireNonNull(mDividerItemDecorationLinear));
            }
        }
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

    @Override
    public void requestLayout() {
        super.requestLayout();
        if (mIsInitialized) {
            mRecyclerView.requestLayout();
        }
        if (mScrollBar != null) {
            mScrollBar.requestLayout();
        }
    }

    @Override
    public void removeOnScrollListener(OnScrollListener scrollListener) {
        mScrollListeners.remove(scrollListener);
        if (mScrollListeners.isEmpty()) {
            mRecyclerView.removeOnScrollListener(mOnScrollListener);
        }
    }

    /**
     * Sets the number of columns in which grid needs to be divided.
     */
    private void setNumOfColumns(int numberOfColumns) {
        mNumOfColumns = numberOfColumns;
        if (mDividerItemDecorationGrid != null) {
            mDividerItemDecorationGrid.setNumOfColumns(mNumOfColumns);
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
    public void setPadding(int left, int top, int right, int bottom) {
        if (mScrollBarEnabled) {
            int currentPosition = findFirstVisibleItemPosition();
            setScrollBarPadding(mScrollBarPaddingTop + top, mScrollBarPaddingBottom + bottom);
            // Maintain same index position after setting padding
            scrollToPosition(currentPosition);
        }
        mRecyclerView.setPadding(0, top, 0, bottom);
        super.setPadding(left, 0, right, 0);
    }

    @Override
    public void setPaddingRelative(int start, int top, int end, int bottom) {
        if (mScrollBarEnabled) {
            int currentPosition = findFirstVisibleItemPosition();
            setScrollBarPadding(mScrollBarPaddingTop + top, mScrollBarPaddingBottom + bottom);
            // Maintain same index position after setting padding
            scrollToPosition(currentPosition);
        }
        mRecyclerView.setPaddingRelative(0, top, 0, bottom);
        super.setPaddingRelative(start, 0, end, 0);
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
    public void smoothScrollBy(int dx, int dy) {
        mRecyclerView.smoothScrollBy(dx, dy);
    }

    @Override
    public void smoothScrollToPosition(int position) {
        mRecyclerView.smoothScrollToPosition(position);
    }

    @Override
    public boolean post(Runnable runnable) {
        return mRecyclerView.post(runnable);
    }

    @Override
    public void scrollToPosition(int position) {
        mRecyclerView.scrollToPosition(position);
    }

    @Override
    public void scrollBy(int x, int y) {
        mRecyclerView.scrollBy(x, y);
    }

    /**
     * Sets the scrollbar's padding top and bottom. This padding is applied in addition to the
     * padding of the RecyclerView.
     */
    private void setScrollBarPadding(int paddingTop, int paddingBottom) {
        if (mScrollBarEnabled && mScrollBar != null) {
            mScrollBar.setPadding(paddingTop, paddingBottom);
        }
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

    @Override
    public void setAdapter(@Nullable Adapter<?> adapter) {
        if (mScrollBar != null) {
            // Make sure this is called before super so that scrollbar can get a reference to
            // the adapter using RecyclerView#getAdapter()
            mScrollBar.adapterChanged(adapter);
        }
        if (mAdapter instanceof OnAttachListener) {
            ((OnAttachListener) mAdapter).onDetachedFromCarUiRecyclerView(this);
        }
        mAdapter = adapter;
        mRecyclerView.setAdapter(adapter);
        if (adapter instanceof OnAttachListener) {
            ((OnAttachListener) adapter).onAttachedToCarUiRecyclerView(this);
        }
    }

    @Override
    public void setItemAnimator(ItemAnimator itemAnimator) {
        mRecyclerView.setItemAnimator(itemAnimator);
    }

    @Override
    public void setHasFixedSize(boolean hasFixedSize) {
        mRecyclerView.setHasFixedSize(hasFixedSize);
    }

    @Override
    public void setOnFlingListener(OnFlingListener listener) {
        mRecyclerView.setOnFlingListener(listener);
    }

    private OrientationHelper createOrientationHelper() {
        if (mLayoutStyle.getOrientation() == CarUiLayoutStyle.VERTICAL) {
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

    private void createScrollBarFromConfig(Context context, View scrollView) {
        Class<?> cls;
        try {
            cls = !TextUtils.isEmpty(mScrollBarClass)
                    ? getContext().getClassLoader().loadClass(mScrollBarClass)
                    : DefaultScrollBar.class;
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Error loading scroll bar component: "
                    + mScrollBarClass, e);
        }
        try {
            Constructor<?> cnst = cls.getDeclaredConstructor();
            cnst.setAccessible(true);
            mScrollBar = (ScrollBar) cnst.newInstance();
        } catch (ReflectiveOperationException e) {
            throw new IllegalArgumentException("Error creating scroll bar component: "
                    + mScrollBarClass, e);
        }

        mScrollBar.initialize(context, mRecyclerView, scrollView);

        setScrollBarPadding(mScrollBarPaddingTop + getPaddingTop(),
                mScrollBarPaddingBottom + getPaddingBottom());
    }

    private class UxRestrictionChangedListener implements
            CarUxRestrictionsUtil.OnUxRestrictionsChangedListener {

        @Override
        public void onRestrictionsChanged(@NonNull CarUxRestrictions carUxRestrictions) {
            Adapter<?> adapter = mRecyclerView.getAdapter();
            // If the adapter does not implement ItemCap, then the max items on it cannot be
            // updated.
            if (!(adapter instanceof ItemCap)) {
                return;
            }

            int maxItems = ItemCap.UNLIMITED;
            if ((carUxRestrictions.getActiveRestrictions()
                    & CarUxRestrictions.UX_RESTRICTIONS_LIMIT_CONTENT)
                    != 0) {
                maxItems = carUxRestrictions.getMaxCumulativeContentItems();
            }

            int originalCount = adapter.getItemCount();
            ((ItemCap) adapter).setMaxItems(maxItems);
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

    /**
     * Instantiate and set a LayoutManager, if specified in the attributes. exact copy of
     * {@link Recyclerview#createLayoutManager(Context, String, int, int)}
     */
    private void createLayoutManager(Context context, String className, AttributeSet attrs,
            int defStyleAttr, int defStyleRes) {
        if (className != null) {
            className = className.trim();
            if (!className.isEmpty()) {
                className = getFullClassName(context, className);
                try {
                    ClassLoader classLoader;
                    if (isInEditMode()) {
                        // layoutlib cannot handle simple class loaders.
                        classLoader = this.getClass().getClassLoader();
                    } else {
                        classLoader = context.getClassLoader();
                    }
                    Class<? extends LayoutManager> layoutManagerClass =
                            Class.forName(className, false, classLoader)
                                    .asSubclass(LayoutManager.class);
                    Constructor<? extends LayoutManager> constructor;
                    Object[] constructorArgs = null;
                    try {
                        constructor = layoutManagerClass
                                .getConstructor(LAYOUT_MANAGER_CONSTRUCTOR_SIGNATURE);
                        constructorArgs = new Object[]{context, attrs, defStyleAttr, defStyleRes};
                    } catch (NoSuchMethodException e) {
                        try {
                            constructor = layoutManagerClass.getConstructor();
                        } catch (NoSuchMethodException e1) {
                            e1.initCause(e);
                            throw new IllegalStateException(attrs.getPositionDescription()
                                    + ": Error creating LayoutManager " + className, e1);
                        }
                    }
                    constructor.setAccessible(true);
                    setLayoutManager(constructor.newInstance(constructorArgs));
                } catch (ClassNotFoundException e) {
                    throw new IllegalStateException(attrs.getPositionDescription()
                            + ": Unable to find LayoutManager " + className, e);
                } catch (InvocationTargetException e) {
                    throw new IllegalStateException(attrs.getPositionDescription()
                            + ": Could not instantiate the LayoutManager: " + className, e);
                } catch (InstantiationException e) {
                    throw new IllegalStateException(attrs.getPositionDescription()
                            + ": Could not instantiate the LayoutManager: " + className, e);
                } catch (IllegalAccessException e) {
                    throw new IllegalStateException(attrs.getPositionDescription()
                            + ": Cannot access non-public constructor " + className, e);
                } catch (ClassCastException e) {
                    throw new IllegalStateException(attrs.getPositionDescription()
                            + ": Class is not a LayoutManager " + className, e);
                }
            }
        }
    }

    /**
     * exact copy of {@link RecyclerView#getFullClassName(Context, String)}
     */
    private String getFullClassName(Context context, String className) {
        if (className.charAt(0) == '.') {
            return context.getPackageName() + className;
        }
        if (className.contains(".")) {
            return className;
        }
        return RecyclerView.class.getPackage().getName() + '.' + className;
    }
}
