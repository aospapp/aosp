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

package com.android.car.ui.utils;

import static android.view.View.GONE;
import static android.view.View.INVISIBLE;
import static android.view.View.VISIBLE;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.ui.actions.ViewActions.waitForView;
import static com.android.car.ui.utils.ViewUtils.DEFAULT_FOCUS;
import static com.android.car.ui.utils.ViewUtils.FOCUSED_BY_DEFAULT;
import static com.android.car.ui.utils.ViewUtils.IMPLICIT_DEFAULT_FOCUS;
import static com.android.car.ui.utils.ViewUtils.NO_FOCUS;
import static com.android.car.ui.utils.ViewUtils.REGULAR_FOCUS;
import static com.android.car.ui.utils.ViewUtils.RESTORE_FOCUS_RETRY_DELAY_MS;
import static com.android.car.ui.utils.ViewUtils.SCROLLABLE_CONTAINER_FOCUS;
import static com.android.car.ui.utils.ViewUtils.SELECTED_FOCUS;
import static com.android.car.ui.utils.ViewUtils.setRotaryScrollEnabled;

import static com.google.common.truth.Truth.assertThat;

import android.view.View;

import androidx.test.rule.ActivityTestRule;

import com.android.car.ui.FocusArea;
import com.android.car.ui.FocusParkingView;
import com.android.car.ui.R;
import com.android.car.ui.TestAdapter;
import com.android.car.ui.recyclerview.CarUiRecyclerView;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit tests for {@link ViewUtils}. */
public class ViewUtilsTest {

    @Rule
    public ActivityTestRule<ViewUtilsTestActivity> mActivityRule =
            new ActivityTestRule<>(ViewUtilsTestActivity.class);

    private ViewUtilsTestActivity mActivity;
    private FocusArea mFocusArea1;
    private FocusArea mFocusArea2;
    private FocusArea mFocusArea3;
    private FocusArea mFocusArea4;
    private FocusArea mFocusArea5;
    private View mView2;
    private View mFocusedByDefault3;
    private View mView4;
    private View mDefaultFocus4;
    private CarUiRecyclerView mCarUiRecyclerView5;
    private CarUiRecyclerView mCarUiRecyclerView6;
    private View mRoot;
    private FocusParkingView mFpv;

    @Before
    public void setUp() {
        mActivity = mActivityRule.getActivity();
        mFocusArea1 = mActivity.findViewById(R.id.focus_area1);
        mFocusArea2 = mActivity.findViewById(R.id.focus_area2);
        mFocusArea3 = mActivity.findViewById(R.id.focus_area3);
        mFocusArea4 = mActivity.findViewById(R.id.focus_area4);
        mFocusArea5 = mActivity.findViewById(R.id.focus_area5);
        mView2 = mActivity.findViewById(R.id.view2);
        mFocusedByDefault3 = mActivity.findViewById(R.id.focused_by_default3);
        mView4 = mActivity.findViewById(R.id.view4);
        mDefaultFocus4 = mActivity.findViewById(R.id.default_focus4);
        mCarUiRecyclerView5 = mActivity.findViewById(R.id.list5);
        mCarUiRecyclerView6 = mActivity.findViewById(R.id.list6);
        mRoot = mFocusArea1.getRootView();

        // Since ViewUtilsTestActivity uses Theme.CarUi.NoToolbar, a FocusParkingView has been added
        // to the view tree automatically.
        mFpv = ViewUtils.findFocusParkingView(mRoot);

        mRoot.post(() -> {
            // Don't set the LayoutManager of mCarUiRecyclerView5 because it already has a default
            // one, which contains important Runnables in its onLayoutCompleted(). Resetting it
            // LayoutManager will remove the Runnables.
            mCarUiRecyclerView5.setAdapter(new TestAdapter(/* numItems= */ 2));
            setRotaryScrollEnabled(mCarUiRecyclerView5.getView(), /* isVertical= */ true);
        });
        // If we don't wait for the recyclerview items to show up, some of the tests flake
        onView(isRoot()).perform(waitForView(withText("Item 0"), 500));
    }

