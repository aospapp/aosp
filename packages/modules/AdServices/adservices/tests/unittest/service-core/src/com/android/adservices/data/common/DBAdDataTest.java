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

package com.android.adservices.data.common;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;

import android.adservices.common.AdData;
import android.adservices.common.AdDataFixture;
import android.adservices.common.CommonFixture;
import android.net.Uri;

import com.android.adservices.data.customaudience.AdDataConversionStrategy;
import com.android.adservices.data.customaudience.AdDataConversionStrategyFactory;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.junit.MockitoJUnitRunner;

import java.util.Collections;
import java.util.HashSet;

@RunWith(MockitoJUnitRunner.class)
public class DBAdDataTest {

    private static final AdData SAMPLE_AD_DATA =
            AdDataFixture.getValidFilterAdDataByBuyer(CommonFixture.VALID_BUYER_1, 1);
    private static final AdDataConversionStrategy CONVERSION_STRATEGY =
            AdDataConversionStrategyFactory.getAdDataConversionStrategy(true);

    @Test
    public void testConstructor() {
        DBAdData dbAdData =
                new DBAdData(
                        SAMPLE_AD_DATA.getRenderUri(),
                        SAMPLE_AD_DATA.getMetadata(),
                        SAMPLE_AD_DATA.getAdCounterKeys(),
                        SAMPLE_AD_DATA.getAdFilters());
        assertEqualsServiceObject(SAMPLE_AD_DATA, dbAdData);
    }

    @Test
    public void testConstructorNullsSucceed() {
        DBAdData dbAdData =
                new DBAdData(
                        SAMPLE_AD_DATA.getRenderUri(),
                        SAMPLE_AD_DATA.getMetadata(),
                        new HashSet<>(),
                        null);
        assertEquals(SAMPLE_AD_DATA.getRenderUri(), dbAdData.getRenderUri());
        assertEquals(SAMPLE_AD_DATA.getMetadata(), dbAdData.getMetadata());
        assertEquals(Collections.EMPTY_SET, dbAdData.getAdCounterKeys());
        assertNull(dbAdData.getAdFilters());
    }

    @Test
    public void testRenderUriNullFails() {
        assertThrows(
                NullPointerException.class,
                () -> new DBAdData(null, SAMPLE_AD_DATA.getMetadata(), new HashSet<>(), null));
    }

    @Test
    public void testMetadataNullFails() {
        assertThrows(
                NullPointerException.class,
                () -> new DBAdData(SAMPLE_AD_DATA.getRenderUri(), null, new HashSet<>(), null));
    }

    @Test
    public void testAdCounterKeysNullFails() {
        assertThrows(
                NullPointerException.class,
                () ->
                        new DBAdData(
                                SAMPLE_AD_DATA.getRenderUri(),
                                SAMPLE_AD_DATA.getMetadata(),
                                null,
                                null));
    }

    @Test
    public void testFromServiceObject() {
        DBAdData dbAdData = CONVERSION_STRATEGY.fromServiceObject(SAMPLE_AD_DATA);
        assertEqualsServiceObject(SAMPLE_AD_DATA, dbAdData);
    }

    @Test
    public void testFromServiceObjectFilteringDisabled() {
        AdData original = AdDataFixture.getValidFilterAdDataByBuyer(CommonFixture.VALID_BUYER_1, 1);
        DBAdData dbAdData =
                AdDataConversionStrategyFactory.getAdDataConversionStrategy(false)
                        .fromServiceObject(original);
        AdData noFilters =
                new AdData.Builder()
                        .setRenderUri(original.getRenderUri())
                        .setMetadata(original.getMetadata())
                        .build();
        assertEqualsServiceObject(noFilters, dbAdData);
    }

    @Test
    public void testSize() {
        int[] size = new int[1];
        size[0] += SAMPLE_AD_DATA.getRenderUri().toString().getBytes().length;
        size[0] += SAMPLE_AD_DATA.getMetadata().getBytes().length;
        SAMPLE_AD_DATA.getAdCounterKeys().forEach(x -> size[0] += x.getBytes().length);
        size[0] += SAMPLE_AD_DATA.getAdFilters().getSizeInBytes();
        assertEquals(size[0], CONVERSION_STRATEGY.fromServiceObject(SAMPLE_AD_DATA).size());
    }

