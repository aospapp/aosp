/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.ui.matchers;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.drawable.Drawable;
import android.view.View;
import android.widget.ImageView;

import androidx.annotation.DrawableRes;
import androidx.annotation.NonNull;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeMatcher;

@TargetApi(MIN_TARGET_API)
public class DrawableMatcher extends TypeSafeMatcher<View> {

    private final Drawable mDrawable;

    DrawableMatcher(@NonNull Context context, @DrawableRes int drawableId) {
        this(context.getDrawable(drawableId));
    }

    DrawableMatcher(Drawable drawable) {
        mDrawable = drawable;
    }

    @Override
    protected boolean matchesSafely(View target) {
        if (!(target instanceof ImageView) || !target.isShown()) {
            return false;
        }

        Drawable targetDrawable = ((ImageView) target).getDrawable();
        Drawable.ConstantState targetState = targetDrawable.getConstantState();
        Drawable.ConstantState expectedState = mDrawable.getConstantState();
        // If the constant state is identical, they are using the same drawable resource.
        // However, the opposite is not necessarily true.
        return (expectedState.equals(targetState) || drawableToBitmap(mDrawable).sameAs(
                drawableToBitmap(targetDrawable)));
    }

    private Bitmap drawableToBitmap(Drawable drawable) {
        if (drawable == null) {
            return null;
        }

        Bitmap bitmap = Bitmap.createBitmap(drawable.getIntrinsicWidth(),
                drawable.getIntrinsicHeight(), Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(bitmap);
        drawable.setBounds(0, 0, canvas.getWidth(), canvas.getHeight());
        drawable.draw(canvas);
        return bitmap;
    }

    @Override
    public void describeTo(Description description) {
        description.appendText("has a certain drawable");
    }
}
