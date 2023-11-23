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

package android.adservices.common;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.net.Uri;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

import java.util.HashSet;

/** Unit tests for temporarily hidden public APIs in {@link AdData}. */
// TODO(b/221876775): Merge into CTS AdData test class once APIs are unhidden
@SmallTest
public class AdDataTempHideTest {
    private static final Uri VALID_RENDER_URI =
            AdDataFixture.getValidRenderUriByBuyer(CommonFixture.VALID_BUYER_1, 0);

    @Test
    public void testParcelWithKeysAndFilters_success() {
        final AdData originalAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                        .setAdFilters(AdFiltersFixture.getValidAdFilters())
                        .build();

        Parcel targetParcel = Parcel.obtain();
        originalAdData.writeToParcel(targetParcel, 0);
        targetParcel.setDataPosition(0);
        final AdData adDataFromParcel = AdData.CREATOR.createFromParcel(targetParcel);

        assertThat(adDataFromParcel.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(adDataFromParcel.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
        assertThat(adDataFromParcel.getAdCounterKeys())
                .containsExactlyElementsIn(AdDataFixture.getAdCounterKeys());
        assertThat(adDataFromParcel.getAdFilters()).isEqualTo(AdFiltersFixture.getValidAdFilters());
    }

    @Test
    public void testEqualsIdentical_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                        .setAdFilters(AdFiltersFixture.getValidAdFilters())
                        .build();
        final AdData identicalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                        .setAdFilters(AdFiltersFixture.getValidAdFilters())
                        .build();

        assertThat(originalAdData.equals(identicalAdData)).isTrue();
    }

    @Test
    public void testEqualsDifferent_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                        .setAdFilters(AdFiltersFixture.getValidAdFilters())
                        .build();
        final AdData differentAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdCounterKeys(new HashSet<>())
                        .setAdFilters(null)
                        .build();

        assertThat(originalAdData.equals(differentAdData)).isFalse();
    }

    @Test
    public void testEqualsNull_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                        .setAdFilters(AdFiltersFixture.getValidAdFilters())
                        .build();
        final AdData nullAdData = null;

        assertThat(originalAdData.equals(nullAdData)).isFalse();
    }

    @Test
    public void testHashCodeIdentical_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                        .setAdFilters(AdFiltersFixture.getValidAdFilters())
                        .build();
        final AdData identicalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                        .setAdFilters(AdFiltersFixture.getValidAdFilters())
                        .build();

        assertThat(originalAdData.hashCode()).isEqualTo(identicalAdData.hashCode());
    }

    @Test
    public void testHashCodeDifferent_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                        .setAdFilters(AdFiltersFixture.getValidAdFilters())
                        .build();
        final AdData differentAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdCounterKeys(new HashSet<>())
                        .setAdFilters(null)
                        .build();

        assertThat(originalAdData.hashCode()).isNotEqualTo(differentAdData.hashCode());
    }

    @Test
    public void testBuildNullKeys_throws() {
        assertThrows(NullPointerException.class, () -> new AdData.Builder().setAdCounterKeys(null));
    }

    @Test
    public void testBuildValidAdDataWithUnsetKeys_success() {
        final AdData validAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .setAdFilters(AdFiltersFixture.getValidAdFilters())
                        .build();

        assertThat(validAdData.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(validAdData.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
        assertThat(validAdData.getAdCounterKeys()).isEmpty();
        assertThat(validAdData.getAdFilters()).isEqualTo(AdFiltersFixture.getValidAdFilters());
    }

    @Test
    public void testBuildValidAdDataWithUnsetFilters_success() {
        final AdData validAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .setAdCounterKeys(AdDataFixture.getAdCounterKeys())
                        .build();

        assertThat(validAdData.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(validAdData.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
        assertThat(validAdData.getAdCounterKeys())
                .containsExactlyElementsIn(AdDataFixture.getAdCounterKeys());
        assertThat(validAdData.getAdFilters()).isNull();
    }

    @Test
    public void testBuildValidAdDataWithUnsetKeysAndFilters_success() {
        final AdData validAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .build();

        assertThat(validAdData.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(validAdData.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
        assertThat(validAdData.getAdCounterKeys()).isEmpty();
        assertThat(validAdData.getAdFilters()).isNull();
    }

    @Test
    public void testParcelWithFilters_success() {
        final AdData originalAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .setAdFilters(getValidAppInstallOnlyFilters())
                        .build();

        Parcel targetParcel = Parcel.obtain();
        originalAdData.writeToParcel(targetParcel, 0);
        targetParcel.setDataPosition(0);
        final AdData adDataFromParcel = AdData.CREATOR.createFromParcel(targetParcel);

        assertThat(adDataFromParcel.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(adDataFromParcel.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
        assertThat(adDataFromParcel.getAdFilters()).isEqualTo(getValidAppInstallOnlyFilters());
    }

    @Test
    public void testEqualsIdenticalFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(getValidAppInstallOnlyFilters())
                        .build();
        final AdData identicalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(getValidAppInstallOnlyFilters())
                        .build();

        assertThat(originalAdData.equals(identicalAdData)).isTrue();
    }

    @Test
    public void testEqualsDifferentFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(getValidAppInstallOnlyFilters())
                        .build();
        final AdData differentAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(null)
                        .build();

        assertThat(originalAdData.equals(differentAdData)).isFalse();
    }

    @Test
    public void testEqualsNullFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(getValidAppInstallOnlyFilters())
                        .build();
        final AdData nullAdData = null;

        assertThat(originalAdData.equals(nullAdData)).isFalse();
    }

    @Test
    public void testHashCodeIdenticalFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(getValidAppInstallOnlyFilters())
                        .build();
        final AdData identicalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(getValidAppInstallOnlyFilters())
                        .build();

        assertThat(originalAdData.hashCode()).isEqualTo(identicalAdData.hashCode());
    }

    @Test
    public void testHashCodeDifferentFilters_success() {
        final AdData originalAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(getValidAppInstallOnlyFilters())
                        .build();
        final AdData differentAdData =
                AdDataFixture.getValidAdDataBuilderByBuyer(CommonFixture.VALID_BUYER_1, 0)
                        .setAdFilters(null)
                        .build();

        assertThat(originalAdData.hashCode()).isNotEqualTo(differentAdData.hashCode());
    }

    @Test
    public void testBuildValidAdDataWithUnsetFiltersOnly_success() {
        final AdData validAdData =
                new AdData.Builder()
                        .setRenderUri(VALID_RENDER_URI)
                        .setMetadata(AdDataFixture.VALID_METADATA)
                        .build();

        assertThat(validAdData.getRenderUri()).isEqualTo(VALID_RENDER_URI);
        assertThat(validAdData.getMetadata()).isEqualTo(AdDataFixture.VALID_METADATA);
        assertThat(validAdData.getAdFilters()).isNull();
    }

    private AdFilters getValidAppInstallOnlyFilters() {
        return new AdFilters.Builder()
                .setAppInstallFilters(AppInstallFiltersFixture.VALID_APP_INSTALL_FILTERS)
                .build();
    }
}
