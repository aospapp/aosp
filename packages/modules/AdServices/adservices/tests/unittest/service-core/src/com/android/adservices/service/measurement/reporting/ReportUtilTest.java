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

package com.android.adservices.service.measurement.reporting;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import android.net.Uri;

import org.json.JSONArray;
import org.junit.Test;

import java.util.List;

public class ReportUtilTest {
    private static final String DESTINATION_1 = "https://destination-1.test";
    private static final String DESTINATION_2 = "https://destination-2.test";
    private static final String DESTINATION_3 = "https://destination-3.test";

    @Test
    public void serializeAttributionDestinations_emptyList_throwsIllegalArgument() {
        assertThrows(
                IllegalArgumentException.class,
                () -> ReportUtil.serializeAttributionDestinations(List.of()));
    }

    @Test
    public void serializeAttributionDestinations_singleDestination_returnsString() {
        List<Uri> destinations = List.of(Uri.parse(DESTINATION_1));
        assertEquals(DESTINATION_1, ReportUtil.serializeAttributionDestinations(destinations));
    }

    @Test
    public void serializeAttributionDestinations_multipleDestinations_returnsOrderedJSONArray() {
        List<Uri> unordered =
                List.of(
                        Uri.parse(DESTINATION_2),
                        Uri.parse(DESTINATION_3),
                        Uri.parse(DESTINATION_1));
        JSONArray expected = new JSONArray(List.of(DESTINATION_1, DESTINATION_2, DESTINATION_3));
        assertEquals(expected, ReportUtil.serializeAttributionDestinations(unordered));
    }
}
