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

package com.android.car.ui.recyclerview;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.google.common.truth.Truth.assertThat;

import android.view.View;
import android.widget.LinearLayout;

import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.RecyclerView.ViewHolder;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.car.ui.test.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

public class ContentLimitingAdapterTest {

    @Rule
    public ActivityScenarioRule<CarUiRecyclerViewTestActivity> mActivityRule =
            new ActivityScenarioRule<>(CarUiRecyclerViewTestActivity.class);
    ActivityScenario<CarUiRecyclerViewTestActivity> mScenario;

    private ContentLimitingAdapter<TestViewHolder> mContentLimitingAdapter;

    @Before
    public void setUp() {
        mContentLimitingAdapter = new TestContentLimitingAdapter(50);
        mScenario = mActivityRule.getScenario();
    }

    @Test
    public void setMaxItem_toLowerThanTotalItems() {
        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        RecyclerView.ViewHolder last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");

        // Switch to limited
        mContentLimitingAdapter.setMaxItems(20);

        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(21);
        RecyclerView.ViewHolder secondToLast = getItemAtPosition(19);
        isTestViewHolderWithText(secondToLast, "Item 19");

        last = getItemAtPosition(20);
        assertThat(last).isInstanceOf(ScrollingLimitedViewHolder.class);

        // Switch back to unlimited
        mContentLimitingAdapter.setMaxItems(-1);

        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");
    }

    @Test
    public void setMaxItem_toOne() {
        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        RecyclerView.ViewHolder last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");

        mContentLimitingAdapter.setMaxItems(1);

        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(2);
        RecyclerView.ViewHolder secondToLast = getItemAtPosition(0);
        isTestViewHolderWithText(secondToLast, "Item 0");

        last = getItemAtPosition(1);
        assertThat(last).isInstanceOf(ScrollingLimitedViewHolder.class);

        // Switch back to unlimited
        mContentLimitingAdapter.setMaxItems(-1);

        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");
    }

    @Test
    public void setMaxItem_toZero() {
        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        RecyclerView.ViewHolder last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");

        mContentLimitingAdapter.setMaxItems(0);

        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(1);
        last = getItemAtPosition(0);
        assertThat(last).isInstanceOf(ScrollingLimitedViewHolder.class);

        // Switch back to unlimited
        mContentLimitingAdapter.setMaxItems(-1);

        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");
    }

    @Test
    public void setMaxItem_toHigherThanTotalItems() {
        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        RecyclerView.ViewHolder last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");

        mContentLimitingAdapter.setMaxItems(70);

        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        RecyclerView.ViewHolder secondToLast = getItemAtPosition(48);
        isTestViewHolderWithText(secondToLast, "Item 48");

        last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");

        // Switch back to unlimited
        mContentLimitingAdapter.setMaxItems(-1);

        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");
    }

    @Test
    public void testViewHolderText_customMessage() {
        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        onView(withId(R.id.list)).check(matches(isDisplayed()));

        String[] msg = new String[] {null};
        mScenario.onActivity(activity -> {
            CarUiRecyclerView carUiRecyclerView = activity.requireViewById(R.id.list);
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(0);
            mContentLimitingAdapter.setScrollingLimitedMessageResId(
                    R.string.scrolling_limited_message);
            msg[0] = activity.getString(R.string.scrolling_limited_message);
        });

        onView(withText(msg[0])).check(matches(isDisplayed()));
    }

    @Test
    public void testViewHolderText_changeCustomMessage() {
        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        onView(withId(R.id.list)).check(matches(isDisplayed()));

        String[] msg = new String[] {null};
        mScenario.onActivity(activity -> {
            CarUiRecyclerView carUiRecyclerView = activity.requireViewById(R.id.list);
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(0);
            mContentLimitingAdapter.setScrollingLimitedMessageResId(
                    R.string.car_ui_scrolling_limited_message);
            mContentLimitingAdapter.setScrollingLimitedMessageResId(
                    R.string.scrolling_limited_message);
            msg[0] = activity.getString(R.string.scrolling_limited_message);
        });

        onView(withText(msg[0])).check(matches(isDisplayed()));
    }

    @Test
    public void testViewHolderText_defaultMessage() {
        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        onView(withId(R.id.list)).check(matches(isDisplayed()));

        String[] msg = new String[] {null};
        mScenario.onActivity(activity -> {
            CarUiRecyclerView carUiRecyclerView = activity.requireViewById(R.id.list);
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(0);
            msg[0] = activity.getString(R.string.car_ui_scrolling_limited_message);
        });

        onView(withText(msg[0])).check(matches(isDisplayed()));
    }

    @Test
    public void test_setScrollingLimitedMessageResId_affectsFutureLimiters() {
        mContentLimitingAdapter.notifyLimitingAnchorChanged(0);
        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(50);
        RecyclerView.ViewHolder last = getItemAtPosition(49);
        isTestViewHolderWithText(last, "Item 49");
        mContentLimitingAdapter.setScrollingLimitedMessageResId(R.string.scrolling_limited_message);

        mContentLimitingAdapter.setMaxItems(0);

        assertThat(mContentLimitingAdapter.getItemCount()).isEqualTo(1);
        last = getItemAtPosition(0);
        assertThat(last).isInstanceOf(ScrollingLimitedViewHolder.class);

        mScenario.onActivity(activity -> {
            CarUiRecyclerView carUiRecyclerView = activity.requireViewById(R.id.list);
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
        });

        onView(withText(R.string.scrolling_limited_message)).check(matches(isDisplayed()));
    }

    private RecyclerView.ViewHolder getItemAtPosition(int position) {
        int viewType = mContentLimitingAdapter.getItemViewType(position);
        RecyclerView.ViewHolder[] viewHolder = new ViewHolder[] {null};
        mScenario.onActivity(activity -> viewHolder[0] =
                mContentLimitingAdapter.createViewHolder(
                    new LinearLayout(activity.getApplicationContext()),
                    viewType));
        mContentLimitingAdapter.bindViewHolder(viewHolder[0], position);
        return viewHolder[0];
    }

    private void isTestViewHolderWithText(RecyclerView.ViewHolder secondToLast, String s) {
        assertThat(secondToLast).isInstanceOf(TestViewHolder.class);
        TestViewHolder testViewHolder = (TestViewHolder) secondToLast;
        assertThat(testViewHolder.getText()).isEqualTo(s);
    }
}
