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

import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.AdWithBid;
import android.adservices.adselection.ContextualAds;
import android.adservices.common.AdTechIdentifier;
import android.annotation.NonNull;
import android.annotation.Nullable;
import android.net.Uri;

import com.android.adservices.LoggerFactory;
import com.android.adservices.service.common.AdDataValidator;
import com.android.adservices.service.common.AdTechUriValidator;
import com.android.adservices.service.common.Validator;
import com.android.adservices.service.common.ValidatorUtil;
import com.android.internal.annotations.VisibleForTesting;

import com.google.common.collect.ImmutableCollection;
import com.google.common.collect.ImmutableList;
import com.google.common.net.InternetDomainName;

import java.util.Map;
import java.util.Objects;

/** This class runs the validation of the {@link AdSelectionConfig} subfields. */
public class AdSelectionConfigValidator implements Validator<AdSelectionConfig> {
    private static final LoggerFactory.Logger sLogger = LoggerFactory.getFledgeLogger();

    @VisibleForTesting
    static final String SELLER_SHOULD_NOT_BE_NULL_OR_EMPTY =
            "The AdSelectionConfig's seller should not be null nor empty.";

    @VisibleForTesting
    static final String SELLER_HAS_MISSING_DOMAIN_NAME =
            "The AdSelectionConfig seller has missing domain name.";

    @VisibleForTesting
    static final String SELLER_IS_AN_INVALID_DOMAIN_NAME =
            "The AdSelectionConfig seller is an invalid domain name.";

    @VisibleForTesting
    static final String TRUSTED_SCORING_SIGNALS_URI_TYPE = "Trusted Scoring Signal";

    @VisibleForTesting static final String DECISION_LOGIC_URI_TYPE = "Decision Logic";

    @VisibleForTesting
    static final String URI_SHOULD_HAVE_PRESENT_HOST =
            "The AdSelectionConfig %s URI should have a valid host.";

    @VisibleForTesting
    static final String SELLER_AND_URI_HOST_ARE_INCONSISTENT =
            "The seller hostname \"%s\" and the seller-provided "
                    + "hostname \"%s\" are not "
                    + "consistent in \"%s\" URI.";

    @VisibleForTesting
    static final String URI_IS_NOT_ABSOLUTE = "The AdSelection %s URI should be absolute";

    @VisibleForTesting
    static final String URI_IS_NOT_HTTPS = "The AdSelection %s URI is not secured by https: '%s'";

    @VisibleForTesting
    static final String CONTEXTUAL_ADS_DECISION_LOGIC_FIELD_NAME =
            "Contextual ads decision logic uri";

    private static final String HTTPS_SCHEME = "https";

    @NonNull private final PrebuiltLogicGenerator mPrebuiltLogicGenerator;

    public AdSelectionConfigValidator(@NonNull PrebuiltLogicGenerator prebuiltLogicGenerator) {
        Objects.requireNonNull(prebuiltLogicGenerator);

        mPrebuiltLogicGenerator = prebuiltLogicGenerator;
    }

    @Override
    public void addValidation(
            @NonNull AdSelectionConfig adSelectionConfig,
            @NonNull ImmutableCollection.Builder<String> violations) {
        if (Objects.isNull(adSelectionConfig)) {
            violations.add("The adSelectionConfig should not be null.");
        }
        violations.addAll(validateSeller(adSelectionConfig.getSeller()));
        if (mPrebuiltLogicGenerator.isPrebuiltUri(adSelectionConfig.getDecisionLogicUri())) {
            sLogger.v("Decision logic uri validation is skipped bc prebuilt uri is detected!");
        } else {
            sLogger.v("Validating decision logic URI");
            violations.addAll(
                    validateSellerDecisionUris(
                            adSelectionConfig.getSeller(),
                            adSelectionConfig.getDecisionLogicUri()));
        }
        if (!adSelectionConfig.getTrustedScoringSignalsUri().equals(Uri.EMPTY)) {
            violations.addAll(
                    validateTrustedSignalsUri(
                            adSelectionConfig.getSeller(),
                            adSelectionConfig.getTrustedScoringSignalsUri()));
        }
        violations.addAll(validateContextualAds(adSelectionConfig.getBuyerContextualAds()));
    }