    @Test
    public void testSizeNulls() {
        int[] size = new int[1];
        size[0] += SAMPLE_AD_DATA.getRenderUri().toString().getBytes().length;
        size[0] += SAMPLE_AD_DATA.getMetadata().getBytes().length;
        DBAdData dbAdData =
                new DBAdData(
                        SAMPLE_AD_DATA.getRenderUri(),
                        SAMPLE_AD_DATA.getMetadata(),
                        Collections.EMPTY_SET,
                        null);
        assertEquals(size[0], dbAdData.size());
    }

    @Test
    public void testEquals() {
        DBAdData dbAdData1 = CONVERSION_STRATEGY.fromServiceObject(SAMPLE_AD_DATA);
        DBAdData dbAdData2 = CONVERSION_STRATEGY.fromServiceObject(SAMPLE_AD_DATA);
        assertEquals(dbAdData1, dbAdData2);
    }

    @Test
    public void testNotEqual() {
        DBAdData dbAdData1 = CONVERSION_STRATEGY.fromServiceObject(SAMPLE_AD_DATA);
        DBAdData dbAdData2 =
                new DBAdData(
                        SAMPLE_AD_DATA.getRenderUri(),
                        SAMPLE_AD_DATA.getMetadata(),
                        Collections.EMPTY_SET,
                        null);
        assertNotEquals(dbAdData1, dbAdData2);
    }

    @Test
    public void testHashEquals() {
        DBAdData dbAdData1 = CONVERSION_STRATEGY.fromServiceObject(SAMPLE_AD_DATA);
        DBAdData dbAdData2 = CONVERSION_STRATEGY.fromServiceObject(SAMPLE_AD_DATA);
        assertEquals(dbAdData1.hashCode(), dbAdData2.hashCode());
    }

    @Test
    public void testHashNotEqual() {
        // Technically there are values for SAMPLE_AD_DATA that could produce a collision, but it's
        // deterministic so there's no flake risk.
        DBAdData dbAdData1 = CONVERSION_STRATEGY.fromServiceObject(SAMPLE_AD_DATA);
        DBAdData dbAdData2 =
                new DBAdData(
                        SAMPLE_AD_DATA.getRenderUri(),
                        SAMPLE_AD_DATA.getMetadata(),
                        Collections.EMPTY_SET,
                        null);
        assertNotEquals(dbAdData1.hashCode(), dbAdData2.hashCode());
    }

    @Test
    public void testToString() {
        DBAdData dbAdData =
                new DBAdData(Uri.parse("https://a.com"), "{}", Collections.EMPTY_SET, null);
        assertEquals(
                "DBAdData{mRenderUri=https://a.com, mMetadata='{}', mAdCounterKeys=[], "
                        + "mAdFilters=null}",
                dbAdData.toString());
    }

    @Test
    public void testBuilder() {
        DBAdData dbAdData =
                new DBAdData.Builder()
                        .setRenderUri(SAMPLE_AD_DATA.getRenderUri())
                        .setMetadata(SAMPLE_AD_DATA.getMetadata())
                        .setAdCounterKeys(SAMPLE_AD_DATA.getAdCounterKeys())
                        .setAdFilters(SAMPLE_AD_DATA.getAdFilters())
                        .build();
        assertEqualsServiceObject(SAMPLE_AD_DATA, dbAdData);
    }

    private void assertEqualsServiceObject(AdData expected, DBAdData test) {
        assertEquals(expected.getRenderUri(), test.getRenderUri());
        assertEquals(expected.getMetadata(), test.getMetadata());
        assertEquals(expected.getAdCounterKeys(), test.getAdCounterKeys());
        assertEquals(expected.getAdFilters(), test.getAdFilters());
    }
}
