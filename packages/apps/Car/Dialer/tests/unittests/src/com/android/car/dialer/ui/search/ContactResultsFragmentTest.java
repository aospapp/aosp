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

package com.android.car.dialer.ui.search;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.doesNotExist;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.hasDescendant;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.android.car.dialer.testing.TestViewMatchers.atPosition;

import static com.google.common.truth.Truth.assertThat;

import static org.hamcrest.Matchers.allOf;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import android.content.Context;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentActivity;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.ext.junit.runners.AndroidJUnit4;

import com.android.car.apps.common.util.FutureData;
import com.android.car.apps.common.util.LiveDataFunctions;
import com.android.car.dialer.R;
import com.android.car.dialer.testing.TestActivity;
import com.android.car.dialer.ui.common.ContactResultsLiveData;
import com.android.car.dialer.ui.contact.ContactDetailsFragment;
import com.android.car.dialer.ui.contact.ContactDetailsViewModel;
import com.android.car.telephony.common.Contact;
import com.android.car.telephony.common.PhoneNumber;
import com.android.car.telephony.common.TelecomUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ContactResultsFragmentTest {

    private static final String INITIAL_SEARCH_QUERY = "";
    private static final String[] DISPLAY_NAMES = {"name1", "name2", "name3"};

    private ActivityScenario<TestActivity> mActivityScenario;

    private ContactResultsFragment mContactResultsFragment;
    private MutableLiveData<List<ContactResultsLiveData.ContactResultListItem>>
            mContactSearchResultsLiveData;

    @Mock
    private Contact mMockContact, mContact1, mContact2, mContact3;
    @Mock
    private ContactResultsLiveData.ContactResultListItem mContactResult1, mContactResult2,
            mContactResult3;
    @Mock
    private PhoneNumber mPhoneNumber;

    // This is initialized in the TestContactResultsFragment#getDefaultViewModelProviderFactory();
    private ContactResultsViewModel mContactResultsViewModel;

    private final FragmentManager.FragmentLifecycleCallbacks mCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentAttached(@NonNull FragmentManager fm, @NonNull Fragment f,
                        @NonNull Context context) {
                    mContactResultsViewModel = new ViewModelProvider(f)
                            .get(ContactResultsViewModel.class);

                    when(mContactResultsViewModel.getContactSearchResults())
                            .thenReturn(mContactSearchResultsLiveData);
                    when(mContactResultsViewModel.getSortOrderLiveData()).thenReturn(
                            LiveDataFunctions.dataOf(TelecomUtils.SORT_BY_FIRST_NAME));
                }

                @Override
                public void onFragmentCreated(@NonNull FragmentManager fm, @NonNull Fragment f,
                        @Nullable Bundle savedInstanceState) {
                    fm.unregisterFragmentLifecycleCallbacks(mCallbacks);
                }
            };

    @Before
    public void setUp() {
        MockitoAnnotations.initMocks(this);

        when(mContactResult1.getContact()).thenReturn(mContact1);
        when(mContact1.getDisplayName()).thenReturn(DISPLAY_NAMES[0]);
        when(mContact1.getNumbers()).thenReturn(Collections.singletonList(mPhoneNumber));
        when(mContactResult2.getContact()).thenReturn(mContact2);
        when(mContact2.getDisplayName()).thenReturn(DISPLAY_NAMES[1]);
        when(mContact2.getNumbers()).thenReturn(Collections.singletonList(mPhoneNumber));
        when(mContactResult3.getContact()).thenReturn(mContact3);
        when(mContact3.getDisplayName()).thenReturn(DISPLAY_NAMES[2]);
        when(mContact3.getNumbers()).thenReturn(Collections.singletonList(mPhoneNumber));
    }

    @Test
    public void testDisplaySearchResults_emptyResult() {
        mContactSearchResultsLiveData = new MutableLiveData<>();
        setUpFragment();

        onView(withId(R.id.contact_result)).check(doesNotExist());
    }

    @Test
    public void testDisplaySearchResults_multipleResults() {
        mContactSearchResultsLiveData = new MutableLiveData<>(
                Arrays.asList(mContactResult1, mContactResult2, mContactResult3));
        setUpFragment();

        onView(withId(R.id.list_view))
                .perform(RecyclerViewActions.scrollToPosition(0))
                .check(matches(atPosition(0, hasDescendant(
                        allOf(withId(R.id.contact_name), withText(DISPLAY_NAMES[0]))))))
                .check(matches(atPosition(1, hasDescendant(
                        allOf(withId(R.id.contact_name), withText(DISPLAY_NAMES[1]))))))
                .check(matches(atPosition(2, hasDescendant(
                        allOf(withId(R.id.contact_name), withText(DISPLAY_NAMES[2]))))));
    }

    @Test
    public void testClickSearchResult_showContactDetailPage() {
        mContactSearchResultsLiveData = new MutableLiveData<>(
                Arrays.asList(mContactResult1, mContactResult2, mContactResult3));
        setUpFragment();

        onView(withId(R.id.list_view)).perform(
                RecyclerViewActions.actionOnItemAtPosition(1, click()));

        // verify contact detail is shown.
        verifyShowContactDetail();
    }

    private void setUpFragment() {
        mActivityScenario = ActivityScenario.launch(TestActivity.class);
        mActivityScenario.onActivity(activity -> {
            ContactDetailsViewModel contactDetailsViewModel = new ViewModelProvider(activity).get(
                    ContactDetailsViewModel.class);
            MutableLiveData<FutureData<Contact>> contactDetailLiveData = new MutableLiveData<>(
                    new FutureData<>(false, mMockContact));
            when(contactDetailsViewModel.getContactDetails(any()))
                    .thenReturn(contactDetailLiveData);

            mContactResultsFragment = TestContactResultsFragment.newInstance(INITIAL_SEARCH_QUERY);
            activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                    mCallbacks, /* recursive= */false);
            activity.getSupportFragmentManager().beginTransaction().add(
                    R.id.test_fragment_container, mContactResultsFragment).commit();
        });
    }

    private void verifyShowContactDetail() {
        mActivityScenario.onActivity(activity -> {
            FragmentManager manager = activity.getSupportFragmentManager();
            String tag = manager.getBackStackEntryAt(manager.getBackStackEntryCount() - 1)
                    .getName();
            Fragment fragment = manager.findFragmentByTag(tag);
            assertThat(fragment instanceof ContactDetailsFragment).isTrue();
        });
    }

    /** A test override that creates mock {@link ViewModel}s. */
    public static class TestContactResultsFragment extends ContactResultsFragment {

        public static TestContactResultsFragment newInstance(@Nullable String initialSearchQuery) {
            TestContactResultsFragment fragment = new TestContactResultsFragment();
            Bundle args = new Bundle();
            args.putString("SearchQuery", initialSearchQuery);
            fragment.setArguments(args);
            return fragment;
        }

        @Override
        public void pushContentFragment(@NonNull Fragment fragment, String fragmentTag) {
            FragmentActivity activity = getActivity();
            activity.getSupportFragmentManager().beginTransaction().replace(
                    R.id.test_fragment_container, fragment, fragmentTag)
                    .addToBackStack(fragmentTag)
                    .commit();
        }

        @Override
        public ViewModelProvider.Factory getDefaultViewModelProviderFactory() {
            return new ViewModelProvider.Factory() {
                @NonNull
                @Override
                public <T extends ViewModel> T create(@NonNull Class<T> aClass) {
                    return mock(aClass);
                }
            };
        }
    }
}
