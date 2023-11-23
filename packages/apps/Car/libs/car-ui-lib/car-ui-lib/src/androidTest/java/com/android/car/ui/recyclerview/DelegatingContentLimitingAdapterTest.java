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
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import android.view.View;

import androidx.annotation.IdRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.OrientationHelper;
import androidx.recyclerview.widget.RecyclerView.AdapterDataObserver;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.car.ui.pluginsupport.PluginFactorySingleton;
import com.android.car.ui.test.R;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.Objects;

@RunWith(Parameterized.class)
public class DelegatingContentLimitingAdapterTest {

    private boolean mIsPluginEnabled;

    @Parameterized.Parameters
    public static Object[] data() {
        // It's important to do no plugin first, so that the plugin will
        // still be enabled when this test finishes
        return new Object[]{false, true};
    }

    @IdRes
    private static final int DEFAULT_CONFIG_ID = R.id.test_config_id;

    @Rule
    public ActivityScenarioRule<CarUiRecyclerViewTestActivity> mActivityRule =
            new ActivityScenarioRule<>(CarUiRecyclerViewTestActivity.class);
    ActivityScenario<CarUiRecyclerViewTestActivity> mScenario;

    private DelegatingContentLimitingAdapter<TestViewHolder> mContentLimitingAdapter;
    private TestDelegatingContentLimitingAdapter mDelegateAdapter;
    private CarUiRecyclerViewTestActivity mActivity;

    public DelegatingContentLimitingAdapterTest(boolean pluginEnabled) {
        PluginFactorySingleton.setPluginEnabledForTesting(pluginEnabled);
        mIsPluginEnabled = pluginEnabled;
    }

    @Before
    public void setUp() {
        mScenario = mActivityRule.getScenario();
        mScenario.onActivity(activity -> mActivity = activity);
    }

    @Test
    public void setMaxItem_noScrolling_noContentLimiting() {
        if (mIsPluginEnabled) return;

        mDelegateAdapter = new TestDelegatingContentLimitingAdapter(50);
        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        onView(withId(R.id.list)).check(matches(isDisplayed()));

        CarUiRecyclerView carUiRecyclerView = mActivity.requireViewById(R.id.list);
        mActivity.runOnUiThread(() -> {
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(10);
        });

        onView(withText(mDelegateAdapter.getItemText(0))).check(matches(isDisplayed()));

        LinearLayoutManager layoutManager =
                (LinearLayoutManager) carUiRecyclerView.getLayoutManager();
        OrientationHelper orientationHelper = OrientationHelper.createVerticalHelper(layoutManager);

        View firstChild = Objects.requireNonNull(layoutManager.getChildAt(0));
        boolean isAtStart = orientationHelper.getDecoratedStart(firstChild)
                >= orientationHelper.getStartAfterPadding()
                && layoutManager.getPosition(firstChild) == 0;
        assertTrue(isAtStart);
    }

    @Test
    public void setMaxItem_noScrolling() {
        if (mIsPluginEnabled) return;

        mDelegateAdapter = new TestDelegatingContentLimitingAdapter.WithContentLimiting(50);
        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        onView(withId(R.id.list)).check(matches(isDisplayed()));

        CarUiRecyclerView carUiRecyclerView = mActivity.requireViewById(R.id.list);
        mActivity.runOnUiThread(() -> {
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(1);
        });

        onView(withText(mDelegateAdapter.getItemText(0))).check(matches(isDisplayed()));

        LinearLayoutManager layoutManager =
                (LinearLayoutManager) carUiRecyclerView.getLayoutManager();
        OrientationHelper orientationHelper = OrientationHelper.createVerticalHelper(layoutManager);

        View firstChild = Objects.requireNonNull(layoutManager.getChildAt(0));
        boolean isAtStart = orientationHelper.getDecoratedStart(firstChild)
                >= orientationHelper.getStartAfterPadding()
                && layoutManager.getPosition(firstChild) == 0;
        assertTrue(isAtStart);
    }

    @Test
    public void setMaxItem_withScrolling() {
        if (mIsPluginEnabled) return;

        mDelegateAdapter = new TestDelegatingContentLimitingAdapter.WithContentLimiting(50);
        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        onView(withId(R.id.list)).check(matches(isDisplayed()));

        CarUiRecyclerView carUiRecyclerView = mActivity.requireViewById(R.id.list);
        mActivity.runOnUiThread(() -> {
            ((TestDelegatingContentLimitingAdapter.WithContentLimiting) mDelegateAdapter)
                    .setScrollPositionWhenRestricted(15);
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(16);
        });

        onView(withText(mDelegateAdapter.getItemText(15))).check(matches(isDisplayed()));
    }

