/*
 * Copyright (C) 2022 The Android Open Source Project
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

package com.android.adservices.service.measurement.access;

import static android.adservices.common.AdServicesStatusUtils.STATUS_USER_CONSENT_REVOKED;

import android.adservices.common.AdServicesStatusUtils;
import android.annotation.NonNull;
import android.content.Context;
import android.os.Build;

import androidx.annotation.RequiresApi;

import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.consent.AdServicesApiConsent;
import com.android.adservices.service.consent.AdServicesApiType;
import com.android.adservices.service.consent.ConsentManager;

/** Resolves whether user consent has been provided or not to use the PPAPI. */
// TODO(b/269798827): Enable for R.
@RequiresApi(Build.VERSION_CODES.S)
public class UserConsentAccessResolver implements IAccessResolver {
    private static final String ERROR_MESSAGE = "User has not consented.";
    private final ConsentManager mConsentManager;

    public UserConsentAccessResolver(@NonNull ConsentManager consentManager) {
        mConsentManager = consentManager;
    }

    @Override
    public boolean isAllowed(@NonNull Context context) {
        AdServicesApiConsent userConsent;
        if (FlagsFactory.getFlags().getGaUxFeatureEnabled()) {
            userConsent = mConsentManager.getConsent(AdServicesApiType.MEASUREMENTS);
        } else {
            userConsent = mConsentManager.getConsent();
        }
        return userConsent.isGiven();
    }

    @NonNull
    @Override
    @AdServicesStatusUtils.StatusCode
    public int getErrorStatusCode() {
        return STATUS_USER_CONSENT_REVOKED;
    }

    @NonNull
    @Override
    public String getErrorMessage() {
        return ERROR_MESSAGE;
    }
}
