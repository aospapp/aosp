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

package com.android.adservices.service.adselection;

import static org.junit.Assert.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;

import android.adservices.common.AdDataFixture;
import android.adservices.common.CommonFixture;
import android.adservices.common.FrequencyCapFilters;

import com.android.adservices.data.adselection.AdSelectionEntryDao;
import com.android.adservices.data.adselection.DBAdSelectionHistogramInfo;
import com.android.adservices.data.adselection.FrequencyCapDao;
import com.android.adservices.data.common.FledgeRoomConverters;

import org.junit.Before;
import org.junit.Rule;
import org.junit.Test;
import org.mockito.Mock;
import org.mockito.junit.MockitoJUnit;
import org.mockito.junit.MockitoRule;

public class AdCounterHistogramUpdaterImplTest {
    private static final long AD_SELECTION_ID = 10;
    private static final int ABSOLUTE_MAX_EVENT_COUNT = 20;
    private static final int LOWER_MAX_EVENT_COUNT = 15;
    private static final String SERIALIZED_AD_COUNTER_KEYS =
            FledgeRoomConverters.serializeStringSet(AdDataFixture.getAdCounterKeys());

    @Rule public MockitoRule mMockitoRule = MockitoJUnit.rule();
    @Mock private AdSelectionEntryDao mAdSelectionEntryDaoMock;
    @Mock private FrequencyCapDao mFrequencyCapDaoMock;

    private AdCounterHistogramUpdater mAdCounterHistogramUpdater;

    @Before
    public void setup() {
        mAdCounterHistogramUpdater =
                new AdCounterHistogramUpdaterImpl(
                        mAdSelectionEntryDaoMock,
                        mFrequencyCapDaoMock,
                        ABSOLUTE_MAX_EVENT_COUNT,
                        LOWER_MAX_EVENT_COUNT);
    }

