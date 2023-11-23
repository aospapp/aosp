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

package com.android.car.ui;

import static android.view.View.FOCUS_DOWN;
import static android.view.View.FOCUS_LEFT;
import static android.view.View.FOCUS_RIGHT;
import static android.view.View.FOCUS_UP;
import static android.view.View.LAYOUT_DIRECTION_RTL;
import static android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS;

import static com.android.car.ui.utils.RotaryConstants.ACTION_NUDGE_SHORTCUT;
import static com.android.car.ui.utils.RotaryConstants.ACTION_NUDGE_TO_ANOTHER_FOCUS_AREA;
import static com.android.car.ui.utils.RotaryConstants.FOCUS_AREA_BOTTOM_BOUND_OFFSET;
import static com.android.car.ui.utils.RotaryConstants.FOCUS_AREA_LEFT_BOUND_OFFSET;
import static com.android.car.ui.utils.RotaryConstants.FOCUS_AREA_RIGHT_BOUND_OFFSET;
import static com.android.car.ui.utils.RotaryConstants.FOCUS_AREA_TOP_BOUND_OFFSET;
import static com.android.car.ui.utils.RotaryConstants.NUDGE_DIRECTION;

import android.content.Context;
import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Log;
import android.util.SparseArray;
import android.util.SparseIntArray;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver.OnGlobalFocusChangeListener;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;

import com.android.car.ui.utils.ViewUtils;

import java.util.Arrays;
import java.util.List;

/** A helper class used by {@link IFocusArea} implementation classes such as {@link FocusArea}. */
class FocusAreaHelper {

    private static final String TAG = "FocusAreaHelper";

    private static final int INVALID_DIMEN = -1;

    private static final int INVALID_DIRECTION = -1;

    private static final List<Integer> NUDGE_DIRECTIONS =
            Arrays.asList(FOCUS_LEFT, FOCUS_RIGHT, FOCUS_UP, FOCUS_DOWN);

    private static final List<Integer> FOCUS_AREA_ACTIONS =
            Arrays.asList(ACTION_FOCUS, ACTION_NUDGE_SHORTCUT, ACTION_NUDGE_TO_ANOTHER_FOCUS_AREA);

    @NonNull
    private final ViewGroup mFocusArea;

    /**
     * Whether one of {@link #mFocusArea}'s descendant is focused (the {@link #mFocusArea} itself
     * is not focusable).
     */
    private boolean mHasFocus;

    /**
     * Whether to draw {@link #mForegroundHighlight} when one of {@link #mFocusArea}'s descendants
     * is focused and it's not in touch mode.
     */
    private boolean mEnableForegroundHighlight;

    /**
     * Whether to draw {@link #mBackgroundHighlight} when one of {@link #mFocusArea}'s descendants
     * is focused and it's not in touch mode.
     */
    private boolean mEnableBackgroundHighlight;

    /**
     * Highlight (typically outline of the focus area) drawn on top of {@link #mFocusArea} and its
     * descendants.
     */
    private Drawable mForegroundHighlight;

    /**
     * Highlight (typically a solid or gradient shape) drawn on top of {@link #mFocusArea} but
     * behind its descendants.
     */
    private Drawable mBackgroundHighlight;

    /** The padding (in pixels) of the focus area highlight. */
    private int mPaddingLeft;
    private int mPaddingRight;
    private int mPaddingTop;
    private int mPaddingBottom;

    /** The offset (in pixels) of {@link #mFocusArea}'s bounds. */
    private int mLeftOffset;
    private int mRightOffset;
    private int mTopOffset;
    private int mBottomOffset;

    /** Whether the {@link #mFocusArea}'s layout direction is {@link View#LAYOUT_DIRECTION_RTL}. */
    private boolean mRtl;

    /** The ID of the view specified in {@link #mFocusArea}'s {@code app:defaultFocus}. */
    private int mDefaultFocusId;
    /** The view specified in {@link #mFocusArea}'s {@code app:defaultFocus}. */
    @Nullable
    private View mDefaultFocusView;

    /**
     * Whether to focus on the default focus view when nudging to {@link #mFocusArea}, even if
     * there was another view in {@link #mFocusArea} focused before.
     */
    private boolean mDefaultFocusOverridesHistory;

