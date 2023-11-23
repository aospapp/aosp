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

package com.android.adservices.data.adselection;

import static com.android.adservices.data.adselection.DBHistogramIdentifierFixture.VALID_FOREIGN_KEY;

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.adservices.common.CommonFixture;
import android.adservices.common.KeyedFrequencyCapFixture;
import android.adservices.customaudience.CustomAudienceFixture;

import androidx.test.filters.SmallTest;

import com.android.adservices.service.adselection.HistogramEventFixture;

import org.junit.Test;

@SmallTest
public class DBHistogramIdentifierTest {
    @Test
    public void testBuildValidIdentifier_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.builder()
                        .setHistogramIdentifierForeignKey(VALID_FOREIGN_KEY)
                        .setAdCounterKey(KeyedFrequencyCapFixture.KEY1)
                        .setBuyer(CommonFixture.VALID_BUYER_1)
                        .setCustomAudienceOwner(CommonFixture.TEST_PACKAGE_NAME)
                        .setCustomAudienceName(CustomAudienceFixture.VALID_NAME)
                        .build();

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey())
                .isEqualTo(VALID_FOREIGN_KEY);
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner())
                .isEqualTo(CommonFixture.TEST_PACKAGE_NAME);
        assertThat(originalIdentifier.getCustomAudienceName())
                .isEqualTo(CustomAudienceFixture.VALID_NAME);
    }

    @Test
    public void testBuildUnsetForeignKey_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.builder()
                        .setAdCounterKey(KeyedFrequencyCapFixture.KEY1)
                        .setBuyer(CommonFixture.VALID_BUYER_1)
                        .setCustomAudienceOwner(CommonFixture.TEST_PACKAGE_NAME)
                        .setCustomAudienceName(CustomAudienceFixture.VALID_NAME)
                        .build();

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey()).isNull();
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner())
                .isEqualTo(CommonFixture.TEST_PACKAGE_NAME);
        assertThat(originalIdentifier.getCustomAudienceName())
                .isEqualTo(CustomAudienceFixture.VALID_NAME);
    }

    @Test
    public void testBuildUnsetAdCounterKey_throws() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        DBHistogramIdentifier.builder()
                                .setHistogramIdentifierForeignKey(VALID_FOREIGN_KEY)
                                .setBuyer(CommonFixture.VALID_BUYER_1)
                                .setCustomAudienceOwner(CommonFixture.TEST_PACKAGE_NAME)
                                .setCustomAudienceName(CustomAudienceFixture.VALID_NAME)
                                .build());
    }

    @Test
    public void testBuildUnsetBuyer_throws() {
        assertThrows(
                IllegalStateException.class,
                () ->
                        DBHistogramIdentifier.builder()
                                .setHistogramIdentifierForeignKey(VALID_FOREIGN_KEY)
                                .setAdCounterKey(KeyedFrequencyCapFixture.KEY1)
                                .setCustomAudienceOwner(CommonFixture.TEST_PACKAGE_NAME)
                                .setCustomAudienceName(CustomAudienceFixture.VALID_NAME)
                                .build());
    }

    @Test
    public void testBuildUnsetCustomAudienceOwner_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.builder()
                        .setHistogramIdentifierForeignKey(VALID_FOREIGN_KEY)
                        .setAdCounterKey(KeyedFrequencyCapFixture.KEY1)
                        .setBuyer(CommonFixture.VALID_BUYER_1)
                        .setCustomAudienceName(CustomAudienceFixture.VALID_NAME)
                        .build();

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey())
                .isEqualTo(VALID_FOREIGN_KEY);
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner()).isNull();
        assertThat(originalIdentifier.getCustomAudienceName())
                .isEqualTo(CustomAudienceFixture.VALID_NAME);
    }

    @Test
    public void testBuildUnsetCustomAudienceName_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.builder()
                        .setHistogramIdentifierForeignKey(VALID_FOREIGN_KEY)
                        .setAdCounterKey(KeyedFrequencyCapFixture.KEY1)
                        .setBuyer(CommonFixture.VALID_BUYER_1)
                        .setCustomAudienceOwner(CommonFixture.TEST_PACKAGE_NAME)
                        .build();

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey())
                .isEqualTo(VALID_FOREIGN_KEY);
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner())
                .isEqualTo(CommonFixture.TEST_PACKAGE_NAME);
        assertThat(originalIdentifier.getCustomAudienceName()).isNull();
    }

    @Test
    public void testSetNullForeignKey_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.builder()
                        .setHistogramIdentifierForeignKey(null)
                        .setAdCounterKey(KeyedFrequencyCapFixture.KEY1)
                        .setBuyer(CommonFixture.VALID_BUYER_1)
                        .setCustomAudienceOwner(CommonFixture.TEST_PACKAGE_NAME)
                        .setCustomAudienceName(CustomAudienceFixture.VALID_NAME)
                        .build();

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey()).isNull();
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner())
                .isEqualTo(CommonFixture.TEST_PACKAGE_NAME);
        assertThat(originalIdentifier.getCustomAudienceName())
                .isEqualTo(CustomAudienceFixture.VALID_NAME);
    }

    @Test
    public void testSetNullAdCounterKey_throws() {
        assertThrows(
                NullPointerException.class,
                () -> DBHistogramIdentifier.builder().setAdCounterKey(null));
    }

    @Test
    public void testSetNullBuyer_throws() {
        assertThrows(
                NullPointerException.class, () -> DBHistogramIdentifier.builder().setBuyer(null));
    }

    @Test
    public void testSetNullCustomAudienceOwner_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.builder()
                        .setHistogramIdentifierForeignKey(VALID_FOREIGN_KEY)
                        .setAdCounterKey(KeyedFrequencyCapFixture.KEY1)
                        .setBuyer(CommonFixture.VALID_BUYER_1)
                        .setCustomAudienceOwner(null)
                        .setCustomAudienceName(CustomAudienceFixture.VALID_NAME)
                        .build();

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey())
                .isEqualTo(VALID_FOREIGN_KEY);
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner()).isNull();
        assertThat(originalIdentifier.getCustomAudienceName())
                .isEqualTo(CustomAudienceFixture.VALID_NAME);
    }

    @Test
    public void testSetNullCustomAudienceName_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.builder()
                        .setHistogramIdentifierForeignKey(VALID_FOREIGN_KEY)
                        .setAdCounterKey(KeyedFrequencyCapFixture.KEY1)
                        .setBuyer(CommonFixture.VALID_BUYER_1)
                        .setCustomAudienceOwner(CommonFixture.TEST_PACKAGE_NAME)
                        .setCustomAudienceName(null)
                        .build();

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey())
                .isEqualTo(VALID_FOREIGN_KEY);
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner())
                .isEqualTo(CommonFixture.TEST_PACKAGE_NAME);
        assertThat(originalIdentifier.getCustomAudienceName()).isNull();
    }

    @Test
    public void testCreateValidIdentifier_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.create(
                        VALID_FOREIGN_KEY,
                        KeyedFrequencyCapFixture.KEY1,
                        CommonFixture.VALID_BUYER_1,
                        CommonFixture.TEST_PACKAGE_NAME,
                        CustomAudienceFixture.VALID_NAME);

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey())
                .isEqualTo(VALID_FOREIGN_KEY);
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner())
                .isEqualTo(CommonFixture.TEST_PACKAGE_NAME);
        assertThat(originalIdentifier.getCustomAudienceName())
                .isEqualTo(CustomAudienceFixture.VALID_NAME);
    }

    @Test
    public void testCreateNullAdCounterKey_throws() {
        assertThrows(
                NullPointerException.class,
                () ->
                        DBHistogramIdentifier.create(
                                VALID_FOREIGN_KEY,
                                null,
                                CommonFixture.VALID_BUYER_1,
                                CommonFixture.TEST_PACKAGE_NAME,
                                CustomAudienceFixture.VALID_NAME));
    }

    @Test
    public void testCreateNullBuyer_throws() {
        assertThrows(
                NullPointerException.class,
                () ->
                        DBHistogramIdentifier.create(
                                VALID_FOREIGN_KEY,
                                KeyedFrequencyCapFixture.KEY1,
                                null,
                                CommonFixture.TEST_PACKAGE_NAME,
                                CustomAudienceFixture.VALID_NAME));
    }

    @Test
    public void testCreateNullCustomAudienceOwner_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.create(
                        VALID_FOREIGN_KEY,
                        KeyedFrequencyCapFixture.KEY1,
                        CommonFixture.VALID_BUYER_1,
                        null,
                        CustomAudienceFixture.VALID_NAME);

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey())
                .isEqualTo(VALID_FOREIGN_KEY);
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner()).isNull();
        assertThat(originalIdentifier.getCustomAudienceName())
                .isEqualTo(CustomAudienceFixture.VALID_NAME);
    }

    @Test
    public void testCreateNullCustomAudienceName_success() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.create(
                        VALID_FOREIGN_KEY,
                        KeyedFrequencyCapFixture.KEY1,
                        CommonFixture.VALID_BUYER_1,
                        CommonFixture.TEST_PACKAGE_NAME,
                        null);

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey())
                .isEqualTo(VALID_FOREIGN_KEY);
        assertThat(originalIdentifier.getAdCounterKey()).isEqualTo(KeyedFrequencyCapFixture.KEY1);
        assertThat(originalIdentifier.getBuyer()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalIdentifier.getCustomAudienceOwner())
                .isEqualTo(CommonFixture.TEST_PACKAGE_NAME);
        assertThat(originalIdentifier.getCustomAudienceName()).isNull();
    }

    @Test
    public void testFromNonWinHistogramEventSetsCustomAudienceFieldsNull() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.fromHistogramEvent(
                        HistogramEventFixture.VALID_HISTOGRAM_EVENT);

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey()).isNull();
        assertThat(originalIdentifier.getAdCounterKey())
                .isEqualTo(HistogramEventFixture.VALID_HISTOGRAM_EVENT.getAdCounterKey());
        assertThat(originalIdentifier.getBuyer())
                .isEqualTo(HistogramEventFixture.VALID_HISTOGRAM_EVENT.getBuyer());
        assertThat(originalIdentifier.getCustomAudienceOwner()).isNull();
        assertThat(originalIdentifier.getCustomAudienceName()).isNull();
    }

    @Test
    public void testFromWinHistogramEventSetsCustomAudienceFields() {
        DBHistogramIdentifier originalIdentifier =
                DBHistogramIdentifier.fromHistogramEvent(
                        HistogramEventFixture.VALID_WIN_HISTOGRAM_EVENT);

        assertThat(originalIdentifier.getHistogramIdentifierForeignKey()).isNull();
        assertThat(originalIdentifier.getAdCounterKey())
                .isEqualTo(HistogramEventFixture.VALID_WIN_HISTOGRAM_EVENT.getAdCounterKey());
        assertThat(originalIdentifier.getBuyer())
                .isEqualTo(HistogramEventFixture.VALID_WIN_HISTOGRAM_EVENT.getBuyer());
        assertThat(originalIdentifier.getCustomAudienceOwner())
                .isEqualTo(HistogramEventFixture.VALID_HISTOGRAM_EVENT.getCustomAudienceOwner());
        assertThat(originalIdentifier.getCustomAudienceName())
                .isEqualTo(HistogramEventFixture.VALID_HISTOGRAM_EVENT.getCustomAudienceName());
    }

    @Test
    public void testFromNullHistogramEvent_throws() {
        assertThrows(
                NullPointerException.class, () -> DBHistogramIdentifier.fromHistogramEvent(null));
    }
}
