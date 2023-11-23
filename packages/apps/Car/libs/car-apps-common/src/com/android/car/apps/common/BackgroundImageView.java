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
package com.android.car.apps.common;

import android.content.Context;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.util.AttributeSet;
import android.view.View;

import androidx.annotation.Nullable;
import androidx.constraintlayout.widget.ConstraintLayout;

/**
 * A View to place a large, blurred image in the background.
 * Intended for Car's Dialer and Media apps.
 */
public class BackgroundImageView extends ConstraintLayout {

    private CrossfadeImageView mImageView;

    /** Configuration (controlled from resources) */
    private int mBitmapTargetSize;
    private float mBitmapBlurPercent;

    private View mDarkeningScrim;

    public BackgroundImageView(Context context) {
        this(context, null);
    }

    public BackgroundImageView(Context context, AttributeSet attrs) {
        this(context, attrs, R.attr.backgroundImageViewStyle);
    }

    public BackgroundImageView(Context context, AttributeSet attrs, int defStyle) {
        super(context, attrs, defStyle);

        inflate(getContext(), R.layout.background_image, this);

        mImageView = findViewById(R.id.background_image_image);
        mDarkeningScrim = findViewById(R.id.background_image_darkening_scrim);

        mBitmapTargetSize = getResources().getInteger(R.integer.background_bitmap_target_size_px);
        mBitmapBlurPercent = getResources().getFloat(R.dimen.background_bitmap_blur_percent);

        TypedArray a = context.getTheme().obtainStyledAttributes(attrs,
                R.styleable.BackgroundImageView, defStyle, 0);

        try {
            setImageAdditionalScale(a.getFloat(R.styleable.BackgroundImageView_imageAdditionalScale,
                    1.05f));
        } finally {
            a.recycle();
        }
    }

    /**
     * Sets the image to display to a bitmap
     * @param bitmap The image to show. It will be scaled to the correct size and blurred.
     * @param showAnimation Whether or not to cross fade to the new image
     */
    public void setBackgroundImage(@Nullable Bitmap bitmap, boolean showAnimation) {
        if (bitmap == null) {
            mImageView.setImageBitmap(null, false);
            return;
        }

        bitmap = ImageUtils.blur(getContext(), bitmap, mBitmapTargetSize, mBitmapBlurPercent);
        mImageView.setImageBitmap(bitmap, showAnimation);

        invalidate();
        requestLayout();
    }

    /** Sets the background to a color */
    public void setBackgroundColor(int color) {
        mImageView.setBackgroundColor(color);
    }

    /**
     * Gets the desired size for an image to pass to {@link #setBackgroundImage}. That size is
     * defined by R.integer.background_bitmap_target_size_px.
     */
    public int getDesiredBackgroundSize() {
        return mBitmapTargetSize;
    }

    /** Dims/undims the background image by 30% */
    public void setDimmed(boolean dim) {
        mDarkeningScrim.setVisibility(dim ? View.VISIBLE : View.GONE);
    }

    /**
     * Sets a scale to be applied on top of the scaling that was used to fit the
     * image to the frame of the view.
     *
     * See {@link
     * com.android.car.apps.common.CropAlignedImageView#setImageAdditionalScale(float)}
     * for more details.
     */
    public void setImageAdditionalScale(float scale) {
        mImageView.setImageAdditionalScale(scale);
    }
}
