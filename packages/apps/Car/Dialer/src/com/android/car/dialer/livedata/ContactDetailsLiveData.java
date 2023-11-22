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

package com.android.car.dialer.livedata;

import android.content.ContentUris;
import android.content.Context;
import android.database.Cursor;
import android.net.Uri;
import android.provider.ContactsContract;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.car.telephony.common.AsyncQueryLiveData;
import com.android.car.telephony.common.Contact;
import com.android.car.telephony.common.QueryParam;

/** {@link androidx.lifecycle.LiveData} for contact details that observes the contact change. */
public class ContactDetailsLiveData extends AsyncQueryLiveData<Contact> {
    private final Context mContext;

    public ContactDetailsLiveData(Context context, @NonNull Uri contactLookupUri) {
        super(context, new ContactDetailsQueryParamProvider(contactLookupUri, context));
        mContext = context;
    }

    @Override
    protected Contact convertToEntity(Cursor cursor) {
        // Contact is not deleted.
        if (cursor.moveToFirst()) {
            Contact contact = Contact.fromCursor(mContext, cursor);
            while (cursor.moveToNext()) {
                contact.merge(Contact.fromCursor(mContext, cursor));
            }
            return contact;
        }
        return null;
    }

    /**
     * Contact id varies on contact change. When we start a new query, this {@link
     * QueryParam.Provider} refreshes the contact lookup uri to get the most up to date contact id
     * and creates a new {@link QueryParam}.
     */
    private static class ContactDetailsQueryParamProvider implements QueryParam.Provider {

        private final Context mContext;
        private final Uri mContactLookupUri;

        public ContactDetailsQueryParamProvider(Uri contactLookupUri, Context context) {
            mContactLookupUri = contactLookupUri;
            mContext = context;
        }

        @Nullable
        @Override
        public QueryParam getQueryParam() {
            Uri refreshedContactLookupUri = ContactsContract.Contacts.getLookupUri(
                    mContext.getContentResolver(), mContactLookupUri);
            return convertToQueryParam(refreshedContactLookupUri);
        }

        /**
         * Build the query param from the given contact lookup uri. Caller is responsible for
         * passing in the most up to date uri.
         *
         * @param contactLookupUri Up to date uri describing the requested {@link Contact} entry.
         *                         When contact is deleted, the uri will be null.
         */
        @Nullable
        private static QueryParam convertToQueryParam(@Nullable Uri contactLookupUri) {
            if (contactLookupUri == null) {
                return null;
            }
            long contactId = ContentUris.parseId(contactLookupUri);
            return new QueryParam(
                    ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                    /* projection= */null,
                    /* selection= */ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                    new String[]{String.valueOf(contactId)},
                    /* orderBy= */null);
        }
    }
}
