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

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.PerformException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.util.HumanReadables;

import com.android.car.ui.matchers.CarUiRecyclerViewMatcher;
import com.android.car.ui.recyclerview.CarUiRecyclerView;

import org.hamcrest.Matcher;

/**
 * adapted from
 * {@link androidx.test.espresso.contrib.RecyclerViewActions.ActionOnItemAtPositionViewAction}
 */
public final class ActionOnItemAtPositionViewAction<VH extends RecyclerView.ViewHolder>
        implements ViewAction {
    private final int mPosition;
    private final ViewAction mViewAction;

    public ActionOnItemAtPositionViewAction(int position, ViewAction viewAction) {
        this.mPosition = position;
        this.mViewAction = viewAction;
    }

    @SuppressWarnings("unchecked")
    @Override
    public Matcher<View> getConstraints() {
        return allOf(new CarUiRecyclerViewMatcher(), isDisplayed());
    }

    @Override
    public String getDescription() {
        return "actionOnItemAtPosition performing ViewAction: "
                + mViewAction.getDescription()
                + " on item at position: "
                + mPosition;
    }

    @Override
    public void perform(UiController uiController, View view) {
        CarUiRecyclerView recyclerView = (CarUiRecyclerView) view;

        new ScrollToPositionViewAction(mPosition).perform(uiController, view);
        uiController.loopMainThreadUntilIdle();

        @SuppressWarnings("unchecked")
        VH viewHolderForPosition = (VH) recyclerView.findViewHolderForAdapterPosition(mPosition);
        if (null == viewHolderForPosition) {
            throw new PerformException.Builder()
                    .withActionDescription(this.toString())
                    .withViewDescription(HumanReadables.describe(view))
                    .withCause(new IllegalStateException("No view holder at position: "
                            + mPosition))
                    .build();
        }

        View viewAtPosition = viewHolderForPosition.itemView;
        if (null == viewAtPosition) {
            throw new PerformException.Builder()
                    .withActionDescription(this.toString())
                    .withViewDescription(HumanReadables.describe(viewAtPosition))
                    .withCause(new IllegalStateException("No view at position: " + mPosition))
                    .build();
        }

        mViewAction.perform(uiController, viewAtPosition);
    }
}
