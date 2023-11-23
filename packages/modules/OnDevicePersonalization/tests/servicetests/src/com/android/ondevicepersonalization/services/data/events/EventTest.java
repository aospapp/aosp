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

package com.android.ondevicepersonalization.services.data.events;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class EventTest {
    @Test
    public void testBuilderAndEquals() {
        int type = EventType.B2D.getValue();
        byte[] eventData = "data".getBytes();
        String bidId = "bidId";
        String servicePackageName = "servicePackageName";
        String slotId = "slotId";
        int slotPosition = 1;
        long queryId = 1;
        long timeMillis = 1;
        long eventId = 1;
        long slotIndex = 1;
        Event event1 = new Event.Builder()
                .setType(type)
                .setEventData(eventData)
                .setBidId(bidId)
                .setServicePackageName(servicePackageName)
                .setSlotId(slotId)
                .setSlotPosition(slotPosition)
                .setQueryId(queryId)
                .setTimeMillis(timeMillis)
                .setSlotIndex(slotIndex)
                .setEventId(eventId)
                .build();

        assertEquals(event1.getType(), type);
        assertArrayEquals(event1.getEventData(), eventData);
        assertEquals(event1.getBidId(), bidId);
        assertEquals(event1.getServicePackageName(), servicePackageName);
        assertEquals(event1.getSlotId(), slotId);
        assertEquals(event1.getSlotPosition(), slotPosition);
        assertEquals(event1.getQueryId(), queryId);
        assertEquals(event1.getTimeMillis(), timeMillis);
        assertEquals(event1.getSlotIndex(), slotIndex);
        assertEquals(event1.getEventId(), eventId);

        Event event2 = new Event.Builder(
                eventId, queryId, slotIndex, bidId, servicePackageName, slotPosition,
                type, timeMillis, slotId, eventData)
                .build();
        assertEquals(event1, event2);
        assertEquals(event1.hashCode(), event2.hashCode());
    }

    @Test
    public void testBuildTwiceThrows() {
        int type = EventType.B2D.getValue();
        byte[] eventData = "data".getBytes();
        String bidId = "bidId";
        String servicePackageName = "servicePackageName";
        String slotId = "slotId";
        int slotPosition = 1;
        long queryId = 1;
        long timeMillis = 1;
        long eventId = 1;
        long slotIndex = 1;
        Event.Builder builder = new Event.Builder()
                .setType(type)
                .setEventData(eventData)
                .setBidId(bidId)
                .setServicePackageName(servicePackageName)
                .setSlotId(slotId)
                .setSlotPosition(slotPosition)
                .setQueryId(queryId)
                .setTimeMillis(timeMillis)
                .setSlotIndex(slotIndex)
                .setEventId(eventId);

        builder.build();
        assertThrows(IllegalStateException.class, () -> builder.build());
    }
}
