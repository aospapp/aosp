/*
 * Copyright 2022 The Android Open Source Project
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

package com.android.helpers;

import android.util.Log;

import androidx.annotation.VisibleForTesting;
import androidx.test.InstrumentationRegistry;
import androidx.test.uiautomator.UiDevice;

import java.io.IOException;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import java.util.HashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** MemLeaksHelper parses unreachable memory from dumpsys meminfo --unreachable <PID>. */
public class MemLeaksHelper implements ICollectorHelper<Long> {
    private static final String TAG = MemLeaksHelper.class.getSimpleName();
    private static final String MEM_NAME_PATTERN = "MEMINFO in pid %d \\[(?<processname>.*)\\]";
    private static final String MEM_LEAKS_PATTERN =
            "(?<bytes>[0-9]+) bytes in (?<allocations>[0-9]+) unreachable allocations";

    @VisibleForTesting public static final String ALL_PROCESS = "ps -A";
    @VisibleForTesting public static final String PROCESS_PID = "ps -p %d";

    @VisibleForTesting
    public static final String DUMPSYS_MEMIFNO = "dumpsys meminfo --unreachable %d";

    @VisibleForTesting public static final String PROC_MEM_BYTES = "proc_unreachable_memory_bytes_";

    @VisibleForTesting
    public static final String PROC_ALLOCATIONS = "proc_unreachable_allocations_";

    private UiDevice mUiDevice;

    @Override
    public boolean startCollecting() {
        mUiDevice = UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
        return true;
    }

    @Override
    public boolean stopCollecting() {
        return true;
    }

    @Override
    public Map<String, Long> getMetrics() {
        // Get all the process PIDs first
        List<Integer> pids = getPids();
        Map<String, Long> results = new HashMap<>();

        if (pids.isEmpty()) {
            Log.e(TAG, "Failed to get all the process PIDs");
            return results;
        }

        for (Integer pid : pids) {
            String dumpOutput;
            try {
                dumpOutput = executeShellCommand(String.format(DUMPSYS_MEMIFNO, pid));
                Log.i(TAG, "dumpsys meminfo --unreachable: " + dumpOutput);
            } catch (IOException ioe) {
                Log.e(TAG, "Failed to run " + String.format(DUMPSYS_MEMIFNO, pid) + ".", ioe);
                continue;
            }

            Pattern patternName =
                    Pattern.compile(
                            String.format(MEM_NAME_PATTERN, pid),
                            Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);
            Pattern patternLeak =
                    Pattern.compile(
                            MEM_LEAKS_PATTERN, Pattern.CASE_INSENSITIVE | Pattern.MULTILINE);

            Matcher matcherName = patternName.matcher(dumpOutput);
            Matcher matcherLeak = patternLeak.matcher(dumpOutput);

            if (matcherName.find() && matcherLeak.find()) {
                results.put(
                        PROC_MEM_BYTES + matcherName.group(1),
                        Long.parseLong(matcherLeak.group(1)));
                results.put(
                        PROC_ALLOCATIONS + matcherName.group(1),
                        Long.parseLong(matcherLeak.group(2)));
            } else {
                if (matcherName.find()) {
                    results.put(PROC_MEM_BYTES + matcherName.group(1), 0L);
                    results.put(PROC_ALLOCATIONS + matcherName.group(1), 0L);
                } else {
                    // Get process name by pid
                    String processName = getProcessNameByPID(pid);
                    if (processName.length() == 0) continue;
                    results.put(PROC_MEM_BYTES + processName, 0L);
                    results.put(PROC_ALLOCATIONS + processName, 0L);
                }
            }
        }

        return results;
    }

    /**
     * Get pid of all processes.
     *
     * @return pid of all processes
     */
    private List<Integer> getPids() {
        try {
            String pidOutput = executeShellCommand(ALL_PROCESS);

            // Sample output for the process info
            // Sample command : "ps -A"
            // Sample output :
            // system  4533 410 13715708 78536 do_freezer_trap 0 S com.android.keychain

            String[] lines = pidOutput.split(System.lineSeparator());

            List<Integer> pids = new ArrayList<>();
            for (String pid : lines) {
                String pidSplit = pid.split("\\s+")[1];
                // Skip the first (i.e header) line from "ps -A" output.
                if (pidSplit.equalsIgnoreCase("PID")) {
                    continue;
                }
                pids.add(Integer.parseInt(pidSplit));
            }
            return pids;
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to get pid of all processes.", ioe);
            return new ArrayList<>();
        }
    }

    /* Execute a shell command and return its output. */
    @VisibleForTesting
    public String executeShellCommand(String command) throws IOException {
        return mUiDevice.executeShellCommand(command);
    }

    /* Execute a shell command and return its output. */
    private String getProcessNameByPID(int pid) {
        try {
            String processInfoStr = executeShellCommand(String.format(PROCESS_PID, pid));
            String[] processInfoArr = processInfoStr.split("\\s+");
            String processName = processInfoArr[processInfoArr.length - 1].replace("\n", "").trim();
            if (processName.startsWith("[") && processName.endsWith("]")) processName = "";
            return processName;
        } catch (IOException ioe) {
            Log.e(TAG, "Failed to get process name by pid.", ioe);
            return "";
        }
    }
}
