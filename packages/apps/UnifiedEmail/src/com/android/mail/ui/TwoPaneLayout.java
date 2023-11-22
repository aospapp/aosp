/*******************************************************************************
 *      Copyright (C) 2012 Google Inc.
 *      Licensed to The Android Open Source Project.
 *
 *      Licensed under the Apache License, Version 2.0 (the "License");
 *      you may not use this file except in compliance with the License.
 *      You may obtain a copy of the License at
 *
 *           http://www.apache.org/licenses/LICENSE-2.0
 *
 *      Unless required by applicable law or agreed to in writing, software
 *      distributed under the License is distributed on an "AS IS" BASIS,
 *      WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *      See the License for the specific language governing permissions and
 *      limitations under the License.
 *******************************************************************************/

package com.android.mail.ui;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.TimeInterpolator;
import android.app.Activity;
import android.content.Context;
import android.content.res.Resources;
import android.util.AttributeSet;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.FrameLayout;

import com.android.mail.R;
import com.android.mail.ui.ViewMode.ModeChangeListener;
import com.android.mail.utils.LogUtils;
import com.android.mail.utils.Utils;
import com.android.mail.utils.ViewUtils;
import com.google.common.annotations.VisibleForTesting;

/**
 * This is a custom layout that manages the possible views of Gmail's large screen (read: tablet)
 * activity, and the transitions between them.
 *
 * This is not intended to be a generic layout; it is specific to the {@code Fragment}s
 * available in {@link MailActivity} and assumes their existence. It merely configures them
 * according to the specific <i>modes</i> the {@link Activity} can be in.
 *
 * Currently, the layout differs in three dimensions: orientation, two aspects of view modes.
 * This results in essentially three states: One where the folders are on the left and conversation
 * list is on the right, and two states where the conversation list is on the left: one in which
 * it's collapsed and another where it is not.
 *
 * In folder or conversation list view, conversations are hidden and folders and conversation lists
 * are visible. This is the case in both portrait and landscape
 *
 * In Conversation List or Conversation View, folders are hidden, and conversation lists and
 * conversation view is visible. This is the case in both portrait and landscape.
 *
 * In the Gmail source code, this was called TriStateSplitLayout
 */
final class TwoPaneLayout extends FrameLayout implements ModeChangeListener {

    private static final String LOG_TAG = "TwoPaneLayout";
    private static final long SLIDE_DURATION_MS = 300;

    private final int mDrawerWidthMini;
    private final int mDrawerWidthOpen;
    private final double mConversationListWeight;
    private final TimeInterpolator mSlideInterpolator;
    /**
     * If true, this layout group will treat the thread list and conversation view as full-width
     * panes to switch between.<br>
     * <br>
     * If false, always show a conversation view right next to the conversation list. This view will
     * also be populated (preview / "peek" mode) with a default conversation if none is selected by
     * the user.
     */
    private final boolean mListCollapsible;

    /**
     * The current mode that the tablet layout is in. This is a constant integer that holds values
     * that are {@link ViewMode} constants like {@link ViewMode#CONVERSATION}.
     */
    private int mCurrentMode = ViewMode.UNKNOWN;
    /**
     * This mode represents the current positions of the three panes. This is split out from the
     * current mode to give context to state transitions.
     */
    private int mPositionedMode = ViewMode.UNKNOWN;

    private TwoPaneController mController;
    private LayoutListener mListener;
    private boolean mIsSearchResult;

    private View mMiscellaneousView;
    private View mConversationView;
    private View mFoldersView;
    private View mListView;

    public static final int MISCELLANEOUS_VIEW_ID = R.id.miscellaneous_pane;

    private final Runnable mTransitionCompleteRunnable = new Runnable() {
        @Override
        public void run() {
            onTransitionComplete();
        }
    };

    public TwoPaneLayout(Context context) {
        this(context, null);
    }

