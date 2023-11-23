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

package com.android.cobalt;

import static com.google.common.truth.Truth.assertThat;

import org.junit.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

public class NoOpCobaltLoggerTest {
    private static ExecutorService sExecutor = Executors.newCachedThreadPool();

    // Event attributes for testing.
    private static int sMetricId = 0;
    private static int sReportId = 0;
    private static List<Integer> sEventVector = new ArrayList<Integer>();

    @Test
    public void testNoOpLogOccurrence() throws Exception {
        CobaltLogger logger = new NoOpCobaltLogger(sExecutor);
        Future<Void> log = logger.logOccurrence(sMetricId, sReportId, sEventVector);
        assertThat(log.get()).isNull();
    }
}
