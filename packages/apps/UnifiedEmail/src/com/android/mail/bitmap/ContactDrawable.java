/*
 * Copyright (C) 2013 The Android Open Source Project
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
package com.android.mail.bitmap;

import android.content.res.Resources;
import android.content.res.TypedArray;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.Paint.Align;
import android.graphics.Rect;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;

import com.android.bitmap.BitmapCache;
import com.android.bitmap.RequestKey;
import com.android.bitmap.ReusableBitmap;
import com.android.mail.R;
import com.android.mail.bitmap.ContactResolver.ContactDrawableInterface;

/**
 * A drawable that encapsulates all the functionality needed to display a contact image,
 * including request creation/cancelling and data unbinding/re-binding. While no contact images
 * can be shown, a default letter tile will be shown instead.
 *
 * <p/>
 * The actual contact resolving and decoding is handled by {@link ContactResolver}.
 */
public class ContactDrawable extends Drawable implements ContactDrawableInterface {

    private BitmapCache mCache;
    private ContactResolver mContactResolver;

    private ContactRequest mContactRequest;
    private ReusableBitmap mBitmap;
    private final Paint mPaint;

    /** Letter tile */
    private static TypedArray sColors;
    private static int sColorCount;
    private static int sDefaultColor;
    private static int sTileLetterFontSize;
    private static int sTileFontColor;
    private static Bitmap DEFAULT_AVATAR;
    /** Reusable components to avoid new allocations */
    private static final Paint sPaint = new Paint();
    private static final Rect sRect = new Rect();
    private static final char[] sFirstChar = new char[1];

    private final float mBorderWidth;
    private final Paint mBitmapPaint;
    private final Paint mBorderPaint;
    private final Matrix mMatrix;

    private int mDecodeWidth;
    private int mDecodeHeight;

    public ContactDrawable(final Resources res) {
        mPaint = new Paint();
        mPaint.setFilterBitmap(true);
        mPaint.setDither(true);

        mBitmapPaint = new Paint();
        mBitmapPaint.setAntiAlias(true);
        mBitmapPaint.setFilterBitmap(true);
        mBitmapPaint.setDither(true);

        mBorderWidth = res.getDimensionPixelSize(R.dimen.avatar_border_width);

        mBorderPaint = new Paint();
        mBorderPaint.setColor(Color.TRANSPARENT);
        mBorderPaint.setStyle(Paint.Style.STROKE);
        mBorderPaint.setStrokeWidth(mBorderWidth);
        mBorderPaint.setAntiAlias(true);

        mMatrix = new Matrix();

        if (sColors == null) {
            sColors = res.obtainTypedArray(R.array.letter_tile_colors);
            sColorCount = sColors.length();
            sDefaultColor = res.getColor(R.color.letter_tile_default_color);
            sTileLetterFontSize = res.getDimensionPixelSize(R.dimen.tile_letter_font_size);
            sTileFontColor = res.getColor(R.color.letter_tile_font_color);
            DEFAULT_AVATAR = BitmapFactory.decodeResource(res, R.drawable.ic_generic_man);

            sPaint.setTypeface(Typeface.create("sans-serif-light", Typeface.NORMAL));
            sPaint.setTextAlign(Align.CENTER);
            sPaint.setAntiAlias(true);
        }
    }

    public void setBitmapCache(final BitmapCache cache) {
        mCache = cache;
    }

    public void setContactResolver(final ContactResolver contactResolver) {
        mContactResolver = contactResolver;
    }

    @Override
    public void draw(final Canvas canvas) {
        final Rect bounds = getBounds();
        if (!isVisible() || bounds.isEmpty()) {
            return;
        }

        if (mBitmap != null && mBitmap.bmp != null) {
            // Draw sender image.
            drawBitmap(mBitmap.bmp, mBitmap.getLogicalWidth(), mBitmap.getLogicalHeight(), canvas);
        } else {
            // Draw letter tile.
            drawLetterTile(canvas);
        }
    }

