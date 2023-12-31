/*
 * Copyright (C) 2016 The Android Open Source Project
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

package android.text.method.cts;

import static android.view.ViewGroup.LayoutParams.MATCH_PARENT;
import static android.view.ViewGroup.LayoutParams.WRAP_CONTENT;
import static android.widget.TextView.BufferType.EDITABLE;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import android.app.Activity;
import android.app.Instrumentation;
import android.os.SystemClock;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.method.BaseMovementMethod;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.MotionEvent.PointerProperties;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.MediumTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.ApiTest;
import com.android.compatibility.common.util.WidgetTestUtils;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

/**
 * Test {@link BaseMovementMethod}.
 */
@MediumTest
@RunWith(AndroidJUnit4.class)
public class BaseMovementMethodTest {
    private Instrumentation mInstrumentation;
    private BaseMovementMethod mMovementMethod;
    private TextView mTextView;

    @Rule
    public ActivityTestRule<CtsActivity> mActivityRule = new ActivityTestRule<>(CtsActivity.class);

    @Before
    public void setup() {
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mMovementMethod = new BaseMovementMethod();
    }

    @Test
    public void testOnGenericMotionEvent_horizontalScroll() throws Throwable {
        final String testLine = "some text some text";
        final String testString = testLine + "\n" + testLine;

        mActivityRule.runOnUiThread(() -> mTextView = createTextView());
        // limit lines for horizontal scroll
        mTextView.setSingleLine();
        mTextView.setText(testString, EDITABLE);

        // limit width for horizontal scroll

        setContentView(mTextView, (int) mTextView.getPaint().measureText(testLine) / 3);
        // assert the default scroll position
        assertEquals(0, mTextView.getScrollX());

        final Spannable text = (Spannable) mTextView.getText();
        final double lineSpacing = Math.ceil(mTextView.getPaint().getFontSpacing());

        // scroll right
        MotionEvent event = createScrollEvent(1, 0);
        assertTrue(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mTextView, null);
        assertEquals(lineSpacing, mTextView.getScrollX(), lineSpacing / 4);
        event.recycle();

        // scroll left
        event = createScrollEvent(-1, 0);
        assertTrue(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mTextView, null);
        assertEquals(0, mTextView.getScrollX());
        event.recycle();

        // cannot scroll to left
        event = createScrollEvent(-1, 0);
        assertFalse(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        event.recycle();

        // cannot scroll to right
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mTextView,
                () -> mTextView.scrollTo((int) mTextView.getLayout().getLineWidth(0), 0));
        event = createScrollEvent(1, 0);
        assertFalse(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        event.recycle();

        // meta shift on
        // reset scroll
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mTextView,
                () -> mTextView.scrollTo(0, 0));

        // scroll top becomes scroll right
        event = createScrollEvent(0, 1, KeyEvent.META_SHIFT_ON);
        assertTrue(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mTextView, null);
        assertEquals(lineSpacing, mTextView.getScrollX(), lineSpacing / 4);
        event.recycle();

        // scroll down becomes scroll left
        event = createScrollEvent(0, -1, KeyEvent.META_SHIFT_ON);
        assertTrue(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mTextView, null);
        assertEquals(0, mTextView.getScrollX());
        event.recycle();
    }

    @Test
    public void testOnGenericMotionEvent_verticalScroll() throws Throwable {
        final String testLine = "some text some text";
        final String testString = testLine + "\n" + testLine;

        mActivityRule.runOnUiThread(() -> mTextView = createTextView());
        // limit lines for vertical scroll
        mTextView.setMaxLines(1);
        mTextView.setText(testString, EDITABLE);
        setContentView(mTextView, WRAP_CONTENT);
        // assert the default scroll positions
        assertEquals(0, mTextView.getScrollY());

        final Spannable text = (Spannable) mTextView.getText();
        final int lineHeight = mTextView.getLineHeight();

        // scroll down
        MotionEvent event = createScrollEvent(0, -1);
        assertTrue(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mTextView, null);
        assertEquals(lineHeight, mTextView.getScrollY(), lineHeight / 4);
        event.recycle();

        // scroll up
        event = createScrollEvent(0, 1);
        assertTrue(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mTextView, null);
        assertEquals(0, mTextView.getScrollY());
        event.recycle();

        // cannot scroll up
        event = createScrollEvent(0, 1);
        assertFalse(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        event.recycle();

        // cannot scroll down
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mTextView,
                () -> mTextView.scrollTo(0, mTextView.getLayout().getHeight()));
        event = createScrollEvent(0, -1);
        assertFalse(mMovementMethod.onGenericMotionEvent(mTextView, text, event));
        event.recycle();
    }

