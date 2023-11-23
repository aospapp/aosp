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

package com.android.car.dialer.ui.dialpad;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.dialer.testing.TestViewActions.selfClickWithoutConstraints;
import static com.android.car.dialer.testing.TestViewActions.selfLongClickWithoutConstraints;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mock;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.mockitoSession;
import static com.android.dx.mockito.inline.extended.ExtendedMockito.when;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;

import android.content.Context;
import android.provider.CallLog;
import android.view.KeyEvent;

import androidx.lifecycle.MutableLiveData;
import androidx.test.annotation.UiThreadTest;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.matcher.ViewMatchers.Visibility;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.apps.common.util.LiveDataFunctions;
import com.android.car.dialer.R;
import com.android.car.dialer.testing.TestActivity;
import com.android.car.dialer.ui.common.ContactResultsLiveData;
import com.android.car.telephony.common.Contact;
import com.android.car.telephony.common.InMemoryPhoneBook;
import com.android.car.telephony.common.PhoneNumber;
import com.android.car.telephony.common.TelecomUtils;

import org.junit.Before;
import org.junit.Ignore;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import org.mockito.MockitoSession;
import org.mockito.quality.Strictness;

import java.util.List;

@RunWith(AndroidJUnit4.class)
public class DialpadFragmentTest {
    private static final String DIAL_NUMBER = "6505551234";
    private static final String DIAL_NUMBER_LONG = "650555123465055512346505551234";
    private static final String SINGLE_DIGIT = "0";
    private static final String SPEC_CHAR = "123,456";
    private static final String DISPLAY_NAME = "Display Name";

    private Context mContext;
    private DialpadFragment mDialpadFragment;
    private MutableLiveData<List<ContactResultsLiveData.ContactResultListItem>>
            mTypeDownResultsLiveData;
    @Mock
    private TypeDownResultsViewModel mTypeDownResultsViewModel;
    @Mock
    private Contact mMockContact;
    @Mock
    private InMemoryPhoneBook mInMemoryPhoneBook;

    @Before
    @UiThreadTest
    public void setup() {
        MockitoAnnotations.initMocks(this);

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();

        mTypeDownResultsLiveData = new MutableLiveData<>();
        when(mTypeDownResultsViewModel.getContactSearchResults()).thenReturn(
                mTypeDownResultsLiveData);
        when(mTypeDownResultsViewModel.getSortOrderLiveData()).thenReturn(
                LiveDataFunctions.dataOf(TelecomUtils.SORT_BY_FIRST_NAME));
    }

    @Test
    public void testOnCreateView_modeDialWithNormalDialNumber() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber(DIAL_NUMBER);

