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

import android.content.Context;
import android.database.Cursor;
import android.provider.ContactsContract;

import com.android.car.telephony.common.AsyncQueryLiveData;
import com.android.car.telephony.common.Contact;
import com.android.car.telephony.common.QueryParam;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Live data which loads starred contact list.
 */
public class FavoriteContactLiveData extends AsyncQueryLiveData<List<Contact>> {
    private static final int IS_STARRED = 1;
    private final Context mContext;

    /**
     * Creates a new instance of {@link FavoriteContactLiveData}.
     */
    public static FavoriteContactLiveData newInstance(Context context) {
        String selection = ContactsContract.Data.MIMETYPE + " = ?"
                + " and "
                + ContactsContract.Data.STARRED + " = ?";
        String[] selectionArgs = new String[2];
        selectionArgs[0] = ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE;
        selectionArgs[1] = String.valueOf(IS_STARRED);

        QueryParam starredContactsQueryParam =
                new QueryParam(
                        ContactsContract.Data.CONTENT_URI,
                        null,
                        selection,
                        selectionArgs,
                        ContactsContract.Contacts.DISPLAY_NAME + " ASC ");
        return new FavoriteContactLiveData(context, starredContactsQueryParam);
    }

    private FavoriteContactLiveData(Context context, QueryParam queryParam) {
        super(context, QueryParam.of(queryParam));
        mContext = context;
    }

    @Override
    protected List<Contact> convertToEntity(Cursor cursor) {
        Map<String, Contact> result = new LinkedHashMap<>();
        while (cursor.moveToNext()) {
            Contact contact = Contact.fromCursor(mContext, cursor);
            String lookupKey = contact.getLookupKey();
            if (result.containsKey(lookupKey)) {
                Contact existingContact = result.get(lookupKey);
                existingContact.merge(contact);
            } else {
                result.put(lookupKey, contact);
            }
        }
        return new ArrayList<>(result.values());
    }
}
