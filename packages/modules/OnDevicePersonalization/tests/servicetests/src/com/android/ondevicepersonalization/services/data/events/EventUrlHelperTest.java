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

package com.android.ondevicepersonalization.services.data.events;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.net.Uri;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.nio.charset.StandardCharsets;

@RunWith(JUnit4.class)
public class EventUrlHelperTest {

    private static final Event TEST_EVENT = new Event.Builder()
            .setType(EventType.B2D.getValue())
            .setEventData("test".getBytes(StandardCharsets.UTF_8))
            .setBidId("bidId")
            .setServicePackageName("packageName")
            .setSlotId("slotId")
            .setSlotPosition(1)
            .setQueryId(1L)
            .setTimeMillis(1L)
            .setSlotIndex(0)
            .build();

    private static final EventUrlPayload TEST_EVENT_URL_PAYLOAD = new EventUrlPayload.Builder()
            .setEvent(TEST_EVENT).build();

    @Test
    public void testEncryptDecryptEvent() throws Exception {
        String url = EventUrlHelper.getEncryptedOdpEventUrl(TEST_EVENT_URL_PAYLOAD);
        Uri uri = Uri.parse(url);
        assertEquals(uri.getScheme(), EventUrlHelper.URI_SCHEME);
        assertEquals(uri.getAuthority(), EventUrlHelper.URI_AUTHORITY);
        assertEquals(uri.getQueryParameterNames().size(), 1);

        EventUrlPayload decryptedEventUrlPayload = EventUrlHelper.getEventFromOdpEventUrl(url);
        assertTrue(TEST_EVENT_URL_PAYLOAD.equals(decryptedEventUrlPayload));
        Event decryptedEvent = decryptedEventUrlPayload.getEvent();
        assertTrue(TEST_EVENT.equals(decryptedEvent));
    }

    @Test
    public void testEncryptDecryptClickTrackingUrlEvent() throws Exception {
        String landingPage = "https://google.com/";
        String url = EventUrlHelper.getEncryptedClickTrackingUrl(TEST_EVENT_URL_PAYLOAD,
                landingPage);
        Uri uri = Uri.parse(url);
        assertEquals(uri.getScheme(), EventUrlHelper.URI_SCHEME);
        assertEquals(uri.getAuthority(), EventUrlHelper.URI_AUTHORITY);
        assertEquals(uri.getQueryParameterNames().size(), 2);

        assertEquals(uri.getQueryParameter(EventUrlHelper.URL_LANDING_PAGE_EVENT_KEY), landingPage);

        EventUrlPayload decryptedEventUrlPayload = EventUrlHelper.getEventFromOdpEventUrl(url);
        assertTrue(TEST_EVENT_URL_PAYLOAD.equals(decryptedEventUrlPayload));
        Event decryptedEvent = decryptedEventUrlPayload.getEvent();
        assertTrue(TEST_EVENT.equals(decryptedEvent));
    }

    @Test
    public void testInvalidUrl() {
        assertThrows(IllegalArgumentException.class,
                () -> EventUrlHelper.getEventFromOdpEventUrl("https://google.com/"));
    }
}
