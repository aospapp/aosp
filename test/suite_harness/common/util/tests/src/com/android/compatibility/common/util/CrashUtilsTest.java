/*
 * Copyright (C) 2019 The Android Open Source Project
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

package com.android.compatibility.common.util;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.JUnit4;

/** Unit tests for {@link CrashUtils}. */
@RunWith(JUnit4.class)
public class CrashUtilsTest {

    private JSONArray mCrashes;

    @Before
    public void setUp() throws IOException {
        try (BufferedReader txtReader =
                new BufferedReader(
                        new InputStreamReader(
                                getClass().getClassLoader().getResourceAsStream("logcat.txt")))) {
            StringBuffer input = new StringBuffer();
            String tmp;
            while ((tmp = txtReader.readLine()) != null) {
                input.append(tmp + "\n");
            }
            mCrashes = CrashUtils.addAllCrashes(input.toString(), new JSONArray());
        }
    }

    @Test
    public void testGetAllCrashes() throws Exception {
        JSONArray expectedResults = new JSONArray();
        expectedResults.put(createCrashJson(11071, 11189, "AudioOut_D", 3912761344L, "SIGSEGV"));
        expectedResults.put(createCrashJson(12736, 12761, "Binder:12736_2", 0L, "SIGSEGV"));
        expectedResults.put(createCrashJson(26201, 26227, "Binder:26201_3", 0L, "SIGSEGV"));
        expectedResults.put(createCrashJson(26246, 26282, "Binder:26246_5", 0L, "SIGSEGV"));
        expectedResults.put(createCrashJson(245, 245, "installd", null, "SIGABRT"));
        expectedResults.put(createCrashJson(6371, 8072, "media.codec", 3976200192L, "SIGSEGV"));
        expectedResults.put(createCrashJson(8373, 8414, "loo", null, "SIGABRT"));

        Assert.assertEquals(mCrashes.toString(), expectedResults.toString());
    }

    public JSONObject createCrashJson(
            int pid, int tid, String name, Long faultaddress, String signal) {
        JSONObject json = new JSONObject();
        try {
            json.put(CrashUtils.PID, pid);
            json.put(CrashUtils.TID, tid);
            json.put(CrashUtils.NAME, name);
            json.put(CrashUtils.FAULT_ADDRESS, faultaddress);
            json.put(CrashUtils.SIGNAL, signal);
        } catch (JSONException e) {

        }
        return json;
    }

    @Test
    public void testValidCrash() throws Exception {
        Assert.assertTrue(CrashUtils.detectCrash(new String[] {"AudioOut_D"}, true, mCrashes));
    }

    @Test
    public void testMissingName() throws Exception {
        Assert.assertFalse(CrashUtils.detectCrash(new String[] {""}, true, mCrashes));
    }

    @Test
    public void testSIGABRT() throws Exception {
        Assert.assertFalse(CrashUtils.detectCrash(new String[] {"installd"}, true, mCrashes));
    }

    @Test
    public void testFaultAddressBelowMin() throws Exception {
        Assert.assertFalse(CrashUtils.detectCrash(new String[] {"Binder:12736_2"}, true, mCrashes));
    }

    @Test
    public void testIgnoreMinAddressCheck() throws Exception {
        Assert.assertTrue(CrashUtils.detectCrash(new String[] {"Binder:12736_2"}, false, mCrashes));
    }

    @Test
    public void testGoodAndBadCrashes() throws Exception {
        Assert.assertTrue(
                CrashUtils.detectCrash(new String[] {"AudioOut_D", "generic"}, true, mCrashes));
    }

    @Test
    public void testNullFaultAddress() throws Exception {
        JSONArray crashes = new JSONArray();
        crashes.put(createCrashJson(8373, 8414, "loo", null, "SIGSEGV"));
        Assert.assertTrue(CrashUtils.detectCrash(new String[] {"loo"}, true, crashes));
    }
}
