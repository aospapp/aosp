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
package com.android.car.ui.recyclerview;

import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.ui.plugin.oemapis.recyclerview.AdapterOEMV1;
import com.android.car.ui.plugin.oemapis.recyclerview.ViewHolderOEMV1;

/**
 * Wrapper class that passes the data to car-ui via AdapterOEMV1 interface
 */
public final class CarUiListItemAdapterAdapterV1 extends
        RecyclerView.Adapter<CarUiListItemAdapterAdapterV1.ViewHolderWrapper> {

    @NonNull
    private AdapterOEMV1 mAdapter;

    public CarUiListItemAdapterAdapterV1(@NonNull AdapterOEMV1 adapter) {
        this.mAdapter = adapter;
        CarUiListItemAdapterAdapterV1.super.setHasStableIds(adapter.hasStableIds());
    }

    @Override
    public int getItemCount() {
        return mAdapter.getItemCount();
    }

    @Override
    public long getItemId(int position) {
        return mAdapter.getItemId(position);
    }

    @Override
    public int getItemViewType(int position) {
        return mAdapter.getItemViewType(position);
    }

    @Override
    public void onBindViewHolder(ViewHolderWrapper holder, int position) {
        mAdapter.bindViewHolder(holder.getViewHolder(), position);
    }

    @Override
    public ViewHolderWrapper onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        return new ViewHolderWrapper(mAdapter.createViewHolder(parent, viewType));
    }

    @Override
    public boolean onFailedToRecycleView(ViewHolderWrapper holder) {
        return mAdapter.onFailedToRecycleView(holder.getViewHolder());
    }

    @Override
    public void onViewAttachedToWindow(ViewHolderWrapper holder) {
        mAdapter.onViewAttachedToWindow(holder.getViewHolder());
    }

    @Override
    public void onViewDetachedFromWindow(ViewHolderWrapper holder) {
        mAdapter.onViewDetachedFromWindow(holder.getViewHolder());
    }

    @Override
    public void onViewRecycled(ViewHolderWrapper holder) {
        mAdapter.onViewRecycled(holder.getViewHolder());
    }

    @Override
    public void onAttachedToRecyclerView(RecyclerView recyclerView) {
        // TODO: can we return something other than null here?
        mAdapter.onAttachedToRecyclerView(null);
    }

    @Override
    public void onDetachedFromRecyclerView(RecyclerView recyclerView) {
        // TODO: can we return something other than null here?
        mAdapter.onDetachedFromRecyclerView(null);
    }

    /**
     * Holds views for each element in the list.
     */
    public static class ViewHolderWrapper extends RecyclerView.ViewHolder {
        @NonNull
        private final ViewHolderOEMV1 mViewHolder;

        ViewHolderWrapper(@NonNull ViewHolderOEMV1 viewHolder) {
            super(viewHolder.getItemView());
            mViewHolder = viewHolder;
            setIsRecyclable(viewHolder.isRecyclable());
        }

        @NonNull
        public ViewHolderOEMV1 getViewHolder() {
            return mViewHolder;
        }
    }
}
