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

package android.adservices.customaudience;

import android.adservices.common.AdDataFixture;
import android.adservices.common.AdSelectionSignals;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.CommonFixture;
import android.net.Uri;

import java.time.Duration;
import java.time.Instant;

/** Utility class supporting custom audience API unit tests */
public final class CustomAudienceFixture {

    public static final Duration CUSTOM_AUDIENCE_MAX_ACTIVATION_DELAY_IN =
            Duration.ofMillis(
                    CommonFixture.FLAGS_FOR_TEST.getFledgeCustomAudienceMaxActivationDelayInMs());
    public static final Duration CUSTOM_AUDIENCE_MAX_EXPIRE_IN =
            Duration.ofMillis(CommonFixture.FLAGS_FOR_TEST.getFledgeCustomAudienceMaxExpireInMs());
    public static final Duration CUSTOM_AUDIENCE_DEFAULT_EXPIRE_IN =
            Duration.ofMillis(
                    CommonFixture.FLAGS_FOR_TEST.getFledgeCustomAudienceDefaultExpireInMs());
    public static final long DAY_IN_SECONDS = 60 * 60 * 24;

    public static final String VALID_OWNER = CommonFixture.TEST_PACKAGE_NAME;
    public static final String VALID_NAME = "testCustomAudienceName";

    public static final Instant VALID_ACTIVATION_TIME =
            CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI;
    public static final Instant VALID_DELAYED_ACTIVATION_TIME =
            CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI.plus(
                    CUSTOM_AUDIENCE_MAX_ACTIVATION_DELAY_IN.dividedBy(2));
    public static final Instant INVALID_DELAYED_ACTIVATION_TIME =
            CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI.plus(
                    CUSTOM_AUDIENCE_MAX_ACTIVATION_DELAY_IN.multipliedBy(2));

    public static final Instant VALID_EXPIRATION_TIME =
            VALID_ACTIVATION_TIME.plus(CUSTOM_AUDIENCE_DEFAULT_EXPIRE_IN);
    public static final Instant VALID_DELAYED_EXPIRATION_TIME =
            VALID_DELAYED_ACTIVATION_TIME.plusSeconds(DAY_IN_SECONDS);
    public static final Instant INVALID_BEFORE_NOW_EXPIRATION_TIME =
            VALID_ACTIVATION_TIME.minusSeconds(DAY_IN_SECONDS);
    public static final Instant INVALID_NOW_EXPIRATION_TIME =
            CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI.minusSeconds(DAY_IN_SECONDS);
    public static final Instant INVALID_BEFORE_DELAYED_EXPIRATION_TIME =
            VALID_DELAYED_ACTIVATION_TIME.minusSeconds(DAY_IN_SECONDS);
    public static final Instant INVALID_BEYOND_MAX_EXPIRATION_TIME =
            VALID_ACTIVATION_TIME.plus(CUSTOM_AUDIENCE_MAX_EXPIRE_IN.multipliedBy(2));
    public static final Instant VALID_LAST_UPDATE_TIME_24_HRS_BEFORE =
            CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI.minusSeconds(DAY_IN_SECONDS);
    public static final Instant INVALID_LAST_UPDATE_TIME_72_DAYS_BEFORE =
            CommonFixture.FIXED_NOW_TRUNCATED_TO_MILLI.minusSeconds(DAY_IN_SECONDS * 72);

    public static final AdSelectionSignals VALID_USER_BIDDING_SIGNALS =
            AdSelectionSignals.fromString("{'valid': 'yep', 'opaque': 'definitely'}");

    public static Uri getValidDailyUpdateUriByBuyer(AdTechIdentifier buyer) {
        return CommonFixture.getUri(buyer, "/update");
    }

    public static Uri getValidBiddingLogicUriByBuyer(AdTechIdentifier buyer) {
        return CommonFixture.getUri(buyer, "/bidding/logic/here/");
    }

    public static CustomAudience.Builder getValidBuilderForBuyer(AdTechIdentifier buyer) {
        return new CustomAudience.Builder()
                .setBuyer(buyer)
                .setName(CustomAudienceFixture.VALID_NAME)
                .setActivationTime(CustomAudienceFixture.VALID_ACTIVATION_TIME)
                .setExpirationTime(CustomAudienceFixture.VALID_EXPIRATION_TIME)
                .setDailyUpdateUri(CustomAudienceFixture.getValidDailyUpdateUriByBuyer(buyer))
                .setUserBiddingSignals(CustomAudienceFixture.VALID_USER_BIDDING_SIGNALS)
                .setTrustedBiddingData(
                        TrustedBiddingDataFixture.getValidTrustedBiddingDataByBuyer(buyer))
                .setBiddingLogicUri(CustomAudienceFixture.getValidBiddingLogicUriByBuyer(buyer))
                .setAds(AdDataFixture.getValidAdsByBuyer(buyer));
    }

    // TODO(b/266837113) Merge with getValidBuilderForBuyer once filters are unhidden
    public static CustomAudience.Builder getValidBuilderForBuyerFilters(AdTechIdentifier buyer) {
        return new CustomAudience.Builder()
                .setBuyer(buyer)
                .setName(CustomAudienceFixture.VALID_NAME)
                .setActivationTime(CustomAudienceFixture.VALID_ACTIVATION_TIME)
                .setExpirationTime(CustomAudienceFixture.VALID_EXPIRATION_TIME)
                .setDailyUpdateUri(CustomAudienceFixture.getValidDailyUpdateUriByBuyer(buyer))
                .setUserBiddingSignals(CustomAudienceFixture.VALID_USER_BIDDING_SIGNALS)
                .setTrustedBiddingData(
                        TrustedBiddingDataFixture.getValidTrustedBiddingDataByBuyer(buyer))
                .setBiddingLogicUri(CustomAudienceFixture.getValidBiddingLogicUriByBuyer(buyer))
                .setAds(AdDataFixture.getValidFilterAdsByBuyer(buyer));
    }
}
