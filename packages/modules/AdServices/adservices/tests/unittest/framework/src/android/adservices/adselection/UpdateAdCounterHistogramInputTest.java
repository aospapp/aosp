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

import static com.google.common.truth.Truth.assertThat;

import static org.junit.Assert.assertThrows;

import android.adservices.common.CommonFixture;
import android.adservices.common.FrequencyCapFilters;
import android.os.Parcel;

import androidx.test.filters.SmallTest;

import org.junit.Test;

@SmallTest
public class UpdateAdCounterHistogramInputTest {
    private static final long VALID_AD_SELECTION_ID = 10;
    private static final String VALID_PACKAGE_NAME = "test.package";

    @Test
    public void testBuildValidInput_success() {
        final UpdateAdCounterHistogramInput originalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_CLICK)
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();

        assertThat(originalInput.getAdEventType())
                .isEqualTo(FrequencyCapFilters.AD_EVENT_TYPE_CLICK);
        assertThat(originalInput.getAdSelectionId()).isEqualTo(VALID_AD_SELECTION_ID);
        assertThat(originalInput.getCallerAdTech()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(originalInput.getCallerPackageName()).isEqualTo(VALID_PACKAGE_NAME);
    }

    @Test
    public void testParcelInput_success() {
        final UpdateAdCounterHistogramInput originalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();

        Parcel targetParcel = Parcel.obtain();
        originalInput.writeToParcel(targetParcel, 0);
        targetParcel.setDataPosition(0);
        final UpdateAdCounterHistogramInput requestFromParcel =
                UpdateAdCounterHistogramInput.CREATOR.createFromParcel(targetParcel);

        assertThat(requestFromParcel.getAdEventType())
                .isEqualTo(FrequencyCapFilters.AD_EVENT_TYPE_VIEW);
        assertThat(requestFromParcel.getAdSelectionId()).isEqualTo(VALID_AD_SELECTION_ID);
        assertThat(requestFromParcel.getCallerAdTech()).isEqualTo(CommonFixture.VALID_BUYER_1);
        assertThat(requestFromParcel.getCallerPackageName()).isEqualTo(VALID_PACKAGE_NAME);
    }

    @Test
    public void testEqualsIdentical() {
        final UpdateAdCounterHistogramInput originalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();
        final UpdateAdCounterHistogramInput identicalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();

        assertThat(originalInput.equals(identicalInput)).isTrue();
    }

    @Test
    public void testEqualsDifferent() {
        final UpdateAdCounterHistogramInput originalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();
        final UpdateAdCounterHistogramInput differentInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID + 99)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_2)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();

        assertThat(originalInput.equals(differentInput)).isFalse();
    }

    @Test
    public void testEqualsNull() {
        final UpdateAdCounterHistogramInput originalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();
        final UpdateAdCounterHistogramInput nullInput = null;

        assertThat(originalInput.equals(nullInput)).isFalse();
    }

    @Test
    public void testHashCodeIdentical() {
        final UpdateAdCounterHistogramInput originalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();
        final UpdateAdCounterHistogramInput identicalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();

        assertThat(originalInput.hashCode()).isEqualTo(identicalInput.hashCode());
    }

    @Test
    public void testHashCodeDifferent() {
        final UpdateAdCounterHistogramInput originalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();
        final UpdateAdCounterHistogramInput differentInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID + 99)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_CLICK)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_2)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();

        assertThat(originalInput.hashCode()).isNotEqualTo(differentInput.hashCode());
    }

    @Test
    public void testToString() {
        final UpdateAdCounterHistogramInput originalInput =
                new UpdateAdCounterHistogramInput.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .setCallerPackageName(VALID_PACKAGE_NAME)
                        .build();

        final String expected =
                String.format(
                        "UpdateAdCounterHistogramInput{mAdSelectionId=%s, mAdEventType=%s,"
                                + " mCallerAdTech=%s, mCallerPackageName='%s'}",
                        VALID_AD_SELECTION_ID,
                        FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION,
                        CommonFixture.VALID_BUYER_1,
                        VALID_PACKAGE_NAME);

        assertThat(originalInput.toString()).isEqualTo(expected);
    }

    @Test
    public void testBuildUnsetAdSelectionId_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UpdateAdCounterHistogramInput.Builder()
                                .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_CLICK)
                                .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                                .setCallerPackageName(VALID_PACKAGE_NAME)
                                .build());
    }

    @Test
    public void testSetWinType_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UpdateAdCounterHistogramInput.Builder()
                                .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_WIN));
    }

    @Test
    public void testBuildUnsetType_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UpdateAdCounterHistogramInput.Builder()
                                .setAdSelectionId(VALID_AD_SELECTION_ID)
                                .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                                .setCallerPackageName(VALID_PACKAGE_NAME)
                                .build());
    }

    @Test
    public void testSetNullCallerAdTech_throws() {
        assertThrows(
                NullPointerException.class,
                () -> new UpdateAdCounterHistogramInput.Builder().setCallerAdTech(null));
    }

    @Test
    public void testBuildUnsetCallerAdTech_throws() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new UpdateAdCounterHistogramInput.Builder()
                                .setAdSelectionId(VALID_AD_SELECTION_ID)
                                .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION)
                                .setCallerPackageName(VALID_PACKAGE_NAME)
                                .build());
    }

    @Test
    public void testSetNullCallerPackageName_throws() {
        assertThrows(
                NullPointerException.class,
                () -> new UpdateAdCounterHistogramInput.Builder().setCallerPackageName(null));
    }

    @Test
    public void testBuildUnsetCallerPackageName_throws() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new UpdateAdCounterHistogramInput.Builder()
                                .setAdSelectionId(VALID_AD_SELECTION_ID)
                                .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION)
                                .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                                .build());
    }
}
