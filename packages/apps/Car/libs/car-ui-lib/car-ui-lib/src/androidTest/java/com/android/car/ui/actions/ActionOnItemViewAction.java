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

import static com.android.car.ui.actions.CarUiRecyclerViewActions.actionOnItemAtPosition;
import static com.android.car.ui.actions.CarUiRecyclerViewActions.itemsMatching;

import static org.hamcrest.Matchers.allOf;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.PerformException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.espresso.util.HumanReadables;

import com.android.car.ui.matchers.CarUiRecyclerViewMatcher;
import com.android.car.ui.recyclerview.CarUiRecyclerView;

import org.hamcrest.Matcher;

import java.util.List;

/**
 * Adapted from {@link androidx.test.espresso.contrib.RecyclerViewActions.ActionOnItemViewAction}
 */
public final class ActionOnItemViewAction<VH extends RecyclerView.ViewHolder>
        implements RecyclerViewActions.PositionableRecyclerViewAction {

    private static final int NO_POSITION = -1;

    private final Matcher<VH> mViewHolderMatcher;
    private final ViewAction mViewAction;
    private final int mAtPosition;
    private final ScrollToViewAction<VH> mScroller;

    public ActionOnItemViewAction(Matcher<VH> viewHolderMatcher, ViewAction viewAction) {
        this(viewHolderMatcher, viewAction, NO_POSITION);
    }

    public ActionOnItemViewAction(
            Matcher<VH> viewHolderMatcher, ViewAction viewAction, int atPosition) {
        this.mViewHolderMatcher = viewHolderMatcher;
        this.mViewAction = viewAction;
        this.mAtPosition = atPosition;
        this.mScroller = new ScrollToViewAction<VH>(viewHolderMatcher, atPosition);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Matcher<View> getConstraints() {
        return allOf(new CarUiRecyclerViewMatcher(), isDisplayed());
    }

    @Override
    public RecyclerViewActions.PositionableRecyclerViewAction atPosition(int position) {
        return new ActionOnItemViewAction<VH>(mViewHolderMatcher, mViewAction, position);
    }

    @Override
    public String getDescription() {
        if (mAtPosition == NO_POSITION) {
            return String.format(
                    "performing ViewAction: %s on item matching: %s",
                    mViewAction.getDescription(), mViewHolderMatcher);

        } else {
            return String.format(
                    "performing ViewAction: %s on %d-th item matching: %s",
                    mViewAction.getDescription(), mAtPosition, mViewHolderMatcher);
        }
    }

    @Override
    public void perform(UiController uiController, View root) {
        CarUiRecyclerView recyclerView = (CarUiRecyclerView) root;
        try {
            mScroller.perform(uiController, root);
            uiController.loopMainThreadUntilIdle();
            // the above scroller has checked bounds, dupes (maybe)
            // and brought the element into screen.
            int max = mAtPosition == NO_POSITION ? 2 : mAtPosition + 1;
            int selectIndex = mAtPosition == NO_POSITION ? 0 : mAtPosition;
            List<CarUiRecyclerViewActions.MatchedItem> matchedItems =
                    itemsMatching(recyclerView, mViewHolderMatcher, max);
            actionOnItemAtPosition(matchedItems.get(selectIndex).position, mViewAction)
                    .perform(uiController, root);
            uiController.loopMainThreadUntilIdle();
        } catch (RuntimeException e) {
            throw new PerformException.Builder()
                    .withActionDescription(this.getDescription())
                    .withViewDescription(HumanReadables.describe(root))
                    .withCause(e)
                    .build();
        }
    }
}