    @Test
    public void testRootVisible() {
        assertThat(mRoot.getVisibility()).isEqualTo(VISIBLE);
    }

    @Test
    public void testGetAncestorFocusArea() {
        assertThat(ViewUtils.getAncestorFocusArea(mView2)).isEqualTo(mFocusArea2);
    }

    @Test
    public void testGetAncestorFocusArea_doesNotReturnItself() {
        assertThat(ViewUtils.getAncestorFocusArea(mFocusArea2)).isNull();
    }

    @Test
    public void testGetAncestorFocusArea_outsideFocusArea() {
        assertThat(ViewUtils.getAncestorFocusArea(mFpv)).isNull();
    }

    @Test
    public void testGetAncestorScrollableContainer() {
        View firstItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(0);
        View container = ViewUtils.getAncestorScrollableContainer(firstItem);
        // Since there is no API to get the inner RecyclerView, verify its focus level instead.
        assertThat(ViewUtils.getFocusLevel(container)).isEqualTo(SCROLLABLE_CONTAINER_FOCUS);
    }

    @Test
    public void testGetAncestorScrollableContainer_returnNull() {
        assertThat(ViewUtils.getAncestorScrollableContainer(mView2)).isNull();
    }

    @Test
    public void testFindFocusedByDefaultView() {
        View focusedByDefault = ViewUtils.findFocusedByDefaultView(mRoot);
        assertThat(focusedByDefault).isEqualTo(mFocusedByDefault3);
    }

    @Test
    public void testFindFocusedByDefaultView_skipNotFocusable() throws InterruptedException {
        TestUtils.accept(mFocusedByDefault3, v -> v.setFocusable(false));
        View focusedByDefault = ViewUtils.findFocusedByDefaultView(mRoot);
        assertThat(focusedByDefault).isNull();
    }

    @Test
    public void testFindFocusedByDefaultView_skipInvisibleView() throws InterruptedException {
        TestUtils.accept(mFocusArea3, v -> v.setVisibility(INVISIBLE));
        assertThat(mFocusArea3.getVisibility()).isEqualTo(INVISIBLE);
        View focusedByDefault = ViewUtils.findFocusedByDefaultView(mRoot);
        assertThat(focusedByDefault).isNull();
    }

    @Test
    public void testFindFocusedByDefaultView_skipInvisibleAncestor() throws InterruptedException {
        TestUtils.accept(mRoot, v -> v.setVisibility(INVISIBLE));
        View focusedByDefault = ViewUtils.findFocusedByDefaultView(mFocusArea3);
        assertThat(focusedByDefault).isNull();
    }

    @Test
    public void testFindImplicitDefaultFocusView_inRoot() {
        View firstItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(0);
        View implicitDefaultFocus = ViewUtils.findImplicitDefaultFocusView(mRoot);
        assertThat(implicitDefaultFocus).isEqualTo(firstItem);
    }

    @Test
    public void testFindImplicitDefaultFocusView_inFocusArea() {
        View firstItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(0);
        View implicitDefaultFocus =
                ViewUtils.findImplicitDefaultFocusView(mFocusArea5);
        assertThat(implicitDefaultFocus).isEqualTo(firstItem);
    }

    @Test
    public void testFindImplicitDefaultFocusView_skipInvisibleAncestor()
            throws InterruptedException {
        TestUtils.accept(mRoot, v -> v.setVisibility(INVISIBLE));
        View implicitDefaultFocus = ViewUtils.findImplicitDefaultFocusView(mFocusArea5);
        assertThat(implicitDefaultFocus).isNull();
    }

