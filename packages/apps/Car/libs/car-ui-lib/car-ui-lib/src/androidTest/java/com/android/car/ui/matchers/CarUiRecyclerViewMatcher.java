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
package com.android.car.ui.matchers;

import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.recyclerview.CarUiRecyclerView;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

/**
 * A {@link org.hamcrest.Matcher} that checks the given View is assignable from
 * {@link CarUiRecyclerView}
 */
public class CarUiRecyclerViewMatcher extends TypeSafeMatcher<View> {

    @Override
    public void describeTo(Description description) {
        description.appendText("is assignable from class: " + CarUiRecyclerView.class);
    }

    @Override
    public boolean matchesSafely(View view) {
        return CarUiRecyclerView.class.isAssignableFrom(view.getClass());
    }

    public static Matcher<View> atPosition(final int position,
                                            @NonNull final Matcher<View> itemMatcher) {
        return new CarUiRecyclerViewMatcher() {
            @Override
            public boolean matchesSafely(View view) {
                if (!super.matchesSafely(view)) {
                    return false;
                }

                RecyclerView.ViewHolder viewHolder = ((CarUiRecyclerView) view)
                        .findViewHolderForAdapterPosition(position);
                if (viewHolder == null) {
                    // has no item on such position
                    return false;
                }
                return itemMatcher.matches(viewHolder.itemView);
            }
        };
    }
}
