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

package com.android.rkpdapp;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * This class provides a global thread pool to RKPD app.
 */
public class ThreadPool {
    public static final int NUMBER_OF_THREADS = Runtime.getRuntime().availableProcessors();
    /*
     * This thread pool has a minimum of 0 threads and a maximum of up to the
     * number of processors. If a thread is idle for more than 30 seconds, it is
     * terminated. RKPD is idle most of the time. So, this way we can don't keep
     * unused threads around.
     *
     * Each thread has an unbounded queue. This allows RKPD to serve requests
     * asynchronously.
     */
    public static final ExecutorService EXECUTOR = Executors.newFixedThreadPool(NUMBER_OF_THREADS);
}