    private static class MockMovementMethod extends BaseMovementMethod {
        public int previousParagraphCallCount = 0;
        public int nextParagraphCallCount = 0;

        @Override
        public boolean previousParagraph(TextView widget, Spannable buffer) {
            previousParagraphCallCount++;
            return true;
        }

        @Override
        public boolean nextParagraph(TextView widget, Spannable buffer) {
            nextParagraphCallCount++;
            return true;
        }
    }

    @Test
    @UiThreadTest
    @ApiTest(apis = "android.text.method.BaseMovementMethod#previousParagraph")
    public void testCallPreviousParagraph() throws Throwable {
        mTextView = createTextView();
        SpannableString spannable = new SpannableString(mTextView.getText());
        mMovementMethod.previousParagraph(mTextView, spannable);
    }

    @Test
    @UiThreadTest
    @ApiTest(apis = "android.text.method.BaseMovementMethod#nextParagraph")
    public void testCallNextParagraph() throws Throwable {
        mTextView = createTextView();
        SpannableString spannable = new SpannableString(mTextView.getText());
        mMovementMethod.nextParagraph(mTextView, spannable);
    }

    @Test
    @UiThreadTest
    @ApiTest(apis = "android.text.method.BaseMovementMethod#previousParagraph")
    public void previousParagraphCall() {
        TextView view = createTextView();
        SpannableString spannable = new SpannableString(view.getText());
        MockMovementMethod method = new MockMovementMethod();
        long downTime = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(
                downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_UP, 0,
                KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_ON);

        method.onKeyDown(view, spannable, event.getKeyCode(), event);
        assertEquals(1, method.previousParagraphCallCount);
    }

    @Test
    @UiThreadTest
    @ApiTest(apis = "android.text.method.BaseMovementMethod#nextParagraph")
    public void nextParagraphCall() {
        TextView view = createTextView();
        SpannableString spannable = new SpannableString(view.getText());
        MockMovementMethod method = new MockMovementMethod();
        long downTime = SystemClock.uptimeMillis();
        KeyEvent event = new KeyEvent(
                downTime, downTime, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_DOWN, 0,
                KeyEvent.META_CTRL_LEFT_ON | KeyEvent.META_CTRL_ON);

        method.onKeyDown(view, spannable, event.getKeyCode(), event);
        assertEquals(1, method.nextParagraphCallCount);
    }
    private TextView createTextView() {
        final TextView textView = new TextViewNoIme(mActivityRule.getActivity());
        textView.setFocusable(true);
        textView.setEllipsize(null);
        textView.setMovementMethod(mMovementMethod);
        textView.setTextDirection(View.TEXT_DIRECTION_LTR);
        return textView;
    }

    private void setContentView(@NonNull TextView textView, int textWidth) throws Throwable {
        final Activity activity = mActivityRule.getActivity();
        final FrameLayout layout = new FrameLayout(activity);
        layout.addView(textView, new ViewGroup.LayoutParams(textWidth, WRAP_CONTENT));

        mActivityRule.runOnUiThread(() -> {
            activity.setContentView(layout, new ViewGroup.LayoutParams(MATCH_PARENT,
                    MATCH_PARENT));
            textView.setFocusableInTouchMode(true);
            textView.requestFocus();
        });
        mInstrumentation.waitForIdleSync();
        assertTrue(textView.isFocused());
    }

    private static MotionEvent createScrollEvent(int horizontal, int vertical) {
        return createScrollEvent(horizontal, vertical, 0);
    }

    private static MotionEvent createScrollEvent(int horizontal, int vertical, int meta) {
        final PointerProperties[] pointerProperties = new PointerProperties[1];
        pointerProperties[0] = new PointerProperties();
        pointerProperties[0].id = 0;
        final MotionEvent.PointerCoords[] coords = new MotionEvent.PointerCoords[1];
        coords[0] = new MotionEvent.PointerCoords();
        coords[0].setAxisValue(MotionEvent.AXIS_VSCROLL, vertical);
        coords[0].setAxisValue(MotionEvent.AXIS_HSCROLL, horizontal);
        final long time = SystemClock.uptimeMillis();
        return MotionEvent.obtain(time, time, MotionEvent.ACTION_SCROLL, 1,
                pointerProperties, coords, meta, 0, 1.0f, 1.0f, 0, 0,
                InputDevice.SOURCE_CLASS_POINTER, 0);
    }
}
