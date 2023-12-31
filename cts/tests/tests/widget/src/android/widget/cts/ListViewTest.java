/*
 * Copyright (C) 2008 The Android Open Source Project
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

package android.widget.cts;

import static android.widget.cts.util.StretchEdgeUtil.fling;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.any;
import static org.mockito.Mockito.anyInt;
import static org.mockito.Mockito.anyLong;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.animation.ValueAnimator;
import android.app.ActionBar.LayoutParams;
import android.app.Activity;
import android.app.Instrumentation;
import android.app.UiAutomation;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.Drawable;
import android.os.Parcelable;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.util.Pair;
import android.util.SparseArray;
import android.util.SparseBooleanArray;
import android.util.Xml;
import android.view.InputDevice;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.LayoutAnimationController;
import android.widget.AbsListView;
import android.widget.AdapterView;
import android.widget.AdapterView.OnItemClickListener;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.EdgeEffect;
import android.widget.FrameLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.cts.util.NoReleaseEdgeEffect;
import android.widget.cts.util.StretchEdgeUtil;
import android.widget.cts.util.TestUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.test.InstrumentationRegistry;
import androidx.test.annotation.UiThreadTest;
import androidx.test.filters.LargeTest;
import androidx.test.filters.MediumTest;
import androidx.test.filters.SmallTest;
import androidx.test.rule.ActivityTestRule;
import androidx.test.runner.AndroidJUnit4;

import com.android.compatibility.common.util.CtsKeyEventUtil;
import com.android.compatibility.common.util.CtsTouchUtils;
import com.android.compatibility.common.util.PollingCheck;
import com.android.compatibility.common.util.WidgetTestUtils;

import junit.framework.Assert;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.xmlpull.v1.XmlPullParser;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

@SmallTest
@RunWith(AndroidJUnit4.class)
public class ListViewTest {
    private final String[] mCountryList = new String[] {
        "Argentina", "Australia", "China", "France", "Germany", "Italy", "Japan", "United States"
    };
    private final String[] mLongCountryList = new String[] {
        "Argentina", "Australia", "Belize", "Botswana", "Brazil", "Cameroon", "China", "Cyprus",
        "Denmark", "Djibouti", "Ethiopia", "Fiji", "Finland", "France", "Gabon", "Germany",
        "Ghana", "Haiti", "Honduras", "Iceland", "India", "Indonesia", "Ireland", "Italy",
        "Japan", "Kiribati", "Laos", "Lesotho", "Liberia", "Malaysia", "Mongolia", "Myanmar",
        "Nauru", "Norway", "Oman", "Pakistan", "Philippines", "Portugal", "Romania", "Russia",
        "Rwanda", "Singapore", "Slovakia", "Slovenia", "Somalia", "Swaziland", "Togo", "Tuvalu",
        "Uganda", "Ukraine", "United States", "Vanuatu", "Venezuela", "Zimbabwe"
    };
    private final String[] mNameList = new String[] {
        "Jacky", "David", "Kevin", "Michael", "Andy"
    };
    private final int[] mColorList = new int[] {
        Color.BLUE, Color.CYAN, Color.GREEN, Color.YELLOW, Color.RED, Color.MAGENTA
    };

    private Instrumentation mInstrumentation;
    private CtsTouchUtils mCtsTouchUtils;
    private CtsKeyEventUtil mCtsKeyEventUtil;
    private Activity mActivity;
    private ListView mListView;
    private ListView mListViewStretch;
    private TextView mTextView;
    private TextView mSecondTextView;

    private AttributeSet mAttributeSet;
    private ArrayAdapter<String> mAdapter_countries;
    private ArrayAdapter<String> mAdapter_longCountries;
    private ArrayAdapter<String> mAdapter_names;
    private ColorAdapter mAdapterColors;
    private float mPreviousDurationScale;

    @Rule
    public ActivityTestRule<ListViewCtsActivity> mActivityRule =
            new ActivityTestRule<>(ListViewCtsActivity.class);

    @Before
    public void setup() {
        mPreviousDurationScale = ValueAnimator.getDurationScale();
        ValueAnimator.setDurationScale(1.0f);
        mInstrumentation = InstrumentationRegistry.getInstrumentation();
        mCtsTouchUtils = new CtsTouchUtils(mInstrumentation.getTargetContext());
        mCtsKeyEventUtil = new CtsKeyEventUtil(mInstrumentation.getTargetContext());
        mActivity = mActivityRule.getActivity();
        XmlPullParser parser = mActivity.getResources().getXml(R.layout.listview_layout);
        mAttributeSet = Xml.asAttributeSet(parser);

        mAdapter_countries = new ArrayAdapter<>(mActivity,
                android.R.layout.simple_list_item_1, mCountryList);
        mAdapter_longCountries = new ArrayAdapter<>(mActivity,
                android.R.layout.simple_list_item_1, mLongCountryList);
        mAdapter_names = new ArrayAdapter<>(mActivity, android.R.layout.simple_list_item_1,
                mNameList);
        mAdapterColors = new ColorAdapter(mActivity, mColorList);

        mListView = (ListView) mActivity.findViewById(R.id.listview_default);
        mListViewStretch = (ListView) mActivity.findViewById(R.id.listview_stretch);
    }

    @After
    public void tearDown() {
        ValueAnimator.setDurationScale(mPreviousDurationScale);
    }

    @Test
    public void testConstructor() {
        new ListView(mActivity);
        new ListView(mActivity, mAttributeSet);
        new ListView(mActivity, mAttributeSet, 0);
    }

    @Test(expected=NullPointerException.class)
    public void testConstructorNullContext1() {
        new ListView(null);
    }

    @Test(expected=NullPointerException.class)
    public void testConstructorNullContext2() {
        new ListView(null, null);
    }

    @Test(expected=NullPointerException.class)
    public void testConstructorNullContext3() {
        new ListView(null, null, -1);
    }

    @Test
    public void testGetMaxScrollAmount() throws Throwable {
        setAdapter(mAdapter_names);
        int scrollAmount = mListView.getMaxScrollAmount();
        assertTrue(scrollAmount > 0);

        mActivityRule.runOnUiThread(() -> {
            mListView.getLayoutParams().height = 0;
            mListView.requestLayout();
        });
        PollingCheck.waitFor(() -> mListView.getHeight() == 0);

        scrollAmount = mListView.getMaxScrollAmount();
        assertEquals(0, scrollAmount);
    }

    private void setAdapter(final ArrayAdapter<String> adapter) throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(adapter));
    }

    @Test
    public void testAccessDividerHeight() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(mAdapter_countries));

        Drawable d = mListView.getDivider();
        final Rect r = d.getBounds();
        PollingCheck.waitFor(() -> r.bottom - r.top > 0);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setDividerHeight(20));

        assertEquals(20, mListView.getDividerHeight());
        assertEquals(20, r.bottom - r.top);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setDividerHeight(10));

        assertEquals(10, mListView.getDividerHeight());
        assertEquals(10, r.bottom - r.top);
    }

    @Test
    public void testAccessItemsCanFocus() {
        mListView.setItemsCanFocus(true);
        assertTrue(mListView.getItemsCanFocus());

        mListView.setItemsCanFocus(false);
        assertFalse(mListView.getItemsCanFocus());

        // TODO: how to check?
    }

    @Test
    public void testAccessAdapter() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(mAdapter_countries));

        assertSame(mAdapter_countries, mListView.getAdapter());
        assertEquals(mCountryList.length, mListView.getCount());

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(mAdapter_names));

        assertSame(mAdapter_names, mListView.getAdapter());
        assertEquals(mNameList.length, mListView.getCount());
    }

    @UiThreadTest
    @Test
    public void testAccessItemChecked() {
        // NONE mode
        mListView.setChoiceMode(ListView.CHOICE_MODE_NONE);
        assertEquals(ListView.CHOICE_MODE_NONE, mListView.getChoiceMode());

        mListView.setItemChecked(1, true);
        assertEquals(ListView.INVALID_POSITION, mListView.getCheckedItemPosition());
        assertFalse(mListView.isItemChecked(1));

        // SINGLE mode
        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        assertEquals(ListView.CHOICE_MODE_SINGLE, mListView.getChoiceMode());

        mListView.setItemChecked(2, true);
        assertEquals(2, mListView.getCheckedItemPosition());
        assertTrue(mListView.isItemChecked(2));

        mListView.setItemChecked(3, true);
        assertEquals(3, mListView.getCheckedItemPosition());
        assertTrue(mListView.isItemChecked(3));
        assertFalse(mListView.isItemChecked(2));

        // test attempt to uncheck a item that wasn't checked to begin with
        mListView.setItemChecked(4, false);
        // item three should still be checked
        assertEquals(3, mListView.getCheckedItemPosition());
        assertFalse(mListView.isItemChecked(4));
        assertTrue(mListView.isItemChecked(3));
        assertFalse(mListView.isItemChecked(2));

        mListView.setItemChecked(4, true);
        assertTrue(mListView.isItemChecked(4));
        mListView.clearChoices();
        assertEquals(ListView.INVALID_POSITION, mListView.getCheckedItemPosition());
        assertFalse(mListView.isItemChecked(4));

        // MULTIPLE mode
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE);
        assertEquals(ListView.CHOICE_MODE_MULTIPLE, mListView.getChoiceMode());

        mListView.setItemChecked(1, true);
        assertEquals(ListView.INVALID_POSITION, mListView.getCheckedItemPosition());
        SparseBooleanArray array = mListView.getCheckedItemPositions();
        assertTrue(array.get(1));
        assertFalse(array.get(2));
        assertTrue(mListView.isItemChecked(1));
        assertFalse(mListView.isItemChecked(2));

        mListView.setItemChecked(2, true);
        mListView.setItemChecked(3, false);
        mListView.setItemChecked(4, true);

        assertTrue(array.get(1));
        assertTrue(array.get(2));
        assertFalse(array.get(3));
        assertTrue(array.get(4));
        assertTrue(mListView.isItemChecked(1));
        assertTrue(mListView.isItemChecked(2));
        assertFalse(mListView.isItemChecked(3));
        assertTrue(mListView.isItemChecked(4));

        mListView.clearChoices();
        assertFalse(array.get(1));
        assertFalse(array.get(2));
        assertFalse(array.get(3));
        assertFalse(array.get(4));
        assertFalse(mListView.isItemChecked(1));
        assertFalse(mListView.isItemChecked(2));
        assertFalse(mListView.isItemChecked(3));
        assertFalse(mListView.isItemChecked(4));
    }

    @Test
    public void testAccessFooterView() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mTextView = new TextView(mActivity);
            mTextView.setText("footerview1");
            mSecondTextView = new TextView(mActivity);
            mSecondTextView.setText("footerview2");
        });

        mActivityRule.runOnUiThread(() -> mListView.setFooterDividersEnabled(true));
        assertTrue(mListView.areFooterDividersEnabled());
        assertEquals(0, mListView.getFooterViewsCount());

        mActivityRule.runOnUiThread(() -> mListView.addFooterView(mTextView, null, true));
        assertTrue(mListView.areFooterDividersEnabled());
        assertEquals(1, mListView.getFooterViewsCount());

        mActivityRule.runOnUiThread(() -> {
            mListView.setFooterDividersEnabled(false);
            mListView.addFooterView(mSecondTextView);
        });
        assertFalse(mListView.areFooterDividersEnabled());
        assertEquals(2, mListView.getFooterViewsCount());

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(mAdapter_countries));

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.removeFooterView(mTextView));
        assertFalse(mListView.areFooterDividersEnabled());
        assertEquals(1, mListView.getFooterViewsCount());

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.removeFooterView(mSecondTextView));
        assertFalse(mListView.areFooterDividersEnabled());
        assertEquals(0, mListView.getFooterViewsCount());
    }

    @UiThreadTest
    @Test
    public void testAccessHeaderView() {
        final TextView headerView1 = (TextView) mActivity.findViewById(R.id.headerview1);
        final TextView headerView2 = (TextView) mActivity.findViewById(R.id.headerview2);
        ((ViewGroup) headerView1.getParent()).removeView(headerView1);
        ((ViewGroup) headerView2.getParent()).removeView(headerView2);

        mListView.setHeaderDividersEnabled(true);
        assertTrue(mListView.areHeaderDividersEnabled());
        assertEquals(0, mListView.getHeaderViewsCount());

        mListView.addHeaderView(headerView2, null, true);
        assertTrue(mListView.areHeaderDividersEnabled());
        assertEquals(1, mListView.getHeaderViewsCount());

        mListView.setHeaderDividersEnabled(false);
        mListView.addHeaderView(headerView1);
        assertFalse(mListView.areHeaderDividersEnabled());
        assertEquals(2, mListView.getHeaderViewsCount());

        mListView.removeHeaderView(headerView2);
        assertFalse(mListView.areHeaderDividersEnabled());
        assertEquals(1, mListView.getHeaderViewsCount());
    }

    @Test
    public void testHeaderFooterType() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mTextView = new TextView(mActivity));
        final List<Pair<View, View>> mismatch = new ArrayList<>();
        final ArrayAdapter adapter = new ArrayAdapter<String>(mActivity,
                android.R.layout.simple_list_item_1, mNameList) {
            @Override
            public int getItemViewType(int position) {
                return position == 0 ? AdapterView.ITEM_VIEW_TYPE_HEADER_OR_FOOTER :
                        super.getItemViewType(position - 1);
            }

            @Override
            public View getView(int position, View convertView, ViewGroup parent) {
                if (position == 0) {
                    if (convertView != null && convertView != mTextView) {
                        mismatch.add(new Pair<>(mTextView, convertView));
                    }
                    return mTextView;
                } else {
                    return super.getView(position - 1, convertView, parent);
                }
            }

            @Override
            public int getCount() {
                return super.getCount() + 1;
            }
        };

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(adapter));

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                adapter::notifyDataSetChanged);

        assertEquals(0, mismatch.size());
    }

    @Test
    public void testAccessDivider() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(mAdapter_countries));

        Drawable defaultDrawable = mListView.getDivider();
        final Rect r = defaultDrawable.getBounds();
        PollingCheck.waitFor(() -> r.bottom - r.top > 0);

        final Drawable d = mActivity.getResources().getDrawable(R.drawable.scenery);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setDivider(d));
        assertSame(d, mListView.getDivider());
        assertEquals(d.getBounds().height(), mListView.getDividerHeight());

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setDividerHeight(10));
        assertEquals(10, mListView.getDividerHeight());
        assertEquals(10, d.getBounds().height());
    }

    @Test
    public void testSetSelection() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(mAdapter_countries));

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setSelection(1));
        String item = (String) mListView.getSelectedItem();
        assertEquals(mCountryList[1], item);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setSelectionFromTop(5, 0));
        item = (String) mListView.getSelectedItem();
        assertEquals(mCountryList[5], item);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                mListView::setSelectionAfterHeaderView);
        item = (String) mListView.getSelectedItem();
        assertEquals(mCountryList[0], item);
    }

    @Test
    public void testPerformItemClick() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(mAdapter_countries));

        mListView.setChoiceMode(ListView.CHOICE_MODE_SINGLE);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setSelection(2));

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mTextView = (TextView) mAdapter_countries.getView(2, null, mListView));
        assertNotNull(mTextView);
        assertEquals(mCountryList[2], mTextView.getText().toString());
        final long itemID = mAdapter_countries.getItemId(2);
        assertEquals(2, itemID);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.performItemClick(mTextView, 2, itemID));

        OnItemClickListener onClickListener = mock(OnItemClickListener.class);
        mListView.setOnItemClickListener(onClickListener);
        verify(onClickListener, never()).onItemClick(any(AdapterView.class), any(View.class),
                anyInt(), anyLong());

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.performItemClick(mTextView, 2, itemID));

        verify(onClickListener, times(1)).onItemClick(mListView, mTextView, 2, 2L);
        verifyNoMoreInteractions(onClickListener);
    }

    @UiThreadTest
    @Test
    public void testSaveAndRestoreInstanceState_positionIsRestored() {
        mListView.setAdapter(mAdapter_countries);
        assertEquals(0, mListView.getSelectedItemPosition());

        int positionToTest = mAdapter_countries.getCount() - 1;
        mListView.setSelection(positionToTest);
        assertEquals(positionToTest, mListView.getSelectedItemPosition());
        Parcelable savedState = mListView.onSaveInstanceState();

        mListView.setSelection(positionToTest - 1);
        assertEquals(positionToTest - 1, mListView.getSelectedItemPosition());

        mListView.onRestoreInstanceState(savedState);
        int measureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY);
        mListView.measure(measureSpec,measureSpec);
        mListView.layout(0, 0, 100, 100);
        assertEquals(positionToTest, mListView.getSelectedItemPosition());
    }

    @Test
    public void testDispatchKeyEvent() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> {
                    mListView.setAdapter(mAdapter_countries);
                    mListView.requestFocus();
                });
        assertTrue(mListView.hasFocus());

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setSelection(1));
        String item = (String) mListView.getSelectedItem();
        assertEquals(mCountryList[1], item);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () ->  {
                    KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_A);
                    mListView.dispatchKeyEvent(keyEvent);
                });

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> {
                    KeyEvent keyEvent = new KeyEvent(KeyEvent.ACTION_DOWN,
                            KeyEvent.KEYCODE_DPAD_DOWN);
                    mListView.dispatchKeyEvent(keyEvent);
                    mListView.dispatchKeyEvent(keyEvent);
                    mListView.dispatchKeyEvent(keyEvent);
                });
        item = (String)mListView.getSelectedItem();
        assertEquals(mCountryList[4], item);
    }

    @Test
    public void testRequestChildRectangleOnScreen() throws Throwable {
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(mAdapter_countries));

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mTextView = (TextView) mAdapter_countries.getView(0, null, mListView));
        assertNotNull(mTextView);
        assertEquals(mCountryList[0], mTextView.getText().toString());

        Rect rect = new Rect(0, 0, 10, 10);
        assertFalse(mListView.requestChildRectangleOnScreen(mTextView, rect, false));

        // TODO: how to check?
    }

    @UiThreadTest
    @Test
    public void testCanAnimate() {
        MyListView listView = new MyListView(mActivity, mAttributeSet);

        assertFalse(listView.canAnimate());
        listView.setAdapter(mAdapter_countries);
        assertFalse(listView.canAnimate());

        LayoutAnimationController controller = new LayoutAnimationController(
                mActivity, mAttributeSet);
        listView.setLayoutAnimation(controller);

        assertTrue(listView.canAnimate());
    }


    @UiThreadTest
    @Test
    public void testFindViewTraversal() {
        MyListView listView = new MyListView(mActivity, mAttributeSet);
        TextView headerView = (TextView) mActivity.findViewById(R.id.headerview1);
        ((ViewGroup) headerView.getParent()).removeView(headerView);

        assertNull(listView.findViewTraversal(R.id.headerview1));

        listView.addHeaderView(headerView);
        assertNotNull(listView.findViewTraversal(R.id.headerview1));
        assertSame(headerView, listView.findViewTraversal(R.id.headerview1));
    }

    @UiThreadTest
    @Test
    public void testFindViewWithTagTraversal() {
        MyListView listView = new MyListView(mActivity, mAttributeSet);
        TextView headerView = (TextView) mActivity.findViewById(R.id.headerview1);
        ((ViewGroup) headerView.getParent()).removeView(headerView);

        assertNull(listView.findViewWithTagTraversal("header"));

        headerView.setTag("header");
        listView.addHeaderView(headerView);
        assertNotNull(listView.findViewWithTagTraversal("header"));
        assertSame(headerView, listView.findViewWithTagTraversal("header"));
    }

    /**
     * MyListView for test
     */
    private static class MyListView extends ListView {
        public MyListView(Context context, AttributeSet attrs) {
            super(context, attrs);
        }

        @Override
        protected boolean canAnimate() {
            return super.canAnimate();
        }

        @Override
        protected void dispatchDraw(Canvas canvas) {
            super.dispatchDraw(canvas);
        }

        @Override
        protected View findViewTraversal(int id) {
            return super.findViewTraversal(id);
        }

        @Override
        protected View findViewWithTagTraversal(Object tag) {
            return super.findViewWithTagTraversal(tag);
        }

        @Override
        protected void layoutChildren() {
            super.layoutChildren();
        }
    }

    @MediumTest
    @UiThreadTest
    @Test
    public void testRequestLayoutCallsMeasure() {
        List<String> items = new ArrayList<>();
        items.add("hello");
        MockAdapter<String> adapter = new MockAdapter<>(mActivity, 0, items);
        mListView.setAdapter(adapter);

        int measureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY);

        adapter.notifyDataSetChanged();
        mListView.measure(measureSpec, measureSpec);
        mListView.layout(0, 0, 100, 100);

        MockView childView = (MockView) mListView.getChildAt(0);

        childView.requestLayout();
        childView.onMeasureCalled = false;
        mListView.measure(measureSpec, measureSpec);
        mListView.layout(0, 0, 100, 100);
        Assert.assertTrue(childView.onMeasureCalled);
    }

    @MediumTest
    @UiThreadTest
    @Test
    public void testNoSelectableItems() throws Exception {
        // We use a header as the unselectable item to remain after the selectable one is removed.
        mListView.addHeaderView(new View(mActivity), null, false);
        List<String> items = new ArrayList<>();
        items.add("hello");
        MockAdapter<String> adapter = new MockAdapter<>(mActivity, 0, items);
        mListView.setAdapter(adapter);

        mListView.setSelection(1);

        int measureSpec = View.MeasureSpec.makeMeasureSpec(100, View.MeasureSpec.EXACTLY);

        adapter.notifyDataSetChanged();
        mListView.measure(measureSpec, measureSpec);
        mListView.layout(0, 0, 100, 100);

        items.remove(0);

        adapter.notifyDataSetChanged();
        mListView.measure(measureSpec, measureSpec);
        mListView.layout(0, 0, 100, 100);
    }

    @MediumTest
    @Test
    public void testFullDetachHeaderViewOnScroll() throws Throwable {
        final AttachDetachAwareView header = new AttachDetachAwareView(mActivity);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.setAdapter(new DummyAdapter(1000));
            mListView.addHeaderView(header);
        });
        assertEquals("test sanity", 1, header.mOnAttachCount);
        assertEquals("test sanity", 0, header.mOnDetachCount);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.scrollListBy(mListView.getHeight() * 3);
        });
        assertNull("test sanity, header should be removed", header.getParent());
        assertEquals("header view should be detached", 1, header.mOnDetachCount);
        assertFalse(header.isTemporarilyDetached());
    }

    @MediumTest
    @Test
    public void testFullDetachHeaderViewOnRelayout() throws Throwable {
        final AttachDetachAwareView header = new AttachDetachAwareView(mActivity);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.setAdapter(new DummyAdapter(1000));
            mListView.addHeaderView(header);
        });
        assertEquals("test sanity", 1, header.mOnAttachCount);
        assertEquals("test sanity", 0, header.mOnDetachCount);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setSelection(800));
        assertNull("test sanity, header should be removed", header.getParent());
        assertEquals("header view should be detached", 1, header.mOnDetachCount);
        assertFalse(header.isTemporarilyDetached());
    }

    @MediumTest
    @Test
    public void testFullDetachHeaderViewOnScrollForFocus() throws Throwable {
        final AttachDetachAwareView header = new AttachDetachAwareView(mActivity);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.setAdapter(new DummyAdapter(1000));
            mListView.addHeaderView(header);
        });
        assertEquals("test sanity", 1, header.mOnAttachCount);
        assertEquals("test sanity", 0, header.mOnDetachCount);
        while (header.getParent() != null) {
            assertEquals("header view should NOT be detached", 0, header.mOnDetachCount);
            mCtsKeyEventUtil.sendKeys(mInstrumentation, mListView, KeyEvent.KEYCODE_DPAD_DOWN);
            WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, null);
        }
        assertEquals("header view should be detached", 1, header.mOnDetachCount);
        assertFalse(header.isTemporarilyDetached());
    }

    @MediumTest
    @Test
    public void testFullyDetachUnusedViewOnScroll() throws Throwable {
        final AttachDetachAwareView theView = new AttachDetachAwareView(mActivity);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(new DummyAdapter(1000, theView)));
        assertEquals("test sanity", 1, theView.mOnAttachCount);
        assertEquals("test sanity", 0, theView.mOnDetachCount);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.scrollListBy(mListView.getHeight() * 2));
        assertNull("test sanity, unused view should be removed", theView.getParent());
        assertEquals("unused view should be detached", 1, theView.mOnDetachCount);
        assertFalse(theView.isTemporarilyDetached());
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.scrollListBy(-mListView.getHeight() * 2);
            // listview limits scroll to 1 page which is why we call it twice here.
            mListView.scrollListBy(-mListView.getHeight() * 2);
        });
        assertNotNull("test sanity, view should be re-added", theView.getParent());
        assertEquals("view should receive another attach call", 2, theView.mOnAttachCount);
        assertEquals("view should not receive a detach call", 1, theView.mOnDetachCount);
        assertFalse(theView.isTemporarilyDetached());
    }

    @MediumTest
    @Test
    public void testFullyDetachUnusedViewOnReLayout() throws Throwable {
        final AttachDetachAwareView theView = new AttachDetachAwareView(mActivity);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(new DummyAdapter(1000, theView)));
        assertEquals("test sanity", 1, theView.mOnAttachCount);
        assertEquals("test sanity", 0, theView.mOnDetachCount);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setSelection(800));
        assertNull("test sanity, unused view should be removed", theView.getParent());
        assertEquals("unused view should be detached", 1, theView.mOnDetachCount);
        assertFalse(theView.isTemporarilyDetached());
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setSelection(0));
        assertNotNull("test sanity, view should be re-added", theView.getParent());
        assertEquals("view should receive another attach call", 2, theView.mOnAttachCount);
        assertEquals("view should not receive a detach call", 1, theView.mOnDetachCount);
        assertFalse(theView.isTemporarilyDetached());
    }

    @MediumTest
    @Test
    public void testFullyDetachUnusedViewOnScrollForFocus() throws Throwable {
        final AttachDetachAwareView theView = new AttachDetachAwareView(mActivity);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(new DummyAdapter(1000, theView)));
        assertEquals("test sanity", 1, theView.mOnAttachCount);
        assertEquals("test sanity", 0, theView.mOnDetachCount);
        while(theView.getParent() != null) {
            assertEquals("the view should NOT be detached", 0, theView.mOnDetachCount);
            mCtsKeyEventUtil.sendKeys(mInstrumentation, mListView, KeyEvent.KEYCODE_DPAD_DOWN);
            WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, null);
        }
        assertEquals("the view should be detached", 1, theView.mOnDetachCount);
        assertFalse(theView.isTemporarilyDetached());
        while(theView.getParent() == null) {
            mCtsKeyEventUtil.sendKeys(mInstrumentation, mListView, KeyEvent.KEYCODE_DPAD_UP);
            WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, null);
        }
        assertEquals("the view should be re-attached", 2, theView.mOnAttachCount);
        assertEquals("the view should not recieve another detach", 1, theView.mOnDetachCount);
        assertFalse(theView.isTemporarilyDetached());
    }

    @MediumTest
    @Test
    public void testSetPadding() throws Throwable {
        View view = new View(mActivity);
        view.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));
        view.setMinimumHeight(30);
        final DummyAdapter adapter = new DummyAdapter(2, view);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.setLayoutParams(new FrameLayout.LayoutParams(200, 100));
            mListView.setAdapter(adapter);
        });
        assertEquals("test sanity", 200, mListView.getWidth());
        assertEquals(200, view.getWidth());
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.setPadding(10, 0, 5, 0);
            assertTrue(view.isLayoutRequested());
        });
        assertEquals(185, view.getWidth());
        assertFalse(view.isLayoutRequested());
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.setPadding(10, 0, 5, 0);
            assertFalse(view.isLayoutRequested());
        });
    }

    @MediumTest
    @Test
    public void testResolveRtlOnReAttach() throws Throwable {
        View spacer = new View(mActivity);
        spacer.setLayoutParams(new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                250));
        final DummyAdapter adapter = new DummyAdapter(50, spacer);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            mListView.setLayoutParams(new FrameLayout.LayoutParams(200, 150));
            mListView.setAdapter(adapter);
        });
        assertEquals("test sanity", 1, mListView.getChildCount());
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            // we scroll in pieces because list view caps scroll by its height
            mListView.scrollListBy(100);
            mListView.scrollListBy(100);
            mListView.scrollListBy(60);
        });
        assertEquals("test sanity", 1, mListView.getChildCount());
        assertEquals("test sanity", 1, mListView.getFirstVisiblePosition());
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.scrollListBy(-100);
            mListView.scrollListBy(-100);
            mListView.scrollListBy(-60);
        });
        assertEquals("test sanity", 1, mListView.getChildCount());
        assertEquals("item 0 should be visible", 0, mListView.getFirstVisiblePosition());
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            mListView.scrollListBy(100);
            mListView.scrollListBy(100);
            mListView.scrollListBy(60);
        });
        assertEquals("test sanity", 1, mListView.getChildCount());
        assertEquals("test sanity", 1, mListView.getFirstVisiblePosition());

        assertEquals("the view's RTL properties must be resolved",
                mListView.getChildAt(0).getLayoutDirection(), View.LAYOUT_DIRECTION_RTL);
    }

    private class MockView extends View {

        public boolean onMeasureCalled = false;

        public MockView(Context context) {
            super(context);
        }

        @Override
        protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
            super.onMeasure(widthMeasureSpec, heightMeasureSpec);
            onMeasureCalled = true;
        }
    }

    private class MockAdapter<T> extends ArrayAdapter<T> {

        public MockAdapter(Context context, int resource, List<T> objects) {
            super(context, resource, objects);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            return new MockView(getContext());
        }
    }

    @MediumTest
    @Test
    public void testRequestLayoutWithTemporaryDetach() throws Throwable {
        List<String> items = new ArrayList<>();
        items.add("0");
        items.add("1");
        items.add("2");
        final TemporarilyDetachableMockViewAdapter<String> adapter =
                new TemporarilyDetachableMockViewAdapter<>(
                        mActivity, android.R.layout.simple_list_item_1, items);
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(adapter));

        assertEquals(items.size(), mListView.getCount());
        final TemporarilyDetachableMockView childView0 =
                (TemporarilyDetachableMockView) mListView.getChildAt(0);
        final TemporarilyDetachableMockView childView1 =
                (TemporarilyDetachableMockView) mListView.getChildAt(1);
        final TemporarilyDetachableMockView childView2 =
                (TemporarilyDetachableMockView) mListView.getChildAt(2);
        assertNotNull(childView0);
        assertNotNull(childView1);
        assertNotNull(childView2);

        // Make sure that ListView#requestLayout() is optimized when nothing is changed.
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, mListView::requestLayout);
        assertEquals(childView0, mListView.getChildAt(0));
        assertEquals(childView1, mListView.getChildAt(1));
        assertEquals(childView2, mListView.getChildAt(2));
    }

    @MediumTest
    @Test
    public void testJumpDrawables() throws Throwable {
        FrameLayout layout = new FrameLayout(mActivity);
        ListView listView = new ListView(mActivity);
        ArrayAdapterWithMockDrawable adapter = new ArrayAdapterWithMockDrawable(mActivity);
        for (int i = 0; i < 50; i++) {
            adapter.add(Integer.toString(i));
        }

        // Initial state should jump exactly once during attach.
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, listView, () -> {
            listView.setAdapter(adapter);
            layout.addView(listView, new LayoutParams(LayoutParams.MATCH_PARENT, 200));
            mActivity.setContentView(layout);
        });
        assertTrue("List is not showing any children", listView.getChildCount() > 0);
        Drawable firstBackground = listView.getChildAt(0).getBackground();
        verify(firstBackground, times(1)).jumpToCurrentState();

        // Lay out views without recycling. This should not jump again.
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, listView, listView::requestLayout);
        assertSame(firstBackground, listView.getChildAt(0).getBackground());
        verify(firstBackground, times(1)).jumpToCurrentState();

        // If we're on a really big display, we might be in a position where
        // the position we're going to scroll to is already visible, in which
        // case we won't be able to test jump behavior when recycling.
        int lastVisiblePosition = listView.getLastVisiblePosition();
        int targetPosition = adapter.getCount() - 1;
        if (targetPosition <= lastVisiblePosition) {
            return;
        }

        // Reset the call counts before continuing, since the backgrounds may
        // be recycled from either views that were on-screen or in the scrap
        // heap, and those would have slightly different call counts.
        adapter.resetMockBackgrounds();

        // Scroll so that we have new views on screen. This should jump at
        // least once when the view is recycled in a new position (but may be
        // more if it was recycled from a view that was previously on-screen).
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, listView,
                () -> listView.setSelection(targetPosition));

        View lastChild = listView.getChildAt(listView.getChildCount() - 1);
        verify(lastChild.getBackground(), atLeast(1)).jumpToCurrentState();

        // Reset the call counts before continuing.
        adapter.resetMockBackgrounds();

        // Scroll back to the top. This should jump at least once when the view
        // is recycled in a new position (but may be more if it was recycled
        // from a view that was previously on-screen).
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, listView,
                () -> listView.setSelection(0));

        View firstChild = listView.getChildAt(0);
        verify(firstChild.getBackground(), atLeast(1)).jumpToCurrentState();
    }

    private static class ArrayAdapterWithMockDrawable extends ArrayAdapter<String> {
        private SparseArray<Drawable> mBackgrounds = new SparseArray<>();

        public ArrayAdapterWithMockDrawable(Context context) {
            super(context, android.R.layout.simple_list_item_1);
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            final View view = super.getView(position, convertView, parent);
            if (convertView == null) {
                if (view.getBackground() == null) {
                    view.setBackground(spy(new ColorDrawable(Color.BLACK)));
                } else {
                    view.setBackground(spy(view.getBackground()));
                }
            }
            return view;
        }

        public void resetMockBackgrounds() {
            for (int i = 0; i < mBackgrounds.size(); i++) {
                Drawable background = mBackgrounds.valueAt(i);
                reset(background);
            }
        }
    }

    private class TemporarilyDetachableMockView extends View {

        private boolean mIsDispatchingStartTemporaryDetach = false;
        private boolean mIsDispatchingFinishTemporaryDetach = false;

        public TemporarilyDetachableMockView(Context context) {
            super(context);
        }

        @Override
        public void dispatchStartTemporaryDetach() {
            mIsDispatchingStartTemporaryDetach = true;
            super.dispatchStartTemporaryDetach();
            mIsDispatchingStartTemporaryDetach = false;
        }

        @Override
        public void dispatchFinishTemporaryDetach() {
            mIsDispatchingFinishTemporaryDetach = true;
            super.dispatchFinishTemporaryDetach();
            mIsDispatchingFinishTemporaryDetach = false;
        }

        @Override
        public void onStartTemporaryDetach() {
            super.onStartTemporaryDetach();
            if (!mIsDispatchingStartTemporaryDetach) {
                throw new IllegalStateException("#onStartTemporaryDetach() must be indirectly"
                        + " called via #dispatchStartTemporaryDetach()");
            }
        }

        @Override
        public void onFinishTemporaryDetach() {
            super.onFinishTemporaryDetach();
            if (!mIsDispatchingFinishTemporaryDetach) {
                throw new IllegalStateException("#onStartTemporaryDetach() must be indirectly"
                        + " called via #dispatchFinishTemporaryDetach()");
            }
        }
    }

    private class TemporarilyDetachableMockViewAdapter<T> extends ArrayAdapter<T> {
        ArrayList<TemporarilyDetachableMockView> views = new ArrayList<>();

        public TemporarilyDetachableMockViewAdapter(Context context, int textViewResourceId,
                List<T> objects) {
            super(context, textViewResourceId, objects);
            for (int i = 0; i < objects.size(); i++) {
                views.add(new TemporarilyDetachableMockView(context));
                views.get(i).setFocusable(true);
            }
        }

        @Override
        public int getCount() {
            return views.size();
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public View getView(int position, View convertView, ViewGroup parent) {
            View result = views.get(position);
            ViewGroup.LayoutParams lp = new ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, 40);
            result.setLayoutParams(lp);
            return result;
        }
    }

    @Test
    public void testTransientStateUnstableIds() throws Throwable {
        final ListView listView = mListView;
        final ArrayList<String> items = new ArrayList<String>(Arrays.asList(mCountryList));
        final ArrayAdapter<String> adapter = new ArrayAdapter<String>(mActivity,
                android.R.layout.simple_list_item_1, items);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, listView,
                () -> listView.setAdapter(adapter));

        final View oldItem = listView.getChildAt(2);
        final CharSequence oldText = ((TextView) oldItem.findViewById(android.R.id.text1))
                .getText();
        oldItem.setHasTransientState(true);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, listView,
                () -> {
                    adapter.remove(adapter.getItem(0));
                    adapter.notifyDataSetChanged();
                });

        final View newItem = listView.getChildAt(2);
        final CharSequence newText = ((TextView) newItem.findViewById(android.R.id.text1))
                .getText();

        Assert.assertFalse(oldText.equals(newText));
    }

    @Test
    public void testTransientStateStableIds() throws Throwable {
        final ArrayList<String> items = new ArrayList<>(Arrays.asList(mCountryList));
        final StableArrayAdapter<String> adapter = new StableArrayAdapter<>(mActivity,
                android.R.layout.simple_list_item_1, items);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(adapter));

        final Object tag = new Object();
        final View oldItem = mListView.getChildAt(2);
        final CharSequence oldText = ((TextView) oldItem.findViewById(android.R.id.text1))
                .getText();
        oldItem.setHasTransientState(true);
        oldItem.setTag(tag);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> {
                    adapter.remove(adapter.getItem(0));
                    adapter.notifyDataSetChanged();
                });

        final View newItem = mListView.getChildAt(1);
        final CharSequence newText = ((TextView) newItem.findViewById(android.R.id.text1))
                .getText();

        Assert.assertTrue(newItem.hasTransientState());
        Assert.assertEquals(oldText, newText);
        Assert.assertEquals(tag, newItem.getTag());
    }

    @Test
    public void testStretchAtTop() throws Throwable {
        // Make sure that the view we care about is on screen and at the top:
        showOnlyStretch();

        NoReleaseEdgeEffect edgeEffect = new NoReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowTop = edgeEffect;
        assertTrue(StretchEdgeUtil.dragStretches(
                mActivityRule,
                mListViewStretch,
                edgeEffect,
                0,
                300
        ));
    }

    @Test
    public void testStretchTopAndCatch() throws Throwable {
        // Make sure that the view we care about is on screen and at the top:
        showOnlyStretch();

        NoReleaseEdgeEffect edgeEffect = new NoReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowTop = edgeEffect;
        assertTrue(StretchEdgeUtil.dragAndHoldKeepsStretch(
                mActivityRule,
                mListViewStretch,
                edgeEffect,
                0,
                300
        ));
    }

    private void scrollToBottomOfStretch() throws Throwable {
        do {
            mActivityRule.runOnUiThread(() -> {
                mListViewStretch.scrollListBy(50);
            });
        } while (mListViewStretch.pointToPosition(0, 40) != mColorList.length - 1);
    }

    @Test
    public void testStretchAtBottom() throws Throwable {
        // Make sure that the view we care about is on screen and at the top:
        showOnlyStretch();

        scrollToBottomOfStretch();
        NoReleaseEdgeEffect edgeEffect = new NoReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowBottom = edgeEffect;
        assertTrue(StretchEdgeUtil.dragStretches(
                mActivityRule,
                mListViewStretch,
                edgeEffect,
                0,
                -300
        ));
    }

    @Test
    public void testStretchBottomAndCatch() throws Throwable {
        // Make sure that the view we care about is on screen and at the top:
        showOnlyStretch();

        scrollToBottomOfStretch();
        NoReleaseEdgeEffect edgeEffect = new NoReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowBottom = edgeEffect;
        assertTrue(StretchEdgeUtil.dragAndHoldKeepsStretch(
                mActivityRule,
                mListViewStretch,
                edgeEffect,
                0,
                -300
        ));
    }

    @Test
    public void testFlingWhileStretchedTop() throws Throwable {
        // Make sure that the scroll view we care about is on screen and at the top:
        showOnlyStretch();

        ScrollViewTest.CaptureOnAbsorbEdgeEffect
                edgeEffect = new ScrollViewTest.CaptureOnAbsorbEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowTop = edgeEffect;
        fling(mActivityRule, mListViewStretch, 0, 300);
        assertTrue(edgeEffect.onAbsorbVelocity > 0);
    }

    @Test
    public void testFlingWhileStretchedBottom() throws Throwable {
        // Make sure that the scroll view we care about is on screen and at the top:
        showOnlyStretch();

        scrollToBottomOfStretch();

        ScrollViewTest.CaptureOnAbsorbEdgeEffect
                edgeEffect = new ScrollViewTest.CaptureOnAbsorbEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowBottom = edgeEffect;
        fling(mActivityRule, mListViewStretch, 0, -300);
        assertTrue(edgeEffect.onAbsorbVelocity > 0);
    }

    @Test
    public void testScrollAfterStretch() throws Throwable {
        showOnlyStretch();
        NoReleaseEdgeEffect edgeEffect = new NoReleaseEdgeEffect(mActivity);
        mActivityRule.runOnUiThread(() -> {
            mListViewStretch.setAdapter(new ClickColorAdapter(mActivity, mColorList));
            mListViewStretch.mEdgeGlowTop = edgeEffect;
        });
        mActivityRule.runOnUiThread(() -> {});

        int[] locationOnScreen = new int[2];
        mActivityRule.runOnUiThread(() -> {
            mListViewStretch.getLocationOnScreen(locationOnScreen);
        });

        int screenX = locationOnScreen[0];
        int screenY = locationOnScreen[1];

        int lastVisiblePositionBeforeScroll = mListViewStretch.getLastVisiblePosition();
        int firstVisiblePositionBeforeScroll = mListViewStretch.getFirstVisiblePosition();


        // Cause a stretch
        mCtsTouchUtils.emulateDragGesture(
                mInstrumentation,
                mActivityRule,
                screenX + mListViewStretch.getWidth() / 2,
                screenY + mListViewStretch.getHeight() / 2,
                0,
                300,
                300,
                20,
                false,
                null
        );

        // Now scroll the other direction
        mCtsTouchUtils.emulateDragGesture(
                mInstrumentation,
                mActivityRule,
                screenX + mListViewStretch.getWidth() / 2,
                screenY + mListViewStretch.getHeight() / 2,
                0,
                -600,
                160,
                20,
                false,
                null
        );

        int lastVisiblePositionAfterScroll = mListViewStretch.getLastVisiblePosition();
        int firstVisiblePositionAfterScroll = mListViewStretch.getFirstVisiblePosition();

        assertTrue(lastVisiblePositionAfterScroll > lastVisiblePositionBeforeScroll);
        assertTrue(firstVisiblePositionAfterScroll > firstVisiblePositionBeforeScroll);
    }

    @Test
    public void testEdgeEffectAddToBottom() throws Throwable {
        // Make sure that the view we care about is on screen and at the top:
        showOnlyStretch();

        scrollToBottomOfStretch();

        NoReleaseEdgeEffect edgeEffect = new NoReleaseEdgeEffect(mListViewStretch.getContext());
        mListViewStretch.mEdgeGlowBottom = edgeEffect;
        edgeEffect.setPauseRelease(true);

        executeWhileDragging(
                -300,
                () -> {
                    assertFalse(edgeEffect.getOnReleaseCalled());
                    try {
                        mActivityRule.runOnUiThread(() -> {
                            for (int color : mColorList) {
                                mAdapterColors.addColor(Color.BLACK);
                                mAdapterColors.addColor(color);
                            }
                        });
                    } catch (Throwable e) {
                    }
                },
                () -> {
                    assertTrue(edgeEffect.getOnReleaseCalled());
                    assertTrue(edgeEffect.getDistance() > 0);
                }
        );

        edgeEffect.finish();
        int firstVisible = mListViewStretch.getFirstVisiblePosition();

        // We've turned off the release, so the distance won't change unless onPull() is called
        executeWhileDragging(-300, () -> {}, () -> {});
        assertTrue(edgeEffect.isFinished());
        assertEquals(0f, edgeEffect.getDistance(), 0.01f);
        assertNotEquals(firstVisible, mListViewStretch.getFirstVisiblePosition());
    }

    @Test
    public void testEdgeEffectAddToTop() throws Throwable {
        // Make sure that the view we care about is on screen and at the top:
        showOnlyStretch();

        NoReleaseEdgeEffect edgeEffect = new NoReleaseEdgeEffect(mListViewStretch.getContext());
        mListViewStretch.mEdgeGlowTop = edgeEffect;
        edgeEffect.setPauseRelease(true);

        executeWhileDragging(
                300,
                () -> {
                    assertFalse(edgeEffect.getOnReleaseCalled());
                    try {
                        mActivityRule.runOnUiThread(() -> {
                            for (int color : mColorList) {
                                mAdapterColors.addColorAtStart(Color.BLACK);
                                mAdapterColors.addColorAtStart(color);
                            }
                            mListViewStretch.setSelection(mColorList.length * 2);
                        });
                    } catch (Throwable e) {
                    }
                },
                () -> {
                    assertTrue(edgeEffect.getOnReleaseCalled());
                    assertTrue(edgeEffect.getDistance() > 0);
                }
        );

        edgeEffect.finish();
        int firstVisible = mListViewStretch.getFirstVisiblePosition();

        // We've turned off the release, so the distance won't change unless onPull() is called
        executeWhileDragging(300, () -> {}, () -> {});
        assertTrue(edgeEffect.isFinished());
        assertEquals(0f, edgeEffect.getDistance(), 0.01f);
        assertNotEquals(firstVisible, mListViewStretch.getFirstVisiblePosition());
    }

    @Test
    public void scrollFromRotaryStretchesTop() throws Throwable {
        showOnlyStretch();

        CaptureOnReleaseEdgeEffect
                edgeEffect = new CaptureOnReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowTop = edgeEffect;

        mActivityRule.runOnUiThread(() -> {
            assertTrue(mListViewStretch.dispatchGenericMotionEvent(
                    createScrollEvent(2f, InputDevice.SOURCE_ROTARY_ENCODER)));
            assertFalse(edgeEffect.isFinished());
            assertTrue(edgeEffect.getDistance() > 0f);
            assertTrue(edgeEffect.onReleaseCalled);
        });
    }

    @Test
    public void scrollFromMouseDoesNotStretchTop() throws Throwable {
        showOnlyStretch();

        CaptureOnReleaseEdgeEffect
                edgeEffect = new CaptureOnReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowTop = edgeEffect;

        mActivityRule.runOnUiThread(() -> {
            assertFalse(mListViewStretch.dispatchGenericMotionEvent(
                    createScrollEvent(2f, InputDevice.SOURCE_MOUSE)));
            assertTrue(edgeEffect.isFinished());
            assertFalse(edgeEffect.onReleaseCalled);
        });
    }

    @Test
    public void scrollFromRotaryStretchesBottom() throws Throwable {
        showOnlyStretch();

        scrollToBottomOfStretch();

        CaptureOnReleaseEdgeEffect
                edgeEffect = new CaptureOnReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowBottom = edgeEffect;

        mActivityRule.runOnUiThread(() -> {
            assertTrue(mListViewStretch.dispatchGenericMotionEvent(
                    createScrollEvent(-2f, InputDevice.SOURCE_ROTARY_ENCODER)));
            assertFalse(edgeEffect.isFinished());
            assertTrue(edgeEffect.getDistance() > 0f);
            assertTrue(edgeEffect.onReleaseCalled);
        });
    }

    @Test
    public void scrollFromMouseDoesNotStretchBottom() throws Throwable {
        showOnlyStretch();

        scrollToBottomOfStretch();

        CaptureOnReleaseEdgeEffect
                edgeEffect = new CaptureOnReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowBottom = edgeEffect;

        mActivityRule.runOnUiThread(() -> {
            assertFalse(mListViewStretch.dispatchGenericMotionEvent(
                    createScrollEvent(-2f, InputDevice.SOURCE_MOUSE)));
            assertTrue(edgeEffect.isFinished());
            assertFalse(edgeEffect.onReleaseCalled);
        });
    }

    @Test
    public void flingUpWhileStretchedAtTop() throws Throwable {
        showOnlyStretch();

        CaptureOnReleaseEdgeEffect edgeEffect = new CaptureOnReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowTop = edgeEffect;

        int[] scrollStateValue = new int[1];

        mListViewStretch.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                scrollStateValue[0] = scrollState;
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                    int totalItemCount) {
            }
        });
        executeWhileDragging(1000, () -> {}, () -> {
            assertFalse(edgeEffect.isFinished());
        });
        mActivityRule.runOnUiThread(() -> {
            edgeEffect.onReleaseCalled = false;
            mListViewStretch.fling(10000);
            assertFalse(edgeEffect.onReleaseCalled);
            assertFalse(edgeEffect.isFinished());
        });
        mActivityRule.runOnUiThread(() -> {
            assertEquals(AbsListView.OnScrollListener.SCROLL_STATE_FLING, scrollStateValue[0]);
        });
        long end = SystemClock.uptimeMillis() + 4000;
        while (scrollStateValue[0] == AbsListView.OnScrollListener.SCROLL_STATE_FLING
                && SystemClock.uptimeMillis() < end) {
            // wait one frame
            mActivityRule.runOnUiThread(() -> {});
        }
        assertNotEquals(AbsListView.OnScrollListener.SCROLL_STATE_FLING, scrollStateValue[0]);
        mActivityRule.runOnUiThread(() -> {
            assertEquals(0f, edgeEffect.getDistance(), 0f);
            assertNotEquals(0, mListViewStretch.getFirstVisiblePosition());
        });
    }

    @Test
    public void flingDownWhileStretchedAtBottom() throws Throwable {
        showOnlyStretch();
        scrollToBottomOfStretch();

        int bottomItem = mListViewStretch.getLastVisiblePosition();

        CaptureOnReleaseEdgeEffect edgeEffect = new CaptureOnReleaseEdgeEffect(mActivity);
        mListViewStretch.mEdgeGlowBottom = edgeEffect;

        int[] scrollStateValue = new int[1];

        mListViewStretch.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
                scrollStateValue[0] = scrollState;
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                    int totalItemCount) {
            }
        });
        executeWhileDragging(-1000, () -> {}, () -> {
            assertFalse(edgeEffect.isFinished());
        });
        mActivityRule.runOnUiThread(() -> {
            edgeEffect.onReleaseCalled = false;
            mListViewStretch.fling(-10000);
            assertFalse(edgeEffect.onReleaseCalled);
            assertFalse(edgeEffect.isFinished());
        });
        mActivityRule.runOnUiThread(() -> {
            assertEquals(AbsListView.OnScrollListener.SCROLL_STATE_FLING, scrollStateValue[0]);
        });
        long end = SystemClock.uptimeMillis() + 4000;
        while (scrollStateValue[0] == AbsListView.OnScrollListener.SCROLL_STATE_FLING
                && SystemClock.uptimeMillis() < end) {
            // wait one frame
            mActivityRule.runOnUiThread(() -> {});
        }
        assertNotEquals(AbsListView.OnScrollListener.SCROLL_STATE_FLING, scrollStateValue[0]);
        mActivityRule.runOnUiThread(() -> {
            assertEquals(0f, edgeEffect.getDistance(), 0f);
            assertNotEquals(bottomItem, mListViewStretch.getLastVisiblePosition());
        });
    }

    private MotionEvent createScrollEvent(float scrollAmount, int source) {
        MotionEvent.PointerProperties pointerProperties = new MotionEvent.PointerProperties();
        pointerProperties.toolType = MotionEvent.TOOL_TYPE_MOUSE;
        MotionEvent.PointerCoords pointerCoords = new MotionEvent.PointerCoords();
        int axis = source == InputDevice.SOURCE_ROTARY_ENCODER ? MotionEvent.AXIS_SCROLL
                : MotionEvent.AXIS_VSCROLL;
        pointerCoords.setAxisValue(axis, scrollAmount);

        return MotionEvent.obtain(
                0, /* downTime */
                0, /* eventTime */
                MotionEvent.ACTION_SCROLL, /* action */
                1, /* pointerCount */
                new MotionEvent.PointerProperties[] { pointerProperties },
                new MotionEvent.PointerCoords[] { pointerCoords },
                0, /* metaState */
                0, /* buttonState */
                0f, /* xPrecision */
                0f, /* yPrecision */
                0, /* deviceId */
                0, /* edgeFlags */
                source, /* source */
                0 /* flags */
        );
    }

    private void executeWhileDragging(
            int dragY,
            Runnable duringDrag,
            Runnable beforeUp
    ) throws Throwable {
        int[] locationOnScreen = new int[2];
        mActivityRule.runOnUiThread(() -> {
            mListViewStretch.getLocationOnScreen(locationOnScreen);
        });

        int screenX = locationOnScreen[0] + mListViewStretch.getWidth() / 2;
        int screenY = locationOnScreen[1] + mListViewStretch.getHeight() / 2;
        Instrumentation instrumentation = InstrumentationRegistry.getInstrumentation();
        UiAutomation uiAutomation = instrumentation.getUiAutomation();
        long downTime = SystemClock.uptimeMillis();
        StretchEdgeUtil.injectDownEvent(uiAutomation, downTime, screenX, screenY);

        int middleY = screenY + (dragY / 2);
        StretchEdgeUtil.injectMoveEventsForDrag(
                uiAutomation,
                downTime,
                downTime,
                screenX,
                screenY,
                screenX,
                middleY,
                5,
                20
        );

        duringDrag.run();

        int endY = screenY + dragY;

        StretchEdgeUtil.injectMoveEventsForDrag(
                uiAutomation,
                downTime,
                downTime + 25,
                screenX,
                middleY,
                screenX,
                endY,
                5,
                20
        );

        beforeUp.run();

        StretchEdgeUtil.injectUpEvent(
                uiAutomation,
                downTime,
                downTime + 50,
                screenX,
                endY
        );
    }

    private void showOnlyStretch() throws Throwable {
        mActivityRule.runOnUiThread(() -> {
            ViewGroup parent = (ViewGroup) mListViewStretch.getParent();
            for (int i = 0; i < parent.getChildCount(); i++) {
                View child = parent.getChildAt(i);
                if (child != mListViewStretch) {
                    child.setVisibility(View.GONE);
                }
            }
            mListViewStretch.setAdapter(mAdapterColors);
            mListViewStretch.setDivider(null);
            mListViewStretch.setDividerHeight(0);
        });
        // Give it an opportunity to finish layout.
        mActivityRule.runOnUiThread(() -> {});
    }

    private static class StableArrayAdapter<T> extends ArrayAdapter<T> {
        public StableArrayAdapter(Context context, int resource, List<T> objects) {
            super(context, resource, objects);
        }

        @Override
        public long getItemId(int position) {
            return getItem(position).hashCode();
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }

    @LargeTest
    @Test
    public void testSmoothScrollByOffset() throws Throwable {
        final int itemCount = mLongCountryList.length;

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(mAdapter_longCountries));

        assertEquals(0, mListView.getFirstVisiblePosition());

        // If we're on a really big display, we might be in a situation where the position
        // we're going to scroll to is already visible. In that case the logic in the rest
        // of this test will never fire off a listener callback and then fail the test.
        final int positionToScrollTo = itemCount - 10;
        final int lastVisiblePosition = mListView.getLastVisiblePosition();
        if (positionToScrollTo <= lastVisiblePosition) {
            return;
        }

        // Register a scroll listener on our ListView. The listener will notify our latch
        // when the "target" item comes into view. If that never happens, the latch will
        // time out and fail the test.
        final CountDownLatch latch = new CountDownLatch(1);
        mListView.setOnScrollListener(new AbsListView.OnScrollListener() {
            @Override
            public void onScrollStateChanged(AbsListView view, int scrollState) {
            }

            @Override
            public void onScroll(AbsListView view, int firstVisibleItem, int visibleItemCount,
                    int totalItemCount) {
                if ((positionToScrollTo >= firstVisibleItem) &&
                        (positionToScrollTo <= (firstVisibleItem + visibleItemCount))) {
                    latch.countDown();
                }
            }
        });
        int offset = positionToScrollTo - lastVisiblePosition;
        mActivityRule.runOnUiThread(() -> mListView.smoothScrollByOffset(offset));

        boolean result = false;
        try {
            result = latch.await(20, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            // ignore
        }
        assertTrue("Timed out while waiting for the target view to be scrolled into view", result);
    }

    private static class PositionArrayAdapter<T> extends ArrayAdapter<T> {
        public PositionArrayAdapter(Context context, int resource, List<T> objects) {
            super(context, resource, objects);
        }

        @Override
        public long getItemId(int position) {
            return position;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }
    }

    @Test
    public void testGetCheckItemIds() throws Throwable {
        final ArrayList<String> items = new ArrayList<>(Arrays.asList(mCountryList));
        final ArrayAdapter<String> adapter = new PositionArrayAdapter<>(mActivity,
                android.R.layout.simple_list_item_1, items);

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setAdapter(adapter));

        mActivityRule.runOnUiThread(
                () -> mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE));
        assertTrue(mListView.getCheckItemIds().length == 0);

        mActivityRule.runOnUiThread(() -> mListView.setItemChecked(2, true));
        TestUtils.assertIdentical(new long[] { 2 }, mListView.getCheckItemIds());

        mActivityRule.runOnUiThread(() -> mListView.setItemChecked(4, true));
        TestUtils.assertIdentical(new long[] { 2, 4 }, mListView.getCheckItemIds());

        mActivityRule.runOnUiThread(() -> mListView.setItemChecked(2, false));
        TestUtils.assertIdentical(new long[] { 4 }, mListView.getCheckItemIds());

        mActivityRule.runOnUiThread(() -> mListView.setItemChecked(4, false));
        assertTrue(mListView.getCheckItemIds().length == 0);
    }

    @Test
    public void testAccessOverscrollHeader() throws Throwable {
        final Drawable overscrollHeaderDrawable = spy(new ColorDrawable(Color.YELLOW));
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> {
                    mListView.setAdapter(mAdapter_longCountries);
                    mListView.setOverscrollHeader(overscrollHeaderDrawable);
                });

        assertEquals(overscrollHeaderDrawable, mListView.getOverscrollHeader());
        verify(overscrollHeaderDrawable, never()).draw(any(Canvas.class));

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setScrollY(-mListView.getHeight() / 2));

        verify(overscrollHeaderDrawable, atLeastOnce()).draw(any(Canvas.class));
    }

    @Test
    public void testAccessOverscrollFooter() throws Throwable {
        final Drawable overscrollFooterDrawable = spy(new ColorDrawable(Color.MAGENTA));
        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView, () -> {
            // Configure ListView to automatically scroll to the selected item
            mListView.setStackFromBottom(true);
            mListView.setTranscriptMode(AbsListView.TRANSCRIPT_MODE_ALWAYS_SCROLL);

            mListView.setAdapter(mAdapter_longCountries);
            mListView.setOverscrollFooter(overscrollFooterDrawable);

            // Set selection to the last item
            mListView.setSelection(mAdapter_longCountries.getCount() - 1);
        });

        assertEquals(overscrollFooterDrawable, mListView.getOverscrollFooter());
        verify(overscrollFooterDrawable, never()).draw(any(Canvas.class));

        WidgetTestUtils.runOnMainAndDrawSync(mActivityRule, mListView,
                () -> mListView.setScrollY(mListView.getHeight() / 2));

        verify(overscrollFooterDrawable, atLeastOnce()).draw(any(Canvas.class));
    }

    private static class ColorAdapter extends BaseAdapter {
        private int[] mColors;
        private Context mContext;
        private int mPositionOffset;

        ColorAdapter(Context context, int[] colors) {
            mContext = context;
            mColors = colors;
        }

        @Override
        public int getCount() {
            return mColors.length;
        }

        @Override
        public Object getItem(int position) {
            return mColors[position];
        }

        @Override
        public long getItemId(int position) {
            return position - mPositionOffset;
        }

        @Override
        public boolean hasStableIds() {
            return true;
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            int color = mColors[position];
            if (convertView != null) {
                convertView.setBackgroundColor(color);
                return convertView;
            }
            View view = new View(mContext);
            view.setBackgroundColor(color);
            view.setLayoutParams(new ViewGroup.LayoutParams(90, 50));
            return view;
        }

        public void addColor(int color) {
            int[] colors = new int[mColors.length + 1];
            System.arraycopy(mColors, 0, colors, 0, mColors.length);
            colors[mColors.length] = color;
            mColors = colors;
            notifyDataSetChanged();
        }

        public void addColorAtStart(int color) {
            int[] colors = new int[mColors.length + 1];
            System.arraycopy(mColors, 0, colors, 1, mColors.length);
            colors[0] = color;
            mColors = colors;
            mPositionOffset++;
            notifyDataSetChanged();
        }
    }

    private static class ClickColorAdapter extends ColorAdapter {
        ClickColorAdapter(Context context, int[] colors) {
            super(context, colors);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            View view = super.getView(position, convertView, parent);
            view.setOnClickListener((v) -> { });
            return view;
        }
    }

    private static class CaptureOnReleaseEdgeEffect extends EdgeEffect {
        public boolean onReleaseCalled;

        CaptureOnReleaseEdgeEffect(Context context) {
            super(context);
        }

        @Override
        public void onRelease() {
            onReleaseCalled = true;
            super.onRelease();
        }
    }
}
