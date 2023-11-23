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
package com.android.adservices.service.ui.ux;

import static com.android.adservices.service.PhFlags.KEY_U18_UX_ENABLED;

import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.android.adservices.service.consent.ConsentManager;
import com.android.adservices.service.ui.data.UxStatesManager;
import com.android.adservices.service.ui.enrollment.PrivacySandboxEnrollmentChannel;

/** The privacy sandbox U18 UX. */
@RequiresApi(Build.VERSION_CODES.S)
public class U18Ux implements PrivacySandboxUx {

    /** Whether a user is eligible for the privacy sandbox U18 UX. */
    public boolean isEligible(ConsentManager consentManager, UxStatesManager uxStatesManager) {
        return uxStatesManager.getFlag(KEY_U18_UX_ENABLED) && consentManager.isU18Account();
    }

    /** Enroll user through one of the available U18 UX enrollment channels if needed. */
    public void handleEnrollment(
            PrivacySandboxEnrollmentChannel enrollmentChannel,
            Context context,
            ConsentManager consentManager) {
        enrollmentChannel.enroll(context, consentManager);
    }

    /** Select one of the available U18 UX modes for the user. */
    public void selectMode(
            Context context, ConsentManager consentManager, UxStatesManager uxStatesManager) {
        // TO-DO(b/284175944): Add mode logic.
    }
}
