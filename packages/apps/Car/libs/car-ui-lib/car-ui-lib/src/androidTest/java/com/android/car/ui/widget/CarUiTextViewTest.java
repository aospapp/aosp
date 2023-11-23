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

package com.android.car.ui.widget;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withHint;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import android.annotation.TargetApi;
import android.graphics.Color;
import android.text.Spannable;
import android.text.SpannableString;
import android.text.Spanned;
import android.text.TextUtils;
import android.text.style.ForegroundColorSpan;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.content.ContextCompat;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.car.ui.CarUiText;
import com.android.car.ui.R;
import com.android.car.ui.TestActivity;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link CarUiTextViewTest}.
 */
@TargetApi(MIN_TARGET_API)
public class CarUiTextViewTest {
    private static final String ELLIPSIS = "…";
    private static final CharSequence LONG_CHAR_SEQUENCE =
            "Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor "
                    + "incididunt ut labore et dolore magna aliqua. Netus et malesuada fames ac "
                    + "turpis egestas maecenas pharetra convallis. At urna condimentum mattis "
                    + "pellentesque id nibh tortor. Purus in mollis nunc sed id semper risus in. "
                    + "Turpis massa tincidunt dui ut ornare lectus sit amet. Porttitor lacus "
                    + "luctus accumsan tortor posuere ac. Augue eget arcu dictum varius. Massa "
                    + "tempor nec feugiat nisl pretium fusce id velit ut. Fames ac turpis egestas"
                    + " sed tempus urna et pharetra pharetra. Tellus orci ac auctor augue mauris "
                    + "augue neque gravida. Purus viverra accumsan in nisl nisi scelerisque eu. "
                    + "Ut lectus arcu bibendum at varius vel pharetra. Penatibus et magnis dis "
                    + "parturient montes nascetur ridiculus mus. Suspendisse sed nisi lacus sed "
                    + "viverra tellus in hac habitasse.";

    @Rule
    public ActivityScenarioRule<TestActivity> mActivityRule =
            new ActivityScenarioRule<>(TestActivity.class);

    private TestActivity mActivity;

    @Before
    public void setUp() {
        mActivityRule.getScenario().onActivity(activity -> mActivity = activity);
    }