    public TwoPaneLayout(Context context, AttributeSet attrs) {
        super(context, attrs);

        final Resources res = getResources();

        // The conversation list might be visible now, depending on the layout: in portrait we
        // don't show the conversation list, but in landscape we do.  This information is stored
        // in the constants
        mListCollapsible = res.getBoolean(R.bool.list_collapsible);

        mDrawerWidthMini = res.getDimensionPixelSize(R.dimen.two_pane_drawer_width_mini);
        mDrawerWidthOpen = res.getDimensionPixelSize(R.dimen.two_pane_drawer_width_open);

        mSlideInterpolator = AnimationUtils.loadInterpolator(context,
                android.R.interpolator.decelerate_cubic);

        final int convListWeight = res.getInteger(R.integer.conversation_list_weight);
        final int convViewWeight = res.getInteger(R.integer.conversation_view_weight);
        mConversationListWeight = (double) convListWeight
                / (convListWeight + convViewWeight);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();

        mFoldersView = findViewById(R.id.drawer);
        mListView = findViewById(R.id.conversation_list_pane);
        mConversationView = findViewById(R.id.conversation_pane);
        mMiscellaneousView = findViewById(MISCELLANEOUS_VIEW_ID);

        // all panes start GONE in initial UNKNOWN mode to avoid drawing misplaced panes
        mCurrentMode = ViewMode.UNKNOWN;
        mFoldersView.setVisibility(GONE);
        mListView.setVisibility(GONE);
        mConversationView.setVisibility(GONE);
        mMiscellaneousView.setVisibility(GONE);
    }

    @VisibleForTesting
    public void setController(TwoPaneController controller, boolean isSearchResult) {
        mController = controller;
        mListener = controller;
        mIsSearchResult = isSearchResult;

        ((ConversationViewFrame) mConversationView).setDownEventListener(mController);
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        LogUtils.d(Utils.VIEW_DEBUGGING_TAG, "TPL(%s).onMeasure()", this);
        setupPaneWidths(MeasureSpec.getSize(widthMeasureSpec));
        super.onMeasure(widthMeasureSpec, heightMeasureSpec);
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        LogUtils.d(Utils.VIEW_DEBUGGING_TAG, "TPL(%s).onLayout()", this);
        positionPanes(getMeasuredWidth());
        super.onLayout(changed, l, t, r, b);
    }

    /**
     * Sizes up the three sliding panes. This method will ensure that the LayoutParams of the panes
     * have the correct widths set for the current overall size and view mode.
     *
     * @param parentWidth this view's new width
     */
    private void setupPaneWidths(int parentWidth) {
        // only adjust the pane widths when my width changes
        if (parentWidth != getMeasuredWidth()) {
            final int convWidth = computeConversationWidth(parentWidth);
            setPaneWidth(mMiscellaneousView, convWidth);
            setPaneWidth(mConversationView, convWidth);
            setPaneWidth(mListView, computeConversationListWidth(parentWidth));
        }
    }

    /**
     * Positions the three sliding panes at the correct X offset (using {@link View#setX(float)}).
     * When switching from list->conversation mode or vice versa, animate the change in X.
     *
     * @param width
     */
    private void positionPanes(int width) {
        final int convX, listX, foldersX;
        final boolean isRtl = ViewUtils.isViewRtl(this);

        final int foldersW = isDrawerOpen() ? mDrawerWidthOpen : mDrawerWidthMini;
        final int listW = getPaneWidth(mListView);

        boolean cvOnScreen = true;
        if (!mListCollapsible) {
            if (isRtl) {
                foldersX = width - mDrawerWidthOpen;
                listX = width - foldersW - listW;
                convX = listX - getPaneWidth(mConversationView);
            } else {
                foldersX = 0;
                listX = foldersW;
                convX = listX + listW;
            }
        } else {
            if (mController.getCurrentConversation() != null
                    && !mController.isCurrentConversationJustPeeking()) {
                // CV mode
                if (isRtl) {
                    convX = 0;
                    listX = getPaneWidth(mConversationView);
                    foldersX = listX + width - mDrawerWidthOpen;
                } else {
                    convX = 0;
                    listX = -listW;
                    foldersX = listX - foldersW;
                }
            } else {
                // TL mode
                cvOnScreen = false;
                if (isRtl) {
                    foldersX = width - mDrawerWidthOpen;
                    listX = width - foldersW - listW;
                    convX = listX - getPaneWidth(mConversationView);
                } else {
                    foldersX = 0;
                    listX = foldersW;
                    convX = listX + listW;
                }
            }
        }

        animatePanes(foldersX, listX, convX);

        // For views that are not on the screen, let's set their visibility for accessibility.
        mFoldersView.setVisibility(foldersX >= 0 ? VISIBLE : INVISIBLE);
        mListView.setVisibility(listX >= 0 ? VISIBLE : INVISIBLE);
        mConversationView.setVisibility(cvOnScreen ? VISIBLE : INVISIBLE);
        mMiscellaneousView.setVisibility(cvOnScreen ? VISIBLE : INVISIBLE);

        mPositionedMode = mCurrentMode;
    }