        verifyButtonVisibility(Visibility.VISIBLE, Visibility.VISIBLE);
        verifyTitleText(DIAL_NUMBER);
    }

    @Test
    public void testOnCreateView_modeDialWithLongDialNumber() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber(DIAL_NUMBER_LONG);

        verifyButtonVisibility(Visibility.VISIBLE, Visibility.VISIBLE);
        verifyTitleText(DIAL_NUMBER_LONG.substring(
                DIAL_NUMBER_LONG.length() - DialpadFragment.MAX_DIAL_NUMBER));
    }

    @Test
    public void testOnCreateView_modeDialWithNullDialNumber() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber(null);

        verifyButtonVisibility(Visibility.VISIBLE, Visibility.GONE);
        verifyTitleText(mDialpadFragment.getContext().getString(R.string.dial_a_number));
    }

    @Test
    public void testOnCreateView_modeDialWithEmptyDialNumber() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber("");

        verifyButtonVisibility(Visibility.VISIBLE, Visibility.GONE);
        verifyTitleText(mDialpadFragment.getContext().getString(R.string.dial_a_number));
    }

    @Test
    public void testOnCreateView_modeDialWithSpecialChar() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber(SPEC_CHAR);

        verifyButtonVisibility(Visibility.VISIBLE, Visibility.VISIBLE);
        verifyTitleText(SPEC_CHAR);
    }

    @Test
    public void testDeleteButton_normalString() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber(DIAL_NUMBER);

        onView(withId(R.id.delete_button)).perform(selfClickWithoutConstraints());
        verifyTitleText(DIAL_NUMBER.substring(0, DIAL_NUMBER.length() - 1));
    }

    @Test
    public void testDeleteButton_oneDigit() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber(SINGLE_DIGIT);

        onView(withId(R.id.delete_button)).perform(selfClickWithoutConstraints());
        verifyTitleText(mDialpadFragment.getContext().getString(R.string.dial_a_number));
    }

    @Test
    public void testDeleteButton_emptyString() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber("");

        onView(withId(R.id.delete_button)).perform(selfClickWithoutConstraints());
        verifyTitleText(mDialpadFragment.getContext().getString(R.string.dial_a_number));
    }

    @Test
    public void testLongPressDeleteButton() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber(DIAL_NUMBER);

        onView(withId(R.id.delete_button)).perform(selfLongClickWithoutConstraints());
        verifyTitleText(mDialpadFragment.getContext().getString(R.string.dial_a_number));
    }

    @Test
    public void testCallButton_emptyString() {
        MockitoSession session = mockitoSession().strictness(Strictness.LENIENT)
                .spyStatic(CallLog.Calls.class).startMocking();

        try {
            doReturn(DIAL_NUMBER).when(() -> CallLog.Calls.getLastOutgoingCall(any()));

            mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
            startPlaceCallActivity();
            mDialpadFragment.setDialedNumber("");

            onView(withId(R.id.call_button)).perform(selfClickWithoutConstraints());
            verifyTitleText(DIAL_NUMBER);
        } finally {
            session.finishMocking();
        }
    }

    @Test
    public void testOnKeyLongPressed_KeyCode0() {
        mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
        startPlaceCallActivity();
        mDialpadFragment.setDialedNumber(DIAL_NUMBER);

        mDialpadFragment.onKeypadKeyLongPressed(KeyEvent.KEYCODE_0);
        verifyTitleText(DIAL_NUMBER.substring(0, DIAL_NUMBER.length() - 1) + "+");
    }

    @Ignore("//TODO fix")
    @Test
    public void testDisplayName() {
        MockitoSession session = mockitoSession().strictness(Strictness.LENIENT)
                .spyStatic(InMemoryPhoneBook.class).startMocking();

        try {
            when(mInMemoryPhoneBook.lookupContactEntry(DIAL_NUMBER)).thenReturn(mMockContact);
            when(mInMemoryPhoneBook.getContactsLiveDataByAccount(anyString()))
                    .thenReturn(new MutableLiveData<>());
            when(InMemoryPhoneBook.get()).thenReturn(mInMemoryPhoneBook);
            when(mMockContact.getDisplayName()).thenReturn(DISPLAY_NAME);
            when(mMockContact.getPhoneNumber(any(), any())).thenReturn(mock(PhoneNumber.class));

            mDialpadFragment = DialpadFragment.newPlaceCallDialpad();
            startPlaceCallActivity();
            mDialpadFragment.setDialedNumber(DIAL_NUMBER);

            if (!mContext.getResources().getBoolean(R.bool.config_show_type_down_list_on_dialpad)
                    && mContext.getResources()
                    .getBoolean(R.bool.config_show_type_down_list_on_dialpad)) {

                onView(withId(R.id.display_name))
                        .check(matches(withText(DISPLAY_NAME)));
            } else {
                onView(withId(R.id.display_name))
                        .check(matches(withText("")));
            }
        } finally {
            session.finishMocking();
        }
    }

    private void startPlaceCallActivity() {
        ActivityScenario<TestActivity> activityScenario = ActivityScenario.launch(
                TestActivity.class);
        activityScenario.onActivity(activity -> {
            activity.getSupportFragmentManager().beginTransaction().add(
                    R.id.test_fragment_container, mDialpadFragment).commit();
        });
    }

    private void verifyButtonVisibility(Visibility callBtnVis, Visibility deleteBtnVis) {
        onView(withId(R.id.call_button)).check(matches(withEffectiveVisibility(callBtnVis)));
        onView(withId(R.id.delete_button)).check(matches(withEffectiveVisibility(deleteBtnVis)));
    }

    private void verifyTitleText(String expectedText) {
        expectedText = TelecomUtils.getFormattedNumber(mContext, expectedText);
        TelecomUtils.getFormattedNumber(mContext, null);

        onView(withId(R.id.title)).check(matches(withText(expectedText)));
    }
}
