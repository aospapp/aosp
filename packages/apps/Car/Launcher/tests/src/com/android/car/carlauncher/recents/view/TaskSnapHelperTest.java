/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.carlauncher.recents.view;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static org.mockito.Mockito.mock;

import android.content.Context;
import android.graphics.Color;
import android.graphics.Rect;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.GridLayoutManager;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.IdlingRegistry;
import androidx.test.espresso.IdlingResource;
import androidx.test.ext.junit.rules.ActivityScenarioRule;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.carlauncher.TestActivity;
import com.android.car.carlauncher.test.R;

import org.hamcrest.Description;
import org.hamcrest.TypeSafeDiagnosingMatcher;
import org.hamcrest.core.CombinableMatcher;
import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

@RunWith(AndroidJUnit4.class)
public class TaskSnapHelperTest {
    private static final int FIRST_TASK_WIDTH = 800;
    private static final int TASK_WIDTH = 300;
    private static final boolean IS_REVERSED_LAYOUT = true;
    private static final int GRID_LAYOUT_DIRECTION = GridLayoutManager.HORIZONTAL;
    private static final int SPAN_COUNT = 2;
    private static final int COL_PER_PAGE = 2;
    private static final int TIP_OVER_VARIANCE = 5;
    // the mid-point between the first task view and first consequent page of task views such that
    // the center of first task view is equidistant from the center of the center of the page
    private static final int MID_POINT_BETWEEN_FIRST_VIEW_AND_FIRST_PAGE =
            FIRST_TASK_WIDTH / 4 + TASK_WIDTH / 2;
    private TaskSnapHelper mTaskSnapHelper;
    private int mWindowWidth;
    @Rule
    public ActivityScenarioRule<TestActivity> mActivityRule = new ActivityScenarioRule<>(
            TestActivity.class);

    @Before
    public void setup() {
        mActivityRule.getScenario().onActivity(
                activity -> activity.setContentView(R.xml.empty_test_activity));
        onView(withId(R.id.list)).check(matches(isDisplayed()));
        mActivityRule.getScenario().onActivity(activity -> {
            Context testableContext = mock(Context.class);
            RecyclerView rv = activity.requireViewById(R.id.list);
            GridLayoutManager gridLayoutManager = new GridLayoutManager(testableContext, SPAN_COUNT,
                    GRID_LAYOUT_DIRECTION, IS_REVERSED_LAYOUT);
            gridLayoutManager.setSpanSizeLookup(new GridLayoutManager.SpanSizeLookup() {
                @Override
                public int getSpanSize(int position) {
                    if (position == 0) {
                        return SPAN_COUNT;
                    }
                    return 1;
                }
            });
            rv.setLayoutManager(gridLayoutManager);
            // add padding to make sure the first and last element can be centered
            mWindowWidth =
                    activity.getWindowManager().getCurrentWindowMetrics().getBounds().width();
            rv.setPaddingRelative(TASK_WIDTH, rv.getPaddingTop(),
                    (mWindowWidth - FIRST_TASK_WIDTH) / 2,
                    rv.getPaddingBottom());
            rv.setAdapter(new TestAdapter(100));
        });

    }

    @After
    public void tearDown() {
        for (IdlingResource idlingResource : IdlingRegistry.getInstance().getResources()) {
            IdlingRegistry.getInstance().unregister(idlingResource);
        }
        if (mActivityRule != null) {
            mActivityRule.getScenario().close();
        }
    }

