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

import android.os.SystemClock;
import android.util.Log;

import com.android.car.carlauncher.homescreen.CardPresenter;
import com.android.car.carlauncher.homescreen.HomeCardFragment;
import com.android.car.carlauncher.homescreen.audio.AudioCard;
import com.android.car.carlauncher.homescreen.audio.AudioFragment;
import com.android.car.carlauncher.homescreen.audio.InCallModel;

import java.util.Arrays;
import java.util.Collections;

/**
 * A portrait UI version of {@link AudioCard}
 */
public class PortraitAudioCard extends AudioCard {

    private static final String TAG = "PortraitAudioCard";
    private PortraitHomeAudioCardPresenter mAudioCardPresenter;
    private AudioFragment mAudioCardView;

    @Override
    public CardPresenter getCardPresenter() {
        if (mAudioCardPresenter == null) {
            mAudioCardPresenter = new PortraitHomeAudioCardPresenter();
            if (getViewModelProvider() == null) {
                Log.w(TAG, "No ViewModelProvider set. Cannot get PortraitMediaViewModel");
                mAudioCardPresenter.setModels(Collections.unmodifiableList(
                        Collections.singletonList(
                                new InCallModel(SystemClock.elapsedRealtimeClock()))));
            } else {
                mAudioCardPresenter.setModels(Collections.unmodifiableList(
                        Arrays.asList(getViewModelProvider().get(PortraitMediaViewModel.class),
                                new InCallModel(SystemClock.elapsedRealtimeClock()))));
            }
        }
        return mAudioCardPresenter;
    }

    @Override
    public HomeCardFragment getCardView() {
        if (mAudioCardView == null) {
            mAudioCardView = new AudioFragment();
            getCardPresenter().setView(mAudioCardView);
            mAudioCardView.setPresenter(getCardPresenter());
        }
        return mAudioCardView;
    }
}
