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

package com.android.tradefed.util;

import static com.google.common.base.Preconditions.checkState;

import com.android.tradefed.device.DeviceNotAvailableException;
import com.android.tradefed.device.ITestDevice;
import com.android.tradefed.log.LogUtil.CLog;

import java.util.List;
import java.util.StringJoiner;

/**
 * A utility class that clears native coverage measurements and forces a flush of native coverage
 * data from processes on the device.
 */
public final class NativeCodeCoverageFlusher {

    private static final String COVERAGE_FLUSH_COMMAND_FORMAT = "kill -37 %s";
    private static final String CLEAR_NATIVE_COVERAGE_FILES = "rm -rf /data/misc/trace/*";

    private final ITestDevice mDevice;

    public NativeCodeCoverageFlusher(ITestDevice device) {
        mDevice = device;
    }

    /**
     * Clears coverage measurements from disk on the device. Device must be in adb root.
     *
     * @throws DeviceNotAvailableException
     */
    public void clearCoverageMeasurements() throws DeviceNotAvailableException {
        checkState(mDevice.isAdbRoot(), "adb root is required to clear coverage files.");
        mDevice.executeShellCommand(CLEAR_NATIVE_COVERAGE_FILES);
    }

    /**
     * Forces a flush of native coverage data from processes running on the device. Device must be
     * in adb root.
     *
     * @param processNames the name of processes to target for flushing; if empty, flushes from all
     *     running native processes on the device.
     * @throws DeviceNotAvailableException
     */
    public void forceCoverageFlush(List<String> processNames) throws DeviceNotAvailableException {
        checkState(mDevice.isAdbRoot(), "adb root is required to flush native coverage data.");

        if ((processNames == null) || processNames.isEmpty()) {
            // Use the special pid -1 to trigger a coverage flush of all running processes.
            mDevice.executeShellCommand(String.format(COVERAGE_FLUSH_COMMAND_FORMAT, "-1"));
        } else {
            // Look up the pid of the processes to send them the coverage flush signal.
            StringJoiner pidString = new StringJoiner(" ");
            for (String processName : processNames) {
                String pid = mDevice.getProcessPid(processName);
                if (pid == null) {
                    CLog.w("Did not find pid for process \"%s\".", processName);
                } else {
                    pidString.add(pid);
                }
            }

            if (pidString.length() > 0) {
                mDevice.executeShellCommand(
                        String.format(COVERAGE_FLUSH_COMMAND_FORMAT, pidString.toString()));
            }
        }
    }
}
