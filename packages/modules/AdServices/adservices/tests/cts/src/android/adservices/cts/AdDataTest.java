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
import android.adservices.common.AdFiltersFixture;
import android.adservices.common.CommonFixture;
import android.net.Uri;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Ignore;
import org.junit.Test;


/** Unit tests for {@link AdData} */
@SmallTest
public final class AdDataTest {
    private static final Uri VALID_RENDER_URI =
            new Uri.Builder().path("valid.example.com/testing/hello").build();


    @Test
    public void testBuildValidAdDataSuccess() {
        AdData validAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .build();

        assertThat(validAdData.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(validAdData.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
    }

    @Test
    public void testParcelValidAdDataSuccess() {
        AdData validAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .build();

        Parcel p = Parcel.obtain();
        validAdData.writeToParcel(p, 0);
        p.setDataPosition(0);
        AdData fromParcel = AdData.CREATOR.createFromParcel(p);

        assertThat(fromParcel.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(fromParcel.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
    }

    @Test
    public void testBuildNullUriAdDataFails() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new AdData.Builder()
                                .setRenderUri(null)
                                .setMetadata(AdDataFixture.VALID_METADATA)
                                .build());
    }

    @Test
    public void testBuildNullMetadataAdDataFails() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new AdData.Builder()
                                .setRenderUri(VALID_RENDER_URI)
                                .setMetadata(null)
                                .build());
    }

    @Test
    public void testAdDataToString() {
        AdData.Builder builder =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA);
        if (AdDataFixture.FCAP_ENABLED) {
            builder.setAdCounterKeys(AdDataFixture.getAdCounterKeys());
        }
        if (AdDataFixture.FCAP_ENABLED && AdDataFixture.APP_INSTALL_ENABLED) {
            builder.setAdFilters(AdFiltersFixture.getValidUnhiddenFilters());
        }
        AdData obj = builder.build();
        String expected =
                "AdData{mRenderUri="
                        + VALID_RENDER_URI
                        + ", mMetadata='"
                        + AdDataFixture.VALID_METADATA
                        + "'"
                        + generateAdCounterKeyString()
                        + generateAdFilterString()
                        + "}";
        assertEquals(expected, obj.toString());
    }

    private String generateAdCounterKeyString() {
        if (AdDataFixture.FCAP_ENABLED) {
            return ", mAdCounterKeys=" + AdDataFixture.getAdCounterKeys();
        }
        return "";
    }

    private String generateAdFilterString() {
        if (AdDataFixture.FCAP_ENABLED || AdDataFixture.APP_INSTALL_ENABLED) {
            return ", mAdFilters=" + AdFiltersFixture.getValidUnhiddenFilters();
        }
        return "";
    }

    @Test
    public void testAdDataDescribeContent() {
        AdData obj =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .build();

        assertEquals(0, obj.describeContents());
    }

    @Ignore
    @Test
    public void testParcelWithFilters_success() {
        final AdData originalAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .setAdFilters(AdFiltersFixture.getValidUnhiddenFilters())
                        .build();

        Parcel targetParcel = Parcel.obtain();
        originalAdData.writeToParcel(targetParcel, 0);
        targetParcel.setDataPosition(0);
        final AdData adDataFromParcel = AdData.CREATOR.createFromParcel(targetParcel);

        assertThat(adDataFromParcel.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(adDataFromParcel.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
        assertThat(adDataFromParcel.getAdFilters())
                .isEqualTo(AdFiltersFixture.getValidUnhiddenFilters());
    }

    @Ignore
    @Test
    public void testEqualsIdenticalFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(AdFiltersFixture.getValidUnhiddenFilters())
                        .build();
        final AdData identicalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(AdFiltersFixture.getValidUnhiddenFilters())
                        .build();

        assertThat(originalAdData.equals(identicalAdData)).isTrue();
    }

    @Ignore
    @Test
    public void testEqualsDifferentFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(AdFiltersFixture.getValidUnhiddenFilters())
                        .build();
        final AdData differentAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(null)
                        .build();

        assertThat(originalAdData.equals(differentAdData)).isFalse();
    }

    @Ignore
    @Test
    public void testEqualsNullFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(AdFiltersFixture.getValidUnhiddenFilters())
                        .build();
        final AdData nullAdData = null;

        assertThat(originalAdData.equals(nullAdData)).isFalse();
    }

    @Ignore
    @Test
    public void testHashCodeIdenticalFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(AdFiltersFixture.getValidUnhiddenFilters())
                        .build();
        final AdData identicalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(AdFiltersFixture.getValidUnhiddenFilters())
                        .build();

        assertThat(originalAdData.hashCode()).isEqualTo(identicalAdData.hashCode());
    }

    @Ignore
    @Test
    public void testHashCodeDifferentFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(AdFiltersFixture.getValidUnhiddenFilters())
                        .build();
        final AdData differentAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(null)
                        .build();

        assertThat(originalAdData.hashCode()).isNotEqualTo(differentAdData.hashCode());
    }

    @Ignore
    @Test
    public void testBuildValidAdDataWithUnsetFilters_success() {
        final AdData validAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .build();

        assertThat(validAdData.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(validAdData.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
        assertThat(validAdData.getAdFilters()).isNull();
    }
}
