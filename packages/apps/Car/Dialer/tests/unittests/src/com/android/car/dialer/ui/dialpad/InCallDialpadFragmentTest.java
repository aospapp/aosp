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
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static org.mockito.Mockito.when;

import android.telecom.Call;

import androidx.core.util.Pair;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.dialer.R;
import com.android.car.dialer.testing.TestActivity;
import com.android.car.dialer.ui.activecall.InCallViewModel;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;

@RunWith(AndroidJUnit4.class)
public class InCallDialpadFragmentTest {

    @Mock
    private Call mMockCall;
    private MutableLiveData<Call> mPrimaryCallLiveData;

    @Test
    public void testOnCreateView_modeInCall() {
        startInCallActivity();

        onView(withId(R.id.title))
                .check(matches(withText("")));
    }

    private void startInCallActivity() {
        InCallDialpadFragment inCallDialpadFragment = new InCallDialpadFragment();
        mPrimaryCallLiveData = new MutableLiveData<>(mMockCall);

        ActivityScenario<TestActivity> activityScenario = ActivityScenario.launch(
                TestActivity.class);
        activityScenario.onActivity(activity -> {
            InCallViewModel mockInCallViewModel = new ViewModelProvider(activity).get(
                    InCallViewModel.class);
            MutableLiveData<Pair<Integer, Long>> stateAndConnectTimeLiveData =
                    new MutableLiveData<>();
            when(mockInCallViewModel.getCallStateAndConnectTime())
                    .thenReturn(stateAndConnectTimeLiveData);
            when(mockInCallViewModel.getPrimaryCall()).thenReturn(mPrimaryCallLiveData);
            activity.getSupportFragmentManager().beginTransaction().add(
                    R.id.test_fragment_container, inCallDialpadFragment).commit();
        });
    }
}