    @Test
    public void testTruncateToVariant_startAsViewGone() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        List<CharSequence> list = new ArrayList<>();
        list.add(LONG_CHAR_SEQUENCE);
        String variant = "Second string";
        list.add(variant);
        textView.setText(new CarUiText.Builder(list).setMaxLines(1).build());
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.setVisibility(View.GONE));
        container.post(() -> container.addView(textView));
        container.post(() -> container.setVisibility(View.VISIBLE));

        onView(withText(variant)).check(matches(isDisplayed()));
    }

    @Test
    public void testSpanOverLastLine() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        String hint = "Test textView";
        textView.setHint(hint);
        SpannableString text = new SpannableString(LONG_CHAR_SEQUENCE);
        ForegroundColorSpan span = new ForegroundColorSpan(Color.RED);
        text.setSpan(span, 0, text.length() - 1, Spanned.SPAN_INCLUSIVE_INCLUSIVE);
        textView.setText(new CarUiText.Builder(text).setMaxLines(3).build());
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        onView(withHint(hint)).check(matches(isDisplayed()));

        Spanned displayedText = (Spanned) textView.getText();
        assertEquals(displayedText.length(), displayedText.getSpanEnd(span));
    }

    @Test
    public void testLineBreaks_lineBreakAtEnd() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        String hint = "Test textView";
        textView.setHint(hint);
        CharSequence text = "This is line 1\nline2\nand then line\n";
        textView.setText(new CarUiText.Builder(text).setMaxLines(3).build());
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        onView(withHint(hint)).check(matches(isDisplayed()));
        assertEquals(3, textView.getLineCount());
    }

    @Test
    public void testSpan() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        String hint = "Test textView";
        SpannableString text = new SpannableString("Test");
        ForegroundColorSpan span = new ForegroundColorSpan(Color.RED);
        text.setSpan(span, 0, 4, Spanned.SPAN_INCLUSIVE_EXCLUSIVE);
        textView.setHint(hint);
        textView.setText(new CarUiText.Builder(text).setMaxLines(3).build());
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        onView(withHint(hint)).check(matches(isDisplayed()));
        assertTrue(TextUtils.equals(text, textView.getText()));
    }

    @Test
    public void testTextVariants_withCharLimit() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CharSequence> variants = new ArrayList<>();
        variants.add("Long string");
        variants.add("Short");
        CarUiText text = new CarUiText.Builder(variants).setMaxLines(1).setMaxChars(5).build();
        textView.setText(text);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        // Check for no manual truncation ellipsis.
        onView(withId(id)).check(matches(not(withText(containsString(ELLIPSIS)))));

        onView(withId(id)).check(
                matches(withText(containsString("Short"))));
    }

    @Test
    public void testTextVariants_withCharLimitNoMaxLines() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CharSequence> variants = new ArrayList<>();
        variants.add("Long string");
        variants.add("Short");
        CarUiText text = new CarUiText.Builder(variants).setMaxLines(Integer.MAX_VALUE).setMaxChars(
                5).build();
        textView.setText(text);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));
        // Check for no manual truncation ellipsis.
        onView(withId(id)).check(matches(not(withText(containsString(ELLIPSIS)))));

        onView(withId(id)).check(matches(withText(containsString("Short"))));
    }

    @Test
    public void testTextVariants_noFit() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CharSequence> variants = new ArrayList<>();
        String marker = "MARKING AS PREFERRED VARIANT";
        variants.add(marker + LONG_CHAR_SEQUENCE);
        variants.add(LONG_CHAR_SEQUENCE);
        variants.add(LONG_CHAR_SEQUENCE);
        CarUiText text = new CarUiText.Builder(variants).setMaxLines(2).build();
        textView.setText(text);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        // Check for manual truncation ellipsis.
        onView(withId(id)).check(matches(withText(containsString(ELLIPSIS))));
        assertEquals(2, textView.getLineCount());
        onView(withId(id)).check(matches(withText(containsString(marker))));
    }

    @Test
    public void testTextVariants() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CharSequence> variants = new ArrayList<>();
        variants.add(LONG_CHAR_SEQUENCE);
        variants.add("Short string");
        CarUiText text = new CarUiText.Builder(variants).setMaxLines(1).build();
        textView.setText(text);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        // Check for no manual truncation ellipsis.
        onView(withId(id)).check(matches(not(withText(containsString(ELLIPSIS)))));

        assertEquals(1, textView.getLineCount());
    }

    @Test
    public void testTextTruncation_twoShortLines() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CarUiText> lines = new ArrayList<>();
        lines.add(new CarUiText.Builder("Short text string").setMaxLines(2).build());
        lines.add(new CarUiText.Builder("Second short string").setMaxLines(2).build());
        textView.setText(lines);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        // Check for no manual truncation ellipsis.
        onView(withId(id)).check(matches(not(withText(containsString(ELLIPSIS)))));
    }

    @Test
    public void testTextTruncation_oneLongOneShort_withMaxLines() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CarUiText> lines = new ArrayList<>();
        lines.add(new CarUiText.Builder(LONG_CHAR_SEQUENCE).setMaxLines(2).build());
        lines.add(new CarUiText.Builder("Second short string").setMaxLines(2).build());
        textView.setText(lines);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        // Check for manual truncation ellipsis.
        onView(withId(id)).check(matches(withText(containsString(ELLIPSIS))));

        assertEquals(3, textView.getLineCount());
    }

    @Test
    public void testTextTruncation_oneLongOneShort_noMaxLines() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CarUiText> lines = new ArrayList<>();
        lines.add(new CarUiText.Builder(LONG_CHAR_SEQUENCE).setMaxLines(Integer.MAX_VALUE).build());
        lines.add(new CarUiText.Builder("Second short string").setMaxLines(2).build());
        textView.setText(lines);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        // Check for no manual truncation ellipsis.
        onView(withId(id)).check(matches(not(withText(containsString(ELLIPSIS)))));
    }

    @Test
    public void testTextTruncation_twoLong_withMaxLines() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CarUiText> lines = new ArrayList<>();
        lines.add(new CarUiText.Builder(LONG_CHAR_SEQUENCE).setMaxLines(3).build());
        lines.add(new CarUiText.Builder(LONG_CHAR_SEQUENCE).setMaxLines(3).build());
        textView.setText(lines);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        // Check for manual truncation ellipsis.
        onView(withId(id)).check(matches(withText(containsString(ELLIPSIS))));

        assertEquals(6, textView.getLineCount());
    }

    @Test
    public void testTitleTextTruncation_withMaxLines() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        textView.setText(new CarUiText.Builder(LONG_CHAR_SEQUENCE).setMaxLines(2).build());
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        // Check for manual truncation ellipsis.
        onView(withId(id)).check(matches(withText(containsString(ELLIPSIS))));

        assertEquals(2, textView.getLineCount());
    }

    @Test
    public void testTextTruncation_twoLong_differentMaxLines() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CarUiText> lines = new ArrayList<>();
        lines.add(new CarUiText(LONG_CHAR_SEQUENCE, 1));
        lines.add(new CarUiText(LONG_CHAR_SEQUENCE, 4));
        textView.setText(lines);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        // Check for manual truncation ellipsis.
        onView(withId(id)).check(matches(withText(containsString(ELLIPSIS))));

        assertEquals(5, textView.getLineCount());
    }

    @Test
    public void testMultipleBodyTextLines() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        CharSequence line1 = "First short string";
        CharSequence line2 = "Second short string";
        CharSequence line3 = "Third short string";

        List<CarUiText> lines = new ArrayList<>();
        lines.add(new CarUiText.Builder(line1).build());
        lines.add(new CarUiText.Builder(line2).build());
        lines.add(new CarUiText.Builder(line3).build());

        textView.setText(lines);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        String expectedText = line1 + "\n" + line2 + "\n" + line3;
        onView(withText(containsString(expectedText))).check(matches(isDisplayed()));
    }

    @Test
    public void testBodyTextSpans() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        int color = ContextCompat.getColor(mActivity, R.color.car_ui_color_accent);

        Spannable line1 = new SpannableString("This text contains color");
        line1.setSpan(new ForegroundColorSpan(color), 19, 24, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE);

        List<CarUiText> lines = new ArrayList<>();
        lines.add(new CarUiText(line1, Integer.MAX_VALUE));

        textView.setText(lines);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        onView(withId(id)).check(matches(isDisplayed()));
        assertTrue(TextUtils.equals(line1, textView.getText()));
    }

    @Test
    public void testTextWithLineBreak() {
        CarUiTextView textView = CarUiTextView.create(mActivity);
        int id = View.generateViewId();
        textView.setId(id);
        List<CarUiText> lines = new ArrayList<>();
        String firstTwoLines = "This is first line\nThis is the second line";
        String thirdLine = "\nThis is the third line";
        lines.add(new CarUiText(firstTwoLines + thirdLine, 2));

        textView.setText(lines);
        ViewGroup container = mActivity.findViewById(
                com.android.car.ui.test.R.id.test_container);
        container.post(() -> container.addView(textView));

        onView(withId(id)).check(matches(isDisplayed()));
        onView(withId(id)).check(matches(withText(firstTwoLines)));
        onView(withId(id)).check(matches(not(withText(thirdLine))));
    }
}