    /**
     * Map from direction to nudge shortcut IDs specified in {@code app:nudgeLeftShortcut},
     * {@code app:nudgRightShortcut}, {@code app:nudgeUpShortcut}, and {@code app
     * :nudgeDownShortcut}.
     */
    private final SparseIntArray mSpecifiedNudgeShortcutIdMap = new SparseIntArray();

    /** Map from direction to specified nudge shortcut views. */
    private SparseArray<View> mSpecifiedNudgeShortcutMap;

    /**
     * Map from direction to nudge target focus area IDs specified in {@link #mFocusArea}'s
     * {@code app:nudgeLeft}, {@code app:nudgRight}, {@code app:nudgeUp}, and {@code app:nudgeDown}.
     */
    private final SparseIntArray mSpecifiedNudgeIdMap = new SparseIntArray();

    /** Map from direction to specified nudge target focus areas. */
    private SparseArray<IFocusArea> mSpecifiedNudgeFocusAreaMap;

    /** Whether wrap-around is enabled for {@link #mFocusArea}. */
    private boolean mWrapAround;

    /**
     * Cache of focus history and nudge history of the rotary controller.
     * <p>
     * For focus history, the previously focused view and a timestamp will be saved when the
     * focused view has changed.
     * <p>
     * For nudge history, the target focus area, direction, and a timestamp will be saved when the
     * focus has moved from another focus area to {@link #mFocusArea}. There are two cases:
     * <ul>
     *     <li>The focus is moved to another focus area because {@link #mFocusArea} has called
     *         {@link #nudgeToAnotherFocusArea}. In this case, the target focus area and direction
     *         are trivial to {@link #mFocusArea}.
     *     <li>The focus is moved to {@link #mFocusArea} because RotaryService has performed {@link
     *         AccessibilityNodeInfo#ACTION_FOCUS} on {@link #mFocusArea}. In this case,
     *         {@link #mFocusArea} can get the source focus area through the {@link
     *         android.view.ViewTreeObserver.OnGlobalFocusChangeListener} registered, and can get
     *         the direction when handling the action. Since the listener is triggered before
     *         {@link View#requestFocus} returns (which is called when handling the action), the
     *         source focus area is revealed earlier than the direction, so the nudge history should
     *         be saved when the direction is revealed.
     * </ul>
     */
    private RotaryCache mRotaryCache;

    /** Whether to clear focus area history when the user rotates the rotary controller. */
    private boolean mClearFocusAreaHistoryWhenRotating;

    /** The focus area that had focus before {@link #mFocusArea}, if any. */
    private IFocusArea mPreviousFocusArea;

    /** The focused view in {@link #mFocusArea}, if any. */
    private View mFocusedView;

    private final OnGlobalFocusChangeListener mFocusChangeListener;

    /**
     * Whether to restore focus when Android frameworks want to focus inside {@link #mFocusArea}.
     * This should be false if {@link #mFocusArea} is in a {@link com.android.wm.shell.TaskView}.
     * The default value is true.
     */
    private boolean mShouldRestoreFocus = true;

    FocusAreaHelper(@NonNull ViewGroup viewGroup, @Nullable AttributeSet attrs) {
        mFocusArea = viewGroup;

        mFocusChangeListener =
                (oldFocus, newFocus) -> {
                    boolean hasFocus = mFocusArea.hasFocus();
                    saveFocusHistory(hasFocus);
                    maybeUpdatePreviousFocusArea(hasFocus, oldFocus);
                    maybeClearFocusAreaHistory(hasFocus, oldFocus);
                    maybeUpdateFocusAreaHighlight(hasFocus);
                    mHasFocus = hasFocus;
                };

        Context context = mFocusArea.getContext();
        Resources resources = context.getResources();
        mEnableForegroundHighlight = resources.getBoolean(
                R.bool.car_ui_enable_focus_area_foreground_highlight);
        mEnableBackgroundHighlight = resources.getBoolean(
                R.bool.car_ui_enable_focus_area_background_highlight);
        mForegroundHighlight = resources.getDrawable(
                R.drawable.car_ui_focus_area_foreground_highlight, context.getTheme());
        mBackgroundHighlight = resources.getDrawable(
                R.drawable.car_ui_focus_area_background_highlight, context.getTheme());

        mClearFocusAreaHistoryWhenRotating = resources.getBoolean(
                R.bool.car_ui_clear_focus_area_history_when_rotating);

        @RotaryCache.CacheType
        int focusHistoryCacheType = resources.getInteger(R.integer.car_ui_focus_history_cache_type);
        int focusHistoryExpirationPeriodMs =
                resources.getInteger(R.integer.car_ui_focus_history_expiration_period_ms);
        @RotaryCache.CacheType
        int focusAreaHistoryCacheType = resources.getInteger(
                R.integer.car_ui_focus_area_history_cache_type);
        int focusAreaHistoryExpirationPeriodMs =
                resources.getInteger(R.integer.car_ui_focus_area_history_expiration_period_ms);
        mRotaryCache = new RotaryCache(focusHistoryCacheType, focusHistoryExpirationPeriodMs,
                focusAreaHistoryCacheType, focusAreaHistoryExpirationPeriodMs);

        // Ensure that an AccessibilityNodeInfo is created for mFocusArea.
        mFocusArea.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_YES);

