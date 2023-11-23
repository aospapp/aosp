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

package com.android.car.rotaryplayground;

import static com.android.car.ui.utils.RotaryConstants.BOTTOM_BOUND_OFFSET_FOR_NUDGE;
import static com.android.car.ui.utils.RotaryConstants.LEFT_BOUND_OFFSET_FOR_NUDGE;
import static com.android.car.ui.utils.RotaryConstants.RIGHT_BOUND_OFFSET_FOR_NUDGE;
import static com.android.car.ui.utils.RotaryConstants.TOP_BOUND_OFFSET_FOR_NUDGE;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.os.Bundle;
import android.util.AttributeSet;
import android.view.SurfaceView;
import android.view.accessibility.AccessibilityNodeInfo;

import androidx.annotation.Nullable;

/**
 * A SurfaceView that allows to set its perceived bounds for the purposes of finding the nudge
 * target.
 */
public class CustomSurfaceView extends SurfaceView {

    /**
     * The offset (in pixels) of the SurfaceView's bounds for the purposes of finding the nudge
     * target.
     */
    private int mLeftOffset;
    private int mRightOffset;
    private int mTopOffset;
    private int mBottomOffset;

    public CustomSurfaceView(Context context) {
        super(context);
    }

    public CustomSurfaceView(Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
    }

    public CustomSurfaceView(Context context, @Nullable AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
    }

    public CustomSurfaceView(Context context, @Nullable AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        super(context, attrs, defStyleAttr, defStyleRes);
    }

    /**
     * Sets the offset (in pixels) of the SurfaceView's bounds.
     * <p>
     * It only affects the perceived bounds for the purposes of finding the nudge target.
     * This doesn't affect the view's bounds. This method should only be used when this view
     * overlaps other focusable views so that nudge targeting is ambiguous.
     */
    public void setBoundsOffset(int left, int top, int right, int bottom) {
        mLeftOffset = left;
        mTopOffset = top;
        mRightOffset = right;
        mBottomOffset = bottom;
    }

    @Override
    public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        if (mLeftOffset == 0 && mTopOffset == 0 && mRightOffset == 0 && mBottomOffset == 0) {
            return;
        }
        Bundle bundle = info.getExtras();
        bundle.putInt(LEFT_BOUND_OFFSET_FOR_NUDGE, mLeftOffset);
        bundle.putInt(RIGHT_BOUND_OFFSET_FOR_NUDGE, mRightOffset);
        bundle.putInt(TOP_BOUND_OFFSET_FOR_NUDGE, mTopOffset);
        bundle.putInt(BOTTOM_BOUND_OFFSET_FOR_NUDGE, mBottomOffset);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);

        // Draw a text for demonstration purpose.
        Paint paint = new Paint();
        paint.setColor(Color.BLACK);
        paint.setTextSize(40);
        canvas.drawText("SurfaceView", 400, 200, paint);
    }
}
