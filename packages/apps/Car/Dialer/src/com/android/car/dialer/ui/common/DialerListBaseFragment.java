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

package com.android.car.dialer.ui.common;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.DrawableRes;
import androidx.annotation.LayoutRes;
import androidx.annotation.NonNull;
import androidx.annotation.StringRes;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.android.car.dialer.R;
import com.android.car.dialer.widget.LoadingFrameLayout;
import com.android.car.ui.baselayout.Insets;
import com.android.car.ui.recyclerview.CarUiRecyclerView;

/**
 * Base fragment that inflates a {@link RecyclerView}. It handles the top offset for first row item
 * so the list can scroll underneath the top bar.
 */
public class DialerListBaseFragment extends DialerBaseFragment {

    private LoadingFrameLayout mLoadingFrameLayout;
    private CarUiRecyclerView mRecyclerView;

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container,
            Bundle savedInstanceState) {
        View view = inflater.inflate(getLayoutResource(), container, false);
        mLoadingFrameLayout = view.findViewById(R.id.loading_frame_layout);
        mRecyclerView = view.requireViewById(R.id.list_view);
        mRecyclerView.setLayoutManager(createLayoutManager());
        return view;
    }

    /**
     * Layout resource for this fragment. It must contains a RecyclerView with id list_view.
     */
    @LayoutRes
    protected int getLayoutResource() {
        return R.layout.loading_list_fragment;
    }

    /**
     * Creates the layout manager for the recycler view. Default is a {@link LinearLayoutManager}.
     * Child inheriting from this fragment can override to create a different layout manager.
     */
    @NonNull
    protected RecyclerView.LayoutManager createLayoutManager() {
        return new LinearLayoutManager(getContext());
    }

    /**
     * Returns the {@link RecyclerView} instance.
     */
    @NonNull
    protected CarUiRecyclerView getRecyclerView() {
        return mRecyclerView;
    }

    /**
     * Shows loading spinner when the data is still loading.
     */
    protected void showLoading() {
        mLoadingFrameLayout.showLoading();
    }

    /**
     * Shows content when data is loaded and the content is not empty.
     */
    protected void showContent() {
        mLoadingFrameLayout.showContent();
    }

    /**
     * Shows the empty view with icon, message and secondary message.
     */
    protected void showEmpty(@DrawableRes int iconResId, @StringRes int messageResId,
            @StringRes int secondaryMessageResId) {
        mLoadingFrameLayout.showEmpty(iconResId, messageResId, secondaryMessageResId);
    }

    /**
     * Shows the empty view with icon, message, secondary message and action button.
     */
    protected void showEmpty(@DrawableRes int iconResId, @StringRes int messageResId,
            @StringRes int secondaryMessageResId,
            @StringRes int actionButtonTextResId, View.OnClickListener actionButtonOnClickListener,
            boolean showActionButton) {
        mLoadingFrameLayout.showEmpty(iconResId, messageResId, secondaryMessageResId,
                actionButtonTextResId, actionButtonOnClickListener, showActionButton);
    }

    @Override
    public void onCarUiInsetsChanged(Insets insets) {
        int listTopPadding = requireContext().getResources().getDimensionPixelSize(
                R.dimen.list_top_padding);
        mRecyclerView.setPadding(0, insets.getTop() + listTopPadding, 0, insets.getBottom());
        requireView().setPadding(insets.getLeft(), 0,
                insets.getRight(), 0);
    }
}
