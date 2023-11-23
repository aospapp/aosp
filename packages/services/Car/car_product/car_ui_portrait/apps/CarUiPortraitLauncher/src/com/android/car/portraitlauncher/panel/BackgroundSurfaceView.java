/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.portraitlauncher.panel;

import android.content.Context;
import android.content.res.Resources;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.portraitlauncher.R;

/**
 * A {@link SurfaceView} that acts as a solid black background of {@link TaskViewPanel}. It
 * avoids user to see the background app when an activity in RootTaskView fades out.
 */
public class BackgroundSurfaceView extends SurfaceView {

    // Whether the panel should use a fixed color.
    private boolean mUseFixedColor;

    // The color used in the surface view.
    private int mColor;

    public BackgroundSurfaceView(Context context) {
        this(context, null);
    }

    public BackgroundSurfaceView(Context context, AttributeSet attrs) {
        this(context, attrs, 0);
    }

    public BackgroundSurfaceView(Context context, AttributeSet attrs, int defStyleAttr) {
        this(context, attrs, defStyleAttr, 0);
    }

    public BackgroundSurfaceView(Context context, AttributeSet attrs, int defStyleAttr,
            int defStyleRes) {
        this(context, attrs, defStyleAttr, defStyleRes, false);
    }

    public BackgroundSurfaceView(@NonNull Context context,
            @Nullable AttributeSet attrs, int defStyleAttr, int defStyleRes,
            boolean disableBackgroundLayer) {
        super(context, attrs, defStyleAttr, defStyleRes, disableBackgroundLayer);
        setZOrderMediaOverlay(true);
        setupSurfaceView();
    }

    private void setupSurfaceView() {
        mColor = getResources().getColor(R.color.car_background, getContext().getTheme());

        getHolder().addCallback(new SurfaceHolder.Callback() {
            @Override
            public void surfaceCreated(@NonNull SurfaceHolder holder) {
                drawColorOnSurface(holder);
            }

            @Override
            public void surfaceChanged(@NonNull SurfaceHolder holder, int format, int width,
                    int height) {
                drawColorOnSurface(holder);
            }

            @Override
            public void surfaceDestroyed(@NonNull SurfaceHolder holder) {

            }
        });
    }

    private void drawColorOnSurface(SurfaceHolder holder) {
        Canvas canvas = holder.lockCanvas();
        if (canvas == null) {
            return;
        }
        canvas.drawColor(mColor);
        holder.unlockCanvasAndPost(canvas);
    }

    /** Sets the fixed color on the surface view */
    public void setFixedColor(int color) {
        mColor = color;
        mUseFixedColor = true;
        drawColorOnSurface(getHolder());
    }

    /** refreshes the color of the surface view if needed. */
    public void refresh(Resources.Theme theme) {
        if (!mUseFixedColor) {
            mColor = getResources().getColor(R.color.car_background, theme);
            drawColorOnSurface(getHolder());
        }
    }
}
