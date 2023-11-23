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

import android.content.Context;
import android.util.AttributeSet;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;

import com.android.car.ui.utils.ViewUtils;

/**
 * A container for hierarchical rotary navigation.
 * <p>
 * Use a NestedContainer in your layout when you want to have the entire container act as a single
 * focusable view until the user chooses to enter it. For example, a long list where each element
 * has many secondary actions can be tedious to scroll through. Wrapping each element in a
 * NestedContainer makes it faster to scroll. If your list is short or has few secondary actions, a
 * NestedContainer is probably not the best choice because it requires the user to explicitly enter
 * (via the center button) and exit (via the Back button).
 */
public class NestedContainer extends FrameLayout {
    /** Whether the user is navigating within this container. */
    private boolean mFocusDescendant;

    /** Focus change listener to exit this container when focus isn't within it. */
    private final ViewTreeObserver.OnGlobalFocusChangeListener mFocusChangeListener =
            (oldFocus, newFocus) -> {
                if (mFocusDescendant && (isFocused() || !hasFocus())) {
                    mFocusDescendant = false;
                    setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
                }
            };

    public NestedContainer(Context context) {
        super(context);
        init();
    }

    public NestedContainer(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public NestedContainer(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    public NestedContainer(Context context, AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
        init();
    }

    private void init() {
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setFocusable(true);
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        getViewTreeObserver().addOnGlobalFocusChangeListener(mFocusChangeListener);
    }

    @Override
    protected void onDetachedFromWindow() {
        mFocusDescendant = false;
        getViewTreeObserver().removeOnGlobalFocusChangeListener(mFocusChangeListener);
        super.onDetachedFromWindow();
    }

    @Override
    public boolean dispatchKeyEvent(KeyEvent event) {
        if (super.dispatchKeyEvent(event)) return true;

        if (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_CENTER && !mFocusDescendant) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                setDescendantFocusability(FOCUS_AFTER_DESCENDANTS);
                if (ViewUtils.focusOnFirstRegularView(this)) {
                    mFocusDescendant = true;
                } else {
                    // We failed to focus a descendant so abort our attempt to enter this container.
                    setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
                }
            }
            return true;
        }

        if (event.getKeyCode() == KeyEvent.KEYCODE_BACK && mFocusDescendant) {
            if (event.getAction() == KeyEvent.ACTION_UP) {
                setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
                requestFocus();
                mFocusDescendant = false;
            }
            return true;
        }

        return false;
    }

    /**
     * @inheritDoc
     * <p>
     * When {@link #mFocusDescendant} is true, the search is restricted to descendants of this
     * {@link NestedContainer}.
     */
    @Override
    public View focusSearch(View focused, int direction) {
        View result = super.focusSearch(focused, direction);
        if (mFocusDescendant && !ViewUtils.isDescendant(result, this)) {
            result = null;
        }
        return result;
    }
}
