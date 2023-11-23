/*
 * Copyright (C) 2021 The Android Open Source Project
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
import java.util.HashMap;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * This is a collector helper that collects the dumpsys meminfo output for specified services and
 * puts them into files.
 */
public class LyricMemProfilerHelper implements ICollectorHelper<Integer> {

    private static final String TAG = LyricMemProfilerHelper.class.getSimpleName();

    private static final String PID_CMD = "pgrep -f -o ";

    private static final String DUMPSYS_MEMINFO_CMD = "dumpsys meminfo -s ";

    private static final String DMABUF_DUMP_CMD = "dmabuf_dump";

    private static final int MIN_PROFILE_PERIOD_MS = 100;

    String mPidName = "camera.provider@";

    // Extract value of "Native Heap:" and  "TOTAL PSS:" from command: "dumpsys meminfo -s [pid]"
    // example of "dumpsys meminfo -s [pid]":
    // Applications Memory Usage (in Kilobytes):
    // Uptime: 2649336 Realtime: 3041976
    // ** MEMINFO in pid 14612 [android.hardwar] **
    // App Summary
    //                       Pss(KB)                        Rss(KB)
    //                        ------                         ------
    //           Java Heap:        0                              0
    //         Native Heap:   377584                         377584
    //                Code:    79008                         117044
    //               Stack:     3364                           3364
    //            Graphics:    47672                          47672
    //       Private Other:    37188
    //              System:     5307
    //             Unknown:                                   39136
    //
    //           TOTAL PSS:   550123            TOTAL RSS:   584800      TOTAL      SWAP (KB):
    //  0
    //
    // Above string example will be remove "\n" first  and then extracted
    // "377584" right after "Native Heap" and "550123" right after "TOTAL PSS"
    // by following Regexes:
    private static final Pattern METRIC_MEMINFO_PATTERN =
            Pattern.compile(".+Native Heap:\\s*(\\d+)\\s*.+TOTAL PSS:\\s*(\\d+)\\s*.+");

    // extrace value after "PROCESS TOTAL" in camera provider section
    // of string from command "dmabuf_dump"
    // Example:
    //          PROCESS TOTAL          1752 kB          1873 kB
    // Above string example will be extracted 1752 as group(1) and 1832 as group(2)
    private static final Pattern METRIC_DMABUF_PSS_PATTERN =
            Pattern.compile("\\s*PROCESS TOTAL\\s*(\\d+)\\s*kB\\s*(\\d+)\\s*kB");

    // Folling Regexes is for removing "\n" in string
    private static final Pattern REMOVE_CR_PATTERN = Pattern.compile("\n");

    // This Regexes is to match string format as:
    //   provider@2.7-se:[pid of provider]
    //
    // Use above pattern to find data section of camera provider
    // of output string from command "dmabuf_dump"
    private Pattern mDmabufProcPattern;

    private UiDevice mUiDevice;

    private String mCameraProviderPid = "";

    private int mProfilePeriodMs = 0;

    private Timer mTimer;

    private static class MemInfo {
        int mNativeHeap;
        int mTotalPss;

        public MemInfo(int nativeHeap, int totalPss) {
            mNativeHeap = nativeHeap;
            mTotalPss = totalPss;
        }
    }

    private int mMaxNativeHeap;

    private int mMaxTotalPss;

    private int mMaxDmabuf;

    @VisibleForTesting
    protected UiDevice initUiDevice() {
        return UiDevice.getInstance(InstrumentationRegistry.getInstrumentation());
    }

    @Override
    public boolean startCollecting() {
        if (null == mUiDevice) {
            mUiDevice = initUiDevice();
        }
        getCameraProviderPid();

        // This Regexes is to match string format as:
        //   provider@2.7-se:[pid of provider]
        //
        // Use following pattern to find data section of camera provider
        // of output string from command "dmabuf_dump"
        mDmabufProcPattern = Pattern.compile("\\s*provider@.*:" + mCameraProviderPid + "\\s*");

        // To avoid frequnce of polling memory data too high and interference test case
        // Set minimum polling period to MIN_PROFILE_PERIOD_MS (100), period profile only
        // enable when MIN_PROFILE_PERIOD_MS <= configured polling period
        if (MIN_PROFILE_PERIOD_MS <= mProfilePeriodMs) {
            if (null == mTimer) {
                mTimer = new Timer();
                mTimer.schedule(
                        new TimerTask() {
                            @Override
                            public void run() {
                                MemInfo memInfo = processMemInfo(getMemInfoString());
                                int dmabuf = processDmabufDump(getDmabufDumpString());
                                mMaxNativeHeap = Math.max(mMaxNativeHeap, memInfo.mNativeHeap);
                                mMaxTotalPss = Math.max(mMaxTotalPss, memInfo.mTotalPss);
                                mMaxDmabuf = Math.max(mMaxDmabuf, dmabuf);
                            }
                        },
                        MIN_PROFILE_PERIOD_MS,
                        mProfilePeriodMs);
            }
        }
        return true;
    }

