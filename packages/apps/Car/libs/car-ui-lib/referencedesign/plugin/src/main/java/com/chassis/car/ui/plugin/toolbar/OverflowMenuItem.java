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
package com.chassis.car.ui.plugin.toolbar;

import android.animation.Animator;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewStub;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;

import com.android.car.ui.plugin.oemapis.toolbar.MenuItemOEMV1;

import com.chassis.car.ui.plugin.R;

import java.util.Collections;
import java.util.List;

class OverflowMenuItem {
    private static final int DIALOG_ANIMATION_TIME = 250;

    @NonNull
    private final Context mPluginContext;

    @NonNull
    private List<MenuItemOEMV1> mOverflowMenuItems = Collections.emptyList();

    private MenuItemOEMV1 mMenuItem;

    private LinearLayout mItemsContainer;
    private View mDialogBackground;


    OverflowMenuItem(
            @NonNull Context pluginContext,
            @NonNull ViewStub dialogStub) {
        mPluginContext = pluginContext;

        mMenuItem = MenuItemOEMV1.builder()
                .setTitle(pluginContext.getString(R.string.toolbar_menu_item_overflow_title))
                .setIcon(ContextCompat.getDrawable(
                        pluginContext, R.drawable.toolbar_menu_item_overflow))
                .setVisible(false)
                .setOnClickListener(() -> {
                    if (mItemsContainer == null) {
                        mDialogBackground = dialogStub.inflate();
                        mItemsContainer = mDialogBackground
                                .requireViewById(R.id.toolbar_dialog_linear_layout);
                        mDialogBackground.requireViewById(R.id.toolbar_dialog_dismiss_button)
                                .setOnClickListener(v -> hideDialog());
                        mDialogBackground.requireViewById(R.id.toolbar_dialog_shade)
                                .setOnClickListener(v -> hideDialog());
                    }

                    refreshViews();

                    mDialogBackground.setVisibility(View.VISIBLE);
                    mDialogBackground.setAlpha(0);
                    mDialogBackground.animate()
                            .alpha(1)
                            .setDuration(DIALOG_ANIMATION_TIME)
                            .setListener(null)
                            .start();
                })
                .build();
    }

    private void hideDialog() {
        if (mDialogBackground.getVisibility() == View.GONE) {
            return;
        }
        mDialogBackground.animate()
                .alpha(0)
                .setDuration(DIALOG_ANIMATION_TIME)
                .setListener(
                        new Animator.AnimatorListener() {
                            @Override
                            public void onAnimationStart(Animator animation) {
                            }

                            @Override
                            public void onAnimationEnd(Animator animation) {
                                mDialogBackground.setVisibility(View.GONE);
                            }

                            @Override
                            public void onAnimationCancel(Animator animation) {
                                mDialogBackground.setVisibility(View.GONE);
                            }

                            @Override
                            public void onAnimationRepeat(Animator animation) {
                            }
                        })
                .start();
    }

    private void refreshViews() {
        if (mItemsContainer == null) {
            return;
        }

        // Make mItemsContainer have a child toolbar_overflow_item for each overflow menu item.
        if (mItemsContainer.getChildCount() > mOverflowMenuItems.size()) {
            mItemsContainer.removeViews(mOverflowMenuItems.size(),
                    mItemsContainer.getChildCount() - mOverflowMenuItems.size());
        }
        while (mItemsContainer.getChildCount() < mOverflowMenuItems.size()) {
            LayoutInflater.from(mPluginContext).inflate(
                    R.layout.toolbar_overflow_item, mItemsContainer, true);
        }

        for (int i = 0; i < mOverflowMenuItems.size(); i++) {
            bindItemView(mItemsContainer.getChildAt(i), mOverflowMenuItems.get(i));
        }
    }

    private void bindItemView(View view, MenuItemOEMV1 item) {
        TextView titleView = view.requireViewById(R.id.toolbar_overflow_item_title);
        titleView.setText(item.getTitle());

        Runnable onClickListener = item.getOnClickListener();
        if (onClickListener != null) {
            view.setOnClickListener(v -> {
                hideDialog();
                onClickListener.run();
            });
        } else {
            view.setOnClickListener(null);
            view.setClickable(false);
        }
    }

    public MenuItemOEMV1 getMenuItem() {
        return mMenuItem;
    }

    public void setOverflowMenuItems(List<MenuItemOEMV1> menuItems) {
        mOverflowMenuItems = menuItems;
        mMenuItem = mMenuItem.copy().setVisible(!menuItems.isEmpty()).build();

        if (mDialogBackground != null && mDialogBackground.getVisibility() == View.VISIBLE) {
            refreshViews();
        }
    }
}
