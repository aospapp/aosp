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

package com.android.permissioncontroller.permission.compat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.content.Context;
import android.os.SystemClock;
import android.text.Selection;
import android.text.Spannable;
import android.text.Spanned;
import android.text.method.MovementMethod;
import android.text.style.ClickableSpan;
import android.util.TypedValue;
import android.view.MotionEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.test.annotation.UiThreadTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

/**
 * Test for {@link LinkMovementMethodCompat} without using Mockito, which is unavailable for
 * in-process tests.
 *
 * @see android.text.method.cts.LinkMovementMethodTest
 */
public class LinkMovementMethodCompatTest {
    private static final String CONTENT = "clickable\nunclickable\nclickable";

    private Activity mActivity;
    private LinkMovementMethodCompat mMethod;
    private TextView mView;
    private Spannable mSpannable;
    private MockClickableSpan mClickable0;
    private MockClickableSpan mClickable1;

    @Rule
    public ActivityTestRule<Activity> mActivityRule = new ActivityTestRule<>(Activity.class);

    @Before
    public void setup() throws Throwable {
        mActivity = mActivityRule.getActivity();
        mMethod = new LinkMovementMethodCompat();

        // Set the content view with a text view which contains 3 lines,
        mActivityRule.runOnUiThread(() -> mView = new TextViewNoIme(mActivity));
        mView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 10);
        mView.setText(CONTENT, TextView.BufferType.SPANNABLE);

        mActivityRule.runOnUiThread(() -> mActivity.setContentView(mView));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();

