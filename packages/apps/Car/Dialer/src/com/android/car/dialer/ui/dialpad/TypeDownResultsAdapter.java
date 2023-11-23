/*
 * Copyright (C) 2020 The Android Open Source Project
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

package com.android.car.dialer.ui.dialpad;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import com.android.car.dialer.R;
import com.android.car.dialer.ui.search.ContactResultViewHolder;
import com.android.car.dialer.ui.search.ContactResultsAdapter;

/**
 * An adapter used for type down functionality.
 */
public class TypeDownResultsAdapter extends ContactResultsAdapter {

    public TypeDownResultsAdapter() {
        super(null);
    }

    @Override
    public ContactResultViewHolder onCreateViewHolder(ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.type_down_list_item, parent, false);
        return new ContactResultViewHolder(view, null);
    }

    @Override
    public void onBindViewHolder(ContactResultViewHolder holder, int position) {
        holder.bindTypeDownResult(getContactResults().get(position));
    }
}
