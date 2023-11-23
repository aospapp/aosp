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
 * This class provides data about the device enrollment in the device financing use case.
 */
public final class DeviceFinancingEnrollmentInfoViewModel extends DeviceEnrollmentInfoViewModel {

    private static final int HEADER_DRAWABLE_ID = R.drawable.ic_calendar_today_24px;

    private static final int HEADER_TEXT_ID = R.string.device_enrollment_header_text;

    private static final int BODY_TEXT_ID = R.string.device_financing_enrollment_body_text;

    DeviceFinancingEnrollmentInfoViewModel() {
        super();
        mHeaderDrawableIdLiveData.setValue(HEADER_DRAWABLE_ID);
        mHeaderTextIdLiveData.setValue(HEADER_TEXT_ID);
        mBodyTextIdLiveData.setValue(BODY_TEXT_ID);
    }
}