    @Test
    public void testNewUpdater_nullAdSelectionDaoThrows() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new AdCounterHistogramUpdaterImpl(
                                null,
                                mFrequencyCapDaoMock,
                                ABSOLUTE_MAX_EVENT_COUNT,
                                LOWER_MAX_EVENT_COUNT));
    }

    @Test
    public void testNewUpdater_nullFrequencyCapDaoThrows() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new AdCounterHistogramUpdaterImpl(
                                mAdSelectionEntryDaoMock,
                                null,
                                ABSOLUTE_MAX_EVENT_COUNT,
                                LOWER_MAX_EVENT_COUNT));
    }

    @Test
    public void testNewUpdater_invalidAbsoluteMaxEventCountThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AdCounterHistogramUpdaterImpl(
                                mAdSelectionEntryDaoMock,
                                mFrequencyCapDaoMock,
                                0,
                                LOWER_MAX_EVENT_COUNT));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AdCounterHistogramUpdaterImpl(
                                mAdSelectionEntryDaoMock,
                                mFrequencyCapDaoMock,
                                -1,
                                LOWER_MAX_EVENT_COUNT));
    }

    @Test
    public void testNewUpdater_invalidLowerMaxEventCountThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AdCounterHistogramUpdaterImpl(
                                mAdSelectionEntryDaoMock,
                                mFrequencyCapDaoMock,
                                ABSOLUTE_MAX_EVENT_COUNT,
                                0));
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AdCounterHistogramUpdaterImpl(
                                mAdSelectionEntryDaoMock,
                                mFrequencyCapDaoMock,
                                ABSOLUTE_MAX_EVENT_COUNT,
                                -1));
    }

    @Test
    public void testNewUpdater_invalidAbsoluteAndLowerMaxEventCountThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new AdCounterHistogramUpdaterImpl(
                                mAdSelectionEntryDaoMock,
                                mFrequencyCapDaoMock,
                                LOWER_MAX_EVENT_COUNT,
                                ABSOLUTE_MAX_EVENT_COUNT));
    }

    @Test
    public void testUpdateNonWinHistogram_nullCallerPackageNameThrows() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mAdCounterHistogramUpdater.updateNonWinHistogram(
                                AD_SELECTION_ID,
                                null,
                                FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION,
                                CommonFixture.FIXED_NOW));
    }

    @Test
    public void testUpdateNonWinHistogram_invalidAdEventTypeThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mAdCounterHistogramUpdater.updateNonWinHistogram(
                                AD_SELECTION_ID,
                                CommonFixture.TEST_PACKAGE_NAME,
                                FrequencyCapFilters.AD_EVENT_TYPE_INVALID,
                                CommonFixture.FIXED_NOW));
    }

    @Test
    public void testUpdateNonWinHistogram_winAdEventTypeThrows() {
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        mAdCounterHistogramUpdater.updateNonWinHistogram(
                                AD_SELECTION_ID,
                                CommonFixture.TEST_PACKAGE_NAME,
                                FrequencyCapFilters.AD_EVENT_TYPE_WIN,
                                CommonFixture.FIXED_NOW));
    }

    @Test
    public void testUpdateNonWinHistogram_nullTimestampThrows() {
        assertThrows(
                NullPointerException.class,
                () ->
                        mAdCounterHistogramUpdater.updateNonWinHistogram(
                                AD_SELECTION_ID,
                                CommonFixture.TEST_PACKAGE_NAME,
                                FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION,
                                null));
    }

    @Test
    public void testUpdateNonWinHistogram_missingAdSelectionStops() {
        doReturn(null).when(mAdSelectionEntryDaoMock).getAdSelectionHistogramInfo(anyLong(), any());

        mAdCounterHistogramUpdater.updateNonWinHistogram(
                AD_SELECTION_ID,
                CommonFixture.TEST_PACKAGE_NAME,
                FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION,
                CommonFixture.FIXED_NOW);

        verifyNoMoreInteractions(mFrequencyCapDaoMock);
    }

    @Test
    public void testUpdateNonWinHistogram_nullAdCounterKeysStops() {
        doReturn(DBAdSelectionHistogramInfo.create(CommonFixture.VALID_BUYER_1, null))
                .when(mAdSelectionEntryDaoMock)
                .getAdSelectionHistogramInfo(anyLong(), any());

        mAdCounterHistogramUpdater.updateNonWinHistogram(
                AD_SELECTION_ID,
                CommonFixture.TEST_PACKAGE_NAME,
                FrequencyCapFilters.AD_EVENT_TYPE_IMPRESSION,
                CommonFixture.FIXED_NOW);

        verifyNoMoreInteractions(mFrequencyCapDaoMock);
    }

    @Test
    public void testUpdateNonWinHistogram_withAdCounterKeysPersists() {
        doReturn(
                        DBAdSelectionHistogramInfo.create(
                                CommonFixture.VALID_BUYER_1, SERIALIZED_AD_COUNTER_KEYS))
                .when(mAdSelectionEntryDaoMock)
                .getAdSelectionHistogramInfo(anyLong(), any());

        mAdCounterHistogramUpdater.updateNonWinHistogram(
                AD_SELECTION_ID,
                CommonFixture.TEST_PACKAGE_NAME,
                FrequencyCapFilters.AD_EVENT_TYPE_VIEW,
                CommonFixture.FIXED_NOW);

        HistogramEvent.Builder expectedEventBuilder =
                HistogramEvent.builder()
                        .setAdEventType(FrequencyCapFilters.AD_EVENT_TYPE_VIEW)
                        .setBuyer(CommonFixture.VALID_BUYER_1)
                        .setTimestamp(CommonFixture.FIXED_NOW);

        for (String key : AdDataFixture.getAdCounterKeys()) {
            verify(mFrequencyCapDaoMock)
                    .insertHistogramEvent(
                            eq(expectedEventBuilder.setAdCounterKey(key).build()),
                            anyInt(),
                            anyInt());
        }
    }
}
