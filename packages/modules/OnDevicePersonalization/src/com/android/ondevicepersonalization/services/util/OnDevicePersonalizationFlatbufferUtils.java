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

package com.android.ondevicepersonalization.services.util;

import android.ondevicepersonalization.ExecuteOutput;
import android.ondevicepersonalization.SlotResult;

import com.android.ondevicepersonalization.services.fbs.Bid;
import com.android.ondevicepersonalization.services.fbs.EventFields;
import com.android.ondevicepersonalization.services.fbs.Metrics;
import com.android.ondevicepersonalization.services.fbs.Owner;
import com.android.ondevicepersonalization.services.fbs.QueryData;
import com.android.ondevicepersonalization.services.fbs.QueryFields;
import com.android.ondevicepersonalization.services.fbs.Slot;

import com.google.flatbuffers.FlatBufferBuilder;

import java.util.List;

/**
 * Util class to support creation of OnDevicePersonalization flatbuffers
 */
public class OnDevicePersonalizationFlatbufferUtils {
    private OnDevicePersonalizationFlatbufferUtils() {
    }

    /**
     * Creates a byte array representing the QueryData as a flatbuffer
     */
    public static byte[] createQueryData(
            String servicePackageName, String certDigest, ExecuteOutput executeOutput) {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        int ownerOffset = createOwner(builder, servicePackageName, certDigest);
        int slotsOffset = 0;
        if (executeOutput.getSlotResults() != null) {
            // Create slots vector
            List<SlotResult> slotResults = executeOutput.getSlotResults();
            int[] slots = new int[slotResults.size()];
            for (int i = 0; i < slotResults.size(); i++) {
                SlotResult slotResult = slotResults.get(i);
                int slotKeyOffset = builder.createString(slotResult.getSlotKey());

                int bidsOffset = 0;
                if (slotResult.getLoggedBids() != null) {
                    bidsOffset = createBidVector(builder, slotResult.getLoggedBids());
                }

                Slot.startSlot(builder);
                Slot.addKey(builder, slotKeyOffset);
                Slot.addBids(builder, bidsOffset);
                slots[i] = Slot.endSlot(builder);
            }
            slotsOffset = QueryFields.createSlotsVector(builder, slots);
        }
        QueryFields.startQueryFields(builder);
        QueryFields.addOwner(builder, ownerOffset);
        QueryFields.addSlots(builder, slotsOffset);
        int[] queryFieldsOffset = new int[1];
        queryFieldsOffset[0] = QueryFields.endQueryFields(builder);
        int queryFieldsListOffset = QueryData.createQueryFieldsVector(builder, queryFieldsOffset);
        QueryData.startQueryData(builder);
        QueryData.addQueryFields(builder, queryFieldsListOffset);
        int queryDataOffset = QueryData.endQueryData(builder);
        builder.finish(queryDataOffset);
        return builder.sizedByteArray();
    }

    /**
     * Creates a byte array representing the EventData as a flatbuffer
     */
    public static byte[] createEventData(android.ondevicepersonalization.Metrics metrics) {
        FlatBufferBuilder builder = new FlatBufferBuilder();
        int metricsOffset = 0;
        if (metrics != null) {
            metricsOffset = createMetrics(builder, metrics);
        }
        EventFields.startEventFields(builder);
        EventFields.addMetrics(builder, metricsOffset);
        int eventFieldsOffset = EventFields.endEventFields(builder);
        builder.finish(eventFieldsOffset);
        return builder.sizedByteArray();
    }

    private static int createOwner(
            FlatBufferBuilder builder,
            String packageName,
            String certDigest) {
        int packageNameOffset = 0;
        if (packageName != null) {
            packageNameOffset = builder.createString(packageName);
        }
        int certDigestOffset = 0;
        if (certDigest != null) {
            certDigestOffset = builder.createString(certDigest);
        }
        Owner.startOwner(builder);
        Owner.addPackageName(builder, packageNameOffset);
        Owner.addCertDigest(builder, certDigestOffset);
        return Owner.endOwner(builder);
    }

    private static int createBidVector(
            FlatBufferBuilder builder,
            List<android.ondevicepersonalization.Bid> bids) {
        int[] loggedBids = new int[bids.size()];
        for (int i = 0; i < bids.size(); i++) {
            android.ondevicepersonalization.Bid bid = bids.get(i);
            int bidKeyOffset = builder.createString(bid.getKey());
            int metricsOffset = 0;
            if (bid.getMetrics() != null) {
                metricsOffset = createMetrics(builder, bid.getMetrics());
            }
            Bid.startBid(builder);
            Bid.addKey(builder, bidKeyOffset);
            Bid.addMetrics(builder, metricsOffset);
            loggedBids[i] = Bid.endBid(builder);
        }
        return Slot.createBidsVector(builder, loggedBids);
    }

    private static int createMetrics(FlatBufferBuilder builder,
            android.ondevicepersonalization.Metrics metrics) {
        int intValuesOffset = 0;
        if (metrics.getLongValues() != null) {
            intValuesOffset = Metrics.createLongValuesVector(builder, metrics.getLongValues());
        }
        int floatValuesOffset = 0;
        if (metrics.getDoubleValues() != null) {
            floatValuesOffset = Metrics.createDoubleValuesVector(
                    builder, metrics.getDoubleValues());
        }
        int booleanValuesOffset = 0;
        if (metrics.getBooleanValues() != null) {
            booleanValuesOffset = Metrics.createBooleanValuesVector(
                    builder, metrics.getBooleanValues());
        }
        Metrics.startMetrics(builder);
        Metrics.addLongValues(builder, intValuesOffset);
        Metrics.addDoubleValues(builder, floatValuesOffset);
        Metrics.addBooleanValues(builder, booleanValuesOffset);
        return Metrics.endMetrics(builder);
    }
}
