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
package com.android.car.messenger.impl.datamodels;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.lifecycle.LiveData;

/**
 * Refresh live data for other live data to force refresh. When a live data listens for updates, it
 * can refresh its data and repost as needed. This typically occurs when the user revisits the
 * activity from the background. The background has a number of states that leads to live datas not
 * being updated. The content observer from the telephony database does not always update when in
 * the background if something changes. Same thing occurs with shared preferences. By checking the
 * data again for any stale data when we resume, we can ensure the latest data is provided.
 */
public class RefreshLiveData extends LiveData<Boolean> {

    @Nullable private static RefreshLiveData sInstance;

    /** Gets the instance of {@link RefreshLiveData} */
    @NonNull
    public static RefreshLiveData getInstance() {
        if (sInstance == null) {
            sInstance = new RefreshLiveData();
        }
        return sInstance;
    }

    /** Posts value to listeners to refresh */
    public void refresh() {
        postValue(true);
    }
}
