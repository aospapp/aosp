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

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.android.ondevicepersonalization.services.data.OnDevicePersonalizationDbHelper;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

import java.nio.charset.StandardCharsets;

@RunWith(JUnit4.class)
public class EventsDaoTest {
    private final Context mContext = ApplicationProvider.getApplicationContext();
    private EventsDao mDao;
    private Event mTestEvent = new Event.Builder()
            .setType(EventType.B2D.getValue())
            .setEventData("event".getBytes(StandardCharsets.UTF_8))
            .setBidId("bidId")
            .setServicePackageName("servicePackageName")
            .setSlotId("slotId")
            .setSlotPosition(1)
            .setQueryId(1L)
            .setTimeMillis(1L)
            .setSlotIndex(0)
            .build();

    private Query mTestQuery = new Query.Builder()
            .setTimeMillis(1L)
            .setServicePackageName("servicePackageName")
            .setQueryData("query".getBytes(StandardCharsets.UTF_8))
            .build();

    @Before
    public void setup() {
        mDao = EventsDao.getInstanceForTest(mContext);
    }

    @After
    public void cleanup() {
        OnDevicePersonalizationDbHelper dbHelper =
                OnDevicePersonalizationDbHelper.getInstanceForTest(mContext);
        dbHelper.getWritableDatabase().close();
        dbHelper.getReadableDatabase().close();
        dbHelper.close();
    }

    @Test
    public void testInsertQueryAndEvent() {
        assertEquals(1, mDao.insertQuery(mTestQuery));
        assertEquals(1, mDao.insertEvent(mTestEvent));
        Event testEvent = new Event.Builder()
                .setType(EventType.CLICK.getValue())
                .setEventData("event".getBytes(StandardCharsets.UTF_8))
                .setBidId("bidId")
                .setServicePackageName("servicePackageName")
                .setSlotId("slotId")
                .setSlotPosition(1)
                .setQueryId(1L)
                .setTimeMillis(1L)
                .setSlotIndex(0)
                .build();
        assertEquals(2, mDao.insertEvent(testEvent));
    }

    @Test
    public void testInsertEventFKError() {
        assertEquals(-1, mDao.insertEvent(mTestEvent));
    }

    @Test
    public void testInsertQueryId() {
        assertEquals(1, mDao.insertQuery(mTestQuery));
        assertEquals(2, mDao.insertQuery(mTestQuery));
    }

    @Test
    public void testInsertEventExistingKey() {
        assertEquals(1, mDao.insertQuery(mTestQuery));
        assertEquals(1, mDao.insertEvent(mTestEvent));
        assertEquals(-1, mDao.insertEvent(mTestEvent));
    }

}
