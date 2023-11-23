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
import java.util.regex.Pattern;

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
        expectedResults.put(createCrashJson(
                11071, 11189, "AudioOut_D", "/system/bin/audioserver", "e9380000", "SIGSEGV"));
        expectedResults.put(createCrashJson(
                12736, 12761, "Binder:12736_2", "/system/bin/audioserver", "0", "SIGSEGV"));
        expectedResults.put(createCrashJson(
                26201, 26227, "Binder:26201_3", "/system/bin/audioserver", "0", "SIGSEGV"));
        expectedResults.put(createCrashJson(
                26246, 26282, "Binder:26246_5", "/system/bin/audioserver", "0", "SIGSEGV"));
        expectedResults.put(createCrashJson(
                245, 245, "installd", "/system/bin/installd", null, "SIGABRT"));
        expectedResults.put(createCrashJson(
                6371, 8072, "media.codec", "omx@1.0-service", "ed000000", "SIGSEGV"));
        expectedResults.put(createCrashJson(
                8373, 8414, "loo", "com.android.bluetooth", null, "SIGABRT"));
        expectedResults.put(createCrashJson(
                11071, 11189, "synthetic_thread", "synthetic_process_0", "e9380000", "SIGSEGV"));
        expectedResults.put(createCrashJson(
                12736, 12761, "synthetic_thread", "synthetic_process_1", "0", "SIGSEGV"));

        Assert.assertEquals(mCrashes.toString(), expectedResults.toString());
    }

    public JSONObject createCrashJson(
            int pid, int tid, String name, String process, String faultaddress, String signal) {
        JSONObject json = new JSONObject();
        try {
            json.put(CrashUtils.PID, pid);
            json.put(CrashUtils.TID, tid);
            json.put(CrashUtils.NAME, name);
            json.put(CrashUtils.PROCESS, process);
            json.put(CrashUtils.FAULT_ADDRESS, faultaddress);
            json.put(CrashUtils.SIGNAL, signal);
        } catch (JSONException e) {}
        return json;
    }

    @Test
    public void testValidCrash() throws Exception {
        Assert.assertTrue(CrashUtils.securityCrashDetected(mCrashes, new CrashUtils.Config()
                .checkMinAddress(true)
                .setProcessPatterns(Pattern.compile("synthetic_process_0"))));
    }

    @Test
    public void testMissingName() throws Exception {
        Assert.assertFalse(CrashUtils.securityCrashDetected(mCrashes, new CrashUtils.Config()
                .checkMinAddress(true)
                .setProcessPatterns(Pattern.compile(""))));
    }

    @Test
    public void testSIGABRT() throws Exception {
        Assert.assertFalse(CrashUtils.securityCrashDetected(mCrashes, new CrashUtils.Config()
                .checkMinAddress(true)
                .setProcessPatterns(Pattern.compile("installd"))));
    }

    @Test
    public void testFaultAddressBelowMin() throws Exception {
        Assert.assertFalse(CrashUtils.securityCrashDetected(mCrashes, new CrashUtils.Config()
                .checkMinAddress(true)
                .setProcessPatterns(Pattern.compile("synthetic_process_1"))));
    }

    @Test
    public void testIgnoreMinAddressCheck() throws Exception {
        Assert.assertTrue(CrashUtils.securityCrashDetected(mCrashes, new CrashUtils.Config()
                .checkMinAddress(false)
                .setProcessPatterns(Pattern.compile("synthetic_process_1"))));
    }

    @Test
    public void testBadAbortMessage() throws Exception {
        Assert.assertFalse(CrashUtils.securityCrashDetected(mCrashes, new CrashUtils.Config()
                .checkMinAddress(true)
                .setProcessPatterns(Pattern.compile("generic"))));
    }

    @Test
    public void testGoodAndBadCrashes() throws Exception {
        Assert.assertTrue(CrashUtils.securityCrashDetected(mCrashes, new CrashUtils.Config()
                .checkMinAddress(true)
                .setProcessPatterns(
                        Pattern.compile("synthetic_process_0"),
                        Pattern.compile("generic"))));
    }

    @Test
    public void testNullFaultAddress() throws Exception {
        JSONArray crashes = new JSONArray();
        crashes.put(createCrashJson(8373, 8414, "loo", "com.android.bluetooth", null, "SIGSEGV"));
        Assert.assertTrue(CrashUtils.securityCrashDetected(crashes, new CrashUtils.Config()
                .checkMinAddress(true)
                .setProcessPatterns(Pattern.compile("com\\.android\\.bluetooth"))));
    }
}
