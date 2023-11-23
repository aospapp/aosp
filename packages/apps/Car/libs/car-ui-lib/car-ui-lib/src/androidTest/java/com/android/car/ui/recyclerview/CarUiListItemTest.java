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
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.ui.core.CarUi.MIN_TARGET_API;
import static com.android.car.ui.matchers.ViewMatchers.isActivated;
import static com.android.car.ui.matchers.ViewMatchers.withDrawable;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.instanceOf;
import static org.hamcrest.Matchers.not;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import android.annotation.TargetApi;
import android.graphics.drawable.Drawable;
import android.widget.CompoundButton;

import androidx.recyclerview.widget.RecyclerView;
import androidx.test.ext.junit.rules.ActivityScenarioRule;

import com.android.car.ui.R;
import com.android.car.ui.core.CarUi;
import com.android.car.ui.pluginsupport.PluginFactorySingleton;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;

import java.util.ArrayList;
import java.util.List;

/**
 * Unit tests for {@link CarUiListItem}.
 */
@RunWith(Parameterized.class)
@TargetApi(MIN_TARGET_API)
public class CarUiListItemTest {

    @Rule
    public ActivityScenarioRule<CarUiRecyclerViewTestActivity> mActivityRule =
            new ActivityScenarioRule<>(CarUiRecyclerViewTestActivity.class);

    @Parameterized.Parameters
    public static Object[] data() {
        // It's important to do not plugin first, so that the plugin will
        // still be enabled when this test finishes
        return new Object[]{false, true};
    }

    private CarUiRecyclerView mCarUiRecyclerView;
    private CarUiRecyclerViewTestActivity mActivity;

    public CarUiListItemTest(boolean pluginEnabled) {
        PluginFactorySingleton.setPluginEnabledForTesting(pluginEnabled);
    }

    @Before
    public void setUp() {
        mActivityRule.getScenario().onActivity(activity -> {
            mActivity = activity;
            mCarUiRecyclerView = mActivity.requireViewById(R.id.list);
        });
    }

