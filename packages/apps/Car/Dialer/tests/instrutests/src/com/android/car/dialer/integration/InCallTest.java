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

package com.android.car.dialer.integration;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isCompletelyDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.dialer.testing.TestViewActions.selfClick;
import static com.android.car.dialer.testing.TestViewActions.waitAction;
import static com.android.car.dialer.testing.TestViewMatchers.atPosition;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.startsWith;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.os.SystemClock;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.dialer.R;
import com.android.car.dialer.framework.FakeHfpManager;
import com.android.car.dialer.framework.FakeTelecomManager;
import com.android.car.dialer.framework.SimulatedBluetoothDevice;
import com.android.car.dialer.framework.testdata.ContactRawData;
import com.android.car.dialer.ui.TelecomActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import javax.inject.Inject;

import dagger.hilt.android.testing.HiltAndroidRule;
import dagger.hilt.android.testing.HiltAndroidTest;

@RunWith(AndroidJUnit4.class)
@HiltAndroidTest
public class InCallTest {
    private static final String DISPLAY_NAME = "Contact";
    private static final String PHONE_NUMBER = "511";
    private static final String PHONE_NUMBER_2 = "512";
    private static final String PHONE_NUMBER_LABEL = "Test";
    private static final long WAIT_ACTION_INTERVAL = 100;
    private static final int WAIT_MAX_RETRY = 50;
    @Rule
    public final HiltAndroidRule mHiltAndroidRule = new HiltAndroidRule(this);
    @Inject FakeHfpManager mFakeHfpManager;
    @Inject FakeTelecomManager mFakeTelecomManager;
    private Context mContext;
    private SimulatedBluetoothDevice mBluetoothDevice;

    @Before
    public void setup() {
        InstrumentationRegistry.getInstrumentation().getUiAutomation().grantRuntimePermission(
                "com.android.car.dialer", Manifest.permission.BLUETOOTH_CONNECT);
        MockitoAnnotations.initMocks(InCallTest.this);
        mHiltAndroidRule.inject();

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ActivityScenario.launch(new Intent(mContext, TelecomActivity.class));
        mBluetoothDevice = mFakeHfpManager.connectHfpDevice(/* withMockData = */false);
    }

    @Test
    public void placeCall() {
        ContactRawData contactRawData = new ContactRawData();
        contactRawData.setNumber(PHONE_NUMBER);
        contactRawData.setNumberLabel(PHONE_NUMBER_LABEL);
        contactRawData.setDisplayName(DISPLAY_NAME);
        mBluetoothDevice.insertContactInBackground(contactRawData);

        onView(withText(R.string.contacts_title)).perform(click());
        onView(withId(R.id.list_view))
                .perform(ViewActions.repeatedlyUntil(
                        waitAction(WAIT_ACTION_INTERVAL),
                        hasDescendant(withText(DISPLAY_NAME)), WAIT_MAX_RETRY));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mFakeTelecomManager.placeCall(PHONE_NUMBER));

        onView(withText(DISPLAY_NAME)).check(matches(isDisplayed()));
        onView(withText(PHONE_NUMBER_LABEL + " " + PHONE_NUMBER)).check(matches(isDisplayed()));
        onView(allOf(withId(R.id.mute_button), isDisplayed())).check(
                matches(isCompletelyDisplayed()));
        onView(allOf(withId(R.id.toggle_dialpad_button), isDisplayed())).check(
                matches(isEnabled()));
        onView(allOf(withId(R.id.voice_channel_view), isDisplayed())).check(matches(isEnabled()));
        onView(allOf(withId(R.id.pause_button), isDisplayed())).check(matches(isEnabled())).perform(
                selfClick());
        // Verify the call is on hold.
        onView(allOf(withText(R.string.call_state_hold), isDisplayed())).check(
                matches(isEnabled()));

        onView(allOf(withId(R.id.end_call_button), isDisplayed())).check(
                matches(isEnabled())).perform(selfClick());

        // Verify InCallActivity is finished.
        onView(withText(R.string.contacts_title)).check(matches(isDisplayed()));
    }

    @Test
    public void swapCall() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    mFakeTelecomManager.placeCall(PHONE_NUMBER);
                    SystemClock.sleep(1000);
                    mFakeTelecomManager.placeCall(PHONE_NUMBER_2);
                });
        onView(allOf(withId(R.id.merge_button), isDisplayed())).check(matches(isEnabled()));
        onView(allOf(withId(R.id.user_profile_title), withText(PHONE_NUMBER_2))).check(
                matches(isDisplayed()));
        onView(allOf(withId(R.id.title), withText(PHONE_NUMBER), isDisplayed())).check(
                matches(isEnabled()));
        onView(allOf(withId(R.id.swap_call), isDisplayed())).check(matches(isEnabled()));
        onView(allOf(withId(R.id.onhold_label), withText(R.string.call_state_hold),
                isDisplayed())).check(matches(isEnabled()));

        onView(allOf(withId(R.id.swap_calls_view), isDisplayed())).perform(click());
        onView(allOf(withId(R.id.title), withText(PHONE_NUMBER_2), isDisplayed())).check(
                matches(isEnabled()));
        onView(allOf(withId(R.id.user_profile_title), withText(PHONE_NUMBER))).check(
                matches(isDisplayed()));

        onView(allOf(withId(R.id.end_call_button), isDisplayed())).perform(selfClick()).perform(
                selfClick());
    }

    @Test
    public void mergeCall() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    mFakeTelecomManager.placeCall(PHONE_NUMBER);
                    SystemClock.sleep(1000);
                    mFakeTelecomManager.placeCall(PHONE_NUMBER_2);
                });

        onView(allOf(withId(R.id.merge_button), isDisplayed())).perform(selfClick());
        SystemClock.sleep(1000);

        onView(withText(startsWith("Conference (2)"))).check(matches(isDisplayed()));
        onView(withId(R.id.conference_call_profiles_recycler_view))
                .perform(RecyclerViewActions.scrollToPosition(0))
                .check(matches(atPosition(0, hasDescendant(
                        allOf(withId(R.id.user_profile_title), withText(PHONE_NUMBER))))))
                .perform(RecyclerViewActions.scrollToPosition(1))
                .check(matches(atPosition(1, hasDescendant(
                        allOf(withId(R.id.user_profile_title), withText(PHONE_NUMBER_2))))));

        onView(allOf(withId(R.id.end_call_button), isDisplayed())).perform(selfClick());
    }

    @After
    public void tearDown() {
        mBluetoothDevice.disconnect();
    }
}
