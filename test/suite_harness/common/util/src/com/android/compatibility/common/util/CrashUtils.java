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

import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Contains helper functions and shared constants for crash parsing. */
public class CrashUtils {
    // used to only detect actual addresses instead of nullptr and other unlikely values
    public static final long MIN_CRASH_ADDR = 0x8000;
    // Matches the end of a crash
    public static final Pattern sEndofCrashPattern =
            Pattern.compile("DEBUG\\s+?:\\s+?backtrace:");
    public static final String DEVICE_PATH = "/data/local/tmp/CrashParserResults/";
    public static final String LOCK_FILENAME = "lockFile.loc";
    public static final String UPLOAD_REQUEST = "Please upload a result file to stagefright";
    public static final Pattern sUploadRequestPattern =
            Pattern.compile(UPLOAD_REQUEST);
    public static final String NEW_TEST_ALERT = "New test starting with name: ";
    public static final Pattern sNewTestPattern =
            Pattern.compile(NEW_TEST_ALERT + "(\\w+?)\\(.*?\\)");
    public static final String SIGNAL = "signal",
            NAME = "name",
            PID = "pid",
            TID = "tid",
            FAULT_ADDRESS = "faultaddress";
    // Matches the smallest blob that has the appropriate header and footer
    private static final Pattern sCrashBlobPattern =
            Pattern.compile("DEBUG\\s+?:( [*]{3})+?.*?DEBUG\\s+?:\\s+?backtrace:", Pattern.DOTALL);
    // Matches process id and name line and captures them
    private static final Pattern sPidtidNamePattern =
            Pattern.compile("pid: (\\d+?), tid: (\\d+?), name: ([^\\s]+?\\s+?)*?>>> (.*?) <<<");
    // Matches fault address and signal type line
    private static final Pattern sFaultLinePattern =
            Pattern.compile(
                    "\\w+? \\d+? \\((.*?)\\), code -*?\\d+? \\(.*?\\), fault addr "
                            + "(?:0x(\\p{XDigit}+)|-+)");
    // Matches the abort message line if it contains CHECK_
    private static Pattern sAbortMessageCheckPattern =
            Pattern.compile("(?i)Abort message.*?CHECK_");

    /**
     * Determines if the given input has a {@link com.android.compatibility.common.util.Crash} that
     * should fail an sts test
     *
     * @param processNames list of applicable process names
     * @param checkMinAddr if the minimum fault address should be respected
     * @param crashes list of crashes to check
     * @return if a crash is serious enough to fail an sts test
     */
    public static boolean detectCrash(
            String[] processNames, boolean checkMinAddr, JSONArray crashes) {
        for (int i = 0; i < crashes.length(); i++) {
            try {
                JSONObject crash = crashes.getJSONObject(i);
                if (!crash.getString(SIGNAL).toLowerCase().matches("sig(segv|bus)")) {
                    continue;
                }

                if (checkMinAddr && !crash.isNull(FAULT_ADDRESS)) {
                    if (crash.getLong(FAULT_ADDRESS) < MIN_CRASH_ADDR) {
                        continue;
                    }
                }

                boolean foundProcess = false;
                String name = crash.getString(NAME);
                for (String process : processNames) {
                    if (name.equals(process)) {
                        foundProcess = true;
                        break;
                    }
                }

                if (!foundProcess) {
                    continue;
                }

                return true; // crash detected
            } catch (JSONException | NullPointerException e) {
            }
        }

        return false;
    }

    /** Adds all crashes found in the input as JSONObjects to the given JSONArray */
    public static JSONArray addAllCrashes(String input, JSONArray crashes) {
        Matcher crashBlobFinder = sCrashBlobPattern.matcher(input);
        while (crashBlobFinder.find()) {
            String crashStr = crashBlobFinder.group(0);
            int tid = 0, pid = 0;
            Long faultAddress = null;
            String name = null, signal = null;

            Matcher pidtidNameMatcher = sPidtidNamePattern.matcher(crashStr);
            if (pidtidNameMatcher.find()) {
                try {
                    pid = Integer.parseInt(pidtidNameMatcher.group(1));
                } catch (NumberFormatException e) {
                }
                try {
                    tid = Integer.parseInt(pidtidNameMatcher.group(2));
                } catch (NumberFormatException e) {
                }
                name = pidtidNameMatcher.group(3).trim();
            }

            Matcher faultLineMatcher = sFaultLinePattern.matcher(crashStr);
            if (faultLineMatcher.find()) {
                signal = faultLineMatcher.group(1);
                String faultAddrMatch = faultLineMatcher.group(2);
                if (faultAddrMatch != null) {
                    try {
                        faultAddress = Long.parseLong(faultAddrMatch, 16);
                    } catch (NumberFormatException e) {
                    }
                }
            }
            if (!sAbortMessageCheckPattern.matcher(crashStr).find()) {
                try {
                    JSONObject crash = new JSONObject();
                    crash.put(PID, pid);
                    crash.put(TID, tid);
                    crash.put(NAME, name);
                    crash.put(FAULT_ADDRESS, faultAddress);
                    crash.put(SIGNAL, signal);
                    crashes.put(crash);
                } catch (JSONException e) {

                }
            }
        }
        return crashes;
    }
}
