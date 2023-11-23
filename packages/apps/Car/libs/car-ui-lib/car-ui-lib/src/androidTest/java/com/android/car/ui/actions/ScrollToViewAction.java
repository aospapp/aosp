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

import static com.android.car.ui.actions.CarUiRecyclerViewActions.itemsMatching;

import static org.hamcrest.Matchers.allOf;

import android.view.View;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.PerformException;
import androidx.test.espresso.UiController;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.espresso.util.HumanReadables;

import com.android.car.ui.matchers.CarUiRecyclerViewMatcher;
import com.android.car.ui.recyclerview.CarUiRecyclerView;

import org.hamcrest.Matcher;

import java.util.List;

/**
 * Adapted from {@link androidx.test.espresso.contrib.RecyclerViewActions.ScrollToViewAction}
 */
public final class ScrollToViewAction<VH extends RecyclerView.ViewHolder>
        implements RecyclerViewActions.PositionableRecyclerViewAction {

    private static final int NO_POSITION = -1;

    private final Matcher<VH> mViewHolderMatcher;
    private final int mAtPosition;

    public ScrollToViewAction(Matcher<VH> viewHolderMatcher) {
        this(viewHolderMatcher, NO_POSITION);
    }

    public ScrollToViewAction(Matcher<VH> viewHolderMatcher, int atPosition) {
        this.mViewHolderMatcher = viewHolderMatcher;
        this.mAtPosition = atPosition;
    }

    @Override
    public RecyclerViewActions.PositionableRecyclerViewAction atPosition(int position) {
        return new ScrollToViewAction<VH>(mViewHolderMatcher, position);
    }

    @SuppressWarnings("unchecked")
    @Override
    public Matcher<View> getConstraints() {
        return allOf(new CarUiRecyclerViewMatcher(), isDisplayed());
    }

    @Override
    public String getDescription() {
        if (mAtPosition == NO_POSITION) {
            return "scroll RecyclerView to: " + mViewHolderMatcher;
        } else {
            return String.format(
                    "scroll RecyclerView to the: %dth matching %s.",
                    mAtPosition,
                    mViewHolderMatcher);
        }
    }

    @SuppressWarnings("unchecked")
    @Override
    public void perform(UiController uiController, View view) {
        CarUiRecyclerView recyclerView = (CarUiRecyclerView) view;
        try {
            int maxMatches = mAtPosition == NO_POSITION ? 2 : mAtPosition + 1;
            int selectIndex = mAtPosition == NO_POSITION ? 0 : mAtPosition;
            List<CarUiRecyclerViewActions.MatchedItem> matchedItems = itemsMatching(recyclerView,
                    mViewHolderMatcher, maxMatches);

            if (selectIndex >= matchedItems.size()) {
                throw new RuntimeException(
                        String.format(
                                "Found %d items matching %s, but position %d was requested.",
                                matchedItems.size(), mViewHolderMatcher.toString(), mAtPosition));
            }
            if (mAtPosition == NO_POSITION && matchedItems.size() == 2) {
                StringBuilder ambiguousViewError = new StringBuilder();
                ambiguousViewError.append(
                        String.format("Found more than one sub-view matching %s",
                                mViewHolderMatcher));
                for (CarUiRecyclerViewActions.MatchedItem item : matchedItems) {
                    ambiguousViewError.append(item + "\n");
                }
                throw new RuntimeException(ambiguousViewError.toString());
            }
            recyclerView.scrollToPosition(matchedItems.get(selectIndex).position);
            uiController.loopMainThreadUntilIdle();
        } catch (RuntimeException e) {
            throw new PerformException.Builder()
                    .withActionDescription(this.getDescription())
                    .withViewDescription(HumanReadables.describe(view))
                    .withCause(e)
                    .build();
        }
    }
}
