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

package com.android.car.ui;

import static android.view.accessibility.AccessibilityNodeInfo.ACTION_FOCUS;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.ui.actions.ViewActions.waitForView;
import static com.android.car.ui.utils.RotaryConstants.ACTION_RESTORE_DEFAULT_FOCUS;
import static com.android.car.ui.utils.ViewUtils.setRotaryScrollEnabled;

import static com.google.common.truth.Truth.assertThat;

import android.view.View;
import android.view.ViewGroup;

import androidx.test.rule.ActivityTestRule;

import com.android.car.ui.recyclerview.CarUiRecyclerView;
import com.android.car.ui.utils.TestUtils;
import com.android.car.ui.utils.ViewUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/** Unit test for {@link FocusParkingView} not in touch mode. */
public class FocusParkingViewTest {

    private static final int NUM_ITEMS = 40;

    @Rule
    public ActivityTestRule<FocusParkingViewTestActivity> mActivityRule =
            new ActivityTestRule<>(FocusParkingViewTestActivity.class);

    private FocusParkingViewTestActivity mActivity;
    private ViewGroup mParent1;
    private View mView1;
    private View mFocusedByDefault;
    private CarUiRecyclerView mList;
    private View mRoot;
    private FocusParkingView mFpv;

    @Before
    public void setUp() {
        mActivity = mActivityRule.getActivity();
        mParent1 = mActivity.findViewById(R.id.parent1);
        mView1 = mActivity.findViewById(R.id.view1);
        mFocusedByDefault = mActivity.findViewById(R.id.focused_by_default);
        mList = mActivity.findViewById(R.id.list);
        mRoot = mView1.getRootView();
        // Since FocusParkingViewTestActivity uses Theme.CarUi.NoToolbar, a FocusParkingView has
        // been added to the view tree automatically.
        mFpv = ViewUtils.findFocusParkingView(mRoot);

        mRoot.post(() -> {
            mList.setAdapter(new TestAdapter(NUM_ITEMS));
            setRotaryScrollEnabled(mList.getView(), /* isVertical= */ true);
        });
        // If we don't wait for the recyclerview items to show up, some of the tests flake
        onView(isRoot()).perform(waitForView(withText("Item 0"), 500));
    }

    @Test
    public void testGetWidthAndHeight() {
        assertThat(mFpv.getWidth()).isEqualTo(1);
        assertThat(mFpv.getHeight()).isEqualTo(1);
    }

    @Test
    public void testRequestFocus_focusOnDefaultFocus() throws Exception {
        TestUtils.hideFocusAndAssertFocusHidden(mRoot, mFpv);
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */ mFpv,
                /* viewToGetFocus= */mFocusedByDefault);
    }

    @Test
    public void testRequestFocus_doNothing() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */
                mView1, /* viewToGetFocus= */ mView1);
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */ mFpv,
                /* viewToGetFocus= */mView1);
    }

    @Test
    public void testRestoreDefaultFocus_focusOnDefaultFocus() throws Exception {
        TestUtils.hideFocusAndAssertFocusHidden(mRoot, mFpv);
        TestUtils.accept(mFpv, v -> v.restoreDefaultFocus());
        assertThat(mFocusedByDefault.isFocused()).isTrue();
    }

    @Test
    public void testRestoreDefaultFocus_doNothing() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        TestUtils.accept(mFpv, v -> v.restoreDefaultFocus());
        assertThat(mView1.isFocused()).isTrue();
    }

    @Test
    public void testOnWindowFocusChanged_loseFocus() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        TestUtils.accept(mFpv, v -> v.onWindowFocusChanged(false));
        assertThat(mFpv.isFocused()).isTrue();
    }

    @Test
    public void testOnWindowFocusChanged_focusOnDefaultFocus() throws Exception {
        TestUtils.hideFocusAndAssertFocusHidden(mRoot, mFpv);
        TestUtils.accept(mFpv, v -> v.onWindowFocusChanged(true));
        assertThat(mFocusedByDefault.isFocused()).isTrue();
    }

    @Test
    public void testPerformAccessibilityAction_actionRestoreDefaultFocus() throws Exception {
        TestUtils.hideFocusAndAssertFocusHidden(mRoot, mFpv);
        TestUtils.accept(mFpv,
                v -> v.performAccessibilityAction(ACTION_RESTORE_DEFAULT_FOCUS, null));
        assertThat(mFocusedByDefault.isFocused()).isTrue();
    }

    @Test
    public void testPerformAccessibilityAction_doNothing() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        TestUtils.accept(mFpv,
                v -> v.performAccessibilityAction(ACTION_RESTORE_DEFAULT_FOCUS, null));
        assertThat(mView1.isFocused()).isTrue();
    }

    @Test
    public void testPerformAccessibilityAction_actionFocus() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        TestUtils.accept(mFpv, v -> v.performAccessibilityAction(ACTION_FOCUS, null));
        assertThat(mFpv.isFocused()).isTrue();
    }

    @Test
    public void testRestoreFocusInRoot_recyclerViewItemRemoved() throws Exception {
        View firstItem = mList.getLayoutManager().findViewByPosition(0);
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */
                firstItem, /* viewToGetFocus= */ firstItem);
        ViewGroup parent = (ViewGroup) firstItem.getParent();
        TestUtils.accept(parent, v -> parent.removeView(firstItem));
        assertThat(mFocusedByDefault.isFocused()).isTrue();
    }

    // TODO(b/179734335) Reenable this test, and remove the asserts inside of layout listeners.
    // When an assert fails inside of a layout listener, it causes a whole bunch of tests in the
    // test suite to fail with "test did not run due to instrumentation issue.
    // See run level error for reason.", making it hard to debug.
