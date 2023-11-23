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
package com.android.car.ui.actions;

import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;

import static org.hamcrest.Matchers.allOf;

import android.view.View;

import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.contrib.RecyclerViewActions;

import com.android.car.ui.matchers.CarUiRecyclerViewMatcher;
import com.android.car.ui.recyclerview.CarUiRecyclerView;

import org.hamcrest.Matcher;

/**
 * {@link ViewAction} which scrolls {@link CarUiRecyclerView} to a given position. See {@link
 * RecyclerViewActions#scrollToPosition(int)} for more details.
 */
public final class ScrollToPositionViewAction implements ViewAction {
    private final int mPosition;

    public ScrollToPositionViewAction(int position) {
        this.mPosition = position;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Matcher<View> getConstraints() {
        return allOf(new CarUiRecyclerViewMatcher(), isDisplayed());
    }

    @Override
    public String getDescription() {
        return "scroll RecyclerView to position: " + mPosition;
    }

    @Override
    public void perform(UiController uiController, View view) {
        CarUiRecyclerView recyclerView = (CarUiRecyclerView) view;
        recyclerView.scrollToPosition(mPosition);
        uiController.loopMainThreadUntilIdle();
    }
}
