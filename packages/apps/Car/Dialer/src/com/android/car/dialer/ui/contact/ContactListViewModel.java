/*
 * Copyright (C) 2018 The Android Open Source Project
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

import android.app.Application;
import android.content.Context;
import android.util.Pair;

import androidx.annotation.NonNull;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MediatorLiveData;

import com.android.car.arch.common.FutureData;
import com.android.car.arch.common.LiveDataFunctions;
import com.android.car.dialer.R;
import com.android.car.dialer.livedata.SharedPreferencesLiveData;
import com.android.car.dialer.ui.common.entity.ContactSortingInfo;
import com.android.car.telephony.common.Contact;
import com.android.car.telephony.common.InMemoryPhoneBook;

import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/**
 * View model for {@link ContactListFragment}.
 */
public class ContactListViewModel extends AndroidViewModel {
    private final Context mContext;
    private final LiveData<Pair<Integer, List<Contact>>> mSortedContactListLiveData;
    private final LiveData<FutureData<Pair<Integer, List<Contact>>>> mContactList;

    public ContactListViewModel(@NonNull Application application) {
        super(application);
        mContext = application.getApplicationContext();

        SharedPreferencesLiveData preferencesLiveData =
                new SharedPreferencesLiveData(mContext, R.string.sort_order_key);
        LiveData<List<Contact>> contactListLiveData = InMemoryPhoneBook.get().getContactsLiveData();
        mSortedContactListLiveData = new SortedContactListLiveData(
                mContext, contactListLiveData, preferencesLiveData);
        mContactList = LiveDataFunctions.loadingSwitchMap(mSortedContactListLiveData,
                input -> LiveDataFunctions.dataOf(input));
    }

    /**
     * Returns a live data which represents a list of all contacts.
     */
    public LiveData<FutureData<Pair<Integer, List<Contact>>>> getAllContacts() {
        return mContactList;
    }

    private static class SortedContactListLiveData
            extends MediatorLiveData<Pair<Integer, List<Contact>>> {

        private final LiveData<List<Contact>> mContactListLiveData;
        private final SharedPreferencesLiveData mPreferencesLiveData;
        private final Context mContext;

        private final ExecutorService mExecutorService;
        private Future<?> mRunnableFuture;

        private SortedContactListLiveData(Context context,
                @NonNull LiveData<List<Contact>> contactListLiveData,
                @NonNull SharedPreferencesLiveData sharedPreferencesLiveData) {
            mContext = context;
            mContactListLiveData = contactListLiveData;
            mPreferencesLiveData = sharedPreferencesLiveData;
            mExecutorService = Executors.newSingleThreadExecutor();

            addSource(mPreferencesLiveData, (trigger) -> updateSortedContactList());
            addSource(mContactListLiveData, (trigger) -> updateSortedContactList());
        }

        private void updateSortedContactList() {
            // Don't set null value to trigger an update when there is no value set.
            if (mContactListLiveData.getValue() == null && getValue() == null) {
                return;
            }

            if (mContactListLiveData.getValue() == null
                    || mContactListLiveData.getValue().isEmpty()) {
                setValue(null);
                return;
            }

            List<Contact> contactList = mContactListLiveData.getValue();
            Pair<Comparator<Contact>, Integer> contactSortingInfo = ContactSortingInfo
                    .getSortingInfo(mContext, mPreferencesLiveData);
            Comparator<Contact> comparator = contactSortingInfo.first;
            Integer sortMethod = contactSortingInfo.second;

            // SingleThreadPoolExecutor is used here to avoid multiple threads sorting the list
            // at the same time.
            if (mRunnableFuture != null) {
                mRunnableFuture.cancel(true);
            }

            Runnable runnable = () -> {
                Collections.sort(contactList, comparator);
                postValue(new Pair<>(sortMethod, contactList));
            };
            mRunnableFuture = mExecutorService.submit(runnable);
        }

        @Override
        protected void onInactive() {
            super.onInactive();
            if (mRunnableFuture != null) {
                mRunnableFuture.cancel(true);
            }
        }
    }
}
