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

import static org.junit.Assert.assertThrows;

import android.adservices.common.CommonFixture;
import android.os.Parcel;

import org.junit.Assert;
import org.junit.Test;

public class ContextualAdsTest {

    @Test
    public void testBuildContextualAdsSuccess() {
        ContextualAds contextualAds =
                new ContextualAds.Builder()
                        .setBuyer(ContextualAdsFixture.BUYER)
                        .setDecisionLogicUri(ContextualAdsFixture.DECISION_LOGIC_URI)
                        .setAdsWithBid(ContextualAdsFixture.ADS_WITH_BID)
                        .build();

        Assert.assertEquals(contextualAds.getBuyer(), ContextualAdsFixture.BUYER);
        Assert.assertEquals(
                contextualAds.getDecisionLogicUri(), ContextualAdsFixture.DECISION_LOGIC_URI);
        Assert.assertEquals(contextualAds.getAdsWithBid(), ContextualAdsFixture.ADS_WITH_BID);
    }

    @Test
    public void testParcelValidContextualAdsSuccess() {
        ContextualAds contextualAds = ContextualAdsFixture.aContextualAd();

        Parcel p = Parcel.obtain();
        contextualAds.writeToParcel(p, 0);
        p.setDataPosition(0);
        ContextualAds fromParcel = ContextualAds.CREATOR.createFromParcel(p);

        Assert.assertEquals(contextualAds.getBuyer(), fromParcel.getBuyer());
        Assert.assertEquals(contextualAds.getDecisionLogicUri(), fromParcel.getDecisionLogicUri());
        Assert.assertEquals(contextualAds.getAdsWithBid(), fromParcel.getAdsWithBid());
    }

    @Test
    public void testSetContextualAdsNullBuyerFailure() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new ContextualAds.Builder().setBuyer(null);
                });
    }

    @Test
    public void testSetContextualAdsNullDecisionLogicUriFailure() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new ContextualAds.Builder().setDecisionLogicUri(null);
                });
    }

    @Test
    public void testSetContextualAdsNullAdWithBidFailure() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new ContextualAds.Builder().setAdsWithBid(null);
                });
    }

    @Test
    public void testParcelNullDestFailure() {
        ContextualAds contextualAds = ContextualAdsFixture.aContextualAd();
        Parcel nullDest = null;
        assertThrows(
                NullPointerException.class,
                () -> {
                    contextualAds.writeToParcel(nullDest, 0);
                });
    }

    @Test
    public void testBuildContextualAdsUnsetBuyerFailure() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new ContextualAds.Builder()
                            .setDecisionLogicUri(ContextualAdsFixture.DECISION_LOGIC_URI)
                            .setAdsWithBid(ContextualAdsFixture.ADS_WITH_BID)
                            .build();
                });
    }

    @Test
    public void testBuildContextualAdsUnsetDecisionLogicUriFailure() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new ContextualAds.Builder()
                            .setBuyer(ContextualAdsFixture.BUYER)
                            .setAdsWithBid(ContextualAdsFixture.ADS_WITH_BID)
                            .build();
                });
    }

    @Test
    public void testBuildContextualAdsUnsetAdWithBidFailure() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    new ContextualAds.Builder()
                            .setBuyer(ContextualAdsFixture.BUYER)
                            .setDecisionLogicUri(ContextualAdsFixture.DECISION_LOGIC_URI)
                            .build();
                });
    }

    @Test
    public void testContextualAdsDescribeContents() {
        ContextualAds obj = ContextualAdsFixture.aContextualAd();

        Assert.assertEquals(obj.describeContents(), 0);
    }

    @Test
    public void testContextualAdsHaveSameHashCode() {
        ContextualAds obj1 = ContextualAdsFixture.aContextualAd();
        ContextualAds obj2 = ContextualAdsFixture.aContextualAd();

        CommonFixture.assertHaveSameHashCode(obj1, obj2);
    }

    @Test
    public void testContextualAdsHaveDifferentHashCode() {
        ContextualAds obj1 = ContextualAdsFixture.aContextualAd();
        ContextualAds obj2 =
                ContextualAdsFixture.aContextualAdBuilder()
                        .setBuyer(ContextualAdsFixture.BUYER_2)
                        .build();

        CommonFixture.assertDifferentHashCode(obj1, obj2);
    }
}
