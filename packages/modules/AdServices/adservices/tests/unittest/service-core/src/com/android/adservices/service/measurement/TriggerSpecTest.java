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

package com.android.adservices.service.measurement;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/** Unit tests for {@link TriggerSpec} */
public class TriggerSpecTest {
    public static JSONObject getJson(
            int[] triggerData,
            int eventReportWindowsStart,
            long[] eventReportWindowsEnd,
            String summaryWindowOperator,
            int[] summaryBucket)
            throws JSONException {
        JSONObject json = new JSONObject();
        if (triggerData != null) {
            json.put("trigger_data", new JSONArray(triggerData));
        }
        JSONObject windows = new JSONObject();
        if (eventReportWindowsStart != 0) {
            windows.put("start_time", eventReportWindowsStart);
        }
        if (eventReportWindowsEnd != null) {
            windows.put("end_times", new JSONArray(eventReportWindowsEnd));
            json.put("event_report_windows", windows);
        }
        if (summaryWindowOperator != null) {
            json.put("summary_window_operator", summaryWindowOperator);
        }
        if (summaryBucket != null) {
            json.put("summary_buckets", new JSONArray(summaryBucket));
        }
        return json;
    }

    public static JSONObject getValidBaselineTestCase() throws JSONException {
        int[] triggerData = {1, 2, 3};
        int eventReportWindowsStart = 0;
        long[] eventReportWindowsEnd = {
            TimeUnit.DAYS.toMillis(2), TimeUnit.DAYS.toMillis(7), TimeUnit.DAYS.toMillis(30)
        };
        String summaryWindowOperator = "count";
        int[] summaryBucket = {1, 2, 3, 4};
        return getJson(
                triggerData,
                eventReportWindowsStart,
                eventReportWindowsEnd,
                summaryWindowOperator,
                summaryBucket);
    }

    @Test
    public void testEqualsPass() throws JSONException {
        // Assertion
        assertEquals(
                new TriggerSpec.Builder(getValidBaselineTestCase()).build(),
                new TriggerSpec.Builder(getValidBaselineTestCase()).build());
    }

    @Test
    public void testEqualsWithDefaultValuePass() throws JSONException {
        // Assertion
        JSONObject json = getValidBaselineTestCase();
        json.remove("summary_window_operator");
        assertEquals(
                new TriggerSpec.Builder(json).build(),
                new TriggerSpec.Builder(getValidBaselineTestCase()).build());
    }

    @Test
    public void testEqualsFail() throws JSONException {

        // Assertion
        assertNotEquals(
                new TriggerSpec.Builder(
                                getValidBaselineTestCase()
                                        .put("trigger_data", new JSONArray(new int[] {1, 2, 3, 4})))
                        .build(),
                new TriggerSpec.Builder(
                                getValidBaselineTestCase()
                                        .put("trigger_data", new JSONArray(new int[] {1, 2, 3})))
                        .build());
        assertNotEquals(
                new TriggerSpec.Builder(
                                getValidBaselineTestCase()
                                        .put(
                                                "summary_window_operator",
                                                TriggerSpec.SummaryOperatorType.VALUE_SUM))
                        .build(),
                new TriggerSpec.Builder(getValidBaselineTestCase()).build());
    }

    @Test
    public void testJSONEncodingDecoding() throws JSONException {
        // Setup

        JSONObject JSONInput = getValidBaselineTestCase();

        // Execution
        TriggerSpec o1 = new TriggerSpec.Builder(JSONInput).build();

        JSONObject JSONOutput = o1.encodeJSON();
        TriggerSpec o2 = new TriggerSpec.Builder(JSONOutput).build();

        // Assertion
        assertEquals(o1, o2);
        assertEquals(o1.hashCode(), o2.hashCode());
    }

    @Test
    public void triggerSpecBuilder_invalidSummaryOperator_throws() throws JSONException {
        // Setup
        int[] triggerData = {1, 2, 3};
        int eventReportWindowsStart = 0;
        long[] eventReportWindowsEnd = {
            TimeUnit.DAYS.toMillis(2), TimeUnit.DAYS.toMillis(7), TimeUnit.DAYS.toMillis(30)
        };
        String summaryWindowOperator = "count_typo";
        int[] summaryBucket = {1, 2, 3, 4};
        JSONObject JSONInput =
                getJson(
                        triggerData,
                        eventReportWindowsStart,
                        eventReportWindowsEnd,
                        summaryWindowOperator,
                        summaryBucket);
        // Assertion
        assertThrows(
                IllegalArgumentException.class, () -> new TriggerSpec.Builder(JSONInput).build());
    }

    @Test
    public void triggerSpecBuilder_missingFields_throws() throws JSONException {
        // Setup
        int[] triggerData = {1, 2, 3};
        int eventReportWindowsStart = 0;
        long[] eventReportWindowsEnd = {
            TimeUnit.DAYS.toMillis(2), TimeUnit.DAYS.toMillis(7), TimeUnit.DAYS.toMillis(30)
        };
        String summaryWindowOperator = "count";
        int[] summaryBucket = {1, 2, 3, 4};
        JSONObject JSONInput =
                getJson(
                        triggerData,
                        eventReportWindowsStart,
                        eventReportWindowsEnd,
                        summaryWindowOperator,
                        summaryBucket);
        JSONInput.remove("event_report_windows");

        // Assertion
        assertThrows(JSONException.class, () -> new TriggerSpec.Builder(JSONInput).build());
    }

