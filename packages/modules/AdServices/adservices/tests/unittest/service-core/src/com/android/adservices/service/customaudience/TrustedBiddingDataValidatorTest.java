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

import android.adservices.common.AdTechIdentifier;
import android.adservices.common.CommonFixture;
import android.adservices.customaudience.TrustedBiddingDataFixture;

import com.android.adservices.service.common.AdTechUriValidator;
import com.android.adservices.service.common.ValidatorTestUtil;
import com.android.adservices.service.common.ValidatorUtil;

import org.junit.Assert;
import org.junit.Test;

public class TrustedBiddingDataValidatorTest {
    private final TrustedBiddingDataValidator mValidator =
            new TrustedBiddingDataValidator(CommonFixture.VALID_BUYER_1.toString());

    @Test
    public void testValidTrustedBiddingData() {
        Assert.assertTrue(
                mValidator
                        .getValidationViolations(
                                TrustedBiddingDataFixture.getValidTrustedBiddingDataByBuyer(
                                        CommonFixture.VALID_BUYER_1))
                        .isEmpty());
    }

    @Test
    public void testInvalidUri() {
        AdTechIdentifier buyer = AdTechIdentifier.fromString("b.com");
        ValidatorTestUtil.assertViolationContainsOnly(
                mValidator.getValidationViolations(
                        TrustedBiddingDataFixture.getValidTrustedBiddingDataByBuyer(buyer)),
                String.format(
                        AdTechUriValidator.IDENTIFIER_AND_URI_ARE_INCONSISTENT,
                        ValidatorUtil.AD_TECH_ROLE_BUYER,
                        CommonFixture.VALID_BUYER_1,
                        ValidatorUtil.AD_TECH_ROLE_BUYER,
                        TrustedBiddingDataValidator.TRUSTED_BIDDING_URI_FIELD_NAME,
                        buyer));
    }
}
