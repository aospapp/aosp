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

package com.android.devicelockcontroller.activities;

import com.android.devicelockcontroller.R;

import java.util.ArrayList;
import java.util.List;

/**
 * This class provides resources and data used for the deferred provisioning flow of the device
 * subsidy use case.
 */
public final class DeviceSubsidyDeferredProvisionInfoViewModel extends ProvisionInfoViewModel {

    private static final int HEADER_DRAWABLE_ID = R.drawable.ic_info_24px;

    private static final int HEADER_TEXT_ID = R.string.enroll_your_device_header;

    private static final int SUBHEADER_TEXT_ID = R.string.enroll_your_device_subsidy_subheader;

    private static final Integer[] DRAWABLE_IDS = new Integer[]{
            R.drawable.ic_file_download_24px, R.drawable.ic_lock_outline_24px,
    };

    private static final Integer[] TEXT_IDS = new Integer[]{
            R.string.download_kiosk_app, R.string.restrict_device_if_dont_make_payment,
    };

    public DeviceSubsidyDeferredProvisionInfoViewModel() {
        super();

        mHeaderDrawableIdLiveData.setValue(HEADER_DRAWABLE_ID);
        mHeaderTextIdLiveData.setValue(HEADER_TEXT_ID);
        mSubheaderTextIdLiveData.setValue(SUBHEADER_TEXT_ID);
        List<ProvisionInfo> provisionInfoList = new ArrayList<>();
        for (int i = 0, size = DRAWABLE_IDS.length; i < size; ++i) {
            provisionInfoList.add(new ProvisionInfo(DRAWABLE_IDS[i], TEXT_IDS[i]));
        }
        mProvisionInfoListLiveData.setValue(provisionInfoList);
    }
}
