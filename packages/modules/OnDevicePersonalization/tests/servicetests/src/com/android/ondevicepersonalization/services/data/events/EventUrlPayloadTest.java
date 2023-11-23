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

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class EventUrlPayloadTest {
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
        Event event = new Event.Builder()
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

        EventUrlPayload eventUrlPayload1 = new EventUrlPayload.Builder()
                .setEvent(event)
                .build();

        assertEquals(eventUrlPayload1.getEvent(), event);

        EventUrlPayload eventUrlPayload2 = new EventUrlPayload.Builder(
                event).build();
        assertEquals(eventUrlPayload1, eventUrlPayload2);
        assertEquals(eventUrlPayload1.hashCode(), eventUrlPayload2.hashCode());
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
        Event event = new Event.Builder()
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

        EventUrlPayload.Builder builder = new EventUrlPayload.Builder()
                .setEvent(event);
        builder.build();
        assertThrows(IllegalStateException.class, () -> builder.build());
    }
}
