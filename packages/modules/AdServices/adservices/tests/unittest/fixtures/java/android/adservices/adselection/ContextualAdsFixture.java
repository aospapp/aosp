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

package android.adservices.adselection;

import android.adservices.common.AdData;
import android.adservices.common.AdDataFixture;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.CommonFixture;
import android.net.Uri;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.ImmutableMap;

import java.util.List;
import java.util.stream.Collectors;

/**
 * This is a static class meant to help with tests that involve creating an {@link ContextualAds}.
 */
public class ContextualAdsFixture {

    public static final AdTechIdentifier BUYER = CommonFixture.VALID_BUYER_1;
    public static final AdTechIdentifier BUYER_2 = CommonFixture.VALID_BUYER_2;

    // Uri Constants
    public static final String DECISION_LOGIC_FRAGMENT = "/decisionFragment";

    public static final Uri DECISION_LOGIC_URI =
            CommonFixture.getUri(BUYER, DECISION_LOGIC_FRAGMENT);

    private static final AdData VALID_AD_DATA = AdDataFixture.getValidAdDataByBuyer(BUYER, 0);
    private static final double TEST_BID = 0.1;

    public static final AdWithBid AD_WITH_BID_1 = new AdWithBid(VALID_AD_DATA, TEST_BID);
    public static final AdWithBid AD_WITH_BID_2 = new AdWithBid(VALID_AD_DATA, TEST_BID * 2);
    public static final List<AdWithBid> ADS_WITH_BID =
            ImmutableList.of(AD_WITH_BID_1, AD_WITH_BID_2);

    public static ContextualAds.Builder aContextualAdBuilder() {
        return new ContextualAds.Builder()
                .setBuyer(BUYER)
                .setDecisionLogicUri(DECISION_LOGIC_URI)
                .setAdsWithBid(ADS_WITH_BID);
    }

    public static ContextualAds.Builder generateContextualAds(
            AdTechIdentifier buyer, List<Double> bids) {
        return new ContextualAds.Builder()
                .setBuyer(buyer)
                .setDecisionLogicUri(CommonFixture.getUri(buyer, DECISION_LOGIC_FRAGMENT))
                .setAdsWithBid(
                        bids.stream()
                                .map(
                                        bid ->
                                                new AdWithBid(
                                                        AdDataFixture.getValidAdDataByBuyer(
                                                                buyer, bid.intValue()),
                                                        bid))
                                .collect(Collectors.toList()));
    }

    public static ContextualAds aContextualAd() {
        return aContextualAdBuilder().build();
    }

    public static ContextualAds aContextualAd(AdTechIdentifier buyer) {
        return aContextualAdBuilder().setBuyer(buyer).build();
    }

    public static ImmutableMap<AdTechIdentifier, ContextualAds> getBuyerContextualAdsMap() {
        return ImmutableMap.of(
                CommonFixture.VALID_BUYER_1,
                aContextualAd(CommonFixture.VALID_BUYER_1),
                CommonFixture.VALID_BUYER_2,
                aContextualAd(CommonFixture.VALID_BUYER_2));
    }
}
