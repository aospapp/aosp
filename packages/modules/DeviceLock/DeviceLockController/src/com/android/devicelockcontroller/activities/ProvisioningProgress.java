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
 * Different stages of the provisioning progress.
 */
public final class ProvisioningProgress {

    public static final ProvisioningProgress GETTING_DEVICE_READY = new ProvisioningProgress(
            R.drawable.ic_smartphone_24px, R.string.getting_device_ready,
            R.string.this_may_take_a_few_minutes);
    public static final ProvisioningProgress INSTALLING_KIOSK_APP = new ProvisioningProgress(
            R.drawable.ic_downloading_24px, R.string.installing_kiosk_app);
    public static final ProvisioningProgress OPENING_KIOSK_APP = new ProvisioningProgress(
            R.drawable.ic_open_in_new_24px, R.string.opening_kiosk_app);

    final int mIconId;
    final int mHeaderId;
    final int mSubheaderId;

    ProvisioningProgress(int iconId, int headerId) {
        this(iconId, headerId, 0);
    }

    ProvisioningProgress(int iconId, int headerId, int subheaderId) {
        this.mHeaderId = headerId;
        this.mIconId = iconId;
        this.mSubheaderId = subheaderId;
    }

}
