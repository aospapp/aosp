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
import static androidx.test.espresso.matcher.ViewMatchers.isChecked;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isNotChecked;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.dialer.testing.TestViewMatchers.atPosition;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;

import android.app.Instrumentation;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;

import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.espresso.intent.Intents;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.uiautomator.By;
import androidx.test.uiautomator.UiDevice;
import androidx.test.uiautomator.Until;

import com.android.car.dialer.R;
import com.android.car.dialer.framework.FakeHfpManager;
import com.android.car.dialer.framework.FakeTelecomManager;
import com.android.car.dialer.framework.testdata.CallLogDataHandler;
import com.android.car.dialer.ui.TelecomActivity;
import com.android.car.dialer.ui.activecall.InCallActivity;

import org.junit.After;
import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;

import java.util.List;

import javax.inject.Inject;

import dagger.hilt.android.testing.HiltAndroidRule;
import dagger.hilt.android.testing.HiltAndroidTest;

@RunWith(AndroidJUnit4.class)
@HiltAndroidTest
public class ActiveCallSettingTest {
    private static final String CALL_ID = "1234567";
    private static final long UI_RESPONSE_TIMEOUT_MS = 5000;

    @Rule
    public final HiltAndroidRule mHiltAndroidRule = new HiltAndroidRule(this);

    @Inject FakeHfpManager mFakeHfpManager;
    @Inject FakeTelecomManager mFakeTelecomManager;
    @Inject SharedPreferences mSharedPreferences;
    @Inject CallLogDataHandler mCallLogDataHandler;
    private Context mContext;
    private UiDevice mUiDevice;

    @Before
    public void setup() {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(() -> mHiltAndroidRule.inject());

        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        mSharedPreferences.edit().clear().commit();

        ActivityScenario.launch(new Intent(mContext, TelecomActivity.class));
        mFakeHfpManager.connectHfpDevice(/* withMockData = */false);

        onView(withId(R.id.menu_item_setting)).check(matches(isDisplayed())).perform(click());
    }

    @Test
    public void defaultOff_noInCallUi() {
        onView(withId(R.id.recycler_view))
                .perform(RecyclerViewActions.scrollToPosition(1))
                .check(matches(atPosition(1, hasDescendant(
                        allOf(withId(android.R.id.title),
                                withText(R.string.pref_show_fullscreen_active_call_ui_title))))))
                .check(matches(atPosition(1, hasDescendant(
                        allOf(withId(android.R.id.switch_widget), isNotChecked())))));

        Intents.init();
        intending(hasComponent(new ComponentName(mContext, InCallActivity.class))).respondWith(
                new Instrumentation.ActivityResult(RESULT_OK, null));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    mFakeTelecomManager.receiveCall(CALL_ID);
                    clickNotificationAnswerButton();
                    mFakeTelecomManager.endCall(CALL_ID);
                });

        List<Intent> intents = Intents.getIntents();
        assertThat(intents).isEmpty();

        // Teardown properly
        waitUntilNotificationDismissed();
        Intents.release();
    }

    @Test
    public void toggleOn_showInCallUi() {
        // Turn on
        onView(withId(R.id.recycler_view))
                .perform(RecyclerViewActions.actionOnItemAtPosition(1, click()))
                .check(matches(atPosition(1, hasDescendant(
                        allOf(withId(android.R.id.switch_widget), isChecked())))));

        Intents.init();
        intending(hasComponent(new ComponentName(mContext, InCallActivity.class))).respondWith(
                new Instrumentation.ActivityResult(RESULT_OK, null));

        InstrumentationRegistry.getInstrumentation().runOnMainSync(
                () -> {
                    mFakeTelecomManager.receiveCall(CALL_ID);
                    clickNotificationAnswerButton();
                    mFakeTelecomManager.endCall(CALL_ID);
                });

        Intent intent = Intents.getIntents().get(0);
        assertThat(intent).isNotNull();

        // Teardown properly
        waitUntilNotificationDismissed();
        Intents.release();
    }

    @After
    public void tearDown() {
        mCallLogDataHandler.tearDown();
    }

    private void clickNotificationAnswerButton() {
        mUiDevice.wait(Until.findObject(By.text("Answer")), UI_RESPONSE_TIMEOUT_MS).click();
    }

    private void waitUntilNotificationDismissed() {
        mUiDevice.wait(Until.gone(By.text("Answer")), UI_RESPONSE_TIMEOUT_MS);
    }
}