    private final AnimatorListenerAdapter mPaneAnimationListener = new AnimatorListenerAdapter() {
        @Override
        public void onAnimationEnd(Animator animation) {
            useHardwareLayer(false);
            onTransitionComplete();
        }
        @Override
        public void onAnimationCancel(Animator animation) {
            useHardwareLayer(false);
        }
    };

    private void animatePanes(int foldersX, int listX, int convX) {
        // If positioning has not yet happened, we don't need to animate panes into place.
        // This happens on first layout, rotate, and when jumping straight to a conversation from
        // a view intent.
        if (mPositionedMode == ViewMode.UNKNOWN) {
            mConversationView.setX(convX);
            mMiscellaneousView.setX(convX);
            mListView.setX(listX);
            mFoldersView.setX(foldersX);

            // listeners need to know that the "transition" is complete, even if one is not run.
            // defer notifying listeners because we're in a layout pass, and they might do layout.
            post(mTransitionCompleteRunnable);
            return;
        }

        useHardwareLayer(true);

        if (ViewMode.isAdMode(mCurrentMode)) {
            mMiscellaneousView.animate().x(convX);
        } else {
            mConversationView.animate().x(convX);
        }

        mFoldersView.animate().x(foldersX);
        mListView.animate()
            .x(listX)
            .setListener(mPaneAnimationListener);
        configureAnimations(mConversationView, mFoldersView, mListView, mMiscellaneousView);
    }

    private void configureAnimations(View... views) {
        for (View v : views) {
            v.animate()
                .setInterpolator(mSlideInterpolator)
                .setDuration(SLIDE_DURATION_MS);
        }
    }

    private void useHardwareLayer(boolean useHardware) {
        final int layerType = useHardware ? LAYER_TYPE_HARDWARE : LAYER_TYPE_NONE;
        mFoldersView.setLayerType(layerType, null);
        mListView.setLayerType(layerType, null);
        mConversationView.setLayerType(layerType, null);
        mMiscellaneousView.setLayerType(layerType, null);
        if (useHardware) {
            // these buildLayer calls are safe because layout is the only way we get here
            // (i.e. these views must already be attached)
            mFoldersView.buildLayer();
            mListView.buildLayer();
            mConversationView.buildLayer();
            mMiscellaneousView.buildLayer();
        }
    }

    private void onTransitionComplete() {
        if (mController.isDestroyed()) {
            // quit early if the hosting activity was destroyed before the animation finished
            LogUtils.i(LOG_TAG, "IN TPL.onTransitionComplete, activity destroyed->quitting early");
            return;
        }

        switch (mCurrentMode) {
            case ViewMode.CONVERSATION:
            case ViewMode.SEARCH_RESULTS_CONVERSATION:
                dispatchConversationVisibilityChanged(true);
                dispatchConversationListVisibilityChange(!isConversationListCollapsed());

                break;
            case ViewMode.CONVERSATION_LIST:
            case ViewMode.SEARCH_RESULTS_LIST:
                dispatchConversationVisibilityChanged(false);
                dispatchConversationListVisibilityChange(true);

                break;
            case ViewMode.AD:
                dispatchConversationVisibilityChanged(false);
                dispatchConversationListVisibilityChange(!isConversationListCollapsed());

                break;
            default:
                break;
        }
    }

