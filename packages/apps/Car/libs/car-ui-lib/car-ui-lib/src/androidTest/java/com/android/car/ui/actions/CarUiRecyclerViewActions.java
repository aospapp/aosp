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

import android.util.SparseArray;
import android.view.View;
import android.widget.FrameLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.espresso.ViewAction;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.espresso.util.HumanReadables;

import com.android.car.ui.matchers.ViewHolderMatcher;
import com.android.car.ui.recyclerview.CarUiRecyclerView;

import org.hamcrest.Description;
import org.hamcrest.Matcher;
import org.hamcrest.TypeSafeMatcher;

import java.util.ArrayList;
import java.util.List;

public class CarUiRecyclerViewActions {
    public static ViewAction scrollToPosition(int position) {
        return new ScrollToPositionViewAction(position);
    }

    public static <VH extends RecyclerView.ViewHolder>
            RecyclerViewActions.PositionableRecyclerViewAction scrollTo(
                final Matcher<View> itemViewMatcher) {
        Matcher<VH> viewHolderMatcher = viewHolderMatcher(itemViewMatcher);
        return new ScrollToViewAction<VH>(viewHolderMatcher);
    }

    public static <VH extends RecyclerView.ViewHolder>
            RecyclerViewActions.PositionableRecyclerViewAction actionOnItem(
                    final Matcher<View> itemViewMatcher, final ViewAction viewAction) {
        Matcher<VH> viewHolderMatcher = new ViewHolderMatcher(itemViewMatcher);
        return new ActionOnItemViewAction<VH>(viewHolderMatcher, viewAction);
    }

    public static <VH extends RecyclerView.ViewHolder> ViewAction actionOnItemAtPosition(
            final int position, final ViewAction viewAction) {
        return new ActionOnItemAtPositionViewAction<VH>(position, viewAction);
    }

    public static <T extends VH, VH extends RecyclerView.ViewHolder> List<MatchedItem>
            itemsMatching(final CarUiRecyclerView recyclerView,
                          final Matcher<VH> viewHolderMatcher, int max) {
        final RecyclerView.Adapter<T> adapter = (RecyclerView.Adapter<T>) recyclerView.getAdapter();
        SparseArray<VH> viewHolderCache = new SparseArray<VH>();
        List<MatchedItem> matchedItems = new ArrayList<MatchedItem>();
        for (int position = 0; position < adapter.getItemCount(); position++) {
            int itemType = adapter.getItemViewType(position);
            VH cachedViewHolder = viewHolderCache.get(itemType);
            // Create a view holder per type if not exists
            if (null == cachedViewHolder) {
                // Return a view created with the app context so that a LayoutInflator created from
                // this view can find resources as expected.
                FrameLayout fakeParent = new FrameLayout(recyclerView.getContext());
                cachedViewHolder = adapter.createViewHolder(fakeParent, itemType);
                viewHolderCache.put(itemType, cachedViewHolder);
            }
            // Bind data to ViewHolder and apply matcher to view descendants.
            adapter.bindViewHolder((T) cachedViewHolder, position);
            if (viewHolderMatcher.matches(cachedViewHolder)) {
                matchedItems.add(
                        new MatchedItem(
                                position,
                                HumanReadables.getViewHierarchyErrorMessage(
                                        cachedViewHolder.itemView,
                                        null,
                                        "\n\n*** Matched ViewHolder item at position: "
                                                + position + " ***",
                                        null)));
                adapter.onViewRecycled((T) cachedViewHolder);
                if (matchedItems.size() == max) {
                    break;
                }
            } else {
                adapter.onViewRecycled((T) cachedViewHolder);
            }
        }
        return matchedItems;
    }

    /**
     * Wrapper for matched items in recycler view which contains position and description of matched
     * view.
     */
    static class MatchedItem {
        public final int position;
        public final String description;

        private MatchedItem(int position, String description) {
            this.position = position;
            this.description = description;
        }

        @Override
        public String toString() {
            return description;
        }
    }

    /**
     * Adapted from {@link androidx.test.espresso.contrib.RecyclerViewActions.viewHolderMatcher}
     */
    private static <VH extends RecyclerView.ViewHolder> Matcher<VH> viewHolderMatcher(
            final Matcher<View> itemViewMatcher) {
        return new TypeSafeMatcher<VH>() {
            @Override
            public boolean matchesSafely(RecyclerView.ViewHolder viewHolder) {
                return itemViewMatcher.matches(viewHolder.itemView);
            }
            @Override
            public void describeTo(Description description) {
                description.appendText("holder with view: ");
                itemViewMatcher.describeTo(description);
            }
        };
    }
}
