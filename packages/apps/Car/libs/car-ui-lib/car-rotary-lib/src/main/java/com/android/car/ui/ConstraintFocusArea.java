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

import static com.android.car.ui.utils.RotaryConstants.I_FOCUS_AREA_CLASS_NAME;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.FocusFinder;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.annotation.VisibleForTesting;
import androidx.constraintlayout.widget.ConstraintLayout;

/**
 * A {@link ConstraintLayout} used as a navigation block for the rotary controller.
 * <p>
 * When creating a navigation block in the layout file, if you intend to use a ConstraintLayout as a
 * container for that block, just use a ConstraintFocusArea instead; otherwise use other
 * {@link IFocusArea} implementations, such as {@link FocusArea} which extends
 * {@link android.widget.LinearLayout}.
 * <p>
 * DO NOT nest an IFocusArea inside another IFocusArea because it will result in undefined
 * navigation behavior.
 */
public class ConstraintFocusArea extends ConstraintLayout implements IFocusArea {

    @NonNull
    private final FocusAreaHelper mFocusAreaHelper;

    public ConstraintFocusArea(Context context) {
        this(context, null);
    }

    public ConstraintFocusArea(Context context, @Nullable AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public ConstraintFocusArea(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public ConstraintFocusArea(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        mFocusAreaHelper = new FocusAreaHelper(this, attrs);
    }

    @Override
    protected void onFinishInflate() {
        super.onFinishInflate();
        mFocusAreaHelper.onFinishInflate();
    }

    @Override
    protected void onLayout(boolean changed, int left, int top, int right, int bottom) {
        super.onLayout(changed, left, top, right, bottom);
        mFocusAreaHelper.onLayout();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        mFocusAreaHelper.onAttachedToWindow();
    }

    @Override
    protected void onDetachedFromWindow() {
        mFocusAreaHelper.onDetachedFromWindow();
        super.onDetachedFromWindow();
    }

    @Override
    public void onWindowFocusChanged(boolean hasWindowFocus) {
        if (!mFocusAreaHelper.onWindowFocusChanged(hasWindowFocus())) {
            super.onWindowFocusChanged(hasWindowFocus);
        }
    }

    @Override
    public boolean performAccessibilityAction(int action, Bundle arguments) {
        if (mFocusAreaHelper.isFocusAreaAction(action)) {
            return mFocusAreaHelper.performAccessibilityAction(action, arguments);
        }
        return super.performAccessibilityAction(action, arguments);
    }

    @Override
    public void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        mFocusAreaHelper.onDraw(canvas);
    }

    @Override
    public void draw(Canvas canvas) {
        super.draw(canvas);
        mFocusAreaHelper.draw(canvas);
    }

    @Override
    public CharSequence getAccessibilityClassName() {
        return I_FOCUS_AREA_CLASS_NAME;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        mFocusAreaHelper.onInitializeAccessibilityNodeInfo(info);
    }

    @Override
    protected boolean onRequestFocusInDescendants(int direction, Rect previouslyFocusedRect) {
        if (isInTouchMode()) {
            return super.onRequestFocusInDescendants(direction, previouslyFocusedRect);
        }
        return mFocusAreaHelper.onRequestFocusInDescendants();
    }

    @Override
    public boolean restoreDefaultFocus() {
        return mFocusAreaHelper.restoreDefaultFocus();
    }

    /**
     * @inheritDoc
     * <p>
     * When wrap-around is allowed, the search is restricted to descendants of this
     * {@link ConstraintFocusArea}.
     */
    @Override
    public View focusSearch(View focused, int direction) {
        if (mFocusAreaHelper.isWrapAround()) {
            return FocusFinder.getInstance().findNextFocus(/* root= */ this, focused, direction);
        }
        return super.focusSearch(focused, direction);
    }

    @VisibleForTesting
    @Override
    @NonNull
    public FocusAreaHelper getHelper() {
        return mFocusAreaHelper;
    }

    @Override
    public View getDefaultFocusView() {
        return mFocusAreaHelper.getDefaultFocusView();
    }

    @Override
    public void setDefaultFocus(@NonNull View defaultFocus) {
        mFocusAreaHelper.setDefaultFocus(defaultFocus);
    }

    @Override
    public void setHighlightPadding(int left, int top, int right, int bottom) {
        mFocusAreaHelper.setHighlightPadding(left, top, right, bottom);
    }

    @Override
    public void setBoundsOffset(int left, int top, int right, int bottom) {
        mFocusAreaHelper.setBoundsOffset(left, top, right, bottom);
    }

    @Override
    public void setWrapAround(boolean wrapAround) {
        mFocusAreaHelper.setWrapAround(wrapAround);
    }

    @Override
    public void setNudgeShortcut(int direction, @Nullable View view) {
        mFocusAreaHelper.setNudgeShortcut(direction, view);
    }

    @Override
    public void setNudgeTargetFocusArea(int direction, @Nullable IFocusArea target) {
        mFocusAreaHelper.setNudgeTargetFocusArea(direction, target);
    }

    @Override
    public void setDefaultFocusOverridesHistory(boolean override) {
        mFocusAreaHelper.setDefaultFocusOverridesHistory(override);
    }
}
