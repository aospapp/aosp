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

import static android.app.Activity.RESULT_OK;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.dialer.testing.TestViewActions.bringToFrontAction;
import static com.android.car.dialer.testing.TestViewActions.selfClickWithoutConstraints;
import static com.android.car.dialer.testing.TestViewActions.waitAction;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.telecom.TelecomManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.dialer.R;
import com.android.car.dialer.framework.FakeHfpManager;
import com.android.car.dialer.framework.FakeTelecomManager;
import com.android.car.dialer.framework.SimulatedBluetoothDevice;
import com.android.car.dialer.framework.testdata.ContactRawData;
import com.android.car.dialer.ui.TelecomActivity;
import com.android.car.dialer.ui.activecall.InCallActivity;

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
public class DialpadTest {
    private static final String DISPLAY_NAME = "Contact";
    private static final String PHONE_NUMBER = "511";
    private static final String PHONE_NUMBER_LABEL = "Test";
    private static final Uri CALL_URI = Uri.fromParts("tel", PHONE_NUMBER, null);
    private static final long WAIT_ACTION_INTERVAL = 100;
    private static final int WAIT_MAX_RETRY = 50;

    @Rule
    public final HiltAndroidRule mHiltAndroidRule = new HiltAndroidRule(this);
    @Inject FakeHfpManager mFakeHfpManager;
    @Inject TelecomManager mTelecomManager;
    @Inject FakeTelecomManager mFakeTelecomManager;
    private Context mContext;
    private SimulatedBluetoothDevice mBluetoothDevice;

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(DialpadTest.this);
        mHiltAndroidRule.inject();

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        ActivityScenario.launch(new Intent(mContext, TelecomActivity.class));
        mBluetoothDevice = mFakeHfpManager.connectHfpDevice(/* withMockData = */false);

        Intents.init();
        intending(hasComponent(new ComponentName(mContext, InCallActivity.class))).respondWith(
                new Instrumentation.ActivityResult(RESULT_OK, null));
    }

    @Test
    public void launchDialpad() {
        onView(withText(R.string.dialpad_title)).check(matches(isDisplayed())).perform(click());
        onView(withText("0")).check(matches(isDisplayed()));
        onView(withText("1")).check(matches(isDisplayed()));
        onView(withText("2")).check(matches(isDisplayed()));
        onView(withText("3")).check(matches(isDisplayed()));
        onView(withText("4")).check(matches(isDisplayed()));
        onView(withText("5")).check(matches(isDisplayed()));
        onView(withText("6")).check(matches(isDisplayed()));
        onView(withText("7")).check(matches(isDisplayed()));
        onView(withText("8")).check(matches(isDisplayed()));
        onView(withText("9")).check(matches(isDisplayed()));
        onView(withText("*")).check(matches(isDisplayed()));
        onView(withText("#")).check(matches(isDisplayed()));
    }

    @Test
    public void dialTest() {
        onView(withText(R.string.dialpad_title)).check(matches(isDisplayed())).perform(click());
        onView(withId(R.id.five)).perform(bringToFrontAction()).perform(click());
        onView(withId(R.id.one)).perform(bringToFrontAction()).perform(click()).perform(click());
        onView(withId(R.id.delete_button)).check(
                matches(withEffectiveVisibility(ViewMatchers.Visibility.VISIBLE)));
        onView(withId(R.id.title)).check(matches(withText(PHONE_NUMBER)));
        onView(withId(R.id.call_button)).perform(selfClickWithoutConstraints());

        verify(mTelecomManager).placeCall(eq(CALL_URI), isNull());

        // Teardown properly
        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mFakeTelecomManager.clearCalls());
    }

    @Test
    public void typeDownTest() {
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

        onView(withText(R.string.dialpad_title)).check(matches(isDisplayed())).perform(click());
        onView(withId(R.id.five)).perform(bringToFrontAction()).perform(click());

        onView(withText(DISPLAY_NAME)).check(matches(isDisplayed()));
        onView(withId(R.id.contact_result)).perform(click());
        verify(mTelecomManager).placeCall(eq(CALL_URI), isNull());

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> mFakeTelecomManager.clearCalls());
    }

    @After
    public void tearDown() {
        mBluetoothDevice.disconnect();
        // Wait InCallActivity is launched and intended before releasing.
        SystemClock.sleep(1000);
        Intents.release();
    }
}
