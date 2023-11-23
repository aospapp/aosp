/*
 * Copyright (C) 2023 The Android Open Source Project
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

package com.android.car.portraitlauncher.homescreen.audio;

import android.view.View;

import com.android.car.carlauncher.homescreen.CardPresenter;
import com.android.car.carlauncher.homescreen.HomeCardInterface;
import com.android.car.carlauncher.homescreen.audio.AudioPresenter;
import com.android.car.carlauncher.homescreen.audio.InCallModel;
import com.android.car.media.common.PlaybackControlsActionBar;

import java.util.List;

/**
 * A portrait UI version of {@link HomeAudioCardPresenter}
 */
public class PortraitHomeAudioCardPresenter extends CardPresenter implements AudioPresenter{
    private PortraitMediaViewModel mPortraitMediaViewModel;
    private List<HomeCardInterface.Model> mModelList;
    private HomeCardInterface.Model mCurrentModel;

    @Override
    public void setModels(List<HomeCardInterface.Model> models) {
        mModelList = models;
    }

    protected List<HomeCardInterface.Model> getModels() {
        return mModelList;
    }


    /**
     * Called when the View is created
     */
    @Override
    public void onViewCreated() {
        for (HomeCardInterface.Model model : getModels()) {
            if (model.getClass() == PortraitMediaViewModel.class) {
                mPortraitMediaViewModel = (PortraitMediaViewModel) model;
            }
            model.setPresenter(this);
            model.onCreate(getFragment().requireContext());
        }
    }

    /**
     * Called when the View is destroyed
     */
    @Override
    public void onViewDestroyed() {
        if (mModelList != null) {
            for (HomeCardInterface.Model model : mModelList) {
                model.onDestroy(getFragment().requireContext());
            }
        }
    }

    /**
     * Called when the View is clicked
     */
    @Override
    public void onViewClicked(View v) {
        mCurrentModel.onClick(v);
    }

    @Override
    public void onModelUpdated(HomeCardInterface.Model model) {
        // Null card header indicates the model has no content to display
        if (model.getCardHeader() == null) {
            if (mCurrentModel != null && model.getClass() == mCurrentModel.getClass()) {
                // If the model currently on display is updating to empty content, check if there
                // is media content to display. If there is no media content the super method is
                // called with empty content, which hides the card.
                if (mPortraitMediaViewModel != null
                        && mPortraitMediaViewModel.getCardHeader() != null) {
                    mCurrentModel = mPortraitMediaViewModel;
                    super.onModelUpdated(mPortraitMediaViewModel);
                    return;
                }
            } else {
                // Otherwise, another model is already on display, so don't update with this
                // empty content since that would hide the card.
                return;
            }
        } else if (mCurrentModel != null && mCurrentModel.getClass() == InCallModel.class
                && model.getClass() != InCallModel.class) {
            // If the Model has content, check if currentModel on display is an ongoing phone call.
            // If there is any ongoing phone call, do not update the View
            // if the model trying to update View is NOT a phone call.
            return;
        }
        mCurrentModel = model;
        super.onModelUpdated(model);
    }

    public void initializeControlsActionBar(View actionBar) {
        ((PlaybackControlsActionBar) actionBar).setModel(
                mPortraitMediaViewModel.getPlaybackViewModel(),
                getFragment().getViewLifecycleOwner());
    }
}
