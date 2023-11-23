/*
 * Copyright (C) 2019 The Android Open Source Project
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

import android.app.Application;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;
import android.text.TextUtils;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.MutableLiveData;

import com.android.car.telephony.common.ObservableAsyncQuery;
import com.android.car.telephony.common.QueryParam;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** {link AndroidViewModel} used for search functionality. */
public class ContactResultsViewModel extends AndroidViewModel {
    private static final String[] CONTACT_DETAILS_PROJECTION = {
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME,
            ContactsContract.Contacts.PHOTO_URI
    };

    private final SearchQueryParamProvider mSearchQueryParamProvider;
    private final ObservableAsyncQuery mObservableAsyncQuery;
    private final MutableLiveData<List<ContactDetails>> mContactSearchResultsLiveData;
    private String mSearchQuery;

    public ContactResultsViewModel(@NonNull Application application) {
        super(application);
        mSearchQueryParamProvider = new SearchQueryParamProvider();
        mContactSearchResultsLiveData = new MutableLiveData<>();
        mObservableAsyncQuery = new ObservableAsyncQuery(mSearchQueryParamProvider,
                application.getContentResolver(), this::onQueryFinished);
    }

    void setSearchQuery(String searchQuery) {
        if (TextUtils.equals(mSearchQuery, searchQuery)) {
            return;
        }

        mSearchQuery = searchQuery;
        if (TextUtils.isEmpty(searchQuery)) {
            mContactSearchResultsLiveData.setValue(Collections.emptyList());
        } else {
            mObservableAsyncQuery.startQuery();
        }
    }

    LiveData<List<ContactDetails>> getContactSearchResults() {
        return mContactSearchResultsLiveData;
    }

    String getSearchQuery() {
        return mSearchQuery;
    }

    private void onQueryFinished(@Nullable Cursor cursor) {
        if (cursor == null) {
            mContactSearchResultsLiveData.setValue(Collections.emptyList());
            return;
        }

        List<ContactDetails> contactDetails = new ArrayList<>();
        while (cursor.moveToNext()) {
            int idColIdx = cursor.getColumnIndex(ContactsContract.Contacts._ID);
            int lookupColIdx = cursor.getColumnIndex(ContactsContract.Contacts.LOOKUP_KEY);
            int nameColIdx = cursor.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME);
            int photoUriColIdx = cursor.getColumnIndex(ContactsContract.Contacts.PHOTO_URI);

            Uri lookupUri = ContactsContract.Contacts.getLookupUri(
                    cursor.getLong(idColIdx), cursor.getString(lookupColIdx));

            contactDetails.add(new ContactDetails(
                    cursor.getString(nameColIdx),
                    cursor.getString(photoUriColIdx),
                    lookupUri));
        }
        mContactSearchResultsLiveData.setValue(contactDetails);
        cursor.close();
    }

    private class SearchQueryParamProvider implements QueryParam.Provider {

        @Nullable
        @Override
        public QueryParam getQueryParam() {
            Uri lookupUri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI,
                    Uri.encode(mSearchQuery));
            return new QueryParam(lookupUri, CONTACT_DETAILS_PROJECTION,
                    ContactsContract.Contacts.HAS_PHONE_NUMBER + "!=0",
                    /* selectionArgs= */null, /* orderBy= */null);
        }
    }
}
