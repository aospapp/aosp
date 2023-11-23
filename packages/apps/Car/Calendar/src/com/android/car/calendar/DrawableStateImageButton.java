/*
 * Copyright 2020 The Android Open Source Project
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

package com.android.car.calendar;

import android.content.Context;
import android.util.AttributeSet;
import android.widget.ImageButton;

import androidx.annotation.Nullable;

import com.android.car.ui.uxr.DrawableStateView;

/**
 * An {@link ImageButton} that implements {@link DrawableStateView}, for allowing additional states
 * such as ux restriction.
 *
 * @see com.android.car.ui.uxr.DrawableStateButton
 *
 * TODO(jdp) Move this to car-ui-lib.
 */
public class DrawableStateImageButton extends ImageButton implements DrawableStateView {

    private int[] mState;

    public DrawableStateImageButton(Context context) {
        super(context);
    }

    public DrawableStateImageButton(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public DrawableStateImageButton(
            Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public DrawableStateImageButton(
            Context context, @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public void setDrawableState(int[] state) {
        mState = state;
        refreshDrawableState();
    }

    @Override
    public int[] onCreateDrawableState(int extraSpace) {
        if (mState == null) {
            return super.onCreateDrawableState(extraSpace);
        } else {
            return mergeDrawableStates(
                    super.onCreateDrawableState(extraSpace + mState.length), mState);
        }
    }
}
