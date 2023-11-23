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

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertThrows;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

public class ReportSpecTest {

    @Test
    public void testEqualsPass() throws JSONException {
        // Assertion
        JSONObject triggerSpecJson = TriggerSpecTest.getValidBaselineTestCase();
        JSONArray triggerSpecsJson = new JSONArray(List.of(triggerSpecJson));

        assertEquals(
                new ReportSpec(triggerSpecsJson, 3, false),
                new ReportSpec(triggerSpecsJson, 3, false));
    }

    @Test
    public void reportSpecsConstructor_maxBucketIncrementsDifferent_equalFail()
            throws JSONException {
        JSONObject triggerSpecJson = TriggerSpecTest.getValidBaselineTestCase();
        JSONArray json1 = new JSONArray(new JSONObject[] {triggerSpecJson});
        JSONArray json2 = new JSONArray(new JSONObject[] {triggerSpecJson});

        // Assertion
        assertNotEquals(new ReportSpec(json1, 3, false), new ReportSpec(json2, 4, false));
    }

    @Test
    public void reportSpecsConstructor_TriggerSpecCountDifferent_equalFail() throws JSONException {
        JSONObject triggerSpecJson = TriggerSpecTest.getValidBaselineTestCase();
        JSONArray json1 = new JSONArray(new JSONObject[] {triggerSpecJson});
        JSONArray json2 = new JSONArray(new JSONObject[] {triggerSpecJson, triggerSpecJson});

        // Assertion
        assertNotEquals(new ReportSpec(json1, 3, false), new ReportSpec(json2, 3, false));
    }

    @Test
    public void reportSpecsConstructor_TriggerSpecContentDifferent_equalFail()
            throws JSONException {
        JSONObject jsonTriggerSpec1 = new JSONObject();
        jsonTriggerSpec1.put("trigger_data", new JSONArray(new int[] {1, 2, 3, 4}));
        JSONObject windows1 = new JSONObject();
        windows1.put("start_time", 0);
        windows1.put("end_times", new JSONArray(new int[] {10000, 20000, 30000, 40000}));
        jsonTriggerSpec1.put("event_report_windows", windows1);
        jsonTriggerSpec1.put("summary_buckets", new JSONArray(new int[] {1, 10, 100}));

        JSONObject jsonTriggerSpec2 = new JSONObject();
        jsonTriggerSpec2.put("trigger_data", new JSONArray(new int[] {1, 2, 3}));
        JSONObject windows2 = new JSONObject();
        windows2.put("start_time", 0);
        windows2.put("end_times", new JSONArray(new int[] {10000, 20000, 30000, 40000}));
        jsonTriggerSpec2.put("event_report_windows", windows2);
        jsonTriggerSpec2.put("summary_buckets", new JSONArray(new int[] {1, 10, 100}));

        // Assertion
        assertNotEquals(
                new ReportSpec(new JSONArray(new JSONObject[] {jsonTriggerSpec1}), 3, false),
                new ReportSpec(new JSONArray(new JSONObject[] {jsonTriggerSpec2}), 3, false));
    }

    @Test
    public void reportSpecsConstructor_completeExpectation_success() throws JSONException {
        JSONObject jsonTriggerSpec = new JSONObject();
        jsonTriggerSpec.put("trigger_data", new JSONArray(new int[] {1, 2, 3, 4}));
        JSONObject windows = new JSONObject();
        windows.put("start_time", 0);
        windows.put("end_times", new JSONArray(new int[] {10000, 20000, 30000, 40000}));
        jsonTriggerSpec.put("event_report_windows", windows);
        jsonTriggerSpec.put("summary_buckets", new JSONArray(new int[] {1, 10, 100}));

        JSONObject jsonTriggerSpec2 = new JSONObject();
        jsonTriggerSpec2.put("trigger_data", new JSONArray(new int[] {5, 6, 7, 8}));
        JSONObject windows2 = new JSONObject();
        windows2.put("start_time", 0);
        windows2.put("end_times", new JSONArray(new int[] {10000, 30000}));
        jsonTriggerSpec2.put("event_report_windows", windows2);
        jsonTriggerSpec2.put("summary_buckets", new JSONArray(new int[] {1, 2, 3}));

        ReportSpec testObject =
                new ReportSpec(
                        new JSONArray(new JSONObject[] {jsonTriggerSpec, jsonTriggerSpec2}),
                        3,
                        false);

        // Assertion
        assertEquals(3, testObject.getMaxReports());
        assertEquals(2, testObject.getTriggerSpecs().length);
        assertEquals(
                new TriggerSpec.Builder(jsonTriggerSpec).build(), testObject.getTriggerSpecs()[0]);
        assertEquals(
                new TriggerSpec.Builder(jsonTriggerSpec2).build(), testObject.getTriggerSpecs()[1]);
    }

