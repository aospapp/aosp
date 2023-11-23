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

package com.android.adservices.service.adselection;

import static com.android.adservices.service.adselection.AdSelectionConfigValidator.DECISION_LOGIC_URI_TYPE;
import static com.android.adservices.service.adselection.AdSelectionConfigValidator.TRUSTED_SCORING_SIGNALS_URI_TYPE;
import static com.android.adservices.service.adselection.AdSelectionConfigValidator.URI_IS_NOT_ABSOLUTE;
import static com.android.adservices.service.adselection.AdSelectionConfigValidator.URI_IS_NOT_HTTPS;

import static org.junit.Assert.assertThrows;

import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.AdSelectionConfigFixture;
import android.adservices.adselection.AdWithBid;
import android.adservices.adselection.ContextualAds;
import android.adservices.adselection.ContextualAdsFixture;
import android.adservices.common.AdDataFixture;
import android.adservices.common.AdTechIdentifier;
import android.adservices.common.CommonFixture;
import android.net.Uri;

import com.android.adservices.service.Flags;
import com.android.adservices.service.FlagsFactory;
import com.android.adservices.service.common.AdDataValidator;
import com.android.adservices.service.common.AdTechUriValidator;
import com.android.adservices.service.common.ValidatorTestUtil;
import com.android.adservices.service.common.ValidatorUtil;

import com.google.common.collect.ImmutableList;

import org.junit.Before;
import org.junit.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

public class AdSelectionConfigValidatorTest {
    private static final AdTechIdentifier EMPTY_STRING = AdTechIdentifier.fromString("");
    private static final AdTechIdentifier SELLER_VALID =
            AdTechIdentifier.fromString("developer.android.com");
    private static final AdTechIdentifier SELLER_VALID_WITH_PREFIX =
            AdTechIdentifier.fromString("www.developer.android.com");
    private static final AdTechIdentifier SELLER_NOT_DOMAIN_NAME =
            AdTechIdentifier.fromString("developer.android.com/test");
    private static final AdTechIdentifier SELLER_INVALID =
            AdTechIdentifier.fromString("developer%$android.com");
    private static final AdTechIdentifier SELLER_NO_HOST = AdTechIdentifier.fromString("test@");
    private static final Uri DECISION_LOGIC_URI_CONSISTENT =
            Uri.parse("https://developer.android.com/test/decisions_logic_uris");
    private static final Uri DECISION_LOGIC_URI_CONSISTENT_WITH_PREFIX =
            Uri.parse("https://www.developer.android.com/test/decisions_logic_uris");
    private static final Uri DECISION_LOGIC_URI_NO_HOST = Uri.parse("test/decisions_logic_uris");
    private static final Uri DECISION_LOGIC_URI_INCONSISTENT =
            Uri.parse("https://developer%$android.com/test/decisions_logic_uris");
    private static final Uri TRUSTED_SIGNALS_URI_CONSISTENT =
            Uri.parse("https://developer.android.com/test/trusted_signals_uri");
    private static final Uri TRUSTED_SIGNALS_URI_CONSISTENT_WITH_PREFIX =
            Uri.parse("https://www.developer.android.com/test/trusted_signals_uri");
    private static final Uri TRUSTED_SIGNALS_URI_INCONSISTENT =
            Uri.parse("https://developer.invalid.com/test/trusted_signals_uri");
    private static final String BUYER_BIDDING_LOGIC_URI_PATH = "/buyer/bidding/logic/";
    private static final String AD_SELECTION_VIOLATION_PREFIX =
            String.format(
                    "Invalid object of type %s. The violations are:",
                    AdSelectionConfig.class.getName());
    private final AdSelectionConfig.Builder mAdSelectionConfigBuilder =
            AdSelectionConfigFixture.anAdSelectionConfigBuilder()
                    .setSeller(SELLER_VALID)
                    .setDecisionLogicUri(DECISION_LOGIC_URI_CONSISTENT)
                    .setTrustedScoringSignalsUri(TRUSTED_SIGNALS_URI_CONSISTENT);

    private Flags mFlags;
    private PrebuiltLogicGenerator mPrebuiltLogicGenerator;

    @Before
    public void setup() {
        mFlags = FlagsFactory.getFlagsForTest();
        mPrebuiltLogicGenerator = new PrebuiltLogicGenerator(mFlags);
    }

