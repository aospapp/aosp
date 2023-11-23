/*
 * Copyright (C) 2017 The Android Open Source Project
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

import android.app.ActionBar;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.lifecycle.LiveData;
import androidx.lifecycle.ViewModelProviders;

import com.android.car.dialer.R;
import com.android.car.dialer.ui.common.DialerListBaseFragment;
import com.android.car.dialer.ui.common.DialerUtils;
import com.android.car.dialer.ui.view.ContactAvatarOutputlineProvider;
import com.android.car.telephony.common.Contact;
import com.android.car.telephony.common.TelecomUtils;

/**
 * A fragment that shows the name of the contact, the photo and all listed phone numbers. It is
 * primarily used to respond to the results of search queries but supplyig it with the content://
 * uri of a contact should work too.
 */
public class ContactDetailsFragment extends DialerListBaseFragment {
    private static final String TAG = "CD.ContactDetailsFragment";
    public static final String FRAGMENT_TAG = "CONTACT_DETAIL_FRAGMENT_TAG";

    // Key to load and save the contact entity instance.
    private static final String KEY_CONTACT_ENTITY = "ContactEntity";

    // Key to load the contact details by passing in the content provider query uri.
    private static final String KEY_CONTACT_QUERY_URI = "ContactQueryUri";

    private Contact mContact;
    private Uri mContactLookupUri;
    private LiveData<Contact> mContactDetailsLiveData;
    private ImageView mAvatarView;
    private TextView mNameView;

    /** Creates a new ContactDetailsFragment using a URI to lookup a {@link Contact} at. */
    public static ContactDetailsFragment newInstance(Uri uri) {
        ContactDetailsFragment fragment = new ContactDetailsFragment();
        Bundle args = new Bundle();
        args.putParcelable(KEY_CONTACT_QUERY_URI, uri);
        fragment.setArguments(args);
        return fragment;
    }

    /** Creates a new ContactDetailsFragment using a {@link Contact}. */
    public static ContactDetailsFragment newInstance(Contact contact) {
        ContactDetailsFragment fragment = new ContactDetailsFragment();
        Bundle args = new Bundle();
        args.putParcelable(KEY_CONTACT_ENTITY, contact);
        fragment.setArguments(args);
        return fragment;
    }

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setHasOptionsMenu(true);

        mContact = getArguments().getParcelable(KEY_CONTACT_ENTITY);
        mContactLookupUri = getArguments().getParcelable(KEY_CONTACT_QUERY_URI);
        if (mContact == null && savedInstanceState != null) {
            mContact = savedInstanceState.getParcelable(KEY_CONTACT_ENTITY);
        }
        if (mContact != null) {
            mContactLookupUri = mContact.getLookupUri();
        }
        ContactDetailsViewModel contactDetailsViewModel = ViewModelProviders.of(this).get(
                ContactDetailsViewModel.class);
        mContactDetailsLiveData = contactDetailsViewModel.getContactDetails(mContactLookupUri);
        mContactDetailsLiveData.observe(this, this::onContactChanged);
    }

    @Override
    public void onCreateOptionsMenu(Menu menu, MenuInflater menuInflater) {
        menuInflater.inflate(R.menu.contact_edit, menu);
        MenuItem defaultNumberMenuItem = menu.findItem(R.id.menu_contact_default_number);
        ContactDefaultNumberActionProvider contactDefaultNumberActionProvider =
                (ContactDefaultNumberActionProvider) defaultNumberMenuItem.getActionProvider();
        contactDefaultNumberActionProvider.setContact(mContact);
        mContactDetailsLiveData.observe(this, contactDefaultNumberActionProvider::setContact);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        menu.findItem(R.id.menu_contacts_search).setVisible(false);
        menu.findItem(R.id.menu_dialer_setting).setVisible(false);
    }

    @Override
    public void onViewCreated(View view, Bundle savedInstanceState) {
        ContactDetailsAdapter contactDetailsAdapter = new ContactDetailsAdapter(getContext(),
                mContact);
        getRecyclerView().setAdapter(contactDetailsAdapter);
        mContactDetailsLiveData.observe(this, contactDetailsAdapter::setContact);
    }

    private void onContactChanged(Contact contact) {
        getArguments().clear();

        if (mAvatarView != null) {
            mAvatarView.setOutlineProvider(ContactAvatarOutputlineProvider.get());
            TelecomUtils.setContactBitmapAsync(getContext(), mAvatarView, contact, null);
        }

        if (mNameView != null) {
            if (contact != null) {
                mNameView.setText(contact.getDisplayName());
            } else {
                mNameView.setText(R.string.error_contact_deleted);
            }
        }
    }

    @Override
    protected void setupActionBar(@NonNull ActionBar actionBar) {
        actionBar.setCustomView(R.layout.contact_details_action_bar);
        actionBar.setTitle(null);

        // Will set these to null on screen sizes that don't have them in the action bar
        View customView = actionBar.getCustomView();
        mAvatarView = customView.findViewById(R.id.contact_details_action_bar_avatar);
        mNameView = customView.findViewById(R.id.contact_details_action_bar_name);

        // Remove the action bar background on non-short screens
        // On short screens the avatar and name is in the action bar so we keep it
        if (mAvatarView == null) {
            setActionBarBackground(null);
            getRecyclerView().setScrollBarPadding(actionBar.getHeight(), 0);
        } else {
            getRecyclerView().setScrollBarPadding(0, 0);
        }
    }

    @Override
    protected int getTopOffset() {
        if (DialerUtils.isShortScreen(getContext())) {
            return super.getTopOffset();
        } else {
            return 0;
        }
    }

    @Override
    public void onSaveInstanceState(Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(KEY_CONTACT_ENTITY, mContactDetailsLiveData.getValue());
    }
}