//    @Test
//    public void testRestoreFocusInRoot_recyclerViewItemScrolledOffScreen() {
//        mList.post(() -> mList.getViewTreeObserver().addOnGlobalLayoutListener(
//                new ViewTreeObserver.OnGlobalLayoutListener() {
//                    @Override
//                    public void onGlobalLayout() {
//                        mList.getViewTreeObserver().removeOnGlobalLayoutListener(this);
//                        View firstItem = mList.getLayoutManager().findViewByPosition(0);
//                        firstItem.requestFocus();
//                        assertThat(firstItem.isFocused()).isTrue();
//
//                        mList.scrollToPosition(NUM_ITEMS - 1);
//                        mList.getViewTreeObserver().addOnGlobalLayoutListener(
//                                new ViewTreeObserver.OnGlobalLayoutListener() {
//                                    @Override
//                                    public void onGlobalLayout() {
//                                        mList.getViewTreeObserver()
//                                                .removeOnGlobalLayoutListener(this);
//                                        assertThat(mList.isFocused()).isTrue();
//                                    }
//                                });
//                    }
//                }));
//    }

    @Test
    public void testRestoreFocusInRoot_focusedViewRemoved() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        ViewGroup parent = (ViewGroup) mView1.getParent();
        TestUtils.accept(parent, v -> parent.removeView(mView1));
        assertThat(mFocusedByDefault.isFocused()).isTrue();
    }

    @Test
    public void testRestoreFocusInRoot_focusedViewDisabled() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        TestUtils.accept(mView1, v -> v.setEnabled(false));
        assertThat(mFocusedByDefault.isFocused()).isTrue();
    }

    @Test
    public void testRestoreFocusInRoot_focusedViewBecomesInvisible() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        TestUtils.accept(mView1, v -> v.setVisibility(View.INVISIBLE));
        assertThat(mFocusedByDefault.isFocused()).isTrue();
    }

    @Test
    public void testRestoreFocusInRoot_focusedViewParentBecomesInvisible() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        TestUtils.accept(mParent1, v -> v.setVisibility(View.INVISIBLE));
        assertThat(mFocusedByDefault.isFocused()).isTrue();
    }

    @Test
    public void testRequestFocus_focusesFpvWhenShouldRestoreFocusIsFalse() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        mFpv.setShouldRestoreFocus(false);
        TestUtils.accept(mFpv, v -> v.requestFocus());
        assertThat(mFpv.isFocused()).isTrue();
    }

    @Test
    public void testRestoreDefaultFocus_focusesFpvWhenShouldRestoreFocusIsFalse() throws Exception {
        TestUtils.requestFocusAndAssertFocused(/* viewToRequestFocus= */mView1,
                /* viewToGetFocus= */ mView1);
        mFpv.setShouldRestoreFocus(false);
        TestUtils.accept(mFpv, v -> v.restoreDefaultFocus());
        assertThat(mFpv.isFocused()).isTrue();
    }
}
