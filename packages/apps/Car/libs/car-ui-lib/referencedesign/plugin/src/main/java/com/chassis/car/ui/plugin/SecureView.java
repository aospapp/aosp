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
package com.chassis.car.ui.plugin;

import android.content.Context;
import android.os.Build;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import androidx.annotation.Nullable;

/**
 * A view that cannot be clicked on when the window is obscured.
 */
public class SecureView extends View {

    private boolean mSecure = true;

    public SecureView(Context context) {
        super(context);
    }

    public SecureView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public SecureView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public SecureView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    @Override
    public boolean onFilterTouchEventForSecurity(MotionEvent event) {
        if (!mSecure) {
            return super.onFilterTouchEventForSecurity(event);
        }

        int flags = MotionEvent.FLAG_WINDOW_IS_OBSCURED;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            flags |= MotionEvent.FLAG_WINDOW_IS_PARTIALLY_OBSCURED;
        }

        return (event.getFlags() & flags) == 0 && super.onFilterTouchEventForSecurity(event);
    }

    /** Sets if this view should ignore touch events when the window is obscured. Default true. */
    public void setSecure(boolean secure) {
        mSecure = secure;
    }

    /** Returns if this view should ignore touch events when the window is obscured. */
    public boolean isSecure() {
        return mSecure;
    }
}
