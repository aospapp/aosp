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

import android.app.Application;
import androidx.lifecycle.AndroidViewModel;
import androidx.lifecycle.LiveData;
import com.android.car.dialer.livedata.FavoriteContactLiveData;
import com.android.car.telephony.common.Contact;

import java.util.List;

/**
 * View model for {@link FavoriteFragment}.
 */
public class FavoriteViewModel extends AndroidViewModel {
    private LiveData<List<Contact>> mFavoriteContactsLiveData;

    public FavoriteViewModel(Application application) {
        super(application);
        mFavoriteContactsLiveData = FavoriteContactLiveData.newInstance(application);
    }

    /** Returns favorite contact list live data. */
    public LiveData<List<Contact>> getFavoriteContacts() {
        return mFavoriteContactsLiveData;
    }
}
