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

package com.android.adservices.service.measurement.util;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.android.adservices.service.measurement.EventSurfaceType;
import com.android.adservices.service.measurement.Source;
import com.android.adservices.service.measurement.SourceFixture;
import com.android.adservices.service.measurement.Trigger;
import com.android.adservices.service.measurement.TriggerFixture;

import org.junit.Test;

public class DebugTest {
    @Test
    public void isAttributionDebugReportPermitted_webWebKeysNotNull_permitted() {
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setPublisherType(EventSurfaceType.WEB)
                        .build();

        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setDestinationType(EventSurfaceType.WEB)
                        .build();

        assertTrue(Debug.isAttributionDebugReportPermitted(source, trigger,
                new UnsignedLong(1L), new UnsignedLong(2L)));
    }

    @Test
    public void isAttributionDebugReportPermitted_webWebKeysNullKey_disallowed() {
        Source source =
                SourceFixture.getValidSourceBuilder()
                        .setPublisherType(EventSurfaceType.WEB)
                        .build();

        Trigger trigger =
                TriggerFixture.getValidTriggerBuilder()
                        .setDestinationType(EventSurfaceType.WEB)
                        .build();

        assertFalse(Debug.isAttributionDebugReportPermitted(source, trigger,
                null, new UnsignedLong(2L)));

        assertFalse(Debug.isAttributionDebugReportPermitted(source, trigger,
                new UnsignedLong(2L), null));
    }

    @Test
    public void isAttributionDebugReportPermitted_nonWebWebBothKeysNull_disallowed() {
        Source sourceWeb =
                SourceFixture.getValidSourceBuilder()
                        .setPublisherType(EventSurfaceType.WEB)
                        .build();

        Source sourceApp =
                SourceFixture.getValidSourceBuilder()
                        .setPublisherType(EventSurfaceType.APP)
                        .build();

        Trigger triggerWeb =
                TriggerFixture.getValidTriggerBuilder()
                        .setDestinationType(EventSurfaceType.WEB)
                        .build();

        Trigger triggerApp =
                TriggerFixture.getValidTriggerBuilder()
                        .setDestinationType(EventSurfaceType.APP)
                        .build();

        assertFalse(Debug.isAttributionDebugReportPermitted(sourceWeb, triggerApp,
                null, null));

        assertFalse(Debug.isAttributionDebugReportPermitted(sourceApp, triggerWeb,
                null, null));
    }

    @Test
    public void isAttributionDebugReportPermitted_nonWebWeb_permitted() {
        UnsignedLong[] keys = new UnsignedLong[] {
                null,
                new UnsignedLong(1L),
                new UnsignedLong(2L)
        };
        @EventSurfaceType int[] surfaceTypes = new int[] {
                EventSurfaceType.APP,
                EventSurfaceType.WEB
        };

        for (int i = 0; i < keys.length; i++) {
            for (int j = 0; j < surfaceTypes.length; j++) {
                Source source =
                        SourceFixture.getValidSourceBuilder()
                                .setPublisherType(surfaceTypes[j])
                                .build();

                Trigger trigger =
                        TriggerFixture.getValidTriggerBuilder()
                                .setDestinationType(
                                        surfaceTypes[(j + 1) % surfaceTypes.length])
                                .build();

                assertTrue(Debug.isAttributionDebugReportPermitted(source, trigger,
                        keys[i], keys[(i + 1) % keys.length]));
            }
        }
    }

}
