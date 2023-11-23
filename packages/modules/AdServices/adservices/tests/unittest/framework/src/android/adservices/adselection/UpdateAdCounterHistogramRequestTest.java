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

import androidx.test.filters.SmallTest;

import org.junit.Test;

// TODO(b/221876775): Move to CTS tests once public APIs are unhidden
@SmallTest
public class UpdateAdCounterHistogramRequestTest {
    private static final long VALID_AD_SELECTION_ID = 10;

    @Test
    public void testBuildValidRequest_success() {
        final UpdateAdCounterHistogramRequest originalRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_CLICK)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .build();

        assertThat(originalRequest.getAdSelectionId()).isEqualTo(VALID_AD_SELECTION_ID);
        assertThat(originalRequest.getAdEventType())
                .isEqualTo(FrequencyCapFilters.AD_EVENT_TYPE_CLICK);
        assertThat(originalRequest.getCallerAdTech()).isEqualTo(CommonFixture.VALID_BUYER_1);
    }

    @Test
    public void testEqualsIdentical() {
        final UpdateAdCounterHistogramRequest originalRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .build();
        final UpdateAdCounterHistogramRequest identicalRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .build();

        assertThat(originalRequest.equals(identicalRequest)).isTrue();
    }

    @Test
    public void testEqualsDifferent() {
        final UpdateAdCounterHistogramRequest originalRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .build();
        final UpdateAdCounterHistogramRequest differentRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID + 99)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_2)
                        .build();

        assertThat(originalRequest.equals(differentRequest)).isFalse();
    }

    @Test
    public void testEqualsNull() {
        final UpdateAdCounterHistogramRequest originalRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .build();
        final UpdateAdCounterHistogramRequest nullRequest = null;

        assertThat(originalRequest.equals(nullRequest)).isFalse();
    }

    @Test
    public void testHashCodeIdentical() {
        final UpdateAdCounterHistogramRequest originalRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .build();
        final UpdateAdCounterHistogramRequest identicalRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .build();

        assertThat(originalRequest.hashCode()).isEqualTo(identicalRequest.hashCode());
    }

    @Test
    public void testHashCodeDifferent() {
        final UpdateAdCounterHistogramRequest originalRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .build();
        final UpdateAdCounterHistogramRequest differentRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID + 99)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_CLICK)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_2)
                        .build();

        assertThat(originalRequest.hashCode()).isNotEqualTo(differentRequest.hashCode());
    }

    @Test
    public void testToString() {
        final UpdateAdCounterHistogramRequest originalRequest =
                new UpdateAdCounterHistogramRequest.Builder()
                        .setAdSelectionId(VALID_AD_SELECTION_ID)
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION)
                        .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                        .build();

        final String expected =
                String.format(
                        "UpdateAdCounterHistogramRequest{mAdSelectionId=%s, mAdEventType=%s,"
                                + " mCallerAdTech=%s}",
                        VALID_AD_SELECTION_ID,
                        FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION,
                        CommonFixture.VALID_BUYER_1);

        assertThat(originalRequest.toString()).isEqualTo(expected);
    }

    @Test
    public void testBuildUnsetAdSelectionId_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UpdateAdCounterHistogramRequest.Builder()
                                .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_CLICK)
                                .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                                .build());
    }

    @Test
    public void testSetWinType_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UpdateAdCounterHistogramRequest.Builder()
                                .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_WIN));
    }

    @Test
    public void testBuildUnsetType_throws() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new UpdateAdCounterHistogramRequest.Builder()
                                .setAdSelectionId(VALID_AD_SELECTION_ID)
                                .setCallerAdTech(CommonFixture.VALID_BUYER_1)
                                .build());
    }

    @Test
    public void testSetNullCaller_throws() {
        assertThrows(
                NullPointerException.class,
                () -> new UpdateAdCounterHistogramRequest.Builder().setCallerAdTech(null));
    }

    @Test
    public void testBuildUnsetCaller_throws() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new UpdateAdCounterHistogramRequest.Builder()
                                .setAdSelectionId(VALID_AD_SELECTION_ID)
                                .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION)
                                .build());
    }
}
