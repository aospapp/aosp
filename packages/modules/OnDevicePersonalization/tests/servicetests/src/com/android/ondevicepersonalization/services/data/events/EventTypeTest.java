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

import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

@RunWith(JUnit4.class)
public class EventTypeTest {
    @Test
    public void testGetValue() {
        assertEquals(EventType.B2D.getValue(), 1);
        assertEquals(EventType.B2R.getValue(), 2);
        assertEquals(EventType.CLICK.getValue(), 3);
        assertEquals(EventType.VIEWABILITY.getValue(), 4);
        assertEquals(EventType.CONVERSIONS.getValue(), 5);
        assertEquals(EventType.OTHER.getValue(), 6);
    }
}
