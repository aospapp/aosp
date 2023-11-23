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
package com.android.wallpaper.widget;

import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_COLLAPSED;
import static com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import com.android.wallpaper.R;

import com.google.android.material.bottomsheet.BottomSheetBehavior;
import com.google.android.material.bottomsheet.BottomSheetBehavior.BottomSheetCallback;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;

/** A {@code ViewGroup} which provides the specific actions for the user to interact with. */
public class BottomActionBar extends FrameLayout {

    /** The action items in the bottom action bar. */
    public enum BottomAction {
        CANCEL, ROTATION, INFORMATION, EDIT, APPLY,
    }

    private final Map<BottomAction, View> mActionList = new EnumMap<>(BottomAction.class);
    private final BottomSheetBehavior<ViewGroup> mBottomSheetBehavior;
    private final TextView mAttributionTitle;
    private final TextView mAttributionSubtitle1;
    private final TextView mAttributionSubtitle2;

    public BottomActionBar(@NonNull Context context, @Nullable AttributeSet attrs) {
        super(context, attrs);
        LayoutInflater.from(context).inflate(R.layout.bottom_actions_layout, this, true);

        mAttributionTitle = findViewById(R.id.preview_attribution_pane_title);
        mAttributionSubtitle1 = findViewById(R.id.preview_attribution_pane_subtitle1);
        mAttributionSubtitle2 = findViewById(R.id.preview_attribution_pane_subtitle2);

        mActionList.put(BottomAction.CANCEL, findViewById(R.id.action_cancel));
        mActionList.put(BottomAction.ROTATION, findViewById(R.id.action_rotation));
        mActionList.put(BottomAction.INFORMATION, findViewById(R.id.action_information));
        mActionList.put(BottomAction.EDIT, findViewById(R.id.action_edit));
        mActionList.put(BottomAction.APPLY, findViewById(R.id.action_apply));

        ViewGroup bottomSheet = findViewById(R.id.action_bottom_sheet);
        mBottomSheetBehavior = BottomSheetBehavior.from(bottomSheet);
        mBottomSheetBehavior.setState(STATE_COLLAPSED);

        // Workaround as we don't have access to bottomDialogCornerRadius, mBottomSheet radii are
        // set to dialogCornerRadius by default.
        GradientDrawable bottomSheetBackground = (GradientDrawable) bottomSheet.getBackground();
        float[] radii = bottomSheetBackground.getCornerRadii();
        for (int i = 0; i < radii.length; i++) {
            radii[i]*=2f;
        }
        bottomSheetBackground = ((GradientDrawable)bottomSheetBackground.mutate());
        bottomSheetBackground.setCornerRadii(radii);
        bottomSheet.setBackground(bottomSheetBackground);

        ImageView informationIcon = findViewById(R.id.action_information);
        mBottomSheetBehavior.setBottomSheetCallback(new BottomSheetCallback() {
            @Override
            public void onStateChanged(@NonNull View bottomSheet, int newState) {
                if (newState == STATE_COLLAPSED) {
                    informationIcon.setColorFilter(getContext().getColor(R.color.material_grey500));
                } else if (newState == STATE_EXPANDED) {
                    informationIcon.setColorFilter(getContext().getColor(R.color.accent_color));
                }
            }

            @Override
            public void onSlide(@NonNull View bottomSheet, float slideOffset) {}
        });
    }

    @Override
    public void onVisibilityAggregated(boolean isVisible) {
        super.onVisibilityAggregated(isVisible);
        if (!isVisible) {
            mBottomSheetBehavior.setState(STATE_COLLAPSED);
        }
    }

    /**
     * Sets the click listener to the specific action.
     *
     * @param bottomAction the specific action
     * @param actionClickListener the click listener for the action
     */
    public void setActionClickListener(
            BottomAction bottomAction, OnClickListener actionClickListener) {
        mActionList.get(bottomAction).setOnClickListener(actionClickListener);
    }

    /** Clears all the actions' click listeners */
    public void clearActionClickListeners() {
        mActionList.forEach((bottomAction, view) -> view.setOnClickListener(null));
    }

    /**
     * Populates attributions(wallpaper info) to the information page.
     *
     * <p>Once get called, the {@link OnClickListener} to show/hide the information page will be
     * set for the {@code BottomAction.INFORMATION}.
     */
    public void populateInfoPage(List<String> attributions, boolean showMetadata) {
        resetInfoPage();

        // Ensure the ClickListener can work normally if has info been populated, since it could be
        // removed by #clearActionClickListeners.
        setActionClickListener(BottomAction.INFORMATION, unused ->
            mBottomSheetBehavior.setState(mBottomSheetBehavior.getState() == STATE_COLLAPSED
                ? STATE_EXPANDED
                : STATE_COLLAPSED
            )
        );

        if (attributions.size() > 0 && attributions.get(0) != null) {
            mAttributionTitle.setText(attributions.get(0));
        }

        if (showMetadata) {
            if (attributions.size() > 1 && attributions.get(1) != null) {
                mAttributionSubtitle1.setVisibility(View.VISIBLE);
                mAttributionSubtitle1.setText(attributions.get(1));
            }

            if (attributions.size() > 2 && attributions.get(2) != null) {
                mAttributionSubtitle2.setVisibility(View.VISIBLE);
                mAttributionSubtitle2.setText(attributions.get(2));
            }
        }
    }


    /** Shows {@link BottomActionBar}. */
    public void show() {
        setVisibility(VISIBLE);
    }

    /** Hides {@link BottomActionBar}. */
    public void hide() {
        setVisibility(GONE);
    }

    /**
     * Shows the specific actions.
     *
     * @param actions the specific actions
     */
    public void showActions(EnumSet<BottomAction> actions) {
        showActions(actions, true);
    }

    /**
     * Hides the specific actions.
     *
     * @param actions the specific actions
     */
    public void hideActions(EnumSet<BottomAction> actions) {
        showActions(actions, false);
        if (actions.contains(BottomAction.INFORMATION)) {
            mBottomSheetBehavior.setState(STATE_COLLAPSED);
        }
    }

    /**
     * Shows the specific actions only. In other words, the other actions will be hidden.
     *
     * @param actions the specific actions which will be shown. Others will be hidden.
     */
    public void showActionsOnly(EnumSet<BottomAction> actions) {
        showActions(actions);
        hideActions(EnumSet.complementOf(actions));
    }

    /** Enables all the actions' {@link View}. */
    public void enableActions() {
        enableActions(true);
    }

    /** Disables all the actions' {@link View}. */
    public void disableActions() {
        enableActions(false);
    }

    private void enableActions(boolean enable) {
        mActionList.forEach((bottomAction, view) -> view.setEnabled(enable));
    }

    private void showActions(EnumSet<BottomAction> actions, boolean show) {
        actions.forEach(bottomAction ->
                mActionList.get(bottomAction).setVisibility(show ? VISIBLE : GONE));
    }

    private void resetInfoPage() {
        mAttributionTitle.setText(null);

        mAttributionSubtitle1.setText(null);
        mAttributionSubtitle1.setVisibility(GONE);

        mAttributionSubtitle2.setText(null);
        mAttributionSubtitle2.setVisibility(GONE);
    }
}
