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

package android.adservices.cts;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.adservices.adselection.AdSelectionConfig;
import android.adservices.adselection.AdSelectionConfigFixture;
import android.adservices.common.CommonFixture;
import android.net.Uri;
import android.os.Parcel;

import org.junit.Test;

public class AdSelectionConfigTest {
    @Test
    public void testBuildValidAdSelectionConfigSuccess() {
        AdSelectionConfig config =
                new AdSelectionConfig.Builder()
                        .setSeller(AdSelectionConfigFixture.SELLER)
                        .setDecisionLogicUri(AdSelectionConfigFixture.DECISION_LOGIC_URI)
                        .setCustomAudienceBuyers(AdSelectionConfigFixture.CUSTOM_AUDIENCE_BUYERS)
                        .setAdSelectionSignals(AdSelectionConfigFixture.AD_SELECTION_SIGNALS)
                        .setSellerSignals(AdSelectionConfigFixture.SELLER_SIGNALS)
                        .setPerBuyerSignals(AdSelectionConfigFixture.PER_BUYER_SIGNALS)
                        .setTrustedScoringSignalsUri(
                                AdSelectionConfigFixture.TRUSTED_SCORING_SIGNALS_URI)
                        .build();

        assertEquals(config.getSeller(), AdSelectionConfigFixture.SELLER);
        assertEquals(config.getDecisionLogicUri(), AdSelectionConfigFixture.DECISION_LOGIC_URI);
        assertEquals(
                config.getCustomAudienceBuyers(), AdSelectionConfigFixture.CUSTOM_AUDIENCE_BUYERS);
        assertEquals(config.getAdSelectionSignals(), AdSelectionConfigFixture.AD_SELECTION_SIGNALS);
        assertEquals(config.getSellerSignals(), AdSelectionConfigFixture.SELLER_SIGNALS);
        assertEquals(config.getPerBuyerSignals(), AdSelectionConfigFixture.PER_BUYER_SIGNALS);
        assertEquals(
                config.getTrustedScoringSignalsUri(),
                AdSelectionConfigFixture.TRUSTED_SCORING_SIGNALS_URI);
    }

    @Test
    public void testParcelValidAdDataSuccess() {
        AdSelectionConfig config = AdSelectionConfigFixture.anAdSelectionConfig();

        Parcel p = Parcel.obtain();
        config.writeToParcel(p, 0);
        p.setDataPosition(0);
        AdSelectionConfig fromParcel = AdSelectionConfig.CREATOR.createFromParcel(p);

        assertEquals(config.getSeller(), fromParcel.getSeller());
        assertEquals(config.getDecisionLogicUri(), fromParcel.getDecisionLogicUri());
        assertEquals(config.getCustomAudienceBuyers(), fromParcel.getCustomAudienceBuyers());
        assertEquals(config.getAdSelectionSignals(), fromParcel.getAdSelectionSignals());
        assertEquals(config.getSellerSignals(), fromParcel.getSellerSignals());
        assertEquals(config.getPerBuyerSignals(), fromParcel.getPerBuyerSignals());
        assertEquals(
                config.getTrustedScoringSignalsUri(), fromParcel.getTrustedScoringSignalsUri());
    }

    @Test
    public void testBuildMinimalAdSelectionConfigWithDefaultsSuccess() {
        AdSelectionConfig config =
                new AdSelectionConfig.Builder()
                        .setSeller(AdSelectionConfigFixture.SELLER)
                        .setDecisionLogicUri(AdSelectionConfigFixture.DECISION_LOGIC_URI)
                        .setCustomAudienceBuyers(AdSelectionConfigFixture.CUSTOM_AUDIENCE_BUYERS)
                        .setTrustedScoringSignalsUri(
                                AdSelectionConfigFixture.TRUSTED_SCORING_SIGNALS_URI)
                        .build();

        assertEquals(config.getSeller(), AdSelectionConfigFixture.SELLER);
        assertEquals(config.getDecisionLogicUri(), AdSelectionConfigFixture.DECISION_LOGIC_URI);
        assertEquals(
                config.getCustomAudienceBuyers(), AdSelectionConfigFixture.CUSTOM_AUDIENCE_BUYERS);
        assertEquals(
                config.getTrustedScoringSignalsUri(),
                AdSelectionConfigFixture.TRUSTED_SCORING_SIGNALS_URI);

        // Populated by default with empty signals, map, and list
        assertEquals(config.getAdSelectionSignals(), AdSelectionConfigFixture.EMPTY_SIGNALS);
        assertEquals(config.getSellerSignals(), AdSelectionConfigFixture.EMPTY_SIGNALS);
        assertTrue(config.getPerBuyerSignals().isEmpty());
    }