    private String generateInconsistentSellerAndDecisionLogicUriMessage(
            String uriType, AdTechIdentifier seller, Uri decisionLogicUri) {
        return String.format(
                AdSelectionConfigValidator.SELLER_AND_URI_HOST_ARE_INCONSISTENT,
                Uri.parse("https://" + seller.toString()).getHost(),
                decisionLogicUri.getHost(),
                uriType);
    }

    @Test
    public void testVerifyAdSelectionConfigSuccess() {
        AdSelectionConfig adSelectionConfig = mAdSelectionConfigBuilder.build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        adSelectionConfigValidator.validate(adSelectionConfig);
    }

    @Test
    public void testVerifyAdSelectionConfigSuccessSellerWithPrefix() {
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder
                        .setSeller(SELLER_VALID_WITH_PREFIX)
                        .setDecisionLogicUri(DECISION_LOGIC_URI_CONSISTENT_WITH_PREFIX)
                        .setTrustedScoringSignalsUri(TRUSTED_SIGNALS_URI_CONSISTENT_WITH_PREFIX)
                        .build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        adSelectionConfigValidator.validate(adSelectionConfig);
    }

    @Test
    public void testVerifyEmptySeller() {
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder.setSeller(EMPTY_STRING).build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(AdSelectionConfigValidator.SELLER_SHOULD_NOT_BE_NULL_OR_EMPTY));
    }

    @Test
    public void testVerifyNotDomainNameSeller() {
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder.setSeller(SELLER_NOT_DOMAIN_NAME).build();

        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(AdSelectionConfigValidator.SELLER_IS_AN_INVALID_DOMAIN_NAME));
    }

    @Test
    public void testVerifyInvalidSeller() {
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder.setSeller(SELLER_INVALID).build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(
                        AdSelectionConfigValidator.SELLER_IS_AN_INVALID_DOMAIN_NAME,
                        generateInconsistentSellerAndDecisionLogicUriMessage(
                                DECISION_LOGIC_URI_TYPE,
                                SELLER_INVALID,
                                DECISION_LOGIC_URI_CONSISTENT)));
    }

    @Test
    public void testVerifyNoHostSeller() {
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder.setSeller(SELLER_NO_HOST).build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(AdSelectionConfigValidator.SELLER_HAS_MISSING_DOMAIN_NAME));
    }

    @Test
    public void testVerifyNoHostDecisionLogicUri() {
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder.setDecisionLogicUri(DECISION_LOGIC_URI_NO_HOST).build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(
                        String.format(
                                AdSelectionConfigValidator.URI_SHOULD_HAVE_PRESENT_HOST,
                                DECISION_LOGIC_URI_TYPE)));
    }

    @Test
    public void testVerifyInconsistentSellerUris() {
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder
                        .setDecisionLogicUri(DECISION_LOGIC_URI_INCONSISTENT)
                        .build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(
                        generateInconsistentSellerAndDecisionLogicUriMessage(
                                DECISION_LOGIC_URI_TYPE,
                                SELLER_VALID,
                                DECISION_LOGIC_URI_INCONSISTENT)));
    }

    @Test
    public void testVerifyTrustedScoringSignalsUriIsRelative() {
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder
                        .setTrustedScoringSignalsUri(Uri.parse("/this/is/relative/path"))
                        .build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(
                        String.format(URI_IS_NOT_ABSOLUTE, TRUSTED_SCORING_SIGNALS_URI_TYPE)));
    }

    @Test
    public void testVerifyTrustedScoringSignalsUriIsNotHTTPS() {
        Uri trustedScoringSignal = Uri.parse("http://google.com");
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder.setTrustedScoringSignalsUri(trustedScoringSignal).build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(
                        String.format(
                                URI_IS_NOT_HTTPS,
                                TRUSTED_SCORING_SIGNALS_URI_TYPE,
                                trustedScoringSignal)));
    }

    @Test
    public void testVerifyInconsistentSellerUrisByPrefix() {

        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder
                        .setSeller(SELLER_VALID_WITH_PREFIX)
                        .setDecisionLogicUri(DECISION_LOGIC_URI_CONSISTENT)
                        .setTrustedScoringSignalsUri(TRUSTED_SIGNALS_URI_INCONSISTENT)
                        .build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(
                        generateInconsistentSellerAndDecisionLogicUriMessage(
                                DECISION_LOGIC_URI_TYPE,
                                SELLER_VALID_WITH_PREFIX,
                                DECISION_LOGIC_URI_CONSISTENT),
                        generateInconsistentSellerAndDecisionLogicUriMessage(
                                TRUSTED_SCORING_SIGNALS_URI_TYPE,
                                SELLER_VALID_WITH_PREFIX,
                                TRUSTED_SIGNALS_URI_INCONSISTENT)));
    }

    @Test
    public void testContextualAdsDecisionLogicEtldMismatch() {
        Map<AdTechIdentifier, ContextualAds> buyerContextualAds = new HashMap<>();
        AdTechIdentifier buyer2 = CommonFixture.VALID_BUYER_2;
        ContextualAds contextualAds2 =
                ContextualAdsFixture.generateContextualAds(buyer2, ImmutableList.of(100.0, 200.0))
                        .setDecisionLogicUri(
                                CommonFixture.getUri(
                                        CommonFixture.VALID_BUYER_1, BUYER_BIDDING_LOGIC_URI_PATH))
                        .build();
        buyerContextualAds.put(buyer2, contextualAds2);
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder.setBuyerContextualAds(buyerContextualAds).build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        IllegalArgumentException thrown =
                assertThrows(
                        IllegalArgumentException.class,
                        () -> adSelectionConfigValidator.validate(adSelectionConfig));
        ValidatorTestUtil.assertValidationFailuresMatch(
                thrown,
                AD_SELECTION_VIOLATION_PREFIX,
                ImmutableList.of(
                        AdSelectionConfigValidator.CONTEXTUAL_ADS_DECISION_LOGIC_FIELD_NAME));
    }

    @Test
    public void testContextualAdsRenderUriEtldMismatch() {
        Map<AdTechIdentifier, ContextualAds> buyerContextualAds = new HashMap<>();
        AdTechIdentifier buyer2 = CommonFixture.VALID_BUYER_2;
        ImmutableList<Double> bids = ImmutableList.of(100.0, 200.0);
        ContextualAds contextualAds2 =
                ContextualAdsFixture.generateContextualAds(buyer2, bids)
                        .setDecisionLogicUri(
                                CommonFixture.getUri(buyer2, BUYER_BIDDING_LOGIC_URI_PATH))
                        .setAdsWithBid(
                                bids.stream()
                                        .map(
                                                bid ->
                                                        new AdWithBid(
                                                                AdDataFixture.getValidAdDataByBuyer(
                                                                        CommonFixture.VALID_BUYER_1,
                                                                        bid.intValue()),
                                                                bid))
                                        .collect(Collectors.toList()))
                        .build();
        // Creating ads which have a render Uri with a different buyer
        buyerContextualAds.put(buyer2, contextualAds2);
        AdSelectionConfig adSelectionConfig =
                mAdSelectionConfigBuilder.setBuyerContextualAds(buyerContextualAds).build();
        AdSelectionConfigValidator adSelectionConfigValidator =
                new AdSelectionConfigValidator(mPrebuiltLogicGenerator);
        List<String> violations =
                bids.stream()
                        .map(
                                bid -> {
                                    return String.format(
                                            AdDataValidator.VIOLATION_FORMAT,
                                            new AdWithBid(
                                                            AdDataFixture.getValidAdDataByBuyer(
                                                                    CommonFixture.VALID_BUYER_1,
                                                                    bid.intValue()),
                                                            bid)
                                                    .getAdData(),
                                            String.format(
                                                    AdTechUriValidator
                                                            .IDENTIFIER_AND_URI_ARE_INCONSISTENT,
                                                    ValidatorUtil.AD_TECH_ROLE_BUYER,
                                                    CommonFixture.VALID_BUYER_2,
                                                    ValidatorUtil.AD_TECH_ROLE_BUYER,
                                                    AdDataValidator.RENDER_URI_FIELD_NAME,
                                                    CommonFixture.getUri(
                                                                    CommonFixture.VALID_BUYER_1,
                                                                    BUYER_BIDDING_LOGIC_URI_PATH)
                                                            .getHost()));
                                })
                        .collect(Collectors.toList());
        ValidatorTestUtil.assertViolationContainsOnly(
                adSelectionConfigValidator.getValidationViolations(adSelectionConfig),
                violations.get(0),
                violations.get(1));
    }
}
