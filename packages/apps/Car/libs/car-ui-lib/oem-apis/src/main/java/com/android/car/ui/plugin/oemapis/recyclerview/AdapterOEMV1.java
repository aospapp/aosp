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
package com.android.car.ui.plugin.oemapis.recyclerview;

import android.view.ViewGroup;

/**
 * See {@link androidx.recyclerview.widget.RecyclerView.Adapter}
 *
 * @param <V> A class that extends ViewHolder that will be used by the adapter.
 */
public interface AdapterOEMV1<V extends ViewHolderOEMV1> {

    int ALLOW = 0;
    int PREVENT_WHEN_EMPTY = 1;
    int PREVENT = 2;

    /**
     * A value to pass to {@link #setMaxItems(int)} that indicates there should be no limit.
     */
    int UNLIMITED = -1;

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#getItemCount()}
     */
    int getItemCount();

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#getItemId(int)}
     */
    long getItemId(int position);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#getItemViewType(int)}
     */
    int getItemViewType(int position);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#getStateRestorationPolicy()}
     */
    int getStateRestorationPolicyInt();

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#onAttachedToRecyclerView}
     */
    void onAttachedToRecyclerView(RecyclerViewOEMV1 recyclerView);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#bindViewHolder}
     */
    void bindViewHolder(V holder, int position);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#createViewHolder}
     */
    V createViewHolder(ViewGroup parent, int viewType);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#onDetachedFromRecyclerView}
     */
    void onDetachedFromRecyclerView(RecyclerViewOEMV1 recyclerView);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#onFailedToRecycleView}
     */
    boolean onFailedToRecycleView(V holder);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#onViewAttachedToWindow}
     */
    void onViewAttachedToWindow(V holder);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#onViewDetachedFromWindow}
     */
    void onViewDetachedFromWindow(V holder);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#onViewRecycled}
     */
    void onViewRecycled(V holder);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#registerAdapterDataObserver}
     */
    void registerAdapterDataObserver(AdapterDataObserverOEMV1 observer);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#unregisterAdapterDataObserver}
     */
    void unregisterAdapterDataObserver(AdapterDataObserverOEMV1 observer);

    /**
     * See {@link androidx.recyclerview.widget.RecyclerView.Adapter#hasStableIds}
     */
    boolean hasStableIds();

    /**
     * Sets the maximum number of items available in the adapter. A value less than '0' means
     * the list should not be capped.
     */
    void setMaxItems(int maxItems);
}