    @Test
    public void testBuildAdSelectionConfigUnsetSellerFailure() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new AdSelectionConfig.Builder()
                            .setDecisionLogicUri(AdSelectionConfigFixture.DECISION_LOGIC_URI)
                            .setCustomAudienceBuyers(
                                    AdSelectionConfigFixture.CUSTOM_AUDIENCE_BUYERS)
                            .build();
                });
    }

    @Test
    public void testBuildValidAdSelectionConfigCloneSuccess() {
        AdSelectionConfig config =
                new AdSelectionConfig.Builder()
                        .setSeller(AdSelectionConfigFixture.SELLER)
                        .setDecisionLogicUri(AdSelectionConfigFixture.DECISION_LOGIC_URI)
                        .setCustomAudienceBuyers(AdSelectionConfigFixture.CUSTOM_AUDIENCE_BUYERS)
                        .setAdSelectionSignals(AdSelectionConfigFixture.AD_SELECTION_SIGNALS)
                        .setSellerSignals(AdSelectionConfigFixture.SELLER_SIGNALS)
                        .setPerBuyerSignals(AdSelectionConfigFixture.PER_BUYER_SIGNALS)
                        .setTrustedScoringSignalsUri(
                                AdSelectionConfigFixture.TRUSTED_SCORING_SIGNALS_URI)
                        .build();

        AdSelectionConfig cloneConfig = config.cloneToBuilder().build();

        assertEquals(AdSelectionConfigFixture.SELLER, cloneConfig.getSeller());
        assertEquals(
                AdSelectionConfigFixture.DECISION_LOGIC_URI, cloneConfig.getDecisionLogicUri());
        assertEquals(
                AdSelectionConfigFixture.CUSTOM_AUDIENCE_BUYERS,
                cloneConfig.getCustomAudienceBuyers());
        assertEquals(
                AdSelectionConfigFixture.AD_SELECTION_SIGNALS, cloneConfig.getAdSelectionSignals());
        assertEquals(AdSelectionConfigFixture.SELLER_SIGNALS, cloneConfig.getSellerSignals());
        assertEquals(AdSelectionConfigFixture.PER_BUYER_SIGNALS, cloneConfig.getPerBuyerSignals());
        assertEquals(
                AdSelectionConfigFixture.TRUSTED_SCORING_SIGNALS_URI,
                cloneConfig.getTrustedScoringSignalsUri());
    }

    @Test
    public void testBuildAdSelectionConfigUnsetDecisionLogicUriFailure() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new AdSelectionConfig.Builder()
                            .setSeller(AdSelectionConfigFixture.SELLER)
                            .setCustomAudienceBuyers(
                                    AdSelectionConfigFixture.CUSTOM_AUDIENCE_BUYERS)
                            .build();
                });
    }

    @Test
    public void testBuildAdSelectionConfigUnsetBuyersFailure() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new AdSelectionConfig.Builder()
                            .setSeller(AdSelectionConfigFixture.SELLER)
                            .setDecisionLogicUri(AdSelectionConfigFixture.DECISION_LOGIC_URI)
                            .build();
                });
    }

    @Test
    public void testAdSelectionConfigDescribeContents() {
        AdSelectionConfig obj = AdSelectionConfigFixture.anAdSelectionConfig();

        assertEquals(obj.describeContents(), 0);
    }

    @Test
    public void testEqualConfigsHaveSameHashCode() {
        AdSelectionConfig obj1 = AdSelectionConfigFixture.anAdSelectionConfig();
        AdSelectionConfig obj2 = AdSelectionConfigFixture.anAdSelectionConfig();

        CommonFixture.assertHaveSameHashCode(obj1, obj2);
    }

    @Test
    public void testNotEqualConfigsHaveDifferentHashCode() {
        AdSelectionConfig obj1 = AdSelectionConfigFixture.anAdSelectionConfig();
        AdSelectionConfig obj2 =
                AdSelectionConfigFixture.anAdSelectionConfig(AdSelectionConfigFixture.SELLER_1);
        AdSelectionConfig obj3 =
                AdSelectionConfigFixture.anAdSelectionConfig(
                        Uri.parse("https://different.uri.com"));

        CommonFixture.assertDifferentHashCode(obj1, obj2, obj3);
    }
}
