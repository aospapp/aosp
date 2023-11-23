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

import android.os.Build;

import androidx.annotation.RequiresApi;

/* Collection of beta UX enrollment channels. */
@RequiresApi(Build.VERSION_CODES.S)
public enum BetaUxEnrollmentChannelCollection implements PrivacySandboxEnrollmentChannelCollection {
    CONSENT_NOTIFICATION_DEBUG_CHANNEL(/* priority= */ 0, new ConsentNotificationDebugChannel()),

    ALREADY_ENROLLED_CHANNEL(/* priority= */ 1, new AlreadyEnrolledChannel()),

    FIRST_CONSENT_NOTIFICATION_CHANNEL(/* priority= */ 2, new FirstConsentNotificationChannel());

    private final int mPriority;

    private final PrivacySandboxEnrollmentChannel mEnrollmentChannel;

    BetaUxEnrollmentChannelCollection(
            int priority, PrivacySandboxEnrollmentChannel enrollmentChannel) {
        mPriority = priority;
        mEnrollmentChannel = enrollmentChannel;
    }

    public int getPriority() {
        return mPriority;
    }

    public PrivacySandboxEnrollmentChannel getEnrollmentChannel() {
        return mEnrollmentChannel;
    }
}
