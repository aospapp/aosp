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

package com.android.adservices.service.ui.enrollment;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.ui.data.UxStatesManager;
import com.android.adservices.service.ui.ux.PrivacySandboxUxCollection;

/** Enrollment channel for checking if user has already enrolled in an UX. */
@RequiresApi(Build.VERSION_CODES.S)
public class AlreadyEnrolledChannel implements PrivacySandboxEnrollmentChannel {

    /** Determines if user has already enrolled in a particular UX. */
    public boolean isEligible(
            PrivacySandboxUxCollection uxCollection,
            ConsentManager consentManager,
            UxStatesManager uxStatesManager) {
        switch (uxCollection) {
            case GA_UX:
                return consentManager.wasGaUxNotificationDisplayed();
            case BETA_UX:
                return consentManager.wasNotificationDisplayed();
            case U18_UX:
                return consentManager.wasU18NotificationDisplayed();
            default:
                // Unsupport and non-valid UXs can never have enrollment channels.
                return false;
        }
    }

    /** No-Op if the user has already enrolled. */
    public void enroll(Context context, ConsentManager consentManager) {}
}
