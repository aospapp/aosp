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

package com.android.car.dialer.ui.contact;

import static androidx.test.espresso.Espresso.onView;
import static androidx.test.espresso.action.ViewActions.click;
import static androidx.test.espresso.assertion.ViewAssertions.matches;
import static androidx.test.espresso.matcher.ViewMatchers.isDisplayed;
import static androidx.test.espresso.matcher.ViewMatchers.isEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.isNotEnabled;
import static androidx.test.espresso.matcher.ViewMatchers.withEffectiveVisibility;
import static androidx.test.espresso.matcher.ViewMatchers.withId;
import static androidx.test.espresso.matcher.ViewMatchers.withText;

import static com.google.common.truth.Truth.assertThat;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;
import android.util.Pair;

import androidx.annotation.CallSuper;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;
import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.MutableLiveData;
import androidx.lifecycle.ViewModel;
import androidx.lifecycle.ViewModelProvider;
import androidx.test.core.app.ActivityScenario;
import androidx.test.espresso.contrib.RecyclerViewActions;
import androidx.test.espresso.matcher.ViewMatchers;
import androidx.test.ext.junit.runners.AndroidJUnit4;
import androidx.test.platform.app.InstrumentationRegistry;

import com.android.car.apps.common.util.FutureData;
import com.android.car.dialer.R;
import com.android.car.dialer.telecom.UiCallManager;
import com.android.car.dialer.testing.TestActivity;
import com.android.car.dialer.ui.common.OnItemClickedListener;
import com.android.car.dialer.ui.favorite.FavoriteViewModel;
import com.android.car.telephony.common.Contact;
import com.android.car.telephony.common.PhoneNumber;
import com.android.car.telephony.common.PostalAddress;
import com.android.car.telephony.common.TelecomUtils;

import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

@RunWith(AndroidJUnit4.class)
public class ContactListFragmentTest {
    private static final String RAW_NUMBER = "6502530000";

    private Context mContext;
    private TestContactListFragment mContactListFragment;
    @Mock
    private UiCallManager mMockUiCallManager;
    private ContactListViewModel mMockContactListViewModel;
    private ContactDetailsViewModel mMockContactDetailsViewModel;
    @Mock
    private FavoriteViewModel mMockFavoriteViewModel;
    @Mock
    private Contact mMockContact1;
    @Mock
    private PhoneNumber mMockPhoneNumber;
    @Mock
    private PostalAddress mMockPostalAddress;

    private ActivityScenario<TestActivity> mActivityScenario;

