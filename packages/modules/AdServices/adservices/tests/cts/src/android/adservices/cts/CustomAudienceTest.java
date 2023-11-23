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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.adservices.common.AdData;
import android.adservices.common.AdDataFixture;
import android.adservices.common.CommonFixture;
import android.adservices.customaudience.CustomAudience;
import android.adservices.customaudience.CustomAudienceFixture;
import android.adservices.customaudience.TrustedBiddingDataFixture;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Collections;

/** Unit tests for {@link android.adservices.customaudience.CustomAudience} */
@SmallTest
public final class CustomAudienceTest {

    @Test
    public void testBuildValidCustomAudienceSuccess() {
        CustomAudience validCustomAudience =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1).build();

        assertEquals(CommonFixture.VALID_BUYER_1, validCustomAudience.getBuyer());
        assertEquals(CustomAudienceFixture.VALID_NAME, validCustomAudience.getName());
        assertEquals(
                CustomAudienceFixture.VALID_ACTIVATION_TIME,
                validCustomAudience.getActivationTime());
        assertEquals(
                CustomAudienceFixture.VALID_EXPIRATION_TIME,
                validCustomAudience.getExpirationTime());
        assertEquals(
                CustomAudienceFixture.getValidDailyUpdateUriByBuyer(CommonFixture.VALID_BUYER_1),
                validCustomAudience.getDailyUpdateUri());
        assertEquals(
                CustomAudienceFixture.VALID_USER_BIDDING_SIGNALS,
                validCustomAudience.getUserBiddingSignals());
        assertEquals(
                TrustedBiddingDataFixture.getValidTrustedBiddingDataByBuyer(
                        CommonFixture.VALID_BUYER_1),
                validCustomAudience.getTrustedBiddingData());
        assertEquals(
                CustomAudienceFixture.getValidBiddingLogicUriByBuyer(CommonFixture.VALID_BUYER_1),
                validCustomAudience.getBiddingLogicUri());
        assertEquals(
                AdDataFixture.getValidAdsByBuyer(CommonFixture.VALID_BUYER_1),
                validCustomAudience.getAds());
    }

    @Test
    public void testBuildValidDelayedActivationCustomAudienceSuccess() {
        CustomAudience validDelayedActivationCustomAudience =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                        .setActivationTime(CustomAudienceFixture.VALID_DELAYED_ACTIVATION_TIME)
                        .setExpirationTime(CustomAudienceFixture.VALID_DELAYED_EXPIRATION_TIME)
                        .build();

        assertThat(validDelayedActivationCustomAudience.getBuyer())
                .isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(validDelayedActivationCustomAudience.getName())
                .isEqualTo(CustomAudienceFixture.VALID_NAME);
        assertThat(validDelayedActivationCustomAudience.getActivationTime())
                .isEqualTo(CustomAudienceFixture.VALID_DELAYED_ACTIVATION_TIME);
        assertThat(validDelayedActivationCustomAudience.getExpirationTime())
                .isEqualTo(CustomAudienceFixture.VALID_DELAYED_EXPIRATION_TIME);
        assertThat(validDelayedActivationCustomAudience.getDailyUpdateUri())
                .isEqualTo(
                        CustomAudienceFixture.getValidDailyUpdateUriByBuyer(
                                CommonFixture.VALID_BUYER_1));
        assertThat(validDelayedActivationCustomAudience.getUserBiddingSignals())
                .isEqualTo(CustomAudienceFixture.VALID_USER_BIDDING_SIGNALS);
        assertThat(validDelayedActivationCustomAudience.getTrustedBiddingData())
                .isEqualTo(
                        TrustedBiddingDataFixture.getValidTrustedBiddingDataByBuyer(
                                CommonFixture.VALID_BUYER_1));
        assertThat(validDelayedActivationCustomAudience.getBiddingLogicUri())
                .isEqualTo(
                        CustomAudienceFixture.getValidBiddingLogicUriByBuyer(
                                CommonFixture.VALID_BUYER_1));
        assertThat(validDelayedActivationCustomAudience.getAds())
                .isEqualTo(AdDataFixture.getValidAdsByBuyer(CommonFixture.VALID_BUYER_1));
    }

    @Test
    public void testParcelValidCustomAudienceSuccess() {
        CustomAudience validCustomAudience =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1).build();

        Parcel p = Parcel.obtain();
        validCustomAudience.writeToParcel(p, 0);
        p.setDataPosition(0);
        CustomAudience fromParcel = CustomAudience.CREATOR.createFromParcel(p);

        assertEquals(validCustomAudience, fromParcel);
    }

    @Test
    public void testParcelValidCustomAudienceWithNullValueSuccess() {
        CustomAudience validCustomAudienceWithNullValue =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                        .setActivationTime(null)
                        .setTrustedBiddingData(null)
                        .build();

        Parcel p = Parcel.obtain();
        validCustomAudienceWithNullValue.writeToParcel(p, 0);
        p.setDataPosition(0);
        CustomAudience fromParcel = CustomAudience.CREATOR.createFromParcel(p);

        assertEquals(validCustomAudienceWithNullValue, fromParcel);
    }

    @Test
    public void testNonNullValueNotSetBuildFails() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    // No buyer were set
                    new CustomAudience.Builder()
                            .setName(CustomAudienceFixture.VALID_NAME)
                            .setActivationTime(CustomAudienceFixture.VALID_DELAYED_ACTIVATION_TIME)
                            .setExpirationTime(CustomAudienceFixture.VALID_EXPIRATION_TIME)
                            .setDailyUpdateUri(
                                    CustomAudienceFixture.getValidDailyUpdateUriByBuyer(
                                            CommonFixture.VALID_BUYER_1))
                            .setUserBiddingSignals(CustomAudienceFixture.VALID_USER_BIDDING_SIGNALS)
                            .setTrustedBiddingData(
                                    TrustedBiddingDataFixture.getValidTrustedBiddingDataByBuyer(
                                            CommonFixture.VALID_BUYER_1))
                            .setBiddingLogicUri(
                                    CustomAudienceFixture.getValidBiddingLogicUriByBuyer(
                                            CommonFixture.VALID_BUYER_1))
                            .setAds(AdDataFixture.getValidAdsByBuyer(CommonFixture.VALID_BUYER_1))
                            .build();
                });
    }

    @Test
    public void testSetNullToNonNullValueFails() {
        assertThrows(
                NullPointerException.class,
                () -> {
                    // No buyer were set
                    new CustomAudience.Builder()
                            .setBuyer(null)
                            .build();
                });
    }

    @Test
    public void testBuildNullAdsCustomAudienceSuccess() {
        // Ads are not set, so the CustomAudience gets built with empty list.
        CustomAudience nullAdsCustomAudience =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                        .setAds(null)
                        .build();

        assertThat(nullAdsCustomAudience.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(nullAdsCustomAudience.getName()).isEqualTo(CustomAudienceFixture.VALID_NAME);
        assertThat(nullAdsCustomAudience.getActivationTime())
                .isEqualTo(CustomAudienceFixture.VALID_ACTIVATION_TIME);
        assertThat(nullAdsCustomAudience.getExpirationTime())
                .isEqualTo(CustomAudienceFixture.VALID_EXPIRATION_TIME);
        assertThat(nullAdsCustomAudience.getDailyUpdateUri())
                .isEqualTo(
                        CustomAudienceFixture.getValidDailyUpdateUriByBuyer(
                                CommonFixture.VALID_BUYER_1));
        assertThat(nullAdsCustomAudience.getUserBiddingSignals())
                .isEqualTo(CustomAudienceFixture.VALID_USER_BIDDING_SIGNALS);
        assertThat(nullAdsCustomAudience.getTrustedBiddingData())
                .isEqualTo(
                        TrustedBiddingDataFixture.getValidTrustedBiddingDataByBuyer(
                                CommonFixture.VALID_BUYER_1));
        assertThat(nullAdsCustomAudience.getBiddingLogicUri())
                .isEqualTo(
                        CustomAudienceFixture.getValidBiddingLogicUriByBuyer(
                                CommonFixture.VALID_BUYER_1));
        assertThat(nullAdsCustomAudience.getAds()).isEqualTo(Collections.emptyList());
    }

    @Test
    public void testBuildEmptyAdsCustomAudienceSuccess() {
        // An empty list is allowed and should not throw any exceptions
        ArrayList<AdData> emptyAds = new ArrayList<>(Collections.emptyList());

        CustomAudience emptyAdsCustomAudience =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1)
                        .setAds(emptyAds)
                        .build();

        assertThat(emptyAdsCustomAudience.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(emptyAdsCustomAudience.getName()).isEqualTo(CustomAudienceFixture.VALID_NAME);
        assertThat(emptyAdsCustomAudience.getActivationTime())
                .isEqualTo(CustomAudienceFixture.VALID_ACTIVATION_TIME);
        assertThat(emptyAdsCustomAudience.getExpirationTime())
                .isEqualTo(CustomAudienceFixture.VALID_EXPIRATION_TIME);
        assertThat(emptyAdsCustomAudience.getDailyUpdateUri())
                .isEqualTo(
                        CustomAudienceFixture.getValidDailyUpdateUriByBuyer(
                                CommonFixture.VALID_BUYER_1));
        assertThat(emptyAdsCustomAudience.getUserBiddingSignals())
                .isEqualTo(CustomAudienceFixture.VALID_USER_BIDDING_SIGNALS);
        assertThat(emptyAdsCustomAudience.getTrustedBiddingData())
                .isEqualTo(
                        TrustedBiddingDataFixture.getValidTrustedBiddingDataByBuyer(
                                CommonFixture.VALID_BUYER_1));
        assertThat(emptyAdsCustomAudience.getBiddingLogicUri())
                .isEqualTo(
                        CustomAudienceFixture.getValidBiddingLogicUriByBuyer(
                                CommonFixture.VALID_BUYER_1));
        assertThat(emptyAdsCustomAudience.getAds()).isEqualTo(emptyAds);
    }

    @Test
    public void testCustomAudienceDescribeContent() {
        CustomAudience validCustomAudience =
                CustomAudienceFixture.getValidBuilderForBuyer(CommonFixture.VALID_BUYER_1).build();

        assertThat(validCustomAudience.describeContents()).isEqualTo(0);
    }
}
