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

package com.android.adservices.service.customaudience;

import android.adservices.customaudience.TrustedBiddingData;
import android.annotation.NonNull;

import com.android.adservices.service.common.AdTechUriValidator;
import com.android.adservices.service.common.Validator;
import com.android.adservices.service.common.ValidatorUtil;
import com.android.internal.annotations.VisibleForTesting;

import com.google.common.collect.ImmutableCollection;

import java.util.Objects;

/** Validator for a trusted bidding data. */
public class TrustedBiddingDataValidator implements Validator<TrustedBiddingData> {
    @VisibleForTesting
    static final String TRUSTED_BIDDING_DATA_CLASS_NAME = TrustedBiddingData.class.getName();

    public static final String TRUSTED_BIDDING_URI_FIELD_NAME = "trusted bidding URI";

    @NonNull private final AdTechUriValidator mTrustedBiddingUriValidator;

    public TrustedBiddingDataValidator(@NonNull String buyer) {
        Objects.requireNonNull(buyer);

        mTrustedBiddingUriValidator =
                new AdTechUriValidator(
                        ValidatorUtil.AD_TECH_ROLE_BUYER,
                        buyer,
                        TRUSTED_BIDDING_DATA_CLASS_NAME,
                        TRUSTED_BIDDING_URI_FIELD_NAME);
    }

    /**
     * Validate a trusted bidding data, the trusted bidding uri should be in buyer domain and should
     * be a valid uri.
     *
     * @param trustedBiddingData the trusted bidding data to be validated.
     */
    @Override
    public void addValidation(
            @NonNull TrustedBiddingData trustedBiddingData,
            @NonNull ImmutableCollection.Builder<String> violations) {
        Objects.requireNonNull(trustedBiddingData);
        Objects.requireNonNull(violations);

        mTrustedBiddingUriValidator.addValidation(
                trustedBiddingData.getTrustedBiddingUri(), violations);
    }
}
