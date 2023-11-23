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

package com.android.car.settings.datausage;

import static com.google.common.truth.Truth.assertThat;

import android.content.Context;
import android.net.NetworkPolicy;
import android.net.NetworkTemplate;
import android.os.Bundle;
import android.util.Pair;

import androidx.fragment.app.FragmentManager;
import androidx.test.core.app.ApplicationProvider;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.test.rule.ActivityTestRule;

import com.android.car.settings.R;
import com.android.car.settings.testutils.BaseCarSettingsTestActivity;
import com.android.settingslib.net.DataUsageController;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.MockitoAnnotations;

import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.concurrent.TimeUnit;

/** Unit test for {@link AppDataUsageFragment}. */
@RunWith(AndroidJUnit4.class)
public class AppDataUsageFragmentTest {
    private static final String KEY_TEMPLATE = "template";
    private static final String KEY_START = "start";
    private static final String KEY_END = "end";

    private Context mContext = ApplicationProvider.getApplicationContext();
    private TestAppDataUsageFragment mFragment;
    private BaseCarSettingsTestActivity mActivity;
    private FragmentManager mFragmentManager;

    private Iterator<Pair<ZonedDateTime, ZonedDateTime>> mIterator;

    @Rule
    public ActivityTestRule<BaseCarSettingsTestActivity> mActivityTestRule =
            new ActivityTestRule<>(BaseCarSettingsTestActivity.class);

    @Before
    public void setUp() throws Throwable {
        MockitoAnnotations.initMocks(this);
        mActivity = mActivityTestRule.getActivity();
        mFragmentManager = mActivityTestRule.getActivity().getSupportFragmentManager();
    }

    @Test
    public void onActivityCreated_policyIsNull_startAndEndDateShouldHaveFourWeeksDifference()
            throws Throwable {
        setUpFragment();
        Bundle bundle = mFragment.getBundle();
        NetworkTemplate networkTemplate = bundle.getParcelable(KEY_TEMPLATE);
        long start = bundle.getLong(KEY_START);
        long end = bundle.getLong(KEY_END);
        DataUsageController.DataUsageInfo dataUsageInfo =
                new DataUsageController(mContext).getDataUsageInfo(networkTemplate);

        compareMilliseconds(start, dataUsageInfo.cycleStart);
        compareMilliseconds(end, dataUsageInfo.cycleEnd);
    }

    @Test
    public void onActivityCreated_iteratorIsEmpty_startAndEndDateShouldHaveFourWeeksDifference()
            throws Throwable {
        ArrayList<Pair<ZonedDateTime, ZonedDateTime>> list = new ArrayList<>();
        mIterator = list.iterator();
        setUpFragment();

        Bundle bundle = mFragment.getBundle();
        NetworkTemplate networkTemplate = bundle.getParcelable(KEY_TEMPLATE);
        long start = bundle.getLong(KEY_START);
        long end = bundle.getLong(KEY_END);
        DataUsageController.DataUsageInfo dataUsageInfo =
                new DataUsageController(mContext).getDataUsageInfo(networkTemplate);

        compareMilliseconds(start, dataUsageInfo.cycleStart);
        compareMilliseconds(end, dataUsageInfo.cycleEnd);
    }

    private void setUpFragment() throws Throwable {
        String appDataUsageFragmentTag = "app_data_usage_fragment";
        mActivityTestRule.runOnUiThread(() -> {
            mFragmentManager.beginTransaction()
                    .replace(R.id.fragment_container,
                            TestAppDataUsageFragment.newInstance(mIterator),
                            appDataUsageFragmentTag)
                    .commitNow();
        });
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        mFragment = (TestAppDataUsageFragment) mFragmentManager
                .findFragmentByTag(appDataUsageFragmentTag);
    }

    public static class TestAppDataUsageFragment extends AppDataUsageFragment {

        private Iterator<Pair<ZonedDateTime, ZonedDateTime>> mIterator;

        public static TestAppDataUsageFragment newInstance(
                Iterator<Pair<ZonedDateTime, ZonedDateTime>> cycleIterator) {
            TestAppDataUsageFragment fragment = new TestAppDataUsageFragment();
            fragment.mIterator = cycleIterator;
            return fragment;
        }

        @Override
        Iterator<Pair<ZonedDateTime, ZonedDateTime>> getCycleIterator(NetworkPolicy policy) {
            if (mIterator != null) {
                return mIterator;
            }
            return super.getCycleIterator(policy);
        }
    }

    /** Avoid some offset in milliseconds. */
    private void compareMilliseconds(long milli1, long milli2) {
        assertThat(TimeUnit.MILLISECONDS.toMinutes(milli1))
                .isEqualTo(TimeUnit.MILLISECONDS.toMinutes(milli2));
    }
}
