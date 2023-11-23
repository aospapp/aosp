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

package com.android.adservices.concurrency;

import static org.junit.Assert.assertTrue;

import android.os.Build;

import com.android.compatibility.common.util.ShellUtils;

import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;

public class AdServicesExecutorTest {

    // Command to kill the adservices process
    public static final String KILL_ADSERVICES_CMD =
            "su 0 killall -9 com.google.android.adservices.api";

    @Before
    public void setup() {
        // TODO(b/265113689) Unsuppress the test for user builds post removing the `su` utility
        Assume.assumeTrue(Build.isDebuggable());
        ShellUtils.runShellCommand(KILL_ADSERVICES_CMD);
    }

    @Test
    public void testCreateLightWeightThreadSuccess() throws Exception {
        String threadName =
                AdServicesExecutors.getLightWeightExecutor()
                        .submit(() -> Thread.currentThread().getName())
                        .get();
        // Expecting 1-19 digits since the thread number is a positive long
        assertTrue(threadName.matches("lightweight-\\d{1,19}$"));
    }

    @Test
    public void testCreateBackgroundThreadSuccess() throws Exception {
        String threadName =
                AdServicesExecutors.getBackgroundExecutor()
                        .submit(() -> Thread.currentThread().getName())
                        .get();
        assertTrue(threadName.matches("background-\\d{1,19}$"));
    }

    @Test
    public void testCreateScheduledThreadSuccess() throws Exception {
        String threadName =
                AdServicesExecutors.getScheduler()
                        .submit(() -> Thread.currentThread().getName())
                        .get();
        assertTrue(threadName.matches("scheduled-\\d{1,19}$"));
    }

    @Test
    public void testCreateBlockingThreadSuccess() throws Exception {
        String threadName =
                AdServicesExecutors.getBlockingExecutor()
                        .submit(() -> Thread.currentThread().getName())
                        .get();
        assertTrue(threadName.matches("blocking-\\d{1,19}$"));
    }
}