    @Test
    public void testFindImplicitDefaultFocusView_selectedItem_inFocusArea()
            throws InterruptedException {
        View selectedItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(1);
        TestUtils.accept(selectedItem, v -> v.setSelected(true));
        View implicitDefaultFocus =
                ViewUtils.findImplicitDefaultFocusView(mFocusArea5);
        assertThat(implicitDefaultFocus).isEqualTo(selectedItem);
    }

    @Test
    public void testFindFirstFocusableDescendant() throws InterruptedException {
        TestUtils.accept(mFocusArea2, v -> v.setFocusable(true));
        View firstFocusable = ViewUtils.findFirstFocusableDescendant(mRoot);
        assertThat(firstFocusable).isEqualTo(mFocusArea2);
    }

    @Test
    public void testFindFirstFocusableDescendant_skipItself() throws InterruptedException {
        TestUtils.accept(mFocusArea2, v -> v.setFocusable(true));
        View firstFocusable = ViewUtils.findFirstFocusableDescendant(mFocusArea2);
        assertThat(firstFocusable).isEqualTo(mView2);
    }

    @Test
    public void testFindFirstFocusableDescendant_skipInvisibleAndGoneView()
            throws InterruptedException {
        TestUtils.accept(mRoot, v -> {
            mFocusArea2.setVisibility(INVISIBLE);
            mFocusArea3.setVisibility(GONE);
        });
        View firstFocusable = ViewUtils.findFirstFocusableDescendant(mRoot);
        assertThat(firstFocusable).isEqualTo(mView4);
    }

    @Test
    public void testFindFirstFocusableDescendant_skipInvisibleAncestor()
            throws InterruptedException {
        TestUtils.accept(mRoot, v -> v.setVisibility(View.INVISIBLE));
        View firstFocusable = ViewUtils.findFirstFocusableDescendant(mFocusArea2);
        assertThat(firstFocusable).isNull();
    }

    @Test
    public void testIsImplicitDefaultFocusView_firstItem() {
        View firstItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(0);
        assertThat(ViewUtils.isImplicitDefaultFocusView(firstItem)).isTrue();
    }

    @Test
    public void testIsImplicitDefaultFocusView_secondItem() {
        View secondItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(1);
        assertThat(ViewUtils.isImplicitDefaultFocusView(secondItem)).isFalse();
    }

    @Test
    public void testIsImplicitDefaultFocusView_normalView() {
        assertThat(ViewUtils.isImplicitDefaultFocusView(mView2)).isFalse();
    }

    @Test
    public void testIsImplicitDefaultFocusView_skipInvisibleAncestor() throws InterruptedException {
        TestUtils.accept(mFocusArea5, v -> v.setVisibility(View.INVISIBLE));
        View firstItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(0);
        assertThat(ViewUtils.isImplicitDefaultFocusView(firstItem)).isFalse();
    }

    @Test
    public void testIsImplicitDefaultFocusView_selectedItem() throws InterruptedException {
        View selectedItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(1);
        TestUtils.accept(selectedItem, v -> v.setSelected(true));
        assertThat(ViewUtils.isImplicitDefaultFocusView(selectedItem)).isTrue();
    }

    @Test
    public void testRequestFocus() throws InterruptedException {
        TestUtils.requestFocusAndAssertFocused(mView2, true);
    }

    @Test
    public void testRequestFocus_nullView() throws InterruptedException {
        TestUtils.requestFocusAndAssertFocused(null, false);
    }

    @Test
    public void testRequestFocus_alreadyFocused() throws InterruptedException {
        TestUtils.requestFocusAndAssertFocused(mView2, true);
        // mView2 is already focused before requesting focus.
        TestUtils.requestFocusAndAssertFocused(mView2, true);
    }

    @Test
    public void testRequestFocus_notFocusable() throws InterruptedException {
        TestUtils.accept(mView2, v -> v.setFocusable(false));
        TestUtils.requestFocusAndAssertFocused(mView2, false);
    }

    @Test
    public void testRequestFocus_disabled() throws InterruptedException {
        TestUtils.accept(mView2, v -> v.setEnabled(false));
        TestUtils.requestFocusAndAssertFocused(mView2, false);
    }