    @Test
    public void testChangeItem_callsObservers() {
        mDelegateAdapter = new TestDelegatingContentLimitingAdapter(50);
        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        AdapterDataObserver observer = mock(AdapterDataObserver.class);
        mContentLimitingAdapter.registerAdapterDataObserver(observer);

        onView(withId(R.id.list)).check(matches(isDisplayed()));

        CarUiRecyclerView carUiRecyclerView = mActivity.requireViewById(R.id.list);
        mActivity.runOnUiThread(() -> {
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(10);
            mDelegateAdapter.changeItemRange(5, 3);
        });

        onView(withText(mDelegateAdapter.getItemText(0))).check(matches(isDisplayed()));

        verify(observer).onItemRangeChanged(5, 3, null);
    }

    @Test
    public void testInsertItem_callsObservers() {
        mDelegateAdapter = new TestDelegatingContentLimitingAdapter(50);
        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        AdapterDataObserver observer = mock(AdapterDataObserver.class);
        mContentLimitingAdapter.registerAdapterDataObserver(observer);

        onView(withId(R.id.list)).check(matches(isDisplayed()));

        CarUiRecyclerView carUiRecyclerView = mActivity.requireViewById(R.id.list);
        mActivity.runOnUiThread(() -> {
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(10);
            mDelegateAdapter.insertItemRange(5, "new item 1", "new item 2");
        });

        onView(withText(mDelegateAdapter.getItemText(0))).check(matches(isDisplayed()));

        verify(observer).onItemRangeInserted(5, 2);
    }

    @Test
    public void testRemoveItem_callsObservers() {
        mDelegateAdapter = new TestDelegatingContentLimitingAdapter(50);
        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        AdapterDataObserver observer = mock(AdapterDataObserver.class);
        mContentLimitingAdapter.registerAdapterDataObserver(observer);

        onView(withId(R.id.list)).check(matches(isDisplayed()));

        CarUiRecyclerView carUiRecyclerView = mActivity.requireViewById(R.id.list);
        mActivity.runOnUiThread(() -> {
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(10);
            mDelegateAdapter.removeItemRange(5, 2);
        });

        onView(withText(mDelegateAdapter.getItemText(0))).check(matches(isDisplayed()));

        verify(observer).onItemRangeRemoved(5, 2);
    }

    @Test
    public void testMoveItem_callsObservers() {
        mDelegateAdapter = new TestDelegatingContentLimitingAdapter(50);
        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        AdapterDataObserver observer = mock(AdapterDataObserver.class);
        mContentLimitingAdapter.registerAdapterDataObserver(observer);

        onView(withId(R.id.list)).check(matches(isDisplayed()));

        CarUiRecyclerView carUiRecyclerView = mActivity.requireViewById(R.id.list);
        mActivity.runOnUiThread(() -> {
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(10);
            mDelegateAdapter.moveItem(5, 2);
        });

        onView(withText(mDelegateAdapter.getItemText(0))).check(matches(isDisplayed()));

        verify(observer).onChanged();
    }

    @Test
    public void testChangeDataSet_callsObservers() {
        mDelegateAdapter = new TestDelegatingContentLimitingAdapter(50);
        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        AdapterDataObserver observer = mock(AdapterDataObserver.class);
        mContentLimitingAdapter.registerAdapterDataObserver(observer);

        onView(withId(R.id.list)).check(matches(isDisplayed()));

        CarUiRecyclerView carUiRecyclerView = mActivity.requireViewById(R.id.list);
        mActivity.runOnUiThread(() -> {
            carUiRecyclerView.setAdapter(mContentLimitingAdapter);
            carUiRecyclerView.setVisibility(View.VISIBLE);
            mContentLimitingAdapter.setMaxItems(10);
        });

        onView(withText(mDelegateAdapter.getItemText(0))).check(matches(isDisplayed()));

        mActivity.runOnUiThread(() -> {
            ArrayList<String> newItems = new ArrayList<>();
            for (int i = 0; i < 40; i++) {
                newItems.add("New Item " + i);
            }
            mDelegateAdapter.changeList(newItems);
        });

        onView(withText("New Item 0")).check(matches(isDisplayed()));

        verify(observer).onChanged();
    }

    @Test
    public void testSetHasStableId_setsDelegateToo() {
        mDelegateAdapter = new TestDelegatingContentLimitingAdapter(50);
        mDelegateAdapter.setHasStableIds(false);

        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        mContentLimitingAdapter.setHasStableIds(true);

        assertTrue(mDelegateAdapter.hasStableIds());
    }

    @Test
    public void testGetIds_callsDelegate() {
        mDelegateAdapter = new TestDelegatingContentLimitingAdapter(50);
        mContentLimitingAdapter = new DelegatingContentLimitingAdapter<>(mDelegateAdapter,
            DEFAULT_CONFIG_ID);

        for (int i = 0; i < 50; i++) {
            assertEquals(mDelegateAdapter.getItemId(i), mContentLimitingAdapter.getItemId(i));
        }
    }
}