    @Test
    public void encodeTriggerSpecsToJSON_equal() throws JSONException {
        JSONObject jsonTriggerSpec = new JSONObject();
        jsonTriggerSpec.put("trigger_data", new JSONArray(new int[] {1, 2, 3, 4}));
        JSONObject windows = new JSONObject();
        windows.put("start_time", 0);
        windows.put("end_times", new JSONArray(new int[] {10000, 20000, 30000, 40000}));
        jsonTriggerSpec.put("event_report_windows", windows);
        jsonTriggerSpec.put("summary_buckets", new JSONArray(new int[] {1, 10, 100}));

        ReportSpec testObject1 =
                new ReportSpec(
                        new JSONArray(new JSONObject[] {jsonTriggerSpec, jsonTriggerSpec}),
                        3,
                        false);

        JSONArray encodedJSON = testObject1.encodeTriggerSpecsToJSON();

        ReportSpec testObject2 = new ReportSpec(encodedJSON, 3, false);
        // Assertion
        assertEquals(testObject1, testObject2);
    }

    @Test
    public void testInvalidCaseDuplicateTriggerData_throws() throws JSONException {
        JSONObject triggerSpecJson = TriggerSpecTest.getValidBaselineTestCase();

        // Assertion
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ReportSpec(
                                new JSONArray(new JSONObject[] {triggerSpecJson, triggerSpecJson}),
                                3,
                                true));
    }

    @Test
    public void validateParameters_testInvalidCaseTotalCardinalityOverLimit_throws()
            throws JSONException {
        JSONObject jsonTriggerSpec1 = new JSONObject();
        jsonTriggerSpec1.put("trigger_data", new JSONArray(new int[] {0, 1, 2, 3, 4}));
        JSONObject windows = new JSONObject();
        windows.put("start_time", 0);
        windows.put("end_times", new JSONArray(new int[] {1}));
        jsonTriggerSpec1.put("event_report_windows", windows);
        jsonTriggerSpec1.put("summary_buckets", new JSONArray(new int[] {1}));
        JSONObject jsonTriggerSpec2 = new JSONObject();
        jsonTriggerSpec2.put("trigger_data", new JSONArray(new int[] {5, 6, 7, 8, 9}));
        jsonTriggerSpec2.put("event_report_windows", windows);
        jsonTriggerSpec2.put("summary_buckets", new JSONArray(new int[] {1}));

        // Assertion
        assertThrows(
                IllegalArgumentException.class,
                () ->
                        new ReportSpec(
                                new JSONArray(
                                        new JSONObject[] {jsonTriggerSpec1, jsonTriggerSpec2}),
                                3,
                                true));
    }

    @Test
    public void testInvalidCaseTotalNumberReportOverLimit_throws() throws JSONException {
        JSONObject jsonTriggerSpec1 = new JSONObject();
        jsonTriggerSpec1.put("trigger_data", new JSONArray(new int[] {0, 1, 2, 3, 4}));
        JSONObject windows = new JSONObject();
        windows.put("start_time", 0);
        windows.put("end_times", new JSONArray(new int[] {1}));
        jsonTriggerSpec1.put("event_report_windows", windows);
        jsonTriggerSpec1.put("summary_buckets", new JSONArray(new int[] {1, 2, 3, 4, 5, 6}));

        // Assertion
        assertThrows(
                IllegalArgumentException.class,
                () -> new ReportSpec(new JSONArray(new JSONObject[] {jsonTriggerSpec1}), 21, true));
    }

    private JSONObject getTestJSONObjectTriggerSpec_4_3_2() throws JSONException {
        JSONObject jsonTriggerSpec = new JSONObject();
        jsonTriggerSpec.put("trigger_data", new JSONArray(new int[] {1, 2, 3, 4}));
        JSONObject windows = new JSONObject();
        windows.put("start_time", 0);
        windows.put("end_times", new JSONArray(new int[] {10000, 20000, 30000}));
        jsonTriggerSpec.put("event_report_windows", windows);
        jsonTriggerSpec.put("summary_buckets", new JSONArray(new int[] {1, 2}));
        return jsonTriggerSpec;
    }

    @Test
    public void getPrivacyParamsForComputation_equal() throws JSONException {
        JSONObject jsonTriggerSpec = getTestJSONObjectTriggerSpec_4_3_2();

        ReportSpec testObject1 =
                new ReportSpec(new JSONArray(new JSONObject[] {jsonTriggerSpec}), 3, true);
        // Assertion
        assertEquals(3, testObject1.getPrivacyParamsForComputation()[0][0]);
        assertArrayEquals(new int[] {3, 3, 3, 3}, testObject1.getPrivacyParamsForComputation()[1]);
        assertArrayEquals(new int[] {2, 2, 2, 2}, testObject1.getPrivacyParamsForComputation()[2]);
    }

    @Test
    public void getNumberState_equal() throws JSONException {
        JSONObject jsonTriggerSpec = getTestJSONObjectTriggerSpec_4_3_2();

        ReportSpec testObject1 =
                new ReportSpec(new JSONArray(new JSONObject[] {jsonTriggerSpec}), 3, true);
        // Assertion
        assertEquals(
                415,
                testObject1.getNumberState()); // Privacy parameter is {3, {3,3,3,3}, {2,2,2,2}}
    }

    @Test
    public void getTriggerDataValue_equal() throws JSONException {
        JSONObject jsonTriggerSpec1 = getTestJSONObjectTriggerSpec_4_3_2();

        JSONObject jsonTriggerSpec2 = new JSONObject();
        jsonTriggerSpec2.put("trigger_data", new JSONArray(new int[] {5, 6, 7}));
        JSONObject windows2 = new JSONObject();
        windows2.put("start_time", 0);
        windows2.put("end_times", new JSONArray(new int[] {15000}));
        jsonTriggerSpec2.put("event_report_windows", windows2);
        jsonTriggerSpec2.put("summary_buckets", new JSONArray(new int[] {1, 2}));

        ReportSpec testObject1 =
                new ReportSpec(
                        new JSONArray(new JSONObject[] {jsonTriggerSpec1, jsonTriggerSpec2}),
                        3,
                        true);
        // Assertion
        assertEquals(1, testObject1.getTriggerDataValue(0));
        assertEquals(3, testObject1.getTriggerDataValue(2));
        assertEquals(5, testObject1.getTriggerDataValue(4));
        assertEquals(7, testObject1.getTriggerDataValue(6));
    }

    @Test
    public void getWindowEndTime_equal() throws JSONException {
        JSONObject jsonTriggerSpec1 = getTestJSONObjectTriggerSpec_4_3_2();

        JSONObject jsonTriggerSpec2 = new JSONObject();
        jsonTriggerSpec2.put("trigger_data", new JSONArray(new int[] {5, 6, 7}));
        JSONObject windows2 = new JSONObject();
        windows2.put("start_time", 0);
        windows2.put("end_times", new JSONArray(new int[] {15000}));
        jsonTriggerSpec2.put("event_report_windows", windows2);
        jsonTriggerSpec2.put("summary_buckets", new JSONArray(new int[] {1, 2}));

        ReportSpec testObject1 =
                new ReportSpec(
                        new JSONArray(new JSONObject[] {jsonTriggerSpec1, jsonTriggerSpec2}),
                        3,
                        true);
        // Assertion
        assertEquals(10000, testObject1.getWindowEndTime(0, 0));
        assertEquals(20000, testObject1.getWindowEndTime(0, 1));
        assertEquals(10000, testObject1.getWindowEndTime(1, 0));
        assertEquals(15000, testObject1.getWindowEndTime(4, 0));
    }
}