    @Test
    public void testItemVisibility_withTitle() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
        String title = "Test title";
        item.setTitle(title);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));
    }

    @Test
    public void testItemVisibility_withBody() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
        String body = "Test body";
        item.setBody(body);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(body)).check(matches(isDisplayed()));
    }

    @Test
    public void testHeaderItemVisibility() {
        List<CarUiListItem> items = new ArrayList<>();

        String title = "Test title";
        String body = "Test body";
        CarUiListItem item = new CarUiHeaderListItem(title, body);
        items.add(item);

        String title2 = "Test title2";
        item = new CarUiHeaderListItem(title2);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));
        onView(withText(body)).check(matches(isDisplayed()));
        onView(withText(title2)).check(matches(isDisplayed()));
    }

    @Test
    public void testItemDisabled() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
        String body = "Item that is disabled";
        item.setBody(body);
        item.setEnabled(false);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(body)).check(matches(not(isEnabled())));
    }

    @Test
    public void testItemActivated() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
        String body = "Item that is activated";
        item.setBody(body);
        item.setActivated(true);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(body)).check(matches(isActivated()));
    }

    @Test
    public void testItemVisibility_withChevron() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.CHEVRON);
        String title = "Test item with chevron";
        item.setTitle(title);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));
        onView(withDrawable(mActivity, R.drawable.car_ui_icon_chevron)).check(
                matches(isDisplayed()));
    }

    @Test
    public void testItemVisibility_withTitle_withBodyAndIcon() {
        List<CarUiListItem> items = new ArrayList<>();

        Drawable close = mActivity.getDrawable(R.drawable.car_ui_icon_close);

        CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.NONE);
        String title = "Test title";
        String body = "Test body";
        item.setTitle(title);
        item.setBody(body);
        item.setIcon(close);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));
        onView(withText(body)).check(matches(isDisplayed()));
        onView(withDrawable(close)).check(matches(isDisplayed()));
    }

    @Test
    public void testItem_withCheckbox() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem.OnCheckedChangeListener mockOnCheckedChangeListener = mock(
                CarUiContentListItem.OnCheckedChangeListener.class);

        CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.CHECK_BOX);
        String title = "Test item with checkbox";
        item.setTitle(title);
        item.setOnCheckedChangeListener(mockOnCheckedChangeListener);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));

        // List item with checkbox should be initially unchecked.
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(
                matches(isNotChecked()));
        // Clicks anywhere on the item should toggle the checkbox
        onView(withText(title)).perform(click());
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(matches(isChecked()));
        // Check that onCheckChangedListener was invoked.
        verify(mockOnCheckedChangeListener, times(1)).onCheckedChanged(item, true);

        // Uncheck checkbox with click on the checkbox
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).perform(click());
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(
                matches(isNotChecked()));
        // Check that onCheckChangedListener was invoked.
        verify(mockOnCheckedChangeListener, times(1)).onCheckedChanged(item, false);
    }

    @Test
    public void testItem_withCheckboxListItem() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem.OnCheckedChangeListener mockOnCheckedChangeListener = mock(
                CarUiContentListItem.OnCheckedChangeListener.class);

        CarUiContentListItem item = new CarUiCheckBoxListItem();
        String title = "Test item with checkbox";
        item.setTitle(title);
        item.setOnCheckedChangeListener(mockOnCheckedChangeListener);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));

        // List item with checkbox should be initially unchecked.
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(
                matches(isNotChecked()));
        // Clicks anywhere on the item should toggle the checkbox
        onView(withText(title)).perform(click());
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(matches(isChecked()));
        // Check that onCheckChangedListener was invoked.
        verify(mockOnCheckedChangeListener, times(1)).onCheckedChanged(item, true);

        // Uncheck checkbox with click on the checkbox
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).perform(click());
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(
                matches(isNotChecked()));
        // Check that onCheckChangedListener was invoked.
        verify(mockOnCheckedChangeListener, times(1)).onCheckedChanged(item, false);
    }

    @Test
    public void testItem_withSwitch() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem item = new CarUiContentListItem(CarUiContentListItem.Action.SWITCH);
        String body = "Test item with switch";
        item.setBody(body);
        item.setChecked(true);
        item.setActionDividerVisible(true);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(body)).check(matches(isDisplayed()));

        // List item with checkbox should be initially checked.
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(matches(isChecked()));
        // Clicks anywhere on the item should toggle the switch
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).perform(click());
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(
                matches(isNotChecked()));
        // Uncheck checkbox with click on the body text
        onView(withText(body)).perform(click());
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(matches(isChecked()));
    }

    @Test
    public void testItem_withRadioButton() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem item = new CarUiContentListItem(
                CarUiContentListItem.Action.RADIO_BUTTON);
        String title = "Test item with radio button";
        item.setTitle(title);
        item.setChecked(false);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));

        // List item with checkbox should be initially not checked.
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(
                matches(isNotChecked()));
        // Clicks anywhere on the item should toggle the radio button.
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).perform(click());
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(matches(isChecked()));

        // Repeated clicks on a selected radio button should not toggle the element once checked.
        onView(withText(title)).perform(click());
        onView(allOf(instanceOf(CompoundButton.class), isDisplayed())).check(matches(isChecked()));
    }

    @Test
    public void testItem_withCompactLayout() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem item = new CarUiContentListItem(
                CarUiContentListItem.Action.NONE);
        String titleText = "Item with compact layout";
        item.setTitle(titleText);
        String bodyText = "Test body text";
        item.setBody(bodyText);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(new CarUiListItemAdapter(items, true)));

        onView(withText(titleText)).check(matches(isDisplayed()));
        onView(withText(bodyText)).check(matches(isDisplayed()));
    }

    @Test
    public void testItem_withListener() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem.OnClickListener mockOnCheckedChangeListener = mock(
                CarUiContentListItem.OnClickListener.class);

        Drawable close = mActivity.getDrawable(R.drawable.car_ui_icon_close);

        CarUiContentListItem item = new CarUiContentListItem(
                CarUiContentListItem.Action.NONE);
        item.setIcon(close);
        item.setPrimaryIconType(CarUiContentListItem.IconType.AVATAR);
        String title = "Test item with listener";
        String body = "Body text";
        item.setTitle(title);
        item.setBody(body);
        item.setOnItemClickedListener(mockOnCheckedChangeListener);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));

        // Clicks anywhere on the item should toggle the listener
        onView(withText(title)).perform(click());
        verify(mockOnCheckedChangeListener, times(1)).onClick(item);

        onView(withText(body)).perform(click());
        verify(mockOnCheckedChangeListener, times(2)).onClick(item);

        onView(withDrawable(close)).check(matches(isDisplayed()));
        onView(withDrawable(close)).perform(click());
        verify(mockOnCheckedChangeListener, times(3)).onClick(item);
    }

    @Test
    public void testItem_withListenerAndSupplementalIconListener() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem.OnClickListener clickListener = mock(
                CarUiContentListItem.OnClickListener.class);
        CarUiContentListItem.OnClickListener supplementalIconClickListener = mock(
                CarUiContentListItem.OnClickListener.class);

        CarUiContentListItem item = new CarUiContentListItem(
                CarUiContentListItem.Action.ICON);
        String title = "Test item with two listeners";
        item.setTitle(title);
        item.setOnItemClickedListener(clickListener);
        Drawable close = mActivity.getDrawable(R.drawable.car_ui_icon_close);


        item.setSupplementalIcon(
                close,
                supplementalIconClickListener);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));

        // Clicks anywhere on the item (except supplemental icon) should trigger the item click
        // listener.
        onView(withText(title)).perform(click());
        verify(clickListener, times(1)).onClick(item);
        verify(supplementalIconClickListener, times(0)).onClick(item);

        onView(withDrawable(close)).check(matches(isDisplayed()));
        onView(withDrawable(close)).perform(click());
        // Check that icon is argument for single call to click listener.
        verify(supplementalIconClickListener, times(1)).onClick(item);

        // Verify that the standard click listener wasn't also fired.
        verify(clickListener, times(1)).onClick(item);
    }

    @Test
    public void testItem_withSupplementalIcon() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem.OnClickListener mockedItemOnClickListener = mock(
                CarUiContentListItem.OnClickListener.class);

        CarUiContentListItem item = new CarUiContentListItem(
                CarUiContentListItem.Action.ICON);
        Drawable close = mActivity.getDrawable(R.drawable.car_ui_icon_close);

        item.setSupplementalIcon(close);
        item.setOnItemClickedListener(mockedItemOnClickListener);
        String title = "Test item with listener";
        item.setTitle(title);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));

        // Clicks anywhere on the icon should invoke listener.
        onView(withDrawable(close)).perform(click());
        verify(mockedItemOnClickListener, times(1)).onClick(item);
    }

    @Test(expected = IllegalStateException.class)
    public void testNonIconItem_withSupplementalIcon() {
        List<CarUiListItem> items = new ArrayList<>();
        CarUiContentListItem item = new CarUiContentListItem(
                CarUiContentListItem.Action.SWITCH);
        item.setSupplementalIcon(mActivity.getDrawable(R.drawable.car_ui_icon_close));
        item.setTitle("Test item with listener");
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));
    }

    @Test
    public void testItem_withSupplementalIconAndIconOnClickListener() {
        List<CarUiListItem> items = new ArrayList<>();

        CarUiContentListItem.OnClickListener mockedItemOnClickListener = mock(
                CarUiContentListItem.OnClickListener.class);
        CarUiContentListItem.OnClickListener mockedIconListener = mock(
                CarUiContentListItem.OnClickListener.class);

        Drawable close = mActivity.getDrawable(R.drawable.car_ui_icon_close);

        CarUiContentListItem item = new CarUiContentListItem(
                CarUiContentListItem.Action.ICON);
        item.setSupplementalIcon(
                close,
                mockedIconListener);
        item.setOnItemClickedListener(mockedItemOnClickListener);
        String title = "Test item with listeners";
        item.setTitle(title);
        items.add(item);

        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(CarUi.createListItemAdapter(mActivity, items)));

        onView(withText(title)).check(matches(isDisplayed()));

        // Clicks anywhere on the item (outside of the icon) should only invoke the item click
        // listener.
        onView(withText(title)).perform(click());
        verify(mockedItemOnClickListener, times(1)).onClick(item);

        // Clicks anywhere on the icon should invoke both listeners.
        onView(withDrawable(close)).check(matches(isDisplayed()));
        onView(withDrawable(close)).perform(click());
        verify(mockedItemOnClickListener, times(1)).onClick(item);
        verify(mockedIconListener, times(1)).onClick(item);
    }

    @Test
    public void testRadioButtonListItemAdapter() {
        List<CarUiRadioButtonListItem> items = new ArrayList<>();

        CarUiRadioButtonListItem itemOne = new CarUiRadioButtonListItem();
        String itemOneTitle = "Item 1";
        itemOne.setTitle(itemOneTitle);
        items.add(itemOne);

        CarUiRadioButtonListItem itemTwo = new CarUiRadioButtonListItem();
        String itemTwoTitle = "Item 2";
        itemTwo.setTitle(itemTwoTitle);
        items.add(itemTwo);

        CarUiRadioButtonListItem itemThree = new CarUiRadioButtonListItem();
        String itemThreeTitle = "Item 3";
        itemThree.setTitle(itemThreeTitle);
        items.add(itemThree);

        CarUiRadioButtonListItemAdapter adapter = new CarUiRadioButtonListItemAdapter(items);
        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(adapter));

        onView(withText(itemOneTitle)).check(matches(isDisplayed()));
        onView(withText(itemTwoTitle)).check(matches(isDisplayed()));
        onView(withText(itemThreeTitle)).check(matches(isDisplayed()));

        // All items are initially unchecked.
        assertFalse(itemOne.isChecked());
        assertFalse(itemTwo.isChecked());
        assertFalse(itemThree.isChecked());
        assertEquals(adapter.getSelectedItemPosition(), -1);

        // Select first item.
        onView(withText(itemOneTitle)).perform(click());
        assertTrue(itemOne.isChecked());
        assertFalse(itemTwo.isChecked());
        assertFalse(itemThree.isChecked());
        assertEquals(adapter.getSelectedItemPosition(), 0);

        // Select second item.
        onView(withText(itemTwoTitle)).perform(click());
        assertFalse(itemOne.isChecked());
        assertTrue(itemTwo.isChecked());
        assertFalse(itemThree.isChecked());
        assertEquals(adapter.getSelectedItemPosition(), 1);

        // Select third item.
        onView(withText(itemThreeTitle)).perform(click());
        assertFalse(itemOne.isChecked());
        assertFalse(itemTwo.isChecked());
        assertTrue(itemThree.isChecked());
        assertEquals(adapter.getSelectedItemPosition(), 2);
    }

    @Test
    public void testRadioButtonListItemAdapter_itemInitiallyChecked() {
        List<CarUiRadioButtonListItem> items = new ArrayList<>();

        CarUiRadioButtonListItem itemOne = new CarUiRadioButtonListItem();
        String itemOneTitle = "Item 1";
        itemOne.setChecked(true);
        itemOne.setTitle(itemOneTitle);
        items.add(itemOne);

        CarUiRadioButtonListItem itemTwo = new CarUiRadioButtonListItem();
        String itemTwoTitle = "Item 2";
        itemTwo.setTitle(itemTwoTitle);
        items.add(itemTwo);

        CarUiRadioButtonListItemAdapter adapter = new CarUiRadioButtonListItemAdapter(items);
        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(adapter));

        onView(withText(itemOneTitle)).check(matches(isDisplayed()));
        onView(withText(itemTwoTitle)).check(matches(isDisplayed()));

        // Item 1 is initially checked.
        assertTrue(itemOne.isChecked());
        assertFalse(itemTwo.isChecked());
        assertEquals(adapter.getSelectedItemPosition(), 0);

        // Select second item.
        onView(withText(itemTwoTitle)).perform(click());
        assertFalse(itemOne.isChecked());
        assertTrue(itemTwo.isChecked());
        assertEquals(adapter.getSelectedItemPosition(), 1);
    }

    @Test(expected = IllegalStateException.class)
    public void testInvalidRadioButtonListItemAdapter() {
        List<CarUiRadioButtonListItem> items = new ArrayList<>();

        CarUiRadioButtonListItem itemOne = new CarUiRadioButtonListItem();
        String itemOneTitle = "Item 1";
        itemOne.setTitle(itemOneTitle);
        itemOne.setChecked(true);
        items.add(itemOne);

        CarUiRadioButtonListItem itemTwo = new CarUiRadioButtonListItem();
        String itemTwoTitle = "Item 2";
        itemTwo.setTitle(itemTwoTitle);
        itemTwo.setChecked(true);
        items.add(itemTwo);

        CarUiRadioButtonListItem itemThree = new CarUiRadioButtonListItem();
        String itemThreeTitle = "Item 3";
        itemThree.setTitle(itemThreeTitle);
        items.add(itemThree);

        CarUiRadioButtonListItemAdapter adapter = new CarUiRadioButtonListItemAdapter(items);
        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(adapter));
    }

    @Test()
    public void testListItemAdapter_getCount() {
        List<CarUiRadioButtonListItem> items = new ArrayList<>();

        CarUiRadioButtonListItem itemOne = new CarUiRadioButtonListItem();
        String itemOneTitle = "Item 1";
        itemOne.setTitle(itemOneTitle);
        items.add(itemOne);

        CarUiRadioButtonListItem itemTwo = new CarUiRadioButtonListItem();
        String itemTwoTitle = "Item 2";
        itemTwo.setTitle(itemTwoTitle);
        items.add(itemTwo);

        CarUiRadioButtonListItem itemThree = new CarUiRadioButtonListItem();
        String itemThreeTitle = "Item 3";
        itemThree.setTitle(itemThreeTitle);
        items.add(itemThree);

        CarUiRadioButtonListItemAdapter adapter = new CarUiRadioButtonListItemAdapter(items);
        mCarUiRecyclerView.post(
                () -> mCarUiRecyclerView.setAdapter(adapter));

        onView(withText(itemOneTitle)).check(matches(isDisplayed()));
        onView(withText(itemTwoTitle)).check(matches(isDisplayed()));
        onView(withText(itemThreeTitle)).check(matches(isDisplayed()));
        assertEquals(adapter.getItemCount(), 3);

        adapter.setMaxItems(2);
        assertEquals(adapter.getItemCount(), 2);
    }

    @SuppressWarnings("AssertThrowsMultipleStatements")
    @Test
    public void testUnknownCarUiListItemType_throwsException() {
        List<CarUiListItem> items = new ArrayList<>();
        CarUiListItem item = new UnknownCarUiListItem();
        items.add(item);
        assertThrows("Unknown view type.", IllegalStateException.class,
                () -> {
                    RecyclerView.Adapter adapter = CarUi.createListItemAdapter(mActivity, items);
                    adapter.getItemViewType(0);
                });
    }

    private static class UnknownCarUiListItem extends CarUiListItem {
    }
}