    @Test
    public void testScrollFromInitialPageToNextPage() {
        assertPageInCenter(/* pageNumber= */ 0);
        RecyclerViewIdlingResource.register(mActivityRule.getScenario());

        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            mTaskSnapHelper = new TaskSnapHelper(SPAN_COUNT, COL_PER_PAGE);
            mTaskSnapHelper.attachToRecyclerView(rv);
            rv.smoothScrollBy(-(MID_POINT_BETWEEN_FIRST_VIEW_AND_FIRST_PAGE + TIP_OVER_VARIANCE),
                    0);
        });

        assertPageInCenter(/* pageNumber= */ 1);
    }

    @Test
    public void testScrollFromFirstPageToNextPage() {
        RecyclerViewIdlingResource.register(mActivityRule.getScenario());
        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            rv.smoothScrollBy(-(FIRST_TASK_WIDTH / 2 + TASK_WIDTH), 0);
        });
        assertPageInCenter(/* pageNumber= */ 1);

        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            mTaskSnapHelper = new TaskSnapHelper(SPAN_COUNT, COL_PER_PAGE);
            mTaskSnapHelper.attachToRecyclerView(rv);
            rv.smoothScrollBy(-(TASK_WIDTH + TIP_OVER_VARIANCE), 0);
        });

        assertPageInCenter(/* pageNumber= */ 2);
    }

    @Test
    public void testScrollFromInitialPageStaysOnInitialPage() {
        assertPageInCenter(/* pageNumber= */ 0);
        RecyclerViewIdlingResource.register(mActivityRule.getScenario());

        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            mTaskSnapHelper = new TaskSnapHelper(SPAN_COUNT, COL_PER_PAGE);
            mTaskSnapHelper.attachToRecyclerView(rv);
            rv.smoothScrollBy(-(MID_POINT_BETWEEN_FIRST_VIEW_AND_FIRST_PAGE - TIP_OVER_VARIANCE),
                    0);
        });

        assertPageInCenter(/* pageNumber= */ 0);
    }

    @Test
    public void testScrollFromFirstPageStaysOnSamePage() {
        RecyclerViewIdlingResource.register(mActivityRule.getScenario());
        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            rv.smoothScrollBy(-(FIRST_TASK_WIDTH / 2 + TASK_WIDTH), 0);
        });
        assertPageInCenter(/* pageNumber= */ 1);

        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            mTaskSnapHelper = new TaskSnapHelper(SPAN_COUNT, COL_PER_PAGE);
            mTaskSnapHelper.attachToRecyclerView(rv);
            rv.smoothScrollBy(-(TASK_WIDTH - TIP_OVER_VARIANCE), 0);
        });

        assertPageInCenter(/* pageNumber= */ 1);
    }

    @Test
    public void testScrollFromFirstPageToPreviousPage() {
        RecyclerViewIdlingResource.register(mActivityRule.getScenario());
        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            rv.smoothScrollBy(-(FIRST_TASK_WIDTH / 2 + TASK_WIDTH), 0);
        });
        assertPageInCenter(/* pageNumber= */ 1);

        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            mTaskSnapHelper = new TaskSnapHelper(SPAN_COUNT, COL_PER_PAGE);
            mTaskSnapHelper.attachToRecyclerView(rv);
            rv.smoothScrollBy(MID_POINT_BETWEEN_FIRST_VIEW_AND_FIRST_PAGE + TIP_OVER_VARIANCE, 0);
        });

        assertPageInCenter(/* pageNumber= */ 0);
    }

    @Test
    public void testScrollFromSecondPageToPreviousPage() {
        RecyclerViewIdlingResource.register(mActivityRule.getScenario());
        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            rv.smoothScrollBy(-(FIRST_TASK_WIDTH / 2 + TASK_WIDTH * 3), 0);
        });
        assertPageInCenter(/* pageNumber= */ 2);

        mActivityRule.getScenario().onActivity(activity -> {
            RecyclerView rv = activity.requireViewById(R.id.list);
            mTaskSnapHelper = new TaskSnapHelper(SPAN_COUNT, COL_PER_PAGE);
            mTaskSnapHelper.attachToRecyclerView(rv);
            rv.smoothScrollBy((TASK_WIDTH + TIP_OVER_VARIANCE), 0);
        });

        assertPageInCenter(/* pageNumber= */ 1);
    }

    private static String getViewTextAtPosition(int position) {
        return ("test " + position);
    }

    /**
     * @param pageNumber page that should be checked to be in the center.
     *                   - 0 indicates the item at adapter position 0.
     *                   - 1+ indicates the page with {@code SPAN_COUNT * COL_PER_PAGE} items.
     */
    private void assertPageInCenter(int pageNumber) {
        if (pageNumber < 0) {
            return;
        }
        int expectedCenter;
        if (pageNumber == 0) {
            expectedCenter = mWindowWidth / 2;
            onView(withText(getViewTextAtPosition(0))).check(matches((new CombinableMatcher<>(
                    isCompletelyDisplayed())).and(new IsViewInCenterMatcher(expectedCenter))));
            return;
        }
        int itemsPerPage = SPAN_COUNT * COL_PER_PAGE;
        int itemPosition = 1 + (itemsPerPage * (pageNumber - 1));

        expectedCenter = (mWindowWidth + TASK_WIDTH) / 2;
        onView(withText(getViewTextAtPosition(itemPosition++))).check(
                matches((new CombinableMatcher<>(isCompletelyDisplayed()))
                        .and(new IsViewInCenterMatcher(expectedCenter))));
        onView(withText(getViewTextAtPosition(itemPosition++))).check(
                matches((new CombinableMatcher<>(isCompletelyDisplayed()))
                        .and(new IsViewInCenterMatcher(expectedCenter))));

        expectedCenter = (mWindowWidth - TASK_WIDTH) / 2;
        onView(withText(getViewTextAtPosition(itemPosition++))).check(
                matches((new CombinableMatcher<>(isCompletelyDisplayed()))
                        .and(new IsViewInCenterMatcher(expectedCenter))));
        onView(withText(getViewTextAtPosition(itemPosition))).check(
                matches((new CombinableMatcher<>(isCompletelyDisplayed()))
                        .and(new IsViewInCenterMatcher(expectedCenter))));
    }

    private static class TestAdapter extends RecyclerView.Adapter<TestViewHolder> {
        private final List<String> mData;

        TestAdapter(int itemCount) {
            mData = new ArrayList<>(itemCount);
            for (int i = 0; i < itemCount; i++) {
                mData.add(getViewTextAtPosition(i));
            }
        }

        @NonNull
        @Override
        public TestViewHolder onCreateViewHolder(@NonNull ViewGroup viewGroup, int i) {
            LayoutInflater inflater = LayoutInflater.from(viewGroup.getContext());
            View view = inflater.inflate(R.xml.test_list_item, viewGroup,
                    /* attachToRoot= */ false);
            return new TestViewHolder(view);
        }

        @Override
        public void onBindViewHolder(@NonNull TestViewHolder testViewHolder, int i) {
            ViewGroup.LayoutParams layoutParams = testViewHolder.itemView.getLayoutParams();
            if (i == 0) {
                layoutParams.width = FIRST_TASK_WIDTH;
            } else {
                layoutParams.width = TASK_WIDTH;
            }
            testViewHolder.itemView.setLayoutParams(layoutParams);
            testViewHolder.bind(mData.get(i));
        }

        @Override
        public int getItemCount() {
            return mData.size();
        }
    }

    private static class TestViewHolder extends RecyclerView.ViewHolder {
        TestViewHolder(View view) {
            super(view);
            // added different background color for easier debugging
            Random random = new Random();
            int color = Color.argb(255, random.nextInt(256), random.nextInt(256),
                    random.nextInt(256));
            view.setBackgroundColor(color);
        }

        void bind(String text) {
            ((TextView) itemView.findViewById(R.id.text)).setText(text);
        }
    }

    public static class RecyclerViewIdlingResource implements IdlingResource, AutoCloseable {
        private final RecyclerView mRecyclerView;
        private ResourceCallback mResourceCallback;

        public RecyclerViewIdlingResource(RecyclerView rv) {
            mRecyclerView = rv;
            rv.addOnScrollListener(new RecyclerView.OnScrollListener() {
                @Override
                public void onScrollStateChanged(@NonNull RecyclerView recyclerView,
                        int newState) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE && mResourceCallback != null) {
                        mResourceCallback.onTransitionToIdle();
                    }
                }

                @Override
                public void onScrolled(@NonNull RecyclerView recyclerView, int dx,
                        int dy) {
                }
            });
        }

        @Override
        public String getName() {
            return RecyclerViewIdlingResource.class.getSimpleName();
        }

        @Override
        public boolean isIdleNow() {
            return mRecyclerView.getScrollState() == RecyclerView.SCROLL_STATE_IDLE;
        }

        @Override
        public void registerIdleTransitionCallback(ResourceCallback callback) {
            mResourceCallback = callback;
        }

        @Override
        public void close() throws Exception {
            IdlingRegistry.getInstance().unregister(this);
        }

        public static RecyclerViewIdlingResource register(ActivityScenario<TestActivity> scenario) {
            final RecyclerViewIdlingResource[] idlingResources = new RecyclerViewIdlingResource[1];
            scenario.onActivity((activity -> idlingResources[0] = new RecyclerViewIdlingResource(
                    activity.findViewById(R.id.list))));
            IdlingRegistry.getInstance().register(idlingResources[0]);
            return idlingResources[0];
        }
    }

    private static class IsViewInCenterMatcher extends TypeSafeDiagnosingMatcher<View> {
        int mExpectedCenter;

        IsViewInCenterMatcher(int expectedCenter) {
            mExpectedCenter = expectedCenter;
        }

        @Override
        protected boolean matchesSafely(View item, Description mismatchDescription) {
            Rect viewPosition = new Rect();
            boolean isViewVisible = item.getGlobalVisibleRect(viewPosition);
            if (!isViewVisible) {
                mismatchDescription.appendText("view was not visible to the user");
                return false;
            }
            int centerOfChild = (viewPosition.right + viewPosition.left) / 2;
            if (centerOfChild != mExpectedCenter) {
                mismatchDescription.appendText("view was off from expected center of ")
                        .appendValue(mExpectedCenter).appendText(" by ")
                        .appendValue(mExpectedCenter - centerOfChild);
                return false;
            }
            return true;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("view's center matches with the expected center: ")
                    .appendValue(mExpectedCenter);
        }
    }
}
