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

/**
 * This class provides resources and data used for the use case where device subsidy is not
 * required any more.
 */
public final class DeviceSubsidyProvisionNotRequiredViewModel extends ProvisionInfoViewModel {

    private static final int HEADER_DRAWABLE_ID = R.drawable.ic_check_circle_24px;

    private static final int HEADER_TEXT_ID = R.string.device_removed_from_subsidy_program;

    private static final int SUBHEADER_TEXT_ID = R.string.restrictions_lifted;

    DeviceSubsidyProvisionNotRequiredViewModel() {
        super();

        mHeaderDrawableIdLiveData.setValue(HEADER_DRAWABLE_ID);
        mHeaderTextIdLiveData.setValue(HEADER_TEXT_ID);
        mSubheaderTextIdLiveData.setValue(SUBHEADER_TEXT_ID);
    }
}
