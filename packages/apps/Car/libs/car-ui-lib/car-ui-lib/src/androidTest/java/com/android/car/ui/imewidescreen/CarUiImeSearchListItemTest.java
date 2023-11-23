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

package com.android.car.ui.imewidescreen;

import static androidx.test.espresso.matcher.ViewMatchers.assertThat;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertThrows;

import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.PixelFormat;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.ui.recyclerview.CarUiContentListItem.Action;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Unit tests for {@link CarUiImeSearchListItem}.
 */
@RunWith(AndroidJUnit4.class)
public class CarUiImeSearchListItemTest {

    private CarUiImeSearchListItem mCarUiImeSearchListItem;

    @Before
    public void setUp() {
        mCarUiImeSearchListItem = new CarUiImeSearchListItem(Action.ICON);
    }

    @Test
    public void setIconResId_shouldSetResId() {
        int resId = 1234;
        mCarUiImeSearchListItem.setIconResId(resId);
        assertThat(mCarUiImeSearchListItem.getIconResId(), is(resId));
    }

    @Test
    public void setSupplementalIconResId_shouldSetResId() {
        int resId = 1234;
        mCarUiImeSearchListItem.setSupplementalIconResId(resId);
        assertThat(mCarUiImeSearchListItem.getSupplementalIconResId(), is(resId));
    }

    @Test
    public void setIcon_shouldSetIcon() {
        Drawable drawable = new BitmapDrawable();
        mCarUiImeSearchListItem.setIcon(drawable);
        assertThat(mCarUiImeSearchListItem.getIcon(), is(drawable));
    }

    @Test
    public void setIcon_shouldThrowError() {
        assertThrows("icon should be of type BitmapDrawable", RuntimeException.class,
                () -> mCarUiImeSearchListItem.setIcon(getDrawable()));
    }

    @Test
    public void setSupplementalIcon_shouldSetIcon() {
        Drawable drawable = new BitmapDrawable();
        mCarUiImeSearchListItem.setSupplementalIcon(drawable);
        assertThat(mCarUiImeSearchListItem.getSupplementalIcon(), is(drawable));
    }

    @Test
    public void setSupplementalIcon_shouldThrowError() {
        assertThrows("icon should be of type BitmapDrawable", RuntimeException.class,
                () -> mCarUiImeSearchListItem.setSupplementalIcon(getDrawable()));
    }

    private Drawable getDrawable() {
        return new Drawable() {
            @Override
            public void draw(@NonNull Canvas canvas) {

            }

            @Override
            public void setAlpha(int alpha) {

            }

            @Override
            public void setColorFilter(@Nullable ColorFilter colorFilter) {

            }

            @Override
            public int getOpacity() {
                return PixelFormat.UNKNOWN;
            }
        };
    }
}
