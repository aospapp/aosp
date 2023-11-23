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
import static androidx.test.espresso.action.ViewActions.pressBack;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.RootMatchers.isDialog;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isRoot;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.dialer.testing.TestViewActions.selfClick;
import static com.android.car.dialer.testing.TestViewActions.selfClickWithoutConstraints;
import static com.android.car.dialer.testing.TestViewMatchers.atPosition;
import static com.android.car.dialer.testing.TestViewMatchers.isActivated;

import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.not;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.telecom.TelecomManager;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.action.ViewActions;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.dialer.R;
import com.android.car.dialer.framework.FakeHfpManager;
import com.android.car.dialer.framework.FakeTelecomManager;
import com.android.car.dialer.framework.SimulatedBluetoothDevice;
import com.android.car.dialer.framework.testdata.ContactRawData;
import com.android.car.dialer.testing.TestViewActions;
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
public class FavoriteContactTest {
    private static final String PHONE_NUMBER = "511";
    private static final Uri CALL_URI = Uri.fromParts("tel", PHONE_NUMBER, null);
    private static final String PHONE_FAVORITE_CONTACT = "Phone Favorite Contact";
    private static final String LOCAL_FAVORITE_CONTACT = "Local Favorite Contact";
    private static final String PHONE_FAVORITE_LABEL = "Work";
    private static final String LOCAL_FAVORITE_LABEL = "Mobile";
    private static final long WAIT_ACTION_INTERVAL = 100;
    private static final int WAIT_MAX_RETRY = 50;

    @Inject FakeHfpManager mFakeHfpManager;
    @Inject TelecomManager mTelecomManager;
    @Inject FakeTelecomManager mFakeTelecomManager;
    private SimulatedBluetoothDevice mBluetoothDevice;

    @Rule
    public final HiltAndroidRule mHiltAndroidRule = new HiltAndroidRule(this);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(FavoriteContactTest.this);

        Intents.init();
        // Suppressing InCallActivity by responding with RESULT_OK.
        intending(hasComponent(InCallActivity.class.getName())).respondWith(
                new Instrumentation.ActivityResult(RESULT_OK, null));

        mHiltAndroidRule.inject();

        mBluetoothDevice = mFakeHfpManager.connectHfpDevice(/* withMockData= */false);

        // Launch activity and switch to the contact list page.
        ActivityScenario.launch(
                new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        TelecomActivity.class));
        onView(withText(R.string.favorites_title)).check(matches(isDisplayed())).perform(click());
    }

    @Test
    public void verifyPhoneFavorite() {
        onView(withId(R.id.list_view)).check(
                matches(not(hasDescendant(withText(R.string.phone_favorites)))));

        // Insert favorite contact into database.
        ContactRawData contact = new ContactRawData();
        contact.setDisplayName(PHONE_FAVORITE_CONTACT);
        contact.setNumber(PHONE_NUMBER);
        contact.setNumberLabel(PHONE_FAVORITE_LABEL);
        contact.setStarred(true);
        mBluetoothDevice.insertContactInBackground(contact);

        // Verify phone favorite contact is rendered correctly.
        onView(withId(R.id.list_view))
                .perform(ViewActions.repeatedlyUntil(
                        TestViewActions.waitAction(WAIT_ACTION_INTERVAL),
                        hasDescendant(withText(PHONE_FAVORITE_CONTACT)), WAIT_MAX_RETRY))
                .perform(RecyclerViewActions.scrollToPosition(0))
                .check(matches(atPosition(0, withText(R.string.phone_favorites))))
                .perform(RecyclerViewActions.scrollToPosition(1))
                .check(matches(atPosition(1, hasDescendant(
                        allOf(withId(R.id.title), withText(PHONE_FAVORITE_CONTACT))))))
                .check(matches(atPosition(1, hasDescendant(
                        allOf(withId(R.id.text), withText(PHONE_FAVORITE_LABEL))))))
                .perform(RecyclerViewActions.actionOnItemAtPosition(1, click()));

        verify(mTelecomManager).placeCall(eq(CALL_URI), isNull());
    }

    @Test
    public void addLocalFavorite() {
        // Insert contact into database.
        ContactRawData contact = new ContactRawData();
        contact.setDisplayName(LOCAL_FAVORITE_CONTACT);
        contact.setNumber(PHONE_NUMBER);
        contact.setNumberLabel(LOCAL_FAVORITE_LABEL);
        contact.setStarred(false);
        mBluetoothDevice.insertContactInBackground(contact);

        onView(withId(R.id.list_view))
                .perform(RecyclerViewActions.scrollToPosition(0))
                .check(matches(atPosition(0, withText(R.string.local_favorites))))
                .perform(RecyclerViewActions.scrollToPosition(1))
                .check(matches(atPosition(1, withText(R.string.add_favorite_button))))
                .perform(RecyclerViewActions.actionOnItemAtPosition(1, click()));
        onView(withId(R.id.car_ui_toolbar_search_bar)).perform(
                TestViewActions.setTextWithoutConstraints(PHONE_NUMBER));

        onView(withId(R.id.list_view))
                .perform(ViewActions.repeatedlyUntil(
                        TestViewActions.waitAction(WAIT_ACTION_INTERVAL),
                        hasDescendant(withText(LOCAL_FAVORITE_CONTACT)), WAIT_MAX_RETRY))
                .perform(RecyclerViewActions.actionOnItemAtPosition(0, click()));
        onView(withId(R.id.car_ui_list_item_touch_interceptor)).inRoot(isDialog()).perform(
                selfClickWithoutConstraints());
        onView(withText(R.string.confirm_add_favorites_dialog)).inRoot(isDialog()).perform(click());

        // Verify local favorite contact is rendered correctly.
        onView(withId(R.id.list_view))
                .perform(ViewActions.repeatedlyUntil(
                        TestViewActions.waitAction(WAIT_ACTION_INTERVAL),
                        hasDescendant(withText(LOCAL_FAVORITE_CONTACT)), WAIT_MAX_RETRY))
                .perform(RecyclerViewActions.scrollToPosition(1))
                .check(matches(atPosition(1, hasDescendant(
                        allOf(withId(R.id.title), withText(LOCAL_FAVORITE_CONTACT))))))
                .check(matches(atPosition(1, hasDescendant(
                        allOf(withId(R.id.text), withText(LOCAL_FAVORITE_LABEL))))))
                .perform(RecyclerViewActions.actionOnItemAtPosition(1, click()));
        verify(mTelecomManager).placeCall(eq(CALL_URI), isNull());

        // Remove the local favorite
        onView(withText(R.string.contacts_title)).check(matches(isDisplayed())).perform(click());
        onView(withId(R.id.show_contact_detail_id)).perform(click());
        onView(withId(R.id.contact_details_favorite_button)).check(
                matches(isActivated(true))).perform(selfClick()).check(matches(isActivated(false)));

        // Back to favorites tab
        onView(isRoot()).perform(pressBack());
        onView(withText(R.string.favorites_title)).check(matches(isDisplayed())).perform(click());
        // Verify there is no local favorites.
        onView(withId(R.id.list_view))
                .perform(RecyclerViewActions.scrollToPosition(1))
                .check(matches(atPosition(1, withText(R.string.add_favorite_button))));
    }

    @After
    public void tearDown() {
        Intents.release();
        mBluetoothDevice.disconnect();
        mFakeTelecomManager.clearCalls();
    }
}