        mSpannable = (Spannable) mView.getText();
        // make first line clickable
        mClickable0 = markClickable(0, CONTENT.indexOf('\n'));
        // make last line clickable
        mClickable1 = markClickable(CONTENT.lastIndexOf('\n'), CONTENT.length());
    }

    @Test
    public void testConstructor() {
        new LinkMovementMethodCompat();
    }

    @Test
    public void testGetInstance() {
        MovementMethod method0 = LinkMovementMethodCompat.getInstance();
        assertTrue(method0 instanceof LinkMovementMethodCompat);

        MovementMethod method1 = LinkMovementMethodCompat.getInstance();
        assertNotNull(method1);
        assertSame(method0, method1);
    }

    @UiThreadTest
    @Test
    public void testOnTouchEvent() {
        assertSelection(mSpannable, -1);

        // press on first line (Clickable)
        assertTrue(pressOnLine(0));
        assertSelectClickableLeftToRight(mSpannable, mClickable0);

        // release on first line
        mClickable0.clearClickCount();
        assertTrue(releaseOnLine(0));
        mClickable0.assertClickCount(1);

        // press on second line (unclickable)
        assertSelectClickableLeftToRight(mSpannable, mClickable0);
        // just clear selection
        pressOnLine(1);
        assertSelection(mSpannable, -1);

        // press on last line  (Clickable)
        assertTrue(pressOnLine(2));
        assertSelectClickableLeftToRight(mSpannable, mClickable1);

        // release on last line
        mClickable1.clearClickCount();
        assertTrue(releaseOnLine(2));
        mClickable1.assertClickCount(1);

        // release on second line (unclickable)
        assertSelectClickableLeftToRight(mSpannable, mClickable1);
        // just clear selection
        releaseOnLine(1);
        assertSelection(mSpannable, -1);
    }

    @UiThreadTest
    @Test
    public void testOnTouchEvent_outsideLineBounds() {
        assertSelection(mSpannable, -1);

        // press on first line (clickable)
        assertTrue(pressOnLine(0));
        assertSelectClickableLeftToRight(mSpannable, mClickable0);

        // release above first line
        mClickable0.clearClickCount();
        float x = (mView.getLayout().getLineLeft(0) + mView.getLayout().getLineRight(0)) / 2f;
        float y = -1f;
        assertFalse(performMotionAtPoint(x, y, MotionEvent.ACTION_UP));
        mClickable0.assertClickCount(0);

        // press on first line (clickable)
        assertTrue(pressOnLine(0));
        assertSelectClickableLeftToRight(mSpannable, mClickable0);

        // release to left of first line
        mClickable0.clearClickCount();
        x = mView.getLayout().getLineLeft(0) - 1f;
        y = (mView.getLayout().getLineTop(0) + mView.getLayout().getLineBottom(0)) / 2f;
        assertFalse(performMotionAtPoint(x, y, MotionEvent.ACTION_UP));
        mClickable0.assertClickCount(0);

        // press on first line (clickable)
        assertTrue(pressOnLine(0));
        assertSelectClickableLeftToRight(mSpannable, mClickable0);

        // release to right of first line
        mClickable0.clearClickCount();
        x = mView.getLayout().getLineRight(0) + 1f;
        y = (mView.getLayout().getLineTop(0) + mView.getLayout().getLineBottom(0)) / 2f;
        assertFalse(performMotionAtPoint(x, y, MotionEvent.ACTION_UP));
        mClickable0.assertClickCount(0);

        // press on last line (clickable)
        assertTrue(pressOnLine(2));
        assertSelectClickableLeftToRight(mSpannable, mClickable1);

        // release below last line
        mClickable1.clearClickCount();
        x = (mView.getLayout().getLineLeft(0) + mView.getLayout().getLineRight(0)) / 2f;
        y = mView.getLayout().getHeight() + 1f;
        assertFalse(performMotionAtPoint(x, y, MotionEvent.ACTION_UP));
        mClickable1.assertClickCount(0);
    }

    private MockClickableSpan markClickable(final int start, final int end) throws Throwable {
        final MockClickableSpan clickableSpan = new MockClickableSpan();
        mActivityRule.runOnUiThread(() -> mSpannable.setSpan(clickableSpan, start, end,
                Spanned.SPAN_MARK_MARK));
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        return clickableSpan;
    }
    private boolean performMotionAtPoint(float x, float y, int action) {
        long now = SystemClock.uptimeMillis();
        return mMethod.onTouchEvent(mView, mSpannable,
                MotionEvent.obtain(now, now, action, x, y, 0));
    }

    private boolean performMotionOnLine(int line, int action) {
        float x = (mView.getLayout().getLineLeft(line) + mView.getLayout().getLineRight(line)) / 2f;
        float y = (mView.getLayout().getLineTop(line) + mView.getLayout().getLineBottom(line)) / 2f;
        return performMotionAtPoint(x, y, action);
    }

    private boolean pressOnLine(int line) {
        return performMotionOnLine(line, MotionEvent.ACTION_DOWN);
    }

    private boolean releaseOnLine(int line) {
        return performMotionOnLine(line, MotionEvent.ACTION_UP);
    }

    private void assertSelection(Spannable spannable, int start, int end) {
        assertEquals(start, Selection.getSelectionStart(spannable));
        assertEquals(end, Selection.getSelectionEnd(spannable));
    }

    private void assertSelection(Spannable spannable, int position) {
        assertSelection(spannable, position, position);
    }

    private void assertSelectClickableLeftToRight(Spannable spannable,
            ClickableSpan clickableSpan) {
        assertSelection(spannable, spannable.getSpanStart(clickableSpan),
                spannable.getSpanEnd(clickableSpan));
    }

    public static class TextViewNoIme extends TextView {
        public TextViewNoIme(@NonNull Context context) {
            super(context);
        }

        @Override
        public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
            return null;
        }
    }

    public static class MockClickableSpan extends ClickableSpan {
        private int mClickCount = 0;

        @Override
        public void onClick(@NonNull View widget) {
            ++mClickCount;
        }

        public void assertClickCount(int expectedClickCount) {
            assertEquals(expectedClickCount, mClickCount);
        }

        public void clearClickCount() {
            mClickCount = 0;
        }
    }
}
