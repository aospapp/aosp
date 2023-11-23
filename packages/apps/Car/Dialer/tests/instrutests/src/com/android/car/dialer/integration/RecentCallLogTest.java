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
import static androidx.test.espresso.action.ViewActions.repeatedlyUntil;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.intent.Intents.intending;
import static androidx.test.espresso.intent.matcher.IntentMatchers.hasComponent;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.verify;

import android.app.Instrumentation;
import android.content.Intent;
import android.net.Uri;
import android.telecom.TelecomManager;
import android.view.View;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.intent.Intents;
import androidx.test.espresso.matcher.BoundedMatcher;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.filters.SmallTest;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.UiObject2;
import androidx.test.uiautomator.Until;

import com.android.car.dialer.R;
import com.android.car.dialer.framework.FakeHfpManager;
import com.android.car.dialer.framework.FakeTelecomManager;
import com.android.car.dialer.framework.SimulatedBluetoothDevice;
import com.android.car.dialer.framework.testdata.CallLogRawData;
import com.android.car.dialer.livedata.CallHistoryLiveData;
import com.android.car.dialer.notification.MissedCallNotificationController;
import com.android.car.dialer.testing.TestViewActions;
import com.android.car.dialer.ui.TelecomActivity;
import com.android.car.dialer.ui.activecall.InCallActivity;
import com.android.car.dialer.widget.CallTypeIconsView;

import org.hamcrest.Description;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import javax.inject.Inject;

import dagger.hilt.android.testing.HiltAndroidRule;
import dagger.hilt.android.testing.HiltAndroidTest;

@SmallTest
@RunWith(AndroidJUnit4.class)
@HiltAndroidTest
public class RecentCallLogTest {
    private static final String HEADER = "Today";
    private static final String RELATIVE_TIME = "0 min. ago";
    private static final String PHONE_NUMBER = "511";
    private static final Uri CALL_URI = Uri.fromParts("tel", PHONE_NUMBER, null);
    private static final long UI_RESPONSE_TIMEOUT_MS = 10000;
    private static final String MISSED_CALL = "Missed call";
    private static final String CALL_BACK = "Call back";

    @Inject FakeHfpManager mFakeHfpManager;
    @Inject TelecomManager mTelecomManager;
    @Inject FakeTelecomManager mFakeTelecomManager;
    @Inject MissedCallNotificationController mMissedCallNotificationController;
    private SimulatedBluetoothDevice mBluetoothDevice;
    private UiDevice mUiDevice;

    @Rule
    public final HiltAndroidRule mHiltAndroidRule = new HiltAndroidRule(this);

    @Before
    public void setup() {
        MockitoAnnotations.initMocks(RecentCallLogTest.this);
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());

        Intents.init();
        // CardView error, suppressing InCallActivity by responding with RESULT_OK.
        intending(hasComponent(InCallActivity.class.getName())).respondWith(
                new Instrumentation.ActivityResult(RESULT_OK, null));

