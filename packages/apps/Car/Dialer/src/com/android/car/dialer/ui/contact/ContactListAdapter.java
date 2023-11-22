
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

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.dialer.R;
import com.android.car.telephony.common.Contact;

import java.util.ArrayList;
import java.util.List;

/** Adapter for contact list. */
public class ContactListAdapter extends RecyclerView.Adapter<ContactListViewHolder> {
    private static final String TAG = "CD.ContactListAdapter";

    interface OnShowContactDetailListener {
        void onShowContactDetail(Contact contact);
    }

    private final Context mContext;
    private final List<Contact> mContactList = new ArrayList<>();
    private final OnShowContactDetailListener mOnShowContactDetailListener;

    public ContactListAdapter(Context context,
            OnShowContactDetailListener onShowContactDetailListener) {
        mContext = context;
        mOnShowContactDetailListener = onShowContactDetailListener;
    }

    public void setContactList(List<Contact> contactList) {
        mContactList.clear();
        if (contactList != null) {
            mContactList.addAll(contactList);
        }
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ContactListViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(mContext).inflate(R.layout.contact_list_item, parent,
                false);
        return new ContactListViewHolder(itemView, mOnShowContactDetailListener);
    }

    @Override
    public void onBindViewHolder(@NonNull ContactListViewHolder holder, int position) {
        Contact contact = mContactList.get(position);
        holder.onBind(contact);
    }

    @Override
    public int getItemCount() {
        return  mContactList.size();
    }
}
