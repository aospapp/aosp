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

package com.android.car.dialer.ui.contact;

import android.app.AlertDialog;
import android.content.Context;
import android.view.ActionProvider;
import android.view.View;
import android.widget.Button;

import com.android.car.dialer.R;
import com.android.car.dialer.ui.common.PhoneNumberListAdapter;
import com.android.car.telephony.common.Contact;
import com.android.car.telephony.common.PhoneNumber;
import com.android.car.telephony.common.TelecomUtils;

import java.util.List;

/** {@link ActionProvider} for setting contact default number menu in contact details page. */
public class ContactDefaultNumberActionProvider extends ActionProvider {
    private final Context mContext;
    private Contact mContact;
    private Button mPositiveButton;
    private PhoneNumber mSelectedPhoneNumber;

    public ContactDefaultNumberActionProvider(Context context) {
        super(context);
        mContext = context;
    }

    public void setContact(Contact contact) {
        mContact = contact;
        refreshVisibility();
    }

    @Override
    @Deprecated
    public View onCreateActionView() {
        return null;
    }

    @Override
    public boolean onPerformDefaultAction() {
        mSelectedPhoneNumber = null;

        List<PhoneNumber> contactPhoneNumbers = mContact.getNumbers();
        int primaryPhoneNumberIndex =
                mContact.hasPrimaryPhoneNumber() ? contactPhoneNumbers.indexOf(
                        mContact.getPrimaryPhoneNumber()) : -1;
        AlertDialog alertDialog = new AlertDialog.Builder(mContext)
                .setTitle(R.string.set_default_number)
                .setSingleChoiceItems(
                        new PhoneNumberListAdapter(mContext, contactPhoneNumbers),
                        primaryPhoneNumberIndex,
                        ((dialog, which) -> {
                            mSelectedPhoneNumber = contactPhoneNumbers.get(which);
                            mPositiveButton.setEnabled(
                                    which != primaryPhoneNumberIndex);
                        }))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(android.R.string.ok,
                        (dialog, which) ->
                                TelecomUtils.setAsPrimaryPhoneNumber(mContext,
                                        mSelectedPhoneNumber))
                .show();
        mPositiveButton = alertDialog.getButton(AlertDialog.BUTTON_POSITIVE);
        mPositiveButton.setEnabled(false);
        return true;
    }

    @Override
    public boolean overridesItemVisibility() {
        return true;
    }

    /** It will be visible when the contact has multiple numbers. */
    @Override
    public boolean isVisible() {
        return mContact != null && mContact.getNumbers().size() > 1;
    }
}
