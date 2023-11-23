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

import android.annotation.NonNull;
import android.util.Log;

import com.google.common.util.concurrent.Futures;
import com.google.common.util.concurrent.ListenableFuture;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;

/** Cobalt logger implementation which asynchronously logs it received an event */
public class NoOpCobaltLogger implements CobaltLogger {
    private static final String NOOP_TAG = "cobalt.noop";

    private final ExecutorService mExecutorService;

    public NoOpCobaltLogger(@NonNull ExecutorService executor) {
        Objects.requireNonNull(executor);
        this.mExecutorService = executor;
    }

    /**
     * Writes a log message from an asynchronous taskindicating a metric occurred.
     *
     * @param metricId registered ID of the OCCURRENCE metric which the event occurred for
     * @param count number of occurrences
     * @param eventVector registered events codes of the event which occurred
     * @return A ListenableFuture for the logging operation.
     */
    @Override
    public ListenableFuture<Void> logOccurrence(
            long metricId, long count, List<Integer> eventVector) {
        return Futures.submit(
                () -> {
                    if (Log.isLoggable(NOOP_TAG, Log.INFO)) {
                        Log.i(
                                NOOP_TAG,
                                String.format(
                                        Locale.US,
                                        "Received OCCURRENCE event for metric id: %s",
                                        metricId));
                    }
                },
                mExecutorService);
    }
}
