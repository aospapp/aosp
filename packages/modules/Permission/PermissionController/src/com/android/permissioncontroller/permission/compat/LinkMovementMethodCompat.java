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

package com.android.permissioncontroller.permission.compat;

import android.text.Layout;
import android.text.Selection;
import android.text.Spannable;
import android.text.method.LinkMovementMethod;
import android.text.method.Touch;
import android.view.MotionEvent;
import android.widget.TextView;

import androidx.annotation.NonNull;

/**
 * Fixes the issue that links can be triggered for touches outside of line bounds for
 * {@link LinkMovementMethod}.
 * <p>
 * This is based on the fix in ag/22301465.
 */
public class LinkMovementMethodCompat extends LinkMovementMethod {
    @Override
    public boolean onTouchEvent(@NonNull TextView widget, @NonNull Spannable buffer,
            @NonNull MotionEvent event) {
        int action = event.getAction();

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_DOWN) {
            int x = (int) event.getX();
            int y = (int) event.getY();

            x -= widget.getTotalPaddingLeft();
            y -= widget.getTotalPaddingTop();

            x += widget.getScrollX();
            y += widget.getScrollY();

            Layout layout = widget.getLayout();
            boolean isOutOfLineBounds;
            if (y < 0 || y > layout.getHeight()) {
                isOutOfLineBounds = true;
            } else {
                int line = layout.getLineForVertical(y);
                isOutOfLineBounds = x < layout.getLineLeft(line) || x > layout.getLineRight(line);
            }

            if (isOutOfLineBounds) {
                Selection.removeSelection(buffer);

                // return LinkMovementMethod.super.onTouchEvent(widget, buffer, event);
                return Touch.onTouchEvent(widget, buffer, event);
            }
        }

        return super.onTouchEvent(widget, buffer, event);
    }

    /**
     * @return a {@link LinkMovementMethodCompat} instance
     */
    @NonNull
    public static LinkMovementMethodCompat getInstance() {
        if (sInstance == null) {
            sInstance = new LinkMovementMethodCompat();
        }

        return sInstance;
    }

    private static LinkMovementMethodCompat sInstance;
}