    @Test
    public void testRequestFocus_notVisible() throws InterruptedException {
        TestUtils.accept(mView2, v -> v.setVisibility(View.INVISIBLE));
        TestUtils.requestFocusAndAssertFocused(mView2, false);
    }

    @Test
    public void testRequestFocus_skipInvisibleAncestor() throws InterruptedException {
        TestUtils.accept(mFocusArea2, v -> v.setVisibility(View.INVISIBLE));
        TestUtils.requestFocusAndAssertFocused(mView2, false);
    }

    @Test
    public void testRequestFocus_zeroWidth() throws InterruptedException {
        TestUtils.accept(mView2, v -> v.setRight(v.getLeft()));
        assertThat(mView2.getWidth()).isEqualTo(0);
        TestUtils.requestFocusAndAssertFocused(mView2, false);
    }

    @Test
    public void testRequestFocus_detachedFromWindow() throws InterruptedException {
        TestUtils.accept(mFocusArea2, v -> mFocusArea2.removeView(mView2));
        // mView2 is detached from window, so post the Runnable on another view mFocusArea2.
        TestUtils.requestFocusAndAssertFocused(mView2, false,
                /* viewToPostRunnable= */ mFocusArea2);
    }

    @Test
    public void testRequestFocus_FocusParkingView() throws InterruptedException {
        TestUtils.requestFocusAndAssertFocused(mView2, true);
        TestUtils.requestFocusAndAssertFocused(mFpv, false);
    }

    @Test
    public void testAdjustFocus_rotaryContainer() throws InterruptedException {
        mRoot.post(() -> setRotaryScrollEnabled(mCarUiRecyclerView5.getView(), false));
        // This test verifies that the rotary container can't be focused because it's not focusable.
        // Instead, its focusable descendant should get focused.
        TestUtils.adjustFocusAndAssertFocusAdjusted(mCarUiRecyclerView5.getView(), NO_FOCUS, true);
        View firstItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(0);
        assertThat(firstItem.isFocused()).isTrue();
    }

    @Test
    public void testAdjustFocus_scrollableContainer() throws InterruptedException {
        // This test verifies that the scrollable container can't be focused. Thought it's
        // focusable, its focusable descendant should get focused.
        TestUtils.adjustFocusAndAssertFocusAdjusted(mCarUiRecyclerView5.getView(), NO_FOCUS, true);
        View firstItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(0);
        assertThat(firstItem.isFocused()).isTrue();
    }

    @Test
    public void testAdjustFocus_inRoot() throws InterruptedException {
        TestUtils.requestFocusAndAssertFocused(mView2, true);
        TestUtils.accept(mRoot, v -> ViewUtils.adjustFocus(mRoot, null));
        assertThat(mFocusedByDefault3.isFocused()).isTrue();
    }

    @Test
    public void testAdjustFocus_inFocusAreaWithDefaultFocus() throws InterruptedException {
        TestUtils.requestFocusAndAssertFocused(mView2, true);
        TestUtils.accept(mRoot, v -> ViewUtils.adjustFocus(mFocusArea3, null));
        assertThat(mFocusedByDefault3.isFocused()).isTrue();
    }

    @Test
    public void testAdjustFocus_inFocusAreaWithoutDefaultFocus() throws InterruptedException {
        TestUtils.requestFocusAndAssertFocused(mView4, true);
        TestUtils.accept(mRoot, v -> ViewUtils.adjustFocus(mFocusArea2, null));
        assertThat(mView2.isFocused()).isTrue();
    }

    @Test
    public void testAdjustFocus_inFocusAreaWithoutFocusableDescendant()
            throws InterruptedException {
        TestUtils.requestFocusAndAssertFocused(mView2, true);
        boolean success = TestUtils.test(mRoot, v -> ViewUtils.adjustFocus(mFocusArea1, null));
        assertThat(success).isFalse();
        assertThat(mFocusArea1.hasFocus()).isFalse();
    }