        // MissedCallNotificationController needs to be injected on the ui thread.
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> mHiltAndroidRule.inject());
        mBluetoothDevice = mFakeHfpManager.connectHfpDevice(/* withMockData= */false);
    }

    @Test
    public void verifyRecentCallScreen() {
        CallLogRawData incomingCall = new CallLogRawData();
        incomingCall.setNumber(PHONE_NUMBER);
        incomingCall.setInterval(3000);
        incomingCall.setCallType(CallHistoryLiveData.CallType.INCOMING_TYPE);
        mBluetoothDevice.insertCallLogInBackground(incomingCall);

        CallLogRawData outgoingCall = new CallLogRawData();
        outgoingCall.setNumber(PHONE_NUMBER);
        outgoingCall.setInterval(30000);
        outgoingCall.setCallType(CallHistoryLiveData.CallType.OUTGOING_TYPE);
        mBluetoothDevice.insertCallLogInBackground(outgoingCall);

        ActivityScenario.launch(
                new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        TelecomActivity.class));
        onView(withText(R.string.call_history_title)).check(matches(isDisplayed()));

        onView(withId(R.id.list_view)).perform(repeatedlyUntil(TestViewActions.waitAction(100),
                hasDescendant(withText(PHONE_NUMBER)), 50));

        onView(allOf(withText(HEADER), withId(R.id.title))).check(matches(isDisplayed()));
        onView(allOf(withText(PHONE_NUMBER), withId(R.id.title))).check(matches(isDisplayed()));
        onView(allOf(withText(RELATIVE_TIME), withId(R.id.text))).check(matches(isDisplayed()));

        onView(withId(R.id.call_type_icons))
                .check(matches(
                        new CallTypeIconMatcher(0, CallHistoryLiveData.CallType.INCOMING_TYPE)))
                .check(matches(
                        new CallTypeIconMatcher(1, CallHistoryLiveData.CallType.OUTGOING_TYPE)));

        onView(withId(R.id.call_action_id)).perform(click());
        verify(mTelecomManager).placeCall(eq(CALL_URI), isNull());
    }

    @Test
    public void emptyPhoneNumber_showAsUnknownCall() {
        CallLogRawData unknownCall = new CallLogRawData();
        unknownCall.setNumber("");
        unknownCall.setInterval(3000);
        unknownCall.setNumberType(CallHistoryLiveData.CallType.INCOMING_TYPE);
        mBluetoothDevice.insertCallLogInBackground(unknownCall);

        ActivityScenario.launch(
                new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        TelecomActivity.class));
        // Verify there is no loading issue and the call is displayed as unknown calls.
        onView(allOf(withText("Unknown"), withId(R.id.title))).check(matches(isDisplayed()));
    }

    @Ignore("//TODO fix")
    @Test
    public void newMissedCall_showNotification() {
        CallLogRawData missedCall = new CallLogRawData();
        missedCall.setNumber(PHONE_NUMBER);
        missedCall.setInterval(3000);
        missedCall.setRead(false);
        missedCall.setCallType(CallHistoryLiveData.CallType.MISSED_TYPE);
        mBluetoothDevice.insertCallLogInBackground(missedCall);

        ActivityScenario.launch(
                new Intent(InstrumentationRegistry.getInstrumentation().getTargetContext(),
                        TelecomActivity.class));
        onView(withId(R.id.call_type_icons)).check(
                matches(new CallTypeIconMatcher(0, CallHistoryLiveData.CallType.MISSED_TYPE)));

        // Show notifications.
        mUiDevice.findObject(By.res("com.android.systemui", "notifications")).click();
        // Verify the missed call notification is shown.
        UiObject2 missedCallNotificationTitle = mUiDevice.wait(
                Until.findObject(By.text(MISSED_CALL)), UI_RESPONSE_TIMEOUT_MS);
        assertThat(missedCallNotificationTitle).isNotNull();
        mUiDevice.wait(Until.findObject(By.text(CALL_BACK)), UI_RESPONSE_TIMEOUT_MS).click();
        // Verify the notification is gone.
        mUiDevice.wait(Until.gone(By.text(CALL_BACK)), UI_RESPONSE_TIMEOUT_MS);
        // Verify the call is placed.
        verify(mTelecomManager).placeCall(eq(CALL_URI), isNull());
    }

    @After
    public void tearDown() {
        mFakeTelecomManager.clearCalls();
        mBluetoothDevice.disconnect();
        Intents.release();
    }

    private static class CallTypeIconMatcher extends BoundedMatcher<View, CallTypeIconsView> {
        private final int mIndex;
        private final int mExpectedIconType;

        CallTypeIconMatcher(int index, int expectedIconType) {
            super(CallTypeIconsView.class);
            mIndex = index;
            mExpectedIconType = expectedIconType;
        }

        @Override
        protected boolean matchesSafely(CallTypeIconsView callTypeIconsView) {
            return callTypeIconsView.getCallType(mIndex) == mExpectedIconType;
        }

        @Override
        public void describeTo(Description description) {
            description.appendText("Call icon type at " + mIndex + " is " + mExpectedIconType);
        }
    }
}
