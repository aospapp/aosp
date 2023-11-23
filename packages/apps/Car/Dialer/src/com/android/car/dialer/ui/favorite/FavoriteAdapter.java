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

package com.android.car.dialer.ui.favorite;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Toast;

import androidx.recyclerview.widget.RecyclerView;

import com.android.car.dialer.R;
import com.android.car.dialer.log.L;
import com.android.car.dialer.ui.common.OnItemClickedListener;
import com.android.car.telephony.common.Contact;

import java.util.Collections;
import java.util.List;

/**
 * Adapter class for binding favorite contacts.
 */
public class FavoriteAdapter extends RecyclerView.Adapter<FavoriteContactViewHolder> {
    private static final String TAG = "CD.FavoriteAdapter";

    private List<Contact> mFavoriteContacts = Collections.emptyList();
    private OnItemClickedListener<Contact> mListener;

    /** Sets the favorite contact list. */
    public void setFavoriteContacts(List<Contact> favoriteContacts) {
        L.d(TAG, "setFavoriteContacts %s", favoriteContacts);
        mFavoriteContacts = (favoriteContacts != null) ? favoriteContacts : Collections.emptyList();
        notifyDataSetChanged();
    }

    @Override
    public int getItemCount() {
        return mFavoriteContacts.size() + 1; // +1 for the "Add a favorite" button
    }

    @Override
    public FavoriteContactViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.favorite_contact_list_item, parent, false);

        return new FavoriteContactViewHolder(view);
    }

    @Override
    public void onBindViewHolder(FavoriteContactViewHolder viewHolder, int position) {
        Context context = viewHolder.itemView.getContext();

        if (position >= mFavoriteContacts.size()) {
            viewHolder.onBindAddFavorite(context);
            viewHolder.itemView.setOnClickListener((v) ->
                    Toast.makeText(context, "Not yet implemented", Toast.LENGTH_LONG).show());
        } else {
            Contact contact = mFavoriteContacts.get(position);
            viewHolder.onBind(context, contact);
            viewHolder.itemView.setOnClickListener((v) -> onItemViewClicked(contact));
        }
    }

    private void onItemViewClicked(Contact contact) {
        if (mListener != null) {
            mListener.onItemClicked(contact);
        }
    }

    /**
     * Sets a {@link OnItemClickedListener listener} which will be called when an item is clicked.
     */
    public void setOnListItemClickedListener(OnItemClickedListener<Contact> listener) {
        mListener = listener;
    }
}