    // This is initialized in the TestContactDetailsFragment#getDefaultViewModelProviderFactory();
    private final FragmentManager.FragmentLifecycleCallbacks mCallbacks =
            new FragmentManager.FragmentLifecycleCallbacks() {
                @Override
                public void onFragmentAttached(@NonNull FragmentManager fm, @NonNull Fragment f,
                        @NonNull Context context) {
                    mMockContactListViewModel = new ViewModelProvider(f).get(
                            ContactListViewModel.class);
                    MutableLiveData<FutureData<Pair<Integer, List<Contact>>>> contactList =
                            new MutableLiveData<>();
                    contactList.setValue(
                            new FutureData<>(false, new Pair<>(TelecomUtils.SORT_BY_LAST_NAME,
                                    Arrays.asList(mMockContact1))));
                    when(mMockContactListViewModel.getAllContacts()).thenReturn(contactList);
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
        mContext = InstrumentationRegistry.getInstrumentation().getTargetContext();
    }

    @Test
    public void testClickCallActionButton_ContactHasOneNumber_placeCall() {
        when(mMockContact1.getNumbers()).thenReturn(Arrays.asList(mMockPhoneNumber));
        when(mMockPhoneNumber.getRawNumber()).thenReturn(RAW_NUMBER);
        setUpFragment();

        onView(withId(R.id.list_view)).perform(
                RecyclerViewActions.actionOnItemAtPosition(0, click()));
        mActivityScenario.moveToState(Lifecycle.State.STARTED);
        ArgumentCaptor<String> captor = ArgumentCaptor.forClass(String.class);
        verify(mMockUiCallManager).placeCall(captor.capture());
        assertThat(captor.getValue()).isEqualTo(RAW_NUMBER);
    }

    @Test
    public void testClickCallActionButton_ContactHasMultipleNumbers_showAlertDialog() {
        PhoneNumber otherMockPhoneNumber = mock(PhoneNumber.class);
        when(mMockContact1.getNumbers()).thenReturn(
                Arrays.asList(mMockPhoneNumber, otherMockPhoneNumber));
        setUpFragment();

        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        onView(withId(R.id.call_action_id)).perform(click());

        verify(mMockUiCallManager, never()).placeCall(any());
        onView(withText(R.string.select_number_dialog_title)).check(matches(isDisplayed()));
    }

    @Test
    public void testClickCallActionButton_ContactHasNoNumbers_callActionButtonNotEnabled() {
        when(mMockContact1.getNumbers()).thenReturn(new ArrayList<>());
        setUpFragment();

        onView(withId(R.id.call_action_id)).check(matches(isNotEnabled()));

        boolean displayed = mContext.getResources().getBoolean(
                R.bool.config_show_contact_detail_button_for_empty_contact);
        if (displayed) {
            onView(withId(R.id.call_action_id)).check(matches(isDisplayed()));
        } else {
            onView(withId(R.id.call_action_id)).check(matches(withEffectiveVisibility(
                    ViewMatchers.Visibility.GONE)));
        }
    }

    @Test
    public void testClickShowContactDetailView_withPhoneNumberAndAddress_showContactDetail() {
        when(mMockContact1.getNumbers()).thenReturn(Arrays.asList(mMockPhoneNumber));
        when(mMockContact1.getPostalAddresses()).thenReturn(Arrays.asList(mMockPostalAddress));
        setUpFragment();

        onView(withId(R.id.contact_list_button)).perform(click());
        InstrumentationRegistry.getInstrumentation().waitForIdleSync();
        // verify contact detail is shown.
        verifyShowContactDetail();
    }

    @Test
    public void testClickShowContactDetailView_withAddressButNoPhoneNumber_dependOnConfig() {
        when(mMockContact1.getPostalAddresses()).thenReturn(Arrays.asList(mMockPostalAddress));
        setUpFragment();

        boolean showPostalAddress = mContext.getResources().getBoolean(
                R.bool.config_show_postal_address);
        boolean forceShowButton = mContext.getResources().getBoolean(
                R.bool.config_show_contact_detail_button_for_empty_contact);

        if (showPostalAddress) {
            onView(withId(R.id.show_contact_detail_id)).check(matches(isEnabled()));
        } else {
            onView(withId(R.id.show_contact_detail_id)).check(matches(isNotEnabled()));
        }

        if (forceShowButton || showPostalAddress) {
            onView(withId(R.id.show_contact_detail_id)).check(matches(isDisplayed()));
        } else {
            onView(withId(R.id.show_contact_detail_id)).check(matches(withEffectiveVisibility(
                    ViewMatchers.Visibility.GONE)));
        }

        if (showPostalAddress) {
            onView(withId(R.id.show_contact_detail_id)).perform(click());

            // verify contact detail is shown.
            verifyShowContactDetail();
        }
    }

    @Test
    public void testClickShowContactDetailView_NoPhoneNumberAndNoAddress_NotEnabled() {
        setUpFragment();

        onView(withId(R.id.show_contact_detail_id)).check(matches(isNotEnabled()));

        boolean displayed = mContext.getResources().getBoolean(
                R.bool.config_show_contact_detail_button_for_empty_contact);
        if (displayed) {
            onView(withId(R.id.call_action_id)).check(matches(isDisplayed()));
        } else {
            onView(withId(R.id.call_action_id)).check(matches(withEffectiveVisibility(
                    ViewMatchers.Visibility.GONE)));
        }
    }

    private void setUpFragment() {
        mActivityScenario = ActivityScenario.launch(TestActivity.class);
        mActivityScenario.onActivity(activity -> {
            mMockContactDetailsViewModel = new ViewModelProvider(activity).get(
                    ContactDetailsViewModel.class);
            MutableLiveData<FutureData<Contact>> contactDetail = new MutableLiveData<>();
            contactDetail.setValue(new FutureData<>(false, mMockContact1));
            when(mMockContactDetailsViewModel.getContactDetails(any())).thenReturn(contactDetail);

            mMockFavoriteViewModel = new ViewModelProvider(activity).get(
                    FavoriteViewModel.class);
            when(mMockFavoriteViewModel.getFavoriteContacts()).thenReturn(new MutableLiveData<>());

            mContactListFragment = TestContactListFragment.newInstance();

            OnItemClickedListener<Contact> listener = (contact) -> {
                Fragment contactDetailsFragment = ContactDetailsFragment.newInstance(contact);
                activity.getSupportFragmentManager().beginTransaction().replace(
                        R.id.test_fragment_container,
                        contactDetailsFragment,
                        ContactDetailsFragment.FRAGMENT_TAG)
                        .addToBackStack(ContactDetailsFragment.FRAGMENT_TAG)
                        .commit();
            };
            ContactListViewHolder.Factory viewHolderFactory = itemView -> new ContactListViewHolder(
                    itemView, listener, mMockUiCallManager);
            ContactListAdapter adapter = new ContactListAdapter(activity, viewHolderFactory);
            mContactListFragment.setContactListAdapter(adapter);

            activity.getSupportFragmentManager().registerFragmentLifecycleCallbacks(
                    mCallbacks, /* recursive= */false);
            activity.getSupportFragmentManager().beginTransaction().add(
                    R.id.test_fragment_container, mContactListFragment).commit();
        });
    }

    private void verifyShowContactDetail() {
        mActivityScenario.onActivity((activity) -> {
            FragmentManager manager = activity.getSupportFragmentManager();
            String tag = manager.getBackStackEntryAt(manager.getBackStackEntryCount() - 1)
                    .getName();
            Fragment fragment = manager.findFragmentByTag(tag);
            assertThat(fragment instanceof ContactDetailsFragment).isTrue();
        });
    }

    public static class TestContactListFragment extends ContactListFragment {

        private ContactListAdapter mContactListAdapterToOverride;

        public void setContactListAdapter(ContactListAdapter adapter) {
            mContactListAdapterToOverride = adapter;
        }

        public static TestContactListFragment newInstance() {
            return new TestContactListFragment();
        }

        @CallSuper
        @Override
        public void onAttach(Context context) {
            super.onAttach(context);
            mContactListAdapter = mContactListAdapterToOverride;
        }

        @CallSuper
        @Override
        public void onAttach(Activity activity) {
            super.onAttach(activity);
            mContactListAdapter = mContactListAdapterToOverride;
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
