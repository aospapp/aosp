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

package com.android.ondevicepersonalization.services.util;

import static org.junit.Assert.assertEquals;

import android.ondevicepersonalization.ExecuteOutput;
import android.ondevicepersonalization.Metrics;
import android.ondevicepersonalization.SlotResult;

import com.android.ondevicepersonalization.services.fbs.Bid;
import com.android.ondevicepersonalization.services.fbs.EventFields;
import com.android.ondevicepersonalization.services.fbs.QueryData;
import com.android.ondevicepersonalization.services.fbs.QueryFields;
import com.android.ondevicepersonalization.services.fbs.Slot;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.nio.ByteBuffer;

@RunWith(JUnit4.class)
public class OnDevicePersonalizationFlatbufferUtilsTests {

    private static final double DELTA = 0.001;

    @Test
    public void testCreateEventData() {
        Metrics metrics = new Metrics.Builder()
                        .setLongValues(1, 2)
                        .setDoubleValues(1, 2)
                        .setBooleanValues(true, false)
                        .build();
        byte[] eventData = OnDevicePersonalizationFlatbufferUtils.createEventData(metrics);

        EventFields eventFields = EventFields.getRootAsEventFields(ByteBuffer.wrap(eventData));
        assertEquals(2, eventFields.metrics().longValuesLength());
        assertEquals(1, eventFields.metrics().longValues(0));
        assertEquals(2, eventFields.metrics().longValues(1));
        assertEquals(2, eventFields.metrics().doubleValuesLength());
        assertEquals(1, eventFields.metrics().doubleValues(0), DELTA);
        assertEquals(2, eventFields.metrics().doubleValues(1), DELTA);
        assertEquals(2, eventFields.metrics().booleanValuesLength());
        assertEquals(true, eventFields.metrics().booleanValues(0));
        assertEquals(false, eventFields.metrics().booleanValues(1));
    }

    @Test
    public void testCreateEventDataNullMetrics() {
        byte[] eventData = OnDevicePersonalizationFlatbufferUtils.createEventData(null);

        EventFields eventFields = EventFields.getRootAsEventFields(ByteBuffer.wrap(eventData));
        assertEquals(null, eventFields.metrics());
    }

    @Test
    public void testCreateQueryDataNullSlotResults() {
        ExecuteOutput result = new ExecuteOutput.Builder().setSlotResults(
                null).build();
        byte[] queryDataBytes = OnDevicePersonalizationFlatbufferUtils.createQueryData(
                null, null, result);

        QueryData queryData = QueryData.getRootAsQueryData(ByteBuffer.wrap(queryDataBytes));
        assertEquals(1, queryData.queryFieldsLength());
        QueryFields queryFields = queryData.queryFields(0);
        assertEquals(null, queryFields.owner().packageName());
        assertEquals(null, queryFields.owner().certDigest());
        assertEquals(0, queryFields.slotsLength());
    }

    @Test
    public void testCreateQueryData() {
        ExecuteOutput result = new ExecuteOutput.Builder()
                .addSlotResults(
                        new SlotResult.Builder()
                                .setSlotKey("abc")
                                .addRenderedBidKeys("bid1")
                                .addLoggedBids(
                                        new android.ondevicepersonalization.Bid.Builder()
                                                .setKey("bid1")
                                                .setMetrics(new Metrics.Builder()
                                                        .setLongValues(11).build())
                                                .build())
                                .addLoggedBids(
                                        new android.ondevicepersonalization.Bid.Builder()
                                                .setKey("bid2")
                                                .build())
                                .build())
                .build();
        byte[] queryDataBytes = OnDevicePersonalizationFlatbufferUtils.createQueryData(
                "testPackage", "testCert", result);

        QueryData queryData = QueryData.getRootAsQueryData(ByteBuffer.wrap(queryDataBytes));
        assertEquals(1, queryData.queryFieldsLength());
        QueryFields queryFields = queryData.queryFields(0);
        assertEquals("testPackage", queryFields.owner().packageName());
        assertEquals("testCert", queryFields.owner().certDigest());
        assertEquals(1, queryFields.slotsLength());
        Slot slot = queryFields.slots(0);
        assertEquals("abc", slot.key());
        assertEquals(2, slot.bidsLength());
        Bid winningBid = slot.bids(0);
        assertEquals("bid1", winningBid.key());
        assertEquals(11, winningBid.metrics().longValues(0));
        assertEquals(0, winningBid.metrics().doubleValuesLength());

        Bid rejectedBid = slot.bids(1);
        assertEquals("bid2", rejectedBid.key());
        assertEquals(null, rejectedBid.metrics());
    }
}