    @Test
    public void testAdjustFocus_differentFocusLevels() throws InterruptedException {
        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea2, SCROLLABLE_CONTAINER_FOCUS, true);
        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea2, REGULAR_FOCUS, false);

        mRoot.post(() -> mView2.setSelected(true));
        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea2, REGULAR_FOCUS, true);
        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea2, SELECTED_FOCUS, false);

        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea5, SELECTED_FOCUS, true);
        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea5, IMPLICIT_DEFAULT_FOCUS, false);

        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea4, IMPLICIT_DEFAULT_FOCUS, true);
        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea4, DEFAULT_FOCUS, false);

        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea3, DEFAULT_FOCUS, true);
        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea3, FOCUSED_BY_DEFAULT, false);

        mRoot.post(() -> {
            View firstItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(0);
            firstItem.setFocusable(false);
            View secondItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(1);
            secondItem.setFocusable(false);
        });
        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea5, NO_FOCUS, true);
        ViewUtils.adjustFocus(mFocusArea5, NO_FOCUS);
        TestUtils.adjustFocusAndAssertFocusAdjusted(mFocusArea5, SCROLLABLE_CONTAINER_FOCUS, false);
    }

    @Test
    public void testGetFocusLevel() {
        assertThat(ViewUtils.getFocusLevel(null)).isEqualTo(NO_FOCUS);
        assertThat(ViewUtils.getFocusLevel(mFpv)).isEqualTo(NO_FOCUS);
        mFocusArea2.setVisibility(INVISIBLE);
        assertThat(ViewUtils.getFocusLevel(mView2)).isEqualTo(NO_FOCUS);

        // SCROLLABLE_CONTAINER_FOCUS is tested in testGetAncestorScrollableContainer().

        assertThat(ViewUtils.getFocusLevel(mView4)).isEqualTo(REGULAR_FOCUS);

        mView4.setSelected(true);
        assertThat(ViewUtils.getFocusLevel(mView4)).isEqualTo(SELECTED_FOCUS);

        View firstItem = mCarUiRecyclerView5.getLayoutManager().findViewByPosition(0);
        assertThat(ViewUtils.getFocusLevel(firstItem)).isEqualTo(IMPLICIT_DEFAULT_FOCUS);

        assertThat(ViewUtils.getFocusLevel(mDefaultFocus4)).isEqualTo(DEFAULT_FOCUS);

        assertThat(ViewUtils.getFocusLevel(mFocusedByDefault3)).isEqualTo(FOCUSED_BY_DEFAULT);
    }

    @Test
    public void testInitFocus_inLazyLayoutView1() throws InterruptedException {
        ViewUtils.LazyLayoutView lazyLayoutView =
                (ViewUtils.LazyLayoutView) mCarUiRecyclerView5;
        assertThat(lazyLayoutView.isLayoutCompleted()).isTrue();
        TestUtils.requestFocusAndAssertFocused(mView2, true);
        mRoot.post(() -> ViewUtils.initFocus(lazyLayoutView));
        waitForFocusRestored();
        // The focus shouldn't change because there was a visible focus.
        assertThat(mView2.isFocused()).isTrue();
    }

    @Test
    public void testInitFocus_inLazyLayoutView2() throws InterruptedException {
        ViewUtils.LazyLayoutView lazyLayoutView =
                (ViewUtils.LazyLayoutView) mCarUiRecyclerView5;
        assertThat(lazyLayoutView.isLayoutCompleted()).isTrue();
        TestUtils.hideFocusAndAssertFocusHidden(mRoot, mFpv);
        mRoot.post(() -> ViewUtils.initFocus(lazyLayoutView));
        waitForFocusRestored();
        // The focus should move into the lazyLayoutView because there was no visible focus.
        assertThat(mCarUiRecyclerView5.getView().hasFocus()).isTrue();
    }

    @Test
    public void testInitFocus_inLazyLayoutView3() throws InterruptedException {
        ViewUtils.LazyLayoutView lazyLayoutView =
                (ViewUtils.LazyLayoutView) mCarUiRecyclerView6;
        assertThat(lazyLayoutView.isLayoutCompleted()).isFalse();
        TestUtils.hideFocusAndAssertFocusHidden(mRoot, mFpv);
        mRoot.post(() -> {
            // mCarUiRecyclerView6 hasn't completed layout when initializing focus.
            ViewUtils.initFocus(lazyLayoutView);
            mCarUiRecyclerView6.setAdapter(new TestAdapter(/* numItems= */ 2));
        });
        waitForFocusRestored();
        // mCarUiRecyclerView6 has completed layout, so the focus should be restored successfully.
        assertThat(lazyLayoutView.isLayoutCompleted()).isTrue();
        assertThat(mCarUiRecyclerView6.getView().hasFocus()).isTrue();
    }

    @Test
    public void testInitFocus_inLazyLayoutView4() throws InterruptedException {
        ViewUtils.LazyLayoutView lazyLayoutView =
                (ViewUtils.LazyLayoutView) mCarUiRecyclerView6;
        assertThat(lazyLayoutView.isLayoutCompleted()).isFalse();
        TestUtils.hideFocusAndAssertFocusHidden(mRoot, mFpv);
        mRoot.post(() -> {
            // mCarUiRecyclerView6 will never complete layout because its adapter has never been
            // set.
            ViewUtils.initFocus(lazyLayoutView);
        });
        waitForFocusRestored();
        // The focus should move to the best view in the view tree as fallback.
        assertThat(mFocusedByDefault3.isFocused()).isTrue();
    }

    @Test
    public void testInitFocus_inLazyLayoutView5() throws InterruptedException {
        ViewUtils.LazyLayoutView lazyLayoutView =
                (ViewUtils.LazyLayoutView) mCarUiRecyclerView5;
        assertThat(lazyLayoutView.isLayoutCompleted()).isTrue();
        TestUtils.hideFocusAndAssertFocusHidden(mRoot, mFpv);
        mRoot.post(() -> {
            // mCarUiRecyclerView5 has completed layout, but it is invisible now. It will become
            // visible after ViewUtils.initFocus() is called..
            mFocusArea5.setVisibility(INVISIBLE);
            ViewUtils.initFocus(lazyLayoutView);
            mFocusArea5.setVisibility(VISIBLE);
        });
        waitForFocusRestored();
        // The focus should be restored successfully.
        assertThat(mCarUiRecyclerView5.getView().hasFocus()).isTrue();
    }

    @Test
    public void testInitFocus_inLazyLayoutView6() throws InterruptedException {
        ViewUtils.LazyLayoutView lazyLayoutView =
                (ViewUtils.LazyLayoutView) mCarUiRecyclerView5;
        assertThat(lazyLayoutView.isLayoutCompleted()).isTrue();
        TestUtils.hideFocusAndAssertFocusHidden(mRoot, mFpv);
        mRoot.post(() -> {
            mFocusArea5.setVisibility(INVISIBLE);
            ViewUtils.initFocus(lazyLayoutView);
        });
        // mCarUiRecyclerView5 has completed layout, but it's invisible forever, so it should move
        // to the best view in the view tree as fallback.
        waitForFocusRestored();
        assertThat(mFocusedByDefault3.isFocused()).isTrue();
    }

    private static void waitForFocusRestored() {
        try {
            // Wait longer than RESTORE_FOCUS_RETRY_DELAY_MS to make sure the delayedTask in
            // ViewUtils.initFocusDelayed() has completed.
            Thread.sleep(RESTORE_FOCUS_RETRY_DELAY_MS + 1000);
        } catch (InterruptedException e) {
            throw new AssertionError("Unexpected InterruptedException", e);
        }
    }
}