    public void setProfilePeriodMs(int periodMs) {
        mProfilePeriodMs = periodMs;
    }

    public void setProfilePidName(String pidName) {
        mPidName = pidName;
    }

    @Override
    public Map<String, Integer> getMetrics() {
        String memInfoString = getMemInfoString();
        String dmabufDumpString = getDmabufDumpString();
        Map<String, Integer> metrics = processOutput(memInfoString, dmabufDumpString);
        if (MIN_PROFILE_PERIOD_MS <= mProfilePeriodMs) {
            metrics.put("maxNativeHeap", mMaxNativeHeap);
            metrics.put("maxTotalPss", mMaxTotalPss);
            metrics.put("maxDmabuf", mMaxDmabuf);
        }
        return metrics;
    }

    @Override
    public boolean stopCollecting() {
        if (null != mTimer) {
            mTimer.cancel();
        }
        return true;
    }

    private MemInfo processMemInfo(String memInfoString) {
        int nativeHeap = 0, totalPss = 0;
        Matcher matcher =
                METRIC_MEMINFO_PATTERN.matcher(
                        REMOVE_CR_PATTERN.matcher(memInfoString).replaceAll(""));
        if (matcher.find()) {
            nativeHeap = Integer.parseInt(matcher.group(1));
            totalPss = Integer.parseInt(matcher.group(2));
        } else {
            Log.e(TAG, "Failed to collect Lyric Native Heap or TOTAL PSS metrics.");
        }
        return new MemInfo(nativeHeap, totalPss);
    }

    private int processDmabufDump(String dmabufDumpString) {
        int dmabuf = 0;
        Matcher matcher;
        boolean procMatched = false;
        for (String line : dmabufDumpString.split("\n")) {
            if (procMatched) {
                matcher = METRIC_DMABUF_PSS_PATTERN.matcher(line);
                if (matcher.find()) {
                    dmabuf = Integer.parseInt(matcher.group(2));
                    break;
                }
            } else {
                matcher = mDmabufProcPattern.matcher(line);
                if (matcher.find()) {
                    procMatched = true;
                }
            }
        }
        return dmabuf;
    }

    @VisibleForTesting
    Map<String, Integer> processOutput(String memInfoString, String dmabufDumpString) {
        Map<String, Integer> metrics = new HashMap<>();
        MemInfo memInfo = processMemInfo(memInfoString);
        int dmabuf = processDmabufDump(dmabufDumpString);
        if (0 < memInfo.mNativeHeap) metrics.put("nativeHeap", memInfo.mNativeHeap);
        if (0 < memInfo.mTotalPss) metrics.put("totalPss", memInfo.mTotalPss);
        if (0 < dmabuf) metrics.put("dmabuf", dmabuf);
        return metrics;
    }

    @VisibleForTesting
    public String getCameraProviderPid() {
        try {
            mCameraProviderPid = mUiDevice.executeShellCommand(PID_CMD + mPidName).trim();
        } catch (IOException e) {
            Log.e(TAG, "Failed to get camera provider PID");
            mCameraProviderPid = "";
        }
        return mCameraProviderPid;
    }

    @VisibleForTesting
    public String getMemInfoString() {
        if (!mCameraProviderPid.isEmpty()) {
            try {
                String cmdString = DUMPSYS_MEMINFO_CMD + mCameraProviderPid;
                return mUiDevice.executeShellCommand(cmdString).trim();
            } catch (IOException e) {
                Log.e(TAG, "Failed to get Mem info string ");
            }
        }
        return "";
    }

    @VisibleForTesting
    public String getDmabufDumpString() {
        if (!mCameraProviderPid.isEmpty()) {
            try {
                final int minDmabufStringLen = 100;
                final int maxDmabufRetryCount = 3;
                String dmabufString;
                for (int retryCount = 0; retryCount < maxDmabufRetryCount; retryCount++) {
                    dmabufString = mUiDevice.executeShellCommand(DMABUF_DUMP_CMD).trim();
                    // "dmabuf_dump" may not get dmabuf size information but get following string:
                    // "debugfs entry for dmabuf not available, using /proc/<pid>/fdinfo instead"
                    // Here use string length to detected above condition and retry.
                    // Normal dmabuf size string should larger than 100 characters.
                    if (minDmabufStringLen < dmabufString.length()) {
                        return dmabufString;
                    }
                    Log.w(TAG, "dmabuf_dump return abnormal:" + dmabufString + ",retry");
                }
            } catch (IOException e) {
                Log.e(TAG, "Failed to get DMA buf dump string");
            }
        }
        return "";
    }
}