    /**
     * Validate the seller and seller-provided decision_logic_uri in the {@link AdSelectionConfig}.
     *
     * <p>TODO(b/238849930) Replace seller validation with validation in AdTechIdentifier
     *
     * @param seller is the string name of the ssp.
     * @param decisionLogicUri is the seller provided decision logic uri.
     * @return a list of strings of messages from each violation.
     */
    private ImmutableList<String> validateSellerDecisionUris(
            @NonNull AdTechIdentifier seller, @NonNull Uri decisionLogicUri) {
        return validateUriAndSellerHost(DECISION_LOGIC_URI_TYPE, decisionLogicUri, seller);
    }

    /**
     * Validate the seller and seller-provided decision_logic_uri in the {@link AdSelectionConfig}.
     *
     * @param seller is the string name of the ssp.
     * @param trustedSignalsUri is the seller provided URI to fetch trusted scoring signals.
     * @return a list of strings of messages from each violation.
     */
    private ImmutableList<String> validateTrustedSignalsUri(
            @NonNull AdTechIdentifier seller, @NonNull Uri trustedSignalsUri) {
        return validateUriAndSellerHost(
                TRUSTED_SCORING_SIGNALS_URI_TYPE, trustedSignalsUri, seller);
    }

    // TODO(b/238658332) fold this validation into the AdTechIdentifier class
    private ImmutableList<String> validateSeller(@NonNull AdTechIdentifier sellerId) {
        String seller = sellerId.toString();
        ImmutableList.Builder<String> violations = new ImmutableList.Builder<>();
        String sellerHost = Uri.parse("https://" + seller).getHost();
        if (isStringNullOrEmpty(seller)) {
            violations.add(SELLER_SHOULD_NOT_BE_NULL_OR_EMPTY);
        } else if (Objects.isNull(sellerHost) || sellerHost.isEmpty()) {
            violations.add(SELLER_HAS_MISSING_DOMAIN_NAME);
        } else if (!Objects.equals(sellerHost, seller) || !InternetDomainName.isValid(seller)) {
            violations.add(SELLER_IS_AN_INVALID_DOMAIN_NAME);
        }

        return violations.build();
    }

    private ImmutableList<String> validateUriAndSellerHost(
            @NonNull String uriType, @NonNull Uri uri, @NonNull AdTechIdentifier sellerId) {
        String seller = sellerId.toString();
        ImmutableList.Builder<String> violations = new ImmutableList.Builder<>();
        if (!uri.isAbsolute()) {
            violations.add(String.format(URI_IS_NOT_ABSOLUTE, uriType));
        } else if (!uri.getScheme().equals(HTTPS_SCHEME)) {
            violations.add(String.format(URI_IS_NOT_HTTPS, uriType, uri));
        }

        String sellerHost = Uri.parse("https://" + seller).getHost();
        String uriHost = uri.getHost();
        if (isStringNullOrEmpty(uriHost)) {
            violations.add(String.format(URI_SHOULD_HAVE_PRESENT_HOST, uriType));
        } else if (!seller.isEmpty()
                && !Objects.isNull(sellerHost)
                && !sellerHost.isEmpty()
                && !uriHost.equalsIgnoreCase(sellerHost)) {
            violations.add(
                    String.format(
                            SELLER_AND_URI_HOST_ARE_INCONSISTENT, sellerHost, uriHost, uriType));
        }
        return violations.build();
    }

    private boolean isStringNullOrEmpty(@Nullable String str) {
        return Objects.isNull(str) || str.isEmpty();
    }

    private ImmutableList<String> validateContextualAds(
            Map<AdTechIdentifier, ContextualAds> contextualAdsMap) {
        ImmutableList.Builder<String> violations = new ImmutableList.Builder<>();

        for (Map.Entry<AdTechIdentifier, ContextualAds> entry : contextualAdsMap.entrySet()) {

            // Validate that the buyer decision logic for Contextual Ads satisfies buyer ETLd+1
            AdTechUriValidator buyerUriValidator =
                    new AdTechUriValidator(
                            ValidatorUtil.AD_TECH_ROLE_BUYER,
                            entry.getValue().getBuyer().toString(),
                            ContextualAds.class.getName(),
                            CONTEXTUAL_ADS_DECISION_LOGIC_FIELD_NAME);
            buyerUriValidator.addValidation(entry.getValue().getDecisionLogicUri(), violations);

            // Validate that the ad render uri for Contextual Ads satisfies buyer ETLd+1
            AdDataValidator adDataValidator =
                    new AdDataValidator(
                            ValidatorUtil.AD_TECH_ROLE_BUYER,
                            entry.getValue().getBuyer().toString());
            for (AdWithBid ad : entry.getValue().getAdsWithBid()) {
                adDataValidator.addValidation(ad.getAdData(), violations);
            }
        }
        return violations.build();
    }
}