        // By default all ViewGroup subclasses do not call their draw() and onDraw() methods. We
        // should enable it since we override these methods.
        mFocusArea.setWillNotDraw(false);

        initAttrs(context, attrs);
    }

    private void saveFocusHistory(boolean hasFocus) {
        // Save focus history and clear mFocusedView if focus is leaving mFocusArea.
        if (!hasFocus) {
            if (mHasFocus) {
                mRotaryCache.saveFocusedView(mFocusedView, SystemClock.uptimeMillis());
                mFocusedView = null;
            }
            return;
        }

        // Update mFocusedView if a descendant of mFocusArea is focused.
        View v = mFocusArea.getFocusedChild();
        while (v != null) {
            if (v.isFocused()) {
                break;
            }
            v = v instanceof ViewGroup ? ((ViewGroup) v).getFocusedChild() : null;
        }
        mFocusedView = v;
    }

    /**
     * Updates {@link #mPreviousFocusArea} when the focus has moved from another focus area to
     * {@link #mFocusArea}, and sets it to {@code null} in any other cases.
     */
    private void maybeUpdatePreviousFocusArea(boolean hasFocus, View oldFocus) {
        if (mHasFocus || !hasFocus || oldFocus == null || oldFocus instanceof FocusParkingView) {
            mPreviousFocusArea = null;
            return;
        }
        mPreviousFocusArea = ViewUtils.getAncestorFocusArea(oldFocus);
        if (mPreviousFocusArea == null) {
            Log.w(TAG, "No ancestor focus area for " + oldFocus);
        }
    }

    /**
     * Clears focus area nudge history when the user rotates the controller to move focus within
     * {@link #mFocusArea}.
     */
    private void maybeClearFocusAreaHistory(boolean hasFocus, View oldFocus) {
        if (!mClearFocusAreaHistoryWhenRotating) {
            return;
        }
        if (!hasFocus || oldFocus == null) {
            return;
        }
        IFocusArea oldFocusArea = ViewUtils.getAncestorFocusArea(oldFocus);
        if (oldFocusArea != mFocusArea) {
            return;
        }
        mRotaryCache.clearFocusAreaHistory();
    }

    /** Updates highlight of {@link #mFocusArea} if it has gained or lost focus. */
    private void maybeUpdateFocusAreaHighlight(boolean hasFocus) {
        if (!mEnableBackgroundHighlight && !mEnableForegroundHighlight) {
            return;
        }
        if (mHasFocus != hasFocus) {
            mFocusArea.invalidate();
        }
    }

    private void initAttrs(Context context, @Nullable AttributeSet attrs) {
        if (attrs == null) {
            return;
        }
        TypedArray a = context.obtainStyledAttributes(attrs, R.styleable.IFocusArea);
        try {
            mDefaultFocusId = a.getResourceId(R.styleable.IFocusArea_defaultFocus, View.NO_ID);

            // Initialize the highlight padding. The padding, for example, left padding, is set in
            // the following order:
            // 1. if highlightPaddingStart (or highlightPaddingEnd in RTL layout) specified, use it
            // 2. otherwise, if highlightPaddingHorizontal is specified, use it
            // 3. otherwise use 0

            int paddingStart = a.getDimensionPixelSize(
                    R.styleable.IFocusArea_highlightPaddingStart, INVALID_DIMEN);
            if (paddingStart == INVALID_DIMEN) {
                paddingStart = a.getDimensionPixelSize(
                        R.styleable.IFocusArea_highlightPaddingHorizontal, 0);
            }

            int paddingEnd = a.getDimensionPixelSize(
                    R.styleable.IFocusArea_highlightPaddingEnd, INVALID_DIMEN);
            if (paddingEnd == INVALID_DIMEN) {
                paddingEnd = a.getDimensionPixelSize(
                        R.styleable.IFocusArea_highlightPaddingHorizontal, 0);
            }

            mRtl = mFocusArea.getLayoutDirection() == LAYOUT_DIRECTION_RTL;
            mPaddingLeft = mRtl ? paddingEnd : paddingStart;
            mPaddingRight = mRtl ? paddingStart : paddingEnd;

            mPaddingTop = a.getDimensionPixelSize(
                    R.styleable.IFocusArea_highlightPaddingTop, INVALID_DIMEN);
            if (mPaddingTop == INVALID_DIMEN) {
                mPaddingTop = a.getDimensionPixelSize(
                        R.styleable.IFocusArea_highlightPaddingVertical, 0);
            }

            mPaddingBottom = a.getDimensionPixelSize(
                    R.styleable.IFocusArea_highlightPaddingBottom, INVALID_DIMEN);
            if (mPaddingBottom == INVALID_DIMEN) {
                mPaddingBottom = a.getDimensionPixelSize(
                        R.styleable.IFocusArea_highlightPaddingVertical, 0);
            }

            // Initialize the offset of mFocusArea's bounds. The offset, for example, left
            // offset, is set in the following order:
            // 1. if startBoundOffset (or endBoundOffset in RTL layout) specified, use it
            // 2. otherwise, if horizontalBoundOffset is specified, use it
            // 3. otherwise use mPaddingLeft

            int startOffset = a.getDimensionPixelSize(
                    R.styleable.IFocusArea_startBoundOffset, INVALID_DIMEN);
            if (startOffset == INVALID_DIMEN) {
                startOffset = a.getDimensionPixelSize(
                        R.styleable.IFocusArea_horizontalBoundOffset, paddingStart);
            }

            int endOffset = a.getDimensionPixelSize(
                    R.styleable.IFocusArea_endBoundOffset, INVALID_DIMEN);
            if (endOffset == INVALID_DIMEN) {
                endOffset = a.getDimensionPixelSize(
                        R.styleable.IFocusArea_horizontalBoundOffset, paddingEnd);
            }

            mLeftOffset = mRtl ? endOffset : startOffset;
            mRightOffset = mRtl ? startOffset : endOffset;

            mTopOffset = a.getDimensionPixelSize(
                    R.styleable.IFocusArea_topBoundOffset, INVALID_DIMEN);
            if (mTopOffset == INVALID_DIMEN) {
                mTopOffset = a.getDimensionPixelSize(
                        R.styleable.IFocusArea_verticalBoundOffset, mPaddingTop);
            }

            mBottomOffset = a.getDimensionPixelSize(
                    R.styleable.IFocusArea_bottomBoundOffset, INVALID_DIMEN);
            if (mBottomOffset == INVALID_DIMEN) {
                mBottomOffset = a.getDimensionPixelSize(
                        R.styleable.IFocusArea_verticalBoundOffset, mPaddingBottom);
            }

            // Handle new nudge shortcut attributes.
            if (a.hasValue(R.styleable.IFocusArea_nudgeLeftShortcut)) {
                mSpecifiedNudgeShortcutIdMap.put(FOCUS_LEFT,
                        a.getResourceId(R.styleable.IFocusArea_nudgeLeftShortcut, View.NO_ID));
            }
            if (a.hasValue(R.styleable.IFocusArea_nudgeRightShortcut)) {
                mSpecifiedNudgeShortcutIdMap.put(FOCUS_RIGHT,
                        a.getResourceId(R.styleable.IFocusArea_nudgeRightShortcut, View.NO_ID));
            }
            if (a.hasValue(R.styleable.IFocusArea_nudgeUpShortcut)) {
                mSpecifiedNudgeShortcutIdMap.put(FOCUS_UP,
                        a.getResourceId(R.styleable.IFocusArea_nudgeUpShortcut, View.NO_ID));
            }
            if (a.hasValue(R.styleable.IFocusArea_nudgeDownShortcut)) {
                mSpecifiedNudgeShortcutIdMap.put(FOCUS_DOWN,
                        a.getResourceId(R.styleable.IFocusArea_nudgeDownShortcut, View.NO_ID));
            }

            // Handle legacy nudge shortcut attributes.
            int nudgeShortcutId = a.getResourceId(R.styleable.IFocusArea_nudgeShortcut, View.NO_ID);
            int nudgeShortcutDirection = a.getInt(
                    R.styleable.IFocusArea_nudgeShortcutDirection, INVALID_DIRECTION);
            if ((nudgeShortcutId == View.NO_ID) ^ (nudgeShortcutDirection == INVALID_DIRECTION)) {
                throw new IllegalStateException("nudgeShortcut and nudgeShortcutDirection must "
                        + "be specified together");
            }
            if (nudgeShortcutId != View.NO_ID) {
                if (mSpecifiedNudgeShortcutIdMap.size() > 0) {
                    throw new IllegalStateException(
                            "Don't use nudgeShortcut/nudgeShortcutDirection and nudge*Shortcut in "
                                    + "the same focus area. Use nudge*Shortcut only.");
                }
                mSpecifiedNudgeShortcutIdMap.put(nudgeShortcutDirection, nudgeShortcutId);
            }

            // Handle nudge targets.
            if (a.hasValue(R.styleable.IFocusArea_nudgeLeft)) {
                mSpecifiedNudgeIdMap.put(FOCUS_LEFT,
                        a.getResourceId(R.styleable.IFocusArea_nudgeLeft, View.NO_ID));
            }
            if (a.hasValue(R.styleable.IFocusArea_nudgeRight)) {
                mSpecifiedNudgeIdMap.put(FOCUS_RIGHT,
                        a.getResourceId(R.styleable.IFocusArea_nudgeRight, View.NO_ID));
            }
            if (a.hasValue(R.styleable.IFocusArea_nudgeUp)) {
                mSpecifiedNudgeIdMap.put(FOCUS_UP,
                        a.getResourceId(R.styleable.IFocusArea_nudgeUp, View.NO_ID));
            }
            if (a.hasValue(R.styleable.IFocusArea_nudgeDown)) {
                mSpecifiedNudgeIdMap.put(FOCUS_DOWN,
                        a.getResourceId(R.styleable.IFocusArea_nudgeDown, View.NO_ID));
            }

            mDefaultFocusOverridesHistory = a.getBoolean(
                    R.styleable.IFocusArea_defaultFocusOverridesHistory, false);

            mWrapAround = a.getBoolean(R.styleable.IFocusArea_wrapAround, false);
        } finally {
            a.recycle();
        }
    }

    void onFinishInflate() {
        if (mDefaultFocusId != View.NO_ID) {
            mDefaultFocusView = mFocusArea.requireViewById(mDefaultFocusId);
        }
    }

    void onLayout() {
        boolean rtl = mFocusArea.getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        if (mRtl != rtl) {
            mRtl = rtl;

            int temp = mPaddingLeft;
            mPaddingLeft = mPaddingRight;
            mPaddingRight = temp;

            temp = mLeftOffset;
            mLeftOffset = mRightOffset;
            mRightOffset = temp;
        }
    }

    void onAttachedToWindow() {
        mFocusArea.getViewTreeObserver().addOnGlobalFocusChangeListener(mFocusChangeListener);

        // Disable restore focus behavior if mFocusArea is in a TaskView.
        if (mShouldRestoreFocus && ViewUtils.isInMultiWindowMode(mFocusArea)) {
            mShouldRestoreFocus = false;
        }
    }

    void onDetachedFromWindow() {
        mFocusArea.getViewTreeObserver().removeOnGlobalFocusChangeListener(mFocusChangeListener);
    }

    boolean onWindowFocusChanged(boolean hasWindowFocus) {
        // TODO(b/201700195): sometimes onWindowFocusChanged() won't be called when window focus
        //  has changed, so add the log for debugging.
        Log.d(TAG, "The window of Activity ["
                + ViewUtils.findActivity(mFocusArea.getContext())
                + (hasWindowFocus ? "] gained" : "] lost") + " focus");
        // To ensure the focus is initialized properly in rotary mode when there is a window focus
        // change, mFocusArea will grab the focus if nothing is focused or the currently
        // focused view's FocusLevel is lower than REGULAR_FOCUS.
        if (hasWindowFocus && mShouldRestoreFocus && !mFocusArea.isInTouchMode()) {
            maybeInitFocus();
            return true;
        }
        return false;
    }

    /**
     * Focuses on another view in {@link #mFocusArea} if nothing is focused or the currently focused
     * view's FocusLevel is lower than REGULAR_FOCUS.
     */
    private boolean maybeInitFocus() {
        View root = mFocusArea.getRootView();
        View focus = root.findFocus();
        return ViewUtils.initFocus(root, focus);
    }

    /**
     * Focuses on a view in {@link #mFocusArea} if the view is a better focus candidate than the
     * currently focused view.
     */
    private boolean maybeAdjustFocus() {
        View root = mFocusArea.getRootView();
        View focus = root.findFocus();
        return ViewUtils.adjustFocus(root, focus);
    }

    /** Whether the given {@code action} is custom action for {@link IFocusArea} subclasses. */
    boolean isFocusAreaAction(int action) {
        return FOCUS_AREA_ACTIONS.contains(action);
    }

    boolean performAccessibilityAction(int action, Bundle arguments) {
        switch (action) {
            case ACTION_FOCUS:
                // Repurpose ACTION_FOCUS to focus on mFocusArea's descendant. We can do this
                // because mFocusArea is not focusable and it didn't consume
                // ACTION_FOCUS previously.
                boolean success = focusOnDescendant();
                if (success && mPreviousFocusArea != null) {
                    int direction = getNudgeDirection(arguments);
                    if (direction != INVALID_DIRECTION) {
                        saveFocusAreaHistory(direction, mPreviousFocusArea,
                                (IFocusArea) mFocusArea, SystemClock.uptimeMillis());
                    }
                }
                return success;
            case ACTION_NUDGE_SHORTCUT:
                return nudgeToShortcutView(arguments);
            case ACTION_NUDGE_TO_ANOTHER_FOCUS_AREA:
                return nudgeToAnotherFocusArea(arguments);
            default:
                return false;
        }
    }

    private boolean focusOnDescendant() {
        View lastFocusedView = mRotaryCache.getFocusedView(SystemClock.uptimeMillis());
        return ViewUtils.adjustFocus(mFocusArea, lastFocusedView, mDefaultFocusOverridesHistory);
    }

    private boolean nudgeToShortcutView(Bundle arguments) {
        int direction = getNudgeDirection(arguments);
        View targetView = getSpecifiedShortcut(direction);
        if (targetView == null) {
            // No nudge shortcut configured for the given direction.
            return false;
        }
        if (targetView.isFocused()) {
            // The nudge shortcut view is already focused; return false so that the user can
            // nudge to another focus area.
            return false;
        }
        return ViewUtils.requestFocus(targetView);
    }

    private boolean nudgeToAnotherFocusArea(Bundle arguments) {
        int direction = getNudgeDirection(arguments);
        long elapsedRealtime = SystemClock.uptimeMillis();

        // Try to nudge to specified focus area, if any.
        IFocusArea targetFocusArea = getSpecifiedFocusArea(direction);
        boolean success =
                targetFocusArea != null && targetFocusArea.getHelper().focusOnDescendant();

        // If failed, try to nudge to cached focus area, if any.
        if (!success) {
            targetFocusArea = mRotaryCache.getCachedFocusArea(direction, elapsedRealtime);
            success = targetFocusArea != null && targetFocusArea.getHelper().focusOnDescendant();
        }

        return success;
    }

    private static int getNudgeDirection(Bundle arguments) {
        return arguments == null
                ? INVALID_DIRECTION
                : arguments.getInt(NUDGE_DIRECTION, INVALID_DIRECTION);
    }

    private void saveFocusAreaHistory(int direction, @NonNull IFocusArea sourceFocusArea,
            @NonNull IFocusArea targetFocusArea, long elapsedRealtime) {
        // Save one-way rather than two-way nudge history to avoid infinite nudge loop.
        FocusAreaHelper sourceHelper = sourceFocusArea.getHelper();
        if (sourceHelper.getCachedFocusArea(direction, elapsedRealtime) == null) {
            // Save reversed nudge history so that the users can nudge back to where they were.
            int oppositeDirection = getOppositeDirection(direction);
            FocusAreaHelper targetHelper = targetFocusArea.getHelper();
            targetHelper.saveFocusArea(oppositeDirection, sourceFocusArea, elapsedRealtime);
        }
    }

    @Nullable
    IFocusArea getCachedFocusArea(int direction, long elapsedRealtime) {
        return mRotaryCache.getCachedFocusArea(direction, elapsedRealtime);
    }

    /** Saves the focus area nudge history. */
    void saveFocusArea(int direction, @NonNull IFocusArea targetFocusArea, long elapsedRealtime) {
        mRotaryCache.saveFocusArea(direction, targetFocusArea, elapsedRealtime);
    }

    /** Returns the direction opposite the given {@code direction} */
    @VisibleForTesting
    private static int getOppositeDirection(int direction) {
        switch (direction) {
            case FOCUS_LEFT:
                return FOCUS_RIGHT;
            case FOCUS_RIGHT:
                return FOCUS_LEFT;
            case FOCUS_UP:
                return FOCUS_DOWN;
            case FOCUS_DOWN:
                return FOCUS_UP;
        }
        throw new IllegalArgumentException("direction must be "
                + "FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, or FOCUS_RIGHT.");
    }

    @Nullable
    private IFocusArea getSpecifiedFocusArea(int direction) {
        maybeInitializeSpecifiedFocusAreas();
        return mSpecifiedNudgeFocusAreaMap.get(direction);
    }

    @Nullable
    private View getSpecifiedShortcut(int direction) {
        maybeInitializeSpecifiedShortcuts();
        return mSpecifiedNudgeShortcutMap.get(direction);
    }

    void onDraw(Canvas canvas) {
        // Draw highlight on top of mFocusArea (including its background and content) but
        // behind its children.
        if (mEnableBackgroundHighlight && mHasFocus && !mFocusArea.isInTouchMode()) {
            mBackgroundHighlight.setBounds(
                    mPaddingLeft + mFocusArea.getScrollX(),
                    mPaddingTop + mFocusArea.getScrollY(),
                    mFocusArea.getScrollX() + mFocusArea.getWidth() - mPaddingRight,
                    mFocusArea.getScrollY() + mFocusArea.getHeight() - mPaddingBottom);
            mBackgroundHighlight.draw(canvas);
        }
    }

    void draw(Canvas canvas) {
        // Draw highlight on top of mFocusArea (including its background and content) and its
        // children (including background, content, focus highlight, etc).
        if (mEnableForegroundHighlight && mHasFocus && !mFocusArea.isInTouchMode()) {
            mForegroundHighlight.setBounds(
                    mPaddingLeft + mFocusArea.getScrollX(),
                    mPaddingTop + mFocusArea.getScrollY(),
                    mFocusArea.getScrollX() + mFocusArea.getWidth() - mPaddingRight,
                    mFocusArea.getScrollY() + mFocusArea.getHeight() - mPaddingBottom);
            mForegroundHighlight.draw(canvas);
        }
    }

    void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        Bundle bundle = info.getExtras();
        bundle.putInt(FOCUS_AREA_LEFT_BOUND_OFFSET, mLeftOffset);
        bundle.putInt(FOCUS_AREA_RIGHT_BOUND_OFFSET, mRightOffset);
        bundle.putInt(FOCUS_AREA_TOP_BOUND_OFFSET, mTopOffset);
        bundle.putInt(FOCUS_AREA_BOTTOM_BOUND_OFFSET, mBottomOffset);
    }

    boolean onRequestFocusInDescendants() {
        if (!mShouldRestoreFocus) {
            return false;
        }
        return maybeAdjustFocus();
    }

    boolean restoreDefaultFocus() {
        if (!mShouldRestoreFocus) {
            return false;
        }
        return maybeAdjustFocus();
    }

    private void maybeInitializeSpecifiedFocusAreas() {
        if (mSpecifiedNudgeFocusAreaMap != null) {
            return;
        }
        View root = mFocusArea.getRootView();
        mSpecifiedNudgeFocusAreaMap = new SparseArray<>();
        for (int direction : NUDGE_DIRECTIONS) {
            int id = mSpecifiedNudgeIdMap.get(direction, View.NO_ID);
            mSpecifiedNudgeFocusAreaMap.put(direction, root.findViewById(id));
        }
    }

    private void maybeInitializeSpecifiedShortcuts() {
        if (mSpecifiedNudgeShortcutMap != null) {
            return;
        }
        View root = mFocusArea.getRootView();
        mSpecifiedNudgeShortcutMap = new SparseArray<>();
        for (int direction : NUDGE_DIRECTIONS) {
            int id = mSpecifiedNudgeShortcutIdMap.get(direction, View.NO_ID);
            mSpecifiedNudgeShortcutMap.put(direction, root.findViewById(id));
        }
    }

    /** Gets the default focus view in {@link #mFocusArea}. */
    View getDefaultFocusView() {
        return mDefaultFocusView;
    }

    /** Sets the default focus view in {@link #mFocusArea}. */
    void setDefaultFocus(@NonNull View defaultFocus) {
        mDefaultFocusView = defaultFocus;
    }

    /**
     * Sets the padding (in pixels) of the focus area highlight.
     * <p>
     * It doesn't affect other values, such as the paddings on {@link #mFocusArea}'s child views.
     */
    void setHighlightPadding(int left, int top, int right, int bottom) {
        if (mPaddingLeft == left && mPaddingTop == top && mPaddingRight == right
                && mPaddingBottom == bottom) {
            return;
        }
        mPaddingLeft = left;
        mPaddingTop = top;
        mPaddingRight = right;
        mPaddingBottom = bottom;
        mFocusArea.invalidate();
    }

    /**
     * Sets the offset (in pixels) of {@link #mFocusArea}'s perceived bounds.
     * <p>
     * It only affects the perceived bounds for the purposes of finding the nudge target. It doesn't
     * affect {@link #mFocusArea}'s view bounds or highlight bounds. The offset should only be used
     * when focus areas are overlapping and nudge interaction is ambiguous.
     */
    void setBoundsOffset(int left, int top, int right, int bottom) {
        mLeftOffset = left;
        mTopOffset = top;
        mRightOffset = right;
        mBottomOffset = bottom;
    }

    /** Whether wrap-around is enabled for {@link #mFocusArea}. */
    boolean isWrapAround() {
        return mWrapAround;
    }

    /** Sets whether wrap-around is enabled for {@link #mFocusArea}. */
    void setWrapAround(boolean wrapAround) {
        mWrapAround = wrapAround;
    }

    /**
     * Sets the nudge shortcut for the given {@code direction}. Removes the nudge shortcut if
     * {@code view} is {@code null}.
     */
    void setNudgeShortcut(int direction, @Nullable View view) {
        if (!NUDGE_DIRECTIONS.contains(direction)) {
            throw new IllegalArgumentException("direction must be "
                    + "FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, or FOCUS_RIGHT.");
        }
        maybeInitializeSpecifiedShortcuts();
        if (view == null) {
            mSpecifiedNudgeShortcutMap.remove(direction);
        } else {
            mSpecifiedNudgeShortcutMap.put(direction, view);
        }
    }

    /**
     * Sets the nudge target focus area for the given {@code direction}. Removes the existing
     * target if {@code target} is {@code null}.
     */
    void setNudgeTargetFocusArea(int direction, @Nullable IFocusArea target) {
        if (!NUDGE_DIRECTIONS.contains(direction)) {
            throw new IllegalArgumentException("direction must be "
                    + "FOCUS_UP, FOCUS_DOWN, FOCUS_LEFT, or FOCUS_RIGHT.");
        }
        maybeInitializeSpecifiedFocusAreas();
        if (target == null) {
            mSpecifiedNudgeFocusAreaMap.remove(direction);
        } else {
            mSpecifiedNudgeFocusAreaMap.put(direction, target);
        }
    }

    void setDefaultFocusOverridesHistory(boolean override) {
        mDefaultFocusOverridesHistory = override;
    }

    @VisibleForTesting
    void enableForegroundHighlight() {
        mEnableForegroundHighlight = true;
    }

    @VisibleForTesting
    void setRotaryCache(@NonNull RotaryCache rotaryCache) {
        mRotaryCache = rotaryCache;
    }

    @VisibleForTesting
    void setClearFocusAreaHistoryWhenRotating(boolean clear) {
        mClearFocusAreaHistoryWhenRotating = clear;
    }
}
