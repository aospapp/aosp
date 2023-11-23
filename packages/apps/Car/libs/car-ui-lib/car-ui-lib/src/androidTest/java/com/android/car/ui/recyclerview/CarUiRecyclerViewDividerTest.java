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

package com.android.car.ui.recyclerview;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import android.view.LayoutInflater;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.car.ui.TestActivity;
import com.android.car.ui.test.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link com.android.car.ui.recyclerview.decorations.grid.GridDividerItemDecoration}
 * and {@link com.android.car.ui.recyclerview.decorations.linear.LinearDividerItemDecoration}.
 */
public class CarUiRecyclerViewDividerTest {

    @Rule
    public ActivityScenarioRule<TestActivity> mActivityRule =
            new ActivityScenarioRule<>(TestActivity.class);

    @Before
    public void setUp() {
        mActivityRule.getScenario().onActivity(activity ->
                activity.setContentView(R.layout.car_ui_recycler_view_dividers_test_activity));
    }

    @Test
    public void test_dividers_linear() {
        mActivityRule.getScenario().onActivity(activity -> {
            CarUiRecyclerView recyclerView = activity.findViewById(R.id.list);
            recyclerView.setAdapter(new TestAdapter());
            recyclerView.setLayoutStyle(new CarUiLinearLayoutStyle());
        });
        onView(withText("Sample item #0")).check(matches(isDisplayed()));
    }

    @Test
    public void test_dividers_grid() {
        mActivityRule.getScenario().onActivity(activity -> {
            CarUiRecyclerView recyclerView = activity.findViewById(R.id.list);
            recyclerView.setAdapter(new TestAdapter());
            CarUiGridLayoutStyle layoutStyle = new CarUiGridLayoutStyle();
            layoutStyle.setSpanCount(3);
            recyclerView.setLayoutStyle(layoutStyle);
        });
        onView(withText("Sample item #0")).check(matches(isDisplayed()));
    }

    /**
     * A test adapter that handles inflating test views and binding data to it.
     */
    private static class TestAdapter extends RecyclerView.Adapter<TestViewHolder> {

        protected final List<String> mData;

        TestAdapter() {
            int itemCount = 50;
            mData = new ArrayList<>(itemCount);

            for (int i = 0; i < itemCount; i++) {
                mData.add("Sample item #" + i);
            }
        }

        @NonNull
        @Override
        public TestViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
            return new TestViewHolder(LayoutInflater.from(parent.getContext())
                .inflate(R.layout.test_car_ui_recycler_view_list_item, parent, false));
        }

        @Override
        public void onBindViewHolder(@NonNull TestViewHolder holder, int position) {
            holder.bind(mData.get(position));
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }
    }
}
