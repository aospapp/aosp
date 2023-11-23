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

package com.android.car.dialer.integration;

import static android.app.Activity.RESULT_OK;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.hasSibling;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.dialer.testing.TestViewMatchers.atPosition;

import static org.hamcrest.Matchers.allOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.os.SystemClock;
import android.telecom.TelecomManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.espresso.intent.Intents;
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
public class ContactListTest {
    private static final String DISPLAY_NAME = "Contact";
    private static final String PHONE_NUMBER = "511";
    private static final String PHONE_NUMBER_LABEL = "Work";
    private static final Uri CALL_URI = Uri.fromParts("tel", PHONE_NUMBER, null);

    @Inject FakeHfpManager mFakeHfpManager;
    @Inject TelecomManager mTelecomManager;
    @Inject FakeTelecomManager mFakeTelecomManager;
    private SimulatedBluetoothDevice mSimulatedBluetoothDevice;

    @Rule
    public final HiltAndroidRule mHiltAndroidRule = new HiltAndroidRule(this);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(ContactListTest.this);

        Intents.init();
        // CardView error, suppressing InCallActivity by responding with RESULT_OK.
        intending(hasComponent(InCallActivity.class.getName())).respondWith(
                new Instrumentation.ActivityResult(RESULT_OK, null));

        mHiltAndroidRule.inject();

        mSimulatedBluetoothDevice = mFakeHfpManager.connectHfpDevice(/* withMockData= */false);
    }

    @Test
    public void verifyLoadContacts() {
        // Insert contacts into database.
        for (int i = 0; i < 3; i++) {
            ContactRawData contact = new ContactRawData();
            contact.setDisplayName(DISPLAY_NAME + " " + i);
            contact.setNumber(PHONE_NUMBER + i);
            contact.setNumberLabel(PHONE_NUMBER_LABEL + " " + i);
            mSimulatedBluetoothDevice.insertContactInBackground(contact);
        }

        // Launch activity and switch to the contact list page.
        ActivityScenario.launch(
                new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        TelecomActivity.class));
        onView(withText(R.string.contacts_title)).check(matches(isDisplayed())).perform(click());

        // Verify contacts are rendered correctly.
        for (int i = 0; i < 3; i++) {
            onView(withId(R.id.list_view))
                    .perform(RecyclerViewActions.scrollToPosition(i))
                    .check(matches(atPosition(i, hasDescendant(
                            allOf(withId(R.id.title), withText(DISPLAY_NAME + " " + i))))))
                    .check(matches(atPosition(i, hasDescendant(
                            allOf(withId(R.id.text), withText(PHONE_NUMBER_LABEL + " " + i))))));

            onView(allOf(withId(R.id.call_action_id),
                    hasSibling(withText(DISPLAY_NAME + " " + i)))).perform(click());
            verify(mTelecomManager).placeCall(eq(Uri.fromParts("tel", PHONE_NUMBER + i, null)),
                    isNull());
        }

    }

    @Test
    public void verifyUpdateContacts() {
        // Launch activity and switch to the contact list page.
        ActivityScenario.launch(
                new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        TelecomActivity.class));
        onView(withText(R.string.contacts_title)).check(matches(isDisplayed())).perform(click());

        // No contacts.
        onView(withText(R.string.contact_list_empty)).check(matches(isDisplayed()));

        // Insert one contact into database.
        ContactRawData contact = new ContactRawData();
        contact.setDisplayName(DISPLAY_NAME);
        contact.setNumber(PHONE_NUMBER);
        contact.setNumberLabel(PHONE_NUMBER_LABEL);
        mSimulatedBluetoothDevice.insertContactInBackground(contact);

        // Wait until the insertion is done and verify the contact is rendered correctly.
        SystemClock.sleep(2000);
        onView(withId(R.id.list_view))
                .perform(RecyclerViewActions.scrollToPosition(0))
                .check(matches(atPosition(0, hasDescendant(
                        allOf(withId(R.id.title), withText(DISPLAY_NAME))))))
                .check(matches(atPosition(0, hasDescendant(
                        allOf(withId(R.id.text), withText(PHONE_NUMBER_LABEL))))));

        onView(withId(R.id.call_action_id)).perform(click());
        verify(mTelecomManager).placeCall(eq(CALL_URI), isNull());
    }

    @After
    public void tearDown() {
        Intents.release();
        mSimulatedBluetoothDevice.disconnect();
        mFakeTelecomManager.clearCalls();
    }
}