    /**
     * Computes the width of the conversation list in stable state of the current mode.
     */
    public int computeConversationListWidth() {
        return computeConversationListWidth(getMeasuredWidth());
    }

    /**
     * Computes the width of the conversation list in stable state of the current mode.
     */
    private int computeConversationListWidth(int parentWidth) {
        final int availWidth = parentWidth - mDrawerWidthMini;
        return mListCollapsible ? availWidth : (int) (availWidth * mConversationListWeight);
    }

    public int computeConversationWidth() {
        return computeConversationWidth(getMeasuredWidth());
    }

    /**
     * Computes the width of the conversation pane in stable state of the
     * current mode.
     */
    private int computeConversationWidth(int parentWidth) {
        return mListCollapsible ? parentWidth :
                parentWidth - computeConversationListWidth(parentWidth) - mDrawerWidthMini;
    }

    private void dispatchConversationListVisibilityChange(boolean visible) {
        if (mListener != null) {
            mListener.onConversationListVisibilityChanged(visible);
        }
    }

    private void dispatchConversationVisibilityChanged(boolean visible) {
        if (mListener != null) {
            mListener.onConversationVisibilityChanged(visible);
        }
    }

    // does not apply to drawer children. will return zero for those.
    private int getPaneWidth(View pane) {
        return pane.getLayoutParams().width;
    }

    private boolean isDrawerOpen() {
        return mController != null && mController.isDrawerOpen();
    }

    /**
     * @return Whether or not the conversation list is visible on screen.
     */
    @Deprecated
    public boolean isConversationListCollapsed() {
        return !ViewMode.isListMode(mCurrentMode) && mListCollapsible;
    }

    @Override
    public void onViewModeChanged(int newMode) {
        // make all initially GONE panes visible only when the view mode is first determined
        if (mCurrentMode == ViewMode.UNKNOWN) {
            mFoldersView.setVisibility(VISIBLE);
            mListView.setVisibility(VISIBLE);
        }

        if (ViewMode.isAdMode(newMode)) {
            mMiscellaneousView.setVisibility(VISIBLE);
            mConversationView.setVisibility(GONE);
        } else {
            mConversationView.setVisibility(VISIBLE);
            mMiscellaneousView.setVisibility(GONE);
        }

        // detach the pager immediately from its data source (to prevent processing updates)
        if (ViewMode.isConversationMode(mCurrentMode)) {
            mController.disablePagerUpdates();
        }

        mCurrentMode = newMode;
        LogUtils.i(LOG_TAG, "onViewModeChanged(%d)", newMode);

        // do all the real work in onMeasure/onLayout, when panes are sized and positioned for the
        // current width/height anyway
        requestLayout();
    }

    public boolean isModeChangePending() {
        return mPositionedMode != mCurrentMode;
    }

    private void setPaneWidth(View pane, int w) {
        final ViewGroup.LayoutParams lp = pane.getLayoutParams();
        if (lp.width == w) {
            return;
        }
        lp.width = w;
        pane.setLayoutParams(lp);
        if (LogUtils.isLoggable(LOG_TAG, LogUtils.DEBUG)) {
            final String s;
            if (pane == mFoldersView) {
                s = "folders";
            } else if (pane == mListView) {
                s = "conv-list";
            } else if (pane == mConversationView) {
                s = "conv-view";
            } else if (pane == mMiscellaneousView) {
                s = "misc-view";
            } else {
                s = "???:" + pane;
            }
            LogUtils.d(LOG_TAG, "TPL: setPaneWidth, w=%spx pane=%s", w, s);
        }
    }

    public boolean shouldShowPreviewPanel() {
        return !mListCollapsible;
    }
}