    /**
     * Draw the bitmap onto the canvas at the current bounds taking into account the current scale.
     */
    private void drawBitmap(final Bitmap bitmap, final int width, final int height,
            final Canvas canvas) {
        final Rect bounds = getBounds();
        // Draw bitmap through shader first.
        final BitmapShader shader = new BitmapShader(bitmap, Shader.TileMode.CLAMP,
                Shader.TileMode.CLAMP);
        mMatrix.reset();

        // Fit bitmap to bounds.
        final float boundsWidth = (float) bounds.width();
        final float boundsHeight = (float) bounds.height();
        final float scale = Math.max(boundsWidth / width, boundsHeight / height);
        mMatrix.postScale(scale, scale);

        // Translate bitmap to dst bounds.
        mMatrix.postTranslate(bounds.left, bounds.top);

        shader.setLocalMatrix(mMatrix);
        mBitmapPaint.setShader(shader);
        drawCircle(canvas, bounds, mBitmapPaint);

        // Then draw the border.
        final float radius = bounds.width() / 2f - mBorderWidth / 2;
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), radius, mBorderPaint);
    }

    private void drawLetterTile(final Canvas canvas) {
        if (mContactRequest == null) {
            return;
        }

        final Rect bounds = getBounds();

        // Draw background color.
        final String email = mContactRequest.getEmail();
        sPaint.setColor(pickColor(email));
        sPaint.setAlpha(mPaint.getAlpha());
        drawCircle(canvas, bounds, sPaint);

        // Draw letter/digit or generic avatar.
        final String displayName = mContactRequest.getDisplayName();
        final char firstChar = displayName.charAt(0);
        if (isEnglishLetterOrDigit(firstChar)) {
            // Draw letter or digit.
            sFirstChar[0] = Character.toUpperCase(firstChar);
            sPaint.setTextSize(sTileLetterFontSize);
            sPaint.getTextBounds(sFirstChar, 0, 1, sRect);
            sPaint.setColor(sTileFontColor);
            canvas.drawText(sFirstChar, 0, 1, bounds.centerX(),
                    bounds.centerY() + sRect.height() / 2, sPaint);
        } else {
            drawBitmap(DEFAULT_AVATAR, DEFAULT_AVATAR.getWidth(), DEFAULT_AVATAR.getHeight(),
                    canvas);
        }
    }

    /**
     * Draws the largest circle that fits within the given <code>bounds</code>.
     *
     * @param canvas the canvas on which to draw
     * @param bounds the bounding box of the circle
     * @param paint the paint with which to draw
     */
    private static void drawCircle(Canvas canvas, Rect bounds, Paint paint) {
        canvas.drawCircle(bounds.centerX(), bounds.centerY(), bounds.width() / 2, paint);
    }

    private static int pickColor(final String email) {
        // String.hashCode() implementation is not supposed to change across java versions, so
        // this should guarantee the same email address always maps to the same color.
        // The email should already have been normalized by the ContactRequest.
        final int color = Math.abs(email.hashCode()) % sColorCount;
        return sColors.getColor(color, sDefaultColor);
    }

    private static boolean isEnglishLetterOrDigit(final char c) {
        return ('A' <= c && c <= 'Z') || ('a' <= c && c <= 'z') || ('0' <= c && c <= '9');
    }

    @Override
    public void setAlpha(final int alpha) {
        mPaint.setAlpha(alpha);
    }

    @Override
    public void setColorFilter(final ColorFilter cf) {
        mPaint.setColorFilter(cf);
    }

    @Override
    public int getOpacity() {
        return 0;
    }

    public void setDecodeDimensions(final int decodeWidth, final int decodeHeight) {
        mDecodeWidth = decodeWidth;
        mDecodeHeight = decodeHeight;
    }

    public void unbind() {
        setImage(null);
    }

    public void bind(final String name, final String email) {
        setImage(new ContactRequest(name, email));
    }

    private void setImage(final ContactRequest contactRequest) {
        if (mContactRequest != null && mContactRequest.equals(contactRequest)) {
            return;
        }

        if (mBitmap != null) {
            mBitmap.releaseReference();
            mBitmap = null;
        }

        mContactResolver.remove(mContactRequest, this);
        mContactRequest = contactRequest;

        if (contactRequest == null) {
            invalidateSelf();
            return;
        }

        final ReusableBitmap cached = mCache.get(contactRequest, true /* incrementRefCount */);
        if (cached != null) {
            setBitmap(cached);
        } else {
            decode();
        }
    }

    private void setBitmap(final ReusableBitmap bmp) {
        if (mBitmap != null && mBitmap != bmp) {
            mBitmap.releaseReference();
        }
        mBitmap = bmp;
        invalidateSelf();
    }

    private void decode() {
        if (mContactRequest == null) {
            return;
        }
        // Add to batch.
        mContactResolver.add(mContactRequest, this);
    }

    @Override
    public int getDecodeWidth() {
        return mDecodeWidth;
    }

    @Override
    public int getDecodeHeight() {
        return mDecodeHeight;
    }

    @Override
    public void onDecodeComplete(final RequestKey key, final ReusableBitmap result) {
        final ContactRequest request = (ContactRequest) key;
        // Remove from batch.
        mContactResolver.remove(request, this);
        if (request.equals(mContactRequest)) {
            setBitmap(result);
        } else {
            // if the requests don't match (i.e. this request is stale), decrement the
            // ref count to allow the bitmap to be pooled
            if (result != null) {
                result.releaseReference();
            }
        }
    }
}