    @Test
    public void triggerSpecBuilder_validJson_success() throws JSONException {
        // Setup
        int[] triggerData = {1, 2, 3};
        int eventReportWindowsStart = 0;
        long[] eventReportWindowsEnd = {
            TimeUnit.DAYS.toMillis(2), TimeUnit.DAYS.toMillis(7), TimeUnit.DAYS.toMillis(30)
        };
        String summaryWindowOperator = "count";
        int[] summaryBucket = {1, 2, 3, 4};
        JSONObject json =
                getJson(
                        triggerData,
                        eventReportWindowsStart,
                        eventReportWindowsEnd,
                        summaryWindowOperator,
                        summaryBucket);

        // Execution
        TriggerSpec testObject = new TriggerSpec.Builder(json).build();

        // Assertion
        List<Integer> expectedTriggerData = Arrays.asList(1, 2, 3);
        assertEquals(expectedTriggerData, testObject.getTriggerData());
        assertEquals(eventReportWindowsStart, testObject.getEventReportWindowsStart());
        List<Long> expectedEventReportWindowsEnd =
                Arrays.asList(
                        TimeUnit.DAYS.toMillis(2),
                        TimeUnit.DAYS.toMillis(7),
                        TimeUnit.DAYS.toMillis(30));
        assertEquals(expectedEventReportWindowsEnd, testObject.getEventReportWindowsEnd());
        assertEquals(
                testObject.getSummaryWindowOperator().name().toLowerCase(), summaryWindowOperator);
        List<Integer> expectedSummaryBuckets = Arrays.asList(1, 2, 3, 4);
        assertEquals(expectedSummaryBuckets, testObject.getSummaryBucket());
    }

    @Test
    public void triggerSpecBuilder_nonStrictIncreaseSummaryBuckets_throws() throws JSONException {
        // Setup
        int[] triggerData = {1, 2, 3};
        int eventReportWindowsStart = 0;
        long[] eventReportWindowsEnd = {
            TimeUnit.DAYS.toMillis(2), TimeUnit.DAYS.toMillis(7), TimeUnit.DAYS.toMillis(30)
        };
        String summaryWindowOperator = "count";
        int[] summaryBucket = {1, 2, 2, 4};
        JSONObject JSONInput =
                getJson(
                        triggerData,
                        eventReportWindowsStart,
                        eventReportWindowsEnd,
                        summaryWindowOperator,
                        summaryBucket);

        // Assertion
        assertThrows(
                IllegalArgumentException.class, () -> new TriggerSpec.Builder(JSONInput).build());
    }

    @Test
    public void triggerSpecBuilder_nonStrictIncreaseReportWindowEnd_throws() throws JSONException {
        // Setup
        int[] triggerData = {1, 2, 3};
        int eventReportWindowsStart = 0;
        long[] eventReportWindowsEnd = {
            TimeUnit.DAYS.toMillis(2), TimeUnit.DAYS.toMillis(2), TimeUnit.DAYS.toMillis(30)
        };
        String summaryWindowOperator = "count";
        int[] summaryBucket = {1, 2, 2, 4};
        JSONObject JSONInput =
                getJson(
                        triggerData,
                        eventReportWindowsStart,
                        eventReportWindowsEnd,
                        summaryWindowOperator,
                        summaryBucket);

        // Assertion
        assertThrows(
                IllegalArgumentException.class, () -> new TriggerSpec.Builder(JSONInput).build());
    }

    @Test
    public void triggerSpecBuilder_exceedReportDataCardinalityLimitation_throws()
            throws JSONException {
        // Setup
        int[] triggerData = new int[PrivacyParams.getMaxFlexibleEventTriggerDataCardinality() + 1];
        for (int i = 0; i < triggerData.length; i++) {
            triggerData[i] = i;
        }
        int eventReportWindowsStart = 0;
        long[] eventReportWindowsEnd = {TimeUnit.DAYS.toMillis(2)};
        String summaryWindowOperator = "count";
        int[] summaryBucket = {1};
        JSONObject JSONInput =
                getJson(
                        triggerData,
                        eventReportWindowsStart,
                        eventReportWindowsEnd,
                        summaryWindowOperator,
                        summaryBucket);

        // Assertion
        assertThrows(
                IllegalArgumentException.class, () -> new TriggerSpec.Builder(JSONInput).build());
    }

    @Test
    public void triggerSpecBuilder_exceedReportWindowLimitation_throws() throws JSONException {
        // Setup
        int[] triggerData = {1};
        int eventReportWindowsStart = 0;
        long[] eventReportWindowsEnd =
                new long[PrivacyParams.getMaxFlexibleEventReportingWindows() + 1];
        for (int i = 0; i < eventReportWindowsEnd.length; i++) {
            eventReportWindowsEnd[i] = TimeUnit.DAYS.toMillis(i + 1);
        }
        String summaryWindowOperator = "count";
        int[] summaryBucket = {1};
        JSONObject JSONInput =
                getJson(
                        triggerData,
                        eventReportWindowsStart,
                        eventReportWindowsEnd,
                        summaryWindowOperator,
                        summaryBucket);

        // Assertion
        assertThrows(
                IllegalArgumentException.class, () -> new TriggerSpec.Builder(JSONInput).build());
    }

    @Test
    public void triggerSpecBuilder_autoCorrectionNegStartTime_equal() throws JSONException {
        // Setup
        int[] triggerData = {1, 2, 3, 4, 5, 6, 7, 8};
        int eventReportWindowsStart = -1;
        long[] eventReportWindowsEnd = {TimeUnit.DAYS.toMillis(2)};
        String summaryWindowOperator = "count";
        int[] summaryBucket = {1};
        JSONObject JSONInput =
                getJson(
                        triggerData,
                        eventReportWindowsStart,
                        eventReportWindowsEnd,
                        summaryWindowOperator,
                        summaryBucket);

        // Assertion
        assertEquals(0, new TriggerSpec.Builder(JSONInput).build().getEventReportWindowsStart());
    }
}
