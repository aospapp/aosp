/*
 * Copyright (C) 2012 Google Inc.
 * Licensed to The Android Open Source Project.
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

package com.android.mail.browse;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Paint.FontMetricsInt;
import android.graphics.RectF;
import android.text.Spanned;
import android.text.TextPaint;
import android.text.TextUtils;
import android.text.style.CharacterStyle;
import android.text.style.ReplacementSpan;

/**
 * A replacement span to use when displaying folders in conversation view. Prevents a folder name
 * from wrapping mid-name, and ellipsizes very long folder names that can't fit on a single line.
 * Also ensures that folder text is drawn vertically centered within the background color chip.
 *
 */
public class FolderSpan extends ReplacementSpan {

    public interface FolderSpanDimensions {

        /**
         * Returns the padding value used for the label inside of its background.
         */
        int getPadding();

        /**
         * Returns the padding value that corresponds to the horizontal padding
         * surrounding the text inside the background color.
         */
        int getPaddingExtraWidth();

        /**
         * Returns the padding value for the space between folders.
         */
        int getPaddingAfter();

        /**
         * Returns the maximum width the span is allowed to use.
         */
        int getMaxWidth();

        /**
         * Returns the radius to use for the rounded corners on the background rect.
         */
        float getRoundedCornerRadius();

        /**
         * Returns the size of the text.
         */
        float getFolderSpanTextSize();

        /**
         * Returns the margin above the span that should be used.
         * It is used to enable the appearance of line spacing.
         */
        int getMarginTop();

        /**
         * Returns {@link true} if the span should format itself in RTL mode.
         */
        boolean isRtl();
    }

    private final TextPaint mWorkPaint = new TextPaint();
    private final FontMetricsInt mFontMetrics = new FontMetricsInt();

    /**
     * A reference to the enclosing Spanned object to collect other CharacterStyle spans and take
     * them into account when drawing.
     */
    private final Spanned mSpanned;
    private final FolderSpanDimensions mDims;

    public FolderSpan(Spanned spanned, FolderSpanDimensions dims) {
        mSpanned = spanned;
        mDims = dims;
    }

    @Override
    public int getSize(Paint paint, CharSequence text, int start, int end, FontMetricsInt fm) {
        setupFontMetrics(start, end, fm, paint);
        if (fm != null) {
            final int padding = mDims.getPadding();
            final int margin = mDims.getMarginTop();
            fm.ascent = Math.min(fm.top, fm.ascent - padding) - margin;
            fm.descent = Math.max(fm.bottom, padding);
            fm.top = fm.ascent;
            fm.bottom = fm.descent;
        }
        return measureWidth(mWorkPaint, text, start, end, true);
    }

    private int measureWidth(Paint paint, CharSequence text, int start, int end,
            boolean includePaddingAfter) {
        final int paddingW = mDims.getPadding() + mDims.getPaddingExtraWidth();
        final int maxWidth = mDims.getMaxWidth();

        int w = (int) paint.measureText(text, start, end) + 2 * paddingW;
        if (includePaddingAfter) {
            w += mDims.getPaddingAfter();
        }
        // TextView doesn't handle spans that are wider than the view very well, so cap their widths
        if (w > maxWidth) {
            w = maxWidth;
        }
        return w;
    }

    private void setupFontMetrics(int start, int end, FontMetricsInt fm, Paint p) {
        mWorkPaint.set(p);
        // take into account the style spans when painting
        final CharacterStyle[] otherSpans = mSpanned.getSpans(start, end, CharacterStyle.class);
        for (CharacterStyle otherSpan : otherSpans) {
            otherSpan.updateDrawState(mWorkPaint);
        }
        mWorkPaint.setTextSize(mDims.getFolderSpanTextSize());
        if (fm != null) {
            mWorkPaint.getFontMetricsInt(fm);
        }
    }

    @Override
    public void draw(Canvas canvas, CharSequence text, int start, int end, float x, int top,
            int y, int bottom, Paint paint) {

        final int padding = mDims.getPadding();
        final int paddingW = padding + mDims.getPaddingExtraWidth();
        final int maxWidth = mDims.getMaxWidth();

        setupFontMetrics(start, end, mFontMetrics, paint);
        final int bgWidth = measureWidth(mWorkPaint, text, start, end, false);
        mFontMetrics.top = Math.min(mFontMetrics.top, mFontMetrics.ascent - padding);
        mFontMetrics.bottom = Math.max(mFontMetrics.bottom, padding);
        top = y + mFontMetrics.top - mFontMetrics.bottom;
        bottom = y;
        y =  bottom - mFontMetrics.bottom;

        final boolean isRtl = mDims.isRtl();
        final int paddingAfter = mDims.getPaddingAfter();
        // paint a background if present
        if (mWorkPaint.bgColor != 0) {
            final int prevColor = mWorkPaint.getColor();
            final Paint.Style prevStyle = mWorkPaint.getStyle();

            mWorkPaint.setColor(mWorkPaint.bgColor);
            mWorkPaint.setStyle(Paint.Style.FILL);
            final float left;
            if (isRtl) {
                left = x + paddingAfter;
            } else {
                left = x;
            }
            final float right = left + bgWidth;
            final RectF rect = new RectF(left, top, right, bottom);
            final float radius = mDims.getRoundedCornerRadius();
            canvas.drawRoundRect(rect, radius, radius, mWorkPaint);

            mWorkPaint.setColor(prevColor);
            mWorkPaint.setStyle(prevStyle);
        }

        // middle-ellipsize long strings
        if (bgWidth == maxWidth) {
            text = TextUtils.ellipsize(text.subSequence(start, end).toString(), mWorkPaint,
                    bgWidth - 2 * paddingW, TextUtils.TruncateAt.MIDDLE);
            start = 0;
            end = text.length();
        }
        float textX = x + paddingW;
        if (isRtl) {
            textX += paddingAfter;
        }
        canvas.drawText(text, start, end, textX, y, mWorkPaint);
    }

}
